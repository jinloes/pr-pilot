package com.jinloes.prpilot.review;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/** Prefixes unified-diff lines with new-file line numbers so findings can cite exact lines. */
final class DiffLineAnnotator {

    private DiffLineAnnotator() {}

    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");

    /**
     * Prefixes each line of a unified diff with its new-file line number so the model can cite line
     * numbers directly instead of counting from {@code @@} headers (a frequent source of
     * misattributed comments). Added ('+') and context (' ') lines get their new-file number;
     * deleted ('-') lines and headers get a numberless {@code "| "} divider. Lines outside any hunk
     * (e.g. {@code diff --git}, {@code index}, {@code +++/---}) pass through unchanged.
     */
    static String annotateDiffWithLineNumbers(String diff) {
        if (StringUtils.isBlank(diff)) {
            return diff;
        }
        String[] lines = diff.split("\n", -1);
        StringBuilder out = new StringBuilder(diff.length() + lines.length * 6);
        int newLine = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher hunk = HUNK_HEADER.matcher(line);
            if (line.startsWith("diff --git ")) {
                newLine = -1;
                out.append(line);
            } else if (hunk.find()) {
                newLine = Integer.parseInt(hunk.group(1));
                out.append(line);
            } else if (newLine < 0) {
                // Pre-hunk header lines (diff --git, index, ---, +++): leave untouched.
                out.append(line);
            } else if (line.startsWith("+")) {
                out.append(newLine).append("| ").append(line);
                newLine++;
            } else if (line.startsWith("-")) {
                out.append("| ").append(line);
            } else if (line.startsWith(" ")) {
                out.append(newLine).append("| ").append(line);
                newLine++;
            } else {
                // "\ No newline at end of file", the trailing split element, or file headers.
                out.append(line);
            }
            if (i < lines.length - 1) {
                out.append("\n");
            }
        }
        return out.toString();
    }
}
