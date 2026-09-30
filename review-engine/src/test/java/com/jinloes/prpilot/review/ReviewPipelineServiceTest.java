package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.model.ReviewResult;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReviewPipelineServiceTest {
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
                            assertThat(ClaudeService.buildPrompt(req))
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
                // Four actual adapter calls: primary/selection/follow-up/critique, or two
                // batch request copies/global reconciliation/final-validation critique.
                for (int invalidateAt = -1; invalidateAt < 4; invalidateAt++) {
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
                                                                "critique")
                                                        : List.of(
                                                                "primary",
                                                                "selection",
                                                                "follow-up",
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

    private static final class DeepStages {
        final SemanticReviewServiceTest.Backend backend;
        final int invalidateAt;
        final boolean providerFailure;
        final List<String> calls = new ArrayList<>();

        DeepStages(
                SemanticReviewServiceTest.Backend backend,
                int invalidateAt,
                boolean providerFailure) {
            this.backend = backend;
            this.invalidateAt = invalidateAt;
            this.providerFailure = providerFailure;
        }

        void observe(String stage, String prompt) throws IOException {
            assertThat(prompt)
                    .contains(
                            "<trusted_semantic_review_skills>",
                            "<untrusted_semantic_evidence>",
                            "untrusted text-tree");
            // The complete pinned instructions, not merely a marker, must survive each copy.
            assertThat(prompt).contains(SemanticSkillBundle.load().instructions());
            calls.add(stage);
            if (calls.size() - 1 == invalidateAt) {
                backend.epochs = "settings-edited-and-restored";
                if (providerFailure) throw new IOException("Simulated provider-stage failure");
            }
        }

        ReviewPassResult primary(PRReviewRequest request) throws IOException {
            assertThat(request.getSemanticContext()).isNotNull();
            observe("primary", ClaudeService.buildPrompt(request));
            return new ReviewPassResult(
                    new ReviewResult("candidate", "APPROVE", List.of()),
                    new InspectionLedger(true, Set.of(), List.of()));
        }

        String complete(String prompt, boolean reads) throws IOException {
            boolean critique = prompt.contains("<draft_review>");
            observe(critique ? "critique" : reads ? "follow-up" : "selection", prompt);
            return !reads ? "{\"selectedGapIds\":[\"G004\",\"G002\"]}" : emptyReviewJson();
        }

        ReviewPipelineService pipeline(boolean copilot) {
            if (copilot) {
                return ReviewPipelineService.forCopilot(
                        new CopilotService() {
                            @Override
                            ReviewPassResult reviewPass(
                                    PRReviewRequest request,
                                    String model,
                                    String effort,
                                    Consumer<String> status,
                                    BiConsumer<String, String> chunks,
                                    boolean inherit,
                                    String config)
                                    throws IOException {
                                assertThat(inherit).isFalse();
                                return primary(request);
                            }

                            @Override
                            String completeReviewPrompt(
                                    String prompt,
                                    String model,
                                    String effort,
                                    boolean inherit,
                                    String config,
                                    boolean reads,
                                    long timeout,
                                    Consumer<String> status)
                                    throws IOException {
                                assertThat(inherit).isFalse();
                                return complete(prompt, reads);
                            }
                        },
                        "fake",
                        "high",
                        true,
                        null);
            }
            return ReviewPipelineService.forClaude(
                    new ClaudeService() {
                        @Override
                        ReviewPassResult reviewPass(
                                PRReviewRequest request,
                                String model,
                                Consumer<String> status,
                                BiConsumer<String, String> chunks)
                                throws IOException {
                            return primary(request);
                        }

                        @Override
                        String completeReviewPrompt(
                                String prompt,
                                String model,
                                Consumer<String> status,
                                long timeout,
                                boolean reads)
                                throws IOException {
                            return complete(prompt, reads);
                        }
                    },
                    "fake");
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

            ReviewResult result =
                    pipeline.review(request(diff), false, false, true, ignored -> {}, null);

            assertThat(result).isSameAs(baseline);
            assertThat(provider.completeCalls).isEmpty();
        }

        @Test
        void usesOneToolFreePrioritizationCallBeforeOneFollowUpWhenMoreThanThreeGapsExist()
                throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult =
                    new ReviewPassResult(
                            new ReviewResult("baseline", "APPROVE", List.of()),
                            new InspectionLedger(true, Set.of(), List.of()));
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

    private static final class FakeProvider implements ReviewPipelineService.ProviderExecutor {
        private ReviewPassResult primaryResult;
        private final AtomicInteger primaryCalls = new AtomicInteger();
        private final List<String> completions = new ArrayList<>();
        private final List<PromptCall> completeCalls = new ArrayList<>();
        private IOException completionFailure;
        private boolean cancelAfterPrimary;
        private final List<PRReviewRequest> primaryRequests = new ArrayList<>();
        private File projectDir;
        private PrimaryHook beforePrimary = () -> {};
        private String displayModel = "claude-opus";

        @Override
        public String displayModel() {
            return displayModel;
        }

        @Override
        public ReviewPassResult primary(
                PRReviewRequest request,
                Consumer<String> onStatus,
                BiConsumer<String, String> onChunk)
                throws InterruptedException {
            primaryCalls.incrementAndGet();
            primaryRequests.add(request);
            beforePrimary.run();
            return primaryResult;
        }

        @Override
        public File projectDir() {
            return projectDir;
        }

        @Override
        public String complete(
                String prompt,
                long timeoutMillis,
                boolean allowReadTools,
                boolean allowMcp,
                Consumer<String> onStatus)
                throws IOException {
            completeCalls.add(new PromptCall(prompt, timeoutMillis, allowReadTools, allowMcp));
            if (completionFailure != null) {
                throw completionFailure;
            }
            return completions.remove(0);
        }

        @Override
        public void checkCancelled() throws InterruptedException {
            if (cancelAfterPrimary && primaryCalls.get() > 0) {
                throw new InterruptedException("cancelled");
            }
        }
    }

    @FunctionalInterface
    private interface PrimaryHook {
        void run() throws InterruptedException;
    }

    /** A second reviewer that can succeed, fail, or block until it is interrupted. */
    private static final class FakeSecondary implements ReviewPipelineService.ProviderExecutor {
        private ReviewPassResult result;
        private IOException failure;
        private boolean blockUntilInterrupted;
        private CountDownLatch release;
        private final List<PRReviewRequest> requests = new CopyOnWriteArrayList<>();
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch interrupted = new CountDownLatch(1);
        private final AtomicInteger cancels = new AtomicInteger();

        @Override
        public ReviewPassResult primary(
                PRReviewRequest request,
                Consumer<String> onStatus,
                BiConsumer<String, String> onChunk)
                throws IOException, InterruptedException {
            requests.add(request);
            started.countDown();
            if (blockUntilInterrupted) {
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException exception) {
                    interrupted.countDown();
                    throw exception;
                }
            }
            if (release != null) release.await(5, TimeUnit.SECONDS);
            if (failure != null) throw failure;
            return result;
        }

        @Override
        public String complete(
                String prompt,
                long timeoutMillis,
                boolean allowReadTools,
                boolean allowMcp,
                Consumer<String> onStatus) {
            throw new AssertionError("The second reviewer only runs the primary pass");
        }

        @Override
        public void checkCancelled() {}

        void cancel() {
            cancels.incrementAndGet();
        }
    }

    private record PromptCall(
            String prompt, long timeoutMillis, boolean allowReadTools, boolean allowMcp) {}

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
    }

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
                            "Merged reviewers into 2 findings");

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
            assertThat(result.getLineComments()).hasSize(1);
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
                            (dir, sha, manifest, cancellation) -> {
                                assertThat(dir).isEqualTo(semanticRoot.toFile());
                                resolvedShas.add(sha);
                                return new BaseCommitContext.Result(
                                        "## AGENTS.md\nBase rule.",
                                        "## src/Api.java\nabc1234 2026-01-01 Keep it stable",
                                        "## save (declaration changed in src/Api.java)\n"
                                                + "src/Caller.java:7: api.save(x);");
                            })
                    .review(
                            request(oneRiskyHunk()).toBuilder().baseSha(BASE_SHA).build(),
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

            pipeline(provider, (dir, sha, manifest, cancellation) -> BaseCommitContext.Result.EMPTY)
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
                    (dir, sha, manifest, cancellation) -> {
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

    private static PRReviewRequest request(String diff) {
        PullRequest pr =
                new PullRequest(
                        "Change API",
                        "https://example.test/pr/1",
                        "acme",
                        "repo",
                        1,
                        "",
                        "author",
                        "",
                        false);
        return PRReviewRequest.builder(pr, diff).build();
    }

    private static String reviewJsonWithFinding(String file, int line) throws Exception {
        return JSON.writeValueAsString(
                Map.of(
                        "summary",
                        "follow-up",
                        "verdict",
                        "REQUEST_CHANGES",
                        "lineComments",
                        List.of(
                                Map.of(
                                        "file", file,
                                        "line", line,
                                        "type", "issue",
                                        "severity", "major",
                                        "category", "correctness",
                                        "confidence", "high",
                                        "body", "The changed contract breaks its caller.",
                                        "rationale",
                                                "The new signature no longer accepts the required value."))));
    }

    private static String emptyReviewJson() throws IOException {
        return JSON.writeValueAsString(
                Map.of(
                        "summary", "reviewed",
                        "verdict", "APPROVE",
                        "lineComments", List.of()));
    }

    private static String oneRiskyHunk() {
        return """
                diff --git a/src/Api.java b/src/Api.java
                --- a/src/Api.java
                +++ b/src/Api.java
                @@ -1 +1 @@
                -private void call(String value) {}
                +public void call() {}
                """;
    }

    private static String fourRiskyHunks() {
        return """
                diff --git a/src/Api.java b/src/Api.java
                --- a/src/Api.java
                +++ b/src/Api.java
                @@ -1 +1 @@
                -private void one(String value) {}
                +public void one() {}
                @@ -10 +10 @@
                -private void two(String value) {}
                +public void two() {}
                @@ -20 +20 @@
                -private void three(String value) {}
                +public void three() {}
                @@ -30 +30 @@
                -private void four(String value) {}
                +public void four() {}
                """;
    }

    private static String sevenFileDiff() {
        StringBuilder diff = new StringBuilder();
        for (int index = 0; index < 7; index++) {
            diff.append("diff --git a/F")
                    .append(index)
                    .append(".java b/F")
                    .append(index)
                    .append(".java\n")
                    .append("--- a/F")
                    .append(index)
                    .append(".java\n")
                    .append("+++ b/F")
                    .append(index)
                    .append(".java\n")
                    .append("@@ -1 +1 @@\n-old\n+new\n");
        }
        return diff.toString();
    }
}
