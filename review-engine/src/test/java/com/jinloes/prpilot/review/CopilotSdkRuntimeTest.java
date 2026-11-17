package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.copilot.SystemMessageMode;
import com.github.copilot.rpc.McpHttpServerConfig;
import com.github.copilot.rpc.McpServerConfig;
import com.github.copilot.rpc.PermissionRequestResult;
import com.github.copilot.rpc.SessionConfig;
import com.jinloes.prpilot.review.CopilotSdkRuntime.SessionRequest;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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

        @Test
        void
                rejectsParallelOverflowInSameTurnWithoutAbortingThenAbortsOnlyAfterRepeatedPostBudgetTurns() {
            CopilotSdkRuntime.ToolBudget budget = new CopilotSdkRuntime.ToolBudget();
            AtomicBoolean aborted = new AtomicBoolean();

            for (int i = 1; i <= CopilotSdkRuntime.MAX_SESSION_TOOL_CALLS; i++) {
                assertThat(
                                CopilotSdkRuntime.permissionDecision(
                                                "read",
                                                false,
                                                true,
                                                budget,
                                                () -> aborted.set(true))
                                        .getKind())
                        .isEqualTo("approve-once");
            }
            // A large parallel batch in the SAME turn (e.g. 20 parallel view/rg calls) rejects the
            // overflow calls cleanly without aborting so the model gets a turn to emit its JSON.
            for (int i = 0; i < 20; i++) {
                PermissionRequestResult rejected =
                        CopilotSdkRuntime.permissionDecision(
                                "read", false, true, budget, () -> aborted.set(true));
                assertThat(rejected.getKind()).isEqualTo("reject");
            }
            assertThat(aborted).isFalse();

            for (int turn = 1; turn <= CopilotSdkRuntime.MAX_POST_BUDGET_TOOL_TURNS; turn++) {
                budget.onAssistantTurnBoundary();
                for (int parallel = 0; parallel < 5; parallel++) {
                    CopilotSdkRuntime.permissionDecision(
                            "read", false, true, budget, () -> aborted.set(true));
                }
                assertThat(aborted).isFalse();
            }

            budget.onAssistantTurnBoundary();
            CopilotSdkRuntime.permissionDecision(
                    "read", false, true, budget, () -> aborted.set(true));
            assertThat(aborted).isTrue();
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

        @Test
        void buildSessionConfigLocksNonMcpSessionsToExplicitEmptyMcpAndReadOnlyAllowlist() {
            SessionConfig config =
                    CopilotSdkRuntime.buildSessionConfig(
                            request(false, true, null),
                            new CopilotSdkRuntime.ToolBudget(),
                            () -> {});

            // Never null: leaving mcpServers null lets the CLI inherit ~/.copilot/mcp-config.json.
            assertThat(config.getMcpServers()).isNotNull().isEmpty();
            assertThat(config.getAvailableTools())
                    .containsExactlyElementsOf(CopilotSdkRuntime.READ_ONLY_TOOLS);
            assertThat(config.getPluginDirectories()).isEmpty();
            assertThat(config.getSkillDirectories()).isEmpty();
            assertThat(config.getAgentDirectories()).isEmpty();
            assertThat(config.getHookDirectories()).isEmpty();
            assertThat(config.getEnableConfigDiscovery()).contains(false);
            assertThat(config.getSkipCustomInstructions()).contains(true);
            assertThat(config.getEnableSkills()).contains(false);
            assertThat(config.getEnableFileHooks()).contains(false);
            assertThat(config.getInfiniteSessions().getEnabled()).contains(false);
            assertThat(config.getSystemMessage()).isNotNull();
            assertThat(config.getSystemMessage().getMode()).isEqualTo(SystemMessageMode.REPLACE);
            assertThat(config.getSystemMessage().getContent())
                    .contains("output_mode: \"content\"", "view_range", "<repo_guidelines>");

            SessionConfig toolFree =
                    CopilotSdkRuntime.buildSessionConfig(
                            request(false, false, null),
                            new CopilotSdkRuntime.ToolBudget(),
                            () -> {});
            assertThat(toolFree.getSystemMessage().getMode()).isEqualTo(SystemMessageMode.REPLACE);
            assertThat(toolFree.getSystemMessage().getContent())
                    .isEqualTo(CopilotSdkRuntime.TOOL_FREE_SYSTEM_MESSAGE);
        }

        @Test
        void
                buildSessionConfigExcludesNonReviewToolsWhenMcpIsActiveAndBlocksPluginsUnlessInherited()
                        throws IOException {
            writeTrustedConfig("search");

            SessionConfig hostOnly =
                    CopilotSdkRuntime.buildSessionConfig(
                            request(false, true, HOST),
                            new CopilotSdkRuntime.ToolBudget(),
                            () -> {});
            assertThat(hostOnly.getAvailableTools()).isNull();
            assertThat(hostOnly.getExcludedTools())
                    .contains("bash", "edit", "create", "task", "skill", "ask_user");
            assertThat(hostOnly.getPluginDirectories()).isEmpty();
            assertThat(hostOnly.getMcpServers()).containsOnlyKeys(HostCodeIntelligence.SERVER_NAME);

            SessionConfig inherited =
                    CopilotSdkRuntime.buildSessionConfig(
                            request(true, true, null),
                            new CopilotSdkRuntime.ToolBudget(),
                            () -> {});
            assertThat(inherited.getAvailableTools()).isNull();
            assertThat(inherited.getExcludedTools())
                    .contains("bash", "edit", "create", "task", "skill", "ask_user");
            assertThat(inherited.getPluginDirectories()).isNull();
            assertThat(inherited.getMcpServers()).containsOnlyKeys("search");
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
        void defaultBootTimeoutUsesSixtySecondsAndFormatsSecondsMessage() {
            AtomicLong observedTimeoutMs = new AtomicLong();
            CompletableFuture<String> future =
                    new CompletableFuture<>() {
                        @Override
                        public String get(long timeout, TimeUnit unit) throws TimeoutException {
                            observedTimeoutMs.set(unit.toMillis(timeout));
                            throw new TimeoutException("simulated boot timeout");
                        }
                    };
            assertThatThrownBy(() -> CopilotSdkRuntime.awaitWithTimeout(future, "session creation"))
                    .isInstanceOf(IOException.class)
                    .hasMessage("copilot session creation timed out after 60s")
                    .hasCauseInstanceOf(TimeoutException.class);
            assertThat(observedTimeoutMs).hasValue(60_000L);
        }

        @Test
        void incompleteFutureWrapsTimeoutAsIOException() {
            CompletableFuture<String> future = new CompletableFuture<>();
            assertThatThrownBy(
                            () ->
                                    CopilotSdkRuntime.awaitWithTimeout(
                                            future, "session creation", 25L))
                    .isInstanceOf(IOException.class)
                    .hasMessage("copilot session creation timed out after 25ms")
                    .hasCauseInstanceOf(TimeoutException.class);
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
