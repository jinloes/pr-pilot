package com.jinloes.prpilot.review;

import com.github.copilot.CopilotClient;
import com.github.copilot.CopilotSession;
import com.github.copilot.generated.AssistantMessageDeltaEvent;
import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.generated.SessionErrorEvent;
import com.github.copilot.generated.ToolExecutionStartEvent;
import com.github.copilot.rpc.CopilotClientMode;
import com.github.copilot.rpc.CopilotClientOptions;
import com.github.copilot.rpc.McpServerConfig;
import com.github.copilot.rpc.MessageOptions;
import com.github.copilot.rpc.PermissionHandler;
import com.github.copilot.rpc.PermissionRequestResult;
import com.github.copilot.rpc.SessionConfig;
import com.jinloes.prpilot.model.ChatMessage;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.ReviewResult;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives the GitHub Copilot runtime via the official Java SDK. Mirrors the synchronous API of
 * {@link ClaudeService} so callers can swap providers without changing threading.
 *
 * <p>The SDK still requires the local {@code copilot} CLI/runtime, but it gives us typed session
 * events instead of a hand-rolled JSONL parser. We keep the same outward behavior: stream text
 * deltas to the UI, surface tool names as status updates, and parse the final assistant message as
 * review JSON.
 */
public class CopilotService {

    private static final Logger log = LoggerFactory.getLogger(CopilotService.class);

    private static final String STATUS_GENERATING = "Generating review…";
    private static final String STATUS_PARSING = "Parsing review…";
    private static final long REQUEST_TIMEOUT_MS = 30L * 60L * 1000L;
    private static final long SDK_BOOT_TIMEOUT_MS = 60L * 1000L;

    /**
     * Default for PR review work: {@code high} trades some latency for materially deeper reasoning,
     * which catches more real correctness/security issues while still following the strict JSON
     * schema. Exposed as a constant so the IntelliJ adapter and VS Code extension can mirror it.
     */
    public static final String DEFAULT_REASONING_EFFORT = "high";

    private final File workingDir;
    private final File projectDir;
    private RuntimeFactory runtimeFactory = new SdkRuntimeFactory();
    private final AtomicReference<ActiveRun> activeRun = new AtomicReference<>();
    private final CancellationToken cancellationToken;

    public CopilotService() {
        this(null, new CancellationToken());
    }

    public CopilotService(String projectDir) {
        this(projectDir, new CancellationToken());
    }

    public CopilotService(String projectDir, CancellationToken cancellationToken) {
        this.projectDir = StringUtils.isNotBlank(projectDir) ? new File(projectDir) : null;
        this.workingDir =
                this.projectDir != null
                        ? this.projectDir
                        : new File(System.getProperty("user.home", "/"));
        this.cancellationToken = Objects.requireNonNull(cancellationToken);
    }

    CopilotService(String projectDir, RuntimeFactory runtimeFactory) {
        this(projectDir, runtimeFactory, new CancellationToken());
    }

    CopilotService(
            String projectDir, RuntimeFactory runtimeFactory, CancellationToken cancellationToken) {
        this(projectDir, cancellationToken);
        this.runtimeFactory = runtimeFactory;
    }

