package com.jinloes.prpilot.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jinloes.prpilot.model.ReviewProvider;
import com.jinloes.prpilot.model.ReviewResult;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class StageEvalRunnerTest {

    @Nested
    class BundledGoldenCorpus {

        @Test
        void allCuratedStageCasesPassWithPerfectRecallAndFalsePositiveSuppression()
                throws Exception {
            StageEvalRunner.SuiteSummary summary = StageEvalRunner.runDeterministicDefaultCorpus();

            assertThat(summary.totalCases()).isEqualTo(11);
            assertThat(summary.allPassed()).isTrue();
            assertThat(summary.truePositiveRecall()).isEqualTo(1.0);
            assertThat(summary.falsePositiveSuppressionRate()).isEqualTo(1.0);
        }
    }

    @Nested
    class RunCli {

        @Test
        void filtersCasesWithOnlyFlagAndRendersJsonReport() throws Exception {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();

            int code =
                    StageEvalRunner.runCli(
                            new String[] {
                                "--only",
                                "ci-duplicate-suppressed",
                                "--only",
                                "safe-suggestion-preserved",
                                "--json"
                            },
                            new PrintStream(out, true, StandardCharsets.UTF_8),
                            new PrintStream(err, true, StandardCharsets.UTF_8));

            assertThat(code).isZero();
            String json = out.toString(StandardCharsets.UTF_8);
            assertThat(json)
                    .contains("\"totalCases\" : 2")
                    .contains("\"passedCases\" : 2")
                    .contains("ci-duplicate-suppressed")
                    .contains("safe-suggestion-preserved");
        }

        @Test
        void reportsNonZeroExitWhenExpectedFindingIsMissedOrFalsePositiveSurvives()
                throws Exception {
            List<StageEvalRunner.EvalCase> single =
                    StageEvalRunner.loadBundledCorpus().stream()
                            .filter(c -> "sql-injection-kept".equals(c.id()))
                            .toList();

            StageEvalRunner.SuiteSummary broken =
                    StageEvalRunner.evaluateSuite(
                            "deterministic",
                            single,
                            (evalCase, request, stages) ->
                                    new ReviewResult("dropped everything", "APPROVE", List.of()));

            assertThat(broken.allPassed()).isFalse();
            assertThat(broken.truePositiveRecall()).isZero();
            assertThat(broken.cases().get(0).failures())
                    .anyMatch(f -> f.contains("missing expected finding at src/UserRepo.java:11"));
        }
    }

    @Nested
    class ParseArgs {

        @Test
        void parsesLiveProviderModelAndEffortAndRejectsInvalidOptions() {
            StageEvalRunner.Options opts =
                    StageEvalRunner.parseArgs(
                            new String[] {
                                "--live", "--provider", "claude", "--model", "claude-sonnet-4-6"
                            });
            assertThat(opts.live()).isTrue();
            assertThat(opts.provider()).isEqualTo(ReviewProvider.CLAUDE);
            assertThat(opts.model()).isEqualTo("claude-sonnet-4-6");

            assertThat(
                            StageEvalRunner.parseArgs(new String[] {"--provider", "antigravity"})
                                    .provider())
                    .isEqualTo(ReviewProvider.ANTIGRAVITY);

            assertThatThrownBy(() -> StageEvalRunner.parseArgs(new String[] {"--provider", "nope"}))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> StageEvalRunner.parseArgs(new String[] {"--bogus"}))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
