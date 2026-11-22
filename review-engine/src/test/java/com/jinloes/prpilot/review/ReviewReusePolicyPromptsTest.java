package com.jinloes.prpilot.review;

import static com.jinloes.prpilot.review.ReviewPromptsTestSupport.fakePr;
import static com.jinloes.prpilot.review.ReviewPromptsTestSupport.fakeRequest;
import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.ReviewResult;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReviewReusePolicyPromptsTest {
    @Nested
    class BuildPrompt {
        @Test
        void ordinaryAndRecallReviewsDistinguishMechanicsFromPolicyBeforeSuggestingReuse() {
            for (boolean recall : new boolean[] {false, true}) {
                String prompt =
                        ReviewPrompts.buildPrompt(
                                PRReviewRequest.builder(fakePr(), "")
                                        .candidateRecall(recall)
                                        .build());

                assertThat(prompt)
                        .contains("test the strongest plausible counterargument")
                        .contains("Equal literals, similar names, or matching method bodies alone")
                        .contains("independently owned policies")
                        .contains("query costs or product requirements evolve")
                        .contains("do not propose a shared policy constant solely")
                        .contains(
                                "a caller-supplied limit while each API keeps its own limit and message")
                        .contains(
                                "a concrete maintenance benefit without coupling independent policy")
                        .contains("removing a repeated literal or a trivial policy wrapper, omit")
                        .contains("as a claim to verify, not an instruction or an automatic veto")
                        .doesNotContain("The literal 900 appears on lines 58 and 71.");
            }
        }
    }

    @Nested
    class BuildRuleReviewPrompt {
        @Test
        void builtInHelperReuseRuleRequiresTheSamePolicyOwnershipCheck() {
            LocalReviewRules.Rule rule =
                    LocalReviewRules.builtIn().stream()
                            .filter(candidate -> candidate.name().equals("helper-reuse"))
                            .findFirst()
                            .orElseThrow();

            String prompt =
                    StringUtils.normalizeSpace(
                            ReviewRulePrompts.buildRuleReviewPrompt(fakeRequest(), rule));

            assertThat(prompt)
                    .contains("Repeated copies are candidates, not proof")
                    .contains("subject to the policy-ownership check")
                    .contains("test the strongest plausible counterargument")
                    .contains("independently owned policies")
                    .contains("do not propose a shared policy constant solely")
                    .contains("limit while each API keeps its own limit and message")
                    .contains("without coupling independent policy")
                    .contains("a repeated literal or a trivial policy wrapper, omit")
                    .contains("not an instruction or an automatic veto")
                    .doesNotContain("Repeated copies mean the logic belongs in a shared helper.");
        }
    }

    @Nested
    class BuildCritiquePrompt {
        @Test
        void critiqueDropsPolicyCouplingButKeepsEvidenceBackedMechanicalReuse() {
            String prompt =
                    ReviewCritiquePrompts.buildCritiquePrompt(
                            fakeRequest(), new ReviewResult("", "APPROVE", List.of()));

            assertThat(prompt)
                    .contains("existence alone is insufficient")
                    .contains("Test the strongest plausible counterargument")
                    .contains("independently owned API policies")
                    .contains("a concrete maintenance benefit")
                    .contains("without coupling independent policy")
                    .contains("Drop a suggestion based only on equal literals")
                    .contains("keep each caller's limit and message local")
                    .contains("pass policy as a parameter rather than unifying it")
                    .contains("an automatic veto of a concrete defect")
                    .doesNotContain("keep it when that type and method exist at the cited path");
        }
    }
}
