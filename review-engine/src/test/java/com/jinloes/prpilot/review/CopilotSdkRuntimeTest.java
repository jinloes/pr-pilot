package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.copilot.rpc.McpHttpServerConfig;
import com.github.copilot.rpc.McpServerConfig;
import com.jinloes.prpilot.review.CopilotSdkRuntime.SessionRequest;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for the {@link CopilotSdkRuntime} permission policy, MCP servers, and startup timeout. */
class CopilotSdkRuntimeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Nested
    class PermissionDecision {

        @Test
        void allowsReadOnlyDeniesMutatingTools() {
            assertThat(CopilotSdkRuntime.permissionDecision("read", false).getKind())
                    .isEqualTo("approve-once");
            assertThat(CopilotSdkRuntime.permissionDecision("shell", false).getKind())
                    .isEqualTo("reject");
            assertThat(CopilotSdkRuntime.permissionDecision("write", false).getKind())
                    .isEqualTo("reject");
            assertThat(CopilotSdkRuntime.permissionDecision("mcp", false).getKind())
                    .isEqualTo("reject");
        }

        @Test
        void onlyApprovesMcpAfterExplicitElevation() {
            assertThat(CopilotSdkRuntime.permissionDecision("mcp", true).getKind())
                    .isEqualTo("approve-once");
            assertThat(CopilotSdkRuntime.permissionDecision("write", true).getKind())
                    .isEqualTo("reject");
        }

        @Test
        void deniesReadToolsForToolFreeSupervisorSessions() {
            assertThat(CopilotSdkRuntime.permissionDecision("read", false, false).getKind())
                    .isEqualTo("reject");
        }
    }

    @Nested
    class McpServers {

        private static final HostCodeIntelligence HOST =
                new HostCodeIntelligence("http://127.0.0.1:41234/mcp", "t".repeat(43));

        private Path configDir;

        @BeforeEach
        void setUp() throws IOException {
            configDir = Files.createTempDirectory("copilot-sdk-runtime-test");
        }

        @AfterEach
        void tearDown() throws IOException {
            FileUtils.deleteDirectory(configDir.toFile());
        }

        private void writeTrustedConfig(String... names) throws IOException {
            ObjectNode root = JSON.createObjectNode();
            ObjectNode servers = root.putObject("mcpServers");
            for (String name : names) {
                servers.putObject(name).put("command", "trusted-" + name);
            }
            Files.writeString(
                    configDir.resolve(CopilotMcpConfig.CONFIG_FILE_NAME),
                    JSON.writeValueAsString(root),
                    StandardCharsets.UTF_8);
        }

        private SessionRequest request(
                boolean inheritMcp, boolean allowReadTools, HostCodeIntelligence host) {
            return new SessionRequest(
                    "",
                    "medium",
                    new File("."),
                    inheritMcp,
                    configDir.toString(),
                    allowReadTools,
                    host);
        }

        @Test
        void attachesOnlyTheHostServerWhenNothingIsInherited() throws IOException {
            writeTrustedConfig("search");

            Map<String, McpServerConfig> servers =
                    CopilotSdkRuntime.mcpServers(request(false, true, HOST));

            assertThat(servers).containsOnlyKeys(HostCodeIntelligence.SERVER_NAME);
            assertThat(
                            ((McpHttpServerConfig) servers.get(HostCodeIntelligence.SERVER_NAME))
                                    .getUrl())
                    .isEqualTo(HOST.url());
        }

        @Test
        void addsTheHostServerAlongsideInheritedServersAndReplacesASameNamedEntry()
                throws IOException {
            writeTrustedConfig("search", HostCodeIntelligence.SERVER_NAME);

            Map<String, McpServerConfig> servers =
                    CopilotSdkRuntime.mcpServers(request(true, true, HOST));

            assertThat(servers).containsOnlyKeys("search", HostCodeIntelligence.SERVER_NAME);
            assertThat(servers.get(HostCodeIntelligence.SERVER_NAME))
                    .isInstanceOf(McpHttpServerConfig.class);
        }

        @Test
        void toolFreeSessionsNeverAttachTheHostServer() {
            SessionRequest request = request(false, false, HOST);

            assertThat(CopilotSdkRuntime.mcpServers(request)).isEmpty();
            assertThat(request.allowsMcp()).isFalse();
        }

        @Test
        void mcpPermissionFollowsInheritanceOrAnAttachedHostServer() {
            assertThat(request(false, true, null).allowsMcp()).isFalse();
            assertThat(request(true, true, null).allowsMcp()).isTrue();
            assertThat(request(false, true, HOST).allowsMcp()).isTrue();
        }

        @Test
        void theLegacyConstructorAttachesNoHostServer() {
            SessionRequest request =
                    new SessionRequest("", "medium", new File("."), false, null, true);

            assertThat(request.hostCodeIntelligence()).isNull();
            assertThat(CopilotSdkRuntime.mcpServers(request)).isEmpty();
        }
    }

    @Nested
    class AwaitWithTimeout {

        @Test
        void completedFutureReturnsValue() throws IOException {
            CompletableFuture<String> future = CompletableFuture.completedFuture("ready");
            assertThat(CopilotSdkRuntime.awaitWithTimeout(future, "runtime startup"))
                    .isEqualTo("ready");
        }

        @Test
        void incompleteFutureWrapsTimeoutAsIOException() {
            CompletableFuture<String> future = new CompletableFuture<>();
            assertThatThrownBy(() -> CopilotSdkRuntime.awaitWithTimeout(future, "session creation"))
                    .isInstanceOf(IOException.class)
                    .hasMessage("copilot session creation timed out after 60s");
        }

        @Test
        void interruptedWaitRestoresTheThreadInterruptStatus() {
            CompletableFuture<String> future = new CompletableFuture<>();
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(
                                () -> CopilotSdkRuntime.awaitWithTimeout(future, "runtime startup"))
                        .isInstanceOf(IOException.class)
                        .hasMessage("copilot runtime startup failed")
                        .hasCauseInstanceOf(InterruptedException.class);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
        }
    }
}
