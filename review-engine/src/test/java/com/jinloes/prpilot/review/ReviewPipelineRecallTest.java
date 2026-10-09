package com.jinloes.prpilot.review;

import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.emptyReviewJson;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.oneRiskyHunk;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.request;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.reviewJsonWithFinding;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.ReviewResult;
import com.jinloes.prpilot.review.ReviewPipelineTestSupport.FakeProvider;
import com.jinloes.prpilot.review.ReviewPipelineTestSupport.FakeSecondary;
import com.jinloes.prpilot.review.ReviewPipelineTestSupport.PromptCall;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Recall-mode and second-reviewer tests for {@link ReviewPipelineService}. */
class ReviewPipelineRecallTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path semanticRoot;

    @Nested
    class RecallAndSecondReviewer {
        private static final String BASE_SHA = "b".repeat(40);

        private ReviewPipelineService pipeline(FakeProvider provider) {
            return new ReviewPipelineService(
                    provider, new ChunkedReviewService(), new ReviewCoverageAnalyzer());
        }

        private ReviewPipelineService pipeline(
                FakeProvider provider, ReviewPipelineService.BaseContextResolver resolver) {
            return new ReviewPipelineService(
                    provider, new ChunkedReviewService(), new ReviewCoverageAnalyzer(), resolver);
        }

        private ReviewPipelineService withSecondary(
                FakeProvider provider, FakeSecondary secondary) {
            return pipeline(provider).withSecondReviewer(secondary, secondary::cancel, "gpt-5.5");
        }

        @Test
        void mergesSecondReviewerFindingsAndForcesValidation() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult =
                    pass(result(comment("src/A.java", 1, "high", "Primary finding.")));
            FakeSecondary secondary = new FakeSecondary();
            secondary.result = pass(result(comment("src/B.java", 2, "low", "Second finding.")));
            provider.completions.add(
                    reviewJson(
                            comment("src/A.java", 1, "high", "Primary finding."),
                            comment("src/B.java", 2, "medium", "Second finding.")));
            List<String> statuses = new CopyOnWriteArrayList<>();

            ReviewResult result =
                    withSecondary(provider, secondary)
                            .review(
                                    request(oneRiskyHunk()),
                                    false,
                                    false,
                                    false,
                                    statuses::add,
                                    null);

            assertThat(statuses)
                    .containsSubsequence(
                            "Second reviewer (gpt-5.5) started in parallel",
                            "Primary reviewer (claude-opus) finished with 1 finding",
                            "Second reviewer (gpt-5.5) finished with 1 finding",
                            "Merged reviewers into 2 findings",
                            "Draft review has 2 findings before validation",
                            ClaudeService.STATUS_REFINING,
                            "Validation kept 2 of 2 findings");

            assertThat(provider.primaryRequests.get(0).isCandidateRecall()).isTrue();
            assertThat(secondary.requests.get(0).isCandidateRecall()).isTrue();
            assertThat(provider.completeCalls)
                    .singleElement()
                    .extracting(PromptCall::prompt)
                    .asString()
                    .contains("<draft_review>", "Primary finding.", "Second finding.");
            assertThat(result.getLineComments())
                    .extracting(LineComment::getBody)
                    .containsExactly("Primary finding.", "Second finding.");
            assertThat(secondary.cancels).hasValue(0);
        }

        @Test
        void reportsWaitingWhenTheSecondReviewerOutlastsThePrimary() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult = pass(result());
            FakeSecondary secondary = new FakeSecondary();
            secondary.result = pass(result());
            secondary.release = new CountDownLatch(1);
            provider.completions.add(reviewJson());
            List<String> statuses = new CopyOnWriteArrayList<>();
            Consumer<String> onStatus =
                    status -> {
                        statuses.add(status);
                        if (status.contains("still running")) secondary.release.countDown();
                    };

            withSecondary(provider, secondary)
                    .review(request(oneRiskyHunk()), false, false, false, onStatus, null);

            assertThat(statuses)
                    .containsSubsequence(
                            "Second reviewer (gpt-5.5) started in parallel",
                            "Primary reviewer (claude-opus) finished with 0 findings",
                            "Second reviewer (gpt-5.5) still running; waiting…",
                            "Second reviewer (gpt-5.5) finished with 0 findings",
                            "Merged reviewers into 0 findings");
        }

        @Test
        void keepsThePrimaryResultWhenTheSecondReviewerFails() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult =
                    pass(result(comment("src/A.java", 1, "high", "Primary finding.")));
            provider.completionFailure = new IOException("validator unavailable");
            FakeSecondary secondary = new FakeSecondary();
            secondary.failure = new IOException("second model unavailable");
            List<String> statuses = new CopyOnWriteArrayList<>();

            ReviewResult result =
                    withSecondary(provider, secondary)
                            .review(
                                    request(oneRiskyHunk()),
                                    false,
                                    false,
                                    false,
                                    statuses::add,
                                    null);

            assertThat(statuses)
                    .contains(
                            "Primary reviewer (claude-opus) finished with 1 finding",
                            "Second reviewer (gpt-5.5) failed; using primary findings")
                    .noneMatch(status -> status.startsWith("Merged reviewers"));

            assertThat(result.getLineComments())
                    .extracting(LineComment::getBody)
                    .containsExactly("Primary finding.");
            assertThat(result.getLineComments())
                    .allSatisfy(comment -> assertThat(comment.getSources()).isEmpty());
        }

        @Test
        void attributesFindingsAndMarksCorroboratedOnesForCritique() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult =
                    pass(result(comment("src/A.java", 1, "high", "Primary finding.")));
            FakeSecondary secondary = new FakeSecondary();
            secondary.result =
                    pass(
                            result(
                                    comment("src/A.java", 2, "medium", "Second phrasing."),
                                    comment("src/B.java", 5, "medium", "Second only.")));
            provider.hygieneCompletions.add(
                    reviewJson(hygiene("src/Api.java", 1, "high", "Hot-path log.")));
            provider.completions.add(
                    reviewJson(
                            comment("src/A.java", 1, "high", "Primary finding, tightened."),
                            comment("src/B.java", 5, "medium", "Second only."),
                            comment("src/C.java", 9, "high", "Critique added.")));
            List<String> statuses = new CopyOnWriteArrayList<>();

            ReviewResult result =
                    withSecondary(provider, secondary)
                            .review(
                                    request(oneRiskyHunk()),
                                    false,
                                    true,
                                    false,
                                    statuses::add,
                                    null);

            assertThat(statuses).contains("Merged reviewers into 2 findings");
            assertThat(provider.completeCalls)
                    .singleElement()
                    .extracting(PromptCall::prompt)
                    .asString()
                    .contains("\"corroborated\":true", "reported independently by two reviewers")
                    .doesNotContain("Second phrasing.", "\"sources\"");
            assertThat(result.getLineComments())
                    .extracting(LineComment::getBody, LineComment::getSources)
                    .containsExactlyInAnyOrder(
                            tuple("Primary finding, tightened.", List.of("claude-opus", "gpt-5.5")),
                            tuple("Second only.", List.of("gpt-5.5")),
                            tuple("Critique added.", List.of("claude-opus")),
                            tuple("Hot-path log.", List.of("claude-opus")));
        }

        @Test
        void attributesTheDraftWhenCritiqueFails() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult =
                    pass(result(comment("src/A.java", 1, "high", "Primary finding.")));
            FakeSecondary secondary = new FakeSecondary();
            secondary.result =
                    pass(
                            result(
                                    comment("src/A.java", 1, "medium", "Second phrasing."),
                                    comment("src/B.java", 5, "medium", "Second only.")));
            provider.hygieneCompletions.add(
                    reviewJson(hygiene("src/Api.java", 1, "high", "Hot-path log.")));
            provider.completionFailure = new IOException("validator unavailable");

            ReviewResult result =
                    withSecondary(provider, secondary)
                            .review(request(oneRiskyHunk()), false, true, false, s -> {}, null);

            assertThat(result.getLineComments())
                    .extracting(LineComment::getBody, LineComment::getSources)
                    .containsExactlyInAnyOrder(
                            tuple("Primary finding.", List.of("claude-opus", "gpt-5.5")),
                            tuple("Second only.", List.of("gpt-5.5")),
                            tuple("Hot-path log.", List.of("claude-opus")));
        }

        @Test
        void critiqueFailureAttributionDoesNotChainThroughNearbyFindings() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult =
                    pass(
                            result(
                                    severity(comment("src/A.java", 10, "high", "Nit."), "nit"),
                                    severity(comment("src/A.java", 12, "high", "Minor."), "minor"),
                                    severity(
                                            comment("src/A.java", 14, "high", "Major."), "major")));
            FakeSecondary secondary = new FakeSecondary();
            secondary.result = pass(result(comment("src/A.java", 14, "high", "Second.")));
            provider.hygieneCompletions.add(reviewJson());
            provider.completionFailure = new IOException("validator unavailable");

            ReviewResult result =
                    withSecondary(provider, secondary)
                            .review(request(oneRiskyHunk()), false, true, false, s -> {}, null);

            assertThat(result.getLineComments())
                    .extracting(LineComment::getLine, LineComment::getSources)
                    .containsExactlyInAnyOrder(
                            tuple(14, List.of("claude-opus", "gpt-5.5")),
                            tuple(12, List.of("claude-opus", "gpt-5.5")),
                            tuple(10, List.of("claude-opus")));
        }

        @Test
        void attributesSupervisorFollowUpFindingsToThePrimaryReviewer() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult =
                    new ReviewPassResult(result(), new InspectionLedger(true, Set.of(), List.of()));
            FakeSecondary secondary = new FakeSecondary();
            secondary.result =
                    new ReviewPassResult(
                            result(comment("src/B.java", 5, "medium", "Second only.")),
                            new InspectionLedger(true, Set.of(), List.of()));
            provider.completions.add(reviewJsonWithFinding("src/Api.java", 1));
            provider.completions.add(
                    reviewJson(
                            comment("src/B.java", 5, "medium", "Second only."),
                            comment("src/Api.java", 1, "high", "Follow-up finding.")));

            ReviewResult result =
                    withSecondary(provider, secondary)
                            .review(request(oneRiskyHunk()), false, false, true, s -> {}, null);

            assertThat(provider.callOrder).containsSubsequence("supervisor", "critique");

            assertThat(result.getLineComments())
                    .extracting(LineComment::getFile, LineComment::getSources)
                    .containsExactly(tuple("src/Api.java", List.of("claude-opus")));
        }

        @Test
        void leavesFindingsUnattributedWithoutASecondReviewer() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult =
                    pass(result(comment("src/A.java", 1, "high", "Primary finding.")));
            provider.completions.add(
                    reviewJson(comment("src/A.java", 1, "high", "Primary finding.")));

            ReviewResult result =
                    pipeline(provider)
                            .review(request(oneRiskyHunk()), false, true, false, s -> {}, null);

            assertThat(provider.completeCalls.get(0).prompt())
                    .doesNotContain("corroborated", "reported independently by two reviewers");
            assertThat(result.getLineComments())
                    .singleElement()
                    .satisfies(comment -> assertThat(comment.getSources()).isEmpty());
        }

        @Test
        void primaryCancellationCancelsAndInterruptsTheSecondReviewer() throws Exception {
            FakeProvider provider = new FakeProvider();
            FakeSecondary secondary = new FakeSecondary();
            secondary.blockUntilInterrupted = true;
            provider.beforePrimary =
                    () -> {
                        assertThat(secondary.started.await(5, TimeUnit.SECONDS)).isTrue();
                        throw new InterruptedException("cancelled");
                    };

            assertThatThrownBy(
                            () ->
                                    withSecondary(provider, secondary)
                                            .review(
                                                    request(oneRiskyHunk()),
                                                    false,
                                                    false,
                                                    false,
                                                    s -> {},
                                                    null))
                    .isInstanceOf(InterruptedException.class);

            assertThat(secondary.cancels).hasValue(1);
            assertThat(secondary.interrupted.await(5, TimeUnit.SECONDS))
                    .as("the executor is shut down, interrupting the second reviewer")
                    .isTrue();
        }

        @Test
        void cancellationWhileWaitingForTheSecondReviewerCancelsIt() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult = pass(result());
            provider.cancelAfterPrimary = true;
            FakeSecondary secondary = new FakeSecondary();
            secondary.blockUntilInterrupted = true;
            provider.beforePrimary =
                    () -> assertThat(secondary.started.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(
                            () ->
                                    withSecondary(provider, secondary)
                                            .review(
                                                    request(oneRiskyHunk()),
                                                    false,
                                                    false,
                                                    false,
                                                    s -> {},
                                                    null))
                    .isInstanceOf(InterruptedException.class);

            assertThat(secondary.cancels).hasValue(1);
            assertThat(secondary.interrupted.await(5, TimeUnit.SECONDS)).isTrue();
        }

        @Test
        void critiqueFailureStripsUnconfirmedLowConfidenceCandidates() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult =
                    pass(
                            result(
                                    comment("src/A.java", 1, "high", "Confirmed finding."),
                                    comment("src/A.java", 1, "low", "Unconfirmed candidate.")));
            provider.completionFailure = new IOException("validator unavailable");

            ReviewResult result =
                    pipeline(provider)
                            .review(request(oneRiskyHunk()), false, true, false, s -> {}, null);

            assertThat(result.getLineComments())
                    .extracting(LineComment::getBody)
                    .containsExactly("Confirmed finding.");
        }

        @Test
        void supervisorFollowUpKeepsRecallModeWhenSelfCritiqueIsOn() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult =
                    new ReviewPassResult(
                            new ReviewResult("baseline", "APPROVE", List.of()),
                            new InspectionLedger(true, Set.of(), List.of()));
            provider.completions.add(emptyReviewJson());
            provider.completions.add(emptyReviewJson());

            pipeline(provider).review(request(oneRiskyHunk()), false, true, true, s -> {}, null);

            assertThat(provider.completeCalls).hasSize(2);
            assertThat(provider.completeCalls.get(0).prompt())
                    .doesNotContain("<draft_review>")
                    .contains("<recall_mode>");
        }

        @Test
        void recomputesTheVerdictAfterValidationEvenWhenNothingIsDropped() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult =
                    pass(result(comment("src/A.java", 1, "medium", "Confirmed minor finding.")));
            provider.completions.add(
                    JSON.writeValueAsString(
                            Map.of(
                                    "summary",
                                    "validated",
                                    "verdict",
                                    "APPROVE",
                                    "lineComments",
                                    List.of(
                                            comment(
                                                    "src/A.java",
                                                    1,
                                                    "medium",
                                                    "Confirmed minor finding.")))));

            ReviewResult result =
                    pipeline(provider)
                            .review(request(oneRiskyHunk()), false, true, false, s -> {}, null);

            assertThat(result.getLineComments()).hasSize(1);
            assertThat(result.getVerdict()).isEqualTo("COMMENT");
        }

        @Test
        void recallStaysOffWithoutSelfCritiqueOrASecondReviewer() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult =
                    pass(result(comment("src/A.java", 1, "low", "Low-confidence note.")));

            ReviewResult result =
                    pipeline(provider)
                            .review(request(oneRiskyHunk()), false, false, false, s -> {}, null);

            assertThat(provider.primaryRequests.get(0).isCandidateRecall()).isFalse();
            assertThat(provider.completeCalls).isEmpty();
            assertThat(provider.hygieneCalls).isEmpty();
            assertThat(result.getLineComments()).hasSize(1);
        }

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
                                        .contains(ReviewPrompts.HYGIENE_RULES, "<pr_diff>")
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

        @Test
        void reportsNoDroppedFindingsUnlessAskedTo() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult = pass(result(comment("src/A.java", 1, "high", "Bug.")));
            provider.completions.add(emptyReviewJson());
            List<String> statuses = new ArrayList<>();

            pipeline(provider)
                    .review(request(oneRiskyHunk()), false, true, false, statuses::add, null);

            assertThat(statuses).noneMatch(status -> status.startsWith("Validation dropped"));
        }

        @Test
        void droppedStatusesSkipKeptLocationsDeduplicateAndAbbreviate() {
            String longBody = "word ".repeat(60);
            ReviewResult draft =
                    result(
                            comment("src/A.java", 1, "high", "Kept."),
                            comment("src/A.java", 4, "high", "First\n  dropped."),
                            comment("src/A.java", 4, "high", "Duplicate location."),
                            comment("src/B.java", 9, "high", longBody));
            ReviewResult validated = result(comment("src/A.java", 1, "high", "Kept."));

            List<String> statuses = ReviewPipelineService.droppedStatuses(draft, validated);

            assertThat(statuses).hasSize(2);
            assertThat(statuses.get(0))
                    .isEqualTo("Validation dropped finding at src/A.java:4 — First dropped.");
            assertThat(statuses.get(1))
                    .startsWith("Validation dropped finding at src/B.java:9 — word word")
                    .endsWith("...");
            assertThat(statuses.get(1).substring(statuses.get(1).indexOf("— ") + 2)).hasSize(120);
        }

        @Test
        void droppedStatusesAreEmptyWhenEverythingIsKept() {
            ReviewResult draft = result(comment("src/A.java", 1, "high", "Kept."));

            assertThat(ReviewPipelineService.droppedStatuses(draft, draft)).isEmpty();
        }

        @Test
        void reportsEachDroppedFindingWhenAskedTo() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult =
                    pass(
                            result(
                                    comment("src/A.java", 1, "high", "Kept."),
                                    comment("src/A.java", 3, "high", "Dropped.")));
            provider.completions.add(reviewJson(comment("src/A.java", 1, "high", "Kept.")));
            List<String> statuses = new ArrayList<>();
            System.setProperty(ReviewPipelineService.REPORT_DROPPED_PROPERTY, "true");
            try {
                pipeline(provider)
                        .review(request(oneRiskyHunk()), false, true, false, statuses::add, null);
            } finally {
                System.clearProperty(ReviewPipelineService.REPORT_DROPPED_PROPERTY);
            }

            assertThat(statuses)
                    .filteredOn(status -> status.startsWith("Validation dropped"))
                    .containsExactly("Validation dropped finding at src/A.java:3 — Dropped.");
            assertThat(statuses)
                    .containsSubsequence(
                            "Validation dropped finding at src/A.java:3 — Dropped.",
                            "Validation kept 1 of 2 findings");
        }

        @Test
        void passesAllFortyMergedCandidatesToTheCritique() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult = pass(numberedComments("primary", "src/A.java", 20));
            FakeSecondary secondary = new FakeSecondary();
            secondary.result = pass(numberedComments("second", "src/B.java", 20));
            provider.completions.add(emptyReviewJson());

            withSecondary(provider, secondary)
                    .review(request(oneRiskyHunk()), false, false, false, s -> {}, null);

            String critique = provider.completeCalls.get(0).prompt();
            for (int index = 0; index < 20; index++) {
                assertThat(critique)
                        .contains("primary finding #" + index + ";")
                        .contains("second finding #" + index + ";");
            }
        }

        @Test
        void capsTheFinalReviewAtTwentyFindingsWithSupervisionOnAndCritiqueOff() throws Exception {
            StringBuilder diff =
                    new StringBuilder(
                            "diff --git a/src/A.java b/src/A.java\n--- a/src/A.java\n"
                                    + "+++ b/src/A.java\n@@ -0,0 +1,25 @@\n");
            for (int line = 1; line <= 25; line++) diff.append("+int v").append(line).append(";\n");
            InspectionManifest manifest = InspectionManifest.fromDiff(diff.toString());
            FakeProvider provider = new FakeProvider();
            ReviewResult many = numberedComments("primary", "src/A.java", 25);
            many.getLineComments().get(24).setSeverity("blocker");
            provider.primaryResult =
                    new ReviewPassResult(
                            many,
                            new InspectionLedger(
                                    true,
                                    Set.of(
                                            manifest.files().get(0).id(),
                                            manifest.files().get(0).hunks().get(0).id()),
                                    List.of()));

            ReviewResult result =
                    pipeline(provider)
                            .review(request(diff.toString()), false, false, true, s -> {}, null);

            assertThat(provider.completeCalls).isEmpty();
            assertThat(result.getLineComments()).hasSize(20);
            assertThat(result.getLineComments().get(0).getBody()).isEqualTo("primary finding #24;");
        }

        @Test
        void enrichesTheRequestFromTheBaseCommitBeforeReviewing() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.projectDir = semanticRoot.toFile();
            provider.primaryResult = pass(result());
            provider.completions.add(emptyReviewJson());
            List<String> resolvedShas = new ArrayList<>();
            List<String> statuses = new ArrayList<>();

            pipeline(
                            provider,
                            (dir, sha, manifest, globs, cancellation) -> {
                                assertThat(dir).isEqualTo(semanticRoot.toFile());
                                assertThat(globs).containsExactly("rules/*.md");
                                resolvedShas.add(sha);
                                return new BaseCommitContext.Result(
                                        "## AGENTS.md\nBase rule.",
                                        "## src/Api.java\nabc1234 2026-01-01 Keep it stable",
                                        "## save (declaration changed in src/Api.java)\n"
                                                + "src/Caller.java:7: api.save(x);");
                            })
                    .review(
                            request(oneRiskyHunk()).toBuilder()
                                    .baseSha(BASE_SHA)
                                    .guidanceGlobs(List.of("rules/*.md"))
                                    .build(),
                            false,
                            true,
                            false,
                            statuses::add,
                            null);

            assertThat(resolvedShas).containsExactly(BASE_SHA);
            assertThat(statuses).contains(ReviewPipelineService.STATUS_BASE_CONTEXT);
            PRReviewRequest reviewed = provider.primaryRequests.get(0);
            assertThat(reviewed.getRepoGuidelines()).isEqualTo("## AGENTS.md\nBase rule.");
            assertThat(reviewed.getFileHistory()).contains("Keep it stable");
            assertThat(provider.completeCalls.get(0).prompt())
                    .contains("<file_history>", "Keep it stable")
                    .contains("<call_sites>", "src/Caller.java:7: api.save(x);");
            assertThat(reviewed.getCallSites()).contains("api.save(x)");
        }

        @Test
        void keepsExistingGuidanceWhenTheBaseCommitHasNone() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.projectDir = semanticRoot.toFile();
            provider.primaryResult = pass(result());

            pipeline(
                            provider,
                            (dir, sha, manifest, globs, cancellation) ->
                                    BaseCommitContext.Result.EMPTY)
                    .review(
                            request(oneRiskyHunk()).toBuilder()
                                    .baseSha(BASE_SHA)
                                    .repoGuidelines("Host guidance.")
                                    .build(),
                            false,
                            false,
                            false,
                            s -> {},
                            null);

            assertThat(provider.primaryRequests.get(0).getRepoGuidelines())
                    .isEqualTo("Host guidance.");
        }

        @Test
        void skipsEnrichmentWithoutAProjectDirectoryOrBaseSha() throws Exception {
            AtomicInteger resolutions = new AtomicInteger();
            ReviewPipelineService.BaseContextResolver resolver =
                    (dir, sha, manifest, globs, cancellation) -> {
                        resolutions.incrementAndGet();
                        return BaseCommitContext.Result.EMPTY;
                    };
            FakeProvider noDir = new FakeProvider();
            noDir.primaryResult = pass(result());
            FakeProvider noSha = new FakeProvider();
            noSha.projectDir = semanticRoot.toFile();
            noSha.primaryResult = pass(result());

            pipeline(noDir, resolver)
                    .review(
                            request(oneRiskyHunk()).toBuilder().baseSha(BASE_SHA).build(),
                            false,
                            false,
                            false,
                            s -> {},
                            null);
            pipeline(noSha, resolver)
                    .review(request(oneRiskyHunk()), false, false, false, s -> {}, null);

            assertThat(resolutions).hasValue(0);
        }

        @Test
        void secondReviewerIsANoOpForABlankModelOrAMissingDirectory() {
            ReviewPipelineService pipeline =
                    ReviewPipelineService.forClaude(new ClaudeService(), "model");

            assertThat(pipeline.withSecondReviewer(" ", "high", null)).isSameAs(pipeline);
            assertThat(pipeline.withSecondReviewer("gpt-5", "high", null)).isSameAs(pipeline);
        }

        private static ReviewPassResult pass(ReviewResult review) {
            return ReviewPassResult.withoutLedger(review);
        }

        private static ReviewResult result(LineComment... comments) {
            return new ReviewResult("reviewed", "COMMENT", new ArrayList<>(List.of(comments)));
        }

        private static ReviewResult numberedComments(String label, String file, int count) {
            List<LineComment> comments = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                comments.add(
                        comment(file, index + 1, "medium", label + " finding #" + index + ";"));
            }
            return new ReviewResult("reviewed", "COMMENT", comments);
        }

        private static LineComment comment(String file, int line, String confidence, String body) {
            LineComment comment = new LineComment(file, line, "issue", body);
            comment.setSeverity("minor");
            comment.setCategory("correctness");
            comment.setConfidence(confidence);
            comment.setRationale("Rationale for " + body);
            return comment;
        }

        private static LineComment severity(LineComment comment, String severity) {
            comment.setSeverity(severity);
            return comment;
        }

        private static LineComment hygiene(String file, int line, String confidence, String body) {
            LineComment comment = comment(file, line, confidence, body);
            comment.setType("suggestion");
            comment.setCategory("performance");
            return comment;
        }

        /** The critique echoes a rule finding's rationale, including the engine's rule tag. */
        private static LineComment ruleTagged(LineComment comment) {
            comment.setRationale(ReviewRulesPass.withRuleSuffix(comment.getRationale(), "team.md"));
            return comment;
        }

        private static String reviewJson(LineComment... comments) throws IOException {
            return JSON.writeValueAsString(
                    Map.of(
                            "summary",
                            "validated",
                            "verdict",
                            "COMMENT",
                            "lineComments",
                            List.of(comments)));
        }
    }
}