    /**
     * @param inheritMcp when true, the review inherits MCP servers from the user's <em>trusted</em>
     *     Copilot config ({@code <configDir>/mcp-config.json}, default {@code ~/.copilot}) via
     *     {@link CopilotMcpConfig}. The SDK's on-disk config discovery is never enabled, so the
     *     untrusted PR-branch worktree's repo-local {@code .mcp.json} is deliberately ignored.
     * @param configDir optional override of the Copilot config directory; blank uses the CLI
     *     default ({@code ~/.copilot}).
     */
    public ReviewResult reviewPR(
            PRReviewRequest request,
            String model,
            String effort,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk,
            boolean inheritMcp,
            String configDir,
            boolean selfCritique)
            throws IOException, InterruptedException {
        ReviewPassResult pass =
                reviewPass(request, model, effort, onStatus, onChunk, inheritMcp, configDir);
        ReviewResult draft = pass.review();
        if (!selfCritique) {
            return CiFindingSuppressor.suppress(draft, request.getCiAnnotations());
        }
        onStatus.accept(ClaudeService.STATUS_REFINING);
        try {
            return CiFindingSuppressor.suppress(
                    runReview(
                            ClaudeService.buildCritiquePrompt(request, draft),
                            model,
                            effort,
                            inheritMcp,
                            configDir,
                            onStatus,
                            onChunk),
                    request.getCiAnnotations());
        } catch (InterruptedException interrupted) {
            throw interrupted;
        } catch (Exception e) {
            log.warn("Copilot self-critique pass failed; keeping first-pass review", e);
            return CiFindingSuppressor.suppress(draft, request.getCiAnnotations());
        }
    }

    ReviewPassResult reviewPass(
            PRReviewRequest request,
            String model,
            String effort,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk,
            boolean inheritMcp,
            String configDir)
            throws IOException, InterruptedException {
        InspectionManifest manifest = InspectionManifest.fromDiff(request.getDiff());
        String prompt = ClaudeService.buildPrompt(request, manifest);
        log.info(
                "Copilot review prompt: {} chars — diff {} chars, CI {} chars, commits {} chars",
                prompt.length(),
                StringUtils.length(request.getDiff()),
                StringUtils.length(request.getCiStatus()),
                StringUtils.length(request.getCommits()));
        onStatus.accept(STATUS_GENERATING);
        String raw =
                runSession(
                        prompt,
                        model,
                        effort,
                        inheritMcp,
                        configDir,
                        true,
                        REQUEST_TIMEOUT_MS,
                        onStatus,
                        onChunk);
        if (StringUtils.isBlank(raw)) {
            throw new IOException("copilot produced no output.");
        }
        onStatus.accept(STATUS_PARSING);
        try {
            return ReviewPassParser.parse(
                    raw, manifest, workingDir, ClaudeService.maxComments(request));
        } catch (Exception parseEx) {
            log.warn("Failed to parse Copilot review JSON (output chars: {})", raw.length());
            throw new IOException("Failed to parse review JSON from Copilot output.", parseEx);
        }
    }

    String completeReviewPrompt(
            String prompt,
            String model,
            String effort,
            boolean inheritMcp,
            String configDir,
            boolean allowReadTools,
            long timeoutMillis,
            Consumer<String> onStatus)
            throws IOException, InterruptedException {
        return runSession(
                prompt,
                model,
                effort,
                inheritMcp,
                configDir,
                allowReadTools,
                timeoutMillis,
                onStatus,
                null);
    }

    /** The PR checkout this service reviews, or null when it was built without one. */
    File projectDir() {
        return projectDir;
    }

    void throwIfCancelled() throws InterruptedException {
        cancellationToken.throwIfCancelled();
    }

    public ReviewResult reviewPR(
            PRReviewRequest request,
            String model,
            String effort,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk,
            boolean inheritMcp,
            String configDir)
            throws IOException, InterruptedException {
        return reviewPR(request, model, effort, onStatus, onChunk, inheritMcp, configDir, false);
    }

    public ReviewResult reviewPR(
            PRReviewRequest request, String model, String effort, Consumer<String> onStatus)
            throws IOException, InterruptedException {
        return reviewPR(request, model, effort, onStatus, null, false, null, false);
    }

    private ReviewResult runReview(
            String prompt,
            String model,
            String effort,
            boolean inheritMcp,
            String configDir,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk)
            throws IOException, InterruptedException {
        onStatus.accept(STATUS_GENERATING);
        String raw =
                runSession(
                        prompt,
                        model,
                        effort,
                        inheritMcp,
                        configDir,
                        true,
                        REQUEST_TIMEOUT_MS,
                        onStatus,
                        onChunk);
        if (StringUtils.isBlank(raw)) {
            throw new IOException("copilot produced no output.");
        }
        onStatus.accept(STATUS_PARSING);
        try {
            return ClaudeService.parseReview(raw);
        } catch (Exception parseEx) {
            log.warn("Failed to parse Copilot review JSON (output chars: {})", raw.length());
            throw new IOException("Failed to parse review JSON from Copilot output.", parseEx);
        }
    }

