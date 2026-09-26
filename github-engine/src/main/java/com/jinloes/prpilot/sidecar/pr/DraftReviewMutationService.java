package com.jinloes.prpilot.sidecar.pr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinloes.prpilot.sidecar.github.GitHubApiBase;
import com.jinloes.prpilot.sidecar.github.GitHubAuthService;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Canonical save/submit/delete orchestration for GitHub pending reviews; tokens never leave the
 * engine. A failed replacement remains recoverable because hosts persist the desired token-free
 * snapshot before calling this service.
 */
public final class DraftReviewMutationService {
    private static final Logger log = LoggerFactory.getLogger(DraftReviewMutationService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_ATTEMPTS = 3;
    static final int PAGE_SIZE = 100;
    static final int MAX_REVIEW_PAGES = 10;
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final Pattern REVIEW_ID = Pattern.compile("[0-9]+");
    private static final Set<String> EVENTS = Set.of("APPROVE", "REQUEST_CHANGES", "COMMENT");

    private final GitHubAuthService.TokenResolver tokenResolver;
    private final GitHubRestClient client;
    private final DraftReviewCodec codec;
    private final ObjectMapper mapper;

    public DraftReviewMutationService() {
        this(
                new GitHubAuthService.ProcessTokenResolver(),
                new HttpGitHubRestClient(),
                new ObjectMapper());
    }

    DraftReviewMutationService(
            GitHubAuthService.TokenResolver tokenResolver,
            GitHubRestClient client,
            ObjectMapper mapper) {
        this.tokenResolver = Objects.requireNonNull(tokenResolver);
        this.client = Objects.requireNonNull(client);
        this.mapper = Objects.requireNonNull(mapper);
        this.codec = new DraftReviewCodec(mapper);
    }

    public DraftReviewMutationResult save(SaveParams params) {
        if (params == null
                || !valid(params.owner())
                || !valid(params.repo())
                || params.number() <= 0) {
            return DraftReviewMutationResult.failure(
                    "invalid_request", "Pull request identity is invalid.");
        }
        Session session = openSession(params.baseUrl());
        if (session == null) {
            return DraftReviewMutationResult.failure(
                    "invalid_base_url", "GitHub base URL must be an HTTPS origin.");
        }
        if (session.failure() != null) return session.failure();

        String basePath = pullPath(params.owner(), params.repo(), params.number());
        String reviewsUrl = basePath + "/reviews";
        try {
            String headSha = getHeadSha(session, basePath);
            List<DraftReviewCodec.LineComment> lineComments = toLineComments(params.lineComments());
            List<DraftReviewCodec.LineComment> orphans = toLineComments(params.orphans());
            ArrayNode comments = codec.buildCommentArray(lineComments, orphans);
            String encodedBody = codec.encodeBody(params.summary(), params.verdict(), lineComments);
            String bodyWithOrphans =
                    orphans.isEmpty()
                            ? encodedBody
                            : encodedBody + "\n\n" + codec.buildOrphanSection(orphans);

            PendingReview existing = findPendingReview(session, basePath);
            if (existing != null) {
                boolean sameHead = headSha.equals(existing.commitId());
                if (sameHead && bodyWithOrphans.equals(existing.body())) {
                    return DraftReviewMutationResult.saved(existing.id(), false);
                }
                DraftReviewCodec.DecodedReview decoded = codec.decode(existing.body(), List.of());
                if (sameHead
                        && !decoded.importedFromGitHub()
                        && decoded.lineComments().equals(lineComments)) {
                    ObjectNode updatePayload = mapper.createObjectNode();
                    updatePayload.put("body", bodyWithOrphans);
                    requireSuccess(
                            client.put(
                                    session.apiBase(),
                                    session.token(),
                                    reviewsUrl + "/" + existing.id(),
                                    writeJson(updatePayload)));
                    return DraftReviewMutationResult.saved(existing.id(), false);
                }
                requireSuccess(
                        client.delete(
                                session.apiBase(),
                                session.token(),
                                reviewsUrl + "/" + existing.id()));
            }

            ObjectNode payload = mapper.createObjectNode();
            payload.put("commit_id", headSha);
            payload.put("body", bodyWithOrphans);
            // Omitting "event" creates a PENDING (draft) review; event:"PENDING" is invalid (422).
            payload.set("comments", comments);

            RestResponse createResponse =
                    client.post(session.apiBase(), session.token(), reviewsUrl, writeJson(payload));
            boolean commentsDropped = false;
            JsonNode created;
            if (createResponse.statusCode() == 422 && !comments.isEmpty()) {
                // GitHub rejected at least one inline position. Keep every comment in the encoded
                // body and visible detached section instead of relying on the undocumented
                // per-review comments endpoint.
                ObjectNode bodyOnlyPayload = payload.deepCopy();
                String bodyOnly = bodyWithOrphans;
                List<DraftReviewCodec.LineComment> detached =
                        lineComments.stream()
                                .filter(comment -> !orphans.contains(comment))
                                .toList();
                if (!detached.isEmpty()) {
                    bodyOnly += "\n\n" + codec.buildOrphanSection(detached);
                }
                bodyOnlyPayload.put("body", bodyOnly);
                bodyOnlyPayload.set("comments", mapper.createArrayNode());
                RestResponse bodyOnlyResponse =
                        client.post(
                                session.apiBase(),
                                session.token(),
                                reviewsUrl,
                                writeJson(bodyOnlyPayload));
                requireSuccess(bodyOnlyResponse);
                created = readJson(bodyOnlyResponse.body());
                String reviewId = created.path("id").asText("");
                if (reviewId.isBlank()) {
                    throw new MutationException("api_failed", "GitHub API request failed.");
                }
                commentsDropped = true;
            } else {
                requireSuccess(createResponse);
                created = readJson(createResponse.body());
            }
            String reviewId = created.path("id").asText("");
            if (reviewId.isBlank()) {
                return DraftReviewMutationResult.failure(
                        "api_failed", "GitHub API request failed.");
            }
            return DraftReviewMutationResult.saved(reviewId, commentsDropped);
        } catch (MutationException exception) {
            return DraftReviewMutationResult.recoveryRequired(
                    exception.status(), exception.getMessage());
        } catch (RuntimeException exception) {
            log.warn(
                    "Unexpected draft review save failure for {}/{}#{} ({})",
                    params.owner(),
                    params.repo(),
                    params.number(),
                    exception.getClass().getName());
            return DraftReviewMutationResult.recoveryRequired(
                    "api_failed", "GitHub API request failed.");
        }
    }

    public DraftReviewMutationResult submit(SubmitParams params) {
        if (!valid(params.owner())
                || !valid(params.repo())
                || params.number() <= 0
                || !REVIEW_ID.matcher(params.reviewId() == null ? "" : params.reviewId()).matches()
                || !EVENTS.contains(params.event())) {
            return DraftReviewMutationResult.failure(
                    "invalid_request", "Pull request identity is invalid.");
        }
        Session session = openSession(params.baseUrl());
        if (session == null) {
            return DraftReviewMutationResult.failure(
                    "invalid_base_url", "GitHub base URL must be an HTTPS origin.");
        }
        if (session.failure() != null) return session.failure();

        try {
            String reviewPath =
                    pullPath(params.owner(), params.repo(), params.number())
                            + "/reviews/"
                            + params.reviewId();
            // An explicit submit body replaces the pending body, so read it first and republish
            // its visible sections; never submit when that read fails.
            RestResponse pendingResponse =
                    client.get(session.apiBase(), session.token(), reviewPath);
            requireSuccess(pendingResponse);
            JsonNode pendingBody = readJson(pendingResponse.body()).path("body");
            String retained =
                    codec.visibleBody(pendingBody.isTextual() ? pendingBody.textValue() : "");
            ObjectNode payload = mapper.createObjectNode();
            payload.put("event", params.event());
            payload.put(
                    "body",
                    effectiveBody(params.event(), composeSubmitBody(params.body(), retained)));
            requireSuccess(
                    client.post(
                            session.apiBase(),
                            session.token(),
                            reviewPath + "/events",
                            writeJson(payload)));
            return DraftReviewMutationResult.ok("Review submitted.");
        } catch (MutationException exception) {
            return DraftReviewMutationResult.failure(exception.status(), exception.getMessage());
        }
    }

    public DraftReviewMutationResult delete(DeleteParams params) {
        if (!valid(params.owner())
                || !valid(params.repo())
                || params.number() <= 0
                || !REVIEW_ID
                        .matcher(params.reviewId() == null ? "" : params.reviewId())
                        .matches()) {
            return DraftReviewMutationResult.failure(
                    "invalid_request", "Pull request identity is invalid.");
        }
        Session session = openSession(params.baseUrl());
        if (session == null) {
            return DraftReviewMutationResult.failure(
                    "invalid_base_url", "GitHub base URL must be an HTTPS origin.");
        }
        if (session.failure() != null) return session.failure();

        try {
            String url =
                    pullPath(params.owner(), params.repo(), params.number())
                            + "/reviews/"
                            + params.reviewId();
            requireSuccess(client.delete(session.apiBase(), session.token(), url));
            return DraftReviewMutationResult.ok("Draft review deleted.");
        } catch (MutationException exception) {
            return DraftReviewMutationResult.failure(exception.status(), exception.getMessage());
        }
    }

    /** Joins the non-blank reviewer text and retained visible pending body, in that order. */
    private static String composeSubmitBody(String reviewerText, String retainedBody) {
        List<String> parts = new ArrayList<>();
        String reviewer = reviewerText == null ? "" : reviewerText.strip();
        if (!reviewer.isEmpty()) parts.add(reviewer);
        if (retainedBody != null && !retainedBody.isBlank()) parts.add(retainedBody);
        return String.join("\n\n", parts);
    }

    // A live check on 2026-09-25 showed that, for COMMENT, an explicit submit body replaces the
    // pending review body and an omitted body keeps it. submit therefore always sends the composed
    // body; this canned fallback remains only for the case where both the reviewer text and the
    // retained sections are empty, because empty-body behavior was not re-tested.
    private static String effectiveBody(String event, String body) {
        if (body != null && !body.isBlank()) return body;
        return switch (event) {
            case "APPROVE" -> "Looks good to me!";
            case "REQUEST_CHANGES" -> "Requesting changes.";
            case "COMMENT" -> "Leaving comments.";
            default -> body == null ? "" : body;
        };
    }

    private PendingReview findPendingReview(Session session, String basePath) {
        for (int page = 1; page <= MAX_REVIEW_PAGES; page++) {
            RestResponse response =
                    client.get(
                            session.apiBase(),
                            session.token(),
                            basePath + "/reviews?per_page=" + PAGE_SIZE + "&page=" + page);
            requireSuccess(response);
            JsonNode reviews = readJson(response.body());
            if (!reviews.isArray())
                throw new MutationException("api_failed", "GitHub API request failed.");
            for (JsonNode review : reviews) {
                if ("PENDING".equals(review.path("state").asText(null))) {
                    String id =
                            review.path("id").isMissingNode() ? null : review.path("id").asText();
                    return id == null || id.isBlank()
                            ? null
                            : new PendingReview(
                                    id,
                                    review.path("commit_id").isTextual()
                                            ? review.path("commit_id").textValue()
                                            : null,
                                    review.path("body").asText(""));
                }
            }
            if (reviews.size() < PAGE_SIZE) return null;
            if (page == MAX_REVIEW_PAGES)
                throw new MutationException(
                        "api_failed", "GitHub pending review pagination limit was exceeded.");
        }
        return null;
    }

    private String getHeadSha(Session session, String basePath) {
        RestResponse response = client.get(session.apiBase(), session.token(), basePath);
        requireSuccess(response);
        JsonNode detail = readJson(response.body());
        String sha = detail.path("head").path("sha").asText("");
        if (sha.isBlank()) throw new MutationException("api_failed", "GitHub API request failed.");
        return sha;
    }

    private List<DraftReviewCodec.LineComment> toLineComments(List<CommentInput> comments) {
        List<DraftReviewCodec.LineComment> result = new ArrayList<>();
        for (CommentInput c : comments) {
            result.add(
                    new DraftReviewCodec.LineComment(
                            c.file(),
                            c.line(),
                            c.type(),
                            c.body(),
                            c.severity(),
                            c.category(),
                            c.confidence(),
                            c.rationale()));
        }
        return result;
    }

    private static String pullPath(String owner, String repo, int number) {
        return "/repos/"
                + URLEncoder.encode(owner, StandardCharsets.UTF_8)
                + "/"
                + URLEncoder.encode(repo, StandardCharsets.UTF_8)
                + "/pulls/"
                + number;
    }

    private void requireSuccess(RestResponse response) {
        if (!isSuccess(response)) {
            throw new MutationException("api_failed", "GitHub API request failed.");
        }
    }

    private static boolean isSuccess(RestResponse response) {
        return response.statusCode() >= 200 && response.statusCode() < 300;
    }

    static boolean isRetryableHttpMethod(String method) {
        return !"POST".equals(method);
    }

    private JsonNode readJson(String body) {
        try {
            return mapper.readTree(body);
        } catch (IOException exception) {
            throw new MutationException("api_failed", "GitHub API request failed.");
        }
    }

    private String writeJson(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (IOException exception) {
            throw new MutationException("api_failed", "GitHub API request failed.");
        }
    }

    private boolean valid(String value) {
        return value != null && SEGMENT.matcher(value).matches();
    }

    private Session openSession(String baseUrl) {
        GitHubApiBase urls = GitHubApiBase.parse(baseUrl);
        if (urls == null) return null;
        GitHubAuthService.TokenResolution token = tokenResolver.resolve(urls.hostnameArgument());
        if (token.status() == GitHubAuthService.TokenStatus.NOT_INSTALLED) {
            return Session.failed(
                    DraftReviewMutationResult.failure(
                            "not_installed", "GitHub CLI is not installed."));
        }
        if (token.status() != GitHubAuthService.TokenStatus.RESOLVED) {
            return Session.failed(
                    DraftReviewMutationResult.failure(
                            "not_authenticated",
                            "Run 'gh auth login' in a terminal for this GitHub host."));
        }
        return Session.ready(urls.apiBaseUrl(), token.token());
    }

    private record Session(String apiBase, String token, DraftReviewMutationResult failure) {
        static Session ready(String apiBase, String token) {
            return new Session(apiBase, token, null);
        }

        static Session failed(DraftReviewMutationResult failure) {
            return new Session(null, null, failure);
        }
    }

    private record PendingReview(String id, String commitId, String body) {}

    /** A single inline or general comment supplied by the caller (host UI). */
    public record CommentInput(
            String file,
            int line,
            String type,
            String body,
            String severity,
            String category,
            String confidence,
            String rationale) {}

    public record SaveParams(
            String baseUrl,
            String owner,
            String repo,
            int number,
            String summary,
            String verdict,
            List<CommentInput> lineComments,
            List<CommentInput> orphans) {}

    public record SubmitParams(
            String baseUrl,
            String owner,
            String repo,
            int number,
            String reviewId,
            String event,
            String body) {}

    public record DeleteParams(
            String baseUrl, String owner, String repo, int number, String reviewId) {}

    /** Generic token-bearing GitHub REST verb client; non-infra 4xx statuses are returned as-is. */
    interface GitHubRestClient {
        RestResponse get(String apiBase, String token, String path);

        RestResponse post(String apiBase, String token, String path, String jsonBody);

        RestResponse put(String apiBase, String token, String path, String jsonBody);

        RestResponse delete(String apiBase, String token, String path);
    }

    /** Raw HTTP outcome; only genuine infra failures (auth/rate-limit/5xx/network) throw. */
    record RestResponse(int statusCode, String body) {}

    /** Thrown to signal a token-free domain failure status. */
    static final class MutationException extends RuntimeException {
        private final String status;

        MutationException(String status, String message) {
            super(message);
            this.status = status;
        }

        String status() {
            return status;
        }
    }

    /**
     * Real GitHub REST client backed by {@link HttpClient}. Never logs or returns the token.
     *
     * <p>Deliberately does <em>not</em> use the shared {@code GitHubHttpClient}: that client's
     * retry policy is only safe for idempotent reads. These verbs create and submit reviews, so
     * merging them would risk a retried POST duplicating a submitted review. Keep write transport
     * separate until mutation retries are made idempotent (e.g. via a request key).
     */
    private static final class HttpGitHubRestClient implements GitHubRestClient {
        private final HttpClient httpClient =
                HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

        @Override
        public RestResponse get(String apiBase, String token, String path) {
            return send("GET", apiBase + path, token, null);
        }

        @Override
        public RestResponse post(String apiBase, String token, String path, String jsonBody) {
            return send("POST", apiBase + path, token, jsonBody);
        }

        @Override
        public RestResponse put(String apiBase, String token, String path, String jsonBody) {
            return send("PUT", apiBase + path, token, jsonBody);
        }

        @Override
        public RestResponse delete(String apiBase, String token, String path) {
            return send("DELETE", apiBase + path, token, null);
        }

        private RestResponse send(String method, String url, String token, String jsonBody) {
            URI uri = URI.create(url);
            int maxAttempts = isRetryableHttpMethod(method) ? MAX_ATTEMPTS : 1;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    HttpRequest.Builder builder =
                            HttpRequest.newBuilder(uri)
                                    .timeout(TIMEOUT)
                                    .header("Authorization", "Bearer " + token)
                                    .header("Accept", "application/vnd.github.v3+json")
                                    .header("X-GitHub-Api-Version", "2022-11-28")
                                    .header("User-Agent", "pr-pilot-sidecar/0.1");
                    HttpRequest request =
                            switch (method) {
                                case "GET" -> builder.GET().build();
                                case "DELETE" -> builder.DELETE().build();
                                case "POST" ->
                                        builder.header("Content-Type", "application/json")
                                                .POST(
                                                        HttpRequest.BodyPublishers.ofString(
                                                                jsonBody, StandardCharsets.UTF_8))
                                                .build();
                                case "PUT" ->
                                        builder.header("Content-Type", "application/json")
                                                .PUT(
                                                        HttpRequest.BodyPublishers.ofString(
                                                                jsonBody, StandardCharsets.UTF_8))
                                                .build();
                                default -> throw new IllegalArgumentException(method);
                            };
                    HttpResponse<String> response =
                            httpClient.send(
                                    request,
                                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    int statusCode = response.statusCode();
                    if (statusCode == 401 || statusCode == 403) {
                        throw new MutationException(
                                "not_authenticated",
                                "Run 'gh auth login' in a terminal for this GitHub host.");
                    }
                    if (statusCode == 429) {
                        if (attempt < maxAttempts) {
                            backoff(attempt);
                            continue;
                        }
                        throw new MutationException(
                                "rate_limited", "GitHub rate limit exceeded. Try again shortly.");
                    }
                    if (statusCode >= 500) {
                        if (attempt < maxAttempts) {
                            backoff(attempt);
                            continue;
                        }
                        throw new MutationException(
                                "network_error", "Unable to reach GitHub. Check your connection.");
                    }
                    return new RestResponse(statusCode, response.body());
                } catch (IOException exception) {
                    if (attempt == maxAttempts) {
                        throw new MutationException(
                                "network_error", "Unable to reach GitHub. Check your connection.");
                    }
                    backoff(attempt);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new MutationException(
                            "network_error", "Unable to reach GitHub. Check your connection.");
                }
            }
            throw new MutationException(
                    "network_error", "Unable to reach GitHub. Check your connection.");
        }

        private void backoff(int attempt) {
            try {
                Thread.sleep(250L * attempt);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
