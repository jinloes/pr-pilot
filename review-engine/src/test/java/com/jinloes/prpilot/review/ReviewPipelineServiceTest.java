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
import com.jinloes.prpilot.review.ReviewPipelineTestSupport.DeepStages;
import com.jinloes.prpilot.review.ReviewPipelineTestSupport.FakeProvider;
import com.jinloes.prpilot.review.ReviewPipelineTestSupport.PromptCall;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReviewPipelineServiceTest {
    @Nested
    class UnattributeUnmatchedRuleFindings {
        private final List<LocalReviewRules.Rule> rules =
                List.of(new LocalReviewRules.Rule("perf", "perf.yaml", "d", "t", "p"));

        private LineComment finding(int line, String rationale, List<String> sources) {
            LineComment comment = new LineComment("src/A.java", line, "issue", "Body " + line);
            comment.setCategory("performance");
            comment.setRationale(rationale);
            comment.setSources(sources);
            return comment;
        }

        @Test
        void clearsTheFallbackLabelOnAnUnmatchedRuleFinding() {
            LineComment rule = finding(10, "Slow. (rule: perf)", List.of("claude-opus"));

            ReviewPipelineService.unattributeUnmatchedRuleFindings(
                    new ReviewResult("", "COMMENT", List.of(rule)), List.of(), List.of(), rules);

            assertThat(rule.getSources()).isEmpty();
        }

        @Test
        void clearsTheFallbackLabelWhenCritiqueRewordedTheRuleFindingWithoutItsTag() {
            List<ReviewPipelineService.RuleSite> sites =
                    ReviewPipelineService.RuleSite.of(
                            List.of(finding(10, "Slow. (rule: perf)", List.of())));
            LineComment reworded = finding(11, "Reworded by critique.", List.of("claude-opus"));

            ReviewPipelineService.unattributeUnmatchedRuleFindings(
                    new ReviewResult("", "COMMENT", List.of(reworded)), List.of(), sites, rules);

            assertThat(reworded.getSources()).isEmpty();
        }

        @Test
        void ruleSitesAreSnapshotsUnaffectedByLaterEditsToTheRuleComment() {
            LineComment original = finding(10, "Slow. (rule: perf)", List.of());
            List<ReviewPipelineService.RuleSite> sites =
                    ReviewPipelineService.RuleSite.of(List.of(original));
            original.setLine(40);
            LineComment atOriginalSite = finding(10, "Reworded.", List.of("claude-opus"));
            LineComment nearEditedLine = finding(40, "Other.", List.of("claude-opus"));

            ReviewPipelineService.unattributeUnmatchedRuleFindings(
                    new ReviewResult("", "COMMENT", List.of(atOriginalSite, nearEditedLine)),
                    List.of(),
                    sites,
                    rules);

            assertThat(atOriginalSite.getSources()).isEmpty();
            assertThat(nearEditedLine.getSources()).containsExactly("claude-opus");
        }

        @Test
        void aRuleSiteDoesNotClaimAFindingInAnotherCategoryOrBeyondTheLineWindow() {
            List<ReviewPipelineService.RuleSite> sites =
                    ReviewPipelineService.RuleSite.of(List.of(finding(10, "x", List.of())));
            LineComment far = finding(13, "Far.", List.of("claude-opus"));
            LineComment otherCategory = finding(10, "Other.", List.of("claude-opus"));
            otherCategory.setCategory("security");

            ReviewPipelineService.unattributeUnmatchedRuleFindings(
                    new ReviewResult("", "COMMENT", List.of(far, otherCategory)),
                    List.of(),
                    sites,
                    rules);

            assertThat(List.of(far, otherCategory))
                    .allSatisfy(c -> assertThat(c.getSources()).containsExactly("claude-opus"));
        }

        @Test
        void keepsLabelsOnARuleFindingAReviewerCandidateMatches() {
            LineComment rule = finding(10, "rule: perf", List.of("gpt-5.5"));
            List<ReviewerAttribution.Evidence> evidence =
                    ReviewerAttribution.Evidence.snapshot(
                            List.of(finding(11, "Second.", List.of("gpt-5.5"))));

            ReviewPipelineService.unattributeUnmatchedRuleFindings(
                    new ReviewResult("", "COMMENT", List.of(rule)),
                    evidence,
                    ReviewPipelineService.RuleSite.of(List.of(finding(10, "x", List.of()))),
                    rules);

            assertThat(rule.getSources()).containsExactly("gpt-5.5");
        }

        @Test
        void leavesNonRuleFindingsAndUnknownRuleTagsAlone() {
            LineComment plain = finding(10, "Slow.", List.of("claude-opus"));
            LineComment unknown = finding(20, "Slow. (rule: other)", List.of("claude-opus"));
            LineComment blank = finding(30, null, List.of("claude-opus"));

            ReviewPipelineService.unattributeUnmatchedRuleFindings(
                    new ReviewResult("", "COMMENT", List.of(plain, unknown, blank)),
                    List.of(),
                    List.of(),
                    rules);

            assertThat(List.of(plain, unknown, blank))
                    .allSatisfy(c -> assertThat(c.getSources()).containsExactly("claude-opus"));
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path semanticRoot;

    @Test
    void bothActualProviderAdaptersUseOwnedEvidenceAndRejectFinalInvalidation() throws Exception {
        for (boolean copilot : List.of(false, true)) {
            for (boolean invalidate : List.of(false, true)) {
                var backend = new SemanticReviewServiceTest.Backend(semanticRoot.toRealPath());
                AtomicInteger calls = new AtomicInteger();
                Consumer<PRReviewRequest> primary =
                        req -> {
                            calls.incrementAndGet();
                            assertThat(ReviewPrompts.buildPrompt(req))
                                    .contains(
                                            "<trusted_semantic_review_skills>",
                                                    "<untrusted_semantic_evidence>",
                                            "Main <-", "untrusted text-tree");
                            if (invalidate) backend.physical = "f".repeat(64);
                        };
                // Marker does not define arguments; it remains untrusted text.
                backend.evidence = "Main <- untrusted text-tree";
                ReviewPipelineService pipeline;
                if (copilot) {
                    pipeline =
                            ReviewPipelineService.forCopilot(
                                    new CopilotService() {
                                        @Override
                                        ReviewPassResult reviewPass(
                                                PRReviewRequest req,
                                                String model,
                                                String effort,
                                                Consumer<String> status,
                                                BiConsumer<String, String> chunks,
                                                boolean inherit,
                                                String config) {
                                            assertThat(inherit)
                                                    .as("Deep ignores forced inherited MCP")
                                                    .isFalse();
                                            primary.accept(req);
                                            return ReviewPassResult.withoutLedger(
                                                    new ReviewResult(
                                                            "candidate", "APPROVE", List.of()));
                                        }
                                    },
                                    "fake",
                                    "high",
                                    true,
                                    null);
                } else {
                    pipeline =
                            ReviewPipelineService.forClaude(
                                    new ClaudeService() {
                                        @Override
                                        ReviewPassResult reviewPass(
                                                PRReviewRequest req,
                                                String model,
                                                Consumer<String> status,
                                                BiConsumer<String, String> chunks) {
                                            primary.accept(req);
                                            return ReviewPassResult.withoutLedger(
                                                    new ReviewResult(
                                                            "candidate", "APPROVE", List.of()));
                                        }
                                    },
                                    "fake");
                }
                try (var execution =
                        new SemanticReviewService.Execution(
                                backend.root, backend, () -> {}, SemanticReviewServiceTest.DIFF)) {
                    execution.collect();
                    if (invalidate) {
                        assertThatThrownBy(
                                        () ->
                                                pipeline.review(
                                                        request(SemanticReviewServiceTest.DIFF),
                                                        false,
                                                        false,
                                                        false,
                                                        ignored -> {},
                                                        null,
                                                        execution))
                                .isInstanceOf(IOException.class)
                                .hasMessageContaining("invalidated");
                    } else {
                        assertThat(
                                        pipeline.review(
                                                        request(SemanticReviewServiceTest.DIFF),
                                                        false,
                                                        false,
                                                        false,
                                                        ignored -> {},
                                                        null,
                                                        execution)
                                                .getSummary())
                                .isEqualTo("candidate");
                        assertThat(backend.collections)
                                .isEqualTo(4); // initial/final collection, pre-provider, final
                        // publication
                    }
                    assertThat(calls).hasValue(1);
                }
            }
        }
    }

    @Test
    void failedCritiqueCannotPublishEarlierDeepCandidateAfterAuthorityChanges() throws Exception {
        var backend = new SemanticReviewServiceTest.Backend(semanticRoot.toRealPath());
        var provider =
                new ClaudeService() {
                    @Override
                    ReviewPassResult reviewPass(
                            PRReviewRequest request,
                            String model,
                            Consumer<String> status,
                            BiConsumer<String, String> chunks) {
                        return ReviewPassResult.withoutLedger(
                                new ReviewResult("must not escape", "APPROVE", List.of()));
                    }

                    @Override
                    String completeReviewPrompt(
                            String prompt,
                            String model,
                            Consumer<String> status,
                            long timeout,
                            boolean reads)
                            throws IOException {
                        assertThat(prompt)
                                .contains(
                                        "<trusted_semantic_review_skills>",
                                        "<untrusted_semantic_evidence>");
                        backend.epochs = "settings-edited-and-restored";
                        throw new IOException("Simulated provider failure");
                    }
                };
        try (var execution =
                new SemanticReviewService.Execution(
                        backend.root, backend, () -> {}, SemanticReviewServiceTest.DIFF)) {
            execution.collect();
            assertThatThrownBy(
                            () ->
                                    ReviewPipelineService.forClaude(provider, "fake")
                                            .review(
                                                    request(SemanticReviewServiceTest.DIFF),
                                                    false,
                                                    true,
                                                    false,
                                                    ignored -> {},
                                                    null,
                                                    execution))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("invalidated");
        }
    }

    @Test
    void callerPromptDataCannotGrantExecutionAuthority() {
        var request =
                request(SemanticReviewServiceTest.DIFF)
                        .withSemanticContext(new com.jinloes.prpilot.model.SemanticReviewContext());
        assertThatThrownBy(
                        () ->
                                ReviewPipelineService.forClaude(new ClaudeService(), "fake")
                                        .review(request, false, false, false, ignored -> {}, null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("cannot authorize");
    }

    @Test
    void bothAdaptersPreserveAuthorityThroughEveryStageAndBestEffortFailure() throws Exception {
        for (boolean copilot : List.of(false, true)) {
            for (boolean chunked : List.of(false, true)) {
                // Five actual adapter calls: primary/selection/follow-up/hygiene/critique, or
                // two batch request copies/global reconciliation/hygiene/final-validation critique.
                for (int invalidateAt = -1; invalidateAt < 5; invalidateAt++) {
                    for (boolean providerFailure : List.of(false, true)) {
                        var backend =
                                new SemanticReviewServiceTest.Backend(semanticRoot.toRealPath());
                        backend.expectedRanges = chunked ? 7 : 1;
                        backend.sourcePaths =
                                chunked
                                        ? List.of(
                                                "F0.java", "F1.java", "F2.java", "F3.java",
                                                "F4.java", "F5.java", "F6.java")
                                        : List.of("src/Api.java");
                        String diff = chunked ? sevenFileDiff() : fourRiskyHunks();
                        var stages = new DeepStages(backend, invalidateAt, providerFailure);
                        try (var execution =
                                new SemanticReviewService.Execution(
                                        backend.root, backend, () -> {}, diff)) {
                            execution.collect();
                            var pipeline = stages.pipeline(copilot);
                            if (invalidateAt < 0) {
                                assertThat(
                                                pipeline.review(
                                                        request(diff),
                                                        chunked,
                                                        true,
                                                        !chunked,
                                                        ignored -> {},
                                                        null,
                                                        execution))
                                        .isNotNull();
                                assertThat(stages.calls)
                                        .containsExactlyElementsOf(
                                                chunked
                                                        ? List.of(
                                                                "primary",
                                                                "primary",
                                                                "primary",
                                                                "hygiene",
                                                                "critique")
                                                        : List.of(
                                                                "primary",
                                                                "selection",
                                                                "follow-up",
                                                                "hygiene",
                                                                "critique"));
                            } else {
                                assertThatThrownBy(
                                                () ->
                                                        pipeline.review(
                                                                request(diff),
                                                                chunked,
                                                                true,
                                                                !chunked,
                                                                ignored -> {},
                                                                null,
                                                                execution))
                                        .isInstanceOf(IOException.class);
                                assertThat(stages.calls)
                                        .as("No later provider may consume stale authority")
                                        .hasSize(invalidateAt + 1);
                                assertThatThrownBy(execution::validate)
                                        .isInstanceOf(IOException.class);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void deepReviewKeepsAuthorityThroughTheRulePhase() throws Exception {
        Path rules = Files.createTempDirectory("deep-rules");
        try {
            Files.writeString(
                    rules.resolve("gated.yaml"),
                    "name: gated\ndescription: d\ntrigger: t\nprompt: Check retries.\n");
            for (boolean copilot : List.of(false, true)) {
                for (boolean chunked : List.of(false, true)) {
                    List<String> expected =
                            chunked
                                    ? List.of(
                                            "primary",
                                            "primary",
                                            "primary",
                                            "rule-selection",
                                            "rule",
                                            "hygiene",
                                            "critique")
                                    : List.of(
                                            "primary",
                                            "selection",
                                            "follow-up",
                                            "rule-selection",
                                            "rule",
                                            "hygiene",
                                            "critique");
                    int firstRuleStage = expected.indexOf("rule-selection");
                    for (int invalidateAt = -1;
                            invalidateAt <= firstRuleStage + 1;
                            invalidateAt++) {
                        if (invalidateAt >= 0 && invalidateAt < firstRuleStage) continue;
                        for (boolean providerFailure : List.of(false, true)) {
                            var backend =
                                    new SemanticReviewServiceTest.Backend(
                                            semanticRoot.toRealPath());
                            backend.expectedRanges = chunked ? 7 : 1;
                            backend.sourcePaths =
                                    chunked
                                            ? List.of(
                                                    "F0.java", "F1.java", "F2.java", "F3.java",
                                                    "F4.java", "F5.java", "F6.java")
                                            : List.of("src/Api.java");
                            String diff = chunked ? sevenFileDiff() : fourRiskyHunks();
                            PRReviewRequest request =
                                    request(diff).toBuilder()
                                            .rulesDirectory(rules.toString())
                                            .build();
                            var stages = new DeepStages(backend, invalidateAt, providerFailure);
                            try (var execution =
                                    new SemanticReviewService.Execution(
                                            backend.root, backend, () -> {}, diff)) {
                                execution.collect();
                                var pipeline = stages.pipeline(copilot);
                                if (invalidateAt < 0) {
                                    pipeline.review(
                                            request,
                                            chunked,
                                            true,
                                            !chunked,
                                            ignored -> {},
                                            null,
                                            execution);
                                    assertThat(stages.calls).containsExactlyElementsOf(expected);
                                } else {
                                    assertThatThrownBy(
                                                    () ->
                                                            pipeline.review(
                                                                    request,
                                                                    chunked,
                                                                    true,
                                                                    !chunked,
                                                                    ignored -> {},
                                                                    null,
                                                                    execution))
                                            .isInstanceOf(IOException.class);
                                    assertThat(stages.calls)
                                            .as("No later provider may consume stale authority")
                                            .hasSize(invalidateAt + 1);
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            Files.deleteIfExists(rules.resolve("gated.yaml"));
            Files.deleteIfExists(rules);
        }
    }

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
                        ReviewPrompts.buildRuleReviewPrompt(
                                chunkedService.finalValidationRequest(request), rule);
                assertThat(condensed)
                        .isNotEqualTo(ReviewPrompts.buildRuleReviewPrompt(request, rule));
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
    class RestoreHygieneFindings {
        private final InspectionManifest manifest = InspectionManifest.fromDiff(fileDiff(2));

        @Test
        void restoresADroppedConfirmedFinding() {
            ReviewResult validated = new ReviewResult("ok", "APPROVE", new ArrayList<>());
            ReviewResult hygiene = result(finding("F0.java", 1, "performance", "medium"));

            ReviewResult restored =
                    ReviewPipelineService.restoreHygieneFindings(validated, hygiene, manifest);

            assertThat(restored.getSummary()).isEqualTo("ok");
            assertThat(restored.getVerdict()).isEqualTo("COMMENT");
            assertThat(restored.getLineComments())
                    .singleElement()
                    .extracting(LineComment::getFile)
                    .isEqualTo("F0.java");
        }

        @Test
        void skipsLowConfidenceAndUnanchoredFindings() {
            ReviewResult validated = result();
            ReviewResult hygiene =
                    result(
                            finding("F0.java", 1, "performance", "low"),
                            finding("F0.java", 9, "performance", "high"),
                            finding("Gone.java", 1, "performance", "high"));

            assertThat(ReviewPipelineService.restoreHygieneFindings(validated, hygiene, manifest))
                    .isSameAs(validated);
        }

        @Test
        void treatsANearbySameCategoryFindingAsTheValidatorsRewording() {
            ReviewResult validated =
                    result(
                            finding(
                                    "F1.java",
                                    1 + ReviewPipelineService.HYGIENE_COVER_LINES,
                                    "performance",
                                    "high"));
            ReviewResult hygiene = result(finding("F1.java", 1, "performance", "medium"));

            assertThat(ReviewPipelineService.restoreHygieneFindings(validated, hygiene, manifest))
                    .isSameAs(validated);
        }

        @Test
        void keepsAHygieneFindingNextToAFindingOfAnotherCategory() {
            ReviewResult validated = result(finding("F1.java", 1, "correctness", "high"));
            ReviewResult hygiene = result(finding("F1.java", 1, "maintainability", "medium"));

            assertThat(
                            ReviewPipelineService.restoreHygieneFindings(
                                            validated, hygiene, manifest)
                                    .getLineComments())
                    .extracting(LineComment::getCategory)
                    .containsExactlyInAnyOrder("correctness", "maintainability");
        }

        @Test
        void restoresOnlyOneOfTwoHygieneDuplicatesAtTheSameSite() {
            ReviewResult hygiene =
                    result(
                            finding("F0.java", 1, "performance", "medium"),
                            finding("F0.java", 1, "performance", "high"));

            assertThat(
                            ReviewPipelineService.restoreHygieneFindings(
                                            result(), hygiene, manifest)
                                    .getLineComments())
                    .hasSize(1);
        }

        private static ReviewResult result(LineComment... comments) {
            return new ReviewResult("ok", "COMMENT", new ArrayList<>(List.of(comments)));
        }

        private static LineComment finding(
                String file, int line, String category, String confidence) {
            LineComment comment = new LineComment(file, line, "suggestion", category + " finding");
            comment.setSeverity("minor");
            comment.setCategory(category);
            comment.setConfidence(confidence);
            return comment;
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