    public String chat(
            String prContext,
            List<ChatMessage> history,
            String userMessage,
            String effort,
            Consumer<String> onChunk,
            boolean inheritMcp,
            String configDir)
            throws IOException, InterruptedException {
        String prompt = ClaudeService.buildChatPrompt(prContext, history, userMessage);
        return runSession(
                prompt,
                "",
                effort,
                inheritMcp,
                configDir,
                true,
                REQUEST_TIMEOUT_MS,
                ignored -> {},
                (kind, chunk) -> onChunk.accept(chunk));
    }

    public String chat(
            String prContext,
            List<ChatMessage> history,
            String userMessage,
            String effort,
            Consumer<String> onChunk)
            throws IOException, InterruptedException {
        return chat(prContext, history, userMessage, effort, onChunk, false, null);
    }

    /**
     * Sends a pre-built prompt directly to Copilot without wrapping it in {@link
     * ClaudeService#buildChatPrompt}. Use this when the caller has already assembled the full
     * prompt (e.g. via {@link ClaudeService#buildFocusedChatPrompt}) and does not want any
     * additional wrapping.
     */
    public String chatWithPrompt(
            String rawPrompt,
            String effort,
            Consumer<String> onChunk,
            boolean inheritMcp,
            String configDir)
            throws IOException, InterruptedException {
        return runSession(
                rawPrompt,
                "",
                effort,
                inheritMcp,
                configDir,
                true,
                REQUEST_TIMEOUT_MS,
                ignored -> {},
                (kind, chunk) -> onChunk.accept(chunk));
    }

    public String chatWithPrompt(String rawPrompt, String effort, Consumer<String> onChunk)
            throws IOException, InterruptedException {
        return chatWithPrompt(rawPrompt, effort, onChunk, false, null);
    }

