package com.jinloes.prpilot.sidecar.pr;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.sidecar.github.GitHubAuthService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class DraftReviewMutationServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private static boolean isReviewList(String path) {
        return path.contains("/reviews?per_page=100&page=");
    }

    private DraftReviewMutationService.SaveParams saveParams(
            List<DraftReviewMutationService.CommentInput> comments,
            List<DraftReviewMutationService.CommentInput> orphans) {
        return new DraftReviewMutationService.SaveParams(
                "https://github.com", "acme", "repo", 1, "summary", "APPROVE", comments, orphans);
    }

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
                                                null)),
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
                        return new DraftReviewMutationService.RestResponse(200, pending.toString());
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
                            return new DraftReviewMutationService.RestResponse(200, "{\"id\":7}");
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
                                                null)),
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
                                                null),
                                        new DraftReviewMutationService.CommentInput(
                                                "bad.java",
                                                4,
                                                "issue",
                                                "bad",
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
                                        mapper.readTree(createdBodies.get(1)).path("body").asText(),
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
                                        200, "[{\"id\":9,\"state\":\"PENDING\",\"body\":\"old\"}]")
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
                                        "a.java", 3, "issue", "fix", null, null, null, null)));
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
                                                null)),
                                List.of()));

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.reviewId()).isEqualTo("7");
        assertThat(putPaths).containsExactly("/repos/acme/repo/pulls/1/reviews/7");
    }

    @Test
    void retriesOnlyIdempotentHttpMethods() {
        assertThat(DraftReviewMutationService.isRetryableHttpMethod("GET")).isTrue();
        assertThat(DraftReviewMutationService.isRetryableHttpMethod("PUT")).isTrue();
        assertThat(DraftReviewMutationService.isRetryableHttpMethod("DELETE")).isTrue();
        assertThat(DraftReviewMutationService.isRetryableHttpMethod("POST")).isFalse();
    }

    @Test
    void submitsReviewWithDefaultBodyWhenBlank() throws Exception {
        List<String> bodies = new ArrayList<>();
        DraftReviewMutationService.GitHubRestClient client =
                new DraftReviewMutationService.GitHubRestClient() {
                    @Override
                    public DraftReviewMutationService.RestResponse get(
                            String apiBase, String token, String path) {
                        return new DraftReviewMutationService.RestResponse(200, "{}");
                    }

                    @Override
                    public DraftReviewMutationService.RestResponse post(
                            String apiBase, String token, String path, String jsonBody) {
                        bodies.add(jsonBody);
                        return new DraftReviewMutationService.RestResponse(200, "{}");
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
                service.submit(
                        new DraftReviewMutationService.SubmitParams(
                                "https://github.com",
                                "acme",
                                "repo",
                                1,
                                "42",
                                "REQUEST_CHANGES",
                                ""));

        assertThat(result.status()).isEqualTo("ok");
        assertThat(bodies).hasSize(1);
        assertThat(mapper.readTree(bodies.get(0)).path("body").textValue())
                .isEqualTo("Requesting changes.");
    }

    @Test
    void rejectsInvalidSubmitEvent() {
        DraftReviewMutationService service =
                new DraftReviewMutationService(
                        ignored -> GitHubAuthService.TokenResolution.resolved("secret-token"),
                        new DraftReviewMutationService.GitHubRestClient() {
                            @Override
                            public DraftReviewMutationService.RestResponse get(
                                    String apiBase, String token, String path) {
                                throw new AssertionError("should not be called");
                            }

                            @Override
                            public DraftReviewMutationService.RestResponse post(
                                    String apiBase, String token, String path, String jsonBody) {
                                throw new AssertionError("should not be called");
                            }

                            @Override
                            public DraftReviewMutationService.RestResponse put(
                                    String apiBase, String token, String path, String jsonBody) {
                                throw new AssertionError("should not be called");
                            }

                            @Override
                            public DraftReviewMutationService.RestResponse delete(
                                    String apiBase, String token, String path) {
                                throw new AssertionError("should not be called");
                            }
                        },
                        mapper);

        DraftReviewMutationResult result =
                service.submit(
                        new DraftReviewMutationService.SubmitParams(
                                "https://github.com", "acme", "repo", 1, "42", "BOGUS", "hi"));

        assertThat(result.status()).isEqualTo("invalid_request");
    }

    @Test
    void deletesDraftReview() {
        List<String> paths = new ArrayList<>();
        DraftReviewMutationService.GitHubRestClient client =
                new DraftReviewMutationService.GitHubRestClient() {
                    @Override
                    public DraftReviewMutationService.RestResponse get(
                            String apiBase, String token, String path) {
                        throw new AssertionError("should not be called");
                    }

                    @Override
                    public DraftReviewMutationService.RestResponse post(
                            String apiBase, String token, String path, String jsonBody) {
                        throw new AssertionError("should not be called");
                    }

                    @Override
                    public DraftReviewMutationService.RestResponse put(
                            String apiBase, String token, String path, String jsonBody) {
                        throw new AssertionError("should not be called");
                    }

                    @Override
                    public DraftReviewMutationService.RestResponse delete(
                            String apiBase, String token, String path) {
                        paths.add(path);
                        return new DraftReviewMutationService.RestResponse(204, "");
                    }
                };
        DraftReviewMutationService service =
                new DraftReviewMutationService(
                        ignored -> GitHubAuthService.TokenResolution.resolved("secret-token"),
                        client,
                        mapper);

        DraftReviewMutationResult result =
                service.delete(
                        new DraftReviewMutationService.DeleteParams(
                                "https://github.com", "acme", "repo", 1, "42"));

        assertThat(result.status()).isEqualTo("ok");
        assertThat(paths).containsExactly("/repos/acme/repo/pulls/1/reviews/42");
    }

    @Test
    void mapsNotAuthenticatedWithoutCallingClient() {
        DraftReviewMutationService.GitHubRestClient client =
                new DraftReviewMutationService.GitHubRestClient() {
                    @Override
                    public DraftReviewMutationService.RestResponse get(
                            String apiBase, String token, String path) {
                        throw new AssertionError("should not be called");
                    }

                    @Override
                    public DraftReviewMutationService.RestResponse post(
                            String apiBase, String token, String path, String jsonBody) {
                        throw new AssertionError("should not be called");
                    }

                    @Override
                    public DraftReviewMutationService.RestResponse put(
                            String apiBase, String token, String path, String jsonBody) {
                        throw new AssertionError("should not be called");
                    }

                    @Override
                    public DraftReviewMutationService.RestResponse delete(
                            String apiBase, String token, String path) {
                        throw new AssertionError("should not be called");
                    }
                };
        DraftReviewMutationService service =
                new DraftReviewMutationService(
                        ignored -> GitHubAuthService.TokenResolution.notAuthenticated(),
                        client,
                        mapper);

        DraftReviewMutationResult result =
                service.delete(
                        new DraftReviewMutationService.DeleteParams(
                                "https://github.com", "acme", "repo", 1, "42"));

        assertThat(result.status()).isEqualTo("not_authenticated");
    }

    @Test
    void rejectsInvalidPullRequestIdentity() {
        DraftReviewMutationService service =
                new DraftReviewMutationService(
                        ignored -> GitHubAuthService.TokenResolution.resolved("secret-token"),
                        new DraftReviewMutationService.GitHubRestClient() {
                            @Override
                            public DraftReviewMutationService.RestResponse get(
                                    String apiBase, String token, String path) {
                                throw new AssertionError("should not be called");
                            }

                            @Override
                            public DraftReviewMutationService.RestResponse post(
                                    String apiBase, String token, String path, String jsonBody) {
                                throw new AssertionError("should not be called");
                            }

                            @Override
                            public DraftReviewMutationService.RestResponse put(
                                    String apiBase, String token, String path, String jsonBody) {
                                throw new AssertionError("should not be called");
                            }

                            @Override
                            public DraftReviewMutationService.RestResponse delete(
                                    String apiBase, String token, String path) {
                                throw new AssertionError("should not be called");
                            }
                        },
                        mapper);

        DraftReviewMutationResult result =
                service.save(
                        new DraftReviewMutationService.SaveParams(
                                "https://github.com",
                                "acme",
                                "repo",
                                0,
                                "s",
                                "APPROVE",
                                List.of(),
                                List.of()));

        assertThat(result.status()).isEqualTo("invalid_request");
    }

    /** Stub that serves one pending review and records the submit calls on exact paths. */
    private static final class SubmitStub implements DraftReviewMutationService.GitHubRestClient {
        private static final String REVIEW_PATH = "/repos/acme/repo/pulls/1/reviews/42";
        private final List<DraftReviewMutationService.RestResponse> getResponses;
        private final List<String> gets = new ArrayList<>();
        private final List<String> posts = new ArrayList<>();
        private final List<String> postBodies = new ArrayList<>();

        SubmitStub(DraftReviewMutationService.RestResponse... getResponses) {
            this.getResponses = new ArrayList<>(List.of(getResponses));
        }

        static SubmitStub withPendingBody(ObjectMapper mapper, String pendingBody)
                throws Exception {
            return new SubmitStub(
                    new DraftReviewMutationService.RestResponse(
                            200,
                            mapper.writeValueAsString(
                                    mapper.createObjectNode()
                                            .put("id", 42)
                                            .put("state", "PENDING")
                                            .put("body", pendingBody))));
        }

        @Override
        public DraftReviewMutationService.RestResponse get(
                String apiBase, String token, String path) {
            gets.add(path);
            if (!REVIEW_PATH.equals(path) || getResponses.isEmpty()) {
                throw new AssertionError("unexpected GET " + path);
            }
            return getResponses.remove(0);
        }

        @Override
        public DraftReviewMutationService.RestResponse post(
                String apiBase, String token, String path, String jsonBody) {
            if (!(REVIEW_PATH + "/events").equals(path)) {
                throw new AssertionError("unexpected POST " + path);
            }
            posts.add(path);
            postBodies.add(jsonBody);
            return new DraftReviewMutationService.RestResponse(200, "{}");
        }

        @Override
        public DraftReviewMutationService.RestResponse put(
                String apiBase, String token, String path, String jsonBody) {
            throw new AssertionError("should not be called");
        }

        @Override
        public DraftReviewMutationService.RestResponse delete(
                String apiBase, String token, String path) {
            throw new AssertionError("should not be called");
        }
    }

    @Nested
    class Submit {
        private final DraftReviewCodec codec = new DraftReviewCodec(mapper);

        private DraftReviewMutationResult submit(SubmitStub stub, String event, String body) {
            return new DraftReviewMutationService(
                            ignored -> GitHubAuthService.TokenResolution.resolved("secret-token"),
                            stub,
                            mapper)
                    .submit(
                            new DraftReviewMutationService.SubmitParams(
                                    "https://github.com", "acme", "repo", 1, "42", event, body));
        }

        private JsonNode onlyPost(SubmitStub stub) throws Exception {
            assertThat(stub.posts).hasSize(1);
            return mapper.readTree(stub.postBodies.get(0));
        }

        private DraftReviewCodec.LineComment comment(String file, int line, String body) {
            return new DraftReviewCodec.LineComment(
                    file, line, "note", body, null, null, null, null);
        }

        private String pendingWithSections() {
            return codec.encodeBody(
                            "SUMMARY",
                            "COMMENT",
                            List.of(comment("", 0, "GENERAL"), comment("demo.txt", 2, "INLINE")))
                    + "\n\n"
                    + codec.buildOrphanSection(List.of(comment("demo.txt", 99, "ORPHAN")));
        }

        private static final String SECTIONS =
                "**General Notes:**\n- GENERAL\n\n"
                        + "**Comments not attached inline (invalid diff positions):**\n"
                        + "- `demo.txt:99`: ORPHAN";

        @Test
        void submitComposesReviewerTextWithRetainedSections() throws Exception {
            SubmitStub stub = SubmitStub.withPendingBody(mapper, pendingWithSections());

            DraftReviewMutationResult result = submit(stub, "COMMENT", "  Edited summary  ");

            assertThat(result.status()).isEqualTo("ok");
            assertThat(stub.gets).containsExactly("/repos/acme/repo/pulls/1/reviews/42");
            JsonNode payload = onlyPost(stub);
            assertThat(payload.path("event").textValue()).isEqualTo("COMMENT");
            assertThat(payload.path("body").textValue()).isEqualTo("Edited summary\n\n" + SECTIONS);
        }

        @ParameterizedTest
        @ValueSource(strings = {"APPROVE", "REQUEST_CHANGES", "COMMENT"})
        void submitPublishesRetainedSectionsWhenReviewerTextBlank(String event) throws Exception {
            SubmitStub stub = SubmitStub.withPendingBody(mapper, pendingWithSections());

            DraftReviewMutationResult result = submit(stub, event, "   ");

            assertThat(result.status()).isEqualTo("ok");
            JsonNode payload = onlyPost(stub);
            assertThat(payload.path("event").textValue()).isEqualTo(event);
            assertThat(payload.path("body").textValue()).isEqualTo(SECTIONS);
        }

        @ParameterizedTest
        @CsvSource({
            "APPROVE,Looks good to me!",
            "REQUEST_CHANGES,Requesting changes.",
            "COMMENT,Leaving comments."
        })
        void submitUsesFallbackOnlyWhenNothingElseToPublish(String event, String fallback)
                throws Exception {
            SubmitStub stub =
                    SubmitStub.withPendingBody(
                            mapper,
                            codec.encodeBody(
                                    "SUMMARY", event, List.of(comment("demo.txt", 2, "INLINE"))));

            DraftReviewMutationResult result = submit(stub, event, "");

            assertThat(result.status()).isEqualTo("ok");
            JsonNode payload = onlyPost(stub);
            assertThat(payload.has("body")).isTrue();
            assertThat(payload.path("body").textValue()).isEqualTo(fallback);
        }

        @Test
        void submitKeepsEveryDetachedCommentFromBodyOnlyFallbackDraft() throws Exception {
            List<DraftReviewCodec.LineComment> all =
                    List.of(comment("demo.txt", 2, "FIRST"), comment("demo.txt", 3, "SECOND"));
            String pending =
                    codec.encodeBody("SUMMARY", "COMMENT", all)
                            + "\n\n"
                            + codec.buildOrphanSection(all);
            SubmitStub stub = SubmitStub.withPendingBody(mapper, pending);

            submit(stub, "COMMENT", "Reviewer note");

            assertThat(onlyPost(stub).path("body").textValue())
                    .isEqualTo(
                            "Reviewer note\n\n"
                                    + "**Comments not attached inline (invalid diff positions):**\n"
                                    + "- `demo.txt:2`: FIRST\n"
                                    + "- `demo.txt:3`: SECOND");
        }

        @Test
        void submitPreservesImportedPlainBodyBelowReviewerText() throws Exception {
            SubmitStub stub =
                    SubmitStub.withPendingBody(mapper, "Text written on GitHub.\n\nSecond line.");

            submit(stub, "APPROVE", "LGTM");

            assertThat(onlyPost(stub).path("body").textValue())
                    .isEqualTo("LGTM\n\nText written on GitHub.\n\nSecond line.");
        }

        @Test
        void submitNeverPublishesHiddenPayload() throws Exception {
            String legacy =
                    "<!-- claude-summary: LEGACY SUMMARY -->\n"
                            + "<!-- claude-verdict: APPROVE -->\n"
                            + "<!-- claude-comments: [] -->\n\n"
                            + "**General Notes:**\n- LEGACY NOTE";
            for (String pending : List.of(pendingWithSections(), legacy)) {
                SubmitStub stub = SubmitStub.withPendingBody(mapper, pending);

                submit(stub, "COMMENT", "Body");

                String body = onlyPost(stub).path("body").textValue();
                assertThat(body)
                        .doesNotContain("<!--")
                        .doesNotContain("pr-pilot-review")
                        .doesNotContain("claude-")
                        .doesNotContain("SUMMARY");
            }
        }

        @Test
        void submitDoesNotPostEventWhenPendingReviewCannotBeRead() {
            for (int status : new int[] {404, 500}) {
                SubmitStub stub =
                        new SubmitStub(
                                new DraftReviewMutationService.RestResponse(
                                        status, "{\"message\":\"nope\"}"));

                DraftReviewMutationResult result = submit(stub, "COMMENT", "Body");

                assertThat(result.status()).isEqualTo("api_failed");
                assertThat(stub.gets).hasSize(1);
                assertThat(stub.posts).isEmpty();
            }
        }

        @Test
        void submitTreatsMissingOrNullPendingBodyAsEmpty() throws Exception {
            for (String response : List.of("{\"id\":42}", "{\"id\":42,\"body\":null}")) {
                SubmitStub stub =
                        new SubmitStub(new DraftReviewMutationService.RestResponse(200, response));

                submit(stub, "REQUEST_CHANGES", "");

                assertThat(onlyPost(stub).path("body").textValue())
                        .isEqualTo("Requesting changes.");
            }
        }
    }
}
