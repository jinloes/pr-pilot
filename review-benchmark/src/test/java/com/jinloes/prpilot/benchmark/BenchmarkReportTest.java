package com.jinloes.prpilot.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.commons.io.file.PathUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class BenchmarkReportTest {
    private static final PrRef PR_A = new PrRef("https://github.com", "o", "a", 1);
    private static final PrRef PR_B = new PrRef("https://github.com", "o", "b", 2);
    private static final Finding M1 = new Finding("M1", "A.java", 3, "Null | deref");
    private static final Finding M2 = new Finding("M2", "B.java", 0, "Missing test");
    private static final Finding P1 = new Finding("P1", "A.java", 4, "null");
    private static final Finding P2 = new Finding("P2", "C.java", 1, "extra");
    private static final BenchmarkReport.Settings SETTINGS =
            new BenchmarkReport.Settings(
                    "copilot", "", "", "", true, false, false, true, "llm", "", 10);

    private static BenchmarkReport.PrResult scored(
            PrRef pr,
            int mae,
            List<BenchmarkReport.Match> matches,
            List<BenchmarkReport.Miss> misses,
            List<Finding> extras) {
        return new BenchmarkReport.PrResult(
                pr.label(),
                "https://github.com/x",
                "scored",
                "",
                "c".repeat(40),
                61_000,
                false,
                mae,
                matches.size() + extras.size(),
                matches,
                misses,
                extras,
                List.of("Validation kept 1 of 3 findings"));
    }

    private static BenchmarkReport report() {
        return BenchmarkReport.of(
                "2024-01-01T00:00:00Z",
                SETTINGS,
                List.of(
                        scored(
                                PR_A,
                                2,
                                List.of(new BenchmarkReport.Match(M1, P1)),
                                List.of(
                                        new BenchmarkReport.Miss(
                                                M2, FindingMatcher.NO_NEARBY_FINDING)),
                                List.of(P2)),
                        scored(
                                PR_B,
                                1,
                                List.of(),
                                List.of(
                                        new BenchmarkReport.Miss(
                                                M1, FindingMatcher.JUDGED_DIFFERENT)),
                                List.of()),
                        BenchmarkReport.PrResult.notScored(PR_B, "u", "skipped", "none"),
                        BenchmarkReport.PrResult.notScored(PR_A, "u", "failed", "boom")));
    }

    private static BenchmarkReport.PrResult withDropped(BenchmarkReport.PrResult base) {
        return new BenchmarkReport.PrResult(
                base.pr(),
                base.url(),
                base.status(),
                base.message(),
                base.reviewedCommit(),
                base.reviewMillis(),
                base.diffTruncated(),
                base.maeFindings(),
                base.prPilotFindings(),
                base.matches(),
                base.misses(),
                base.prPilotOnly(),
                base.stages(),
                List.of(
                        new Finding(null, "A.java", 12, "Close to the miss"),
                        new Finding(null, "A.java", 30, "Too far away"),
                        new Finding(null, "B.java", 3, "Other file")));
    }

    @Nested
    class Totals {
        @Test
        void countsOnlyScoredPrsTowardRecall() {
            BenchmarkReport.Totals totals = report().totals();
            assertThat(totals.prs()).isEqualTo(4);
            assertThat(totals.scored()).isEqualTo(2);
            assertThat(totals.skipped()).isEqualTo(1);
            assertThat(totals.failed()).isEqualTo(1);
            assertThat(totals.maeFindings()).isEqualTo(3);
            assertThat(totals.matched()).isEqualTo(1);
            assertThat(totals.prPilotFindings()).isEqualTo(2);
            assertThat(totals.recall()).isEqualTo(1.0 / 3);
            assertThat(totals.meanPrRecall()).isEqualTo(0.25);
            assertThat(totals.completeDiffRecall()).isEqualTo(1.0 / 3);
        }

        @Test
        void completeDiffRecallExcludesTruncatedPrs() {
            BenchmarkReport.PrResult complete =
                    scored(
                            PR_A,
                            2,
                            List.of(new BenchmarkReport.Match(M1, P1)),
                            List.of(),
                            List.of());
            BenchmarkReport.PrResult base =
                    scored(
                            PR_B,
                            4,
                            List.of(),
                            List.of(new BenchmarkReport.Miss(M2, FindingMatcher.NO_NEARBY_FINDING)),
                            List.of());
            BenchmarkReport.PrResult truncated =
                    new BenchmarkReport.PrResult(
                            base.pr(),
                            base.url(),
                            base.status(),
                            base.message(),
                            base.reviewedCommit(),
                            base.reviewMillis(),
                            true,
                            base.maeFindings(),
                            base.prPilotFindings(),
                            base.matches(),
                            base.misses(),
                            base.prPilotOnly(),
                            base.stages());

            BenchmarkReport.Totals totals = BenchmarkReport.Totals.of(List.of(complete, truncated));

            assertThat(totals.recall()).isEqualTo(1.0 / 6);
            assertThat(totals.completeDiffMaeFindings()).isEqualTo(2);
            assertThat(totals.completeDiffMatched()).isEqualTo(1);
            assertThat(totals.completeDiffRecall()).isEqualTo(0.5);
        }

        @Test
        void recallIsZeroWithNothingScored() {
            assertThat(BenchmarkReport.Totals.of(List.of()).recall()).isZero();
        }
    }

    @Nested
    class Markdown {
        @Test
        void summarizesRecallAndListsMissesWithReasons() {
            String md = report().markdown();
            assertThat(md)
                    .contains("| Recall (matched / Mae findings) | 33.3% (1 / 3) |")
                    .contains(
                            "| Recall on complete diffs (truncated PRs excluded) | 33.3% (1 / 3) |")
                    .contains("| PRs scored / skipped / failed | 2 / 1 / 1 |")
                    .contains(
                            "| [o/a#1](https://github.com/x) | scored | 2 | 1 | 50.0% | 2 | 61s |")
                    .contains("- `B.java` — no PR Pilot finding nearby: Missing test")
                    .contains("- `A.java:3` — nearby finding judged different: Null \\| deref")
                    .contains("- o/a#1 (failed): boom");
        }

        @Test
        void listsPipelineStagesForScoredPrsOnly() {
            String md = report().markdown();

            assertThat(md)
                    .contains("## Pipeline stages")
                    .contains("### o/a#1\n\n- Validation kept 1 of 3 findings\n");
        }

        @Test
        void omitsPipelineStagesWhenNoneRecorded() {
            String md =
                    BenchmarkReport.of(
                                    "now",
                                    SETTINGS,
                                    List.of(
                                            BenchmarkReport.PrResult.notScored(
                                                    PR_A, "u", "failed", "boom")))
                            .markdown();

            assertThat(md).doesNotContain("## Pipeline stages");
        }

        @Test
        void listsDroppedFindingsAndMarksNearMissesOfMissedMaeFindings() {
            BenchmarkReport.PrResult base =
                    scored(
                            PR_A,
                            1,
                            List.of(),
                            List.of(new BenchmarkReport.Miss(M1, FindingMatcher.NO_NEARBY_FINDING)),
                            List.of());
            String md = BenchmarkReport.of("now", SETTINGS, List.of(withDropped(base))).markdown();

            assertThat(md)
                    .contains("## Dropped by validation")
                    .contains("- `A.java:12` (near miss) — Close to the miss\n")
                    .contains("- `A.java:30` — Too far away\n")
                    .contains("- `B.java:3` — Other file\n");
        }

        @Test
        void omitsDroppedSectionWhenNothingWasDropped() {
            assertThat(report().markdown()).doesNotContain("## Dropped by validation");
        }

        @Test
        void headerNamesChunkedAndCallSiteSettingsOnlyWhenNonDefault() {
            assertThat(report().markdown())
                    .doesNotContain("chunked review")
                    .doesNotContain("no call sites");

            BenchmarkReport.Settings varied =
                    new BenchmarkReport.Settings(
                            "copilot", "", "", "", true, false, true, false, "llm", "", 10);
            String md = BenchmarkReport.of("now", varied, report().prs()).markdown();

            assertThat(md).contains(", chunked review, no call sites, judge `llm");
        }
    }

    @Nested
    class Write {
        private Path dir;

        @BeforeEach
        void setUp() throws Exception {
            dir = Files.createTempDirectory("benchmark-report");
        }

        @AfterEach
        void tearDown() throws Exception {
            PathUtils.deleteDirectory(dir);
        }

        @Test
        void writesMarkdownAndJson() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            Path md = report().write(dir.resolve("out"), "benchmark-x", mapper);

            assertThat(md).hasFileName("benchmark-x.md").isRegularFile();
            JsonNode json = mapper.readTree(dir.resolve("out/benchmark-x.json").toFile());
            assertThat(json.path("totals").path("matched").asInt()).isEqualTo(1);
            assertThat(json.path("prs").get(0).path("prPilotOnly").get(0).path("id").asText())
                    .isEqualTo("P2");
            assertThat(json.path("prs").get(0).path("dropped").isArray()).isTrue();
        }

        @Test
        void writesDroppedFindingsToJson() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            BenchmarkReport.PrResult base = scored(PR_A, 0, List.of(), List.of(), List.of());
            BenchmarkReport.of("now", SETTINGS, List.of(withDropped(base)))
                    .write(dir, "dropped", mapper);

            JsonNode dropped =
                    mapper.readTree(dir.resolve("dropped.json").toFile())
                            .path("prs")
                            .get(0)
                            .path("dropped");
            assertThat(dropped).hasSize(3);
            assertThat(dropped.get(0).path("path").asText()).isEqualTo("A.java");
            assertThat(dropped.get(0).path("line").asInt()).isEqualTo(12);
        }
    }
}
