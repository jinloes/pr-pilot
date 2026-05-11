package com.jinloes.prpilot.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.PullRequest;
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
        void alwaysContainsPersonaAndDiff() {
            String prompt =
                    ClaudeService.buildPrompt(new PRReviewRequest(pr(""), "diff content", "", ""));
            assertThat(prompt).contains("senior security engineer");
            assertThat(prompt).contains("diff content");
        }

        @Test
        void diffWrappedInXmlTags() {
            String prompt =
                    ClaudeService.buildPrompt(new PRReviewRequest(pr(""), "diff content", "", ""));
            assertThat(prompt).contains("<diff>\ndiff content\n</diff>");
        }

        @Test
        void blankProjectConventions_sectionAbsent() {
            String prompt = ClaudeService.buildPrompt(new PRReviewRequest(pr(""), "diff", "", ""));
            // When blank, <project_conventions> must NOT appear as a block wrapper (with newline);
            // the instruction text references these tags inline (without a trailing newline).
            assertThat(prompt).doesNotContain("<project_conventions>\n");
        }

        @Test
        void nonBlankProjectConventions_wrappedInXmlTagsBeforeDiff() {
            String prompt =
                    ClaudeService.buildPrompt(
                            new PRReviewRequest(pr(""), "diff", "", "no magic numbers"));
            assertThat(prompt)
                    .contains("<project_conventions>\nno magic numbers\n</project_conventions>");
            // Use the content-carrying form to locate the injected block, not the inline mention
            assertThat(prompt.indexOf("<project_conventions>\nno magic numbers"))
                    .isLessThan(prompt.indexOf("<diff>\ndiff\n</diff>"));
        }

        @Test
        void blankKnownPatterns_sectionAbsent() {
            String prompt = ClaudeService.buildPrompt(new PRReviewRequest(pr(""), "diff", "", ""));
            // When blank, <known_patterns> must NOT appear as a block wrapper (with newline)
            assertThat(prompt).doesNotContain("<known_patterns>\n");
        }

        @Test
        void nonBlankKnownPatterns_wrappedInXmlTags() {
            String prompt =
                    ClaudeService.buildPrompt(
                            new PRReviewRequest(pr(""), "diff", "use Optional", ""));
            assertThat(prompt).contains("<known_patterns>\n");
            assertThat(prompt).contains("</known_patterns>");
            assertThat(prompt).contains("use Optional");
        }

        @Test
        void conventionsSectionAppearsBeforeKnownPatterns() {
            String prompt =
                    ClaudeService.buildPrompt(
                            new PRReviewRequest(pr(""), "diff", "patterns", "conventions"));
            // Use the content-carrying form to locate the injected blocks
            assertThat(prompt.indexOf("<project_conventions>\nconventions"))
                    .isLessThan(prompt.indexOf("<known_patterns>\n"));
        }

        @Test
        void blankPrBody_descriptionSectionAbsent() {
            String prompt = ClaudeService.buildPrompt(new PRReviewRequest(pr(""), "diff", "", ""));
            // When blank, <pr_description> must NOT appear as a block wrapper (with newline)
            assertThat(prompt).doesNotContain("<pr_description>\n");
        }

        @Test
        void nonBlankPrBody_wrappedInXmlTags() {
            String prompt =
                    ClaudeService.buildPrompt(
                            new PRReviewRequest(pr("fixes the bug"), "diff", "", ""));
            assertThat(prompt).contains("<pr_description>\nfixes the bug\n</pr_description>");
        }

        @Test
        void noPriorReview_sectionAbsent() {
            String prompt = ClaudeService.buildPrompt(new PRReviewRequest(pr(""), "diff", "", ""));
            assertThat(prompt).doesNotContain("<prior_review>\n");
        }

        @Test
        void blankPriorReview_sectionAbsent() {
            String prompt =
                    ClaudeService.buildPrompt(new PRReviewRequest(pr(""), "diff", "", "", "   "));
            assertThat(prompt).doesNotContain("<prior_review>\n");
        }

        @Test
        void nonBlankPriorReview_wrappedInXmlTags() {
            String prompt =
                    ClaudeService.buildPrompt(
                            new PRReviewRequest(pr(""), "diff", "", "", "Verdict: APPROVE"));
            assertThat(prompt).contains("<prior_review>\n");
            assertThat(prompt).contains("</prior_review>");
            assertThat(prompt).contains("Verdict: APPROVE");
        }

        @Test
        void priorReviewAppearsAfterKnownPatternsBeforePrDescription() {
            String prompt =
                    ClaudeService.buildPrompt(
                            new PRReviewRequest(
                                    pr("body"), "diff", "patterns", "conventions", "prior"));
            // Use content-carrying forms to avoid matching inline tag mentions in
            // REVIEW_INSTRUCTIONS
            int knownEnd = prompt.indexOf("</known_patterns>");
            int priorStart = prompt.indexOf("<prior_review>\n");
            int descStart = prompt.indexOf("<pr_description>\nbody");
            assertThat(knownEnd).isLessThan(priorStart);
            assertThat(priorStart).isLessThan(descStart);
        }

        @Test
        void priorReviewStripped() {
            String prompt =
                    ClaudeService.buildPrompt(
                            new PRReviewRequest(pr(""), "diff", "", "", "  spaced  "));
            assertThat(prompt).contains("<prior_review>\n");
            // The content should be stripped of surrounding whitespace
            assertThat(prompt).contains("spaced");
            assertThat(prompt).doesNotContain("  spaced  ");
        }

        @Test
        void noExistingReviews_sectionAbsent() {
            String prompt = ClaudeService.buildPrompt(new PRReviewRequest(pr(""), "diff", "", ""));
            assertThat(prompt).doesNotContain("<existing_reviews>\n");
        }

        @Test
        void blankExistingReviews_sectionAbsent() {
            String prompt =
                    ClaudeService.buildPrompt(
                            new PRReviewRequest(pr(""), "diff", "", "", null, "   "));
            assertThat(prompt).doesNotContain("<existing_reviews>\n");
        }

        @Test
        void nonBlankExistingReviews_wrappedInXmlTags() {
            String prompt =
                    ClaudeService.buildPrompt(
                            new PRReviewRequest(pr(""), "diff", "", "", null, "Review by @alice"));
            assertThat(prompt).contains("<existing_reviews>\n");
            assertThat(prompt).contains("</existing_reviews>");
            assertThat(prompt).contains("Review by @alice");
        }

        @Test
        void existingReviewsAppearsAfterKnownPatternsBeforePriorReview() {
            String prompt =
                    ClaudeService.buildPrompt(
                            new PRReviewRequest(
                                    pr("body"),
                                    "diff",
                                    "patterns",
                                    "conventions",
                                    "prior",
                                    "existing"));
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
            // Should be a no-op when idle (activeProcess is null)
            service.cancelCurrentRequest();
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
