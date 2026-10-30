package com.jinloes.prpilot.sidecar.pr;

import com.jinloes.prpilot.model.DiffCoverage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Restricts a {@code baseline...head} compare diff to changes the pull request itself makes.
 *
 * <p>When the author merges the base branch after a review, the compare range also carries every
 * base-branch change the merge brought in. Those lines are not in the pull request's diff, so the
 * model reviews code the author did not write and every finding on it fails to anchor. Both diffs
 * end at the same head commit, so their new-side line numbers agree: a compare hunk is kept only
 * when its new-side range overlaps a hunk of the same file in the pull request diff.
 *
 * <p>The filter only removes what it can prove is absent. A file the pull request diff may have
 * omitted (its coverage trailer lists it, leaves paths unlisted, or did not scan to the end) is
 * kept whole.
 */
final class IncrementalDiffScope {
    private static final String SECTION_START = "diff --git ";
    private static final Pattern HUNK =
            Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@");

    private IncrementalDiffScope() {}

    /** The restricted diff and how many file sections it dropped. */
    record Restricted(String diff, boolean truncated, int droppedFiles) {}

    static Restricted restrict(String incrementalDiff, boolean truncated, String pullRequestDiff) {
        DiffCoverage.Split incremental = DiffCoverage.split(incrementalDiff);
        DiffCoverage.Split pullRequest = DiffCoverage.split(pullRequestDiff);
        Map<String, List<int[]>> pullRequestHunks = new HashMap<>();
        for (String section : sections(pullRequest.body())) {
            pullRequestHunks
                    .computeIfAbsent(pathOf(section), ignored -> new ArrayList<>())
                    .addAll(newRanges(section));
        }
        DiffCoverage pullRequestCoverage = pullRequest.coverage();
        Set<String> maybeOmitted = new HashSet<>(pullRequestCoverage.paths());
        boolean omissionsKnown =
                pullRequestCoverage.scanComplete() && pullRequestCoverage.unlisted() == 0;

        StringBuilder kept = new StringBuilder();
        int dropped = 0;
        List<String> sections = sections(incremental.body());
        if (!sections.isEmpty()) kept.append(preamble(incremental.body()));
        for (String section : sections) {
            String path = pathOf(section);
            List<int[]> ranges = pullRequestHunks.get(path);
            if (ranges == null) {
                if (omissionsKnown && !maybeOmitted.contains(path)) {
                    dropped++;
                } else {
                    kept.append(section);
                }
                continue;
            }
            String restricted = keepOverlappingHunks(section, ranges);
            if (restricted == null) {
                dropped++;
            } else {
                kept.append(restricted);
            }
        }

        DiffCoverage coverage = incremental.coverage();
        List<String> listed = new ArrayList<>();
        for (String path : coverage.paths()) {
            boolean baseOnly =
                    !pullRequestHunks.containsKey(path)
                            && omissionsKnown
                            && !maybeOmitted.contains(path);
            if (!baseOnly) listed.add(path);
        }
        DiffCoverage restrictedCoverage =
                new DiffCoverage(
                        coverage.omitted() - (coverage.listed() - listed.size()),
                        listed,
                        coverage.budgetBytes(),
                        coverage.scanComplete());
        String body = kept.toString();
        if (body.isBlank()) return new Restricted("", false, dropped);
        return new Restricted(
                body + restrictedCoverage.trailer(),
                truncated && !restrictedCoverage.complete(),
                dropped);
    }

    /**
     * The section with only the hunks whose new-side range overlaps {@code ranges}, or null when no
     * hunk does. A section without hunks (a rename, mode change, or binary file) is kept whole.
     */
    private static String keepOverlappingHunks(String section, List<int[]> ranges) {
        int firstHunk = hunkStart(section, 0);
        if (firstHunk < 0) return section;
        StringBuilder kept = new StringBuilder(section.substring(0, firstHunk));
        boolean anyKept = false;
        int start = firstHunk;
        while (start >= 0) {
            int next = hunkStart(section, start + 1);
            String hunk = next < 0 ? section.substring(start) : section.substring(start, next);
            int[] range = rangeOf(hunk);
            if (range == null || overlapsAny(range, ranges)) {
                kept.append(hunk);
                anyKept = true;
            }
            start = next;
        }
        return anyKept ? kept.toString() : null;
    }

    private static boolean overlapsAny(int[] range, List<int[]> ranges) {
        for (int[] other : ranges) {
            if (range[0] <= other[1] && other[0] <= range[1]) return true;
        }
        return false;
    }

    private static List<int[]> newRanges(String section) {
        List<int[]> ranges = new ArrayList<>();
        int start = hunkStart(section, 0);
        while (start >= 0) {
            int next = hunkStart(section, start + 1);
            int[] range = rangeOf(section.substring(start, next < 0 ? section.length() : next));
            if (range != null) ranges.add(range);
            start = next;
        }
        return ranges;
    }

    /**
     * The inclusive new-side line range a hunk header covers. A hunk with no new-side lines (a pure
     * deletion) covers the line it follows, so it still matches a hunk at the same place.
     */
    private static int[] rangeOf(String hunk) {
        Matcher matcher = HUNK.matcher(hunk);
        if (!matcher.find()) return null;
        int first = Integer.parseInt(matcher.group(1));
        int count = matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2));
        return new int[] {first, first + Math.max(count, 1) - 1};
    }

    private static int hunkStart(String section, int from) {
        if (from == 0 && section.startsWith("@@")) return 0;
        int index = section.indexOf("\n@@", Math.max(from - 1, 0));
        return index < 0 ? -1 : index + 1;
    }

    private static List<String> sections(String diff) {
        List<String> sections = new ArrayList<>();
        int start = sectionStart(diff, 0);
        while (start >= 0) {
            int next = sectionStart(diff, start + 1);
            sections.add(next < 0 ? diff.substring(start) : diff.substring(start, next));
            start = next;
        }
        return sections;
    }

    private static String preamble(String diff) {
        int start = sectionStart(diff, 0);
        return start <= 0 ? "" : diff.substring(0, start);
    }

    private static int sectionStart(String diff, int from) {
        if (from == 0 && diff.startsWith(SECTION_START)) return 0;
        int index = diff.indexOf("\n" + SECTION_START, Math.max(from - 1, 0));
        return index < 0 ? -1 : index + 1;
    }

    private static String pathOf(String section) {
        byte[] bytes = section.getBytes(StandardCharsets.UTF_8);
        return PrDiffService.pathOf(bytes, bytes.length);
    }
}
