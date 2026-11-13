package com.jinloes.prpilot.review;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Splits a unified diff into one section per changed file. */
final class DiffFiles {

    private static final Pattern FILE_START = Pattern.compile("(?m)^diff --git ");

    private DiffFiles() {}

    /**
     * One changed file's diff section.
     *
     * @param path the post-change path, or the pre-change path for a deleted file
     * @param added lines added inside hunks
     * @param removed lines removed inside hunks
     * @param diff the whole section, from its {@code diff --git} header
     */
    record DiffFile(String path, int added, int removed, String diff) {
        int changedLines() {
            return added + removed;
        }
    }

    /** Parses {@code diff} into file sections in diff order, skipping sections with no path. */
    static List<DiffFile> parse(String diff) {
        if (diff == null || diff.isEmpty()) return List.of();
        Matcher matcher = FILE_START.matcher(diff);
        List<Integer> starts = new ArrayList<>();
        while (matcher.find()) starts.add(matcher.start());
        List<DiffFile> files = new ArrayList<>();
        for (int index = 0; index < starts.size(); index++) {
            String section =
                    diff.substring(
                            starts.get(index),
                            index + 1 < starts.size() ? starts.get(index + 1) : diff.length());
            String path = "";
            int added = 0;
            int removed = 0;
            boolean inHunk = false;
            for (String line : section.split("\\R")) {
                if (line.startsWith("@@")) {
                    inHunk = true;
                    continue;
                }
                if (!inHunk && line.startsWith("+++ b/")) path = line.substring(6).trim();
                if (inHunk && line.startsWith("+")) added++;
                if (inHunk && line.startsWith("-")) removed++;
            }
            if (path.isBlank()) {
                String header = section.lines().findFirst().orElse("");
                int destination = header.indexOf(" b/");
                if (destination >= 0) path = header.substring(destination + 3).trim();
            }
            if (!path.isBlank()) files.add(new DiffFile(path, added, removed, section));
        }
        return files;
    }
}
