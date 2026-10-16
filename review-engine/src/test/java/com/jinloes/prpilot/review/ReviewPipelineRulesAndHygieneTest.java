package com.jinloes.prpilot.review;

import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.comment;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.emptyReviewJson;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.hygiene;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.oneRiskyHunk;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.pass;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.pipeline;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.request;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.result;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.reviewJson;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.ruleTagged;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.withSecondary;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.ReviewResult;
import com.jinloes.prpilot.review.ReviewPipelineTestSupport.FakeProvider;
import com.jinloes.prpilot.review.ReviewPipelineTestSupport.FakeSecondary;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Hygiene-pass and local review-rule tests for {@link ReviewPipelineService}. */
class ReviewPipelineRulesAndHygieneTest {
    @TempDir Path semanticRoot;

    @Nested
    class Review {
        @Test
        void runsTheHygienePassBetweenReviewAndCritiqueAndMergesItsFindings() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult = pass(result(comment("src/A.java", 1, "high", "Bug.")));
            provider.hygieneCompletions.add(
                    reviewJson(comment("src/Api.java", 1, "medium", "Demote this log.")));
            provider.completions.add(emptyReviewJson());
            List<String> statuses = new ArrayList<>();

            pipeline(provider)
                    .review(request(oneRiskyHunk()), false, true, false, statuses::add, null);

            assertThat(provider.callOrder).containsExactly("review", "hygiene", "critique");
            assertThat(provider.hygieneCalls)
                    .singleElement()
                    .satisfies(
                            call -> {
                                assertThat(call.allowReadTools()).isTrue();
                                assertThat(call.allowMcp()).isFalse();
                                assertThat(call.timeoutMillis()).isEqualTo(15L * 60L * 1000L);
                                assertThat(call.prompt())
                                        .contains(ReviewHygienePrompts.HYGIENE_RULES, "<pr_diff>")
                                        .doesNotContain("<draft_review>");
                            });
            assertThat(statuses)
                    .containsSubsequence(
                            "Hygiene pass found 1 finding",
                            "Draft review has 2 findings before validation");
            assertThat(provider.completeCalls.get(0).prompt())
                    .contains("<draft_review>", "Bug.", "Demote this log.");
        }

        @Test
        void keepsAHygieneFindingTheCritiqueDropped() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult = pass(result(comment("src/A.java", 1, "high", "Bug.")));
            provider.hygieneCompletions.add(
                    reviewJson(hygiene("src/Api.java", 1, "medium", "Demote this log.")));
            provider.completions.add(reviewJson(comment("src/A.java", 1, "high", "Bug.")));
            List<String> statuses = new ArrayList<>();

            ReviewResult result =
                    pipeline(provider)
                            .review(
                                    request(oneRiskyHunk()),
                                    false,
                                    true,
                                    false,
                                    statuses::add,
                                    null);

