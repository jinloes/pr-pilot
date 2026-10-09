package com.jinloes.prpilot.review;

import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.ReviewResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    /** Merges and deduplicates, keeping at most {@code cap} findings in priority order. */
    static ReviewResult merge(ReviewResult baseline, ReviewResult followUp, int cap) {
        Map<String, LineComment> unique = new LinkedHashMap<>();
        baseline.getLineComments().forEach(comment -> unique.put(key(comment), comment));
        followUp.getLineComments().forEach(comment -> unique.putIfAbsent(key(comment), comment));

        List<LineComment> comments = new ArrayList<>(unique.values());
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
