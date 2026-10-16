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
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;

/**
 * Copilot runtime seam for {@link CopilotService}: the client and session requests, the runtime
 * interfaces tests fake, and their implementations over the official Copilot Java SDK (which still
 * launches the local {@code copilot} CLI). Permissions are deny-by-default: {@code read} tools are
 * approved unless the session is tool-free, {@code mcp} tools only when trusted MCP servers are
 * inherited, and everything else is rejected.
 */
final class CopilotSdkRuntime {

    private static final long SDK_BOOT_TIMEOUT_MS = 60L * 1000L;

    private CopilotSdkRuntime() {}

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
}
