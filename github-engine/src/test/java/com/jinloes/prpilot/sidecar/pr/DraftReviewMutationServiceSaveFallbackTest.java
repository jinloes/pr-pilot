package com.jinloes.prpilot.sidecar.pr;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.sidecar.github.GitHubAuthService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DraftReviewMutationServiceSaveFallbackTest extends DraftReviewMutationServiceTestSupport {
    @Nested
    class Save {
        @Test
        void fallsBackToBodyOnlyReviewWhenCommentsAre422() throws Exception {
            AtomicInteger commentAttempts = new AtomicInteger();
            List<String> createdBodies = new ArrayList<>();
            List<String> updatedBodies = new ArrayList<>();
            DraftReviewMutationService.GitHubRestClient client =
                    new DraftReviewMutationService.GitHubRestClient() {
                        @Override
                        public DraftReviewMutationService.RestResponse get(
                                String apiBase, String token, String path) {
                            if (isReviewList(path)) {
                                return new DraftReviewMutationService.RestResponse(200, "[]");
                            }
                            return new DraftReviewMutationService.RestResponse(
                                    200, "{\"head\":{\"sha\":\"abc123\"}}");
                        }

                        @Override
                        public DraftReviewMutationService.RestResponse post(
                                String apiBase, String token, String path, String jsonBody) {
                            if (path.endsWith("/comments")) {
                                commentAttempts.incrementAndGet();
                                return new DraftReviewMutationService.RestResponse(422, "{}");
                            }
                            if (path.endsWith("/reviews") && jsonBody.contains("\"comments\":[]")) {
                                createdBodies.add(jsonBody);
                                return new DraftReviewMutationService.RestResponse(
                                        200, "{\"id\":7}");
                            }
                            return new DraftReviewMutationService.RestResponse(422, "{}");
                        }

                        @Override
                        public DraftReviewMutationService.RestResponse put(
                                String apiBase, String token, String path, String jsonBody) {
                            updatedBodies.add(jsonBody);
                            return new DraftReviewMutationService.RestResponse(200, "{}");
                        }

                        @Override
                        public DraftReviewMutationService.RestResponse delete(
                                String apiBase, String token, String path) {
                            return new DraftReviewMutationService.RestResponse(200, "{}");
                        }
                    };
            DraftReviewMutationService service =
                    new DraftReviewMutationService(
                            ignored -> GitHubAuthService.TokenResolution.resolved("secret-token"),
                            client,
                            mapper);

            DraftReviewMutationResult result =
                    service.save(
                            saveParams(
                                    List.of(
                                            new DraftReviewMutationService.CommentInput(
                                                    "a.java", 3, "issue", "fix", null, null, null,
                                                    null, null)),
                                    List.of()));

            assertThat(result.status()).isEqualTo("ok");
            assertThat(result.reviewId()).isEqualTo("7");
            assertThat(result.commentsDropped()).isTrue();
            assertThat(result.recoveryRequired()).isFalse();
            assertThat(commentAttempts.get()).isZero();
            assertThat(createdBodies).hasSize(1);
            String updatedBody = mapper.readTree(createdBodies.get(0)).path("body").asText();
            assertThat(updatedBody).contains("- `a.java:3`: fix");
            DraftReviewCodec.DecodedReview decoded =
                    new DraftReviewCodec(mapper).decode(updatedBody, List.of());
            assertThat(decoded.lineComments())
                    .extracting(DraftReviewCodec.LineComment::body)
                    .containsExactly("fix");
            assertThat(updatedBodies).isEmpty();
        }

        @Test
        void preservesEveryCommentInBodyOnlyFallback() throws Exception {
            List<String> createdBodies = new ArrayList<>();
            List<String> updatedBodies = new ArrayList<>();
            DraftReviewMutationService.GitHubRestClient client =
                    new DraftReviewMutationService.GitHubRestClient() {
                        @Override
                        public DraftReviewMutationService.RestResponse get(
                                String apiBase, String token, String path) {
                            return isReviewList(path)
                                    ? new DraftReviewMutationService.RestResponse(200, "[]")
                                    : new DraftReviewMutationService.RestResponse(
                                            200, "{\"head\":{\"sha\":\"abc123\"}}");
                        }

                        @Override
                        public DraftReviewMutationService.RestResponse post(
                                String apiBase, String token, String path, String jsonBody) {
                            if (path.endsWith("/comments")) {
                                return jsonBody.contains("bad")
                                        ? new DraftReviewMutationService.RestResponse(422, "{}")
                                        : new DraftReviewMutationService.RestResponse(201, "{}");
                            }
                            createdBodies.add(jsonBody);
                            return jsonBody.contains("\"comments\":[]")
                                    ? new DraftReviewMutationService.RestResponse(201, "{\"id\":7}")
                                    : new DraftReviewMutationService.RestResponse(422, "{}");
                        }

                        @Override
                        public DraftReviewMutationService.RestResponse put(
                                String apiBase, String token, String path, String jsonBody) {
                            updatedBodies.add(jsonBody);
                            return new DraftReviewMutationService.RestResponse(200, "{}");
                        }

                        @Override
                        public DraftReviewMutationService.RestResponse delete(
                                String apiBase, String token, String path) {
                            return new DraftReviewMutationService.RestResponse(200, "{}");
                        }
                    };
            DraftReviewMutationService service =
                    new DraftReviewMutationService(
                            ignored -> GitHubAuthService.TokenResolution.resolved("secret-token"),
                            client,
                            mapper);

            DraftReviewMutationResult result =
                    service.save(
                            saveParams(
                                    List.of(
                                            new DraftReviewMutationService.CommentInput(
                                                    "good.java",
                                                    3,
                                                    "issue",
                                                    "good",
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    null),
                                            new DraftReviewMutationService.CommentInput(
                                                    "bad.java",
                                                    4,
                                                    "issue",
                                                    "bad",
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    null)),
                                    List.of()));

            assertThat(result.status()).isEqualTo("ok");
            assertThat(result.commentsDropped()).isTrue();
            assertThat(createdBodies).hasSize(2);
            assertThat(
                            new DraftReviewCodec(mapper)
                                    .decode(
                                            mapper.readTree(createdBodies.get(1))
                                                    .path("body")
                                                    .asText(),
                                            List.of())
                                    .lineComments())
                    .extracting(DraftReviewCodec.LineComment::body)
                    .containsExactly("good", "bad");
            assertThat(mapper.readTree(createdBodies.get(1)).path("body").asText())
                    .contains("good.java:3", "bad.java:4");
            assertThat(updatedBodies).isEmpty();
        }

        @Test
        void marksFailedReplacementAsLocallyRecoverable() {
            List<String> deletedPaths = new ArrayList<>();
            DraftReviewMutationService.GitHubRestClient client =
                    new DraftReviewMutationService.GitHubRestClient() {
                        @Override
                        public DraftReviewMutationService.RestResponse get(
                                String apiBase, String token, String path) {
                            return isReviewList(path)
                                    ? new DraftReviewMutationService.RestResponse(
                                            200,
                                            "[{\"id\":9,\"state\":\"PENDING\",\"body\":\"old\"}]")
                                    : new DraftReviewMutationService.RestResponse(
                                            200, "{\"head\":{\"sha\":\"abc123\"}}");
                        }

                        @Override
                        public DraftReviewMutationService.RestResponse post(
                                String apiBase, String token, String path, String jsonBody) {
                            return new DraftReviewMutationService.RestResponse(500, "{}");
                        }

                        @Override
                        public DraftReviewMutationService.RestResponse put(
                                String apiBase, String token, String path, String jsonBody) {
                            throw new AssertionError("finalization should not run");
                        }

                        @Override
                        public DraftReviewMutationService.RestResponse delete(
                                String apiBase, String token, String path) {
                            deletedPaths.add(path);
                            return new DraftReviewMutationService.RestResponse(204, "");
                        }
                    };
            DraftReviewMutationService service =
                    new DraftReviewMutationService(
                            ignored -> GitHubAuthService.TokenResolution.resolved("secret-token"),
                            client,
                            mapper);

            DraftReviewMutationResult result =
                    service.save(
                            saveParams(
                                    List.of(
                                            new DraftReviewMutationService.CommentInput(
                                                    "a.java",
                                                    3,
                                                    "issue",
                                                    "unexpected",
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    null)),
                                    List.of()));

            assertThat(result.status()).isEqualTo("api_failed");
            assertThat(result.recoveryRequired()).isTrue();
            assertThat(deletedPaths).containsExactly("/repos/acme/repo/pulls/1/reviews/9");
        }

        @Test
        void updatesBodyInPlaceWhenCommentsAreUnchanged() {
            List<String> putPaths = new ArrayList<>();
            DraftReviewCodec codec = new DraftReviewCodec(mapper);
            String oldBody =
                    codec.encodeBody(
                            "old summary",
                            "APPROVE",
                            List.of(
                                    new DraftReviewCodec.LineComment(
                                            "a.java", 3, "issue", "fix", null, null, null, null,
                                            null)));
            DraftReviewMutationService.GitHubRestClient client =
                    new DraftReviewMutationService.GitHubRestClient() {
                        @Override
                        public DraftReviewMutationService.RestResponse get(
                                String apiBase, String token, String path) {
                            if (isReviewList(path)) {
                                try {
                                    return new DraftReviewMutationService.RestResponse(
                                            200,
                                            mapper.writeValueAsString(
                                                    List.of(
                                                            java.util.Map.of(
                                                                    "id",
                                                                    7,
                                                                    "state",
                                                                    "PENDING",
                                                                    "commit_id",
                                                                    "abc123",
                                                                    "body",
                                                                    oldBody))));
                                } catch (Exception exception) {
                                    throw new AssertionError(exception);
                                }
                            }
                            return new DraftReviewMutationService.RestResponse(
                                    200, "{\"head\":{\"sha\":\"abc123\"}}");
                        }

                        @Override
                        public DraftReviewMutationService.RestResponse post(
                                String apiBase, String token, String path, String jsonBody) {
                            throw new AssertionError("body-only update should not create a review");
                        }

                        @Override
                        public DraftReviewMutationService.RestResponse put(
                                String apiBase, String token, String path, String jsonBody) {
                            putPaths.add(path);
                            return new DraftReviewMutationService.RestResponse(200, "{}");
                        }

                        @Override
                        public DraftReviewMutationService.RestResponse delete(
                                String apiBase, String token, String path) {
                            throw new AssertionError("body-only update should not delete a review");
                        }
                    };
            DraftReviewMutationService service =
                    new DraftReviewMutationService(
                            ignored -> GitHubAuthService.TokenResolution.resolved("secret-token"),
                            client,
                            mapper);

            DraftReviewMutationResult result =
                    service.save(
                            saveParams(
                                    List.of(
                                            new DraftReviewMutationService.CommentInput(
                                                    "a.java", 3, "issue", "fix", null, null, null,
                                                    null, null)),
                                    List.of()));

            assertThat(result.status()).isEqualTo("ok");
            assertThat(result.reviewId()).isEqualTo("7");
            assertThat(putPaths).containsExactly("/repos/acme/repo/pulls/1/reviews/7");
        }
    }
}
