package com.jinloes.prpilot.review;

import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.comment;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.emptyReviewJson;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.numberedComments;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.oneRiskyHunk;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.pass;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.pipeline;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.request;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.result;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.withSecondary;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.model.CiAnnotation;
import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.ReviewResult;
import com.jinloes.prpilot.review.ReviewPipelineTestSupport.FakeProvider;
import com.jinloes.prpilot.review.ReviewPipelineTestSupport.FakeSecondary;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Recall-mode tests for {@link ReviewPipelineService}. */
class ReviewPipelineRecallTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Nested
    class Review {
        @Test
        void critiqueFailureStripsUnconfirmedLowConfidenceCandidates() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult =
                    pass(
                            result(
                                    comment("src/A.java", 1, "high", "Confirmed finding."),
                                    comment("src/A.java", 1, "low", "Unconfirmed candidate.")));
            provider.completionFailure = new IOException("validator unavailable");

            ReviewResult result =
                    pipeline(provider)
                            .review(request(oneRiskyHunk()), false, true, false, s -> {}, null);

            assertThat(result.getLineComments())
                    .extracting(LineComment::getBody)
                    .containsExactly("Confirmed finding.");
        }

        @Test
        void supervisorFollowUpKeepsRecallModeWhenSelfCritiqueIsOn() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult =
                    new ReviewPassResult(
                            new ReviewResult("baseline", "APPROVE", List.of()),
                            new InspectionLedger(true, Set.of(), List.of()));
            provider.completions.add(emptyReviewJson());
            provider.completions.add(emptyReviewJson());

            pipeline(provider).review(request(oneRiskyHunk()), false, true, true, s -> {}, null);

            assertThat(provider.completeCalls).hasSize(2);
            assertThat(provider.completeCalls.get(0).prompt())
                    .doesNotContain("<draft_review>")
                    .contains("<recall_mode>");
        }

        @Test
        void recomputesTheVerdictAfterValidationEvenWhenNothingIsDropped() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult =
                    pass(result(comment("src/A.java", 1, "medium", "Confirmed minor finding.")));
            provider.completions.add(
                    JSON.writeValueAsString(
                            Map.of(
                                    "summary",
                                    "validated",
                                    "verdict",
                                    "APPROVE",
                                    "lineComments",
                                    List.of(
                                            comment(
                                                    "src/A.java",
                                                    1,
                                                    "medium",
                                                    "Confirmed minor finding.")))));

            ReviewResult result =
                    pipeline(provider)
                            .review(request(oneRiskyHunk()), false, true, false, s -> {}, null);

            assertThat(result.getLineComments()).hasSize(1);
            assertThat(result.getVerdict()).isEqualTo("COMMENT");
        }

        @Test
        void recallStaysOffWithoutSelfCritiqueOrASecondReviewer() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult =
                    pass(result(comment("src/A.java", 1, "low", "Low-confidence note.")));

            ReviewResult result =
                    pipeline(provider)
                            .review(request(oneRiskyHunk()), false, false, false, s -> {}, null);

            assertThat(provider.primaryRequests.get(0).isCandidateRecall()).isFalse();
            assertThat(provider.completeCalls).isEmpty();
            assertThat(provider.hygieneCalls).isEmpty();
            assertThat(result.getLineComments()).hasSize(1);
        }

        @Test
        void reportsNoDroppedFindingsUnlessAskedTo() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult = pass(result(comment("src/A.java", 1, "high", "Bug.")));
            provider.completions.add(emptyReviewJson());
            List<String> statuses = new ArrayList<>();

            pipeline(provider)
                    .review(request(oneRiskyHunk()), false, true, false, statuses::add, null);

            assertThat(statuses).noneMatch(status -> status.startsWith("Validation dropped"));
            assertThat(provider.completeCalls.get(0).prompt())
                    .doesNotContain(ReviewCritiquePrompts.DROP_REASONS_DIRECTIVE);
        }

        @Test
        void reportsEachDroppedFindingWithTheValidatorsReasonWhenAskedTo() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult =
                    pass(
                            result(
                                    comment("src/A.java", 1, "high", "Kept."),
                                    comment("src/A.java", 3, "high", "Dropped.")));
            provider.completions.add(
                    JSON.writeValueAsString(
                            Map.of(
                                    "summary",
                                    "validated",
                                    "verdict",
                                    "COMMENT",
                                    "lineComments",
                                    List.of(comment("src/A.java", 1, "high", "Kept.")),
                                    "droppedFindings",
                                    List.of(
                                            Map.of(
                                                    "file", "src/A.java",
                                                    "line", 3,
                                                    "reason", "The caller already checks it.")))));
            List<String> statuses = new ArrayList<>();
            System.setProperty(ReviewPipelineService.REPORT_DROPPED_PROPERTY, "true");
            try {
                pipeline(provider)
                        .review(request(oneRiskyHunk()), false, true, false, statuses::add, null);
            } finally {
                System.clearProperty(ReviewPipelineService.REPORT_DROPPED_PROPERTY);
            }

            String dropped =
                    "Validation dropped finding at src/A.java:3 — Dropped."
                            + " — Reason: The caller already checks it.";
            assertThat(statuses)
                    .filteredOn(status -> status.startsWith("Validation dropped"))
                    .containsExactly(dropped);
            assertThat(statuses).containsSubsequence(dropped, "Validation kept 1 of 2 findings");
            assertThat(provider.completeCalls.get(0).prompt())
                    .contains(ReviewCritiquePrompts.DROP_REASONS_DIRECTIVE);
        }

        @Test
        void passesAllFortyMergedCandidatesToTheCritique() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult = pass(numberedComments("primary", "src/A.java", 20));
            FakeSecondary secondary = new FakeSecondary();
            secondary.result = pass(numberedComments("second", "src/B.java", 20));
            provider.completions.add(emptyReviewJson());

            withSecondary(provider, secondary)
                    .review(request(oneRiskyHunk()), false, false, false, s -> {}, null);

            String critique = provider.completeCalls.get(0).prompt();
            for (int index = 0; index < 20; index++) {
                assertThat(critique)
                        .contains("primary finding #" + index + ";")
                        .contains("second finding #" + index + ";");
            }
        }

        @Test
        void capsTheFinalReviewAtTwentyFindingsWithSupervisionOnAndCritiqueOff() throws Exception {
            StringBuilder diff =
                    new StringBuilder(
                            "diff --git a/src/A.java b/src/A.java\n--- a/src/A.java\n"
                                    + "+++ b/src/A.java\n@@ -0,0 +1,25 @@\n");
            for (int line = 1; line <= 25; line++) diff.append("+int v").append(line).append(";\n");
            InspectionManifest manifest = InspectionManifest.fromDiff(diff.toString());
            FakeProvider provider = new FakeProvider();
            ReviewResult many = numberedComments("primary", "src/A.java", 25);
            many.getLineComments().get(24).setSeverity("blocker");
            provider.primaryResult =
                    new ReviewPassResult(
                            many,
                            new InspectionLedger(
                                    true,
                                    Set.of(
                                            manifest.files().get(0).id(),
                                            manifest.files().get(0).hunks().get(0).id()),
                                    List.of()));

            ReviewResult result =
                    pipeline(provider)
                            .review(request(diff.toString()), false, false, true, s -> {}, null);

            assertThat(provider.completeCalls).isEmpty();
            assertThat(result.getLineComments()).hasSize(20);
            assertThat(result.getLineComments().get(0).getBody()).isEqualTo("primary finding #24;");
        }

        @Test
        void suppressesCiDuplicateBeforeRestoringNearbyHygieneFinding() throws Exception {
            String diff =
                    """
                    diff --git a/src/Api.java b/src/Api.java
                    --- a/src/Api.java
                    +++ b/src/Api.java
                    @@ -1,2 +1,2 @@
                    -public void call() {}
                    +public void call(String token) {
                    +    logger.error("token=" + token);
                    """;
            LineComment ciDuplicate =
                    new LineComment(
                            "src/Api.java",
                            1,
                            "suggestion",
                            "Unused parameter token triggers Checkstyle unused parameter rule.");
            ciDuplicate.setRationale("Repeats CI failure");
            ciDuplicate.setSeverity("minor");
            ciDuplicate.setCategory("maintainability");
            ciDuplicate.setConfidence("high");
            LineComment realHygiene =
                    new LineComment(
                            "src/Api.java",
                            2,
                            "suggestion",
                            "Avoid logging secret token values at error level without redaction.");
            realHygiene.setRationale("Sensitive credential logged");
            realHygiene.setSeverity("minor");
            realHygiene.setCategory("maintainability");
            realHygiene.setConfidence("high");
            FakeProvider provider = new FakeProvider();
            provider.primaryResult = pass(result(ciDuplicate));
            provider.hygieneCompletions.add(
                    JSON.writeValueAsString(
                            Map.of(
                                    "summary",
                                    "hygiene",
                                    "verdict",
                                    "COMMENT",
                                    "lineComments",
                                    List.of(realHygiene))));
            provider.completions.add(
                    JSON.writeValueAsString(
                            Map.of(
                                    "summary",
                                    "validated",
                                    "verdict",
                                    "COMMENT",
                                    "lineComments",
                                    List.of(ciDuplicate))));
            PRReviewRequest req =
                    request(diff).toBuilder()
                            .ciAnnotations(
                                    List.of(
                                            new CiAnnotation(
                                                    "src/Api.java",
                                                    1,
                                                    "failure",
                                                    "Unused parameter token triggers Checkstyle"
                                                            + " unused parameter rule.")))
                            .build();

            ReviewResult result = pipeline(provider).review(req, false, true, false, s -> {}, null);

            assertThat(result.getLineComments())
                    .singleElement()
                    .extracting(LineComment::getBody)
                    .isEqualTo(
                            "Avoid logging secret token values at error level without redaction.");
        }

        @Test
        void suppressesCiDuplicatesBeforeCappingAtTwentyFindings() throws Exception {
            StringBuilder diff =
                    new StringBuilder(
                            "diff --git a/src/A.java b/src/A.java\n--- a/src/A.java\n"
                                    + "+++ b/src/A.java\n@@ -0,0 +1,22 @@\n");
            for (int line = 1; line <= 22; line++) diff.append("+int v").append(line).append(";\n");
            ReviewResult many = numberedComments("primary", "src/A.java", 21);
            LineComment ciDuplicate =
                    new LineComment(
                            "src/A.java",
                            1,
                            "issue",
                            "Checkstyle reports missing final modifier on parameter value.");
            ciDuplicate.setRationale("Repeats CI failure");
            ciDuplicate.setSeverity("blocker");
            ciDuplicate.setCategory("correctness");
            ciDuplicate.setConfidence("high");
            List<LineComment> all = new ArrayList<>();
            all.add(ciDuplicate);
            for (int i = 0; i < 20; i++) {
                LineComment item = many.getLineComments().get(i);
                item.setLine(i + 2);
                all.add(item);
            }
            FakeProvider provider = new FakeProvider();
            provider.primaryResult = pass(new ReviewResult("many", "REQUEST_CHANGES", all));
            PRReviewRequest req =
                    request(diff.toString()).toBuilder()
                            .ciAnnotations(
                                    List.of(
                                            new CiAnnotation(
                                                    "src/A.java",
                                                    1,
                                                    "failure",
                                                    "Checkstyle reports missing final modifier on"
                                                            + " parameter value.")))
                            .build();

            ReviewResult result =
                    pipeline(provider).review(req, false, false, false, s -> {}, null);

            assertThat(result.getLineComments())
                    .hasSize(20)
                    .noneMatch(c -> c.getBody().contains("Checkstyle reports missing final"));
        }

        @Test
        void followsUpHighRiskUncommentedHunkWhenMultiFileLedgerIsOmitted() throws Exception {
            String diff =
                    """
                    diff --git a/src/Commented.java b/src/Commented.java
                    --- a/src/Commented.java
                    +++ b/src/Commented.java
                    @@ -1 +1 @@
                    -private void oldOne() {}
                    +public void newOne() {}
                    diff --git a/src/Missed.java b/src/Missed.java
                    --- a/src/Missed.java
                    +++ b/src/Missed.java
                    @@ -1 +1 @@
                    -private void oldTwo() {}
                    +public void newTwo() {}
                    """;
            FakeProvider provider = new FakeProvider();
            provider.primaryResult =
                    ReviewPassResult.withoutLedger(
                            result(comment("src/Commented.java", 1, "high", "Already reviewed.")));
            provider.completions.add(
                    ReviewPipelineTestSupport.reviewJsonWithFinding("src/Missed.java", 1));

            ReviewResult result =
                    pipeline(provider).review(request(diff), false, false, true, s -> {}, null);

            assertThat(provider.completeCalls).hasSize(1);
            assertThat(provider.completeCalls.get(0).prompt())
                    .contains("src/Missed.java")
                    .doesNotContain("diff --git a/src/Commented.java");
            assertThat(result.getLineComments())
                    .extracting(LineComment::getFile)
                    .containsExactlyInAnyOrder("src/Commented.java", "src/Missed.java");
        }
    }
}
