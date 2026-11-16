package com.jinloes.prpilot.review;

/**
 * First-pass review instruction text: the reviewer preamble, the Pass A/B checklists, and the
 * recall-mode variant, loaded from {@code /review-prompts/*.md}. {@link ReviewPrompts#buildPrompt}
 * starts every first-pass prompt with it.
 */
final class ReviewInstructions {

    private ReviewInstructions() {}

    private static final String REVIEW_PREAMBLE = PromptResources.load("review-preamble.md");

    /**
     * Security defect classes for Pass B. Each is gated on concrete evidence: unanchored security
     * advice (rate limits, theoretical denial of service, validation with no sink) is a common
     * false positive in LLM security review, so it is excluded outright.
     */
    static final String SECURITY_CHECKLIST = PromptResources.load("security-checklist.md");

    /**
     * Defect classes a single "look for bugs" instruction tends to skip, taken from the bug-hunt
     * review criteria. Each still has to meet the evidence policy; the list only widens what Pass B
     * looks for.
     */
    static final String BUG_HUNT_CHECKLIST =
            PromptResources.load("bug-hunt-checklist.md")
                    .replace("{{SECURITY_CHECKLIST}}", SECURITY_CHECKLIST);

    /**
     * Splits the first pass into a guideline-compliance pass, an exhaustive bug hunt, and the
     * {@link ReviewHygienePrompts#HYGIENE_PASS}. A single undifferentiated pass tends to stop at
     * the first salient finding; naming the passes and requiring the bug hunt to walk every
     * manifest target is what keeps later files from being skimmed.
     */
    private static final String PASS_A_AND_B =
            PromptResources.load("pass-a-and-b.md")
                    .replace("{{BUG_HUNT_CHECKLIST}}", BUG_HUNT_CHECKLIST);

    private static final String REVIEW_PASS_INSTRUCTIONS =
            "Review in three explicit passes before writing the JSON.\n"
                    + PASS_A_AND_B
                    + ReviewHygienePrompts.HYGIENE_PASS
                    + "Then merge all three passes into a single \"lineComments\" list without"
                    + " duplicates. The same problem on different lines is not a duplicate: keep"
                    + " one comment per affected line.\n\n";

    static final String REVIEW_INSTRUCTIONS =
            REVIEW_PREAMBLE + REVIEW_PASS_INSTRUCTIONS + ReviewOutputContract.OUTPUT_CONTRACT;

    /**
     * Candidate-recall variant of {@link #REVIEW_PASS_INSTRUCTIONS}: the pipeline runs the hygiene
     * rules as their own pass in this mode, so the first pass spends its budget on A and B.
     */
    private static final String RECALL_REVIEW_PASS_INSTRUCTIONS =
            "Review in two explicit passes before writing the JSON.\n"
                    + PASS_A_AND_B
                    + "Then merge both passes into a single \"lineComments\" list without"
                    + " duplicates. The same problem on different lines is not a duplicate: keep"
                    + " one comment per affected line.\n\n";

    static final String RECALL_REVIEW_INSTRUCTIONS =
            REVIEW_PREAMBLE
                    + RECALL_REVIEW_PASS_INSTRUCTIONS
                    + ReviewOutputContract.OUTPUT_CONTRACT;

    /**
     * Candidate-recall mode, used only when a validation pass will re-check the output. It
     * deliberately relaxes the preamble's "omit what you cannot confirm" rule for low-confidence
     * notes, because the validator confirms or drops each one and the pipeline strips any that
     * survive unresolved.
     */
    static final String RECALL_DIRECTIVE =
            PromptResources.load("recall-directive.md")
                    .replace(
                            "{{RECALL_MAX_LINE_COMMENTS}}",
                            Integer.toString(ReviewResultParser.RECALL_MAX_LINE_COMMENTS));

    /**
     * Appended to Copilot prompts when the host attached its code-intelligence MCP server. It names
     * the server so the preamble's "read only inside the working directory" rule has an explicit
     * exception, and it warns that the checkout it reads can be at another commit.
     */
    static final String HOST_CODE_INTELLIGENCE =
            PromptResources.load("host-code-intelligence.md")
                    .replace("{{SERVER_NAME}}", HostCodeIntelligence.SERVER_NAME)
                    .stripTrailing();
}
