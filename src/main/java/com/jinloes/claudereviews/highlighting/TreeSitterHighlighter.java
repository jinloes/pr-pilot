package com.jinloes.claudereviews.highlighting;

import java.awt.Color;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.treesitter.TSLanguage;
import org.treesitter.TSParser;
import org.treesitter.TSQuery;
import org.treesitter.TSQueryCapture;
import org.treesitter.TSQueryCursor;
import org.treesitter.TSQueryMatch;
import org.treesitter.TSQueryPredicateStep;
import org.treesitter.TSQueryPredicateStepType;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterBash;
import org.treesitter.TreeSitterGo;
import org.treesitter.TreeSitterJava;
import org.treesitter.TreeSitterJavascript;
import org.treesitter.TreeSitterKotlin;
import org.treesitter.TreeSitterProto;
import org.treesitter.TreeSitterPython;
import org.treesitter.TreeSitterRust;

/**
 * Syntax highlighting via tree-sitter for languages where a grammar is available. Produces more
 * accurate results than the lexer-based approach: method names, type names, annotations, and all
 * structural tokens are correctly identified from the parse tree rather than inferred from token
 * types alone.
 *
 * <p>Supports Java, Python, Go, Kotlin, JavaScript, TypeScript, Rust, Bash, and Proto.
 * Initialization is lazy and per-language failure-tolerant: if a grammar fails to load only that
 * language falls back to plain FG_COLOR spans; other languages are unaffected.
 *
 * <p>Captures are collected for an entire hunk at once (all lines joined with {@code \n}) so the
 * parser sees enough context to resolve multi-line constructs correctly. Token colours match
 * GitHub's dark mode palette so the diff panel looks consistent with how the PR appears on
 * github.com.
 *
 * <p>nvim-treesitter predicate annotations ({@code #lua-match?}, {@code #eq?}, {@code #any-of?},
 * {@code #match?} and their negations) are evaluated during match processing so that general
 * catch-all patterns are correctly narrowed.
 */
@Slf4j
class TreeSitterHighlighter {

    // -----------------------------------------------------------------------
    // GitHub dark mode colour palette (@primer/primitives dark scale)
    // -----------------------------------------------------------------------

    /** Keywords: if, for, return, public, static, int, void, … — scale.red[3] */
    static final Color COLOR_KEYWORD = new Color(0xff7b72);

    /** Type/class declaration names — pl-smi — scale.gray[0] */
    static final Color COLOR_ENTITY = new Color(0xf0f6fc);

    /** Function/method declaration names — pl-en — scale.purple[2] */
    static final Color COLOR_FUNCTION = new Color(0xd2a8ff);

    /** Constants, numbers, this/super, annotations — pl-c1 — scale.blue[2] */
    static final Color COLOR_CONSTANT = new Color(0x79c0ff);

    /** String literals — scale.blue[1] */
    static final Color COLOR_STRING = new Color(0xa5d6ff);

    /** Comments — scale.gray[3] */
    static final Color COLOR_COMMENT = new Color(0x8b949e);

    // -----------------------------------------------------------------------

    private static volatile boolean initialized = false;

    /** Compiled Lua-pattern → Java regex cache, keyed on the raw Lua pattern string. */
    private static final Map<String, Pattern> PATTERN_CACHE = new HashMap<>();

    private static final Map<String, LanguageEntry> LANGUAGE_ENTRIES = new HashMap<>();

    // -----------------------------------------------------------------------
    // Internal data structures
    // -----------------------------------------------------------------------

    private record LanguageEntry(TSLanguage language, TSQuery query) {}

    private record QueryPredicate(String name, String captureName, List<String> args) {}

