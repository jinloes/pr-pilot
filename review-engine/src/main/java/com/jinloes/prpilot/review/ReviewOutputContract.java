package com.jinloes.prpilot.review;

/**
 * The JSON response contract shared by every review stage that emits findings (first pass, hygiene,
 * rule review, self-critique), so {@link ReviewResultParser} can read all of them.
 */
final class ReviewOutputContract {

    private ReviewOutputContract() {}

    /**
     * The output contract half of the review prompt: how to format the JSON response. Shared by the
     * full first-pass instructions ({@link ReviewInstructions#REVIEW_INSTRUCTIONS}) and the lean
     * self-critique prompt ({@link ReviewCritiquePrompts#buildCritiquePrompt}) so both passes emit
     * the same schema without resending the full fresh-review narrative.
     */
    static final String OUTPUT_CONTRACT = PromptResources.load("output-contract.md");
}
