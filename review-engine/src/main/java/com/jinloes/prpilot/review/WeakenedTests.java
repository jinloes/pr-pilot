package com.jinloes.prpilot.review;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/**
 * Mechanically extracts test hunks that newly disable/skip a test or remove assertions without
 * adding replacement assertions in the same hunk.
 *
 * <p>Only test files ({@link ChangedSymbols#isTestPath}) are inspected, and non-test PRs or normal
 * test additions/updates emit nothing so prompt size stays unchanged unless a test is weakened.
 */
final class WeakenedTests {
    static final int MAX_ENTRIES = 25;
    static final int MAX_DETAIL_CHARS = 180;

    record Entry(String path, int line, String kind, String detail) {}

    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");

    private static final Pattern SKIP_MARKER =
            Pattern.compile(
                    "(?:@(?:Disabled|Ignore)\\b"
                            + "|\\b(?:it|test|describe)\\.(?:skip|todo|fixme)\\s*\\("
                            + "|\\b(?:xit|xtest|xdescribe)\\s*\\("
                            + "|@(?:pytest\\.mark\\.skip(?:if)?|unittest\\.skip(?:If|Unless)?)\\b"
                            + "|\\bpytest\\.skip\\s*\\("
                            + "|#\\s*\\[\\s*ignore\\b"
                            + "|\\.(?:Skip|SkipNow|Skipf)\\s*\\("
                            + "|\\bAssumptions\\.assume(?:True|False)\\s*\\(\\s*false\\b)");

    private static final Pattern ASSERTION_LINE =
            Pattern.compile(
                    "(?:\\bassert[A-Z_]\\w*\\s*\\("
                            + "|\\bassert\\s+"
                            + "|\\bexpect\\s*\\("
                            + "|\\bverify\\s*\\("
                            + "|\\brequire\\.[A-Z]\\w*\\s*\\("
                            + "|\\bassert_[a-z0-9_]+!\\s*\\("
                            + "|\\bshould(?:Be|Equal|Throw|Contain|Have)[A-Za-z0-9_]*\\b)");

    private static final Pattern TEST_DECLARATION =
            Pattern.compile(
                    "(?:@(?:Test|ParameterizedTest|RepeatedTest|Fact|Theory)\\b"
                            + "|\\b(?:it|test)\\s*\\("
                            + "|\\bdef\\s+test_[A-Za-z0-9_]+"
                            + "|\\bfunc\\s+Test[A-Za-z0-9_]+"
                            + "|#\\s*\\[\\s*(?:tokio::)?test\\b)");

    private WeakenedTests() {}

    static List<Entry> extract(InspectionManifest manifest) {
        List<Entry> entries = new ArrayList<>();
        if (manifest == null) return entries;
        for (InspectionManifest.FileTarget file : manifest.files()) {
            if (file.path() == null
                    || file.diff() == null
                    || !ChangedSymbols.isTestPath(file.path())) {
                continue;
            }
            extractFile(file.path(), file.diff(), entries);
            if (entries.size() >= MAX_ENTRIES) {
                return List.copyOf(entries.subList(0, MAX_ENTRIES));
            }
        }
        return List.copyOf(entries);
    }

    static String formatInventory(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Entry entry : entries) {
            sb.append("- ")
                    .append(entry.path())
                    .append(':')
                    .append(entry.line())
                    .append(" [")
                    .append(entry.kind())
                    .append("] ")
                    .append(entry.detail())
                    .append('\n');
        }
        return sb.toString();
    }

    private static void extractFile(String path, String diff, List<Entry> out) {
        String[] lines = diff.split("\n", -1);
        int index = 0;
        while (index < lines.length && out.size() < MAX_ENTRIES) {
            Matcher header = HUNK_HEADER.matcher(lines[index]);
            if (!header.find()) {
                index++;
                continue;
            }
            int newLine = Integer.parseInt(header.group(1));
            index++;
            Set<String> removedSkipMarkers = new LinkedHashSet<>();
            List<AddedSkip> addedSkips = new ArrayList<>();
            List<String> removedAssertions = new ArrayList<>();
            boolean hasAddedAssertion = false;
            boolean removedTestDeclaration = false;
            int firstAddedLine = -1;
            int firstContextLine = -1;

            while (index < lines.length && !lines[index].startsWith("@@ ")) {
                String raw = lines[index++];
                if (raw.startsWith("---")
                        || raw.startsWith("+++")
                        || raw.startsWith("diff --git")
                        || raw.startsWith("\\")) {
                    continue;
                }
                char prefix = raw.isEmpty() ? ' ' : raw.charAt(0);
                String content = raw.isEmpty() ? "" : raw.substring(1);
                String code = codeWithoutComment(content);
                if (prefix == '-') {
                    Matcher skip = SKIP_MARKER.matcher(code);
                    if (skip.find()) {
                        removedSkipMarkers.add(skip.group());
                    }
                    if (ASSERTION_LINE.matcher(code).find()) {
                        removedAssertions.add(content.strip());
                    }
                    if (TEST_DECLARATION.matcher(code).find()) {
                        removedTestDeclaration = true;
                    }
                } else if (prefix == '+') {
                    if (firstAddedLine < 0) {
                        firstAddedLine = newLine;
                    }
                    Matcher skip = SKIP_MARKER.matcher(code);
                    if (skip.find()) {
                        addedSkips.add(new AddedSkip(newLine, skip.group(), content.strip()));
                    }
                    if (ASSERTION_LINE.matcher(code).find()) {
                        hasAddedAssertion = true;
                    }
                    newLine++;
                } else if (prefix == ' ') {
                    if (firstContextLine < 0) {
                        firstContextLine = newLine;
                    }
                    newLine++;
                }
            }

            for (AddedSkip skip : addedSkips) {
                if (removedSkipMarkers.contains(skip.marker())) continue;
                out.add(
                        new Entry(
                                path,
                                skip.line(),
                                "skipped-test",
                                StringUtils.abbreviate(skip.text(), MAX_DETAIL_CHARS)));
                if (out.size() >= MAX_ENTRIES) return;
            }

            // Flag removed assertions only when no assertion was added in the same hunk and the
            // hunk did not simply delete an entire test method with zero added lines.
            int anchor = firstAddedLine > 0 ? firstAddedLine : firstContextLine;
            if (!removedAssertions.isEmpty()
                    && !hasAddedAssertion
                    && !(removedTestDeclaration && firstAddedLine < 0)
                    && anchor > 0) {
                String summary =
                        removedAssertions.size() == 1
                                ? "Removed assertion: " + removedAssertions.get(0)
                                : "Removed "
                                        + removedAssertions.size()
                                        + " assertions (first: "
                                        + removedAssertions.get(0)
                                        + ")";
                out.add(
                        new Entry(
                                path,
                                anchor,
                                "removed-assertions",
                                StringUtils.abbreviate(summary, MAX_DETAIL_CHARS)));
                if (out.size() >= MAX_ENTRIES) return;
            }
        }
    }

    private static String codeWithoutComment(String line) {
        String trimmed = line.stripLeading();
        if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) {
            return "";
        }
        // Keep Rust `#[ignore]` / `#[test]` attributes intact while skipping Python `#` comments.
        if (trimmed.startsWith("#") && !trimmed.startsWith("#[")) {
            return "";
        }
        int slash = line.indexOf("//");
        return slash >= 0 ? line.substring(0, slash) : line;
    }

    private record AddedSkip(int line, String marker, String text) {}
}
