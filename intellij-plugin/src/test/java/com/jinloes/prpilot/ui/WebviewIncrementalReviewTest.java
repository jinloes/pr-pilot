package com.jinloes.prpilot.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.sidecar.pr.IncrementalDiffResult;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class WebviewIncrementalReviewTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Nested
    class IncrementalReview {

        private static final String PR_DIFF = "diff --git a/A.java b/A.java\n@@ -1 +1 @@\n-a\n+b\n";
        private static final String SINCE_DIFF =
                "diff --git a/B.java b/B.java\n@@ -1 +1 @@\n-c\n+d\n";
        private static final String BASELINE = "a".repeat(40);

        private static IncrementalDiffResult ok(String scope, String reason, String diff) {
            return new IncrementalDiffResult(
                    "ok",
                    "loaded",
                    scope,
                    reason,
                    diff == null ? null : BASELINE,
                    diff == null ? null : "b".repeat(40),
                    diff,
                    false,
                    0);
        }

        @Test
        void ordinaryGenerationNeverFetchesTheIncrementalDiff() throws Exception {
            AtomicInteger fetches = new AtomicInteger();

            var input =
                    WebviewReviewController.resolveReviewInput(
                            false,
                            PR_DIFF,
                            () -> {
                                fetches.incrementAndGet();
                                return ok(
                                        IncrementalDiffResult.SCOPE_INCREMENTAL, null, SINCE_DIFF);
                            });

            assertThat(fetches).hasValue(0);
            assertThat(input)
                    .isEqualTo(new WebviewReviewController.ReviewInput(PR_DIFF, null, null));
        }

        @Test
        void incrementalScopeReviewsOnlyTheNewCommitsAndRecordsTheBaseline() throws Exception {
            var input =
                    WebviewReviewController.resolveReviewInput(
                            true,
                            PR_DIFF,
                            () -> ok(IncrementalDiffResult.SCOPE_INCREMENTAL, null, SINCE_DIFF));

            assertThat(input.modelDiff()).isEqualTo(SINCE_DIFF);
            assertThat(input.incrementalBaselineSha()).isEqualTo(BASELINE);
            assertThat(input.reviewScope())
                    .isEqualTo(
                            new WebviewBridgeMessages.ReviewScopeDto(
                                    "incremental", BASELINE, null));
        }

        @Test
        void fullScopeFallbackReviewsThePrDiffAndRecordsTheReason() throws Exception {
            var input =
                    WebviewReviewController.resolveReviewInput(
                            true,
                            PR_DIFF,
                            () ->
                                    ok(
                                            IncrementalDiffResult.SCOPE_FULL,
                                            IncrementalDiffResult.BASELINE_NOT_IN_HISTORY,
                                            null));

            assertThat(input.modelDiff()).isEqualTo(PR_DIFF);
            assertThat(input.incrementalBaselineSha()).isNull();
            assertThat(input.reviewScope())
                    .isEqualTo(
                            new WebviewBridgeMessages.ReviewScopeDto(
                                    "full", null, IncrementalDiffResult.BASELINE_NOT_IN_HISTORY));
        }

        @Test
        void aFailedFetchPropagatesSoTheCallerReportsAReviewError() {
            IOException failure = new IOException("rate limited");

            assertThatThrownBy(
                            () ->
                                    WebviewReviewController.resolveReviewInput(
                                            true,
                                            PR_DIFF,
                                            () -> {
                                                throw failure;
                                            }))
                    .isSameAs(failure);
        }

        @Test
        void publishedDiffsStayPrDiffsAndTheScopeIsSerialized() {
            var input =
                    new WebviewReviewController.ReviewInput(
                            SINCE_DIFF,
                            BASELINE,
                            WebviewBridgeMessages.ReviewScopeDto.incremental(BASELINE));

            var json =
                    MAPPER.valueToTree(
                            new WebviewBridgeMessages.ReviewResultMsg(
                                    "reviewResult",
                                    "acme/widget#42",
                                    null,
                                    PR_DIFF,
                                    PR_DIFF,
                                    input.reviewScope()));

            assertThat(json.path("diff").asText()).isEqualTo(PR_DIFF);
            assertThat(json.path("validationDiff").asText()).isEqualTo(PR_DIFF);
            assertThat(json.path("reviewScope").path("kind").asText()).isEqualTo("incremental");
            assertThat(json.path("reviewScope").path("baselineSha").asText()).isEqualTo(BASELINE);
            assertThat(json.path("reviewScope").has("fallbackReason")).isFalse();
        }

        @Test
        void fullScopeSerializesOnlyTheReason() {
            var json =
                    MAPPER.valueToTree(
                            new WebviewBridgeMessages.ReviewResultMsg(
                                    "reviewResult",
                                    "acme/widget#42",
                                    null,
                                    PR_DIFF,
                                    PR_DIFF,
                                    WebviewBridgeMessages.ReviewScopeDto.full(
                                            IncrementalDiffResult.UP_TO_DATE)));

            assertThat(json.path("reviewScope").path("kind").asText()).isEqualTo("full");
            assertThat(json.path("reviewScope").path("fallbackReason").asText())
                    .isEqualTo(IncrementalDiffResult.UP_TO_DATE);
            assertThat(json.path("reviewScope").has("baselineSha")).isFalse();
        }

        @Test
        void ordinaryResultOmitsTheScope() {
            var json =
                    MAPPER.valueToTree(
                            new WebviewBridgeMessages.ReviewResultMsg(
                                    "reviewResult",
                                    "acme/widget#42",
                                    null,
                                    PR_DIFF,
                                    PR_DIFF,
                                    null));

            assertThat(json.has("reviewScope")).isFalse();
        }

        @Test
        void onlyAJsonTrueOptsIntoIncremental() {
            assertThat(
                            WebviewBridgeHandler.isIncrementalRequest(
                                    MAPPER.createObjectNode().put("incremental", true)))
                    .isTrue();
            assertThat(
                            WebviewBridgeHandler.isIncrementalRequest(
                                    MAPPER.createObjectNode().put("incremental", false)))
                    .isFalse();
            assertThat(
                            WebviewBridgeHandler.isIncrementalRequest(
                                    MAPPER.createObjectNode().put("incremental", "true")))
                    .isFalse();
            assertThat(WebviewBridgeHandler.isIncrementalRequest(MAPPER.createObjectNode()))
                    .isFalse();
        }
    }
}
