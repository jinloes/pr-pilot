package com.jinloes.prpilot.review;

import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.FAIL;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.emptyReviewJson;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.fileDiff;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.fourRiskyHunks;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.oneRiskyHunk;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.request;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.reviewJsonWithFinding;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.sevenFileDiff;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.ReviewResult;
import com.jinloes.prpilot.review.ReviewPipelineTestSupport.FakeProvider;
import com.jinloes.prpilot.review.ReviewPipelineTestSupport.PromptCall;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReviewPipelineServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Nested
    class ReviewRules {
        @Test
        void makesNoRuleCallsWithoutARulesFolder() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult =
                    ReviewPassResult.withoutLedger(
                            new ReviewResult("baseline", "APPROVE", List.of()));
            PRReviewRequest request =
                    request(oneRiskyHunk()).toBuilder().repoGuidelines("repo rule").build();

            ReviewResult result =
                    new ReviewPipelineService(
                                    provider,
                                    new ChunkedReviewService(),
                                    new ReviewCoverageAnalyzer())
                            .review(request, false, false, false, ignored -> {}, null);

            assertThat(result.getSummary()).isEqualTo("baseline");
            assertThat(provider.callOrder).containsExactly("review");
            assertThat(provider.primaryRequests)
                    .singleElement()
                    .extracting(PRReviewRequest::getRepoGuidelines)
                    .isEqualTo("repo rule");
        }

        @Test
        void loadsTheBuiltInRulesWhenEnabled() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult =
                    ReviewPassResult.withoutLedger(
                            new ReviewResult("baseline", "APPROVE", List.of()));
            PRReviewRequest request =
                    request(oneRiskyHunk()).toBuilder().builtInRules(true).build();
            List<String> status = new ArrayList<>();

            new ReviewPipelineService(
                            provider, new ChunkedReviewService(), new ReviewCoverageAnalyzer())
                    .review(request, false, false, false, status::add, null);

            assertThat(status).anySatisfy(line -> assertThat(line).startsWith("Rules: 2 loaded"));
            assertThat(provider.callOrder).startsWith("review", "rule-selection");
        }

        @Test
        void runsRulesAsSeparateAgentsInsteadOfAddingThemToTheGuidance() throws Exception {
            Path rules = Files.createTempDirectory("pipeline-rules");
            try {
                Files.writeString(rules.resolve("team.md"), "Prefer Optional over null.");
                PRReviewRequest request =
                        request(oneRiskyHunk()).toBuilder()
                                .repoGuidelines("## AGENTS.md\nrepo rule")
                                .rulesDirectory(rules.toString())
                                .build();
                FakeProvider provider = new FakeProvider();
                provider.primaryResult =
                        ReviewPassResult.withoutLedger(
                                new ReviewResult("baseline", "APPROVE", List.of()));
                provider.ruleScripts.put(
                        "team.md", prompt -> reviewJsonWithFinding("src/Api.java", 1));
                List<String> statuses = new ArrayList<>();

                ReviewResult result =
                        new ReviewPipelineService(
                                        provider,
                                        new ChunkedReviewService(),
                                        new ReviewCoverageAnalyzer())
                                .review(request, false, false, false, statuses::add, null);

                assertThat(provider.primaryRequests)
                        .singleElement()
                        .extracting(PRReviewRequest::getRepoGuidelines)
                        .isEqualTo("## AGENTS.md\nrepo rule");
                assertThat(provider.callOrder).containsExactly("review", "rule");
                assertThat(provider.ruleCalls.get(0).prompt())
                        .contains("Prefer Optional over null.");
                assertThat(statuses).contains("Rules: 1 loaded, 1 selected (team.md)");
                assertThat(result.getLineComments())
                        .singleElement()
                        .extracting(LineComment::getRationale)
                        .isEqualTo(
                                "The new signature no longer accepts the required value."
                                        + " (rule: team.md)");
            } finally {
                Files.deleteIfExists(rules.resolve("team.md"));
                Files.deleteIfExists(rules);
            }
        }

        @Test
        void chunkedRuleAgentsSeeTheCondensedIndex() throws Exception {
            Path rules = Files.createTempDirectory("pipeline-rules");
            try {
                Files.writeString(rules.resolve("team.md"), "Prefer Optional over null.");
                PRReviewRequest request =
                        request(sevenFileDiff()).toBuilder()
                                .rulesDirectory(rules.toString())
                                .build();
                FakeProvider provider = new FakeProvider();
                provider.primaryResult =
                        ReviewPassResult.withoutLedger(
                                new ReviewResult("baseline", "APPROVE", List.of()));
                ChunkedReviewService chunkedService = new ChunkedReviewService();

                new ReviewPipelineService(provider, chunkedService, new ReviewCoverageAnalyzer())
                        .review(request, true, false, false, ignored -> {}, null);

                LocalReviewRules.Rule rule =
                        new LocalReviewRules.Rule(
                                "team.md", "team.md", null, null, "Prefer Optional over null.");
                String condensed =
                        ReviewRulePrompts.buildRuleReviewPrompt(
                                chunkedService.finalValidationRequest(request), rule);
                assertThat(condensed)
                        .isNotEqualTo(ReviewRulePrompts.buildRuleReviewPrompt(request, rule));
                assertThat(provider.ruleCalls)
                        .singleElement()
                        .extracting(PromptCall::prompt)
                        .isEqualTo(condensed);
            } finally {
                Files.deleteIfExists(rules.resolve("team.md"));
                Files.deleteIfExists(rules);
            }
        }
    }

    @Nested
    class Review {
        @Test
        void disabledSupervisorPreservesSinglePassBehavior() throws Exception {
            FakeProvider provider = new FakeProvider();
            ReviewResult baseline = new ReviewResult("baseline", "APPROVE", List.of());
            provider.primaryResult = ReviewPassResult.withoutLedger(baseline);
            ReviewPipelineService pipeline =
                    new ReviewPipelineService(
                            provider, new ChunkedReviewService(), new ReviewCoverageAnalyzer());

            ReviewResult result =
                    pipeline.review(
                            request(oneRiskyHunk()), false, false, false, ignored -> {}, null);

            assertThat(result).isSameAs(baseline);
            assertThat(provider.completeCalls).isEmpty();
        }

        @Test
        void clearsAnUnsafeSuggestedChangeAsTheLastStep() throws Exception {
            LineComment valid = suggestedFinding("public void call(String value) {}");
            LineComment unchanged = suggestedFinding("public void call() {}");
            unchanged.setCategory("compatibility");
            FakeProvider provider = new FakeProvider();
            provider.primaryResult =
                    ReviewPassResult.withoutLedger(
                            new ReviewResult("baseline", "COMMENT", List.of(valid, unchanged)));
            ReviewPipelineService pipeline =
                    new ReviewPipelineService(
                            provider, new ChunkedReviewService(), new ReviewCoverageAnalyzer());

            ReviewResult result =
                    pipeline.review(
                            request(oneRiskyHunk()), false, false, false, ignored -> {}, null);

            assertThat(result.getLineComments())
                    .extracting(LineComment::getSuggestedChange)
                    .containsExactlyInAnyOrder("public void call(String value) {}", "");
            assertThat(result.getLineComments()).hasSize(2);
        }

        @Test
        void followsUpOnAnUninspectedHighRiskHunkAndMergesTheFinding() throws Exception {
            String diff = oneRiskyHunk();
            InspectionManifest manifest = InspectionManifest.fromDiff(diff);
            FakeProvider provider = new FakeProvider();
            provider.primaryResult =
                    new ReviewPassResult(
                            new ReviewResult("baseline", "APPROVE", List.of()),
                            new InspectionLedger(true, Set.of(), List.of()));
            provider.completions.add(reviewJsonWithFinding("src/Api.java", 1));
            ReviewPipelineService pipeline =
                    new ReviewPipelineService(
                            provider, new ChunkedReviewService(), new ReviewCoverageAnalyzer());

            ReviewResult result =
                    pipeline.review(request(diff), false, false, true, ignored -> {}, null);

            assertThat(result.getSummary()).isEqualTo("baseline");
            assertThat(result.getVerdict()).isEqualTo("REQUEST_CHANGES");
            assertThat(result.getLineComments())
                    .singleElement()
                    .extracting(LineComment::getFile)
                    .isEqualTo("src/Api.java");
            assertThat(provider.completeCalls)
                    .singleElement()
                    .satisfies(
                            call -> {
                                assertThat(call.allowReadTools()).isTrue();
                                assertThat(call.allowMcp()).isFalse();
                                assertThat(call.timeoutMillis()).isEqualTo(6L * 60L * 1000L);
                                assertThat(call.prompt())
                                        .contains(manifest.files().get(0).hunks().get(0).id())
                                        .doesNotContain("<recall_mode>");
                            });
        }

        @Test
        void cleanLowRiskControlDoesNotTriggerAnAdditionalProviderCall() throws Exception {
            FakeProvider provider = new FakeProvider();
            ReviewResult baseline = new ReviewResult("baseline", "APPROVE", List.of());
            String diff =
                    """
                    diff --git a/src/Formatting.java b/src/Formatting.java
                    --- a/src/Formatting.java
                    +++ b/src/Formatting.java
                    @@ -1 +1 @@
                    -int spacing = 1;
                    +int spacing = 2;
                    """;
            String inspectedFile = InspectionManifest.fromDiff(diff).files().get(0).id();
            provider.primaryResult =
                    new ReviewPassResult(
                            baseline, new InspectionLedger(true, Set.of(inspectedFile), List.of()));
            ReviewPipelineService pipeline =
                    new ReviewPipelineService(
                            provider, new ChunkedReviewService(), new ReviewCoverageAnalyzer());

            List<String> statuses = new ArrayList<>();

            ReviewResult result =
                    pipeline.review(request(diff), false, false, true, statuses::add, null);

            assertThat(result).isSameAs(baseline);
            assertThat(provider.completeCalls).isEmpty();
            assertThat(statuses).contains(ReviewPipelineService.STATUS_COVERAGE_COMPLETE);
        }

        @Test
        void usesOneToolFreePrioritizationCallBeforeOneFollowUpWhenMoreThanThreeGapsExist()
                throws Exception {
            FakeProvider provider = new FakeProvider();
            String fileId = InspectionManifest.fromDiff(fourRiskyHunks()).files().get(0).id();
            provider.primaryResult =
                    new ReviewPassResult(
                            new ReviewResult("baseline", "APPROVE", List.of()),
                            new InspectionLedger(true, Set.of(fileId), List.of()));
            provider.completions.add(
                    JSON.writeValueAsString(Map.of("selectedGapIds", List.of("G004", "G002"))));
            provider.completions.add(emptyReviewJson());
            ReviewPipelineService pipeline =
                    new ReviewPipelineService(
                            provider, new ChunkedReviewService(), new ReviewCoverageAnalyzer());

            pipeline.review(request(fourRiskyHunks()), false, false, true, ignored -> {}, null);

            assertThat(provider.completeCalls).hasSize(2);
            assertThat(provider.completeCalls.get(0))
                    .satisfies(
                            call -> {
                                assertThat(call.allowReadTools()).isFalse();
                                assertThat(call.allowMcp()).isFalse();
                                assertThat(call.timeoutMillis()).isEqualTo(90_000);
                            });
            assertThat(provider.completeCalls.get(1))
                    .satisfies(
                            call -> {
                                assertThat(call.allowReadTools()).isTrue();
                                assertThat(call.allowMcp()).isFalse();
                                assertThat(call.timeoutMillis()).isEqualTo(6L * 60L * 1000L);
                            });
        }

        @Test
        void reReviewsEveryUncoveredFileInBatches() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult =
                    new ReviewPassResult(
                            new ReviewResult("baseline", "APPROVE", List.of()),
                            new InspectionLedger(true, Set.of(), List.of()));
            provider.completions.add(reviewJsonWithFinding("F0.java", 1));
            provider.completions.add(reviewJsonWithFinding("F6.java", 1));
            ReviewPipelineService pipeline =
                    new ReviewPipelineService(
                            provider, new ChunkedReviewService(), new ReviewCoverageAnalyzer());

            List<String> statuses = new ArrayList<>();

            ReviewResult result =
                    pipeline.review(request(fileDiff(7)), false, false, true, statuses::add, null);

            assertThat(statuses)
                    .contains(
                            "Coverage follow-ups re-reviewed 7 files and 0 hunks and found 2"
                                    + " findings");
            assertThat(provider.completeCalls).hasSize(2);
            assertThat(provider.completeCalls).allMatch(PromptCall::allowReadTools);
            assertThat(provider.completeCalls.get(0).prompt())
                    .contains("diff --git a/F0.java", "diff --git a/F5.java")
                    .doesNotContain("diff --git a/F6.java");
            assertThat(provider.completeCalls.get(1).prompt())
                    .contains("diff --git a/F6.java")
                    .doesNotContain("diff --git a/F0.java");
            assertThat(result.getLineComments())
                    .extracting(LineComment::getFile)
                    .containsExactlyInAnyOrder("F0.java", "F6.java");
        }

        @Test
        void capsTheNumberOfWholeFileFollowUps() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult =
                    new ReviewPassResult(
                            new ReviewResult("baseline", "APPROVE", List.of()),
                            new InspectionLedger(true, Set.of(), List.of()));
            int files =
                    ReviewPipelineService.FILES_PER_FOLLOW_UP
                                    * (ReviewPipelineService.MAX_FILE_FOLLOW_UPS + 1)
                            + 1;
            for (int i = 0; i < ReviewPipelineService.MAX_FILE_FOLLOW_UPS; i++) {
                provider.completions.add(emptyReviewJson());
            }
            ReviewPipelineService pipeline =
                    new ReviewPipelineService(
                            provider, new ChunkedReviewService(), new ReviewCoverageAnalyzer());

            pipeline.review(request(fileDiff(files)), false, false, true, ignored -> {}, null);

            assertThat(provider.completeCalls).hasSize(ReviewPipelineService.MAX_FILE_FOLLOW_UPS);
        }

        @Test
        void keepsOtherBatchesFindingsWhenOneFollowUpFails() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult =
                    new ReviewPassResult(
                            new ReviewResult("baseline", "APPROVE", List.of()),
                            new InspectionLedger(true, Set.of(), List.of()));
            provider.completions.add(FAIL);
            provider.completions.add(reviewJsonWithFinding("F6.java", 1));
            ReviewPipelineService pipeline =
                    new ReviewPipelineService(
                            provider, new ChunkedReviewService(), new ReviewCoverageAnalyzer());

            ReviewResult result =
                    pipeline.review(request(fileDiff(7)), false, false, true, ignored -> {}, null);

            assertThat(provider.completeCalls).hasSize(2);
            assertThat(result.getLineComments())
                    .singleElement()
                    .extracting(LineComment::getFile)
                    .isEqualTo("F6.java");
        }

        @Test
        void stillReReviewsUncoveredFilesWhenPrioritizationFails() throws Exception {
            String diff = fourRiskyHunks() + fileDiff(1);
            FakeProvider provider = new FakeProvider();
            String apiId = InspectionManifest.fromDiff(diff).files().get(0).id();
            provider.primaryResult =
                    new ReviewPassResult(
                            new ReviewResult("baseline", "APPROVE", List.of()),
                            new InspectionLedger(true, Set.of(apiId), List.of()));
            provider.completions.add(FAIL);
            provider.completions.add(reviewJsonWithFinding("F0.java", 1));
            ReviewPipelineService pipeline =
                    new ReviewPipelineService(
                            provider, new ChunkedReviewService(), new ReviewCoverageAnalyzer());

            ReviewResult result =
                    pipeline.review(request(diff), false, false, true, ignored -> {}, null);

            assertThat(provider.completeCalls).hasSize(2);
            assertThat(provider.completeCalls.get(0).allowReadTools()).isFalse();
            assertThat(provider.completeCalls.get(1).prompt())
                    .contains("diff --git a/F0.java")
                    .doesNotContain("diff --git a/src/Api.java");
            assertThat(result.getLineComments())
                    .singleElement()
                    .extracting(LineComment::getFile)
                    .isEqualTo("F0.java");
        }

        @Test
        void keepsTheBaselineWhenTheTargetedFollowUpFails() throws Exception {
            FakeProvider provider = new FakeProvider();
            ReviewResult baseline = new ReviewResult("baseline", "APPROVE", List.of());
            provider.primaryResult =
                    new ReviewPassResult(baseline, new InspectionLedger(true, Set.of(), List.of()));
            provider.completionFailure = new IOException("follow-up unavailable");
            ReviewPipelineService pipeline =
                    new ReviewPipelineService(
                            provider, new ChunkedReviewService(), new ReviewCoverageAnalyzer());

            ReviewResult result =
                    pipeline.review(
                            request(oneRiskyHunk()), false, false, true, ignored -> {}, null);

            assertThat(result).isSameAs(baseline);
        }

        @Test
        void runsFinalCritiqueOnlyOnceAfterChunkReconciliation() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult =
                    ReviewPassResult.withoutLedger(
                            new ReviewResult("primary", "APPROVE", List.of()));
            provider.completions.add(emptyReviewJson());
            ReviewPipelineService pipeline =
                    new ReviewPipelineService(
                            provider, new ChunkedReviewService(), new ReviewCoverageAnalyzer());

            pipeline.review(request(sevenFileDiff()), true, true, false, ignored -> {}, null);

            assertThat(provider.primaryCalls).hasValue(3);
            assertThat(provider.completeCalls)
                    .singleElement()
                    .extracting(PromptCall::prompt)
                    .asString()
                    .contains(
                            "<draft_review>", "Changed files and contract-relevant changed lines.")
                    .doesNotContain("diff --git");
        }

        @Test
        void propagatesCancellationInsteadOfReturningFallbackSuccess() {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult =
                    ReviewPassResult.withoutLedger(
                            new ReviewResult("primary", "APPROVE", List.of()));
            provider.cancelAfterPrimary = true;
            ReviewPipelineService pipeline =
                    new ReviewPipelineService(
                            provider, new ChunkedReviewService(), new ReviewCoverageAnalyzer());

            assertThatThrownBy(
                            () ->
                                    pipeline.review(
                                            request(oneRiskyHunk()),
                                            false,
                                            false,
                                            true,
                                            ignored -> {},
                                            null))
                    .isInstanceOf(InterruptedException.class);
        }
    }

    private static LineComment suggestedFinding(String suggestion) {
        LineComment comment = new LineComment("src/Api.java", 1, "issue", "Keep the parameter.");
        comment.setSeverity("major");
        comment.setCategory("correctness");
        comment.setConfidence("high");
        comment.setRationale("Line 1 drops the parameter.");
        comment.setSuggestedChange(suggestion);
        return comment;
    }

    @Nested
    class ReviewerStatus {
        @Test
        void namesTheModelWhenKnown() {
            assertThat(ReviewPipelineService.reviewerStatus("Primary", " gpt-5.5 ", "done"))
                    .isEqualTo("Primary reviewer (gpt-5.5) done");
        }

        @Test
        void omitsTheModelWhenBlankOrNull() {
            assertThat(ReviewPipelineService.reviewerStatus("Second", " ", "done"))
                    .isEqualTo("Second reviewer done");
            assertThat(ReviewPipelineService.reviewerStatus("Second", null, "done"))
                    .isEqualTo("Second reviewer done");
        }

        @Test
        void reportsDraftAndValidatedCounts() {
            assertThat(ReviewPipelineService.draftStatus(1))
                    .isEqualTo("Draft review has 1 finding before validation");
            assertThat(ReviewPipelineService.validatedStatus(0, 3))
                    .isEqualTo("Validation kept 0 of 3 findings");
        }
    }

    @Nested
    class CoverageStatus {
        @Test
        void reportsFilesHunksAndFindings() {
            assertThat(ReviewPipelineService.coverageStatus(1, 2, 1, 0))
                    .isEqualTo(
                            "Coverage follow-ups re-reviewed 1 file and 2 hunks and found 1"
                                    + " finding");
        }

        @Test
        void appendsFailedCalls() {
            assertThat(ReviewPipelineService.coverageStatus(7, 1, 0, 2))
                    .isEqualTo(
                            "Coverage follow-ups re-reviewed 7 files and 1 hunk and found 0"
                                    + " findings (2 calls failed)");
        }
    }
}
