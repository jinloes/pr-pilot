package com.jinloes.prpilot.review;

import com.microsoft.copilot.CopilotClient;
import com.microsoft.copilot.CopilotSession;
import com.microsoft.copilot.SystemMessageMode;
import com.microsoft.copilot.generated.AssistantMessageDeltaEvent;
import com.microsoft.copilot.generated.AssistantMessageEvent;
import com.microsoft.copilot.generated.SessionErrorEvent;
import com.microsoft.copilot.generated.ToolExecutionStartEvent;
import com.microsoft.copilot.rpc.BuiltInTools;
import com.microsoft.copilot.rpc.CopilotClientMode;
import com.microsoft.copilot.rpc.CopilotClientOptions;
import com.microsoft.copilot.rpc.CreateSessionInstructionOptions;
import com.microsoft.copilot.rpc.CreateSessionOptions;
import com.microsoft.copilot.rpc.InfiniteSessionConfig;
import com.microsoft.copilot.rpc.McpServerConfig;
import com.microsoft.copilot.rpc.MessageOptions;
import com.microsoft.copilot.rpc.PermissionHandler;
import com.microsoft.copilot.rpc.PermissionRequestResult;
import com.microsoft.copilot.rpc.RuntimeConnection;
import com.microsoft.copilot.rpc.SessionAgentOptions;
import com.microsoft.copilot.rpc.SessionEventOptions;
import com.microsoft.copilot.rpc.SessionHookOptions;
import com.microsoft.copilot.rpc.SessionInferenceOptions;
import com.microsoft.copilot.rpc.SessionMcpOptions;
import com.microsoft.copilot.rpc.SessionPermissionOptions;
import com.microsoft.copilot.rpc.SessionPluginOptions;
import com.microsoft.copilot.rpc.SessionSkillOptions;
import com.microsoft.copilot.rpc.SessionStateOptions;
import com.microsoft.copilot.rpc.SessionToolOptions;
import com.microsoft.copilot.rpc.SystemMessageConfig;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;

/**
 * Copilot runtime seam for {@link CopilotService}: the client and session requests, the runtime
 * interfaces tests fake, and their implementations over the official Copilot Java SDK (which still
 * launches the local {@code copilot} CLI). Permissions are deny-by-default: {@code read} tools are
 * approved unless the session is tool-free, {@code mcp} tools only when trusted MCP servers are
 * inherited or the host's code-intelligence server is attached, and everything else is rejected.
 */
final class CopilotSdkRuntime {

    private static final long SDK_BOOT_TIMEOUT_MS = 60L * 1000L;

    /**
     * Built-in read-only file tools exposed when MCP is off (matching {@code
     * ClaudeService.READ_ONLY_TOOLS}). Passing an explicit allowlist prevents installed Copilot
     * plugins, {@code ~/.copilot/mcp-config.json} servers, shell tools, and subagents from leaking
     * into review sessions.
     */
    static final List<String> READ_ONLY_TOOLS = List.of("view", "grep", "glob");

    /**
     * Built-in interactive, subagent, skill, shell, and mutating tools excluded when MCP servers
     * are enabled (where an open {@code availableTools} allowlist is needed so MCP server tools
     * remain visible).
     */
    static final List<String> EXCLUDED_NON_REVIEW_TOOLS = buildExcludedTools();

    /**
     * Maximum approved tool calls in one Copilot session (matching {@code
     * ClaudeService.REVIEW_MAX_TURNS}). Beyond this budget, further tool requests are rejected with
     * an instruction to emit the final response from context already gathered.
     */
    static final int MAX_SESSION_TOOL_CALLS = 40;

    /**
     * Separate assistant turns that may still attempt tools after budget exhaustion before the
     * session is aborted as a wedged loop. Counting turns rather than raw parallel calls ensures a
     * model that batches 10-20 parallel reads in one turn is never aborted before seeing the budget
     * rejection and emitting its JSON on the next turn.
     */
    static final int MAX_POST_BUDGET_TOOL_TURNS = 3;

    /** Safety cap for a single burst of overflow calls with no intervening turn boundary. */
    static final int MAX_SINGLE_TURN_OVERFLOW = 50;

