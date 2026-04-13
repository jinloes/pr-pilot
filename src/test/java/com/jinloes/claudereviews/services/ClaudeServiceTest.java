package com.jinloes.claudereviews.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ClaudeServiceTest {

    @Nested
    class ToolUseStatus {

        @Test
        void simpleToolName_formatsWithEmptyArgs() {
            assertThat(ClaudeService.toolUseStatus("my_tool", Map.of())).isEqualTo("my_tool()");
        }

        @Test
        void mcpPrefixStripped_andDoubleUnderscoreReplacedWithSlash() {
            assertThat(ClaudeService.toolUseStatus("mcp__github__get_file", Map.of()))
                    .isEqualTo("github/get_file()");
        }

        @Test
        void noMcpPrefix_doubleUnderscoreStillReplaced() {
            assertThat(ClaudeService.toolUseStatus("ns__tool", Map.of())).isEqualTo("ns/tool()");
        }

        @Test
        void primitiveArgs_includedInOutput() {
            Map<String, Object> input = Map.of("owner", "alice", "repo", "myrepo");
            String result = ClaudeService.toolUseStatus("mcp__github__search", input);
            assertThat(result).contains("owner=alice");
            assertThat(result).contains("repo=myrepo");
        }

        @Test
        void multipleArgs_joinedWithCommaSpace() {
            Map<String, Object> input = Map.of("a", "1", "b", "2");
            String result = ClaudeService.toolUseStatus("tool", input);
            // Both entries present, separated by ", "
            assertThat(result).matches("tool\\(.*=.*,\\s.*=.*\\)");
        }

        @Test
        void nonPrimitiveArg_excluded() {
            Map<String, Object> input = new HashMap<>();
            input.put("nested", Map.of());
            input.put("list", List.of());
            input.put("scalar", "val");
            String result = ClaudeService.toolUseStatus("tool", input);
            assertThat(result).isEqualTo("tool(scalar=val)");
        }

        @Test
        void pathContainsClaudeDir_returnsNull() {
            assertThat(
                            ClaudeService.toolUseStatus(
                                    "tool", Map.of("path", "/home/user/.claude/tmp/abc")))
                    .isNull();
        }

        @Test
        void filePathContainsClaudeDir_returnsNull() {
            assertThat(
                            ClaudeService.toolUseStatus(
                                    "tool", Map.of("file_path", "/home/user/.claude/settings")))
                    .isNull();
        }

        @Test
        void filenameContainsClaudeDir_returnsNull() {
            assertThat(
                            ClaudeService.toolUseStatus(
                                    "tool", Map.of("filename", "C:\\Users\\user\\.claude\\tmp")))
                    .isNull();
        }

        @Test
        void pathOutsideClaudeDir_notSuppressed() {
            assertThat(
                            ClaudeService.toolUseStatus(
                                    "tool", Map.of("path", "/home/user/projects/src/Foo.java")))
                    .isNotNull();
        }

        @Test
        void nullPathValue_notSuppressed() {
            Map<String, Object> input = new HashMap<>();
            input.put("path", null);
            assertThat(ClaudeService.toolUseStatus("tool", input)).isNotNull();
        }
    }
}
