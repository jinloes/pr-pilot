package com.jinloes.prpilot.review;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/**
 * Lists the log statements a diff changes, so the hygiene pass judges a complete inventory instead
 * of finding the statements itself. Left to the model, that inventory is erratic: on a PR with
 * about a dozen changed log calls, repeated runs reported between zero and four, and a multi-line
 * call whose opening {@code logger.info(} line was unchanged context was never reported. Textual
 * and language-agnostic on purpose: a spurious entry costs one judged line, a missed one costs the
 * finding.
 */
final class ChangedLogStatements {
    static final int MAX_STATEMENTS = 150;
    static final int MAX_STATEMENT_LINES = 12;
    static final int MAX_TEXT_CHARS = 240;

    /** A changed log call, anchored on its first added line. */
    record Statement(String path, int line, String level, String text) {}

    private record NewLine(int number, String text, boolean added) {}

    // The receiver is log, logging, console, or any identifier ending in "logger" (any case), so
    // logger.info(, LOG.warn(, _logger.error(, logging.info( and console.error( match but
    // catalog.info( does not. Kotlin's logger.info { } lambda form is included.
    private static final Pattern CALL =
            Pattern.compile(
                    "(?i)(?<![\\w$])(?:[\\w$]*logger|log|logging|console)\\s*\\.\\s*"
                            + "(trace|debug|info|warn|warning|error|fatal|critical|exception"
                            + "|severe|log)\\s*[({]");

    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");

    private ChangedLogStatements() {}

    /**
     * Extracts up to {@link #MAX_STATEMENTS} changed log statements from non-test files in {@code
     * manifest}, in diff order. A statement counts as changed when any line of it is added.
     */
    static List<Statement> extract(InspectionManifest manifest) {
        List<Statement> statements = new ArrayList<>();
        if (manifest == null) return statements;
        for (InspectionManifest.FileTarget file : manifest.files()) {
            if (file.path() == null
                    || file.diff() == null
                    || ChangedSymbols.isTestPath(file.path())) {
                continue;
            }
            for (List<NewLine> hunk : hunks(file.diff())) {
                for (int index = 0; index < hunk.size(); index++) {
                    Matcher call = CALL.matcher(codeOf(hunk.get(index).text()));
                    while (call.find()) {
                        Statement statement = statementAt(file.path(), hunk, index, call);
                        if (statement != null) {
                            statements.add(statement);
                            if (statements.size() == MAX_STATEMENTS) return statements;
                        }
                    }
                }
            }
        }
        return statements;
    }

    /**
     * Reads the call starting at {@code call} through its closing bracket, at most {@link
     * #MAX_STATEMENT_LINES} lines, and returns it when any of those lines is added.
     */
    private static Statement statementAt(String path, List<NewLine> hunk, int start, Matcher call) {
        int depth = 0;
        Character quote = null;
        int end = start;
        StringBuilder text = new StringBuilder();
        int anchor = -1;
        boolean closed = false;
        for (int index = start;
                index < hunk.size() && index < start + MAX_STATEMENT_LINES;
                index++) {
            NewLine line = hunk.get(index);
            String code = line.text();
            int from = index == start ? call.end() - 1 : 0;
            for (int at = from; at < code.length() && !closed; at++) {
                char c = code.charAt(at);
                if (quote != null) {
                    if (c == '\\') at++;
                    else if (c == quote) quote = null;
                } else if (c == '"' || c == '\'' || c == '`') {
                    quote = c;
                } else if (c == '(' || c == '{') {
                    depth++;
                } else if (c == ')' || c == '}') {
                    closed = --depth == 0;
                }
            }
            if (line.added() && anchor < 0) anchor = line.number();
            if (!text.isEmpty()) text.append(' ');
            text.append(index == start ? code.substring(call.start()).strip() : code.strip());
            end = index;
            if (closed) break;
            // Only Python's triple quotes and JS template literals span lines; anything else is
            // an unterminated quote in a fragment, so stop treating the rest as a string.
            if (quote != null && quote != '`') quote = null;
        }
        if (anchor < 0) return null;
        String level = call.group(1).toLowerCase(Locale.ROOT);
        return new Statement(
                path, anchor, level, StringUtils.abbreviate(text.toString(), MAX_TEXT_CHARS));
    }

    /** The diff's hunks as new-side lines (context and added), with new-file line numbers. */
    private static List<List<NewLine>> hunks(String diff) {
        List<List<NewLine>> hunks = new ArrayList<>();
        List<NewLine> current = null;
        int number = 0;
        for (String line : diff.split("\\R", -1)) {
            Matcher header = HUNK_HEADER.matcher(line);
            if (header.find()) {
                current = new ArrayList<>();
                hunks.add(current);
                number = Integer.parseInt(header.group(1));
            } else if (current == null || line.startsWith("\\")) {
                continue;
            } else if (line.startsWith("+")) {
                current.add(new NewLine(number++, line.substring(1), true));
            } else if (line.startsWith(" ") || line.isEmpty()) {
                current.add(new NewLine(number++, line.isEmpty() ? "" : line.substring(1), false));
            } else if (!line.startsWith("-")) {
                current = null;
            }
        }
        return hunks;
    }

    /** Blank for a comment line, so commented-out calls are skipped. */
    private static String codeOf(String line) {
        String stripped = line.stripLeading();
        return stripped.startsWith("//")
                        || stripped.startsWith("#")
                        || stripped.startsWith("*")
                        || stripped.startsWith("/*")
                ? ""
                : line;
    }
}
