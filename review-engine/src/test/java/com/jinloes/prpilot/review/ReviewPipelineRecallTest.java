package com.jinloes.prpilot.review;

import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.comment;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.emptyReviewJson;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.numberedComments;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.oneRiskyHunk;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.pass;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.pipeline;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.request;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.result;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.reviewJson;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.withSecondary;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.model.LineComment;
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
        }

        @Test
        void reportsEachDroppedFindingWhenAskedTo() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult =
                    pass(
                            result(
                                    comment("src/A.java", 1, "high", "Kept."),
                                    comment("src/A.java", 3, "high", "Dropped.")));
            provider.completions.add(reviewJson(comment("src/A.java", 1, "high", "Kept.")));
            List<String> statuses = new ArrayList<>();
            System.setProperty(ReviewPipelineService.REPORT_DROPPED_PROPERTY, "true");
            try {
                pipeline(provider)
                        .review(request(oneRiskyHunk()), false, true, false, statuses::add, null);
            } finally {
                System.clearProperty(ReviewPipelineService.REPORT_DROPPED_PROPERTY);
            }

            assertThat(statuses)
                    .filteredOn(status -> status.startsWith("Validation dropped"))
                    .containsExactly("Validation dropped finding at src/A.java:3 — Dropped.");
            assertThat(statuses)
                    .containsSubsequence(
                            "Validation dropped finding at src/A.java:3 — Dropped.",
                            "Validation kept 1 of 2 findings");
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
    }
}
