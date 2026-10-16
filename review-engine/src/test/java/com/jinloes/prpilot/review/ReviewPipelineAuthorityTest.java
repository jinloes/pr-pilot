package com.jinloes.prpilot.review;

import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.fourRiskyHunks;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.request;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.sevenFileDiff;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.ReviewResult;
import com.jinloes.prpilot.review.ReviewPipelineTestSupport.DeepStages;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewPipelineAuthorityTest {
    @TempDir Path semanticRoot;

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
}
