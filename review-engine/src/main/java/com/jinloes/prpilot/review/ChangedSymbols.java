package com.jinloes.prpilot.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the names of existing declarations a diff changes, so their callers in the base commit can
 * be shown to the reviewer. Textual and language-agnostic on purpose: a spurious candidate costs a
 * few prompt bytes, while a missed caller costs a missed bug.
 */
final class ChangedSymbols {
    static final int MAX_SYMBOLS = 12;
    static final int MIN_NAME_LENGTH = 3;

    /**
     * A declaration the diff touches. {@code declarationChanged} is true when a removed line
     * declared it (signature changed or symbol deleted); false when only its body changed.
     */
    record Symbol(String name, String path, boolean declarationChanged) {}

    private static final String NAME = "([A-Za-z_$][\\w$]*)";
    private static final List<Pattern> DECLARATIONS =
            List.of(
                    // Python, Go (with receiver), Kotlin (with extension receiver), JS, Rust.
                    Pattern.compile(
                            "\\b(?:def|func|fun|function|fn)\\s+(?:\\([^)]*\\)\\s*)?"
                                    + "(?:[A-Za-z_][\\w]*\\.)?"
                                    + NAME),
                    Pattern.compile(
                            "\\b(?:class|interface|enum|record|struct|trait|protocol)\\s+" + NAME),
                    // Java, C#, TypeScript and Kotlin members led by at least one modifier.
                    Pattern.compile(
                            "^\\s*(?:@\\w+(?:\\([^)]*\\))?\\s+)*"
                                    + "(?:(?:public|protected|private|internal|static|final"
                                    + "|abstract|synchronized|native|default|override|virtual"
                                    + "|async|export|open|suspend)\\s+)+"
                                    + "(?:<[^>]*>\\s*)?"
                                    + "(?:[\\w<>\\[\\],.?]+\\s+)*?"
                                    + NAME
                                    + "\\s*\\("),
                    Pattern.compile(
                            "\\b(?:const|let|var)\\s+"
                                    + NAME
                                    + "\\s*=\\s*(?:async\\s+)?"
                                    + "(?:function\\b|\\([^)]*\\)\\s*=>|[A-Za-z_$][\\w$]*\\s*=>)"));

    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ [^@]* @@ ?(.*)$");

    private static final Set<String> IGNORED =
            Set.of(
                    "if",
                    "for",
                    "while",
                    "switch",
                    "catch",
                    "return",
                    "new",
                    "throw",
                    "else",
                    "main",
                    "init",
                    "__init__",
                    "constructor",
                    "toString",
                    "hashCode",
                    "equals",
                    "get",
                    "set",
                    "run",
                    "call",
                    "apply",
                    "test",
                    "setUp",
                    "tearDown",
                    "compareTo",
                    "close",
                    "clone",
                    "values",
                    "valueOf",
                    "of",
                    "self",
                    "this",
                    "super");

    private ChangedSymbols() {}

    /**
     * Extracts up to {@link #MAX_SYMBOLS} changed declarations from {@code manifest}, declarations
     * whose signature changed first, then enclosing declarations whose body changed. Test files are
     * skipped because nothing outside them calls their members.
     */
    static List<Symbol> extract(InspectionManifest manifest) {
        if (manifest == null) return List.of();
        Map<String, Symbol> declarations = new LinkedHashMap<>();
        Map<String, Symbol> bodies = new LinkedHashMap<>();
        for (InspectionManifest.FileTarget file : manifest.files()) {
            if (file.path() == null || isTestPath(file.path()) || file.diff() == null) continue;
            for (String line : file.diff().split("\n", -1)) {
                if (line.startsWith("-") && !line.startsWith("---")) {
                    String name = declaredName(line.substring(1));
                    if (name != null) {
                        declarations.putIfAbsent(name, new Symbol(name, file.path(), true));
                    }
                } else {
                    Matcher hunk = HUNK_HEADER.matcher(line);
                    if (hunk.matches()) {
                        String name = declaredName(hunk.group(1));
                        if (name != null) {
                            bodies.putIfAbsent(name, new Symbol(name, file.path(), false));
                        }
                    }
                }
            }
        }
        List<Symbol> symbols = new ArrayList<>(declarations.values());
        bodies.values().stream()
                .filter(symbol -> !declarations.containsKey(symbol.name()))
                .forEach(symbols::add);
        return symbols.size() > MAX_SYMBOLS ? symbols.subList(0, MAX_SYMBOLS) : symbols;
    }

    /** The name a source line declares, or null when it declares nothing worth searching for. */
    static String declaredName(String line) {
        if (line == null || line.isBlank()) return null;
        String trimmed = line.strip();
        if (trimmed.startsWith("//")
                || trimmed.startsWith("/*")
                || trimmed.startsWith("*")
                || trimmed.startsWith("#")) {
            return null;
        }
        for (Pattern pattern : DECLARATIONS) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                String name = matcher.group(1);
                return name.length() < MIN_NAME_LENGTH || IGNORED.contains(name) ? null : name;
            }
        }
        return null;
    }

    static boolean isTestPath(String path) {
        String lower = "/" + path.toLowerCase(Locale.ROOT);
        String file = path.substring(path.lastIndexOf('/') + 1);
        return lower.contains("/test/")
                || lower.contains("/tests/")
                || lower.contains("/__tests__/")
                || file.startsWith("test_")
                || file.matches("\\w*(?:Test|Tests|Spec)\\.\\w+")
                || file.matches(".*[._](?:test|spec)\\.\\w+");
    }
}
