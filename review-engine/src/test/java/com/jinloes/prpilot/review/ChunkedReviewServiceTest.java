package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.DiffCoverage;
import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.model.ReviewResult;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ChunkedReviewServiceTest {
    @Test
    void returnsTheProviderResultDirectlyWhenOnlyOneBatchIsNeeded() throws Exception {
        ChunkedReviewService service = new ChunkedReviewService();
        AtomicInteger calls = new AtomicInteger();
        ReviewResult expected = new ReviewResult("single", "APPROVE", List.of());

        ReviewResult result =
                service.review(
                        PRReviewRequest.builder(
                                        pr(),
                                        "diff --git a/A.java b/A.java\n--- a/A.java\n+++ b/A.java\n@@ -1 +1 @@\n-old\n+new\n")
                                .build(),
                        ignored -> {},
                        request -> {
                            calls.incrementAndGet();
                            return expected;
                        });

        assertThat(calls).hasValue(1);
        assertThat(result).isSameAs(expected);
    }

    @Test
    void preservesDeletedFilesInBatches() {
        String diff =
                "diff --git a/Removed.java b/Removed.java\n"
                        + "--- a/Removed.java\n"
                        + "+++ /dev/null\n"
                        + "@@ -1 +0,0 @@\n"
                        + "-removed\n";

        assertThat(ChunkedReviewService.buildBatches(diff))
                .singleElement()
                .satisfies(
                        batch -> {
                            assertThat(batch.files()).containsExactly("Removed.java");
                            assertThat(batch.diff()).contains("+++ /dev/null");
                        });
    }

    @Test
    void countsTriplePlusSourceLinesAsChangesInsideAHunk() {
        StringBuilder diff = new StringBuilder();
        for (int index = 0; index < 7; index++) {
            String file = "File" + index + ".txt";
            diff.append("diff --git a/").append(file).append(" b/").append(file).append("\n");
            diff.append("--- a/").append(file).append("\n");
            diff.append("+++ b/").append(file).append("\n");
            diff.append("@@ -1 +1 @@\n");
            diff.append(index == 0 ? "+++operator\n" : "+change\n");
        }

        assertThat(ChunkedReviewService.buildBatches(diff.toString()).get(0).files())
                .contains("File0.txt");
    }

    @Test
    void contractIndexUsesExplicitCorrectLineLabelsAcrossHunksAndContext() {
        String diff =
                "diff --git a/A.java b/A.java\n"
                        + "--- a/A.java\n"
                        + "+++ b/A.java\n"
                        + "@@ -10,4 +20,4 @@\n"
                        + " context one\n"
                        + "-removed\n"
                        + " context two\n"
                        + "+++operator\n"
                        + "@@ -50,2 +80,2 @@\n"
                        + "-old call\n"
                        + "+new call\n";

        String index =
                ChunkedReviewService.buildContractIndex(ChunkedReviewService.buildBatches(diff));

        assertThat(index)
                .contains("OLD 11 | -removed")
                .contains("NEW 22 | +++operator")
                .contains("OLD 50 | -old call")
                .contains("NEW 80 | +new call");
        assertThat(index).doesNotContain("\n@@", "\n+new call", "\n-old call");
    }

    @Test
    void contractIndexTruncatesAtWholeLabelledLines() {
        String longBody = "x".repeat(2_000);
        StringBuilder diff = new StringBuilder();
        for (int file = 0; file < 70; file++) {
            diff.append("diff --git a/F").append(file).append(" b/F").append(file).append("\n");
            diff.append("--- a/F").append(file).append("\n");
            diff.append("+++ b/F").append(file).append("\n");
            diff.append("@@ -1 +1 @@\n+").append(longBody).append("\n");
        }

        String index =
                ChunkedReviewService.buildContractIndex(
                        ChunkedReviewService.buildBatches(diff.toString()));

        assertThat(index).endsWith("[contract index truncated at engine limit]\n");
        assertThat(index.length())
                .isLessThanOrEqualTo(ChunkedReviewService.MAX_RECONCILIATION_INDEX_CHARS);
    }

    @Test
    void reconcilesCrossFileContractAfterReviewingAllBatches() throws Exception {
        ChunkedReviewService service = new ChunkedReviewService();
        List<PRReviewRequest> requests = new ArrayList<>();
        PRReviewRequest request = PRReviewRequest.builder(pr(), sevenFileSignatureDiff()).build();
        AtomicInteger calls = new AtomicInteger();

        ReviewResult result =
                service.review(
                        request,
                        ignored -> {},
                        current -> {
                            requests.add(current);
                            int call = calls.incrementAndGet();
                            if (call < 3) {
                                return new ReviewResult("batch " + call, "APPROVE", List.of());
                            }
                            return new ReviewResult(
                                    "The caller still uses the removed argument.",
                                    "REQUEST_CHANGES",
                                    List.of(
                                            new LineComment(
                                                    "Caller.java",
                                                    10,
                                                    "issue",
                                                    "Update the call site for the new signature.")));
                        });

        assertThat(requests).hasSize(3);
        assertThat(requests.get(2).getCustomInstructions())
                .contains(
                        "mandatory final reconciliation", "<batch_reviews>", "batch 1", "batch 2");
        assertThat(requests.get(2).getDiff()).contains("Api.java", "Caller.java", "-call(old)");
        assertThat(result.getVerdict()).isEqualTo("REQUEST_CHANGES");
        assertThat(result.getLineComments())
                .extracting(LineComment::getFile)
                .contains("Caller.java");
    }

    @Test
    void preservesCompleteBatchSummariesAndDisclosesFailedReconciliation() throws Exception {
        ChunkedReviewService service = new ChunkedReviewService();
        AtomicInteger calls = new AtomicInteger();
        String longSummary = "x".repeat(600);

        ReviewResult result =
                service.review(
                        PRReviewRequest.builder(pr(), sevenFileSignatureDiff()).build(),
                        ignored -> {},
                        request -> {
                            int call = calls.incrementAndGet();
                            if (call == 3) throw new IOException("reconciliation unavailable");
                            return new ReviewResult(longSummary + call, "COMMENT", List.of());
                        });

        assertThat(result.getSummary())
                .startsWith("## Degraded mode")
                .contains(longSummary + "1", longSummary + "2");
        assertThat(result.getSummary().length()).isGreaterThan(1_200);
    }

    @Nested
    class FinalValidationRequest {

        @Test
        void singleBatchRequestsReceiveAContractIndexForFinalValidation() {
            String diff =
                    "diff --git a/Api.java b/Api.java\n"
                            + "--- a/Api.java\n"
                            + "+++ b/Api.java\n"
                            + "@@ -1 +1 @@\n"
                            + "-void call(String old) {}\n"
                            + "+void call() {}\n"
                            + "diff --git a/Caller.java b/Caller.java\n"
                            + "--- a/Caller.java\n"
                            + "+++ b/Caller.java\n"
                            + "@@ -2 +2 @@\n"
                            + "-call(old);\n"
                            + "+call();\n";
            PRReviewRequest request = PRReviewRequest.builder(pr(), diff).build();

            PRReviewRequest validation = new ChunkedReviewService().finalValidationRequest(request);

            assertThat(validation.getDiff())
                    .contains(
                            "Changed files and contract-relevant changed lines.",
                            "FILE Api.java",
                            "FILE Caller.java",
                            "OLD 1 | -void call(String old) {}",
                            "NEW 2 | +call();")
                    .doesNotContain("diff --git");
        }
    }

    @Nested
    class DiffCoverageCopies {
        private final DiffCoverage omitted =
                new DiffCoverage(2, List.of("Big.java", "Huge.java"), 1_000_000, true);

        @Test
        void everyBatchAndTheReconciliationCarryTheValidationOmittedList() throws Exception {
            PRReviewRequest request =
                    PRReviewRequest.builder(pr(), sevenFileSignatureDiff() + omitted.trailer())
                            .build();
            List<PRReviewRequest> requests = new ArrayList<>();

            new ChunkedReviewService()
                    .review(
                            request,
                            ignored -> {},
                            current -> {
                                requests.add(current);
                                return new ReviewResult("ok", "APPROVE", List.of());
                            });

            assertThat(request.diffCoverage()).isEqualTo(omitted);
            assertThat(requests).hasSize(3);
            assertThat(requests)
                    .allSatisfy(
                            current -> {
                                assertThat(current.diffCoverage()).isEqualTo(omitted);
                                assertThat(current.getDiff()).doesNotContain("[pr-pilot:");
                                assertThat(ClaudeService.buildPrompt(current))
                                        .contains("<omitted_files>\n", "- Big.java", "- Huge.java")
                                        .doesNotContain("[pr-pilot:");
                            });
        }

        @Test
        void batchesTogetherCoverEveryKeptFile() {
            PRReviewRequest request =
                    PRReviewRequest.builder(pr(), sevenFileSignatureDiff() + omitted.trailer())
                            .build();

            List<ChunkedReviewService.DiffBatch> batches =
                    ChunkedReviewService.buildBatches(request.getDiff());

            assertThat(batches).hasSizeGreaterThan(1);
            assertThat(batches.stream().flatMap(batch -> batch.files().stream()).toList())
                    .containsExactlyInAnyOrder(
                            "Api.java",
                            "File1.java",
                            "File2.java",
                            "File3.java",
                            "File4.java",
                            "File5.java",
                            "Caller.java");
        }

        @Test
        void finalValidationKeepsTheOmittedList() {
            PRReviewRequest request =
                    PRReviewRequest.builder(pr(), sevenFileSignatureDiff() + omitted.trailer())
                            .build();

            PRReviewRequest validation = new ChunkedReviewService().finalValidationRequest(request);

            assertThat(validation.diffCoverage()).isEqualTo(omitted);
            assertThat(validation.getDiff()).doesNotContain("[pr-pilot:");
        }

        @Test
        void completeCoverageAddsNoSectionToAnyBatch() throws Exception {
            List<PRReviewRequest> requests = new ArrayList<>();

            new ChunkedReviewService()
                    .review(
                            PRReviewRequest.builder(pr(), sevenFileSignatureDiff()).build(),
                            ignored -> {},
                            current -> {
                                requests.add(current);
                                return new ReviewResult("ok", "APPROVE", List.of());
                            });

            assertThat(requests)
                    .hasSize(3)
                    .allSatisfy(
                            current -> {
                                assertThat(current.diffCoverage()).isEqualTo(DiffCoverage.NONE);
                                assertThat(ClaudeService.buildPrompt(current))
                                        .doesNotContain("<omitted_files>\n");
                            });
        }
    }

    private static PullRequest pr() {
        return new PullRequest(
                "Change API",
                "https://example.test/pr/1",
                "acme",
                "repo",
                1,
                "",
                "author",
                "",
                false);
    }

    private static String sevenFileSignatureDiff() {
        StringBuilder diff = new StringBuilder();
        for (int index = 0; index < 7; index++) {
            String file =
                    index == 0 ? "Api.java" : index == 6 ? "Caller.java" : "File" + index + ".java";
            diff.append("diff --git a/").append(file).append(" b/").append(file).append("\n");
            diff.append("--- a/").append(file).append("\n");
            diff.append("+++ b/").append(file).append("\n");
            diff.append("@@ -1 +1 @@\n");
            diff.append(
                    index == 0
                            ? "-void call(String old)\n+void call()\n"
                            : "-call(old)\n+call()\n");
        }
        return diff.toString();
    }
}
