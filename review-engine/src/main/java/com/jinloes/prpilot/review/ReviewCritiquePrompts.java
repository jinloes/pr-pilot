package com.jinloes.prpilot.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.ReviewResult;
import java.util.List;
import org.apache.commons.lang3.StringUtils;

/**
 * Self-critique prompt that validates a first-pass draft review against the same PR evidence the
 * first pass saw, plus the draft serialization it embeds.
 */
final class ReviewCritiquePrompts {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ReviewCritiquePrompts() {}

    static final String CRITIQUE_PREAMBLE = PromptResources.load("critique-preamble.md");

    /**
     * The validation directive for the self-critique pass.
     *
     * <p>The low-confidence rule is deliberately keyed on {@code confidence}, not on {@code type}.
     * An earlier version told the validator to drop "a low-confidence issue", which could never
     * match: the critique input is {@link #draftReviewJson} over an already-parsed draft, and
     * {@link ReviewResultParser#repairLineComment} has by then dropped every low-confidence
     * "issue". The surviving low-confidence comments are "suggestion" and "note", so the rule has
     * to name them by confidence to reach anything at all.
     */
    static final String CRITIQUE_DIRECTIVE = PromptResources.load("critique-directive.md");

    /** Appended only when the draft marks a comment both reviewers reported. */
    static final String CORROBORATION_DIRECTIVE =
            "A comment marked \"corroborated\": true was reported independently by two"
                    + " reviewers; treat that as supporting evidence, but still drop it when the"
                    + " diff contradicts it.\n";

    /**
     * Appended only when dropped findings are being reported, so production reviews keep the output
     * contract unchanged and do not pay for the extra field.
     */
    static final String DROP_REASONS_DIRECTIVE =
            "Also add a top-level \"droppedFindings\" array to the JSON: one object per"
                    + " <draft_review> comment you drop or merge into another, with the draft"
                    + " comment's \"file\" and \"line\" and a one-sentence \"reason\" naming the"
                    + " evidence that failed (for example, the code that contradicts it or the"
                    + " comment it duplicates). Omit kept comments. This field does not change"
                    + " which comments you keep.\n";

    /**
     * {@link #buildCritiquePrompt(PRReviewRequest, ReviewResult, boolean)} without drop reasons.
     */
    public static String buildCritiquePrompt(PRReviewRequest request, ReviewResult draft) {
        return buildCritiquePrompt(request, draft, false);
    }

    /**
     * Builds the self-critique prompt: a lean validation preamble plus the shared {@link
     * #OUTPUT_CONTRACT}, the PR metadata, the same context sections the first pass saw, the
     * annotated diff needed to re-verify line numbers and symbols, the first-pass review as {@code
     * <draft_review>} JSON, and a directive to validate and correct it. Unlike {@link
     * ReviewPrompts#buildPrompt} it drops the full fresh-review narrative so the second pass does
     * not pay to resend the entire first-pass framing — but it keeps the context, because a
     * validator that cannot see the repo guideline or focus area behind a finding will read that
     * finding as unsupported and drop it. {@code reportDropped} also asks for a reason per dropped
     * comment ({@link #DROP_REASONS_DIRECTIVE}).
     */
    static String buildCritiquePrompt(
            PRReviewRequest request, ReviewResult draft, boolean reportDropped) {
        return buildCritiquePrompt(
                request,
                draft,
                reportDropped,
                WeakenedTests.extract(InspectionManifest.fromDiff(request.getDiff())));
    }

    static String buildCritiquePrompt(
            PRReviewRequest request,
            ReviewResult draft,
            boolean reportDropped,
            List<WeakenedTests.Entry> weakenedTests) {
        StringBuilder prompt =
                new StringBuilder(CRITIQUE_PREAMBLE).append(ReviewOutputContract.OUTPUT_CONTRACT);
        ReviewPrompts.appendPrMetadata(prompt, request.getPr());
        ReviewPrompts.appendContextSections(prompt, request, weakenedTests);
        ReviewPrompts.appendPrDiff(prompt, request);
        prompt.append("\n<draft_review>\n")
                .append(ReviewPrompts.escapeClosingTag(draftReviewJson(draft), "draft_review"))
                .append("\n</draft_review>\n\n")
                .append(CRITIQUE_DIRECTIVE);
        if (draft.getLineComments().stream().anyMatch(ReviewCritiquePrompts::corroborated)) {
            prompt.append(CORROBORATION_DIRECTIVE);
        }
        if (reportDropped) {
            prompt.append(DROP_REASONS_DIRECTIVE);
        }
        return prompt.toString();
    }

    /** Serializes a first-pass {@link ReviewResult} back into the review JSON schema. */
    static String draftReviewJson(ReviewResult draft) {
        var root = JSON.createObjectNode();
        root.put("summary", draft.getSummary());
        root.put("verdict", draft.getVerdict());
        var comments = root.putArray("lineComments");
        for (LineComment c : draft.getLineComments()) {
            var node = comments.addObject();
            node.put("file", c.getFile());
            node.put("line", c.getLine());
            node.put("type", c.getType());
            if (StringUtils.isNotBlank(c.getSeverity())) {
                node.put("severity", c.getSeverity());
            }
            if (StringUtils.isNotBlank(c.getCategory())) {
                node.put("category", c.getCategory());
            }
            if (StringUtils.isNotBlank(c.getConfidence())) {
                node.put("confidence", c.getConfidence());
            }
            node.put("body", c.getBody());
            if (StringUtils.isNotBlank(c.getRationale())) {
                node.put("rationale", c.getRationale());
            }
            if (StringUtils.isNotBlank(c.getSuggestedChange())) {
                node.put("suggestedChange", c.getSuggestedChange());
            }
            if (corroborated(c)) {
                node.put("corroborated", true);
            }
        }
        try {
            return JSON.writeValueAsString(root);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static boolean corroborated(LineComment comment) {
        return comment.getSources().size() >= 2;
    }
}
