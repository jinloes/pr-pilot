package com.jinloes.prpilot.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts newly added enum, string-union, or sealed-hierarchy variants from a diff so {@link
 * BaseCommitContext} can surface {@code switch} / {@code when} / {@code match} dispatch sites in
 * unchanged files.
 *
 * <p>Unlike {@link ChangedSymbols}, which skips enums declared on context lines and collapses
 * widely referenced types to a summary count, this extractor fires only when a new variant is added
 * and lets {@link BaseCommitContext} filter specifically to dispatch sites.
 */
final class ChangedEnumVariants {
    static final int MAX_ADDITIONS = 6;
    static final int MAX_VARIANTS_PER_TYPE = 8;

    record Addition(String typeName, String path, List<String> addedVariants) {}

    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ [^@]* @@ ?(.*)$");

    // Java, Kotlin (`enum class`), TypeScript, Rust, C#, Go/proto `enum Name`, Python `(Enum)`.
    private static final Pattern ENUM_DECL =
            Pattern.compile(
                    "\\b(?:enum(?:\\s+class)?\\s+([A-Za-z_]\\w*)"
                            + "|class\\s+([A-Za-z_]\\w*)\\s*\\([^)]*\\b(?:StrEnum|IntEnum|Enum)\\b[^)]*\\))");

    // TypeScript discriminated/string union: `export type OrderStatus = 'pending' | ...`
    private static final Pattern TS_UNION_DECL = Pattern.compile("\\btype\\s+([A-Za-z_]\\w*)\\s*=");

    private static final Pattern TS_UNION_PIPE_VARIANT =
            Pattern.compile("(?:^|\\|)\\s*(?:['\"]([A-Za-z_][\\w:-]*)['\"]|([A-Z][A-Za-z0-9_]*))");

    // Added subtype in a sealed hierarchy (`record Foo(...) implements Bar` / `data class Foo :
    // Bar`).
    private static final Pattern SEALED_PARENT_DECL =
            Pattern.compile(
                    "\\bsealed\\s+(?:interface|class|abstract\\s+class|trait)\\s+([A-Za-z_]\\w*)");

    private static final Pattern SUBTYPE_DECL =
            Pattern.compile(
                    "\\b(?:record|class|object)\\s+([A-Za-z_]\\w*)\\b[^{;]*?"
                            + "(?:\\bimplements\\b|\\bextends\\b|:)\\s*([A-Za-z_]\\w*)");

    // Single enum member line: `REFUNDED,`, `REFUNDED("r"),`, `Refunded = 3,`, `REFUNDED = "r"`.
    private static final Pattern ENUM_VARIANT_LINE =
            Pattern.compile(
                    "^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*"
                            + "(?:\\([^;{}]*\\)\\s*)?"
                            + "(?:=\\s*[^;{}]+?)?"
                            + "[,;]?\\s*(?://.*|#.*|/\\*.*\\*/\\s*)?$");

    private static final Set<String> NON_VARIANT_KEYWORDS =
            Set.of(
                    "public",
                    "private",
                    "protected",
                    "internal",
                    "static",
                    "final",
                    "abstract",
                    "override",
                    "val",
                    "var",
                    "let",
                    "const",
                    "def",
                    "fun",
                    "fn",
                    "func",
                    "function",
                    "return",
                    "case",
                    "default",
                    "break",
                    "continue",
                    "pass",
                    "reserved",
                    "option",
                    "syntax",
                    "package",
                    "import");

    private ChangedEnumVariants() {}

    static List<Addition> extract(InspectionManifest manifest) {
        if (manifest == null) return List.of();
        Map<String, MutableAddition> byType = new LinkedHashMap<>();
        for (InspectionManifest.FileTarget file : manifest.files()) {
            if (file.path() == null
                    || file.diff() == null
                    || ChangedSymbols.isTestPath(file.path())) {
                continue;
            }
            extractFromFile(file.path(), file.diff(), byType);
        }
        List<Addition> result = new ArrayList<>();
        for (MutableAddition candidate : byType.values()) {
            List<String> netAdded =
                    candidate.added.stream()
                            .filter(v -> !candidate.removed.contains(v))
                            .limit(MAX_VARIANTS_PER_TYPE)
                            .toList();
            if (!netAdded.isEmpty()) {
                result.add(new Addition(candidate.typeName, candidate.path, netAdded));
                if (result.size() == MAX_ADDITIONS) break;
            }
        }
        return List.copyOf(result);
    }

