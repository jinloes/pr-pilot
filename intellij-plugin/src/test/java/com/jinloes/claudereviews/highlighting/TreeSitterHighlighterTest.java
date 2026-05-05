package com.jinloes.claudereviews.highlighting;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TreeSitterHighlighterTest {

    @Nested
    class LuaPatternToJava {

        @Test
        void noEscapes_passesThrough() {
            assertThat(TreeSitterHighlighter.luaPatternToJava("^[A-Z]")).isEqualTo("^[A-Z]");
        }

        @Test
        void percentD_becomesDigitClass() {
            assertThat(TreeSitterHighlighter.luaPatternToJava("%d")).isEqualTo("[0-9]");
        }

        @Test
        void percentU_becomesUpperClass() {
            assertThat(TreeSitterHighlighter.luaPatternToJava("%u")).isEqualTo("[A-Z]");
        }

        @Test
        void percentL_becomesLowerClass() {
            assertThat(TreeSitterHighlighter.luaPatternToJava("%l")).isEqualTo("[a-z]");
        }

        @Test
        void percentA_becomesAlphaClass() {
            assertThat(TreeSitterHighlighter.luaPatternToJava("%a")).isEqualTo("[a-zA-Z]");
        }

        @Test
        void percentW_becomesAlphanumClass() {
            assertThat(TreeSitterHighlighter.luaPatternToJava("%w")).isEqualTo("[a-zA-Z0-9]");
        }

        @Test
        void insideCharClass_percentD() {
            // [A-Z%d_] in Lua → [A-Z[0-9]_] in Java (nested union, valid)
            assertThat(TreeSitterHighlighter.luaPatternToJava("[A-Z%d_]")).isEqualTo("[A-Z[0-9]_]");
        }

        @Test
        void constantPattern_upperCaseWithDigitsAndUnderscores() {
            // Pattern from kotlin.scm / javascript.scm; verify conversion and matching
            String result = TreeSitterHighlighter.luaPatternToJava("^_*[A-Z][A-Z%d_]*$");
            assertThat(result).isEqualTo("^_*[A-Z][A-Z[0-9]_]*$");
            var pat = java.util.regex.Pattern.compile(result);
            assertThat(pat.matcher("MAX_SIZE").find()).isTrue();
            assertThat(pat.matcher("_PRIVATE_CONST").find()).isTrue();
            assertThat(pat.matcher("myVariable").find()).isFalse();
        }

        @Test
        void uppercaseTypePattern() {
            // luaPatternToJava converts the pattern; matchesPattern uses Matcher.find() semantics
            String result = TreeSitterHighlighter.luaPatternToJava("^[A-Z]");
            assertThat(java.util.regex.Pattern.compile(result).matcher("String").find()).isTrue();
            assertThat(java.util.regex.Pattern.compile(result).matcher("string").find()).isFalse();
        }

        @Test
        void trailingPercent_treatedAsLiteral() {
            // % at end of string with no following char — should not throw
            assertThat(TreeSitterHighlighter.luaPatternToJava("abc%")).isEqualTo("abc%");
        }

        @Test
        void percentX_becomesHexClass() {
            assertThat(TreeSitterHighlighter.luaPatternToJava("%x")).isEqualTo("[0-9a-fA-F]");
        }
    }

    @Nested
    class CaptureColor {

        @Test
        void keywords_returnKeywordColor() {
            assertThat(TreeSitterHighlighter.captureColor("keyword"))
                    .isEqualTo(TreeSitterHighlighter.COLOR_KEYWORD);
            assertThat(TreeSitterHighlighter.captureColor("keyword.type"))
                    .isEqualTo(TreeSitterHighlighter.COLOR_KEYWORD);
            assertThat(TreeSitterHighlighter.captureColor("keyword.return"))
                    .isEqualTo(TreeSitterHighlighter.COLOR_KEYWORD);
            assertThat(TreeSitterHighlighter.captureColor("boolean"))
                    .isEqualTo(TreeSitterHighlighter.COLOR_KEYWORD);
        }

        @Test
        void functionDeclarations_returnFunctionColor() {
            // method/function declaration names → pl-en purple on GitHub
            assertThat(TreeSitterHighlighter.captureColor("function"))
                    .isEqualTo(TreeSitterHighlighter.COLOR_FUNCTION);
            assertThat(TreeSitterHighlighter.captureColor("function.method"))
                    .isEqualTo(TreeSitterHighlighter.COLOR_FUNCTION);
            assertThat(TreeSitterHighlighter.captureColor("constructor"))
                    .isEqualTo(TreeSitterHighlighter.COLOR_FUNCTION);
        }

        @Test
        void typeDeclarations_returnEntityColor() {
            // entity.name → scale.orange[2] on GitHub
            assertThat(TreeSitterHighlighter.captureColor("type"))
                    .isEqualTo(TreeSitterHighlighter.COLOR_ENTITY);
            assertThat(TreeSitterHighlighter.captureColor("type.definition"))
                    .isEqualTo(TreeSitterHighlighter.COLOR_ENTITY);
        }

        @Test
        void attributes_returnConstantColor() {
            // Annotations/decorators → pl-c1 blue on GitHub
            assertThat(TreeSitterHighlighter.captureColor("attribute"))
                    .isEqualTo(TreeSitterHighlighter.COLOR_CONSTANT);
        }

        @Test
        void typeBuiltin_returnKeywordColor() {
            // Primitive types (int, void, boolean) — storage.type on GitHub = keyword red
            assertThat(TreeSitterHighlighter.captureColor("type.builtin"))
                    .isEqualTo(TreeSitterHighlighter.COLOR_KEYWORD);
        }

        @Test
        void variableBuiltin_returnConstantColor() {
            // this/super = variable.language → scale.blue[2] on GitHub
            assertThat(TreeSitterHighlighter.captureColor("variable.builtin"))
                    .isEqualTo(TreeSitterHighlighter.COLOR_CONSTANT);
        }

        @Test
        void functionCalls_returnFunctionColor() {
            // Calls → pl-en purple on GitHub, same as declarations
            assertThat(TreeSitterHighlighter.captureColor("function.call"))
                    .isEqualTo(TreeSitterHighlighter.COLOR_FUNCTION);
            assertThat(TreeSitterHighlighter.captureColor("function.method.call"))
                    .isEqualTo(TreeSitterHighlighter.COLOR_FUNCTION);
        }

        @Test
        void strings_returnStringColor() {
            assertThat(TreeSitterHighlighter.captureColor("string"))
                    .isEqualTo(TreeSitterHighlighter.COLOR_STRING);
            assertThat(TreeSitterHighlighter.captureColor("string.escape"))
                    .isEqualTo(TreeSitterHighlighter.COLOR_STRING);
        }

        @Test
        void comments_returnCommentColor() {
            assertThat(TreeSitterHighlighter.captureColor("comment"))
                    .isEqualTo(TreeSitterHighlighter.COLOR_COMMENT);
            assertThat(TreeSitterHighlighter.captureColor("comment.documentation"))
                    .isEqualTo(TreeSitterHighlighter.COLOR_COMMENT);
        }

        @Test
        void numbers_returnConstantColor() {
            assertThat(TreeSitterHighlighter.captureColor("number"))
                    .isEqualTo(TreeSitterHighlighter.COLOR_CONSTANT);
            assertThat(TreeSitterHighlighter.captureColor("number.float"))
                    .isEqualTo(TreeSitterHighlighter.COLOR_CONSTANT);
            assertThat(TreeSitterHighlighter.captureColor("constant"))
                    .isEqualTo(TreeSitterHighlighter.COLOR_CONSTANT);
            // null/undefined/self → pl-c1 blue on GitHub, not keyword red
            assertThat(TreeSitterHighlighter.captureColor("constant.builtin"))
                    .isEqualTo(TreeSitterHighlighter.COLOR_CONSTANT);
        }

        @Test
        void unknownCaptures_returnNull() {
            assertThat(TreeSitterHighlighter.captureColor("variable")).isNull();
            assertThat(TreeSitterHighlighter.captureColor("variable.member")).isNull();
            assertThat(TreeSitterHighlighter.captureColor("operator")).isNull();
            assertThat(TreeSitterHighlighter.captureColor("spell")).isNull();
            assertThat(TreeSitterHighlighter.captureColor("property")).isNull();
        }
    }

    @Nested
    class JavaKeywords {

        @Test
        void javaGrammarLoads() {
            assertThat(TreeSitterHighlighter.isLanguageLoaded("java"))
                    .as(
                            "java grammar failed to load — check test output for"
                                    + " UnsatisfiedLinkError or TSQuery exception")
                    .isTrue();
        }

        @Test
        void privateStaticReturnNew_areColoredAsKeywords() {
            // Only meaningful if grammar loads.
            if (!TreeSitterHighlighter.isLanguageLoaded("java")) {
                return;
            }
            var lines = List.of("private static void foo() {", "return new ArrayList<>();", "}");
            var spans = TreeSitterHighlighter.colorHunk(lines, "java");

            assertThat(spans).hasSize(3);

            // Dump all spans for diagnostics — visible in test output on failure.
            for (int i = 0; i < spans.size(); i++) {
                System.out.println("Line " + i + " [" + lines.get(i) + "]:");
                for (var s : spans.get(i)) {
                    String tokenText = lines.get(i).substring(s.start(), s.end());
                    System.out.println(
                            "  span ["
                                    + s.start()
                                    + ","
                                    + s.end()
                                    + "] color="
                                    + (s.color() != null
                                            ? "#"
                                                    + Integer.toHexString(
                                                            s.color().getRGB() & 0xFFFFFF)
                                            : "null")
                                    + " text='"
                                    + tokenText
                                    + "'");
                }
            }

            // Line 0: "private static void foo() {"
            boolean privateColored =
                    spans.get(0).stream()
                            .anyMatch(
                                    s ->
                                            TreeSitterHighlighter.COLOR_KEYWORD.equals(s.color())
                                                    && lines.get(0)
                                                            .substring(s.start(), s.end())
                                                            .contains("private"));
            assertThat(privateColored)
                    .as("'private' should be COLOR_KEYWORD (#ff7b72) on line 0")
                    .isTrue();

            boolean staticColored =
                    spans.get(0).stream()
                            .anyMatch(
                                    s ->
                                            TreeSitterHighlighter.COLOR_KEYWORD.equals(s.color())
                                                    && lines.get(0)
                                                            .substring(s.start(), s.end())
                                                            .contains("static"));
            assertThat(staticColored)
                    .as("'static' should be COLOR_KEYWORD (#ff7b72) on line 0")
                    .isTrue();

            // Line 1: "return new ArrayList<>();"
            boolean returnColored =
                    spans.get(1).stream()
                            .anyMatch(
                                    s ->
                                            TreeSitterHighlighter.COLOR_KEYWORD.equals(s.color())
                                                    && lines.get(1)
                                                            .substring(s.start(), s.end())
                                                            .contains("return"));
            assertThat(returnColored)
                    .as("'return' should be COLOR_KEYWORD (#ff7b72) on line 1")
                    .isTrue();

            boolean newColored =
                    spans.get(1).stream()
                            .anyMatch(
                                    s ->
                                            TreeSitterHighlighter.COLOR_KEYWORD.equals(s.color())
                                                    && lines.get(1)
                                                            .substring(s.start(), s.end())
                                                            .contains("new"));
            assertThat(newColored).as("'new' should be COLOR_KEYWORD (#ff7b72) on line 1").isTrue();
        }

        @Test
        void mixedDiffHunk_keywordsAreColoredOnBothDeletedAndAddedLines() {
            // Simulates what precomputeSpans feeds to colorHunk: ALL diff lines (deleted AND added)
            // stripped of their +/- prefix, in the order they appear in the diff.
            if (!TreeSitterHighlighter.isLanguageLoaded("java")) {
                return;
            }
            var lines =
                    List.of(
                            "public class Foo {",
                            "    private static int count = 0;", // deleted line (- prefix stripped)
                            "    private static int count = 1;", // added line  (+ prefix stripped)
                            "    public static String getStatus() {",
                            "        return new StringBuilder().toString();",
                            "    }",
                            "}");
            var spans = TreeSitterHighlighter.colorHunk(lines, "java");

            assertThat(spans).hasSize(7);

            // Dump ALL spans for diagnostics.
            for (int i = 0; i < spans.size(); i++) {
                System.out.println("MixedDiff Line " + i + " [" + lines.get(i) + "]:");
                for (var s : spans.get(i)) {
                    String tokenText = lines.get(i).substring(s.start(), s.end());
                    System.out.printf(
                            "  span [%d,%d] color=%s text='%s'%n",
                            s.start(),
                            s.end(),
                            s.color() != null
                                    ? "#" + String.format("%06x", s.color().getRGB() & 0xFFFFFF)
                                    : "null",
                            tokenText);
                }
            }

            String keyword =
                    "#"
                            + String.format(
                                    "%06x",
                                    TreeSitterHighlighter.COLOR_KEYWORD.getRGB() & 0xFFFFFF);
            System.out.println("Expected COLOR_KEYWORD = " + keyword);

            // Line 1 (deleted): "    private static int count = 0;"
            boolean privateOnDeletedLine =
                    spans.get(1).stream()
                            .anyMatch(
                                    s ->
                                            TreeSitterHighlighter.COLOR_KEYWORD.equals(s.color())
                                                    && lines.get(1)
                                                            .substring(s.start(), s.end())
                                                            .contains("private"));
            assertThat(privateOnDeletedLine)
                    .as("'private' on the deleted line (line 1) should be COLOR_KEYWORD")
                    .isTrue();

            boolean staticOnDeletedLine =
                    spans.get(1).stream()
                            .anyMatch(
                                    s ->
                                            TreeSitterHighlighter.COLOR_KEYWORD.equals(s.color())
                                                    && lines.get(1)
                                                            .substring(s.start(), s.end())
                                                            .contains("static"));
            assertThat(staticOnDeletedLine)
                    .as("'static' on the deleted line (line 1) should be COLOR_KEYWORD")
                    .isTrue();

            // Line 4: "        return new StringBuilder().toString();"
            boolean returnColored =
                    spans.get(4).stream()
                            .anyMatch(
                                    s ->
                                            TreeSitterHighlighter.COLOR_KEYWORD.equals(s.color())
                                                    && lines.get(4)
                                                            .substring(s.start(), s.end())
                                                            .contains("return"));
            assertThat(returnColored).as("'return' should be COLOR_KEYWORD on line 4").isTrue();

            boolean newColored =
                    spans.get(4).stream()
                            .anyMatch(
                                    s ->
                                            TreeSitterHighlighter.COLOR_KEYWORD.equals(s.color())
                                                    && lines.get(4)
                                                            .substring(s.start(), s.end())
                                                            .contains("new"));
            assertThat(newColored).as("'new' should be COLOR_KEYWORD on line 4").isTrue();
        }

        @Test
        void realisticClassSnippet_keywordsAreColored() {
            // Only meaningful if grammar loads.
            if (!TreeSitterHighlighter.isLanguageLoaded("java")) {
                return;
            }
            var lines =
                    List.of(
                            "import java.util.ArrayList;",
                            "public class Foo {",
                            "    private static final int MAX = 10;",
                            "    private int count;",
                            "    public static void bar() {",
                            "        return new ArrayList<>();",
                            "    }",
                            "}");
            var spans = TreeSitterHighlighter.colorHunk(lines, "java");

            assertThat(spans).hasSize(8);

            // Dump ALL spans for EVERY line so we can see what tree-sitter returns.
            for (int i = 0; i < spans.size(); i++) {
                System.out.println("Line " + i + " [" + lines.get(i) + "]:");
                for (var s : spans.get(i)) {
                    String tokenText = lines.get(i).substring(s.start(), s.end());
                    System.out.printf(
                            "  span [%d,%d] color=%s text='%s'%n",
                            s.start(),
                            s.end(),
                            s.color() != null
                                    ? "#" + String.format("%06x", s.color().getRGB() & 0xFFFFFF)
                                    : "null",
                            tokenText);
                }
            }

            // Line 2: "    private static final int MAX = 10;"
            boolean privateColored =
                    spans.get(2).stream()
                            .anyMatch(
                                    s ->
                                            TreeSitterHighlighter.COLOR_KEYWORD.equals(s.color())
                                                    && lines.get(2)
                                                            .substring(s.start(), s.end())
                                                            .contains("private"));
            assertThat(privateColored).as("'private' should be COLOR_KEYWORD on line 2").isTrue();

            boolean staticColored =
                    spans.get(2).stream()
                            .anyMatch(
                                    s ->
                                            TreeSitterHighlighter.COLOR_KEYWORD.equals(s.color())
                                                    && lines.get(2)
                                                            .substring(s.start(), s.end())
                                                            .contains("static"));
            assertThat(staticColored).as("'static' should be COLOR_KEYWORD on line 2").isTrue();

            // Line 5: "        return new ArrayList<>();"
            boolean returnColored =
                    spans.get(5).stream()
                            .anyMatch(
                                    s ->
                                            TreeSitterHighlighter.COLOR_KEYWORD.equals(s.color())
                                                    && lines.get(5)
                                                            .substring(s.start(), s.end())
                                                            .contains("return"));
            assertThat(returnColored).as("'return' should be COLOR_KEYWORD on line 5").isTrue();

            boolean newColored2 =
                    spans.get(5).stream()
                            .anyMatch(
                                    s ->
                                            TreeSitterHighlighter.COLOR_KEYWORD.equals(s.color())
                                                    && lines.get(5)
                                                            .substring(s.start(), s.end())
                                                            .contains("new"));
            assertThat(newColored2).as("'new' should be COLOR_KEYWORD on line 5").isTrue();
        }
    }

    @Nested
    class IsQuoteChar {

        @Test
        void doubleQuote_isQuote() {
            assertThat(TreeSitterHighlighter.isQuoteChar('"')).isTrue();
        }

        @Test
        void singleQuote_isQuote() {
            assertThat(TreeSitterHighlighter.isQuoteChar('\'')).isTrue();
        }

        @Test
        void backtick_isQuote() {
            assertThat(TreeSitterHighlighter.isQuoteChar('`')).isTrue();
        }

        @Test
        void letter_isNotQuote() {
            assertThat(TreeSitterHighlighter.isQuoteChar('s')).isFalse();
        }

        @Test
        void digit_isNotQuote() {
            assertThat(TreeSitterHighlighter.isQuoteChar('3')).isFalse();
        }
    }

    @Nested
    class IsWordChar {

        @Test
        void letter_isWordChar() {
            assertThat(TreeSitterHighlighter.isWordChar('a')).isTrue();
            assertThat(TreeSitterHighlighter.isWordChar('Z')).isTrue();
        }

        @Test
        void digit_isWordChar() {
            assertThat(TreeSitterHighlighter.isWordChar('9')).isTrue();
        }

        @Test
        void underscore_isWordChar() {
            assertThat(TreeSitterHighlighter.isWordChar('_')).isTrue();
        }

        @Test
        void dot_isNotWordChar() {
            assertThat(TreeSitterHighlighter.isWordChar('.')).isFalse();
        }

        @Test
        void space_isNotWordChar() {
            assertThat(TreeSitterHighlighter.isWordChar(' ')).isFalse();
        }

        @Test
        void hyphen_isNotWordChar() {
            assertThat(TreeSitterHighlighter.isWordChar('-')).isFalse();
        }
    }

    @Nested
    class BuildInQuoteMap {

        @Test
        void noQuotes_allFalse() {
            boolean[] map = TreeSitterHighlighter.buildInQuoteMap("abc def");
            for (boolean b : map) {
                assertThat(b).isFalse();
            }
        }

        @Test
        void simpleString_contentIsTrue_delimsAreFalse() {
            // Source: import "foo";
            // Positions: i=7 is '"' (false), i=8..10 are f,o,o (true), i=11 is '"' (false)
            boolean[] map = TreeSitterHighlighter.buildInQuoteMap("import \"foo\";");
            assertThat(map[7]).isFalse(); // opening quote
            assertThat(map[8]).isTrue(); // f
            assertThat(map[9]).isTrue(); // o
            assertThat(map[10]).isTrue(); // o
            assertThat(map[11]).isFalse(); // closing quote
            assertThat(map[12]).isFalse(); // ;
        }

        @Test
        void keywordInPath_isInsideQuote() {
            String src = "import \"proto/com/gagarin/grpc/api/Foo.proto\";";
            boolean[] map = TreeSitterHighlighter.buildInQuoteMap(src);
            int rpcIdx = src.indexOf("rpc");
            assertThat(rpcIdx).isGreaterThan(0);
            assertThat(map[rpcIdx]).isTrue();
        }

        @Test
        void textAfterClosingQuote_isFalse() {
            // "foo" bar  →  bar should be false
            boolean[] map = TreeSitterHighlighter.buildInQuoteMap("\"foo\" bar");
            assertThat(map[6]).isFalse(); // 'b'
            assertThat(map[7]).isFalse(); // 'a'
            assertThat(map[8]).isFalse(); // 'r'
        }
    }

    @Nested
    class ByteToCharMap {

        @Test
        void pureAscii_identityMapping() {
            int[] map = TreeSitterHighlighter.buildByteToCharMap("abc");
            assertThat(map).containsExactly(0, 1, 2, 3); // [0]=0, [1]=1, [2]=2, sentinel [3]=3
        }

        @Test
        void copyright_twoByteSequence_shiftedCorrectly() {
            // '©' is U+00A9, encoded as 0xC2 0xA9 (2 bytes) but 1 Java char.
            // "a©b" → bytes [97, 0xC2, 0xA9, 98], chars [a=0, ©=1, b=2]
            int[] map = TreeSitterHighlighter.buildByteToCharMap("a©b");
            assertThat(map[0]).isEqualTo(0); // 'a' → char 0
            assertThat(map[1]).isEqualTo(1); // first byte of '©' → char 1
            assertThat(map[2]).isEqualTo(1); // continuation byte of '©' → char 1
            assertThat(map[3]).isEqualTo(2); // 'b' → char 2
            assertThat(map[4]).isEqualTo(3); // sentinel = source.length()
        }

        @Test
        void nonAsciiComment_keywordsStillAligned() {
            // When a hunk starts with a comment containing '©', the byte offset of the keyword
            // on the next line would be one byte ahead of its char offset — this used to cause
            // the keyword capture to be silently dropped (start >= sourceLen check).
            if (!TreeSitterHighlighter.isLanguageLoaded("java")) {
                return;
            }
            var lines = List.of("// Copyright © 2024 Foo Corp.", "private static int x = 0;");
            var spans = TreeSitterHighlighter.colorHunk(lines, "java");
            assertThat(spans).hasSize(2);
            boolean privateColored =
                    spans.get(1).stream()
                            .anyMatch(
                                    s ->
                                            TreeSitterHighlighter.COLOR_KEYWORD.equals(s.color())
                                                    && lines.get(1)
                                                            .substring(s.start(), s.end())
                                                            .contains("private"));
            assertThat(privateColored)
                    .as("'private' should be COLOR_KEYWORD even after non-ASCII '©' on line 0")
                    .isTrue();
        }
    }

    @Nested
    class ProtoGrammar {

        @Test
        void protoGrammarLoads() {
            // Verifies the proto grammar and query compile successfully.
            // If this fails, check the IDE log for:
            //   "TreeSitterHighlighter: failed to load grammar for .proto"
            assertThat(TreeSitterHighlighter.isLanguageLoaded("proto"))
                    .as(
                            "proto grammar failed to load — check test output for"
                                    + " UnsatisfiedLinkError or TSQuery exception")
                    .isTrue();
        }

        @Test
        void protoKeywords_areColored() {
            // Only meaningful if protoGrammarLoads passes.
            if (!TreeSitterHighlighter.isLanguageLoaded("proto")) {
                return;
            }
            var lines = List.of("syntax = \"proto3\";", "message Foo {", "}");
            var spans = TreeSitterHighlighter.colorHunk(lines, "proto");
            assertThat(spans).hasSize(3);
            // "syntax" on line 0 should be keyword red, not default FG
            boolean syntaxColored =
                    spans.get(0).stream()
                            .anyMatch(s -> TreeSitterHighlighter.COLOR_KEYWORD.equals(s.color()));
            assertThat(syntaxColored)
                    .as(
                            "\"syntax\" keyword should be colored "
                                    + TreeSitterHighlighter.COLOR_KEYWORD)
                    .isTrue();
        }

        @Test
        void fullProtoFile_serviceRpcKeywordsStillColored() {
            if (!TreeSitterHighlighter.isLanguageLoaded("proto")) return;
            var lines =
                    List.of(
                            "syntax = \"proto3\";",
                            "import \"proto/com/linkedin/gagarin/grpc/api/salesai/AgentMetric.proto\";",
                            "message FooRequest { string name = 1; }",
                            "service FooService {",
                            "  rpc GetFoo (FooRequest) returns (FooResponse);",
                            "}");
            var spans = TreeSitterHighlighter.colorHunk(lines, "proto");
            String rpcLine = lines.get(4);
            boolean rpcColored =
                    spans.get(4).stream()
                            .anyMatch(
                                    s ->
                                            TreeSitterHighlighter.COLOR_KEYWORD.equals(s.color())
                                                    && rpcLine.substring(s.start(), s.end())
                                                            .contains("rpc"));
            assertThat(rpcColored).as("'rpc' in service def should be COLOR_KEYWORD").isTrue();
        }

        @Test
        void fragmentHunkWithImport_messageKeywordsStillColored() {
            // A diff hunk without a "syntax" header whose import path triggers grammar error
            // recovery — keywords like "message" outside the string must still get highlighting.
            if (!TreeSitterHighlighter.isLanguageLoaded("proto")) return;
            var lines =
                    List.of(
                            "import \"proto/com/linkedin/gagarin/grpc/api/AgentMetric.proto\";",
                            "message FooRequest {",
                            "  string name = 1;",
                            "}");
            var spans = TreeSitterHighlighter.colorHunk(lines, "proto");
            String msgLine = lines.get(1);
            boolean msgColored =
                    spans.get(1).stream()
                            .anyMatch(
                                    s ->
                                            TreeSitterHighlighter.COLOR_KEYWORD.equals(s.color())
                                                    && msgLine.substring(s.start(), s.end())
                                                            .contains("message"));
            assertThat(msgColored)
                    .as("'message' keyword should be COLOR_KEYWORD even in a fragment hunk")
                    .isTrue();
        }

        @Test
        void stringFieldType_isNotColoredAsString() {
            // Regression: the proto grammar emits (string) nodes for both "..." literals and the
            // bare 'string' scalar-type keyword. The keyword must be keyword-red, not string-blue.
            if (!TreeSitterHighlighter.isLanguageLoaded("proto")) return;
            var lines = List.of("  optional string nameSubstring = 3;");
            var spans = TreeSitterHighlighter.colorHunk(lines, "proto");
            String line = lines.get(0);
            boolean coloredAsString =
                    spans.get(0).stream()
                            .anyMatch(
                                    s ->
                                            TreeSitterHighlighter.COLOR_STRING.equals(s.color())
                                                    && line.substring(s.start(), s.end())
                                                            .equals("string"));
            assertThat(coloredAsString)
                    .as("'string' field-type keyword must not be COLOR_STRING")
                    .isFalse();
        }

        @Test
        void rpcInPackageTypePath_isNotColoredAsKeyword() {
            // Regression: tree-sitter error recovery matches "rpc" inside "grpc" in an unquoted
            // package-style type reference and emits a spurious keyword capture.
            if (!TreeSitterHighlighter.isLanguageLoaded("proto")) return;
            var lines =
                    List.of(
                            "  repeated proto.com.linkedin.gagarin.grpc.api.crm.DecoratedCrmAccount"
                                    + " values = 3;");
            var spans = TreeSitterHighlighter.colorHunk(lines, "proto");
            String line = lines.get(0);
            boolean rpcAsKeyword =
                    spans.get(0).stream()
                            .anyMatch(
                                    s ->
                                            TreeSitterHighlighter.COLOR_KEYWORD.equals(s.color())
                                                    && line.substring(s.start(), s.end())
                                                            .equals("rpc"));
            assertThat(rpcAsKeyword)
                    .as("'rpc' substring inside 'grpc' package path must not be COLOR_KEYWORD")
                    .isFalse();
        }

        @Test
        void importPath_rpcInPathIsNotHighlightedAsKeyword() {
            // Regression: the proto grammar error-recovers import paths that contain proto keywords
            // (e.g. "rpc" in /grpc/) and emits spurious keyword captures inside the string.
            if (!TreeSitterHighlighter.isLanguageLoaded("proto")) {
                return;
            }
            String importLine =
                    "import \"proto/com/linkedin/gagarin/grpc/api/salesai/AgentMetric.proto\";";
            var spans = TreeSitterHighlighter.colorHunk(List.of(importLine), "proto");
            assertThat(spans).hasSize(1);

            // Every character inside the quoted string should be COLOR_STRING, not COLOR_KEYWORD.
            int quoteOpen = importLine.indexOf('"');
            int quoteClose = importLine.lastIndexOf('"');
            List<DiffHighlighter.Span> lineSpans = spans.get(0);
            for (DiffHighlighter.Span span : lineSpans) {
                // Spans that are entirely within the quoted range must not be keyword-colored.
                if (span.start() > quoteOpen && span.end() <= quoteClose) {
                    assertThat(span.color())
                            .as(
                                    "text inside import string ['%s'] should not be keyword color"
                                            + " (\"rpc\" in /grpc/ was being mis-colored)",
                                    importLine.substring(span.start(), span.end()))
                            .isNotEqualTo(TreeSitterHighlighter.COLOR_KEYWORD);
                }
            }
        }
    }
}
