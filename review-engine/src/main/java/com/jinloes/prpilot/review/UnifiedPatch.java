package com.jinloes.prpilot.review;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the hunks of one file's unified diff and applies them strictly to a base text, so the head
 * version of a file can be rebuilt from the base commit without checking it out. Every context and
 * removed line must match the base exactly; any mismatch yields empty rather than a guessed result.
 */
final class UnifiedPatch {
    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(?:.*)$");

    private UnifiedPatch() {}

    record Hunk(int oldStart, int oldCount, int newStart, int newCount, List<String> lines) {}

    static Optional<List<Hunk>> parseHunks(String fileDiff) {
        String diff = fileDiff == null ? "" : fileDiff;
        if (isBinary(diff)) {
            return Optional.empty();
        }
        String[] lines = diff.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<Hunk> hunks = new ArrayList<>();
        int index = 0;
        while (index < lines.length) {
            Matcher matcher = HUNK_HEADER.matcher(stripTrailingCarriageReturn(lines[index]));
            if (!matcher.matches()) {
                index++;
                continue;
            }
            int oldStart = parseInt(matcher.group(1));
            int oldCount = matcher.group(2) == null ? 1 : parseInt(matcher.group(2));
            int newStart = parseInt(matcher.group(3));
            int newCount = matcher.group(4) == null ? 1 : parseInt(matcher.group(4));
            List<String> hunkLines = new ArrayList<>();
            int seenOld = 0;
            int seenNew = 0;
            index++;
            while (index < lines.length) {
                String line = stripTrailingCarriageReturn(lines[index]);
                if (line.startsWith("@@")) {
                    break;
                }
                if (line.startsWith("\\ No newline at end of file")) {
                    index++;
                    continue;
                }
                if (line.isEmpty()) {
                    if (index == lines.length - 1) {
                        break;
                    }
                    // A blank context line whose leading space was trimmed, as GNU patch reads it.
                    line = " ";
                }
                char prefix = line.charAt(0);
                if (prefix != ' ' && prefix != '-' && prefix != '+') {
                    return Optional.empty();
                }
                hunkLines.add(line);
                if (prefix == ' ' || prefix == '-') {
                    seenOld++;
                }
                if (prefix == ' ' || prefix == '+') {
                    seenNew++;
                }
                index++;
                if (seenOld == oldCount && seenNew == newCount) {
                    while (index < lines.length
                            && stripTrailingCarriageReturn(lines[index])
                                    .startsWith("\\ No newline at end of file")) {
                        index++;
                    }
                    break;
                }
            }
            if (seenOld != oldCount || seenNew != newCount) {
                return Optional.empty();
            }
            hunks.add(new Hunk(oldStart, oldCount, newStart, newCount, List.copyOf(hunkLines)));
        }
        return Optional.of(List.copyOf(hunks));
    }

    static Optional<String> apply(String base, String fileDiff) {
        if (isBinary(fileDiff == null ? "" : fileDiff)) {
            return Optional.empty();
        }
        Optional<List<Hunk>> parsed = parseHunks(fileDiff);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        List<String> baseLines = splitBase(base == null ? "" : base);
        if (parsed.get().isEmpty()) {
            return Optional.of(join(baseLines));
        }
        List<String> output = new ArrayList<>();
        int baseIndex = 0;
        int previousOldEnd = 0;
        for (Hunk hunk : parsed.get()) {
            int target = hunk.oldCount() == 0 ? hunk.oldStart() : hunk.oldStart() - 1;
            if (target < baseIndex || target < previousOldEnd || target > baseLines.size()) {
                return Optional.empty();
            }
            while (baseIndex < target) {
                output.add(baseLines.get(baseIndex++));
            }
            int hunkBaseIndex = target;
            for (String line : hunk.lines()) {
                char prefix = line.charAt(0);
                String text = stripTrailingCarriageReturn(line.substring(1));
                if (prefix == ' ') {
                    if (hunkBaseIndex >= baseLines.size()
                            || !baseLines.get(hunkBaseIndex).equals(text)) {
                        return Optional.empty();
                    }
                    output.add(text);
                    hunkBaseIndex++;
                    baseIndex++;
                } else if (prefix == '-') {
                    if (hunkBaseIndex >= baseLines.size()
                            || !baseLines.get(hunkBaseIndex).equals(text)) {
                        return Optional.empty();
                    }
                    hunkBaseIndex++;
                    baseIndex++;
                } else if (prefix == '+') {
                    output.add(text);
                }
            }
            previousOldEnd = target + hunk.oldCount();
        }
        while (baseIndex < baseLines.size()) {
            output.add(baseLines.get(baseIndex++));
        }
        return Optional.of(join(output));
    }

    private static boolean isBinary(String diff) {
        for (String line : diff.split("\\r?\\n")) {
            if (line.startsWith("@@")) {
                return false;
            }
            if (line.startsWith("GIT binary patch") || line.startsWith("Binary files ")) {
                return true;
            }
        }
        return false;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private static List<String> splitBase(String base) {
        String normalized = base.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.isEmpty()) {
            return List.of();
        }
        if (normalized.endsWith("\n")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            return List.of("");
        }
        return new ArrayList<>(List.of(normalized.split("\n", -1)));
    }

    private static String join(List<String> lines) {
        if (lines.isEmpty()) {
            return "";
        }
        return String.join("\n", lines) + "\n";
    }

    private static String stripTrailingCarriageReturn(String value) {
        return value.endsWith("\r") ? value.substring(0, value.length() - 1) : value;
    }
}