    private static void extractFromFile(
            String path, String diff, Map<String, MutableAddition> byType) {
        Set<String> sealedParents = findSealedParents(diff);
        String activeEnum = null;
        boolean pythonEnum = false;
        int braceDepth = 0;
        boolean constantsTerminated = false;
        String activeTsUnion = null;

        for (String raw : diff.split("\n", -1)) {
            Matcher header = HUNK_HEADER.matcher(raw);
            if (header.matches()) {
                activeEnum = null;
                activeTsUnion = null;
                pythonEnum = false;
                braceDepth = 0;
                constantsTerminated = false;
                String tail = header.group(1);
                String headerEnum = matchEnumName(tail);
                if (headerEnum != null && ChangedSymbols.MIN_NAME_LENGTH <= headerEnum.length()) {
                    activeEnum = headerEnum;
                    pythonEnum = tail.contains("(") && tail.contains("Enum");
                    braceDepth = pythonEnum ? 0 : 1;
                } else {
                    Matcher tsHeader = TS_UNION_DECL.matcher(tail);
                    if (tsHeader.find()
                            && tsHeader.group(1).length() >= ChangedSymbols.MIN_NAME_LENGTH) {
                        activeTsUnion = tsHeader.group(1);
                    }
                }
                continue;
            }
            if (raw.startsWith("---") || raw.startsWith("+++") || raw.startsWith("diff --git")) {
                continue;
            }
            char prefix = raw.isEmpty() ? ' ' : raw.charAt(0);
            if (prefix != '+' && prefix != '-' && prefix != ' ') {
                continue;
            }
            String line = raw.isEmpty() ? "" : raw.substring(1);
            String stripped = stripLineComment(line).strip();

            // Detect sealed-hierarchy subtype additions even outside an enum body.
            if (!sealedParents.isEmpty() && (prefix == '+' || prefix == '-')) {
                Matcher subtype = SUBTYPE_DECL.matcher(stripped);
                if (subtype.find() && sealedParents.contains(subtype.group(2))) {
                    recordVariant(byType, subtype.group(2), path, subtype.group(1), prefix == '+');
                }
            }

            // Detect entering a new enum declaration.
            String declaredEnum = matchEnumName(stripped);
            if (declaredEnum != null && declaredEnum.length() >= ChangedSymbols.MIN_NAME_LENGTH) {
                activeEnum = declaredEnum;
                pythonEnum = stripped.contains("(") && stripped.contains("Enum");
                braceDepth = countChar(stripped, '{') - countChar(stripped, '}');
                constantsTerminated = false;
                activeTsUnion = null;
                continue;
            }

            // Detect TypeScript union type declaration (`type Status = 'a' | 'b'`).
            Matcher tsDecl = TS_UNION_DECL.matcher(stripped);
            if (tsDecl.find()
                    && tsDecl.group(1).length() >= ChangedSymbols.MIN_NAME_LENGTH
                    && (stripped.contains("|") || stripped.endsWith("="))) {
                activeTsUnion = tsDecl.group(1);
                activeEnum = null;
                String rhs = stripped.substring(stripped.indexOf('=') + 1);
                if (prefix == '+' || prefix == '-') {
                    extractTsUnionVariants(byType, activeTsUnion, path, rhs, prefix == '+');
                }
                if (stripped.endsWith(";")) {
                    activeTsUnion = null;
                }
                continue;
            }

            if (activeTsUnion != null) {
                if (stripped.isEmpty()
                        || (!stripped.startsWith("|")
                                && !stripped.startsWith("'")
                                && !stripped.startsWith("\""))) {
                    activeTsUnion = null;
                } else {
                    if (prefix == '+' || prefix == '-') {
                        extractTsUnionVariants(
                                byType, activeTsUnion, path, stripped, prefix == '+');
                    }
                    if (stripped.endsWith(";")) {
                        activeTsUnion = null;
                    }
                    continue;
                }
            }

            if (activeEnum != null) {
                if (pythonEnum) {
                    if (!stripped.isEmpty()
                            && !line.startsWith(" ")
                            && !line.startsWith("\t")
                            && prefix != '-') {
                        activeEnum = null;
                        pythonEnum = false;
                        continue;
                    }
                    if (prefix == '+' || prefix == '-') {
                        String variant = extractEnumConstant(stripped, true);
                        if (variant != null) {
                            recordVariant(byType, activeEnum, path, variant, prefix == '+');
                        }
                    }
                    continue;
                }

                int openBefore = braceDepth;
                if (openBefore == 1 && !constantsTerminated && (prefix == '+' || prefix == '-')) {
                    String variant = extractEnumConstant(stripped, false);
                    if (variant != null) {
                        recordVariant(byType, activeEnum, path, variant, prefix == '+');
                    }
                }
                if (prefix != '-') {
                    if (openBefore == 1
                            && stripped.endsWith(";")
                            && !stripped.contains("=")
                            && !activeEnum.isEmpty()) {
                        // In Java/Kotlin enums, a trailing `;` ends the constant list; in proto
                        // enums (`FOO = 1;`) `=` is present so constants continue.
                        constantsTerminated = true;
                    }
                    braceDepth += countChar(stripped, '{') - countChar(stripped, '}');
                    if (braceDepth <= 0 && stripped.contains("}")) {
                        activeEnum = null;
                        constantsTerminated = false;
                    }
                }
            }
        }
    }

