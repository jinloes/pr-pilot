package com.jinloes.prpilot.sidecar.pr;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.sidecar.github.GitHubAuthService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DraftReviewMutationServiceSaveLifecycleTest extends DraftReviewMutationServiceTestSupport {
    @Nested
    class Save {
        @Test
        void savesDraftReviewWithoutExistingPendingReview() {
            List<String> paths = new ArrayList<>();
            DraftReviewMutationService.GitHubRestClient client =
                    new DraftReviewMutationService.GitHubRestClient() {
                        @Override
                        public DraftReviewMutationService.RestResponse get(
                                String apiBase, String token, String path) {
                            paths.add("GET " + path);
                            if (isReviewList(path)) {
                                return new DraftReviewMutationService.RestResponse(200, "[]");
                            }

                            return new DraftReviewMutationService.RestResponse(
                                    200, "{\"head\":{\"sha\":\"abc123\"}}");
                        }

                        @Override
                        public DraftReviewMutationService.RestResponse post(
                                String apiBase, String token, String path, String jsonBody) {
                            paths.add("POST " + path);
                            return new DraftReviewMutationService.RestResponse(200, "{\"id\":42}");
                        }

                        @Override
                        public DraftReviewMutationService.RestResponse put(
                                String apiBase, String token, String path, String jsonBody) {
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
            assertThat(result.reviewId()).isEqualTo("42");
            assertThat(result.commentsDropped()).isFalse();
            assertThat(result.toString()).doesNotContain("secret-token");
            assertThat(paths).anyMatch(p -> p.equals("POST /repos/acme/repo/pulls/1/reviews"));
        }

        @Test
        void adoptsAnExactlyMatchingPendingReviewWithoutMutation() throws Exception {
            String body = new DraftReviewCodec(mapper).encodeBody("summary", "APPROVE", List.of());
            String bodyJson = mapper.writeValueAsString(body);
            List<String> methods = new ArrayList<>();
            DraftReviewMutationService.GitHubRestClient client =
                    new DraftReviewMutationService.GitHubRestClient() {
                        @Override
                        public DraftReviewMutationService.RestResponse get(
                                String apiBase, String token, String path) {
                            methods.add("GET " + path);
                            if (isReviewList(path)) {
                                return new DraftReviewMutationService.RestResponse(
                                        200,
                                        "[{\"id\":17,\"state\":\"PENDING\",\"commit_id\":\"abc123\",\"body\":"
                                                + bodyJson
                                                + "}]");
                            }
                            return new DraftReviewMutationService.RestResponse(
                                    200, "{\"head\":{\"sha\":\"abc123\"}}");
                        }

                        @Override
                        public DraftReviewMutationService.RestResponse post(
                                String apiBase, String token, String path, String jsonBody) {
                            throw new AssertionError("matching review should not be recreated");
                        }

                        @Override
                        public DraftReviewMutationService.RestResponse put(
                                String apiBase, String token, String path, String jsonBody) {
                            throw new AssertionError("matching review should not be updated");
                        }

                        @Override
                        public DraftReviewMutationService.RestResponse delete(
                                String apiBase, String token, String path) {
                            throw new AssertionError("matching review should not be deleted");
                        }
                    };

            DraftReviewMutationResult result =
                    new DraftReviewMutationService(
                                    ignored -> GitHubAuthService.TokenResolution.resolved("token"),
                                    client,
                                    mapper)
                            .save(saveParams(List.of(), List.of()));

            assertThat(result.status()).isEqualTo("ok");
            assertThat(result.reviewId()).isEqualTo("17");
            assertThat(methods)
                    .containsExactly(
                            "GET /repos/acme/repo/pulls/1",
                            "GET /repos/acme/repo/pulls/1/reviews?per_page=100&page=1");
        }

        @Test
        void findsAnExactlyMatchingPendingReviewOnTheSecondPage() throws Exception {
            String body = new DraftReviewCodec(mapper).encodeBody("summary", "APPROVE", List.of());
            var firstPage = mapper.createArrayNode();
            for (int index = 0; index < DraftReviewMutationService.PAGE_SIZE; index++) {
                firstPage.addObject().put("id", index + 1).put("state", "COMMENTED");
            }
            AtomicInteger reviewPages = new AtomicInteger();
            DraftReviewMutationService.GitHubRestClient client =
                    new DraftReviewMutationService.GitHubRestClient() {
                        @Override
                        public DraftReviewMutationService.RestResponse get(
                                String apiBase, String token, String path) {
                            if (!isReviewList(path)) {
                                return new DraftReviewMutationService.RestResponse(
                                        200, "{\"head\":{\"sha\":\"abc123\"}}");
                            }
                            reviewPages.incrementAndGet();
                            if (path.endsWith("page=1")) {
                                return new DraftReviewMutationService.RestResponse(
                                        200, firstPage.toString());
                            }
                            var pending = mapper.createArrayNode();
                            pending.addObject()
                                    .put("id", 777)
                                    .put("state", "PENDING")
                                    .put("commit_id", "abc123")
                                    .put("body", body);
                            return new DraftReviewMutationService.RestResponse(
                                    200, pending.toString());
                        }

                        @Override
                        public DraftReviewMutationService.RestResponse post(
                                String apiBase, String token, String path, String jsonBody) {
                            throw new AssertionError("matching review should not be recreated");
                        }

                        @Override
                        public DraftReviewMutationService.RestResponse put(
                                String apiBase, String token, String path, String jsonBody) {
                            throw new AssertionError("matching review should not be updated");
                        }

                        @Override
                        public DraftReviewMutationService.RestResponse delete(
                                String apiBase, String token, String path) {
                            throw new AssertionError("matching review should not be deleted");
                        }
                    };

            DraftReviewMutationResult result =
                    new DraftReviewMutationService(
                                    ignored -> GitHubAuthService.TokenResolution.resolved("token"),
                                    client,
                                    mapper)
                            .save(saveParams(List.of(), List.of()));

            assertThat(result.status()).isEqualTo("ok");
            assertThat(result.reviewId()).isEqualTo("777");
            assertThat(reviewPages).hasValue(2);
        }

        @Test
        void recreatesAnEqualBodyWhenPendingReviewTargetsAnOlderCommit() throws Exception {
            String body = new DraftReviewCodec(mapper).encodeBody("summary", "APPROVE", List.of());
            List<String> operations = new ArrayList<>();
            DraftReviewMutationService.GitHubRestClient client =
                    new DraftReviewMutationService.GitHubRestClient() {
                        @Override
                        public DraftReviewMutationService.RestResponse get(
                                String apiBase, String token, String path) {
                            operations.add("GET " + path);
                            if (isReviewList(path)) {
                                var pending = mapper.createArrayNode();
                                pending.addObject()
                                        .put("id", 17)
                                        .put("state", "PENDING")
                                        .put("commit_id", "old-sha")
                                        .put("body", body);
                                return new DraftReviewMutationService.RestResponse(
                                        200, pending.toString());
                            }
                            return new DraftReviewMutationService.RestResponse(
                                    200, "{\"head\":{\"sha\":\"abc123\"}}");
                        }

                        @Override
                        public DraftReviewMutationService.RestResponse post(
                                String apiBase, String token, String path, String jsonBody) {
                            operations.add("POST " + path);
                            return new DraftReviewMutationService.RestResponse(201, "{\"id\":18}");
                        }

                        @Override
                        public DraftReviewMutationService.RestResponse put(
                                String apiBase, String token, String path, String jsonBody) {
                            throw new AssertionError("stale review cannot be retargeted with PUT");
                        }

                        @Override
                        public DraftReviewMutationService.RestResponse delete(
                                String apiBase, String token, String path) {
                            operations.add("DELETE " + path);
                            return new DraftReviewMutationService.RestResponse(204, "");
                        }
                    };

            DraftReviewMutationResult result =
                    new DraftReviewMutationService(
                                    ignored -> GitHubAuthService.TokenResolution.resolved("token"),
                                    client,
                                    mapper)
                            .save(saveParams(List.of(), List.of()));

            assertThat(result.reviewId()).isEqualTo("18");
            assertThat(operations)
                    .containsSubsequence(
                            "DELETE /repos/acme/repo/pulls/1/reviews/17",
                            "POST /repos/acme/repo/pulls/1/reviews");
        }

        @Test
        void failsClosedAtThePendingReviewPaginationCap() {
            var fullPage = mapper.createArrayNode();
            for (int index = 0; index < DraftReviewMutationService.PAGE_SIZE; index++) {
                fullPage.addObject().put("id", index + 1).put("state", "COMMENTED");
            }
            AtomicInteger reviewPages = new AtomicInteger();
            DraftReviewMutationService.GitHubRestClient client =
                    new DraftReviewMutationService.GitHubRestClient() {
                        @Override
                        public DraftReviewMutationService.RestResponse get(
                                String apiBase, String token, String path) {
                            if (isReviewList(path)) {
                                reviewPages.incrementAndGet();
                                return new DraftReviewMutationService.RestResponse(
                                        200, fullPage.toString());
                            }
                            return new DraftReviewMutationService.RestResponse(
                                    200, "{\"head\":{\"sha\":\"abc123\"}}");
                        }

                        @Override
                        public DraftReviewMutationService.RestResponse post(
                                String apiBase, String token, String path, String jsonBody) {
                            throw new AssertionError("pagination failure must not mutate GitHub");
                        }

                        @Override
                        public DraftReviewMutationService.RestResponse put(
                                String apiBase, String token, String path, String jsonBody) {
                            throw new AssertionError("pagination failure must not mutate GitHub");
                        }

                        @Override
                        public DraftReviewMutationService.RestResponse delete(
                                String apiBase, String token, String path) {
                            throw new AssertionError("pagination failure must not mutate GitHub");
                        }
                    };

            DraftReviewMutationResult result =
                    new DraftReviewMutationService(
                                    ignored -> GitHubAuthService.TokenResolution.resolved("token"),
                                    client,
                                    mapper)
                            .save(saveParams(List.of(), List.of()));

            assertThat(result.status()).isEqualTo("api_failed");
            assertThat(result.recoveryRequired()).isTrue();
            assertThat(reviewPages).hasValue(DraftReviewMutationService.MAX_REVIEW_PAGES);
        }

        @Test
        void readsHeadAndPendingStateBeforeDeletingAReplacement() {
            List<String> operations = new ArrayList<>();
            DraftReviewMutationService.GitHubRestClient client =
                    new DraftReviewMutationService.GitHubRestClient() {
                        @Override
                        public DraftReviewMutationService.RestResponse get(
                                String apiBase, String token, String path) {
                            operations.add("GET " + path);
                            if (isReviewList(path)) {
                                return new DraftReviewMutationService.RestResponse(
                                        200,
                                        "[{\"id\":9,\"state\":\"PENDING\",\"body\":\"external draft\"}]");
                            }
                            return new DraftReviewMutationService.RestResponse(
                                    200, "{\"head\":{\"sha\":\"abc123\"}}");
                        }

                        @Override
                        public DraftReviewMutationService.RestResponse post(
                                String apiBase, String token, String path, String jsonBody) {
                            operations.add("POST " + path);
                            return new DraftReviewMutationService.RestResponse(200, "{\"id\":10}");
                        }

                        @Override
                        public DraftReviewMutationService.RestResponse put(
                                String apiBase, String token, String path, String jsonBody) {
                            return new DraftReviewMutationService.RestResponse(200, "{}");
                        }

                        @Override
                        public DraftReviewMutationService.RestResponse delete(
                                String apiBase, String token, String path) {
                            operations.add("DELETE " + path);
                            return new DraftReviewMutationService.RestResponse(204, "");
                        }
                    };

            DraftReviewMutationResult result =
                    new DraftReviewMutationService(
                                    ignored -> GitHubAuthService.TokenResolution.resolved("token"),
                                    client,
                                    mapper)
                            .save(saveParams(List.of(), List.of()));

            assertThat(result.status()).isEqualTo("ok");
            assertThat(operations)
                    .containsExactly(
                            "GET /repos/acme/repo/pulls/1",
                            "GET /repos/acme/repo/pulls/1/reviews?per_page=100&page=1",
                            "DELETE /repos/acme/repo/pulls/1/reviews/9",
                            "POST /repos/acme/repo/pulls/1/reviews");
        }
    }
}
