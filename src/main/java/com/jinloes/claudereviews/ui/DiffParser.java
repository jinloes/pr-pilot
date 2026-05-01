package com.jinloes.claudereviews.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses unified diff text into structured {@link DiffFile} / {@link DiffLine} objects. */
final class DiffParser {

    private DiffParser() {}

    static class DiffFile {
        String name;
        final List<DiffLine> lines = new ArrayList<>();

        DiffFile(String name) {
            this.name = name;
        }
    }

    record DiffLine(int newLineNum, char type, String content, boolean hunkStart) {}

    private static final Pattern HUNK_NEW_START = Pattern.compile("@@ -\\d+(?:,\\d+)? \\+(\\d+)");

    static List<DiffFile> parseDiff(String rawDiff) {
        List<DiffFile> files = new ArrayList<>();
        DiffFile current = null;
        int newLineNum = 0;
        boolean nextIsHunkStart = false;

        for (String line : rawDiff.split("\\r?\\n", -1)) {
            if (line.startsWith("diff --git ")) {
                int bIdx = line.lastIndexOf(" b/");
                current = new DiffFile(bIdx >= 0 ? line.substring(bIdx + 3) : line);
                files.add(current);
                newLineNum = 0;
                nextIsHunkStart = false;
            } else if (line.startsWith("+++ b/") && current != null) {
                current.name = line.substring(6);
            } else if (line.startsWith("---")
                    || line.startsWith("+++")
                    || line.startsWith("index ")
                    || line.startsWith("new file")
                    || line.startsWith("deleted file")
                    || line.startsWith("old mode")
                    || line.startsWith("new mode")
                    || line.startsWith("Binary files")
                    || line.startsWith("similarity")
                    || line.startsWith("rename")) {
                // skip metadata
            } else if (line.startsWith("@@ ")) {
                Matcher m = HUNK_NEW_START.matcher(line);
                if (m.find()) newLineNum = Integer.parseInt(m.group(1)) - 1;
                nextIsHunkStart = true;
            } else if (current != null && !line.isEmpty() && line.charAt(0) != '\\') {
                char first = line.charAt(0);
                String content = line.length() > 1 ? line.substring(1) : "";
                boolean isHunkStart = nextIsHunkStart;
                nextIsHunkStart = false;
                if (first == '+') {
                    current.lines.add(new DiffLine(++newLineNum, '+', content, isHunkStart));
                } else if (first == '-') {
                    current.lines.add(new DiffLine(-1, '-', content, isHunkStart));
                } else {
                    current.lines.add(new DiffLine(++newLineNum, ' ', content, isHunkStart));
                }
            }
        }
        return files;
    }

    /**
     * Returns the column count matching the longest content line across all diff files, clamped to
     * [40, 120].
     */
    static int computeMaxColumns(List<DiffFile> files) {
        int max = 40;
        for (DiffFile f : files) {
            for (DiffLine l : f.lines) {
                max = Math.max(max, l.content().length());
            }
        }
        return Math.min(max, 120);
    }
}