    private record CaptureRange(int start, int end, int patternIndex, Color color) {}

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} if the grammar for the given extension loaded successfully. Useful for
     * tests to distinguish "highlighting not working" from "language not supported".
     */
    static boolean isLanguageLoaded(String extension) {
        ensureInitialized();
        return LANGUAGE_ENTRIES.containsKey(extension);
    }

    /**
     * Highlights all lines in a hunk. Always returns a non-null, non-empty result: unsupported
     * languages and parse failures produce a list of single {@link DiffHighlighter#FG_COLOR} spans.
     *
     * @param lines code content lines (diff prefix stripped)
     * @param extension file extension, e.g. {@code "java"}
     * @return one {@link DiffHighlighter.Span} list per input line
     */
    static List<List<DiffHighlighter.Span>> colorHunk(List<String> lines, String extension) {
        ensureInitialized();
        if (lines.isEmpty()) {
            return new ArrayList<>();
        }
        LanguageEntry entry = LANGUAGE_ENTRIES.get(extension);
        if (entry == null) {
            return fgColorSpans(lines);
        }

        StringBuilder sb = new StringBuilder();
        int[] lineOffsets = new int[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            lineOffsets[i] = sb.length();
            sb.append(lines.get(i));
            if (i < lines.size() - 1) {
                sb.append('\n');
            }
        }
        String source = sb.toString();

        // Tree-sitter returns UTF-8 byte offsets; Java strings use char (UTF-16) offsets.
        // Build a mapping so that captures stay aligned even when non-ASCII chars are present
        // (e.g. © in a copyright comment shifts every subsequent byte offset by one or more).
        int[] byteToChar = buildByteToCharMap(source);

        // TSParser, TSTree, and TSQueryCursor clean up native memory via GC Cleaner —
        // no explicit close() calls are needed or available.
        try {
            TSParser parser = new TSParser();
            parser.setLanguage(entry.language());
            TSTree tree = parser.parseString(null, source);
            return buildSpans(tree, entry.query(), source, lines, lineOffsets, byteToChar);
        } catch (Exception e) {
            log.warn("TreeSitterHighlighter: highlight failed for .{}", extension, e);
            return fgColorSpans(lines);
        }
    }

    // -----------------------------------------------------------------------
    // Span building
    // -----------------------------------------------------------------------

    private static List<List<DiffHighlighter.Span>> buildSpans(
            TSTree tree,
            TSQuery query,
            String source,
            List<String> lines,
            int[] lineOffsets,
            int[] byteToChar) {

        int sourceLen = source.length();
        // Precompute which char positions fall inside "..." quoted strings. When a grammar uses
        // error recovery it re-tokenizes string content and produces spurious keyword tokens (e.g.
        // "rpc" inside a proto import path). Skipping captures whose start position is inside a
        // quoted region prevents mis-coloring without suppressing valid keywords outside strings.
        boolean[] inQuote = buildInQuoteMap(source);
        Map<Integer, List<QueryPredicate>> predicateCache = new HashMap<>();

        List<CaptureRange> captureRanges = new ArrayList<>();
        TSQueryCursor cursor = new TSQueryCursor();
        cursor.exec(query, tree.getRootNode());
        TSQueryMatch match = new TSQueryMatch();
        while (cursor.nextMatch(match)) {
            int patternIndex = match.getPatternIndex();
            List<QueryPredicate> preds =
                    predicateCache.computeIfAbsent(
                            patternIndex, idx -> parsePredicates(query, idx));
            if (!evaluatePredicates(preds, match, query, source, byteToChar)) {
                continue;
            }
            for (TSQueryCapture capture : match.getCaptures()) {
                int startByte = capture.getNode().getStartByte();
                int endByte = capture.getNode().getEndByte();
                if (startByte >= endByte || startByte >= byteToChar.length) {
                    continue;
                }
                int start = byteToChar[startByte];
                int end = endByte < byteToChar.length ? byteToChar[endByte] : sourceLen;
                if (start >= end || start >= sourceLen) {
                    continue;
                }
                end = Math.min(end, sourceLen);
                String captureName = query.getCaptureNameForId(capture.getIndex());
                Color color = captureColor(captureName);
                if (color == null) {
                    continue;
                }
                // Suppress captures that start inside a quoted string. Grammars using error
                // recovery re-tokenize string content and produce spurious keyword tokens (e.g.
                // "rpc" in a proto import path like "proto/.../grpc/...").
                if (start < inQuote.length && inQuote[start]) {
                    continue;
                }
                captureRanges.add(new CaptureRange(start, end, patternIndex, color));
            }
        }

        captureRanges.sort(Comparator.comparingInt(CaptureRange::patternIndex));
        Color[] charColors = new Color[sourceLen];
        for (CaptureRange r : captureRanges) {
            for (int i = r.start(); i < r.end(); i++) {
                charColors[i] = r.color();
            }
        }

        List<List<DiffHighlighter.Span>> result = new ArrayList<>(lines.size());
        for (int lineIdx = 0; lineIdx < lines.size(); lineIdx++) {
            int lineStart = lineOffsets[lineIdx];
            int lineLen = lines.get(lineIdx).length();
            List<DiffHighlighter.Span> spans = new ArrayList<>();

            if (lineLen == 0) {
                spans.add(new DiffHighlighter.Span(0, 0, DiffHighlighter.FG_COLOR));
                result.add(spans);
                continue;
            }

            Color spanColor = colorAt(charColors, lineStart, sourceLen);
            int spanStart = 0;

            for (int offset = 1; offset <= lineLen; offset++) {
                Color next =
                        offset < lineLen
                                ? colorAt(charColors, lineStart + offset, sourceLen)
                                : null;
                if (next == null || !next.equals(spanColor)) {
                    spans.add(new DiffHighlighter.Span(spanStart, offset, spanColor));
                    spanStart = offset;
                    if (next != null) {
                        spanColor = next;
                    }
                }
            }
            result.add(spans);
        }
        return result;
    }

    private static Color colorAt(Color[] charColors, int idx, int len) {
        if (idx < 0 || idx >= len) {
            return DiffHighlighter.FG_COLOR;
        }
        return charColors[idx] != null ? charColors[idx] : DiffHighlighter.FG_COLOR;
    }

    private static List<List<DiffHighlighter.Span>> fgColorSpans(List<String> lines) {
        return lines.stream()
                .map(
                        line ->
                                List.of(
                                        new DiffHighlighter.Span(
                                                0, line.length(), DiffHighlighter.FG_COLOR)))
                .collect(Collectors.toList());
    }

    // -----------------------------------------------------------------------
    // Predicate evaluation
    // -----------------------------------------------------------------------

    private static List<QueryPredicate> parsePredicates(TSQuery query, int patternIndex) {
        TSQueryPredicateStep[] steps = query.getPredicateForPattern(patternIndex);
        if (steps == null || steps.length == 0) {
            return List.of();
        }
        List<QueryPredicate> result = new ArrayList<>();
        int i = 0;
        while (i < steps.length) {
            if (steps[i].getType() != TSQueryPredicateStepType.TSQueryPredicateStepTypeString) {
                i++;
                continue;
            }
            String predName = query.getStringValueForId(steps[i].getValueId());
            i++;
            String captureName = null;
            List<String> args = new ArrayList<>();
            while (i < steps.length
                    && steps[i].getType()
                            != TSQueryPredicateStepType.TSQueryPredicateStepTypeDone) {
                TSQueryPredicateStep step = steps[i];
                if (step.getType() == TSQueryPredicateStepType.TSQueryPredicateStepTypeCapture) {
                    captureName = query.getCaptureNameForId(step.getValueId());
                } else if (step.getType()
                        == TSQueryPredicateStepType.TSQueryPredicateStepTypeString) {
                    args.add(query.getStringValueForId(step.getValueId()));
                }
                i++;
            }
            i++; // skip DONE
            result.add(new QueryPredicate(predName, captureName, List.copyOf(args)));
        }
        return result;
    }

    private static boolean evaluatePredicates(
            List<QueryPredicate> predicates,
            TSQueryMatch match,
            TSQuery query,
            String source,
            int[] byteToChar) {
        return predicates.stream()
                .allMatch(pred -> evaluatePredicate(pred, match, query, source, byteToChar));
    }

    private static boolean evaluatePredicate(
            QueryPredicate pred,
            TSQueryMatch match,
            TSQuery query,
            String source,
            int[] byteToChar) {
        if (pred.captureName() == null || pred.args().isEmpty()) {
            return true;
        }
        String captureText =
                resolveCaptureText(pred.captureName(), match, query, source, byteToChar);
        if (captureText == null) {
            return true;
        }
        return switch (pred.name()) {
            case "lua-match?", "match?" -> matchesPattern(captureText, pred.args().get(0));
            case "not-lua-match?", "not-match?" -> !matchesPattern(captureText, pred.args().get(0));
            case "eq?" -> captureText.equals(pred.args().get(0));
            case "not-eq?" -> !captureText.equals(pred.args().get(0));
            case "any-of?" -> pred.args().contains(captureText);
            case "not-any-of?" -> !pred.args().contains(captureText);
            default -> true;
        };
    }

    private static String resolveCaptureText(
            String captureName,
            TSQueryMatch match,
            TSQuery query,
            String source,
            int[] byteToChar) {
        for (TSQueryCapture cap : match.getCaptures()) {
            if (captureName.equals(query.getCaptureNameForId(cap.getIndex()))) {
                int startByte = cap.getNode().getStartByte();
                int endByte = cap.getNode().getEndByte();
                if (startByte >= 0 && startByte < endByte && startByte < byteToChar.length) {
                    int start = byteToChar[startByte];
                    int end = endByte < byteToChar.length ? byteToChar[endByte] : source.length();
                    if (start < end && end <= source.length()) {
                        return source.substring(start, end);
                    }
                }
            }
        }
        return null;
    }

    private static boolean matchesPattern(String text, String luaPattern) {
        Pattern compiled = PATTERN_CACHE.get(luaPattern);
        if (compiled == null) {
            try {
                compiled = Pattern.compile(luaPatternToJava(luaPattern));
            } catch (PatternSyntaxException e) {
                log.warn(
                        "TreeSitterHighlighter: invalid pattern '{}': {}",
                        luaPattern,
                        e.getMessage());
                return true;
            }
            PATTERN_CACHE.put(luaPattern, compiled);
        }
        return compiled.matcher(text).find();
    }

    /**
     * Converts a Lua pattern to a Java regex string. Handles the {@code %d/%a/%l/%u/%w/%s/%x/%p}
     * character-class escapes used by nvim-treesitter highlight queries.
     */
    static String luaPatternToJava(String lua) {
        StringBuilder out = new StringBuilder(lua.length() * 2);
        int i = 0;
        while (i < lua.length()) {
            char c = lua.charAt(i);
            if (c == '%' && i + 1 < lua.length()) {
                char n = lua.charAt(i + 1);
                switch (n) {
                    case 'd' -> out.append("[0-9]");
                    case 'D' -> out.append("[^0-9]");
                    case 'a' -> out.append("[a-zA-Z]");
                    case 'A' -> out.append("[^a-zA-Z]");
                    case 'l' -> out.append("[a-z]");
                    case 'L' -> out.append("[^a-z]");
                    case 'u' -> out.append("[A-Z]");
                    case 'U' -> out.append("[^A-Z]");
                    case 'w' -> out.append("[a-zA-Z0-9]");
                    case 'W' -> out.append("[^a-zA-Z0-9]");
                    case 's' -> out.append("\\s");
                    case 'S' -> out.append("\\S");
                    case 'p' -> out.append("\\p{Punct}");
                    case 'c' -> out.append("\\p{Cntrl}");
                    case 'x' -> out.append("[0-9a-fA-F]");
                    case 'X' -> out.append("[^0-9a-fA-F]");
                    default -> out.append(n);
                }
                i += 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    // -----------------------------------------------------------------------
    // Capture-name → colour mapping (GitHub dark mode palette)
    // -----------------------------------------------------------------------

    /**
     * Maps a tree-sitter capture name to a GitHub dark-mode colour. Returns {@code null} for
     * captures with no meaningful colour (e.g. {@code @variable}, {@code @operator}, {@code
     * @spell}) so they are skipped rather than overwriting a more specific colour already set.
     *
     * <p>Colour values come from GitHub's Primer design system (@primer/primitives dark scale):
     *
     * <ul>
     *   <li>{@code #ff7b72} — scale.red[3] — pl-k: keywords, primitives
     *   <li>{@code #f0f6fc} — scale.gray[0] — pl-smi: type/class names
     *   <li>{@code #d2a8ff} — scale.purple[2] — pl-en: method/function declaration names
     *   <li>{@code #79c0ff} — scale.blue[2] — pl-c1: annotations, constants, numbers
     *   <li>{@code #a5d6ff} — scale.blue[1] — pl-s: string literals
     *   <li>{@code #8b949e} — scale.gray[3] — pl-c: comments
     * </ul>
     */
    static Color captureColor(String name) {
        return switch (name) {
                // Keywords — red (storage.type, keyword.* scopes on GitHub)
            case "keyword",
                            "keyword.type",
                            "keyword.modifier",
                            "keyword.return",
                            "keyword.operator",
                            "keyword.conditional",
                            "keyword.conditional.ternary",
                            "keyword.repeat",
                            "keyword.import",
                            "keyword.exception",
                            "keyword.function",
                            "keyword.coroutine",
                            // Literals/storage types that are keywords in their language
                            "boolean",
                            "type.builtin" ->
                    COLOR_KEYWORD;

                // Type/class declaration names — orange (entity.name on GitHub)
            case "type", "type.definition" -> COLOR_ENTITY;

                // Function/method declaration names — pl-en purple on GitHub
            case "function",
                            "function.method",
                            "function.call",
                            "function.method.call",
                            "function.macro",
                            "function.builtin",
                            "constructor" ->
                    COLOR_FUNCTION;

                // Constants, numbers, this/super, annotations, null/true/false builtins — pl-c1
                // blue on GitHub
            case "variable.builtin",
                            "constant",
                            "constant.builtin",
                            "number",
                            "number.float",
                            "attribute" ->
                    COLOR_CONSTANT;

                // Strings
            case "string", "string.escape", "string.regexp", "string.special", "character" ->
                    COLOR_STRING;

                // Comments
            case "comment", "comment.documentation" -> COLOR_COMMENT;

                // Calls, operators, variables, properties — no special colour on GitHub
                // Return null so these are skipped and underlying FG_COLOR shows through.
            default -> null;
        };
    }

    // -----------------------------------------------------------------------
    // Quoted-string region map
    // -----------------------------------------------------------------------

    /**
     * Builds a boolean array where {@code map[i]} is {@code true} when char offset {@code i} falls
     * strictly inside a {@code "..."} quoted string (i.e. after an opening {@code "} and before the
     * matching closing {@code "}). The quote characters themselves map to {@code false}.
     *
     * <p>This is used to suppress captures that a grammar's error-recovery emits inside string
     * literals (e.g. the proto grammar re-tokenizing an import path as keyword tokens).
     */
    static boolean[] buildInQuoteMap(String source) {
        boolean[] map = new boolean[source.length()];
        boolean inside = false;
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '"') {
                // Toggle on the delimiter itself; the delimiter char stays false.
                inside = !inside;
            } else {
                map[i] = inside;
            }
        }
        return map;
    }

    // -----------------------------------------------------------------------
    // Byte-to-char mapping
    // -----------------------------------------------------------------------

    /**
     * Builds a mapping from UTF-8 byte offset to Java {@code String} char offset for {@code
     * source}. Tree-sitter returns byte offsets; Java strings use char (UTF-16) offsets. The
     * returned array has length {@code utf8ByteCount + 1}: {@code byteToChar[b]} is the char index
     * of the code point whose first UTF-8 byte is at position {@code b}. The final entry {@code
     * byteToChar[utf8ByteCount]} equals {@code source.length()} as a sentinel for end-of-input.
     *
     * <p>For pure ASCII sources, every byte offset equals the corresponding char offset, so this is
     * a no-op in the common case.
     */
    static int[] buildByteToCharMap(String source) {
        byte[] utf8 = source.getBytes(StandardCharsets.UTF_8);
        int[] map = new int[utf8.length + 1];
        int charIdx = 0;
        int byteIdx = 0;
        while (byteIdx < utf8.length) {
            int b = utf8[byteIdx] & 0xFF;
            int seqLen;
            if (b < 0x80) {
                seqLen = 1;
            } else if (b < 0xE0) {
                seqLen = 2;
            } else if (b < 0xF0) {
                seqLen = 3;
            } else {
                seqLen = 4;
            }
            // All bytes in this sequence map to the same char (the code point start).
            for (int k = 0; k < seqLen && byteIdx + k < utf8.length; k++) {
                map[byteIdx + k] = charIdx;
            }
            byteIdx += seqLen;
            // Surrogate pairs: a 4-byte UTF-8 sequence encodes a supplementary code point that
            // Java represents as two chars (a surrogate pair). Advance charIdx by 2 in that case.
            charIdx += (seqLen == 4) ? 2 : 1;
        }
        map[utf8.length] = source.length(); // sentinel: past-the-end
        return map;
    }

    // -----------------------------------------------------------------------
    // Initialisation
    // -----------------------------------------------------------------------

    private static synchronized void ensureInitialized() {
        if (initialized) {
            return;
        }
        initialized = true;
        tryLoad("java", TreeSitterJava::new, "/highlights/java.scm");
        tryLoad("py", TreeSitterPython::new, "/highlights/python.scm");
        tryLoad("go", TreeSitterGo::new, "/highlights/go.scm");
        tryLoad("rs", TreeSitterRust::new, "/highlights/rust.scm");
        tryLoad("js", TreeSitterJavascript::new, "/highlights/javascript.scm");
        tryLoad("kt", TreeSitterKotlin::new, "/highlights/kotlin.scm");
        tryLoad("sh", TreeSitterBash::new, "/highlights/bash.scm");
        tryLoad("bash", TreeSitterBash::new, "/highlights/bash.scm");
        tryLoad("proto", TreeSitterProto::new, "/highlights/proto.scm");
        tryLoadTypescript();

        alias("jsx", "js");
        alias("mjs", "js");
        alias("cjs", "js");
        alias("kts", "kt");
        alias("tsx", "ts");

        log.info(
                "TreeSitterHighlighter: initialized, supported extensions: {}",
                LANGUAGE_ENTRIES.keySet());
    }

    private static void tryLoad(
            String extension, Supplier<TSLanguage> langFactory, String queryResource) {
        try {
            TSLanguage lang = langFactory.get();
            String queryStr = loadResource(queryResource);
            if (queryStr == null) {
                return;
            }
            LANGUAGE_ENTRIES.put(extension, new LanguageEntry(lang, new TSQuery(lang, queryStr)));
        } catch (UnsatisfiedLinkError | Exception e) {
            log.warn("TreeSitterHighlighter: failed to load grammar for .{}", extension, e);
        }
    }

    private static void tryLoadTypescript() {
        String queryStr = loadResource("/highlights/typescript.scm");
        if (queryStr == null) {
            return;
        }
        String[] classNames = {
            "org.treesitter.TreeSitterTypescriptTypescript", "org.treesitter.TreeSitterTypescript",
        };
        for (String className : classNames) {
            try {
                Class<?> cls = Class.forName(className);
                TSLanguage lang = (TSLanguage) cls.getDeclaredConstructor().newInstance();
                TSQuery query = new TSQuery(lang, queryStr);
                LANGUAGE_ENTRIES.put("ts", new LanguageEntry(lang, query));
                log.info("TreeSitterHighlighter: loaded TypeScript grammar via {}", className);
                return;
            } catch (UnsatisfiedLinkError | Exception ignored) {
                // try next class name
            }
        }
        log.warn("TreeSitterHighlighter: TypeScript grammar not available");
    }

    private static void alias(String alias, String canonical) {
        LanguageEntry entry = LANGUAGE_ENTRIES.get(canonical);
        if (entry != null) {
            LANGUAGE_ENTRIES.put(alias, entry);
        }
    }

    private static String loadResource(String path) {
        try (InputStream is = TreeSitterHighlighter.class.getResourceAsStream(path)) {
            if (is == null) {
                log.warn("TreeSitterHighlighter: resource not found: {}", path);
                return null;
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("TreeSitterHighlighter: failed to load {}", path, e);
            return null;
        }
    }
}
