package com.jinloes.prpilot.review;

import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.comment;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.hygiene;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.oneRiskyHunk;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.pass;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.pipeline;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.request;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.result;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.reviewJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.ReviewResult;
import com.jinloes.prpilot.review.ReviewPipelineTestSupport.FakeProvider;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Concurrency tests for {@link ReviewPipelineService} and {@link BaseCommitContext}. */
class ReviewPipelineConcurrencyTest {
    @TempDir Path tempDir;

    @Nested
    class AuxiliaryStages {

        @Test
        void overlapsPrimaryRulesAndHygieneBeforeCritiqueAndKeepsDeterministicStatuses()
                throws Exception {
            Path rules = Files.createDirectories(tempDir.resolve("rules"));
            Files.writeString(rules.resolve("team.md"), "Check concurrency.");
            CountDownLatch allThreeRunning = new CountDownLatch(3);
            FakeProvider provider = new FakeProvider();
            provider.primaryResult = pass(result(comment("src/Api.java", 1, "high", "Primary.")));
            provider.beforePrimary =
                    () -> {
                        allThreeRunning.countDown();
                        assertThat(allThreeRunning.await(5, TimeUnit.SECONDS)).isTrue();
                    };
            provider.ruleScripts.put(
                    "team.md",
                    prompt -> {
                        allThreeRunning.countDown();
                        assertThat(allThreeRunning.await(5, TimeUnit.SECONDS)).isTrue();
                        return reviewJson(comment("src/Api.java", 1, "high", "Rule."));
                    });
            provider.hygieneScript =
                    prompt -> {
                        allThreeRunning.countDown();
                        assertThat(allThreeRunning.await(5, TimeUnit.SECONDS)).isTrue();
                        return reviewJson(hygiene("src/Api.java", 1, "medium", "Hygiene."));
                    };
            provider.completions.add(
                    reviewJson(
                            comment("src/Api.java", 1, "high", "Primary."),
                            comment("src/Api.java", 1, "high", "Rule."),
                            hygiene("src/Api.java", 1, "medium", "Hygiene.")));
            List<String> statuses = new ArrayList<>();
            PRReviewRequest req =
                    request(oneRiskyHunk()).toBuilder().rulesDirectory(rules.toString()).build();

            ReviewResult reviewed =
                    pipeline(provider).review(req, false, true, false, statuses::add, null);

            assertThat(provider.callOrder).endsWith("critique");
            assertThat(provider.completeCalls.get(0).prompt())
                    .contains("<draft_review>", "Primary.", "Rule.", "Hygiene.");
            assertThat(statuses)
                    .containsSubsequence(
                            "Rules: 1 loaded, 1 selected (team.md)",
                            "Rule team.md found 1 finding",
                            "Hygiene pass found 1 finding",
                            "Draft review has 3 findings before validation");
            assertThat(reviewed.getLineComments())
                    .extracting(LineComment::getBody)
                    .containsExactlyInAnyOrder("Primary.", "Rule.", "Hygiene.");
        }

        @Test
        void cancelsInFlightAuxiliaryStagesWhenPrimaryGetsInterrupted() throws Exception {
            Path rules = Files.createDirectories(tempDir.resolve("cancel-rules"));
            Files.writeString(rules.resolve("team.md"), "Check cancellation.");
            CountDownLatch auxiliaryStarted = new CountDownLatch(2);
            CountDownLatch auxiliaryInterrupted = new CountDownLatch(2);
            CountDownLatch holdForever = new CountDownLatch(1);
            FakeProvider provider = new FakeProvider();
            provider.ruleScripts.put(
                    "team.md",
                    prompt -> {
                        auxiliaryStarted.countDown();
                        try {
                            holdForever.await(10, TimeUnit.SECONDS);
                            return "{}";
                        } catch (InterruptedException interrupted) {
                            auxiliaryInterrupted.countDown();
                            throw interrupted;
                        }
                    });
            provider.hygieneScript =
                    prompt -> {
                        auxiliaryStarted.countDown();
                        try {
                            holdForever.await(10, TimeUnit.SECONDS);
                            return "{}";
                        } catch (InterruptedException interrupted) {
                            auxiliaryInterrupted.countDown();
                            throw interrupted;
                        }
                    };
            provider.beforePrimary =
                    () -> {
                        assertThat(auxiliaryStarted.await(5, TimeUnit.SECONDS)).isTrue();
                        throw new InterruptedException("primary cancelled");
                    };
            PRReviewRequest req =
                    request(oneRiskyHunk()).toBuilder().rulesDirectory(rules.toString()).build();

            assertThatThrownBy(
                            () ->
                                    pipeline(provider)
                                            .review(req, false, true, false, ignored -> {}, null))
                    .isInstanceOf(InterruptedException.class);
            assertThat(auxiliaryInterrupted.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Nested
    class BaseContextParallelism {

        @Test
        void resolvesGuidelinesAndFileHistoryConcurrently() throws Exception {
            File repo = Files.createDirectories(tempDir.resolve("git-repo")).toFile();
            runGit(repo, "init", "-b", "main");
            runGit(repo, "config", "user.email", "test@example.com");
            runGit(repo, "config", "user.name", "Test");
            runGit(repo, "config", "commit.gpgsign", "false");
            Files.writeString(repo.toPath().resolve("AGENTS.md"), "Base rule.");
            Files.writeString(repo.toPath().resolve("App.java"), "class App {}\n");
            runGit(repo, "add", "-A");
            runGit(repo, "commit", "-q", "-m", "initial");
            String baseSha = runGit(repo, "rev-parse", "HEAD");

            CountDownLatch lsTreeAndLogConcurrent = new CountDownLatch(2);
            BaseCommitContext context =
                    new BaseCommitContext(
                            new BoundedProcessRunner(
                                    builder -> {
                                        List<String> cmd = builder.command();
                                        if (cmd.contains("ls-tree") || cmd.contains("log")) {
                                            lsTreeAndLogConcurrent.countDown();
                                            try {
                                                if (!lsTreeAndLogConcurrent.await(
                                                        5, TimeUnit.SECONDS)) {
                                                    throw new IOException(
                                                            "git reads did not overlap");
                                                }
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                                throw new IOException("interrupted", e);
                                            }
                                        }
                                        return builder.start();
                                    }));
            InspectionManifest manifest =
                    InspectionManifest.fromDiff(
                            "diff --git a/App.java b/App.java\n"
                                    + "--- a/App.java\n"
                                    + "+++ b/App.java\n"
                                    + "@@ -1 +1 @@\n"
                                    + "-class App {}\n"
                                    + "+class App { int x; }\n");

            BaseCommitContext.Result resolved =
                    context.resolve(repo, baseSha, manifest, List.of(), () -> {});

            assertThat(resolved.guidelines()).contains("Base rule.");
            assertThat(resolved.fileHistory()).contains("initial");
        }

        private static String runGit(File dir, String... args) throws Exception {
            List<String> cmd = new ArrayList<>(List.of("git"));
            cmd.addAll(List.of(args));
            Process process =
                    new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true).start();
            String out =
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() != 0) throw new IllegalStateException(out);
            return out.trim();
        }
    }
}