    /**
     * Starts a fresh Copilot SDK client + session, forwards text deltas to {@code onChunk} (as
     * {@code "text"} chunks, matching ClaudeService's protocol) and tool names to {@code onStatus},
     * then returns the final assistant message content. If the SDK never delivers a consolidated
     * assistant message, we fall back to the accumulated deltas.
     */
    private String runSession(
            String prompt,
            String model,
            String effort,
            boolean inheritMcp,
            String configDir,
            boolean allowReadTools,
            long timeoutMillis,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk)
            throws IOException, InterruptedException {
        cancellationToken.throwIfCancelled();
        ActiveRun currentRun = null;
        List<Closeable> subscriptions = new ArrayList<>();
        try {
            RuntimeClient client = runtimeFactory.createClient(buildClientRequest());
            currentRun = new ActiveRun(client);
            cancellationToken.throwIfCancelled();
            this.activeRun.set(currentRun);
            if (cancellationToken.isCancelled()) {
                cancelCurrentRequest();
                cancellationToken.throwIfCancelled();
            }

            client.start();
            cancellationToken.throwIfCancelled();
            RuntimeSession session =
                    client.createSession(
                            buildSessionRequest(
                                    model, effort, inheritMcp, configDir, allowReadTools));
            currentRun.attachSession(session);
            cancellationToken.throwIfCancelled();

            StringBuilder deltaBuffer = new StringBuilder();
            AtomicReference<String> finalMessage = new AtomicReference<>();
            AtomicReference<String> sessionError = new AtomicReference<>();

            subscriptions.add(
                    session.onAssistantMessageDelta(
                            delta -> {
                                if (StringUtils.isNotEmpty(delta)) {
                                    deltaBuffer.append(delta);
                                    if (onChunk != null) onChunk.accept("text", delta);
                                }
                            }));
            subscriptions.add(
                    session.onAssistantMessage(
                            content -> {
                                if (StringUtils.isNotBlank(content)) {
                                    finalMessage.set(content);
                                }
                            }));
            subscriptions.add(
                    session.onToolExecutionStart(
                            toolName -> {
                                if (StringUtils.isNotBlank(toolName)) {
                                    onStatus.accept(toolName);
                                }
                            }));
            subscriptions.add(
                    session.onSessionError(
                            message -> {
                                if (StringUtils.isNotBlank(message)) {
                                    sessionError.compareAndSet(null, message);
                                }
                            }));

            String responseMessage = session.sendAndWait(prompt, timeoutMillis);
            cancellationToken.throwIfCancelled();

            String raw =
                    StringUtils.defaultIfBlank(
                            finalMessage.get(),
                            StringUtils.defaultIfBlank(responseMessage, deltaBuffer.toString()));
            if (StringUtils.isBlank(raw) && StringUtils.isNotBlank(sessionError.get())) {
                throw new IOException(sessionError.get());
            }
            return raw;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (Exception e) {
            if (currentRun != null && currentRun.cancelled.get()) {
                throw new InterruptedException("copilot request cancelled");
            }
            throw asIOException(e);
        } finally {
            for (Closeable c : subscriptions) closeQuietly(c);
            this.activeRun.compareAndSet(currentRun, null);
            closeQuietly(currentRun);
        }
    }

    public void cancelCurrentRequest() {
        cancellationToken.cancel();
        ActiveRun run = activeRun.getAndSet(null);
        if (run != null) run.cancel();
    }

    private ClientRequest buildClientRequest() {
        Map<String, String> env = new LinkedHashMap<>(System.getenv());
        String userHome = System.getProperty("user.home", "/");
        env.put("HOME", userHome);
        String existingPath = env.getOrDefault("PATH", "");
        env.put("PATH", BinaryLocator.providerPath(userHome, existingPath));
        return new ClientRequest(findCopilotBinary(), workingDir, env);
    }

    private SessionRequest buildSessionRequest(
            String model,
            String effort,
            boolean inheritMcp,
            String configDir,
            boolean allowReadTools) {
        String trimmedConfigDir = configDir != null ? configDir.trim() : null;
        return new SessionRequest(
                model,
                normalizeReasoningEffort(effort),
                workingDir,
                inheritMcp,
                StringUtils.isNotEmpty(trimmedConfigDir) ? trimmedConfigDir : null,
                allowReadTools);
    }

    private IOException asIOException(Exception ex) {
        Throwable root =
                ex instanceof ExecutionException && ex.getCause() instanceof Exception cause
                        ? cause
                        : ex;
        if (root instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return new IOException("copilot request interrupted", root);
        }
        if (root instanceof IOException ioException) {
            return ioException;
        }
        String message = StringUtils.defaultIfBlank(root.getMessage(), "copilot request failed");
        return new IOException(message, root);
    }

    private void closeQuietly(AutoCloseable closeable) {
        try {
            if (closeable != null) closeable.close();
        } catch (Exception e) {
            // Best effort cleanup only.
        }
    }

    record ClientRequest(String cliPath, File workingDir, Map<String, String> environment) {}

    record SessionRequest(
            String model,
            String effort,
            File workingDir,
            boolean inheritMcp,
            String configDir,
            boolean allowReadTools) {}

    interface RuntimeFactory {
        RuntimeClient createClient(ClientRequest request);
    }

    interface RuntimeClient extends AutoCloseable {
        void start() throws Exception;

        RuntimeSession createSession(SessionRequest request) throws Exception;

        void forceStop();

        @Override
        void close();
    }

    interface RuntimeSession extends AutoCloseable {
        Closeable onAssistantMessageDelta(Consumer<String> listener);

        Closeable onAssistantMessage(Consumer<String> listener);

        Closeable onToolExecutionStart(Consumer<String> listener);

        Closeable onSessionError(Consumer<String> listener);

        String sendAndWait(String prompt, long timeoutMs) throws Exception;

        void abort();

        @Override
        void close();
    }

    static final class SdkRuntimeFactory implements RuntimeFactory {
        @Override
        public RuntimeClient createClient(ClientRequest request) {
            return new SdkRuntimeClient(request);
        }
    }

    static final class SdkRuntimeClient implements RuntimeClient {
        private final ClientRequest request;
        private final CopilotClient client;

        SdkRuntimeClient(ClientRequest request) {
            this.request = request;
            this.client =
                    new CopilotClient(
                            new CopilotClientOptions()
                                    .setCliPath(request.cliPath())
                                    .setCwd(request.workingDir().getAbsolutePath())
                                    .setEnvironment(request.environment())
                                    .setMode(CopilotClientMode.COPILOT_CLI)
                                    .setAutoStart(false));
        }

        @Override
        public void start() throws Exception {
            awaitWithTimeout(client.start(), "runtime startup");
        }

        @Override
        public RuntimeSession createSession(SessionRequest sessionRequest) throws Exception {
            PermissionHandler permissionHandler =
                    (permissionRequest, ctx) ->
                            CompletableFuture.completedFuture(
                                    permissionDecision(
                                            permissionRequest.getKind(),
                                            sessionRequest.inheritMcp(),
                                            sessionRequest.allowReadTools()));
            SessionConfig config =
                    new SessionConfig()
                            .setOnPermissionRequest(permissionHandler)
                            .setStreaming(true)
                            .setWorkingDirectory(sessionRequest.workingDir().getAbsolutePath())
                            .setReasoningEffort(sessionRequest.effort())
                            // Never enable on-disk config discovery: for a review the working
                            // directory is the untrusted PR-branch worktree, so discovery would
                            // load an attacker-controlled repo-local .mcp.json (which can launch
                            // an arbitrary process). Trusted MCP servers are injected explicitly
                            // below from the user's own config dir instead.
                            .setEnableConfigDiscovery(false);
            if (!sessionRequest.allowReadTools()) {
                config.setAvailableTools(List.of());
            }
            if (sessionRequest.inheritMcp()) {
                Map<String, McpServerConfig> trusted =
                        CopilotMcpConfig.loadTrustedServers(sessionRequest.configDir());
                if (!trusted.isEmpty()) {
                    config.setMcpServers(trusted);
                }
            }
            if (StringUtils.isNotBlank(sessionRequest.model())) {
                config.setModel(sessionRequest.model());
            }
            if (StringUtils.isNotBlank(sessionRequest.configDir())) {
                config.setConfigDirectory(sessionRequest.configDir());
            }
            return new SdkRuntimeSession(
                    awaitWithTimeout(client.createSession(config), "session creation"));
        }

        @Override
        public void forceStop() {
            client.forceStop();
        }

        @Override
        public void close() {
            client.close();
        }
    }

    static final class SdkRuntimeSession implements RuntimeSession {
        private final CopilotSession session;

        SdkRuntimeSession(CopilotSession session) {
            this.session = session;
        }

        @Override
        public Closeable onAssistantMessageDelta(Consumer<String> listener) {
            return session.on(
                    AssistantMessageDeltaEvent.class,
                    event ->
                            listener.accept(
                                    event.getData() != null
                                            ? event.getData().deltaContent()
                                            : null));
        }

        @Override
        public Closeable onAssistantMessage(Consumer<String> listener) {
            return session.on(
                    AssistantMessageEvent.class,
                    event ->
                            listener.accept(
                                    event.getData() != null ? event.getData().content() : null));
        }

        @Override
        public Closeable onToolExecutionStart(Consumer<String> listener) {
            return session.on(
                    ToolExecutionStartEvent.class,
                    event ->
                            listener.accept(
                                    event.getData() != null ? event.getData().toolName() : null));
        }

        @Override
        public Closeable onSessionError(Consumer<String> listener) {
            return session.on(
                    SessionErrorEvent.class,
                    event ->
                            listener.accept(
                                    event.getData() != null ? event.getData().message() : null));
        }

        @Override
        public String sendAndWait(String prompt, long timeoutMs) throws Exception {
            return session.sendAndWait(new MessageOptions().setPrompt(prompt), timeoutMs)
                    .get()
                    .getData()
                    .content();
        }

        @Override
        public void abort() {
            session.abort();
        }

        @Override
        public void close() {
            session.close();
        }
    }

    private static final class ActiveRun implements AutoCloseable {
        private final RuntimeClient client;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicReference<RuntimeSession> sessionRef = new AtomicReference<>();

        private ActiveRun(RuntimeClient client) {
            this.client = client;
        }

        void attachSession(RuntimeSession session) {
            sessionRef.set(session);
            if (cancelled.get()) {
                try {
                    session.abort();
                } catch (Exception e) {
                    // Best effort only.
                }
            }
        }

        void cancel() {
            cancelled.set(true);
            try {
                RuntimeSession session = sessionRef.get();
                if (session != null) session.abort();
            } catch (Exception e) {
                // Best effort only.
            }
            try {
                client.forceStop();
            } catch (Exception e) {
                // Best effort only.
            }
        }

        @Override
        public void close() {
            try {
                RuntimeSession session = sessionRef.getAndSet(null);
                if (session != null) session.close();
            } finally {
                client.close();
            }
        }
    }

    static PermissionRequestResult permissionDecision(String kind, boolean allowMcp) {
        return permissionDecision(kind, allowMcp, true);
    }

    static PermissionRequestResult permissionDecision(
            String kind, boolean allowMcp, boolean allowReadTools) {
        if (allowReadTools && "read".equals(kind)) {
            return PermissionRequestResult.approveOnce();
        }
        if (allowMcp && "mcp".equals(kind)) {
            return PermissionRequestResult.approveOnce();
        }
        return PermissionRequestResult.reject(
                "PR Pilot reviews allow read-only file access only; write, shell, and network tools"
                        + " are disabled.");
    }

    static String normalizeReasoningEffort(String effort) {
        String normalized =
                effort != null ? effort.trim().toLowerCase(java.util.Locale.ROOT) : null;
        if (normalized == null) return DEFAULT_REASONING_EFFORT;
        return switch (normalized) {
            case "low", "medium", "high", "xhigh" -> normalized;
            case "none" -> "low";
            case "max" -> "xhigh";
            default -> DEFAULT_REASONING_EFFORT;
        };
    }

    static <T> T awaitWithTimeout(Future<T> future, String operation) throws IOException {
        try {
            return future.get(SDK_BOOT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            throw new IOException(
                    "copilot "
                            + operation
                            + " timed out after "
                            + (SDK_BOOT_TIMEOUT_MS / 1000)
                            + "s",
                    timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("copilot " + operation + " failed", interrupted);
        } catch (ExecutionException execution) {
            throw new IOException("copilot " + operation + " failed", execution);
        }
    }

    public static String findCopilotBinary() {
        return BinaryLocator.findBinary("copilot", copilotBinaryCandidates());
    }

    /** Proactive preflight: true when the {@code copilot} CLI is resolvable without spawning it. */
    public static boolean isBinaryAvailable() {
        return BinaryLocator.isBinaryAvailable("copilot", copilotBinaryCandidates());
    }

    private static List<String> copilotBinaryCandidates() {
        String home = System.getProperty("user.home", "");
        return List.of(
                home + "/.local/bin/copilot",
                home + "/.npm-global/bin/copilot",
                "/usr/local/bin/copilot",
                "/opt/homebrew/bin/copilot",
                "/usr/bin/copilot");
    }
}