            assertThat(result.getLineComments())
                    .extracting(LineComment::getBody)
                    .containsExactlyInAnyOrder("Bug.", "Demote this log.");
            assertThat(statuses)
                    .containsSubsequence(
                            "Kept 1 finding from the hygiene pass",
                            "Validation kept 2 of 2 findings");
        }

        @Test
        void dropsAnUnanchoredHygieneFindingBeforeTheDraft() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult = pass(result());
            provider.hygieneCompletions.add(
                    reviewJson(hygiene("src/Missing.java", 4, "high", "Demote this log.")));
            provider.completions.add(emptyReviewJson());
            List<String> statuses = new ArrayList<>();

            ReviewResult result =
                    pipeline(provider)
                            .review(
                                    request(oneRiskyHunk()),
                                    false,
                                    true,
                                    false,
                                    statuses::add,
                                    null);

            assertThat(result.getLineComments()).isEmpty();
            assertThat(statuses).contains("Hygiene pass found 0 findings");
        }

        private PRReviewRequest withRules(String diff, String... ruleFiles) throws IOException {
            Path rules = Files.createDirectories(semanticRoot.resolve("rules"));
            for (int index = 0; index < ruleFiles.length; index += 2) {
                Files.writeString(rules.resolve(ruleFiles[index]), ruleFiles[index + 1]);
            }
            return request(diff).toBuilder().rulesDirectory(rules.toString()).build();
        }

        private static String fiveLineDiff() {
            StringBuilder diff =
                    new StringBuilder(
                            "diff --git a/src/Api.java b/src/Api.java\n--- a/src/Api.java\n"
                                    + "+++ b/src/Api.java\n@@ -0,0 +1,5 @@\n");
            for (int line = 1; line <= 5; line++) diff.append("+int v").append(line).append(";\n");
            return diff.toString();
        }

        @Test
        void runsTheRulesAfterTheReviewAndBeforeTheHygienePass() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult = pass(result(comment("src/A.java", 1, "high", "Bug.")));
            provider.ruleSelectionCompletions.add("{\"triggered\":[\"gated\"]}");
            provider.ruleScripts.put(
                    "gated", prompt -> reviewJson(comment("src/Api.java", 1, "high", "Rule bug.")));
            provider.completions.add(emptyReviewJson());
            List<String> statuses = new ArrayList<>();

            pipeline(provider)
                    .review(
                            withRules(
                                    oneRiskyHunk(),
                                    "gated.yaml",
                                    "name: gated\ndescription: d\ntrigger: t\nprompt: Check.\n"),
                            false,
                            true,
                            false,
                            statuses::add,
                            null);

            assertThat(provider.callOrder)
                    .containsExactly("review", "rule-selection", "rule", "hygiene", "critique");
            assertThat(provider.completeCalls.get(0).prompt())
                    .contains("<draft_review>", "Bug.", "Rule bug.", "(rule: gated)");
            assertThat(statuses)
                    .containsSubsequence(
                            "Rules: 1 loaded, 1 selected (gated)",
                            "Rule gated found 1 finding",
                            "Hygiene pass found 0 findings");
        }

        @Test
        void keepsARuleFindingTheCritiqueDropped() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult = pass(result(comment("src/A.java", 1, "high", "Bug.")));
            provider.ruleScripts.put(
                    "team.md",
                    prompt -> reviewJson(comment("src/Api.java", 1, "high", "Rule bug.")));
            provider.completions.add(reviewJson(comment("src/A.java", 1, "high", "Bug.")));
            List<String> statuses = new ArrayList<>();

            ReviewResult result =
                    pipeline(provider)
                            .review(
                                    withRules(oneRiskyHunk(), "team.md", "Prefer Optional."),
                                    false,
                                    true,
                                    false,
                                    statuses::add,
                                    null);

            assertThat(result.getLineComments())
                    .extracting(LineComment::getBody)
                    .containsExactlyInAnyOrder("Bug.", "Rule bug.");
            assertThat(statuses).contains("Kept 1 finding from review rules");
        }

        @Test
        void doesNotRestoreARuleFindingTheCritiqueKept() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult = pass(result());
            provider.ruleScripts.put(
                    "team.md",
                    prompt -> reviewJson(comment("src/Api.java", 1, "high", "Rule bug.")));
            provider.completions.add(
                    reviewJson(comment("src/Api.java", 1, "high", "Reworded rule bug.")));
            List<String> statuses = new ArrayList<>();

            ReviewResult result =
                    pipeline(provider)
                            .review(
                                    withRules(oneRiskyHunk(), "team.md", "Prefer Optional."),
                                    false,
                                    true,
                                    false,
                                    statuses::add,
                                    null);

            assertThat(result.getLineComments())
                    .extracting(LineComment::getBody)
                    .containsExactly("Reworded rule bug.");
            assertThat(statuses).noneMatch(status -> status.endsWith("from review rules"));
        }

        @Test
        void ruleFindingsHaveNoSourcesWithoutASecondReviewer() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult = pass(result());
            provider.ruleScripts.put(
                    "team.md",
                    prompt -> reviewJson(comment("src/Api.java", 1, "high", "Rule bug.")));

            ReviewResult result =
                    pipeline(provider)
                            .review(
                                    withRules(oneRiskyHunk(), "team.md", "Prefer Optional."),
                                    false,
                                    false,
                                    false,
                                    s -> {},
                                    null);

            assertThat(result.getLineComments())
                    .singleElement()
                    .satisfies(comment -> assertThat(comment.getSources()).isEmpty());
        }

        @Test
        void ruleFindingsGainReviewerLabelsOnlyThroughMatchingCandidates() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult = pass(result());
            FakeSecondary secondary = new FakeSecondary();
            secondary.result = pass(result(comment("src/Api.java", 2, "high", "Second bug.")));
            LineComment matching = comment("src/Api.java", 3, "high", "Rule bug.");
            LineComment unmatched = ruleTagged(hygiene("src/Api.java", 5, "high", "Rule perf."));
            provider.ruleScripts.put("team.md", prompt -> reviewJson(matching, unmatched));
            provider.completions.add(reviewJson(matching, unmatched));

            ReviewResult result =
                    withSecondary(provider, secondary)
                            .review(
                                    withRules(fiveLineDiff(), "team.md", "Prefer Optional."),
                                    false,
                                    false,
                                    false,
                                    s -> {},
                                    null);

            assertThat(result.getLineComments())
                    .extracting(LineComment::getBody, LineComment::getSources)
                    .containsExactlyInAnyOrder(
                            tuple("Rule bug.", List.of("gpt-5.5")), tuple("Rule perf.", List.of()));
        }

        @Test
        void aRuleFindingTheCritiqueRewordsWithoutItsTagStaysUnlabelled() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult = pass(result());
            FakeSecondary secondary = new FakeSecondary();
            secondary.result = pass(result());
            LineComment rule = ruleTagged(hygiene("src/Api.java", 5, "high", "Rule perf."));
            LineComment reworded = hygiene("src/Api.java", 5, "high", "Rule perf.");
            reworded.setRationale("Reworded by the critique.");
            provider.ruleScripts.put("team.md", prompt -> reviewJson(rule));
            provider.completions.add(reviewJson(reworded));

            ReviewResult result =
                    withSecondary(provider, secondary)
                            .review(
                                    withRules(fiveLineDiff(), "team.md", "Prefer Optional."),
                                    false,
                                    false,
                                    false,
                                    s -> {},
                                    null);

            assertThat(result.getLineComments())
                    .singleElement()
                    .satisfies(
                            comment -> {
                                assertThat(comment.getRationale())
                                        .isEqualTo("Reworded by the critique.");
                                assertThat(comment.getSources()).isEmpty();
                            });
        }

        @Test
        void anUnmatchedReviewerFindingStillFallsBackToThePrimaryLabel() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult = pass(result());
            FakeSecondary secondary = new FakeSecondary();
            secondary.result = pass(result());
            LineComment critiqueOnly = comment("src/Api.java", 3, "high", "Critique bug.");
            LineComment rule = ruleTagged(hygiene("src/Api.java", 5, "high", "Rule perf."));
            provider.ruleScripts.put("team.md", prompt -> reviewJson(rule));
            provider.completions.add(reviewJson(critiqueOnly, rule));

            ReviewResult result =
                    withSecondary(provider, secondary)
                            .review(
                                    withRules(fiveLineDiff(), "team.md", "Prefer Optional."),
                                    false,
                                    false,
                                    false,
                                    s -> {},
                                    null);

            assertThat(result.getLineComments())
                    .extracting(LineComment::getBody, LineComment::getSources)
                    .containsExactlyInAnyOrder(
                            tuple("Critique bug.", List.of("claude-opus")),
                            tuple("Rule perf.", List.of()));
        }

        @Test
        void theHygienePassSeesTheFullDiffNotTheCondensedIndex() throws Exception {
            StringBuilder diff =
                    new StringBuilder(
                            "diff --git a/src/A.java b/src/A.java\n"
                                    + "--- a/src/A.java\n+++ b/src/A.java\n"
                                    + "@@ -1,1 +1,91 @@\n"
                                    + " unchangedContextLine();\n");
            for (int i = 1; i <= 90; i++) diff.append("+added").append(i).append("();\n");
            FakeProvider provider = new FakeProvider();
            provider.primaryResult = pass(result());
            provider.hygieneCompletions.add(emptyReviewJson());
            provider.completions.add(emptyReviewJson());

            pipeline(provider).review(request(diff.toString()), false, true, false, s -> {}, null);

            assertThat(provider.hygieneCalls)
                    .singleElement()
                    .satisfies(
                            call ->
                                    assertThat(call.prompt())
                                            .contains("unchangedContextLine();", "+added90();"));
        }

        @Test
        void aFailedHygienePassStillReachesTheCritique() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult = pass(result(comment("src/A.java", 1, "high", "Bug.")));
            provider.hygieneFailure = new IOException("hygiene unavailable");
            provider.completions.add(reviewJson(comment("src/A.java", 1, "high", "Bug.")));
            List<String> statuses = new ArrayList<>();

            ReviewResult result =
                    pipeline(provider)
                            .review(
                                    request(oneRiskyHunk()),
                                    false,
                                    true,
                                    false,
                                    statuses::add,
                                    null);

            assertThat(provider.callOrder).containsExactly("review", "hygiene", "critique");
            assertThat(statuses)
                    .containsSubsequence(
                            ReviewPipelineService.STATUS_HYGIENE_FAILED,
                            "Draft review has 1 finding before validation");
            assertThat(result.getLineComments())
                    .extracting(LineComment::getBody)
                    .containsExactly("Bug.");
        }
    }
}
