package com.jinloes.prpilot.review;

import com.jinloes.prpilot.model.LineComment;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/**
 * Detects two comments that describe the same finding in different words. Separate reviewers,
 * passes, and critique rewrites anchor the same defect a few lines apart and phrase it differently,
 * so neither an exact-body match nor the tight same-line window catches them. Code identifiers they
 * both name (method, class, and constant names) are the strongest signal; prose overlap is the
 * fallback for comments that name none.
 *
 * <p>Apply this only across passes. Within one pass a reviewer deliberately repeats a finding at
 * each call site it applies to, and those comments share identifiers and wording too.
 */
final class FindingSimilarity {
    /** How far apart two reworded comments may sit and still be one finding. */
    static final int NEAR_LINES = 10;

    static final int MIN_SHARED_IDENTIFIERS = 3;
    static final double IDENTIFIER_OVERLAP = 0.6;
    static final double WORD_OVERLAP = 0.6;

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern WORD = Pattern.compile("[a-z0-9]{4,}");

    private FindingSimilarity() {}

    /**
     * True when both comments sit in the same file, type, and category within {@link #NEAR_LINES}
     * lines and their bodies match: identical after normalization, or sharing most code identifiers
     * or most distinctive words.
     */
    static boolean duplicates(LineComment a, LineComment b) {
        if (!Objects.equals(a.getFile(), b.getFile())
                || !Objects.equals(a.getType(), b.getType())
                || !Objects.equals(a.getCategory(), b.getCategory())
                || Math.abs(a.getLine() - b.getLine()) > NEAR_LINES) {
            return false;
        }
        String bodyA = normalize(a.getBody());
        String bodyB = normalize(b.getBody());
        if (bodyA.equals(bodyB)) {
            return !bodyA.isEmpty() || a.getLine() == b.getLine();
        }
        Set<String> identifiersA = identifiers(a.getBody());
        Set<String> identifiersB = identifiers(b.getBody());
        int sharedIdentifiers = intersectionSize(identifiersA, identifiersB);
        if (sharedIdentifiers >= MIN_SHARED_IDENTIFIERS
                && overlap(sharedIdentifiers, identifiersA, identifiersB) >= IDENTIFIER_OVERLAP) {
            return true;
        }
        Set<String> wordsA = words(bodyA);
        Set<String> wordsB = words(bodyB);
        return jaccard(wordsA, wordsB) >= WORD_OVERLAP;
    }

    /**
     * Code-like tokens: camelCase or PascalCase with an inner capital, or containing an underscore.
     * Capitalized prose words ("This", "Add") and plain lowercase words are excluded.
     */
    static Set<String> identifiers(String body) {
        Set<String> identifiers = new HashSet<>();
        Matcher matcher = IDENTIFIER.matcher(StringUtils.defaultString(body));
        while (matcher.find()) {
            String token = matcher.group();
            boolean innerCapital =
                    token.length() > 1
                            && StringUtils.containsAny(
                                    token.substring(1), "ABCDEFGHIJKLMNOPQRSTUVWXYZ");
            boolean underscore = token.indexOf('_') > 0 && token.length() > 2;
            if ((innerCapital && !StringUtils.isAllUpperCase(token)) || underscore) {
                identifiers.add(token);
            }
        }
        return identifiers;
    }

    static Set<String> words(String normalizedBody) {
        Set<String> words = new HashSet<>();
        Matcher matcher = WORD.matcher(normalizedBody);
        while (matcher.find()) {
            words.add(matcher.group());
        }
        return words;
    }

    private static String normalize(String body) {
        return StringUtils.normalizeSpace(StringUtils.defaultString(body)).toLowerCase(Locale.ROOT);
    }

    private static int intersectionSize(Set<String> a, Set<String> b) {
        Set<String> shared = new HashSet<>(a);
        shared.retainAll(b);
        return shared.size();
    }

    /** Overlap coefficient: a shorter rewording that names a subset still counts as a match. */
    private static double overlap(int shared, Set<String> a, Set<String> b) {
        int smaller = Math.min(a.size(), b.size());
        return smaller == 0 ? 0 : (double) shared / smaller;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        int shared = intersectionSize(a, b);
        int union = a.size() + b.size() - shared;
        return union == 0 ? 0 : (double) shared / union;
    }
}
