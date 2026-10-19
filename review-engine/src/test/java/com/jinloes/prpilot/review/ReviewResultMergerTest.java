package com.jinloes.prpilot.review;

import static com.jinloes.prpilot.review.FindingSimilarityTest.FIRST_WORDING;
import static com.jinloes.prpilot.review.FindingSimilarityTest.SECOND_WORDING;
import static com.jinloes.prpilot.review.FindingSimilarityTest.comment;
import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.ReviewResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReviewResultMergerTest {
    private static ReviewResult result(LineComment... comments) {
        return new ReviewResult("summary", "COMMENT", new ArrayList<>(List.of(comments)));
    }

    private static LineComment sourced(LineComment comment, String... sources) {
        comment.setSources(List.of(sources));
        return comment;
    }

    @Nested
    class Merge {
        @Test
        void dropsExactDuplicatesAndKeepsTheBaselineSummary() {
            ReviewResult merged =
                    ReviewResultMerger.merge(
                            result(comment(1, "Close the stream.")),
                            new ReviewResult(
                                    "other",
                                    "APPROVE",
                                    new ArrayList<>(List.of(comment(1, " close the stream. ")))));

            assertThat(merged.getSummary()).isEqualTo("summary");
            assertThat(merged.getLineComments()).hasSize(1);
        }

        @Test
        void foldsANearbyRewordingIntoTheBaselineAndUnionsSources() {
            LineComment baseline = sourced(comment(237, FIRST_WORDING), "rules");
            LineComment followUp = sourced(comment(241, SECOND_WORDING), "claude-opus-5.5");

            ReviewResult merged = ReviewResultMerger.merge(result(baseline), result(followUp));

            assertThat(merged.getLineComments()).containsExactly(baseline);
            assertThat(baseline.getSources()).containsExactly("rules", "claude-opus-5.5");
        }

        @Test
        void keepsTheHigherPriorityRewording() {
            LineComment baseline = sourced(comment(237, FIRST_WORDING), "a");
            LineComment followUp = sourced(comment(241, SECOND_WORDING), "b");
            followUp.setSeverity("major");

            ReviewResult merged = ReviewResultMerger.merge(result(baseline), result(followUp));

            assertThat(merged.getLineComments()).containsExactly(followUp);
            assertThat(followUp.getSources()).containsExactly("b", "a");
        }

        @Test
        void foldsIntoTheNearestMatchingBaselineComment() {
            LineComment far = sourced(comment(233, FIRST_WORDING), "a");
            LineComment near = sourced(comment(240, FIRST_WORDING), "a");

            ReviewResult merged =
                    ReviewResultMerger.merge(
                            result(far, near), result(sourced(comment(241, SECOND_WORDING), "b")));

            assertThat(merged.getLineComments()).containsExactlyInAnyOrder(far, near);
            assertThat(near.getSources()).containsExactly("a", "b");
            assertThat(far.getSources()).containsExactly("a");
        }

        @Test
        void keepsSimilarCommentsWithinOnePass() {
            ReviewResult merged =
                    ReviewResultMerger.merge(
                            result(),
                            result(comment(237, FIRST_WORDING), comment(241, SECOND_WORDING)));

            assertThat(merged.getLineComments()).hasSize(2);
        }

        @Test
        void keepsDistinctFindingsAndCaps() {
            ReviewResult merged =
                    ReviewResultMerger.merge(
                            result(comment(1, FIRST_WORDING)),
                            result(comment(100, SECOND_WORDING), comment(3, "Unrelated nit.")),
                            2);

            assertThat(merged.getLineComments()).hasSize(2);
        }
    }
}
