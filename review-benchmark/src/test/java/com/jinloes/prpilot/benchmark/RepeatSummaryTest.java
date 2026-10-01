package com.jinloes.prpilot.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.commons.io.file.PathUtils;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RepeatSummaryTest {
    private static final PrRef PR_A = new PrRef("https://github.com", "o", "a", 1);
    private static final PrRef PR_B = new PrRef("https://github.com", "o", "b", 2);
    private static final Finding MAE = new Finding("M", "A.java", 3, "bug");
    private static final Finding OURS = new Finding("P", "A.java", 3, "bug");
    private static final BenchmarkReport.Settings SETTINGS =
            new BenchmarkReport.Settings(
                    "copilot", "", "", "", true, false, false, true, "llm", "", 10);

    private static BenchmarkReport.PrResult scored(PrRef pr, int mae, int matched) {
        List<BenchmarkReport.Match> matches =
                Collections.nCopies(matched, new BenchmarkReport.Match(MAE, OURS));
        return new BenchmarkReport.PrResult(
                pr.label(),
                "u",
                BenchmarkReport.STATUS_SCORED,
                "",
                "c".repeat(40),
                1_000,
                false,
                mae,
                matched,
                matches,
                List.of(),
                List.of(),
                List.of());
    }

    private static BenchmarkReport run(BenchmarkReport.PrResult... prs) {
        return BenchmarkReport.of("now", SETTINGS, List.of(prs));
    }

    private static RepeatSummary summary() {
        return new RepeatSummary(
                List.of(
                        run(scored(PR_A, 4, 1), scored(PR_B, 4, 1)),
                        run(
                                scored(PR_A, 4, 3),
                                BenchmarkReport.PrResult.notScored(
                                        PR_B, "u", BenchmarkReport.STATUS_FAILED, "403"))));
    }

    @Nested
    class Recall {
        @Test
        void reportsMeanMinAndMaxAcrossRuns() {
            RepeatSummary summary = summary();
            assertThat(summary.minRecall()).isEqualTo(0.25);
            assertThat(summary.maxRecall()).isEqualTo(0.75);
            assertThat(summary.meanRecall()).isEqualTo(0.5);
        }

        @Test
        void rejectsAnEmptyRunList() {
            assertThatThrownBy(() -> new RepeatSummary(List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class MatchedByPr {
        @Test
        void keepsOneCountPerRunAndNullWhenNotScored() {
            assertThat(summary().matchedByPr())
                    .containsExactly(
                            entry(PR_A.label(), List.of(1, 3)),
                            entry(PR_B.label(), Arrays.asList(1, null)));
        }
    }

    @Nested
    class Markdown {
        @Test
        void listsEachRunAndPerPrCountsWithTheirMean() {
            assertThat(summary().markdown())
                    .contains("| 1 | 25.0% (2 / 8) | 25.0% (2 / 8) |")
                    .contains("| 2 | 75.0% (3 / 4) | 75.0% (3 / 4) |")
                    .contains("Mean recall 50.0% (min 25.0%, max 75.0%).")
                    .contains("| PR | Mae | Run 1 | Run 2 | Mean |")
                    .contains("| o/a#1 | 4 | 1 | 3 | 2.0 |")
                    .contains("| o/b#2 | 4 | 1 | – | 1.0 |");
        }

        @Test
        void writesTheSummaryFile() throws Exception {
            Path dir = Files.createTempDirectory("repeat-summary");
            try {
                Path written = summary().write(dir, "benchmark-x-summary");
                assertThat(written.getFileName().toString()).isEqualTo("benchmark-x-summary.md");
                assertThat(Files.readString(written)).startsWith("# PR Pilot recall across");
            } finally {
                PathUtils.deleteDirectory(dir);
            }
        }
    }
}