    private static Set<String> findSealedParents(String diff) {
        Set<String> parents = new LinkedHashSet<>();
        for (String raw : diff.split("\n", -1)) {
            String text = raw.isEmpty() ? "" : raw.substring(Math.min(1, raw.length()));
            Matcher matcher = SEALED_PARENT_DECL.matcher(text);
            while (matcher.find()) {
                if (matcher.group(1).length() >= ChangedSymbols.MIN_NAME_LENGTH) {
                    parents.add(matcher.group(1));
                }
            }
        }
        return parents;
    }

    private static String matchEnumName(String line) {
        if (line == null || line.isBlank()) return null;
        String trimmed = line.strip();
        if (trimmed.startsWith("//")
                || trimmed.startsWith("/*")
                || trimmed.startsWith("*")
                || trimmed.startsWith("#")) {
            return null;
        }
        Matcher matcher = ENUM_DECL.matcher(trimmed);
        if (!matcher.find()) return null;
        return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
    }

    private static void extractTsUnionVariants(
            Map<String, MutableAddition> byType,
            String typeName,
            String path,
            String fragment,
            boolean added) {
        Matcher matcher = TS_UNION_PIPE_VARIANT.matcher(fragment);
        while (matcher.find()) {
            String variant = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (variant != null && !variant.isBlank()) {
                recordVariant(byType, typeName, path, variant, added);
            }
        }
    }

    private static String extractEnumConstant(String stripped, boolean pythonEnum) {
        if (stripped.isEmpty()
                || stripped.startsWith("@")
                || stripped.startsWith("//")
                || stripped.startsWith("#")
                || stripped.startsWith("/*")
                || stripped.startsWith("*")
                || stripped.startsWith("}")) {
            return null;
        }
        if (pythonEnum && !stripped.contains("=")) {
            return null;
        }
        Matcher matcher = ENUM_VARIANT_LINE.matcher(stripped);
        if (!matcher.matches()) return null;
        String candidate = matcher.group(1);
        if (NON_VARIANT_KEYWORDS.contains(candidate)) {
            return null;
        }
        return candidate;
    }

    private static void recordVariant(
            Map<String, MutableAddition> byType,
            String typeName,
            String path,
            String variant,
            boolean added) {
        MutableAddition entry =
                byType.computeIfAbsent(typeName, k -> new MutableAddition(typeName, path));
        if (added) {
            entry.added.add(variant);
        } else {
            entry.removed.add(variant);
        }
    }

    private static String stripLineComment(String line) {
        int slash = line.indexOf("//");
        return slash >= 0 ? line.substring(0, slash) : line;
    }

    private static int countChar(String text, char target) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == target) count++;
        }
        return count;
    }

    private static final class MutableAddition {
        final String typeName;
        final String path;
        final Set<String> added = new LinkedHashSet<>();
        final Set<String> removed = new LinkedHashSet<>();

        MutableAddition(String typeName, String path) {
            this.typeName = typeName;
            this.path = path;
        }
    }
}
