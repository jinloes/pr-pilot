package com.jinloes.prpilot.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.ReviewProvider;
import com.jinloes.prpilot.model.ReviewResult;
import com.jinloes.prpilot.review.CopilotModelDiscovery;
import com.jinloes.prpilot.review.ProviderSetupProbe;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ProviderCanaryTest {

    @Nested
    class ParseArgs {

        @Test
        void parsesProvidersModelEffortAndJsonFlags() {
            ProviderCanary.Options opts =
                    ProviderCanary.parseArgs(
                            new String[] {
                                "--provider",
                                "both",
                                "--model",
                                "gpt-5.4",
                                "--effort",
                                "MEDIUM",
                                "--json"
                            });
            assertThat(opts.providers())
                    .containsExactly(ReviewProvider.COPILOT, ReviewProvider.CLAUDE);
            assertThat(opts.model()).isEqualTo("gpt-5.4");
            assertThat(opts.effort()).isEqualTo("medium");
            assertThat(opts.json()).isTrue();
        }

        @Test
        void rejectsUnknownFlagsAndUnsupportedProviders() {
            assertThatThrownBy(() -> ProviderCanary.parseArgs(new String[] {"--provider", "other"}))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ProviderCanary.parseArgs(new String[] {"--unknown"}))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class VerifyProvider {

        @Test
        void passesWhenPreflightDiscoveryAndReviewContractSucceedAndCleansUpTempDir()
                throws Exception {
            AtomicReference<Path> seenRepoDir = new AtomicReference<>();
            ProviderCanary.ProviderProbe probe =
                    new ProviderCanary.ProviderProbe() {
                        @Override
                        public ProviderSetupProbe.Result preflight(ReviewProvider provider) {
                            return new ProviderSetupProbe.Result(
                                    true, "ready", "unverified", "copilot login");
                        }

                        @Override
                        public CopilotModelDiscovery.Result discoverCopilotModels() {
                            return new CopilotModelDiscovery.Result(
                                    List.of("gpt-5.4", "claude-sonnet-4.6"),
                                    CopilotModelDiscovery.Source.ACCOUNT,
                                    "");
                        }

                        @Override
                        public ReviewResult runCanaryReview(
                                ReviewProvider provider,
                                Path repoDir,
                                PRReviewRequest request,
                                String model,
                                String effort) {
                            seenRepoDir.set(repoDir);
                            assertThat(Files.isRegularFile(repoDir.resolve("src/MathOps.java")))
                                    .isTrue();
                            return new ReviewResult(
                                    "Division by zero when count == 0.",
                                    "REQUEST_CHANGES",
                                    List.of(
                                            new LineComment(
                                                    "src/BatchAverage.java",
                                                    4,
                                                    "issue",
                                                    "Guard count == 0 before dividing.")));
                        }
                    };

            ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
            int exitCode =
                    ProviderCanary.runCli(
                            new String[] {"--provider", "copilot"},
                            probe,
                            new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                            new PrintStream(new ByteArrayOutputStream()));

            assertThat(exitCode).isZero();
            assertThat(outBytes.toString(StandardCharsets.UTF_8))
                    .contains(
                            "PASS provider=copilot discovery=ACCOUNT(2) verdict=REQUEST_CHANGES findings=1");
            assertThat(Files.exists(seenRepoDir.get())).isFalse();
        }

        @Test
        void failsFastWhenCopilotLiveDiscoveryDegradesToHelpFallback() throws Exception {
            ProviderCanary.ProviderProbe probe =
                    new ProviderCanary.ProviderProbe() {
                        @Override
                        public ProviderSetupProbe.Result preflight(ReviewProvider provider) {
                            return new ProviderSetupProbe.Result(
                                    true, "ready", "unverified", "copilot login");
                        }

                        @Override
                        public CopilotModelDiscovery.Result discoverCopilotModels() {
                            return new CopilotModelDiscovery.Result(
                                    List.of("gpt-5.4"),
                                    CopilotModelDiscovery.Source.CLI_HELP,
                                    "SDK protocol version mismatch");
                        }

                        @Override
                        public ReviewResult runCanaryReview(
                                ReviewProvider provider,
                                Path repoDir,
                                PRReviewRequest request,
                                String model,
                                String effort) {
                            throw new AssertionError("Should fail before starting review");
                        }
                    };

            ProviderCanary.CheckReport report =
                    ProviderCanary.verifyProvider(ReviewProvider.COPILOT, "", "low", probe);

            assertThat(report.passed()).isFalse();
            assertThat(report.failure()).contains("SDK protocol version mismatch");
        }

        @Test
        void redactsCredentialsWhenReviewThrowsAndValidatesResultContract() throws IOException {
            String token = "ghp_" + "z".repeat(36);
            ProviderCanary.ProviderProbe failingProbe =
                    new ProviderCanary.ProviderProbe() {
                        @Override
                        public ProviderSetupProbe.Result preflight(ReviewProvider provider) {
                            return new ProviderSetupProbe.Result(
                                    true, "ready", "ready", "claude auth login");
                        }

                        @Override
                        public CopilotModelDiscovery.Result discoverCopilotModels() {
                            return CopilotModelDiscovery.Result.none("");
                        }

                        @Override
                        public ReviewResult runCanaryReview(
                                ReviewProvider provider,
                                Path repoDir,
                                PRReviewRequest request,
                                String model,
                                String effort)
                                throws IOException {
                            throw new IOException("Auth failed for " + token);
                        }
                    };

            ProviderCanary.CheckReport failed =
                    ProviderCanary.verifyProvider(ReviewProvider.CLAUDE, "", "low", failingProbe);
            assertThat(failed.passed()).isFalse();
            assertThat(failed.failure()).doesNotContain(token).contains("<redacted>");

            assertThat(
                            ProviderCanary.validateResultContract(
                                    new ReviewResult(
                                            "ok",
                                            "COMMENT",
                                            List.of(
                                                    new LineComment(
                                                            "src/Other.java", 4, "issue", "bad")))))
                    .contains("Unanchored finding");
        }
    }
}
