package com.jinloes.prpilot.review;

import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.ReviewResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;

/**
 * Finding bookkeeping around {@link ReviewPipelineService}'s final validation: restores the hygiene
 * and review-rule findings validation dropped, reports dropped findings when asked, and keeps rule
 * findings from inheriting reviewer labels no candidate earned.
 */
final class ReviewPipelineFindings {
    private static final int DROPPED_BODY_MAX_CHARS = 120;
    // A validated finding this close to a hygiene finding, in the same category, already covers it.
    static final int HYGIENE_COVER_LINES = 2;

    private ReviewPipelineFindings() {}

    /**
     * Re-adds confirmed hygiene findings that validation dropped; review-rule findings are restored
     * the same way. The hygiene rules are mechanical (log level, attached exception, comment
     * wording), and the validator reliably discards them as low-value even when told to keep them,
     * which loses exactly the findings production reviewers such as Mae report. A hygiene finding
     * is kept unless it is low confidence, does not anchor to a changed line, or a validated
     * finding of the same category already sits within {@link #HYGIENE_COVER_LINES} lines of it in
     * the same file (the validator reworded or merged it), or a validated finding rewords it nearby
     * ({@link FindingSimilarity}).
     */
    static ReviewResult restoreHygieneFindings(
            ReviewResult validated, ReviewResult hygiene, InspectionManifest manifest) {
        List<LineComment> kept = new ArrayList<>(validated.getLineComments());
        List<LineComment> restored = new ArrayList<>();
        for (LineComment comment : hygiene.getLineComments()) {
            if ("low".equals(comment.getConfidence())
                    || manifest.hunkFor(comment.getFile(), comment.getLine()).isEmpty()
                    || kept.stream().anyMatch(other -> covers(other, comment))
                    || validated.getLineComments().stream()
                            .anyMatch(other -> FindingSimilarity.duplicates(other, comment))) {
                continue;
            }
            kept.add(comment);
            restored.add(comment);
        }
        if (restored.isEmpty()) {
            return validated;
        }
        return ReviewResultMerger.merge(
                validated,
                new ReviewResult("", "COMMENT", restored),
                ReviewPipelineService.MERGED_CANDIDATE_CAP);
    }

    private static boolean covers(LineComment kept, LineComment hygiene) {
        return Objects.equals(kept.getFile(), hygiene.getFile())
                && Math.abs(kept.getLine() - hygiene.getLine()) <= HYGIENE_COVER_LINES
                && Objects.equals(kept.getCategory(), hygiene.getCategory());
    }

    /**
     * One diagnostic status per draft finding whose file and line no longer appear after
     * validation. Opt-in via {@link ReviewPipelineService#REPORT_DROPPED_PROPERTY} so recall
     * benchmarks can tell a finding that was never produced from one validation discarded.
     */
    static List<String> droppedStatuses(ReviewResult draft, ReviewResult validated) {
        Set<String> kept = new HashSet<>();
        for (LineComment comment : validated.getLineComments()) {
            kept.add(comment.getFile() + ":" + comment.getLine());
        }
        List<String> statuses = new ArrayList<>();
        Set<String> reported = new HashSet<>();
        for (LineComment comment : draft.getLineComments()) {
            String location = comment.getFile() + ":" + comment.getLine();
            if (kept.contains(location) || !reported.add(location)) continue;
            statuses.add(
                    "Validation dropped finding at "
                            + location
                            + " — "
                            + StringUtils.abbreviate(
                                    StringUtils.normalizeSpace(comment.getBody()),
                                    DROPPED_BODY_MAX_CHARS));
        }
        return statuses;
    }

    /**
     * Where a rule finding sat before critique. Captured as values so later edits to the comment
     * objects cannot change which final comments count as rule findings.
     */
    record RuleSite(String file, String category, int line) {
        static List<RuleSite> of(List<LineComment> ruleFindings) {
            return ruleFindings.stream()
                    .filter(comment -> StringUtils.isNotBlank(comment.getCategory()))
                    .map(
                            comment ->
                                    new RuleSite(
                                            comment.getFile(),
                                            comment.getCategory(),
                                            comment.getLine()))
                    .toList();
        }

        boolean matches(LineComment comment) {
            return Objects.equals(file, comment.getFile())
                    && Objects.equals(category, comment.getCategory())
                    && Math.abs(line - comment.getLine()) <= HYGIENE_COVER_LINES;
        }
    }

    /**
     * Clears the primary-label fallback {@code reattach} gives a rule finding that no reviewer
     * candidate matched, so a rule finding carries reviewer labels only through a match. A final
     * comment is a rule finding when it sits where one did before critique (the critique may reword
     * it) or still carries the engine's rule tag.
     */
    static void unattributeUnmatchedRuleFindings(
            ReviewResult result,
            List<ReviewerAttribution.Evidence> evidence,
            List<RuleSite> ruleSites,
            List<LocalReviewRules.Rule> rules) {
        for (LineComment comment : result.getLineComments()) {
            boolean ruleFinding =
                    ruleSites.stream().anyMatch(site -> site.matches(comment))
                            || hasRuleTag(comment, rules);
            if (ruleFinding
                    && evidence.stream().noneMatch(candidate -> candidate.matches(comment))) {
                comment.setSources(List.of());
            }
        }
    }

    /** True when the rationale carries the engine's {@code (rule: name)} tag for a loaded rule. */
    static boolean hasRuleTag(LineComment comment, List<LocalReviewRules.Rule> rules) {
        String rationale = StringUtils.strip(comment.getRationale());
        if (StringUtils.isEmpty(rationale)) return false;
        for (LocalReviewRules.Rule rule : rules) {
            if (rationale.endsWith(" (rule: " + rule.name() + ")")
                    || rationale.equals(ReviewRulesPass.withRuleSuffix(null, rule.name()))) {
                return true;
            }
        }
        return false;
    }
}
