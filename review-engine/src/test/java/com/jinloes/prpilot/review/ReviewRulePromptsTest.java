package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.PullRequest;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for {@link ReviewRulePrompts}. */
class ReviewRulePromptsTest {
    @Nested
    class RulePrompts {
        private static final String DIFF =
                """
                diff --git a/src/Api.java b/src/Api.java
                --- a/src/Api.java
                +++ b/src/Api.java
                @@ -1,2 +1,3 @@
                -old();
                -older();
                +fresh();
                +fresher();
                +freshest();
                diff --git a/src/Gone.java b/src/Gone.java
                --- a/src/Gone.java
                +++ b/src/Gone.java
                @@ -1 +1 @@
                ---counter;
                +++counter;
                """;

        private PRReviewRequest request(String body) {
            PullRequest pr =
                    new PullRequest(
                            "Retry payments",
                            "https://example.test/pr/1",
                            "acme",
                            "repo",
                            1,
                            body,
                            "author",
                            "",
                            false);
            return PRReviewRequest.builder(pr, DIFF).build();
        }

        @Test
        void selectionPromptListsChangedFilesAndRulesWithoutTheDiff() {
            LocalReviewRules.Rule rule =
                    new LocalReviewRules.Rule(
                            "gated",
                            "gated.yaml",
                            "Checks\n  retries",
                            "Touches retry code",
                            "Secret rule body.");

            String prompt =
                    ReviewRulePrompts.buildRuleSelectionPrompt(
                            request("Adds a retry loop."),
                            InspectionManifest.fromDiff(DIFF),
                            List.of(rule));

            assertThat(prompt)
                    .startsWith("You are deciding which review rules apply")
                    .contains(
                            "Retry payments",
                            "<pr_description>\nAdds a retry loop.\n</pr_description>",
                            "<changed_files>",
                            "- src/Api.java (+3 -2)",
                            "<review_rules>",
                            "- name: gated\n  description: Checks retries\n"
                                    + "  trigger: Touches retry code\n")
                    .doesNotContain("Secret rule body.", "<pr_diff>", "fresh();");
        }

        @Test
        void selectionPromptOmitsABlankDescription() {
            String prompt =
                    ReviewRulePrompts.buildRuleSelectionPrompt(
                            request(" "), InspectionManifest.fromDiff(DIFF), List.of());

            assertThat(prompt).doesNotContain("<pr_description>\n");
        }

        @Test
        void removedLinesSkipsFileHeadersButCountsDoubleDashLines() {
            assertThat(
                            ReviewRulePrompts.removedLines(
                                    "--- a/x\n+++ b/x\n@@ -1,2 +1 @@\n-one\n-two\n+three"))
                    .isEqualTo(2);
            assertThat(ReviewRulePrompts.removedLines("--- a/x\n+++ b/x\n@@ -1 +1 @@\n---counter;"))
                    .isEqualTo(1);
            assertThat(ReviewRulePrompts.removedLines(null)).isZero();
        }

        @Test
        void rulePromptAppliesOneRuleToTheAnnotatedDiff() {
            LocalReviewRules.Rule rule =
                    new LocalReviewRules.Rule(
                            "team.md", "team.md", null, null, "Prefer Optional over null.");

            String prompt = ReviewRulePrompts.buildRuleReviewPrompt(request(""), rule);

            assertThat(prompt)
                    .startsWith("You are applying exactly one review rule")
                    .contains(
                            "<review_rule>",
                            "name: team.md\n\nPrefer Optional over null.",
                            "Respond ONLY with a JSON object",
                            "<pr_metadata>",
                            "<pr_diff>",
                            "fresh();");
            assertThat(prompt.indexOf("<review_rule>"))
                    .isLessThan(prompt.indexOf("Respond ONLY with a JSON object"));
        }
    }
}