    /**
     * Replaces Copilot CLI's ~13 KB interactive coding-agent system prompt (which instructs some
     * models to emit progress updates, cap replies at 100 words, or "take as many turns as needed")
     * with focused read-only PR analysis tool discipline.
     */
    static final String READ_ONLY_SYSTEM_MESSAGE =
            """
            You are a non-interactive pull request review and code analysis engine.
            Follow the user prompt's instructions and output format strictly; never emit \
            conversational progress updates or narration before your response.
            Tool discipline:
            - Batch all independent grep/rg, glob, and view calls in parallel in a single turn.
            - For grep/rg, always pass output_mode: "content", "-n": true, "-C": 5 (or "-A"/"-B"), \
            and narrow with paths or glob so one search returns line numbers and surrounding code \
            without opening every matching file.
            - For view, prefer view_range around the relevant lines instead of reading whole \
            files unless the prompt requires the full file (such as a changed .proto schema).
            - Do not search for or re-read repository guidance .md files already supplied in \
            <repo_guidelines>.
            - Stop calling tools as soon as candidate findings are verified or ruled out; 1-3 \
            focused tool waves are sufficient for most pull requests.""";

    static final String TOOL_FREE_SYSTEM_MESSAGE =
            "You are a non-interactive pull request review engine. Follow the user prompt's"
                    + " instructions and output format strictly, without conversational preamble.";

    static final String TOOL_BUDGET_REJECT_MESSAGE =
            "Investigation tool budget reached ("
                    + MAX_SESSION_TOOL_CALLS
                    + " calls). Stop calling tools and output the final response now following the"
                    + " required format.";

    private static List<String> buildExcludedTools() {
        List<String> excluded = new ArrayList<>(BuiltInTools.ISOLATED);
        excluded.addAll(
                List.of(
                        "bash",
                        "shell",
                        "powershell",
                        "edit",
                        "create",
                        "write",
                        "str_replace_editor",
                        "sql",
                        "web_fetch"));
        return Collections.unmodifiableList(excluded);
    }

    private CopilotSdkRuntime() {}

    record ClientRequest(String cliPath, File workingDir, Map<String, String> environment) {}

