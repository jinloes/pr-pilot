package com.jinloes.prpilot.review;

import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.ReviewResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Merges candidate findings from a bounded follow-up or second reviewer without replacing the
 * primary summary, and applies the final published-review limits.
 */
final class ReviewResultMerger {
    static final int MAX_COMMENTS = 20;
    private static final Comparator<LineComment> BY_PRIORITY =
            Comparator.comparingInt(ReviewResultMerger::priority)
                    .reversed()
                    .thenComparing(LineComment::getFile)
                    .thenComparingInt(LineComment::getLine);

    private ReviewResultMerger() {}

    static ReviewResult merge(ReviewResult baseline, ReviewResult followUp) {
        return merge(baseline, followUp, MAX_COMMENTS);
    }

    /**
     * Merges and deduplicates, keeping at most {@code cap} findings in priority order. A follow-up
     * comment that rewords a baseline finding nearby ({@link FindingSimilarity}) is folded into it:
     * the higher-priority comment of the pair is kept (ties keep the baseline) and carries the
     * sources of both.
     */
    static ReviewResult merge(ReviewResult baseline, ReviewResult followUp, int cap) {
        Map<String, LineComment> unique = new LinkedHashMap<>();
        baseline.getLineComments().forEach(comment -> unique.put(key(comment), comment));
        List<LineComment> comments = new ArrayList<>(unique.values());
        int baselineCount = comments.size();
        for (LineComment comment : followUp.getLineComments()) {
            if (unique.containsKey(key(comment))) continue;
            int match = similarBaseline(comments, baselineCount, comment);
            if (match < 0) {
                unique.put(key(comment), comment);
                comments.add(comment);
                continue;
            }
            LineComment kept = comments.get(match);
            LineComment winner = priority(comment) > priority(kept) ? comment : kept;
            LineComment loser = winner == comment ? kept : comment;
            winner.setSources(unionSources(winner, loser));
            comments.set(match, winner);
        }

        comments.sort(BY_PRIORITY);
        if (comments.size() > cap) {
            comments = new ArrayList<>(comments.subList(0, cap));
        }
        return new ReviewResult(baseline.getSummary(), verdict(comments), comments);
    }

    /**
     * Drops low-confidence recall candidates that no validator confirmed and recomputes the verdict
     * from the surviving comments.
     */
    static ReviewResult withoutLowConfidence(ReviewResult result) {
        List<LineComment> kept =
                result.getLineComments().stream()
                        .filter(comment -> !"low".equals(comment.getConfidence()))
                        .toList();
        return new ReviewResult(result.getSummary(), verdict(kept), new ArrayList<>(kept));
    }

    /** Caps the published review at the standard limit, keeping the highest-priority findings. */
    static ReviewResult capFinal(ReviewResult result) {
        if (result.getLineComments().size() <= MAX_COMMENTS) {
            return result;
        }
        List<LineComment> comments = new ArrayList<>(result.getLineComments());
        comments.sort(BY_PRIORITY);
        comments = new ArrayList<>(comments.subList(0, MAX_COMMENTS));
        return new ReviewResult(result.getSummary(), verdict(comments), comments);
    }

    /** Nearest baseline comment the follow-up comment duplicates, or -1. */
    private static int similarBaseline(
            List<LineComment> comments, int baselineCount, LineComment comment) {
        int best = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int index = 0; index < baselineCount; index++) {
            LineComment candidate = comments.get(index);
            if (!FindingSimilarity.duplicates(candidate, comment)) continue;
            int distance = Math.abs(candidate.getLine() - comment.getLine());
            if (distance < bestDistance) {
                best = index;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static List<String> unionSources(LineComment winner, LineComment loser) {
        Set<String> sources = new LinkedHashSet<>(winner.getSources());
        sources.addAll(loser.getSources());
        return new ArrayList<>(sources);
    }

    private static String key(LineComment comment) {
        return comment.getFile()
                + "|"
                + comment.getLine()
                + "|"
                + comment.getType()
                + "|"
                + comment.getBody().trim().toLowerCase(Locale.ROOT);
    }

    static int priority(LineComment comment) {
        int severity =
                switch (comment.getSeverity()) {
                    case "blocker" -> 40;
                    case "major" -> 30;
                    case "minor" -> 20;
                    case "nit" -> 10;
                    default -> 0;
                };
        int confidence =
                switch (comment.getConfidence()) {
                    case "high" -> 3;
                    case "medium" -> 2;
                    case "low" -> 1;
                    default -> 0;
                };
        return severity + confidence;
    }

    private static String verdict(List<LineComment> comments) {
        boolean blocking =
                comments.stream()
                        .anyMatch(
                                comment ->
                                        "issue".equals(comment.getType())
                                                && ("blocker".equals(comment.getSeverity())
                                                        || "major".equals(comment.getSeverity())));
        if (blocking) {
            return "REQUEST_CHANGES";
        }
        return comments.isEmpty() ? "APPROVE" : "COMMENT";
    }
}
