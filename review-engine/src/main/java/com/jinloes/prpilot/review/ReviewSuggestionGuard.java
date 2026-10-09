package com.jinloes.prpilot.review;

import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.ReviewResult;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Clears a finding's {@code suggestedChange} unless it is a safe, literal replacement for the
 * anchored new-side line. The comment itself is always kept; only the suggestion is removed, since
 * a wrong suggestion is one click away from being committed by the PR author.
 */
final class ReviewSuggestionGuard {
    private static final Logger log = LoggerFactory.getLogger(ReviewSuggestionGuard.class);

    static final int MAX_LINES = 6;
    static final int MAX_CHARS = 1_000;
    private static final Set<String> SUGGESTIBLE_TYPES = Set.of("issue", "suggestion");
    private static final Pattern LINE_NUMBER_PREFIX = Pattern.compile("(?m)^\\d*\\| ");
    private static final Pattern LEADING_WHITESPACE = Pattern.compile("^[ \\t]*");

    private ReviewSuggestionGuard() {}

    static ReviewResult apply(ReviewResult review, InspectionManifest manifest) {
        int cleared = 0;
        for (LineComment comment : review.getLineComments()) {
            if (comment.getSuggestedChange().isEmpty()) {
                continue;
            }
            String normalized = normalize(comment.getSuggestedChange());
            if (accepts(comment, normalized, manifest)) {
                comment.setSuggestedChange(normalized);
            } else {
                comment.setSuggestedChange("");
                cleared++;
            }
        }
        if (cleared > 0) {
            log.info("Cleared {} unsafe suggested change(s)", cleared);
        }
        return review;
    }

    static String normalize(String value) {
        return StringUtils.stripEnd(value.replace("\r\n", "\n"), "\n");
    }

    private static boolean accepts(
            LineComment comment, String suggestion, InspectionManifest manifest) {
        if (StringUtils.isBlank(suggestion)
                || suggestion.length() > MAX_CHARS
                || suggestion.split("\n", -1).length > MAX_LINES
                || suggestion.contains("```")
                || LINE_NUMBER_PREFIX.matcher(suggestion).find()
                || !SUGGESTIBLE_TYPES.contains(comment.getType())
                || !"high".equals(comment.getConfidence())) {
            return false;
        }
        Optional<String> anchored = manifest.newSideLineText(comment.getFile(), comment.getLine());
        return anchored.isPresent()
                && !anchored.get().equals(suggestion)
                && leadingWhitespace(anchored.get()).equals(leadingWhitespace(suggestion));
    }

    private static String leadingWhitespace(String text) {
        var matcher = LEADING_WHITESPACE.matcher(text);
        return matcher.find() ? matcher.group() : "";
    }
}
