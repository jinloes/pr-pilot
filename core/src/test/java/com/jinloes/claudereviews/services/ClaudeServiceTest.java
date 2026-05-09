package com.jinloes.claudereviews.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.claudereviews.model.PRReviewRequest;
import com.jinloes.claudereviews.model.PullRequest;
import com.jinloes.claudereviews.services.stream.ContentBlock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ClaudeServiceTest {

    private static PullRequest pr(String body) {
        return new PullRequest("My PR", "", "owner", "repo", 42, body, "author", "2024-01-01");
    }

    @Nested
    class BuildPrompt {

        @Test
        void alwaysContainsPersonaAndFetchInstruction() {
            PullRequest prWithMeta =
                    new PullRequest(
                            "Fix the bug", "", "myorg", "myrepo", 99, "", "alice", "2024-01-01");
            String prompt = ClaudeService.buildPrompt(new PRReviewRequest(prWithMeta, "", ""));
            assertThat(prompt).contains("experienced engineer");
            assertThat(prompt).contains("gh pr diff 99 --repo myorg/myrepo");
        }

        @Test
        void fetchDiffWrappedInFetchDiffTag() {
            PullRequest prWithMeta =
                    new PullRequest(
                            "Fix the bug", "", "myorg", "myrepo", 99, "", "alice", "2024-01-01");
            String prompt = ClaudeService.buildPrompt(new PRReviewRequest(prWithMeta, "", ""));
            assertThat(prompt).contains("<fetch_diff>\n");
            assertThat(prompt).contains("gh pr diff 99 --repo myorg/myrepo");
            assertThat(prompt).contains("</fetch_diff>");
            assertThat(prompt).doesNotContain("<diff>");
        }

        @Test
        void prMetadataWrappedInXmlTags() {
            PullRequest prWithMeta =
                    new PullRequest(
                            "Fix the bug", "", "myorg", "myrepo", 99, "", "alice", "2024-01-01");
            String prompt = ClaudeService.buildPrompt(new PRReviewRequest(prWithMeta, "diff", ""));
            assertThat(prompt).contains("<pr_metadata>\n");
            assertThat(prompt).contains("</pr_metadata>");
            assertThat(prompt).contains("number: 99");
            assertThat(prompt).contains("repo: myorg/myrepo");
            assertThat(prompt).contains("title: Fix the bug");
        }

        @Test
        void prMetadataAppearsBeforeFetchDiff() {
            PullRequest prWithMeta =
                    new PullRequest("My PR", "", "org", "repo", 1, "", "alice", "2024-01-01");
            String prompt = ClaudeService.buildPrompt(new PRReviewRequest(prWithMeta, "", ""));
            assertThat(prompt.indexOf("<pr_metadata>\nnumber:"))
                    .isLessThan(prompt.indexOf("<fetch_diff>\nRun:"));
        }

        @Test
        void blankKnownPatterns_sectionAbsent() {
            String prompt = ClaudeService.buildPrompt(new PRReviewRequest(pr(""), "diff", ""));
            assertThat(prompt).doesNotContain("<known_patterns>\n");
        }

        @Test
        void nonBlankKnownPatterns_wrappedInXmlTags() {
            String prompt =
                    ClaudeService.buildPrompt(new PRReviewRequest(pr(""), "diff", "use Optional"));
            assertThat(prompt).contains("<known_patterns>\n");
            assertThat(prompt).contains("</known_patterns>");
            assertThat(prompt).contains("use Optional");
        }

        @Test
        void blankPrBody_descriptionSectionAbsent() {
            String prompt = ClaudeService.buildPrompt(new PRReviewRequest(pr(""), "diff", ""));
            assertThat(prompt).doesNotContain("<pr_description>\n");
        }

        @Test
        void nonBlankPrBody_wrappedInXmlTags() {
            String prompt =
                    ClaudeService.buildPrompt(new PRReviewRequest(pr("fixes the bug"), "diff", ""));
            assertThat(prompt).contains("<pr_description>\nfixes the bug\n</pr_description>");
        }

        @Test
        void noPriorReview_sectionAbsent() {
            String prompt = ClaudeService.buildPrompt(new PRReviewRequest(pr(""), "diff", ""));
            assertThat(prompt).doesNotContain("<prior_review>\n");
        }

        @Test
        void blankPriorReview_sectionAbsent() {
            String prompt =
                    ClaudeService.buildPrompt(new PRReviewRequest(pr(""), "diff", "", "   "));
            assertThat(prompt).doesNotContain("<prior_review>\n");
        }

        @Test
        void nonBlankPriorReview_wrappedInXmlTags() {
            String prompt =
                    ClaudeService.buildPrompt(
                            new PRReviewRequest(pr(""), "diff", "", "Verdict: APPROVE"));
            assertThat(prompt).contains("<prior_review>\n");
            assertThat(prompt).contains("</prior_review>");
            assertThat(prompt).contains("Verdict: APPROVE");
        }

        @Test
        void priorReviewAppearsAfterKnownPatternsBeforePrDescription() {
            String prompt =
                    ClaudeService.buildPrompt(
                            new PRReviewRequest(pr("body"), "diff", "patterns", "prior"));
            int knownEnd = prompt.indexOf("</known_patterns>");
            int priorStart = prompt.indexOf("<prior_review>\n");
            int descStart = prompt.indexOf("<pr_description>\nbody");
            assertThat(knownEnd).isLessThan(priorStart);
            assertThat(priorStart).isLessThan(descStart);
        }

        @Test
        void blankTypeContext_sectionAbsent() {
            String prompt = ClaudeService.buildPrompt(new PRReviewRequest(pr(""), "diff", ""));
            assertThat(prompt).doesNotContain("<type_context>\n");
        }

        @Test
        void nonBlankTypeContext_wrappedInXmlTags() {
            String prompt =
                    ClaudeService.buildPrompt(
                            new PRReviewRequest(pr(""), "diff", "", null, null, "Foo#bar(): int"));
            assertThat(prompt).contains("<type_context>\nFoo#bar(): int\n</type_context>");
        }

        @Test
        void typeContextAppearsAfterPrDescriptionBeforeFetchDiff() {
            String prompt =
                    ClaudeService.buildPrompt(
                            new PRReviewRequest(
                                    pr("body"), "", "", null, null, "Foo#bar(): int"));
            assertThat(prompt.indexOf("<type_context>\nFoo#bar(): int"))
                    .isGreaterThan(prompt.indexOf("<pr_description>\nbody"));
            assertThat(prompt.indexOf("<type_context>\nFoo#bar(): int"))
                    .isLessThan(prompt.indexOf("<fetch_diff>\nRun:"));
        }

        @Test
        void misattributionGuardPresent() {
            String prompt = ClaudeService.buildPrompt(new PRReviewRequest(pr(""), "", ""));
            assertThat(prompt).contains("misattributed comment is worse than no comment");
            assertThat(prompt).contains("trace");
        }

        @Test
        void unverifiableIssueGuardPresent() {
            String prompt = ClaudeService.buildPrompt(new PRReviewRequest(pr(""), "", ""));
            assertThat(prompt).contains("library internals");
            assertThat(prompt).contains("When in doubt, leave it out");
        }

        @Test
        void issueTypeRequiresConfirmationFromDiff() {
            String prompt = ClaudeService.buildPrompt(new PRReviewRequest(pr(""), "", ""));
            assertThat(prompt).contains("Do NOT use \"issue\" for problems that require runtime");
        }

        @Test
        void priorReviewInUntrustedInputList() {
            String prompt = ClaudeService.buildPrompt(new PRReviewRequest(pr(""), "", ""));
            assertThat(prompt).contains("<prior_review>");
            int injectionGuardIndex = prompt.indexOf("untrusted input");
            int priorReviewTagIndex = prompt.indexOf("<prior_review>", 0);
            assertThat(priorReviewTagIndex).isLessThan(injectionGuardIndex);
        }

        @Test
        void lineCommentsCapPresent() {
            String prompt = ClaudeService.buildPrompt(new PRReviewRequest(pr(""), "", ""));
            assertThat(prompt).contains("at most 12 comments");
        }

        @Test
        void testCoverageRuleIsScoped() {
            String prompt = ClaudeService.buildPrompt(new PRReviewRequest(pr(""), "", ""));
            assertThat(prompt).contains("flag as \"issue\" only if");
            assertThat(prompt).doesNotContain("any non-trivial new or changed branch or");
        }

        @Test
        void lineNumberingRulesAppearBeforeSchema() {
            String prompt = ClaudeService.buildPrompt(new PRReviewRequest(pr(""), "", ""));
            assertThat(prompt.indexOf("Line numbering:")).isLessThan(prompt.indexOf("Schema (emit"));
        }

        @Test
        void priorReviewStripped() {
            String prompt =
                    ClaudeService.buildPrompt(
                            new PRReviewRequest(pr(""), "diff", "", "  spaced  "));
            assertThat(prompt).contains("<prior_review>\n");
            assertThat(prompt).contains("spaced");
            assertThat(prompt).doesNotContain("  spaced  ");
        }

        @Test
        void noExistingReviews_sectionAbsent() {
            String prompt = ClaudeService.buildPrompt(new PRReviewRequest(pr(""), "diff", ""));
            assertThat(prompt).doesNotContain("<existing_reviews>\n");
        }

        @Test
        void blankExistingReviews_sectionAbsent() {
            String prompt =
                    ClaudeService.buildPrompt(new PRReviewRequest(pr(""), "diff", "", null, "   "));
            assertThat(prompt).doesNotContain("<existing_reviews>\n");
        }

        @Test
        void nonBlankExistingReviews_wrappedInXmlTags() {
            String prompt =
                    ClaudeService.buildPrompt(
                            new PRReviewRequest(pr(""), "diff", "", null, "Review by @alice"));
            assertThat(prompt).contains("<existing_reviews>\n");
            assertThat(prompt).contains("</existing_reviews>");
            assertThat(prompt).contains("Review by @alice");
        }

        @Test
        void existingReviewsAppearsAfterKnownPatternsBeforePriorReview() {
            String prompt =
                    ClaudeService.buildPrompt(
                            new PRReviewRequest(
                                    pr("body"), "diff", "patterns", "prior", "existing"));
            int knownEnd = prompt.indexOf("</known_patterns>");
            int existingStart = prompt.indexOf("<existing_reviews>\n");
            int priorStart = prompt.indexOf("<prior_review>\n");
            assertThat(knownEnd).isLessThan(existingStart);
            assertThat(existingStart).isLessThan(priorStart);
        }
    }

    @Nested
    class CancelCurrentRequest {

        @Test
        void noActiveProcess_doesNotThrow() {
            ClaudeService service = new ClaudeService();
            service.cancelCurrentRequest();
        }
    }

    @Nested
    class HandleContentBlock {

        private final ClaudeService service = new ClaudeService();

        @Test
        void textBlock_withOnChunk_callsOnChunkNotOnStatus() {
            List<String> statuses = new ArrayList<>();
            List<String[]> chunks = new ArrayList<>();
            ContentBlock block = textBlock("hello world");

            service.handleContentBlock(
                    block, statuses::add, (k, v) -> chunks.add(new String[] {k, v}));

            assertThat(statuses).isEmpty();
            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0)).containsExactly("text", "hello world");
        }

        @Test
        void textBlock_withTextBuffer_appendsText() {
            StringBuilder textBuffer = new StringBuilder();

            service.handleContentBlock(
                    textBlock("json content"), s -> {}, null, textBuffer);

            assertThat(textBuffer.toString()).isEqualTo("json content");
        }

        @Test
        void textBlock_blankText_doesNotAppendToTextBuffer() {
            StringBuilder textBuffer = new StringBuilder();

            service.handleContentBlock(textBlock("  "), s -> {}, null, textBuffer);

            assertThat(textBuffer.toString()).isEmpty();
        }

        @Test
        void textBlock_withoutOnChunk_callsOnStatusWithGenerating() {
            List<String> statuses = new ArrayList<>();

            service.handleContentBlock(textBlock("hello"), statuses::add, null);

            assertThat(statuses).containsExactly("Generating review…");
        }

        @Test
        void textBlock_blankText_withOnChunk_fallsBackToOnStatus() {
            List<String> statuses = new ArrayList<>();
            List<String[]> chunks = new ArrayList<>();

            service.handleContentBlock(
                    textBlock("   "), statuses::add, (k, v) -> chunks.add(new String[] {k, v}));

            assertThat(chunks).isEmpty();
            assertThat(statuses).containsExactly("Generating review…");
        }

        @Test
        void thinkingBlock_withOnChunk_callsOnChunk() {
            List<String[]> chunks = new ArrayList<>();

            service.handleContentBlock(
                    thinkingBlock("deep thought"),
                    s -> {},
                    (k, v) -> chunks.add(new String[] {k, v}));

            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0)).containsExactly("thinking", "deep thought");
        }

        @Test
        void thinkingBlock_withoutOnChunk_callsNothing() {
            List<String> statuses = new ArrayList<>();

            service.handleContentBlock(thinkingBlock("deep thought"), statuses::add, null);

            assertThat(statuses).isEmpty();
        }

        @Test
        void toolUseBlock_callsOnStatus_regardlessOfOnChunk() {
            List<String> statuses = new ArrayList<>();

            service.handleContentBlock(toolUseBlock("my_tool"), statuses::add, (k, v) -> {});

            assertThat(statuses).containsExactly("my_tool()");
        }

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
    }

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
