package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for the {@link CopilotSdkRuntime} permission policy and SDK startup timeout. */
class CopilotSdkRuntimeTest {

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
