package com.jinloes.prpilot.model;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which changed files a bounded PR diff omits, carried in-band as a trailer at the end of the diff
 * text so it survives every existing transport (RPC, hosts, bridge) without a new field.
 *
 * <p>Wire grammar: a header line {@code [pr-pilot:diff-coverage] omitted=<n> listed=<n> budget=<n>
 * scan=<complete|incomplete>} followed by exactly {@code listed} lines of the form {@code
 * [pr-pilot:omitted] <path>}, each terminated by {@code \n}. {@link #split} accepts only a
 * well-formed, end-anchored trailer and otherwise leaves the input untouched with {@link #NONE}, so
 * a lookalike inside a diff can never hide diff text.
 *
 * <p>The grammar is mirrored by {@code webview/src/lib/diffCoverage.ts}; both implementations are
 * pinned to {@code core/src/test/resources/diff-coverage/trailer.golden.txt}. Change them together.
 *
 * @param omitted changed files left out of the diff body; a lower bound when the scan is incomplete
 * @param paths the omitted paths that fit the trailer, in original diff order; at most {@link
 *     #MAX_LISTED}
 * @param budgetBytes the byte budget the diff was bounded to
 * @param scanComplete false when the source diff was too large to scan to its end
 */
public record DiffCoverage(int omitted, List<String> paths, int budgetBytes, boolean scanComplete) {
    public static final int MAX_LISTED = 200;
    public static final String HEADER_PREFIX = "[pr-pilot:diff-coverage] ";
    public static final String PATH_PREFIX = "[pr-pilot:omitted] ";

    /** Complete coverage: nothing omitted, so no trailer. */
    public static final DiffCoverage NONE = new DiffCoverage(0, List.of(), 0, true);

    private static final int MAX_COUNT = 999_999_999;
    private static final String COUNT = "(0|[1-9][0-9]{0,8})";
    private static final Pattern HEADER =
            Pattern.compile(
                    Pattern.quote(HEADER_PREFIX)
                            + "omitted="
                            + COUNT
                            + " listed="
                            + COUNT
                            + " budget="
                            + COUNT
                            + " scan=(complete|incomplete)");

    public DiffCoverage {
        paths = List.copyOf(paths);
        if (omitted < 0 || omitted > MAX_COUNT) {
            throw new IllegalArgumentException("omitted out of range: " + omitted);
        }
        if (budgetBytes < 0 || budgetBytes > MAX_COUNT) {
            throw new IllegalArgumentException("budgetBytes out of range: " + budgetBytes);
        }
        if (paths.size() > omitted || paths.size() > MAX_LISTED) {
            throw new IllegalArgumentException("too many listed paths: " + paths.size());
        }
        for (String path : paths) {
            if (!listablePath(path)) {
                throw new IllegalArgumentException("path cannot be listed in a trailer");
            }
        }
    }

    /** The trailer-free diff body and the coverage its trailer declared. */
    public record Split(String body, DiffCoverage coverage) {}

    /**
     * Builds coverage whose trailer fits {@code maxTrailerBytes}: listable paths are taken in the
     * given order, at most {@link #MAX_LISTED}, stopping at the first one that does not fit.
     * Unlistable paths (empty or containing control characters) still count as omitted.
     */
    public static DiffCoverage fitted(
            int omitted,
            List<String> omittedPaths,
            int budgetBytes,
            boolean scanComplete,
            int maxTrailerBytes) {
        int available =
                maxTrailerBytes
                        - utf8Length(header(omitted, MAX_LISTED, budgetBytes, scanComplete));
        List<String> listed = new ArrayList<>();
        for (String path : omittedPaths) {
            if (listed.size() == MAX_LISTED || listed.size() == omitted) break;
            if (!listablePath(path)) continue;
            int line = utf8Length(PATH_PREFIX) + utf8Length(path) + 1;
            if (line > available) break;
            available -= line;
            listed.add(path);
        }
        return new DiffCoverage(omitted, listed, budgetBytes, scanComplete);
    }

    /** True when a path can appear on a trailer line: non-empty and free of control characters. */
    public static boolean listablePath(String path) {
        return path != null && !path.isEmpty() && path.chars().noneMatch(Character::isISOControl);
    }

    /**
     * Separates a trailing coverage block from a diff. Strict and end-anchored: only the last
     * header that starts a line is considered, and anything malformed about it or after it returns
     * {@code (input, NONE)} unchanged.
     */
    public static Split split(String input) {
        if (input == null) return new Split(null, NONE);
        int start = lastHeaderStart(input);
        if (start < 0 || !input.endsWith("\n")) return new Split(input, NONE);
        String[] lines = input.substring(start, input.length() - 1).split("\n", -1);
        Matcher header = HEADER.matcher(lines[0]);
        if (!header.matches()) return new Split(input, NONE);
        int omitted = Integer.parseInt(header.group(1));
        int listed = Integer.parseInt(header.group(2));
        int budget = Integer.parseInt(header.group(3));
        boolean scanComplete = "complete".equals(header.group(4));
        if (listed > omitted
                || listed > MAX_LISTED
                || (omitted == 0 && scanComplete)
                || lines.length - 1 != listed) {
            return new Split(input, NONE);
        }
        List<String> paths = new ArrayList<>(listed);
        for (int index = 1; index < lines.length; index++) {
            if (!lines[index].startsWith(PATH_PREFIX)) return new Split(input, NONE);
            String path = lines[index].substring(PATH_PREFIX.length());
            if (!listablePath(path)) return new Split(input, NONE);
            paths.add(path);
        }
        return new Split(
                input.substring(0, start), new DiffCoverage(omitted, paths, budget, scanComplete));
    }

    public int listed() {
        return paths.size();
    }

    /** Omitted files the trailer does not name. */
    public int unlisted() {
        return omitted - paths.size();
    }

    public boolean complete() {
        return omitted == 0 && scanComplete;
    }

    /** The exact trailer text, or an empty string when coverage is complete. */
    public String trailer() {
        if (complete()) return "";
        StringBuilder trailer =
                new StringBuilder(header(omitted, paths.size(), budgetBytes, scanComplete));
        for (String path : paths) trailer.append(PATH_PREFIX).append(path).append('\n');
        return trailer.toString();
    }

    /**
     * The listed paths as untrusted prompt data, one per line. Never blank, so a prompt section
     * built from it is never silently dropped when no path could be listed.
     */
    public String promptText() {
        if (paths.isEmpty()) return "(no omitted paths are listed)";
        StringBuilder text = new StringBuilder();
        for (String path : paths) {
            if (!text.isEmpty()) text.append('\n');
            text.append("- ").append(path);
        }
        return text.toString();
    }

    private static String header(int omitted, int listed, int budgetBytes, boolean scanComplete) {
        return HEADER_PREFIX
                + "omitted="
                + omitted
                + " listed="
                + listed
                + " budget="
                + budgetBytes
                + " scan="
                + (scanComplete ? "complete" : "incomplete")
                + "\n";
    }

    private static int lastHeaderStart(String input) {
        int index = input.lastIndexOf(HEADER_PREFIX);
        while (index > 0 && input.charAt(index - 1) != '\n') {
            index = input.lastIndexOf(HEADER_PREFIX, index - 1);
        }
        return index;
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
