package com.jinloes.prpilot.review;

import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.ReviewResult;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;

/**
 * Attributes findings to the reviewer model(s) that reported them when a second reviewer runs.
 * Attribution is engine-owned: model output never sets {@link LineComment#getSources()}, and
 * critique never has to echo it, because surviving comments are matched back to the pre-critique
 * candidates by location and category.
 */
final class ReviewerAttribution {
    /** Two findings this close in the same file and category describe the same defect. */
    static final int SAME_FINDING_LINES = ReviewPipelineService.HYGIENE_COVER_LINES;

    private ReviewerAttribution() {}

    /** The two reviewer labels; the second is disambiguated when both models share a name. */
    record Labels(String primary, String second) {
        static Labels of(String primary, String second) {
            String first = StringUtils.defaultIfBlank(StringUtils.trim(primary), "Primary");
            String other = StringUtils.defaultIfBlank(StringUtils.trim(second), "Second");
            return new Labels(first, first.equals(other) ? other + " (second)" : other);
        }
    }

    /** The primary result with cross-reviewer duplicates collapsed, and the unmatched remainder. */
    record Collapsed(ReviewResult primary, ReviewResult second) {}

    /**
     * Tags each pass with its reviewer, then folds each second-reviewer comment into at most one
     * unconsumed primary comment describing the same finding. The higher-priority comment of a pair
     * is kept (ties keep the primary) and carries both labels.
     */
    static Collapsed collapse(ReviewResult primary, ReviewResult second, Labels labels) {
        primary.getLineComments().forEach(comment -> comment.setSources(List.of(labels.primary())));
        second.getLineComments().forEach(comment -> comment.setSources(List.of(labels.second())));

        List<LineComment> kept = new ArrayList<>(primary.getLineComments());
        boolean[] consumed = new boolean[kept.size()];
        List<LineComment> unmatched = new ArrayList<>();
        for (LineComment comment : second.getLineComments()) {
            int match = nearestUnconsumed(kept, consumed, comment);
            if (match < 0) {
                unmatched.add(comment);
                continue;
            }
            consumed[match] = true;
            LineComment primaryComment = kept.get(match);
            LineComment winner =
                    ReviewResultMerger.priority(comment)
                                    > ReviewResultMerger.priority(primaryComment)
                            ? comment
                            : primaryComment;
            winner.setSources(List.of(labels.primary(), labels.second()));
            kept.set(match, winner);
        }
        return new Collapsed(
                new ReviewResult(primary.getSummary(), primary.getVerdict(), kept),
                new ReviewResult(second.getSummary(), second.getVerdict(), unmatched));
    }

    private static int nearestUnconsumed(
            List<LineComment> candidates, boolean[] consumed, LineComment comment) {
        int best = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int index = 0; index < candidates.size(); index++) {
            if (consumed[index] || !sameFinding(candidates.get(index), comment)) continue;
            int distance = Math.abs(candidates.get(index).getLine() - comment.getLine());
            if (distance < bestDistance) {
                best = index;
                bestDistance = distance;
            }
        }
        return best;
    }

    /**
     * Immutable attribution evidence for one pre-critique candidate. Captured before critique so
     * that sources assigned during re-attachment cannot feed back into later matches.
     */
    record Evidence(String file, String category, int line, List<String> sources) {
        static List<Evidence> snapshot(List<LineComment> candidates) {
            return candidates.stream()
                    .filter(candidate -> !candidate.getSources().isEmpty())
                    .map(
                            candidate ->
                                    new Evidence(
                                            candidate.getFile(),
                                            candidate.getCategory(),
                                            candidate.getLine(),
                                            List.copyOf(candidate.getSources())))
                    .toList();
        }

        boolean matches(LineComment comment) {
            return StringUtils.isNotBlank(category)
                    && Objects.equals(file, comment.getFile())
                    && Objects.equals(category, comment.getCategory())
                    && Math.abs(line - comment.getLine()) <= SAME_FINDING_LINES;
        }
    }

    /**
     * Re-attaches sources after critique: each comment gets the union, primary label first, of the
     * sources of the pre-critique candidates it matches by the collapse rule. A comment matching
     * none (critique-added, hygiene, supervisor follow-up) is credited to the primary reviewer.
     */
    static ReviewResult reattach(
            ReviewResult result, List<Evidence> candidates, String primaryLabel) {
        for (LineComment comment : result.getLineComments()) {
            Set<String> union = new LinkedHashSet<>();
            for (Evidence candidate : candidates) {
                if (candidate.matches(comment)) union.addAll(candidate.sources());
            }
            List<String> sources = new ArrayList<>();
            if (union.isEmpty() || union.remove(primaryLabel)) sources.add(primaryLabel);
            sources.addAll(union);
            comment.setSources(sources);
        }
        return result;
    }

    static boolean sameFinding(LineComment a, LineComment b) {
        return StringUtils.isNotBlank(a.getCategory())
                && Objects.equals(a.getFile(), b.getFile())
                && Objects.equals(a.getCategory(), b.getCategory())
                && Math.abs(a.getLine() - b.getLine()) <= SAME_FINDING_LINES;
    }
}
