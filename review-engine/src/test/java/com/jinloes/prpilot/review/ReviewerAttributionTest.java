package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.ReviewResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReviewerAttributionTest {
    private static final ReviewerAttribution.Labels LABELS =
            ReviewerAttribution.Labels.of("claude-opus", "gpt-5");

    private static LineComment comment(String file, int line, String category, String body) {
        LineComment comment = new LineComment(file, line, "issue", body);
        comment.setSeverity("minor");
        comment.setCategory(category);
        comment.setConfidence("medium");
        return comment;
    }

    private static ReviewResult result(LineComment... comments) {
        return new ReviewResult("summary", "COMMENT", new ArrayList<>(List.of(comments)));
    }

    @Nested
    class LabelsOf {

        @Test
        void keepsDistinctLabels() {
            assertThat(LABELS).isEqualTo(new ReviewerAttribution.Labels("claude-opus", "gpt-5"));
        }

        @Test
        void disambiguatesEqualLabels() {
            assertThat(ReviewerAttribution.Labels.of("gpt-5", " gpt-5 "))
                    .isEqualTo(new ReviewerAttribution.Labels("gpt-5", "gpt-5 (second)"));
        }
    }

    @Nested
    class Collapse {

        @Test
        void tagsUnmatchedCommentsWithTheirReviewer() {
            LineComment primary = comment("A.java", 1, "correctness", "p");
            LineComment second = comment("B.java", 1, "correctness", "s");

            ReviewerAttribution.Collapsed collapsed =
                    ReviewerAttribution.collapse(result(primary), result(second), LABELS);

            assertThat(collapsed.primary().getLineComments()).containsExactly(primary);
            assertThat(collapsed.second().getLineComments()).containsExactly(second);
            assertThat(primary.getSources()).containsExactly("claude-opus");
            assertThat(second.getSources()).containsExactly("gpt-5");
        }

        @Test
        void collapsesSameFindingWithinTwoLines() {
            LineComment atSameLine = comment("A.java", 10, "correctness", "p1");
            LineComment twoAway = comment("A.java", 20, "correctness", "p2");

            ReviewerAttribution.Collapsed collapsed =
                    ReviewerAttribution.collapse(
                            result(atSameLine, twoAway),
                            result(
                                    comment("A.java", 10, "correctness", "s1"),
                                    comment("A.java", 22, "correctness", "s2")),
                            LABELS);

            assertThat(collapsed.primary().getLineComments())
                    .containsExactly(atSameLine, twoAway)
                    .allSatisfy(
                            kept ->
                                    assertThat(kept.getSources())
                                            .containsExactly("claude-opus", "gpt-5"));
            assertThat(collapsed.second().getLineComments()).isEmpty();
        }

        @Test
        void doesNotCollapseThreeLinesApartOrAcrossFilesOrCategories() {
            LineComment primary = comment("A.java", 10, "correctness", "p");

            ReviewerAttribution.Collapsed collapsed =
                    ReviewerAttribution.collapse(
                            result(primary),
                            result(
                                    comment("A.java", 13, "correctness", "three away"),
                                    comment("B.java", 10, "correctness", "other file"),
                                    comment("A.java", 10, "security", "other category")),
                            LABELS);

            assertThat(collapsed.second().getLineComments()).hasSize(3);
            assertThat(primary.getSources()).containsExactly("claude-opus");
        }

        @Test
        void neverCollapsesBlankCategories() {
            ReviewerAttribution.Collapsed collapsed =
                    ReviewerAttribution.collapse(
                            result(comment("A.java", 10, "", "p")),
                            result(comment("A.java", 10, "", "s")),
                            LABELS);

            assertThat(collapsed.second().getLineComments()).hasSize(1);
        }

        @Test
        void matchesOneToOneNearestFirst() {
            LineComment far = comment("A.java", 8, "correctness", "far");
            LineComment near = comment("A.java", 11, "correctness", "near");

            ReviewerAttribution.Collapsed collapsed =
                    ReviewerAttribution.collapse(
                            result(far, near),
                            result(
                                    comment("A.java", 10, "correctness", "s1"),
                                    comment("A.java", 10, "correctness", "s2"),
                                    comment("A.java", 10, "correctness", "s3")),
                            LABELS);

            assertThat(collapsed.primary().getLineComments())
                    .extracting(LineComment::getBody)
                    .containsExactly("far", "near");
            assertThat(collapsed.second().getLineComments())
                    .extracting(LineComment::getBody)
                    .containsExactly("s3");
        }

        @Test
        void breaksDistanceTiesByListOrder() {
            LineComment first = comment("A.java", 9, "correctness", "first");
            LineComment second = comment("A.java", 11, "correctness", "second");

            ReviewerAttribution.collapse(
                    result(first, second),
                    result(comment("A.java", 10, "correctness", "s")),
                    LABELS);

            assertThat(first.getSources()).hasSize(2);
            assertThat(second.getSources()).containsExactly("claude-opus");
        }

        @Test
        void keepsTheHigherPriorityCommentAndThePrimaryOnTies() {
            LineComment weakPrimary = comment("A.java", 1, "correctness", "weak primary");
            LineComment strongSecond = comment("A.java", 1, "correctness", "strong second");
            strongSecond.setSeverity("blocker");
            LineComment tiePrimary = comment("B.java", 1, "correctness", "tie primary");

            ReviewerAttribution.Collapsed collapsed =
                    ReviewerAttribution.collapse(
                            result(weakPrimary, tiePrimary),
                            result(strongSecond, comment("B.java", 1, "correctness", "tie second")),
                            LABELS);

            assertThat(collapsed.primary().getLineComments())
                    .extracting(LineComment::getBody)
                    .containsExactly("strong second", "tie primary");
            assertThat(strongSecond.getSources()).containsExactly("claude-opus", "gpt-5");
        }

        @Test
        void exactDuplicatesAreStillRemovedByTheMerge() {
            ReviewerAttribution.Collapsed collapsed =
                    ReviewerAttribution.collapse(
                            result(comment("A.java", 1, "", "Same body")),
                            result(comment("A.java", 1, "", "Same body")),
                            LABELS);

            assertThat(
                            ReviewResultMerger.merge(collapsed.primary(), collapsed.second(), 40)
                                    .getLineComments())
                    .hasSize(1);
        }
    }

    @Nested
    class Reattach {

        @Test
        void unionsMatchingCandidateSourcesPrimaryFirst() {
            LineComment fromSecond = comment("A.java", 10, "correctness", "s");
            fromSecond.setSources(List.of("gpt-5"));
            LineComment fromPrimary = comment("A.java", 12, "correctness", "p");
            fromPrimary.setSources(List.of("claude-opus"));
            LineComment rewritten = comment("A.java", 11, "correctness", "rewritten by critique");

            ReviewerAttribution.reattach(
                    result(rewritten), snapshot(fromSecond, fromPrimary), "claude-opus");

            assertThat(rewritten.getSources()).containsExactly("claude-opus", "gpt-5");
        }

        @Test
        void keepsSecondOnlyAttribution() {
            LineComment candidate = comment("A.java", 10, "correctness", "s");
            candidate.setSources(List.of("gpt-5"));
            LineComment rewritten = comment("A.java", 10, "correctness", "rewritten");

            ReviewerAttribution.reattach(result(rewritten), snapshot(candidate), "claude-opus");

            assertThat(rewritten.getSources()).containsExactly("gpt-5");
        }

        @Test
        void creditsUnmatchedCommentsToThePrimary() {
            LineComment unattributed = comment("A.java", 10, "correctness", "hygiene");
            LineComment far = comment("A.java", 30, "correctness", "critique-added");
            LineComment candidate = comment("A.java", 50, "correctness", "c");
            candidate.setSources(List.of("gpt-5"));

            ReviewerAttribution.reattach(
                    result(unattributed, far), snapshot(unattributed, candidate), "claude-opus");

            assertThat(unattributed.getSources()).containsExactly("claude-opus");
            assertThat(far.getSources()).containsExactly("claude-opus");
        }

        @Test
        void matchesOnlyByTheCollapseRuleSoBlankCategoriesFallBackToThePrimary() {
            LineComment kept = comment("A.java", 10, "", "kept unchanged");
            kept.setSources(List.of("gpt-5"));

            ReviewerAttribution.reattach(result(kept), snapshot(kept), "claude-opus");

            assertThat(kept.getSources()).containsExactly("claude-opus");
        }

        @Test
        void neverPropagatesSourcesAssignedDuringReattachment() {
            LineComment major = comment("A.java", 14, "correctness", "major");
            major.setSources(List.of("claude-opus", "gpt-5"));
            LineComment minor = comment("A.java", 12, "correctness", "minor");
            minor.setSources(List.of("claude-opus"));
            LineComment nit = comment("A.java", 10, "correctness", "nit");
            nit.setSources(List.of("claude-opus"));
            List<ReviewerAttribution.Evidence> evidence = snapshot(major, minor, nit);

            ReviewerAttribution.reattach(result(major, minor, nit), evidence, "claude-opus");

            assertThat(major.getSources()).containsExactly("claude-opus", "gpt-5");
            assertThat(minor.getSources()).containsExactly("claude-opus", "gpt-5");
            assertThat(nit.getSources()).containsExactly("claude-opus");
        }

        @Test
        void attributionDoesNotDependOnCommentOrder() {
            LineComment major = comment("A.java", 14, "correctness", "major");
            major.setSources(List.of("claude-opus", "gpt-5"));
            LineComment minor = comment("A.java", 12, "correctness", "minor");
            minor.setSources(List.of("claude-opus"));
            LineComment nit = comment("A.java", 10, "correctness", "nit");
            nit.setSources(List.of("claude-opus"));
            List<ReviewerAttribution.Evidence> evidence = snapshot(nit, minor, major);

            ReviewerAttribution.reattach(result(nit, minor, major), evidence, "claude-opus");

            assertThat(nit.getSources()).containsExactly("claude-opus");
            assertThat(minor.getSources()).containsExactly("claude-opus", "gpt-5");
            assertThat(major.getSources()).containsExactly("claude-opus", "gpt-5");
        }

        @Test
        void snapshotSkipsUnattributedCandidatesAndCopiesSources() {
            LineComment attributed = comment("A.java", 10, "correctness", "a");
            attributed.setSources(List.of("gpt-5"));
            LineComment unattributed = comment("A.java", 11, "correctness", "u");

            List<ReviewerAttribution.Evidence> evidence = snapshot(attributed, unattributed);
            attributed.setSources(List.of("changed"));

            assertThat(evidence)
                    .containsExactly(
                            new ReviewerAttribution.Evidence(
                                    "A.java", "correctness", 10, List.of("gpt-5")));
        }
    }

    private static List<ReviewerAttribution.Evidence> snapshot(LineComment... candidates) {
        return ReviewerAttribution.Evidence.snapshot(List.of(candidates));
    }
}