    record SessionRequest(
            String model,
            String effort,
            File workingDir,
            boolean inheritMcp,
            String configDir,
            boolean allowReadTools,
            HostCodeIntelligence hostCodeIntelligence) {
        SessionRequest(
                String model,
                String effort,
                File workingDir,
                boolean inheritMcp,
                String configDir,
                boolean allowReadTools) {
            this(model, effort, workingDir, inheritMcp, configDir, allowReadTools, null);
        }

        /** Host code intelligence is a tool, so tool-free sessions never attach it. */
        boolean attachesHostCodeIntelligence() {
            return allowReadTools && hostCodeIntelligence != null;
        }

        boolean allowsMcp() {
            return inheritMcp || attachesHostCodeIntelligence();
        }
    }

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
                                    // Keep the runtime in a spawned CLI subprocess so a native
                                    // runtime fault can never crash the host IDE or sidecar JVM.
                                    .setRuntime(
                                            RuntimeConnection.forSpawnedStdio(request.cliPath())
                                                    .setWorkingDirectory(
                                                            request.workingDir().getAbsolutePath())
                                                    .setEnvironment(request.environment()))
                                    .setMode(CopilotClientMode.CODING_AGENT)
                                    .setAutoStart(false));
        }

        @Override
        public void start() throws Exception {
            awaitWithTimeout(client.start(), "runtime startup");
        }

        @Override
        public RuntimeSession createSession(SessionRequest sessionRequest) throws Exception {
            AtomicReference<CopilotSession> liveSession = new AtomicReference<>();
            ToolBudget toolBudget = new ToolBudget();
            CreateSessionOptions config =
                    buildSessionConfig(
                            sessionRequest,
                            toolBudget,
                            () -> {
                                CopilotSession active = liveSession.get();
                                if (active != null) active.abort();
                            });
            CopilotSession created =
                    awaitWithTimeout(client.createSession(config), "session creation");
            liveSession.set(created);
            Closeable turnTracker =
                    created.on(
                            AssistantMessageEvent.class,
                            ignored -> toolBudget.onAssistantTurnBoundary());
            return new SdkRuntimeSession(created, turnTracker);
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
        private final Closeable internalSubscription;

        SdkRuntimeSession(CopilotSession session, Closeable internalSubscription) {
            this.session = session;
            this.internalSubscription = internalSubscription;
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
            AssistantMessageEvent event =
                    session.sendAndWait(new MessageOptions().setPrompt(prompt), timeoutMs).get();
            return event != null && event.getData() != null ? event.getData().content() : null;
        }

        @Override
        public void abort() {
            session.abort();
        }

        @Override
        public void close() {
            try {
                if (internalSubscription != null) internalSubscription.close();
            } catch (IOException ignored) {
                // Best-effort cleanup before closing session.
            }
            session.close();
        }
    }

    /**
     * Tracks per-session tool calls and post-budget assistant turns so parallel tool batches inside
     * a single turn receive budget rejections cleanly without aborting the session before the model
     * gets a turn to emit its final JSON.
     */
    static final class ToolBudget {
        private int attemptedCalls;
        private boolean exhausted;
        private boolean awaitingNextTurn;
        private int postBudgetToolTurns;
        private int singleBurstOverflow;

        record Decision(boolean approved, boolean runawayAbort) {}

        synchronized Decision recordToolAttempt() {
            attemptedCalls++;
            if (attemptedCalls <= MAX_SESSION_TOOL_CALLS) {
                return new Decision(true, false);
            }
            if (!exhausted) {
                exhausted = true;
                awaitingNextTurn = true;
                singleBurstOverflow = 1;
                return new Decision(false, false);
            }
            if (awaitingNextTurn) {
                singleBurstOverflow++;
                return new Decision(false, singleBurstOverflow > MAX_SINGLE_TURN_OVERFLOW);
            }
            postBudgetToolTurns++;
            awaitingNextTurn = true;
            singleBurstOverflow = 1;
            return new Decision(false, postBudgetToolTurns > MAX_POST_BUDGET_TOOL_TURNS);
        }

        synchronized void onAssistantTurnBoundary() {
            if (exhausted) {
                awaitingNextTurn = false;
                singleBurstOverflow = 0;
            }
        }
    }

    /**
     * MCP servers for one session: the user's trusted servers when inherited, plus the host's
     * code-intelligence server, which replaces any trusted entry of the same name.
     */
    static Map<String, McpServerConfig> mcpServers(SessionRequest sessionRequest) {
        Map<String, McpServerConfig> servers = new LinkedHashMap<>();
        if (sessionRequest.inheritMcp()) {
            servers.putAll(CopilotMcpConfig.loadTrustedServers(sessionRequest.configDir()));
        }
        if (sessionRequest.attachesHostCodeIntelligence()) {
            servers.put(
                    HostCodeIntelligence.SERVER_NAME,
                    sessionRequest.hostCodeIntelligence().toMcpServerConfig());
        }
        return servers;
    }

    static CreateSessionOptions buildSessionConfig(
            SessionRequest sessionRequest, ToolBudget toolBudget, Runnable onRunawayToolLoop) {
        PermissionHandler permissionHandler =
                (permissionRequest, ctx) ->
                        CompletableFuture.completedFuture(
                                permissionDecision(
                                        permissionRequest.getKind(),
                                        sessionRequest.allowsMcp(),
                                        sessionRequest.allowReadTools(),
                                        toolBudget,
                                        onRunawayToolLoop));
        SessionToolOptions tools = new SessionToolOptions();
        CreateSessionOptions config =
                new CreateSessionOptions()
                        .setPermissions(
                                new SessionPermissionOptions().setOnRequest(permissionHandler))
                        .setEvents(new SessionEventOptions().setStreaming(true))
                        .setWorkingDirectory(sessionRequest.workingDir().getAbsolutePath())
                        .setInference(
                                new SessionInferenceOptions()
                                        .setReasoningEffort(sessionRequest.effort()))
                        .setSystemMessage(
                                new SystemMessageConfig()
                                        .setMode(SystemMessageMode.REPLACE)
                                        .setContent(
                                                sessionRequest.allowReadTools()
                                                        ? READ_ONLY_SYSTEM_MESSAGE
                                                        : TOOL_FREE_SYSTEM_MESSAGE))
                        // Never enable on-disk config discovery or custom instructions from the
                        // untrusted PR-branch worktree; trusted repo guidance comes from
                        // BaseCommitContext and trusted MCP servers are injected explicitly below.
                        .setEnableConfigDiscovery(false)
                        .setInstructions(
                                new CreateSessionInstructionOptions()
                                        .setSkipCustomInstructions(true))
                        .setSkills(
                                new SessionSkillOptions()
                                        .setEnabled(false)
                                        .setDirectories(List.of()))
                        .setAgents(new SessionAgentOptions().setDirectories(List.of()))
                        .setHooks(
                                new SessionHookOptions()
                                        .setEnableRepositoryHooks(false)
                                        .setDirectories(List.of()))
                        // Disable infinite context compaction so a single review pass cannot loop
                        // across multiple context windows of file reads.
                        .setState(
                                new SessionStateOptions()
                                        .setInfiniteSessions(
                                                new InfiniteSessionConfig().setEnabled(false)))
                        // Always pass an explicit map (including empty) — leaving mcpServers null
                        // lets the CLI inherit ~/.copilot/mcp-config.json even when inheritMcp is
                        // false, and read-only remote MCP tools can bypass onPermissionRequest.
                        .setMcp(new SessionMcpOptions().setServers(mcpServers(sessionRequest)))
                        .setTools(tools);
        if (!sessionRequest.allowReadTools()) {
            tools.setAvailableTools(List.of());
            config.setPlugins(new SessionPluginOptions().setDirectories(List.of()));
        } else if (!sessionRequest.allowsMcp()) {
            tools.setAvailableTools(READ_ONLY_TOOLS);
            config.setPlugins(new SessionPluginOptions().setDirectories(List.of()));
        } else {
            tools.setExcludedTools(EXCLUDED_NON_REVIEW_TOOLS);
            if (!sessionRequest.inheritMcp()) {
                config.setPlugins(new SessionPluginOptions().setDirectories(List.of()));
            }
        }
        if (StringUtils.isNotBlank(sessionRequest.model())) {
            config.setModel(sessionRequest.model());
        }
        if (StringUtils.isNotBlank(sessionRequest.configDir())) {
            config.setConfigDirectory(sessionRequest.configDir());
        }
        return config;
    }

    static PermissionRequestResult permissionDecision(String kind, boolean allowMcp) {
        return permissionDecision(kind, allowMcp, true, null, null);
    }

    static PermissionRequestResult permissionDecision(
            String kind, boolean allowMcp, boolean allowReadTools) {
        return permissionDecision(kind, allowMcp, allowReadTools, null, null);
    }

    static PermissionRequestResult permissionDecision(
            String kind,
            boolean allowMcp,
            boolean allowReadTools,
            ToolBudget toolBudget,
            Runnable onRunawayToolLoop) {
        boolean permittedKind =
                (allowReadTools && "read".equals(kind)) || (allowMcp && "mcp".equals(kind));
        if (!permittedKind) {
            return PermissionRequestResult.reject(
                    "PR Pilot reviews allow read-only file access only; write, shell, and network"
                            + " tools are disabled.");
        }
        if (toolBudget != null) {
            ToolBudget.Decision decision = toolBudget.recordToolAttempt();
            if (!decision.approved()) {
                if (decision.runawayAbort() && onRunawayToolLoop != null) {
                    onRunawayToolLoop.run();
                }
                return PermissionRequestResult.reject(TOOL_BUDGET_REJECT_MESSAGE);
            }
        }
        return PermissionRequestResult.approveOnce();
    }

    static <T> T awaitWithTimeout(Future<T> future, String operation) throws IOException {
        return awaitWithTimeout(future, operation, SDK_BOOT_TIMEOUT_MS);
    }

    static <T> T awaitWithTimeout(Future<T> future, String operation, long timeoutMs)
            throws IOException {
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            String formatted =
                    timeoutMs % 1000L == 0L ? (timeoutMs / 1000L) + "s" : timeoutMs + "ms";
            throw new IOException(
                    "copilot " + operation + " timed out after " + formatted, timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("copilot " + operation + " failed", interrupted);
        } catch (ExecutionException execution) {
            Throwable cause = execution.getCause();
            String detail = cause != null ? StringUtils.trimToNull(cause.getMessage()) : null;
            throw new IOException(
                    detail != null
                            ? "copilot " + operation + " failed: " + detail
                            : "copilot " + operation + " failed",
                    execution);
        }
    }
}
