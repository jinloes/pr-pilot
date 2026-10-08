package com.jinloes.prpilot.sidecar.pr;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinloes.prpilot.sidecar.github.GitHubAuthService;
import com.jinloes.prpilot.sidecar.github.GitHubResponse;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PrSupplementalServiceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void searchesWithABoundedEncodedQuery() {
        FakeClient client = new FakeClient();
        client.responses.add(
                ok(
                        "{\"items\":[{\"number\":42,\"title\":\"Fix\","
                                + "\"repository_url\":\"https://api.github.com/repos/acme/widgets\","
                                + "\"user\":{\"login\":\"octo\"},"
                                + "\"created_at\":\"2026-07-22T01:00:00Z\","
                                + "\"html_url\":\"https://example/pr/42\"}]}"));
        PrSupplementalService service = service(client);

        PrSearchResult result =
                service.search(
                        new PrSupplementalService.SearchParams(
                                "https://github.com", "is:pr review-requested:@me", 50));

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.prs())
                .singleElement()
                .extracting(PullRequestSummary::repo)
                .isEqualTo("widgets");
        assertThat(client.paths).singleElement().asString().contains("review-requested%3A%40me");
        assertThat(result.toString()).doesNotContain("secret-token");
    }

    @Test
    void rejectsAnUnboundedSearchWithoutResolvingCredentials() {
        int[] tokenCalls = {0};
        PrSupplementalService service =
                new PrSupplementalService(
                        hostname -> {
                            tokenCalls[0]++;
                            return GitHubAuthService.TokenResolution.resolved("secret-token");
                        },
                        new FakeClient(),
                        new ObjectMapper());

        PrSearchResult result =
                service.search(new PrSupplementalService.SearchParams("", "is:pr", 101));

        assertThat(result.status()).isEqualTo("invalid_request");
        assertThat(tokenCalls[0]).isZero();
    }

    @Test
    void rejectsMalformedSearchResponses() {
        List<String> malformedBodies =
                List.of(
                        "{}",
                        "{\"items\":{}}",
                        "{\"items\":[{}]}",
                        "{\"items\":[{\"number\":42,\"title\":\"Fix\","
                                + "\"repository_url\":\"malformed\","
                                + "\"user\":{\"login\":\"octo\"},"
                                + "\"created_at\":\"2026-07-22T01:00:00Z\","
                                + "\"html_url\":\"https://example/pr/42\"}]}",
                        "{\"items\":[{\"number\":0,\"title\":\"Fix\","
                                + "\"repository_url\":\"https://api.github.com/repos/acme/widgets\","
                                + "\"user\":{\"login\":\"octo\"},"
                                + "\"created_at\":\"2026-07-22T01:00:00Z\","
                                + "\"html_url\":\"https://example/pr/42\"}]}");

        for (String body : malformedBodies) {
            FakeClient client = new FakeClient();
            client.responses.add(ok(body));

            PrSearchResult result =
                    service(client)
                            .search(
                                    new PrSupplementalService.SearchParams(
                                            "https://github.com", "is:pr", 50));

            assertThat(result.status()).as(body).isEqualTo("api_failed");
            assertThat(result.prs()).as(body).isEmpty();
        }
    }

    @Test
    void loadsStarredRepositoriesUntilAPartialPage() {
        FakeClient client = new FakeClient();
        client.responses.add(ok("[{\"full_name\":\"acme/one\"},{\"full_name\":\"acme/two\"}]"));

        StarredReposResult result = service(client).starred("https://github.com");

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.repositories()).containsExactly("acme/one", "acme/two");
        assertThat(client.paths).singleElement().asString().contains("page=1");
    }

    @Test
    void formatsSubmittedReviewsAndIgnoresPendingReviews() {
        FakeClient client = new FakeClient();
        client.responses.add(
                ok(
                        "[{\"id\":1,\"state\":\"PENDING\"},"
                                + "{\"id\":2,\"state\":\"APPROVED\",\"body\":\"Looks good\","
                                + "\"submitted_at\":\"2026-07-22T01:00:00Z\",\"user\":{\"login\":\"sam\"}}]"));
        client.responses.add(
                ok(
                        "[{\"pull_request_review_id\":2,\"path\":\"src/App.java\","
                                + "\"line\":12,\"body\":\"Nice change\"}]"));

        ExistingReviewsResult result =
                service(client)
                        .existingReviews(
                                new PrSupplementalService.IdentityParams(
                                        "https://github.com", "acme", "widgets", 42));

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.summary())
                .contains("Review by @sam (APPROVED, 2026-07-22):")
                .contains("Overall: \"Looks good\"")
                .contains("src/App.java:12: \"Nice change\"");
        assertThat(client.paths)
                .containsExactly(
                        "/repos/acme/widgets/pulls/42/reviews?per_page=100&page=1",
                        "/repos/acme/widgets/pulls/42/comments?per_page=100&page=1");
    }

    @Test
    void paginatesReviewsAndFetchesCommentsOnceForThePullRequest() {
        FakeClient client = new FakeClient();
        ArrayNode firstReviewPage = MAPPER.createArrayNode();
        for (int i = 0; i < 99; i++) {
            firstReviewPage.add(review(1_000 + i, "PENDING", "pending-" + i));
        }
        firstReviewPage.add(review(2, "APPROVED", "sam"));
        client.responses.add(ok(firstReviewPage.toString()));
        client.responses.add(
                ok(MAPPER.createArrayNode().add(review(3, "COMMENTED", "lee")).toString()));
        client.responses.add(
                ok(
                        MAPPER.createArrayNode()
                                .add(comment(2, "src/A.java", 10, "First"))
                                .add(comment(3, "src/B.java", 20, "Second"))
                                .toString()));

        ExistingReviewsResult result =
                service(client)
                        .existingReviews(
                                new PrSupplementalService.IdentityParams(
                                        "https://github.com", "acme", "widgets", 42));

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.summary())
                .contains("Review by @sam")
                .contains("src/A.java:10: \"First\"")
                .contains("Review by @lee")
                .contains("src/B.java:20: \"Second\"");
        assertThat(client.paths)
                .containsExactly(
                        "/repos/acme/widgets/pulls/42/reviews?per_page=100&page=1",
                        "/repos/acme/widgets/pulls/42/reviews?per_page=100&page=2",
                        "/repos/acme/widgets/pulls/42/comments?per_page=100&page=1");
    }

    @Test
    void failedLaterPageReturnsAnExplicitFailureWithoutPartialContext() {
        FakeClient client = new FakeClient();
        ArrayNode fullReviewPage = MAPPER.createArrayNode();
        for (int i = 0; i < 100; i++) {
            fullReviewPage.add(review(i + 1, "COMMENTED", "reviewer-" + i));
        }
        client.responses.add(ok(fullReviewPage.toString()));
        client.responses.add(new GitHubResponse(500, ""));

        ExistingReviewsResult result =
                service(client)
                        .existingReviews(
                                new PrSupplementalService.IdentityParams(
                                        "https://github.com", "acme", "widgets", 42));

        assertThat(result.status()).isEqualTo("api_failed");
        assertThat(result.summary()).isEmpty();
        assertThat(client.paths).hasSize(2);
    }

    @Test
    void preservesReviewBodiesWhenTheFirstCommentsPageFails() {
        FakeClient client = new FakeClient();
        client.responses.add(
                ok(MAPPER.createArrayNode().add(review(2, "APPROVED", "sam")).toString()));
        client.responses.add(new GitHubResponse(500, ""));

        ExistingReviewsResult result =
                service(client)
                        .existingReviews(
                                new PrSupplementalService.IdentityParams(
                                        "https://github.com", "acme", "widgets", 42));

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.message()).contains("without inline comments");
        assertThat(result.summary())
                .contains("Review by @sam")
                .contains("Overall: \"Overall\"")
                .contains("(Inline review comments were unavailable.)");
    }

    @Test
    void preservesReviewBodiesWhenALaterCommentsPageFails() {
        FakeClient client = new FakeClient();
        client.responses.add(
                ok(MAPPER.createArrayNode().add(review(2, "APPROVED", "sam")).toString()));
        ArrayNode fullCommentsPage = MAPPER.createArrayNode();
        for (int i = 0; i < 100; i++) {
            fullCommentsPage.add(comment(2, "src/File" + i + ".java", i + 1, "Comment"));
        }
        client.responses.add(ok(fullCommentsPage.toString()));
        client.responses.add(new GitHubResponse(500, ""));

        ExistingReviewsResult result =
                service(client)
                        .existingReviews(
                                new PrSupplementalService.IdentityParams(
                                        "https://github.com", "acme", "widgets", 42));

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.summary())
                .contains("Review by @sam")
                .doesNotContain("src/File0.java")
                .contains("(Inline review comments were unavailable.)");
    }

    @Test
    void exactReviewCapIsNotReportedAsTruncated() {
        FakeClient client = new FakeClient();
        client.responses.add(ok(reviewPage(1, 100, "PENDING").toString()));
        client.responses.add(ok(reviewPage(101, 100, "PENDING").toString()));
        client.responses.add(ok("[]"));
        client.responses.add(ok("[]"));

        ExistingReviewsResult result =
                service(client)
                        .existingReviews(
                                new PrSupplementalService.IdentityParams(
                                        "https://github.com", "acme", "widgets", 42));

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.summary()).doesNotContain("additional items may be omitted");
        assertThat(client.paths.get(2)).endsWith("reviews?per_page=100&page=3");
    }

    @Test
    void reviewCapPlusOneIsReportedAsTruncated() {
        FakeClient client = new FakeClient();
        client.responses.add(ok(reviewPage(1, 100, "PENDING").toString()));
        client.responses.add(ok(reviewPage(101, 100, "PENDING").toString()));
        client.responses.add(ok(reviewPage(201, 1, "PENDING").toString()));
        client.responses.add(ok("[]"));

        ExistingReviewsResult result =
                service(client)
                        .existingReviews(
                                new PrSupplementalService.IdentityParams(
                                        "https://github.com", "acme", "widgets", 42));

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.summary()).contains("additional items may be omitted");
    }

    @Test
    void capsRenderedExistingReviewContext() {
        FakeClient client = new FakeClient();
        client.responses.add(
                ok(MAPPER.createArrayNode().add(review(2, "COMMENTED", "sam")).toString()));
        ArrayNode comments = MAPPER.createArrayNode();
        for (int i = 0; i < 100; i++) {
            comments.add(comment(2, "src/File" + i + ".java", i + 1, "x".repeat(500)));
        }
        client.responses.add(ok(comments.toString()));
        client.responses.add(ok("[]"));

        ExistingReviewsResult result =
                service(client)
                        .existingReviews(
                                new PrSupplementalService.IdentityParams(
                                        "https://github.com", "acme", "widgets", 42));

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.summary().length())
                .isLessThanOrEqualTo(PrSupplementalService.MAX_EXISTING_REVIEWS_CHARS);
        assertThat(result.summary()).endsWith("...(existing review context truncated)");
    }

    @Nested
    class ExistingReviewsThreadState {
        private static final PrSupplementalService.IdentityParams PARAMS =
                new PrSupplementalService.IdentityParams(
                        "https://github.com", "acme", "widgets", 42);

        @Test
        void tagsResolvedRootAndItsReplyButNotAnUnresolvedThread() {
            FakeClient client = new FakeClient();
            queueReviewAndComments(
                    client,
                    comment(2, 10L, null, "src/A.java", 5, "Root"),
                    comment(2, 11L, 10L, "src/A.java", 5, "Reply"),
                    comment(2, 20L, null, "src/B.java", 7, "Open"));
            client.postResponses.add(
                    ok(threadPage(false, null, thread(true, "10"), thread(false, "20"))));

            String summary = service(client).existingReviews(PARAMS).summary();

            assertThat(summary)
                    .contains("src/A.java:5 [resolved]: \"Root\"")
                    .contains("src/A.java:5 [resolved]: \"Reply\"")
                    .contains("src/B.java:7: \"Open\"")
                    .doesNotContain("Thread resolution state was unavailable");
        }

        @Test
        void tagsOutdatedFromNullLineAndCombinesWithResolved() {
            FakeClient client = new FakeClient();
            ObjectNode outdated = comment(2, 10L, null, "src/A.java", 0, "Old");
            outdated.putNull("line");
            outdated.put("original_line", 33);
            ObjectNode outdatedOnly = comment(2, 30L, null, "src/C.java", 0, "Stale");
            outdatedOnly.putNull("line");
            outdatedOnly.put("original_line", 44);
            ObjectNode current = comment(2, 40L, null, "src/D.java", 9, "Current");
            current.put("original_line", 2);
            queueReviewAndComments(client, outdated, outdatedOnly, current);
            client.postResponses.add(ok(threadPage(false, null, thread(true, "10"))));

            String summary = service(client).existingReviews(PARAMS).summary();

            assertThat(summary)
                    .contains("src/A.java:33 [resolved, outdated]: \"Old\"")
                    .contains("src/C.java:44 [outdated]: \"Stale\"")
                    .contains("src/D.java:9: \"Current\"");
        }

        @Test
        void postsGraphqlBodyWithVariablesAndFollowsCursor() throws Exception {
            FakeClient client = new FakeClient();
            queueReviewAndComments(client, comment(2, 10L, null, "src/A.java", 5, "Root"));
            client.postResponses.add(ok(threadPage(true, "CURSOR1")));
            client.postResponses.add(ok(threadPage(false, null, thread(true, "10"))));

            String summary = service(client).existingReviews(PARAMS).summary();

            assertThat(summary).contains("src/A.java:5 [resolved]");
            assertThat(client.postUrls)
                    .containsExactly(
                            "https://api.github.com/graphql", "https://api.github.com/graphql");
            JsonNode first = MAPPER.readTree(client.postBodies.get(0));
            assertThat(first.path("query").asText())
                    .contains("reviewThreads(first: 100, after: $cursor)")
                    .contains("pageInfo { hasNextPage endCursor }")
                    .contains(
                            "nodes { isResolved comments(first: 1) { nodes { fullDatabaseId } } }");
            assertThat(first.path("variables").path("owner").asText()).isEqualTo("acme");
            assertThat(first.path("variables").path("repo").asText()).isEqualTo("widgets");
            assertThat(first.path("variables").path("number").asInt()).isEqualTo(42);
            assertThat(first.path("variables").has("cursor")).isFalse();
            JsonNode second = MAPPER.readTree(client.postBodies.get(1));
            assertThat(second.path("variables").path("cursor").asText()).isEqualTo("CURSOR1");
        }

        @Test
        void stopsAfterFivePagesAndReportsUnavailable() {
            FakeClient client = new FakeClient();
            queueReviewAndComments(client, comment(2, 10L, null, "src/A.java", 5, "Root"));
            for (int i = 0; i < 6; i++) {
                client.postResponses.add(ok(threadPage(true, "C" + i, thread(true, "10"))));
            }

            String summary = service(client).existingReviews(PARAMS).summary();

            assertThat(client.postBodies).hasSize(5);
            assertThat(summary)
                    .startsWith("(Thread resolution state was unavailable.)\n\n")
                    .doesNotContain("[resolved]");
        }

        @Test
        void matchesIdsAboveIntRangeAndSkipsMissingIds() {
            FakeClient client = new FakeClient();
            queueReviewAndComments(
                    client,
                    comment(2, 3_000_000_000L, null, "src/A.java", 5, "Big"),
                    comment(2, 12L, null, "src/B.java", 6, "Other"));
            ObjectNode nullId = MAPPER.createObjectNode();
            nullId.put("isResolved", true);
            nullId.putObject("comments").putArray("nodes").addObject().putNull("fullDatabaseId");
            client.postResponses.add(
                    ok(
                            threadPage(
                                    false,
                                    null,
                                    thread(true, "3000000000"),
                                    nullId,
                                    thread(true, "x"))));

            String summary = service(client).existingReviews(PARAMS).summary();

            assertThat(summary)
                    .contains("src/A.java:5 [resolved]: \"Big\"")
                    .contains("src/B.java:6: \"Other\"")
                    .doesNotContain("unavailable");
        }

        @Test
        void keepsUnavailableMarkerWhenSummaryIsCapped() {
            FakeClient client = new FakeClient();
            ArrayNode comments = MAPPER.createArrayNode();
            for (int i = 0; i < 99; i++) {
                comments.add(
                        comment(
                                2,
                                100L + i,
                                null,
                                "src/File" + i + ".java",
                                i + 1,
                                "x".repeat(500)));
            }
            client.responses.add(
                    ok(MAPPER.createArrayNode().add(review(2, "COMMENTED", "sam")).toString()));
            client.responses.add(ok(comments.toString()));
            client.postResponses.add(new GitHubResponse(500, ""));

            String summary = service(client).existingReviews(PARAMS).summary();

            assertThat(summary)
                    .startsWith("(Thread resolution state was unavailable.)")
                    .endsWith("...(existing review context truncated)");
        }

        @Test
        void skipsGraphqlWhenThereAreNoInlineComments() {
            FakeClient client = new FakeClient();
            client.responses.add(
                    ok(MAPPER.createArrayNode().add(review(2, "APPROVED", "sam")).toString()));
            client.responses.add(ok("[]"));

            service(client).existingReviews(PARAMS);

            assertThat(client.postBodies).isEmpty();
        }

        @Test
        void skipsGraphqlWhenInlineCommentsFailed() {
            FakeClient client = new FakeClient();
            client.responses.add(
                    ok(MAPPER.createArrayNode().add(review(2, "APPROVED", "sam")).toString()));
            client.responses.add(new GitHubResponse(500, ""));

            String summary = service(client).existingReviews(PARAMS).summary();

            assertThat(client.postBodies).isEmpty();
            assertThat(summary).doesNotContain("Thread resolution state was unavailable");
        }

        @Test
        void eachFailureKindKeepsOutdatedTagsAndAddsMarker() {
            ObjectNode errors = MAPPER.createObjectNode();
            errors.putArray("errors").addObject().put("message", "boom");
            ObjectNode missingThreads = MAPPER.createObjectNode();
            missingThreads.putObject("data").putObject("repository").putObject("pullRequest");
            List<GitHubResponse> failures =
                    List.of(
                            new GitHubResponse(502, ""),
                            new GitHubResponse(GitHubResponse.NETWORK_ERROR, ""),
                            ok(errors.toString()),
                            ok("not json"),
                            ok(missingThreads.toString()));
            for (GitHubResponse failure : failures) {
                FakeClient client = new FakeClient();
                ObjectNode outdated = comment(2, 10L, null, "src/A.java", 0, "Old");
                outdated.putNull("line");
                outdated.put("original_line", 8);
                queueReviewAndComments(client, outdated);
                client.postResponses.add(failure);

                ExistingReviewsResult result = service(client).existingReviews(PARAMS);

                assertThat(result.status()).as(failure.body()).isEqualTo("ok");
                assertThat(result.summary())
                        .as(failure.body())
                        .startsWith("(Thread resolution state was unavailable.)\n\n")
                        .contains("src/A.java:8 [outdated]: \"Old\"")
                        .doesNotContain("[resolved");
            }
        }

        @Test
        void laterPageFailureDiscardsEarlierResolvedIds() {
            FakeClient client = new FakeClient();
            queueReviewAndComments(client, comment(2, 10L, null, "src/A.java", 5, "Root"));
            client.postResponses.add(ok(threadPage(true, "C1", thread(true, "10"))));
            client.postResponses.add(new GitHubResponse(500, ""));

            String summary = service(client).existingReviews(PARAMS).summary();

            assertThat(summary)
                    .contains("Thread resolution state was unavailable")
                    .contains("src/A.java:5: \"Root\"")
                    .doesNotContain("[resolved]");
        }

        private void queueReviewAndComments(FakeClient client, ObjectNode... comments) {
            client.responses.add(
                    ok(MAPPER.createArrayNode().add(review(2, "COMMENTED", "sam")).toString()));
            ArrayNode page = MAPPER.createArrayNode();
            for (ObjectNode comment : comments) page.add(comment);
            client.responses.add(ok(page.toString()));
        }
    }

    private static String threadPage(boolean hasNextPage, String endCursor, ObjectNode... threads) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode reviewThreads =
                root.putObject("data")
                        .putObject("repository")
                        .putObject("pullRequest")
                        .putObject("reviewThreads");
        ObjectNode pageInfo = reviewThreads.putObject("pageInfo");
        pageInfo.put("hasNextPage", hasNextPage);
        if (endCursor == null) pageInfo.putNull("endCursor");
        else pageInfo.put("endCursor", endCursor);
        ArrayNode nodes = reviewThreads.putArray("nodes");
        for (ObjectNode thread : threads) nodes.add(thread);
        return root.toString();
    }

    private static ObjectNode thread(boolean resolved, String rootId) {
        ObjectNode thread = MAPPER.createObjectNode();
        thread.put("isResolved", resolved);
        thread.putObject("comments").putArray("nodes").addObject().put("fullDatabaseId", rootId);
        return thread;
    }

    private static PrSupplementalService service(FakeClient client) {
        return new PrSupplementalService(
                hostname -> GitHubAuthService.TokenResolution.resolved("secret-token"),
                client,
                new ObjectMapper());
    }

    private static GitHubResponse ok(String body) {
        return new GitHubResponse(200, body);
    }

    private static ObjectNode review(long id, String state, String reviewer) {
        ObjectNode review = MAPPER.createObjectNode();
        review.put("id", id);
        review.put("state", state);
        review.put("body", "Overall");
        review.put("submitted_at", "2026-07-22T01:00:00Z");
        review.putObject("user").put("login", reviewer);
        return review;
    }

    private static ArrayNode reviewPage(int firstId, int count, String state) {
        ArrayNode reviews = MAPPER.createArrayNode();
        for (int i = 0; i < count; i++) {
            reviews.add(review(firstId + i, state, "reviewer-" + (firstId + i)));
        }
        return reviews;
    }

    private static ObjectNode comment(long reviewId, String path, int line, String body) {
        ObjectNode comment = MAPPER.createObjectNode();
        comment.put("pull_request_review_id", reviewId);
        comment.put("path", path);
        comment.put("line", line);
        comment.put("body", body);
        return comment;
    }

    private static ObjectNode comment(
            long reviewId, long id, Long inReplyToId, String path, int line, String body) {
        ObjectNode comment = comment(reviewId, path, line, body);
        comment.put("id", id);
        if (inReplyToId != null) comment.put("in_reply_to_id", inReplyToId);
        return comment;
    }

    private static final class FakeClient implements PrSupplementalService.ApiClient {
        private final Deque<GitHubResponse> responses = new ArrayDeque<>();
        private final List<String> paths = new ArrayList<>();
        private final Deque<GitHubResponse> postResponses = new ArrayDeque<>();
        private final List<String> postUrls = new ArrayList<>();
        private final List<String> postBodies = new ArrayList<>();

        @Override
        public GitHubResponse get(String apiBase, String token, String path) {
            assertThat(apiBase).isEqualTo("https://api.github.com");
            assertThat(token).isEqualTo("secret-token");
            paths.add(path);
            return responses.removeFirst();
        }

        @Override
        public GitHubResponse postJson(String url, String token, String body) {
            assertThat(token).isEqualTo("secret-token");
            postUrls.add(url);
            postBodies.add(body);
            GitHubResponse next = postResponses.pollFirst();
            return next != null ? next : ok(threadPage(false, null));
        }
    }
}
