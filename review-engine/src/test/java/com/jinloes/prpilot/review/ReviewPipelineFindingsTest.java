package com.jinloes.prpilot.review;

import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.comment;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.fileDiff;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.result;
import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.ReviewResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReviewPipelineFindingsTest {
    @Nested
    class UnattributeUnmatchedRuleFindings {
        private final List<LocalReviewRules.Rule> rules =
                List.of(new LocalReviewRules.Rule("perf", "perf.yaml", "d", "t", "p"));

        private LineComment finding(int line, String rationale, List<String> sources) {
            LineComment comment = new LineComment("src/A.java", line, "issue", "Body " + line);
            comment.setCategory("performance");
            comment.setRationale(rationale);
            comment.setSources(sources);
            return comment;
        }

        @Test
        void clearsTheFallbackLabelOnAnUnmatchedRuleFinding() {
            LineComment rule = finding(10, "Slow. (rule: perf)", List.of("claude-opus"));

            ReviewPipelineFindings.unattributeUnmatchedRuleFindings(
                    new ReviewResult("", "COMMENT", List.of(rule)), List.of(), List.of(), rules);

            assertThat(rule.getSources()).isEmpty();
        }

        @Test
        void clearsTheFallbackLabelWhenCritiqueRewordedTheRuleFindingWithoutItsTag() {
            List<ReviewPipelineFindings.RuleSite> sites =
                    ReviewPipelineFindings.RuleSite.of(
                            List.of(finding(10, "Slow. (rule: perf)", List.of())));
            LineComment reworded = finding(11, "Reworded by critique.", List.of("claude-opus"));

            ReviewPipelineFindings.unattributeUnmatchedRuleFindings(
                    new ReviewResult("", "COMMENT", List.of(reworded)), List.of(), sites, rules);

            assertThat(reworded.getSources()).isEmpty();
        }

        @Test
        void ruleSitesAreSnapshotsUnaffectedByLaterEditsToTheRuleComment() {
            LineComment original = finding(10, "Slow. (rule: perf)", List.of());
            List<ReviewPipelineFindings.RuleSite> sites =
                    ReviewPipelineFindings.RuleSite.of(List.of(original));
            original.setLine(40);
            LineComment atOriginalSite = finding(10, "Reworded.", List.of("claude-opus"));
            LineComment nearEditedLine = finding(40, "Other.", List.of("claude-opus"));

            ReviewPipelineFindings.unattributeUnmatchedRuleFindings(
                    new ReviewResult("", "COMMENT", List.of(atOriginalSite, nearEditedLine)),
                    List.of(),
                    sites,
                    rules);

            assertThat(atOriginalSite.getSources()).isEmpty();
            assertThat(nearEditedLine.getSources()).containsExactly("claude-opus");
        }

        @Test
        void aRuleSiteDoesNotClaimAFindingInAnotherCategoryOrBeyondTheLineWindow() {
            List<ReviewPipelineFindings.RuleSite> sites =
                    ReviewPipelineFindings.RuleSite.of(List.of(finding(10, "x", List.of())));
            LineComment far = finding(13, "Far.", List.of("claude-opus"));
            LineComment otherCategory = finding(10, "Other.", List.of("claude-opus"));
            otherCategory.setCategory("security");

            ReviewPipelineFindings.unattributeUnmatchedRuleFindings(
                    new ReviewResult("", "COMMENT", List.of(far, otherCategory)),
                    List.of(),
                    sites,
                    rules);

            assertThat(List.of(far, otherCategory))
                    .allSatisfy(c -> assertThat(c.getSources()).containsExactly("claude-opus"));
        }

        @Test
        void keepsLabelsOnARuleFindingAReviewerCandidateMatches() {
            LineComment rule = finding(10, "rule: perf", List.of("gpt-5.5"));
            List<ReviewerAttribution.Evidence> evidence =
                    ReviewerAttribution.Evidence.snapshot(
                            List.of(finding(11, "Second.", List.of("gpt-5.5"))));

            ReviewPipelineFindings.unattributeUnmatchedRuleFindings(
                    new ReviewResult("", "COMMENT", List.of(rule)),
                    evidence,
                    ReviewPipelineFindings.RuleSite.of(List.of(finding(10, "x", List.of()))),
                    rules);

            assertThat(rule.getSources()).containsExactly("gpt-5.5");
        }

        @Test
        void leavesNonRuleFindingsAndUnknownRuleTagsAlone() {
            LineComment plain = finding(10, "Slow.", List.of("claude-opus"));
            LineComment unknown = finding(20, "Slow. (rule: other)", List.of("claude-opus"));
            LineComment blank = finding(30, null, List.of("claude-opus"));

            ReviewPipelineFindings.unattributeUnmatchedRuleFindings(
                    new ReviewResult("", "COMMENT", List.of(plain, unknown, blank)),
                    List.of(),
                    List.of(),
                    rules);

            assertThat(List.of(plain, unknown, blank))
                    .allSatisfy(c -> assertThat(c.getSources()).containsExactly("claude-opus"));
        }
    }

    @Nested
    class RestoreHygieneFindings {
        private final InspectionManifest manifest = InspectionManifest.fromDiff(fileDiff(2));

        @Test
        void restoresADroppedConfirmedFinding() {
            ReviewResult validated = new ReviewResult("ok", "APPROVE", new ArrayList<>());
            ReviewResult hygiene = result(finding("F0.java", 1, "performance", "medium"));

            ReviewResult restored =
                    ReviewPipelineFindings.restoreHygieneFindings(validated, hygiene, manifest);

            assertThat(restored.getSummary()).isEqualTo("ok");
            assertThat(restored.getVerdict()).isEqualTo("COMMENT");
            assertThat(restored.getLineComments())
                    .singleElement()
                    .extracting(LineComment::getFile)
                    .isEqualTo("F0.java");
        }

        @Test
        void skipsLowConfidenceAndUnanchoredFindings() {
            ReviewResult validated = result();
            ReviewResult hygiene =
                    result(
                            finding("F0.java", 1, "performance", "low"),
                            finding("F0.java", 9, "performance", "high"),
                            finding("Gone.java", 1, "performance", "high"));

            assertThat(ReviewPipelineFindings.restoreHygieneFindings(validated, hygiene, manifest))
                    .isSameAs(validated);
        }

        @Test
        void treatsANearbySameCategoryFindingAsTheValidatorsRewording() {
            ReviewResult validated =
                    result(
                            finding(
                                    "F1.java",
                                    1 + ReviewPipelineFindings.HYGIENE_COVER_LINES,
                                    "performance",
                                    "high"));
            ReviewResult hygiene = result(finding("F1.java", 1, "performance", "medium"));

            assertThat(ReviewPipelineFindings.restoreHygieneFindings(validated, hygiene, manifest))
                    .isSameAs(validated);
        }

        @Test
        void treatsAValidatedRewordingFurtherAwayAsCoveringTheFinding() {
            ReviewResult validated =
                    result(
                            FindingSimilarityTest.comment(
                                    "F1.java",
                                    5,
                                    "suggestion",
                                    "maintainability",
                                    FindingSimilarityTest.SECOND_WORDING));
            ReviewResult hygiene =
                    result(
                            FindingSimilarityTest.comment(
                                    "F1.java",
                                    1,
                                    "suggestion",
                                    "maintainability",
                                    FindingSimilarityTest.FIRST_WORDING));

            assertThat(ReviewPipelineFindings.restoreHygieneFindings(validated, hygiene, manifest))
                    .isSameAs(validated);
        }

        @Test
        void keepsAHygieneFindingNextToAFindingOfAnotherCategory() {
            ReviewResult validated = result(finding("F1.java", 1, "correctness", "high"));
            ReviewResult hygiene = result(finding("F1.java", 1, "maintainability", "medium"));

            assertThat(
                            ReviewPipelineFindings.restoreHygieneFindings(
                                            validated, hygiene, manifest)
                                    .getLineComments())
                    .extracting(LineComment::getCategory)
                    .containsExactlyInAnyOrder("correctness", "maintainability");
        }

        @Test
        void restoresOnlyOneOfTwoHygieneDuplicatesAtTheSameSite() {
            ReviewResult hygiene =
                    result(
                            finding("F0.java", 1, "performance", "medium"),
                            finding("F0.java", 1, "performance", "high"));

            assertThat(
                            ReviewPipelineFindings.restoreHygieneFindings(
                                            result(), hygiene, manifest)
                                    .getLineComments())
                    .hasSize(1);
        }

        private static ReviewResult result(LineComment... comments) {
            return new ReviewResult("ok", "COMMENT", new ArrayList<>(List.of(comments)));
        }

        private static LineComment finding(
                String file, int line, String category, String confidence) {
            LineComment comment = new LineComment(file, line, "suggestion", category + " finding");
            comment.setSeverity("minor");
            comment.setCategory(category);
            comment.setConfidence(confidence);
            return comment;
        }
    }

    @Nested
    class DroppedStatuses {
        @Test
        void droppedStatusesSkipKeptLocationsDeduplicateAndAbbreviate() {
            String longBody = "word ".repeat(60);
            ReviewResult draft =
                    result(
                            comment("src/A.java", 1, "high", "Kept."),
                            comment("src/A.java", 4, "high", "First\n  dropped."),
                            comment("src/A.java", 4, "high", "Duplicate location."),
                            comment("src/B.java", 9, "high", longBody));
            ReviewResult validated = result(comment("src/A.java", 1, "high", "Kept."));

            List<String> statuses = ReviewPipelineFindings.droppedStatuses(draft, validated);

            assertThat(statuses).hasSize(2);
            assertThat(statuses.get(0))
                    .isEqualTo("Validation dropped finding at src/A.java:4 — First dropped.");
            assertThat(statuses.get(1))
                    .startsWith("Validation dropped finding at src/B.java:9 — word word")
                    .endsWith("...");
            assertThat(statuses.get(1).substring(statuses.get(1).indexOf("— ") + 2)).hasSize(120);
        }

        @Test
        void droppedStatusesAreEmptyWhenEverythingIsKept() {
            ReviewResult draft = result(comment("src/A.java", 1, "high", "Kept."));

            assertThat(ReviewPipelineFindings.droppedStatuses(draft, draft)).isEmpty();
        }
    }
}
