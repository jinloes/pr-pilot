package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jinloes.prpilot.model.ReviewResult;
import com.jinloes.prpilot.review.stream.ContentBlock;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ClaudeStreamParserTest {

    @Nested
    class FindErrorInfo {

        @Test
        void fileDoesNotExistReturnsNulls() {
            ClaudeStreamParser.ErrorInfo info =
                    ClaudeStreamParser.findErrorInfo(new File("/nonexistent/path/file.ndjson"));
            assertThat(info.subtype()).isNull();
            assertThat(info.sessionId()).isNull();
        }

        @Test
        void emptyFileReturnsNulls() throws Exception {
            File file = Files.createTempFile("test", ".ndjson").toFile();
            try {
                ClaudeStreamParser.ErrorInfo info = ClaudeStreamParser.findErrorInfo(file);
                assertThat(info.subtype()).isNull();
                assertThat(info.sessionId()).isNull();
            } finally {
                file.delete();
            }
        }

        @Test
        void fileWithErrorMaxTurnsEventAndSessionIdReturnsBoth() throws Exception {
            File file = Files.createTempFile("test", ".ndjson").toFile();
            try {
                Files.writeString(
                        file.toPath(),
                        "{\"type\":\"result\",\"subtype\":\"error_max_turns\",\"is_error\":true,\"session_id\":\"sess-abc\"}\n");
                ClaudeStreamParser.ErrorInfo info = ClaudeStreamParser.findErrorInfo(file);
                assertThat(info.subtype()).isEqualTo("error_max_turns");
                assertThat(info.sessionId()).isEqualTo("sess-abc");
            } finally {
                file.delete();
            }
        }

        @Test
        void fileWithNonErrorResultReturnsNulls() throws Exception {
            File file = Files.createTempFile("test", ".ndjson").toFile();
            try {
                Files.writeString(
                        file.toPath(),
                        "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false}\n");
                ClaudeStreamParser.ErrorInfo info = ClaudeStreamParser.findErrorInfo(file);
                assertThat(info.subtype()).isNull();
                assertThat(info.sessionId()).isNull();
            } finally {
                file.delete();
            }
        }

        @Test
        void fileWithCorruptLineFollowedByValidErrorEventSkipsCorruptFindsError() throws Exception {
            File file = Files.createTempFile("test", ".ndjson").toFile();
            try {
                Files.writeString(
                        file.toPath(),
                        "NOT_JSON\n{\"type\":\"result\",\"subtype\":\"error_max_turns\",\"is_error\":true,\"session_id\":\"s1\"}\n");
                ClaudeStreamParser.ErrorInfo info = ClaudeStreamParser.findErrorInfo(file);
                assertThat(info.subtype()).isEqualTo("error_max_turns");
            } finally {
                file.delete();
            }
        }
    }

    @Nested
    class ParseStdoutFileToResult {

        @Test
        void resultEventWithJsonParsedIntoReviewResult() throws Exception {
            File file = Files.createTempFile("test", ".ndjson").toFile();
            try {
                String reviewJson =
                        "{\"summary\":\"overview\",\"verdict\":\"APPROVE\",\"lineComments\":[]}";
                String escaped = reviewJson.replace("\"", "\\\"");
                Files.writeString(
                        file.toPath(),
                        "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\""
                                + escaped
                                + "\"}\n");
                List<String> statuses = new ArrayList<>();
                ReviewResult result =
                        ClaudeStreamParser.parseStdoutFileToResult(file, "", statuses::add, null);
                assertThat(result.getVerdict()).isEqualTo("APPROVE");
            } finally {
                file.delete();
            }
        }

        @Test
        void noResultEventThrowsIOExceptionWithDiagnosticDetail() throws Exception {
            File file = Files.createTempFile("test", ".ndjson").toFile();
            try {
                Files.writeString(file.toPath(), "{\"type\":\"assistant\",\"message\":null}\n");
                assertThatThrownBy(
                                () ->
                                        ClaudeStreamParser.parseStdoutFileToResult(
                                                file, "", ignored -> {}, null))
                        .isInstanceOf(IOException.class);
            } finally {
                file.delete();
            }
        }

        @Test
        void textBlockFallbackTextAccumulatedInTextBufferUsedWhenResultBufferEmpty()
                throws Exception {
            File file = Files.createTempFile("test", ".ndjson").toFile();
            try {
                String reviewJson =
                        "{\"summary\":\"fallback\",\"verdict\":\"COMMENT\",\"lineComments\":[]}";
                String escaped = reviewJson.replace("\"", "\\\"");
                Files.writeString(
                        file.toPath(),
                        "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\""
                                + escaped
                                + "\"}]}}\n");
                ReviewResult result =
                        ClaudeStreamParser.parseStdoutFileToResult(file, "", ignored -> {}, null);
                assertThat(result.getVerdict()).isEqualTo("COMMENT");
            } finally {
                file.delete();
            }
        }
    }

    @Nested
    class HandleContentBlock {

        private static ContentBlock textBlock(String text) {
            ContentBlock b = new ContentBlock();
            b.setType("text");
            b.setText(text);
            return b;
        }

        private static ContentBlock thinkingBlock(String thinking) {
            ContentBlock b = new ContentBlock();
            b.setType("thinking");
            b.setThinking(thinking);
            return b;
        }

        private static ContentBlock toolUseBlock(String name) {
            ContentBlock b = new ContentBlock();
            b.setType("tool_use");
            b.setName(name);
            b.setInput(Map.of());
            return b;
        }

        @Test
        void textBlockWithOnChunkCallsOnChunkNotOnStatus() {
            List<String> statuses = new ArrayList<>();
            List<String[]> chunks = new ArrayList<>();
            ClaudeStreamParser.handleContentBlock(
                    textBlock("hello world"),
                    statuses::add,
                    (k, v) -> chunks.add(new String[] {k, v}));
            assertThat(statuses).isEmpty();
            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0)).containsExactly("text", "hello world");
        }

        @Test
        void textBlockWithoutOnChunkCallsOnStatusWithGenerating() {
            List<String> statuses = new ArrayList<>();
            ClaudeStreamParser.handleContentBlock(textBlock("hello"), statuses::add, null);
            assertThat(statuses).containsExactly("Generating review…");
        }

        @Test
        void thinkingBlockWithOnChunkCallsOnChunkWithThinking() {
            List<String[]> chunks = new ArrayList<>();
            ClaudeStreamParser.handleContentBlock(
                    thinkingBlock("deep thought"),
                    ignored -> {},
                    (k, v) -> chunks.add(new String[] {k, v}));
            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0)).containsExactly("thinking", "deep thought");
        }

        @Test
        void toolUseBlockAlwaysCallsOnStatus() {
            List<String> statuses = new ArrayList<>();
            ClaudeStreamParser.handleContentBlock(
                    toolUseBlock("my_tool"), statuses::add, (k, v) -> {});
            assertThat(statuses).containsExactly("my_tool()");
        }

        @Test
        void unknownBlockTypeNoExceptionAndNoCallback() {
            List<String> statuses = new ArrayList<>();
            ContentBlock block = new ContentBlock();
            block.setType("unknown_future_type");
            ClaudeStreamParser.handleContentBlock(block, statuses::add, null);
            assertThat(statuses).isEmpty();
        }
    }

    @Nested
    class ToolUseStatus {

        @Test
        void simpleToolNameFormatsWithEmptyArgs() {
            assertThat(ClaudeStreamParser.toolUseStatus("my_tool", Map.of()))
                    .isEqualTo("my_tool()");
        }

        @Test
        void mcpPrefixStrippedAndDoubleUnderscoreReplacedWithSlash() {
            assertThat(ClaudeStreamParser.toolUseStatus("mcp__github__get_file", Map.of()))
                    .isEqualTo("github/get_file()");
        }

        @Test
        void primitiveStringArgsIncludedInOutput() {
            String result =
                    ClaudeStreamParser.toolUseStatus(
                            "mcp__github__search", Map.of("owner", "alice", "repo", "myrepo"));
            assertThat(result).contains("owner=alice").contains("repo=myrepo");
        }

        @Test
        void nonPrimitiveArgsExcluded() {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("nested", Map.of());
            input.put("list", List.of());
            input.put("scalar", "val");
            assertThat(ClaudeStreamParser.toolUseStatus("tool", input))
                    .isEqualTo("tool(scalar=val)");
        }

        @Test
        void pathContainingClaudeDirReturnsNull() {
            assertThat(
                            ClaudeStreamParser.toolUseStatus(
                                    "tool", Map.of("path", "/home/user/.claude/tmp/abc")))
                    .isNull();
        }

        @Test
        void filePathContainingClaudeDirReturnsNull() {
            assertThat(
                            ClaudeStreamParser.toolUseStatus(
                                    "tool", Map.of("file_path", "/home/user/.claude/settings")))
                    .isNull();
        }

        @Test
        void pathOutsideClaudeDirNotSuppressed() {
            assertThat(
                            ClaudeStreamParser.toolUseStatus(
                                    "tool", Map.of("path", "/home/user/projects/src/Foo.java")))
                    .isNotNull();
        }

        @Test
        void numberArgIncludedAsScalar() {
            assertThat(ClaudeStreamParser.toolUseStatus("tool", Map.of("count", 42)))
                    .isEqualTo("tool(count=42)");
        }

        @Test
        void booleanArgIncludedAsScalar() {
            assertThat(ClaudeStreamParser.toolUseStatus("tool", Map.of("flag", true)))
                    .isEqualTo("tool(flag=true)");
        }
    }
}
