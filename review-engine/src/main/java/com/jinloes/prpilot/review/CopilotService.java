package com.jinloes.prpilot.review;

import com.jinloes.prpilot.model.ChatMessage;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.ReviewResult;
import com.jinloes.prpilot.review.CopilotSdkRuntime.ClientRequest;
import com.jinloes.prpilot.review.CopilotSdkRuntime.RuntimeClient;
import com.jinloes.prpilot.review.CopilotSdkRuntime.RuntimeFactory;
import com.jinloes.prpilot.review.CopilotSdkRuntime.RuntimeSession;
import com.jinloes.prpilot.review.CopilotSdkRuntime.SdkRuntimeFactory;
import com.jinloes.prpilot.review.CopilotSdkRuntime.SessionRequest;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
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
 * review JSON. The SDK adapter, and the runtime seam that tests fake, live in {@link
 * CopilotSdkRuntime}.
 */
public class CopilotService {

    private static final Logger log = LoggerFactory.getLogger(CopilotService.class);

    private static final String STATUS_GENERATING = "Generating review…";
    private static final String STATUS_PARSING = "Parsing review…";
    private static final long REQUEST_TIMEOUT_MS = 30L * 60L * 1000L;

    /**
     * Default for PR review work: {@code high} trades some latency for materially deeper reasoning,
     * which catches more real correctness/security issues while still following the strict JSON
     * schema. Exposed as a constant so the IntelliJ adapter and VS Code extension can mirror it.
     */
    public static final String DEFAULT_REASONING_EFFORT = "high";

    private final File workingDir;
    private final File projectDir;
    private RuntimeFactory runtimeFactory = new SdkRuntimeFactory();

    /** Runs in flight; several run at once during the rules pass. */
    private final Set<ActiveRun> activeRuns = ConcurrentHashMap.newKeySet();

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
                            ReviewCritiquePrompts.buildCritiquePrompt(request, draft),
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
        String prompt = ReviewPrompts.buildPrompt(request, manifest);
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
                    raw, manifest, workingDir, ReviewResultParser.maxComments(request));
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
            return ReviewResultParser.parseReview(raw);
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
        String prompt = ReviewChatPrompts.buildChatPrompt(prContext, history, userMessage);
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
     * ReviewChatPrompts#buildChatPrompt}. Use this when the caller has already assembled the full
     * prompt (e.g. via {@link ReviewChatPrompts#buildFocusedChatPrompt}) and does not want any
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
            this.activeRuns.add(currentRun);
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
            if (currentRun != null) this.activeRuns.remove(currentRun);
            closeQuietly(currentRun);
        }
    }

    public void cancelCurrentRequest() {
        cancellationToken.cancel();
        for (ActiveRun run : activeRuns) {
            if (activeRuns.remove(run)) run.cancel();
        }
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
