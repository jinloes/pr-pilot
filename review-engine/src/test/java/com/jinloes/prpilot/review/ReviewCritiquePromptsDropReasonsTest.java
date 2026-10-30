package com.jinloes.prpilot.review;

import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.comment;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.oneRiskyHunk;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.request;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.result;
import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.ReviewResult;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for the opt-in dropped-finding reasons in {@link ReviewCritiquePrompts}. */
class ReviewCritiquePromptsDropReasonsTest {
    private final ReviewResult draft = result(comment("src/Api.java", 1, "high", "Bug."));

    @Nested
    class BuildCritiquePrompt {
        @Test
        void asksForDropReasonsAfterTheMainDirectiveWhenReporting() {
            String prompt =
                    ReviewCritiquePrompts.buildCritiquePrompt(request(oneRiskyHunk()), draft, true);

            assertThat(prompt)
                    .contains(ReviewCritiquePrompts.DROP_REASONS_DIRECTIVE)
                    .contains("\"droppedFindings\"");
            assertThat(prompt.indexOf(ReviewCritiquePrompts.DROP_REASONS_DIRECTIVE))
                    .isGreaterThan(prompt.indexOf("Re-derive \"verdict\""));
        }

        @Test
        void leavesTheProductionPromptUnchanged() {
            String withoutFlag =
                    ReviewCritiquePrompts.buildCritiquePrompt(request(oneRiskyHunk()), draft);

            assertThat(withoutFlag)
                    .doesNotContain("droppedFindings")
                    .isEqualTo(
                            ReviewCritiquePrompts.buildCritiquePrompt(
                                    request(oneRiskyHunk()), draft, false));
        }
    }
}
