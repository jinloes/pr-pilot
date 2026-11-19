package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.ReviewProvider;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderSetupProbeTest {
    @Test
    void reportsMissingBinaryWithoutAttemptingAuthentication() {
        ProviderSetupProbe probe =
                new ProviderSetupProbe(
                        ignored -> false,
                        () -> {
                            throw new AssertionError("auth must not run without a binary");
                        });

        ProviderSetupProbe.Result result = probe.probe(ReviewProvider.CLAUDE);

        assertThat(result.available()).isFalse();
        assertThat(result.binaryStatus()).isEqualTo("missing");
        assertThat(result.authenticationStatus()).isEqualTo("unavailable");
    }

    @Test
    void reportsClaudeAuthenticationAsReadyOrUnavailable() {
        ProviderSetupProbe ready =
                new ProviderSetupProbe(
                        ignored -> true, () -> ProviderSetupProbe.AuthenticationStatus.READY);
        ProviderSetupProbe unavailable =
                new ProviderSetupProbe(
                        ignored -> true, () -> ProviderSetupProbe.AuthenticationStatus.UNAVAILABLE);

        assertThat(ready.probe(ReviewProvider.CLAUDE).authenticationStatus()).isEqualTo("ready");
        assertThat(unavailable.probe(ReviewProvider.CLAUDE).authenticationStatus())
                .isEqualTo("unavailable");
    }

    @Test
    void reportsFailedClaudeProbeAsUnverified() {
        ProviderSetupProbe probe =
                new ProviderSetupProbe(
                        ignored -> true,
                        () -> {
                            throw new IOException("probe failed");
                        });

        assertThat(probe.probe(ReviewProvider.CLAUDE).authenticationStatus())
                .isEqualTo("unverified");
    }

    @Test
    void classifiesOnlyConclusiveSignedOutResponsesAsUnavailable() {
        assertThat(classify(1, "You are not logged in. Run claude auth login.", false))
                .isEqualTo(ProviderSetupProbe.AuthenticationStatus.UNAVAILABLE);
        assertThat(classify(1, "error: unknown command 'auth'", false))
                .isEqualTo(ProviderSetupProbe.AuthenticationStatus.UNVERIFIED);
        assertThat(classify(0, "error: unknown command 'auth'", false))
                .isEqualTo(ProviderSetupProbe.AuthenticationStatus.UNVERIFIED);
        assertThat(classify(1, "", false))
                .isEqualTo(ProviderSetupProbe.AuthenticationStatus.UNVERIFIED);
        assertThat(classify(1, "not authenticated", true))
                .isEqualTo(ProviderSetupProbe.AuthenticationStatus.UNVERIFIED);
        assertThat(classify(0, "authenticated", false))
                .isEqualTo(ProviderSetupProbe.AuthenticationStatus.READY);
    }

    @Test
    void reportsCopilotAuthenticationAsUnverifiedWithoutInteractiveProbe() {
        ProviderSetupProbe probe =
                new ProviderSetupProbe(
                        ignored -> true,
                        () -> {
                            throw new AssertionError("unsupported Copilot auth probe must not run");
                        });

        ProviderSetupProbe.Result result = probe.probe(ReviewProvider.COPILOT);

        assertThat(result.authenticationStatus()).isEqualTo("unverified");
        assertThat(result.authCommand()).isEqualTo("copilot login");
    }

    @Test
    void buildClaudeAuthProcessWrapsWindowsBatchShimAndNormalizesPathEnv() throws IOException {
        String shim = "C:\\Program Files (x86)\\nodejs\\claude.cmd";
        ProcessBuilder pb =
                ProviderSetupProbe.buildClaudeAuthProcess(
                        shim,
                        true,
                        Map.of("ComSpec", "C:\\Windows\\System32\\cmd.exe"),
                        "C:\\Users\\tester");

        assertThat(pb.command())
                .containsExactly(
                        "C:\\Windows\\System32\\cmd.exe",
                        "/d",
                        "/c",
                        "call",
                        shim,
                        "auth",
                        "status",
                        "--text");
        assertThat(pb.environment()).containsKey("PATH");
        assertThat(pb.environment().keySet().stream().filter("path"::equalsIgnoreCase).count())
                .isEqualTo(1);
    }

    private static ProviderSetupProbe.AuthenticationStatus classify(
            int exitCode, String output, boolean truncated) {
        return ProviderSetupProbe.classifyClaudeAuthResult(
                new BoundedProcessRunner.ProcessResult(exitCode, output, truncated));
    }
}
