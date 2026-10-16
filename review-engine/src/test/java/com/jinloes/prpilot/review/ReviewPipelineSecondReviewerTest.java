package com.jinloes.prpilot.review;

import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.comment;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.hygiene;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.oneRiskyHunk;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.pass;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.pipeline;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.request;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.result;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.reviewJson;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.reviewJsonWithFinding;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.severity;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.withSecondary;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.ReviewResult;
import com.jinloes.prpilot.review.ReviewPipelineTestSupport.FakeProvider;
import com.jinloes.prpilot.review.ReviewPipelineTestSupport.FakeSecondary;
import com.jinloes.prpilot.review.ReviewPipelineTestSupport.PromptCall;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Second-reviewer tests for {@link ReviewPipelineService}: merging, attribution, cancellation. */
class ReviewPipelineSecondReviewerTest {
    @Nested
    class WithSecondReviewer {
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
        void secondReviewerIsANoOpForABlankModelOrAMissingDirectory() {
            ReviewPipelineService pipeline =
                    ReviewPipelineService.forClaude(new ClaudeService(), "model");

            assertThat(pipeline.withSecondReviewer(" ", "high", null)).isSameAs(pipeline);
            assertThat(pipeline.withSecondReviewer("gpt-5", "high", null)).isSameAs(pipeline);
        }
    }
}
