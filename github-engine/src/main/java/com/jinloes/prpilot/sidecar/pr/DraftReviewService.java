package com.jinloes.prpilot.sidecar.pr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.sidecar.github.GitHubApiBase;
import com.jinloes.prpilot.sidecar.github.GitHubAuthService;
import com.jinloes.prpilot.sidecar.github.GitHubFailure;
import com.jinloes.prpilot.sidecar.github.GitHubHttpClient;
import com.jinloes.prpilot.sidecar.github.GitHubResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Read-only pending-review orchestration; tokens never leave the sidecar process. */
public final class DraftReviewService {
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_.-]+");
    static final int PAGE_SIZE = 100;
    static final int MAX_PAGES = 10;

    private final GitHubAuthService.TokenResolver tokenResolver;
    private final PendingReviewClient client;
    private final DraftReviewCodec codec;

    public DraftReviewService() {
        this(
                new GitHubAuthService.ProcessTokenResolver(),
                new HttpPendingReviewClient(),
                new ObjectMapper());
    }

    DraftReviewService(
            GitHubAuthService.TokenResolver tokenResolver,
            PendingReviewClient client,
            ObjectMapper mapper) {
        this.tokenResolver = Objects.requireNonNull(tokenResolver);
        this.client = Objects.requireNonNull(client);
        this.codec = new DraftReviewCodec(mapper);
    }

    public DraftReviewResult load(String baseUrl, String owner, String repo, int number) {
        if (!valid(owner) || !valid(repo) || number <= 0)
            return DraftReviewResult.failure(
                    "invalid_request", "Pull request identity is invalid.");
        GitHubApiBase urls = GitHubApiBase.parse(baseUrl);
        if (urls == null)
            return DraftReviewResult.failure(
                    "invalid_base_url", "GitHub base URL must be an HTTPS origin.");
        GitHubAuthService.TokenResolution token = tokenResolver.resolve(urls.hostnameArgument());
        if (token.status() == GitHubAuthService.TokenStatus.NOT_INSTALLED)
            return DraftReviewResult.failure("not_installed", "GitHub CLI is not installed.");
        if (token.status() != GitHubAuthService.TokenStatus.RESOLVED)
            return DraftReviewResult.failure(
                    "not_authenticated", "Run 'gh auth login' in a terminal for this GitHub host.");
        Pending pending;
        try {
            pending = client.load(urls.apiBaseUrl(), token.token(), owner, repo, number);
        } catch (PendingReviewFetchException exception) {
            return DraftReviewResult.failure(exception.status(), exception.getMessage());
        }
        if (pending == null) return DraftReviewResult.none();
        DraftReviewCodec.DecodedReview review = codec.decode(pending.body(), pending.comments());
        return new DraftReviewResult(
                "ok", "Pending review draft loaded.", pending.id(), pending.commitId(), review);
    }

    private boolean valid(String value) {
        return value != null && SEGMENT.matcher(value).matches();
    }

    interface PendingReviewClient {
        Pending load(String baseUrl, String token, String owner, String repo, int number);
    }

    record Pending(
            String id, String commitId, String body, List<DraftReviewCodec.ApiComment> comments) {}

    /** Thrown by a {@link PendingReviewClient} to signal a token-free domain failure status. */
    static final class PendingReviewFetchException extends RuntimeException {
        private final String status;

        PendingReviewFetchException(String status, String message) {
            super(message);
            this.status = status;
        }

        String status() {
            return status;
        }
    }

    /**
     * Fetches the PENDING review (if any) for a pull request over the GitHub REST API, plus its
     * inline comments. Never returns or logs the token used to authenticate.
     */
    static final class HttpPendingReviewClient implements PendingReviewClient {
        private final HttpGetter httpGet;
        private final ObjectMapper mapper = new ObjectMapper();

        HttpPendingReviewClient() {
            this(new GitHubHttpClient()::get);
        }

        HttpPendingReviewClient(HttpGetter httpGet) {
            this.httpGet = Objects.requireNonNull(httpGet);
        }

        @Override
        public Pending load(
                String apiBaseUrl, String token, String owner, String repo, int number) {
            String reviewsUrl =
                    apiBaseUrl
                            + "/repos/"
                            + URLEncoder.encode(owner, StandardCharsets.UTF_8)
                            + "/"
                            + URLEncoder.encode(repo, StandardCharsets.UTF_8)
                            + "/pulls/"
                            + number
                            + "/reviews";
            JsonNode pending = null;
            for (int page = 1; page <= MAX_PAGES; page++) {
                JsonNode reviews =
                        fetchJson(reviewsUrl + "?per_page=" + PAGE_SIZE + "&page=" + page, token);
                if (!reviews.isArray()) {
                    throw new PendingReviewFetchException(
                            "api_failed", "GitHub API request failed.");
                }
                for (JsonNode review : reviews) {
                    if ("PENDING".equals(review.path("state").asText(null))) {
                        pending = review;
                        break;
                    }
                }
                if (pending != null || reviews.size() < PAGE_SIZE) break;
                if (page == MAX_PAGES) throw paginationLimit();
            }
            if (pending == null) return null;

            String id = pending.path("id").isMissingNode() ? null : pending.path("id").asText();
            if (id == null || id.isBlank()) {
                throw new PendingReviewFetchException("api_failed", "GitHub API request failed.");
            }
            String commitId =
                    pending.path("commit_id").isTextual()
                            ? pending.path("commit_id").textValue()
                            : null;
            String body = pending.path("body").isTextual() ? pending.path("body").textValue() : "";

            List<DraftReviewCodec.ApiComment> apiComments = new ArrayList<>();
            String commentsUrl = reviewsUrl + "/" + id + "/comments";
            for (int page = 1; page <= MAX_PAGES; page++) {
                JsonNode comments =
                        fetchJson(commentsUrl + "?per_page=" + PAGE_SIZE + "&page=" + page, token);
                if (!comments.isArray()) {
                    throw new PendingReviewFetchException(
                            "api_failed", "GitHub API request failed.");
                }
                for (JsonNode comment : comments) {
                    apiComments.add(
                            new DraftReviewCodec.ApiComment(
                                    nullableText(comment, "path"),
                                    nullableInt(comment, "line"),
                                    nullableInt(comment, "original_line"),
                                    nullableText(comment, "body")));
                }
                if (comments.size() < PAGE_SIZE) break;
                if (page == MAX_PAGES) throw paginationLimit();
            }
            return new Pending(id, commitId, body, apiComments);
        }

        private static PendingReviewFetchException paginationLimit() {
            return new PendingReviewFetchException(
                    "api_failed", "GitHub pending review pagination limit was exceeded.");
        }

        private static String nullableText(JsonNode node, String field) {
            JsonNode value = node.path(field);
            return value.isTextual() ? value.textValue() : null;
        }

        private static Integer nullableInt(JsonNode node, String field) {
            JsonNode value = node.path(field);
            return value.canConvertToInt() ? value.intValue() : null;
        }

        private JsonNode fetchJson(String url, String token) {
            GitHubResponse response = httpGet.get(url, token);
            if (response.isRateLimited()) {
                throw new PendingReviewFetchException(
                        "rate_limited", "GitHub rate limit exceeded. Try again shortly.");
            }
            if (response.isUnauthenticated()) {
                throw new PendingReviewFetchException(
                        "not_authenticated",
                        "Run 'gh auth login' in a terminal for this GitHub host.");
            }
            if (response.isNetworkError()) {
                GitHubFailure failure = GitHubFailure.networkFailure(response);
                throw new PendingReviewFetchException(failure.status(), failure.message());
            }
            if (!response.isSuccess()) {
                throw new PendingReviewFetchException("api_failed", "GitHub API request failed.");
            }
            try {
                return mapper.readTree(response.body());
            } catch (IOException exception) {
                throw new PendingReviewFetchException("api_failed", "GitHub API request failed.");
            }
        }

        @FunctionalInterface
        interface HttpGetter {
            GitHubResponse get(String url, String token);
        }
    }
}
