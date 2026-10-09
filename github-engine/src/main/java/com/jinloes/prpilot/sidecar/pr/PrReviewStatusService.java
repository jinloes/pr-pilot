package com.jinloes.prpilot.sidecar.pr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinloes.prpilot.model.ReviewStatus;
import com.jinloes.prpilot.sidecar.github.GitHubApiBase;
import com.jinloes.prpilot.sidecar.github.GitHubHttpClient;
import com.jinloes.prpilot.sidecar.github.GitHubResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Adds authenticated-user review freshness to a bounded pull-request list. */
final class PrReviewStatusService implements PrListService.ReviewStatusClient {
    private static final int MAX_PULL_REQUESTS = 50;
    private static final Duration OPTIONAL_REQUEST_TIMEOUT = Duration.ofSeconds(3);
    private static final Set<String> SUBMITTED_STATES =
            Set.of("APPROVED", "CHANGES_REQUESTED", "COMMENTED");
    private static final String REVIEW_FIELDS =
            "headRefOid reviews(last: 1, author: $viewer, "
                    + "states: [APPROVED, CHANGES_REQUESTED, COMMENTED]) "
                    + "{ nodes { state submittedAt commit { oid } } }";
    private static final int MAX_BASELINE_COMMIT_PAGES = 3;
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");
    private static final String BASELINE_QUERY =
            "query ReviewBaseline($viewer: String!, $owner: String!, $repo: String!, $number:"
                    + " Int!, $cursor: String) { repository(owner: $owner, name: $repo) {"
                    + " pullRequest(number: $number) { "
                    + REVIEW_FIELDS
                    + " commits(last: 100, before: $cursor) { pageInfo { hasPreviousPage"
                    + " startCursor } nodes { commit { oid } } } } } }";

    private final ApiClient client;
    private final ObjectMapper mapper;

    PrReviewStatusService() {
        this(new HttpApiClient(), new ObjectMapper());
    }

    PrReviewStatusService(ApiClient client, ObjectMapper mapper) {
        this.client = Objects.requireNonNull(client);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public PrListService.ReviewStatusResponse enrich(
            GitHubApiBase baseUrls, String token, List<PullRequestSummary> pullRequests) {
        if (pullRequests.isEmpty()) {
            return new PrListService.ReviewStatusResponse(true, pullRequests);
        }
        if (pullRequests.size() > MAX_PULL_REQUESTS) {
            return unavailable(pullRequests);
        }
        String viewer = viewerLogin(baseUrls.apiBaseUrl(), token);
        if (viewer == null) {
            return unavailable(pullRequests);
        }

        String requestBody;
        try {
            requestBody = requestBody(viewer, pullRequests);
        } catch (IOException exception) {
            return unavailable(pullRequests);
        }
        GitHubResponse response = client.postJson(baseUrls.graphqlUrl(), token, requestBody);
        if (!response.isSuccess()) {
            return unavailable(pullRequests);
        }
        try {
            JsonNode root = mapper.readTree(response.body());
            JsonNode errors = root.path("errors");
            JsonNode data = root.path("data");
            if ((errors.isArray() && !errors.isEmpty()) || !data.isObject()) {
                return unavailable(pullRequests);
            }

            boolean available = true;
            List<PullRequestSummary> enriched = new ArrayList<>(pullRequests.size());
            for (int index = 0; index < pullRequests.size(); index++) {
                ReviewStatus reviewStatus =
                        reviewStatus(data.path(alias(index)).path("pullRequest"));
                available &= reviewStatus != ReviewStatus.UNAVAILABLE;
                enriched.add(pullRequests.get(index).withReviewStatus(reviewStatus));
            }
            return new PrListService.ReviewStatusResponse(available, enriched);
        } catch (IOException exception) {
            return unavailable(pullRequests);
        }
    }

    private String viewerLogin(String apiBaseUrl, String token) {
        GitHubResponse response = client.get(apiBaseUrl + "/user", token);
        return response.isSuccess() ? login(response.body()) : null;
    }

    private String login(String body) {
        try {
            JsonNode login = mapper.readTree(body).path("login");
            return login.isTextual() && !login.textValue().isBlank() ? login.textValue() : null;
        } catch (IOException exception) {
            return null;
        }
    }

    private String requestBody(String viewer, List<PullRequestSummary> pullRequests)
            throws IOException {
        StringBuilder declaration = new StringBuilder("query ReviewStatus($viewer: String!");
        StringBuilder selection = new StringBuilder(") {");
        ObjectNode variables = mapper.createObjectNode();
        variables.put("viewer", viewer);
        for (int index = 0; index < pullRequests.size(); index++) {
            declaration
                    .append(", $owner")
                    .append(index)
                    .append(": String!, $repo")
                    .append(index)
                    .append(": String!, $number")
                    .append(index)
                    .append(": Int!");
            selection
                    .append(' ')
                    .append(alias(index))
                    .append(": repository(owner: $owner")
                    .append(index)
                    .append(", name: $repo")
                    .append(index)
                    .append(") { pullRequest(number: $number")
                    .append(index)
                    .append(") { ")
                    .append(REVIEW_FIELDS)
                    .append(" } }");
            PullRequestSummary pullRequest = pullRequests.get(index);
            variables.put("owner" + index, pullRequest.owner());
            variables.put("repo" + index, pullRequest.repo());
            variables.put("number" + index, pullRequest.number());
        }
        selection.append(" }");

        ObjectNode body = mapper.createObjectNode();
        body.put("query", declaration.append(selection).toString());
        body.set("variables", variables);
        return mapper.writeValueAsString(body);
    }

    static ReviewStatus reviewStatus(JsonNode pullRequest) {
        JsonNode headRefOid = pullRequest.path("headRefOid");
        if (!pullRequest.isObject()
                || !headRefOid.isTextual()
                || headRefOid.textValue().isBlank()) {
            return ReviewStatus.UNAVAILABLE;
        }
        LatestReview latest = latestReview(pullRequest.path("reviews").path("nodes"));
        if (!latest.available()) {
            return ReviewStatus.UNAVAILABLE;
        }
        if (latest.commitOid() == null) {
            return ReviewStatus.UNREVIEWED;
        }
        return headRefOid.textValue().equals(latest.commitOid())
                ? ReviewStatus.REVIEWED
                : ReviewStatus.UPDATED_SINCE_REVIEW;
    }

    /**
     * Selects the commit of the viewer's latest submitted review, the single rule shared by the
     * list badge and the incremental-review baseline. A null OID on an available result means no
     * submitted review exists.
     */
    static LatestReview latestReview(JsonNode reviews) {
        if (!reviews.isArray()) {
            return LatestReview.UNAVAILABLE;
        }
        JsonNode latest = null;
        Instant latestSubmittedAt = null;
        for (JsonNode review : reviews) {
            if (!SUBMITTED_STATES.contains(review.path("state").asText())) {
                continue;
            }
            String submittedAt = review.path("submittedAt").asText("");
            Instant submitted;
            try {
                submitted = Instant.parse(submittedAt);
            } catch (DateTimeParseException exception) {
                return LatestReview.UNAVAILABLE;
            }
            if (latestSubmittedAt == null || !submitted.isBefore(latestSubmittedAt)) {
                latest = review;
                latestSubmittedAt = submitted;
            }
        }
        if (latest == null) {
            return new LatestReview(true, null);
        }
        JsonNode reviewedOid = latest.path("commit").path("oid");
        if (!reviewedOid.isTextual() || reviewedOid.textValue().isBlank()) {
            return LatestReview.UNAVAILABLE;
        }
        return new LatestReview(true, reviewedOid.textValue());
    }

    /**
     * Resolves the incremental-review baseline: the badge's latest-review commit, proved to be in
     * the pull request's current history by paging its commit OIDs backwards from the head.
     */
    ReviewBaseline reviewBaseline(
            GitHubApiBase baseUrls, String token, String owner, String repo, int number) {
        GitHubResponse userResponse = client.get(baseUrls.apiBaseUrl() + "/user", token);
        if (userResponse.statusCode() == 401) {
            return ReviewBaseline.of(BaselineOutcome.NOT_AUTHENTICATED);
        }
        String viewer = userResponse.isSuccess() ? login(userResponse.body()) : null;
        if (viewer == null) {
            return ReviewBaseline.of(BaselineOutcome.FAILED);
        }

        String headSha = null;
        String baselineSha = null;
        String cursor = null;
        for (int page = 1; page <= MAX_BASELINE_COMMIT_PAGES; page++) {
            JsonNode pullRequest;
            try {
                GitHubResponse response =
                        client.postJson(
                                baseUrls.graphqlUrl(),
                                token,
                                baselineRequestBody(viewer, owner, repo, number, cursor));
                if (response.statusCode() == 401) {
                    return ReviewBaseline.of(BaselineOutcome.NOT_AUTHENTICATED);
                }
                if (!response.isSuccess()) {
                    return ReviewBaseline.of(BaselineOutcome.FAILED);
                }
                JsonNode root = mapper.readTree(response.body());
                JsonNode errors = root.path("errors");
                if ((errors.isArray() && !errors.isEmpty()) || !root.path("data").isObject()) {
                    return ReviewBaseline.of(BaselineOutcome.FAILED);
                }
                pullRequest = root.path("data").path("repository").path("pullRequest");
            } catch (IOException exception) {
                return ReviewBaseline.of(BaselineOutcome.FAILED);
            }
            if (!pullRequest.isObject()) {
                return ReviewBaseline.of(BaselineOutcome.NOT_FOUND);
            }
            if (page == 1) {
                JsonNode headRefOid = pullRequest.path("headRefOid");
                if (!headRefOid.isTextual() || !isSha(headRefOid.textValue())) {
                    return ReviewBaseline.of(BaselineOutcome.FAILED);
                }
                headSha = headRefOid.textValue();
                LatestReview latest = latestReview(pullRequest.path("reviews").path("nodes"));
                if (!latest.available()) {
                    return ReviewBaseline.of(BaselineOutcome.FAILED);
                }
                if (latest.commitOid() == null) {
                    return new ReviewBaseline(BaselineOutcome.NO_PRIOR_REVIEW, headSha, null);
                }
                if (!isSha(latest.commitOid())) {
                    return ReviewBaseline.of(BaselineOutcome.FAILED);
                }
                baselineSha = latest.commitOid();
                if (baselineSha.equals(headSha)) {
                    return new ReviewBaseline(BaselineOutcome.UP_TO_DATE, headSha, baselineSha);
                }
            }
            JsonNode commits = pullRequest.path("commits");
            JsonNode nodes = commits.path("nodes");
            if (!nodes.isArray()) {
                return ReviewBaseline.of(BaselineOutcome.FAILED);
            }
            for (JsonNode node : nodes) {
                if (baselineSha.equals(node.path("commit").path("oid").asText())) {
                    return new ReviewBaseline(BaselineOutcome.FOUND, headSha, baselineSha);
                }
            }
            JsonNode pageInfo = commits.path("pageInfo");
            JsonNode startCursor = pageInfo.path("startCursor");
            if (!pageInfo.path("hasPreviousPage").asBoolean(false)
                    || !startCursor.isTextual()
                    || startCursor.textValue().isBlank()) {
                break;
            }
            cursor = startCursor.textValue();
        }
        return new ReviewBaseline(BaselineOutcome.NOT_IN_HISTORY, headSha, baselineSha);
    }

    private String baselineRequestBody(
            String viewer, String owner, String repo, int number, String cursor)
            throws IOException {
        ObjectNode variables = mapper.createObjectNode();
        variables.put("viewer", viewer);
        variables.put("owner", owner);
        variables.put("repo", repo);
        variables.put("number", number);
        if (cursor == null) {
            variables.putNull("cursor");
        } else {
            variables.put("cursor", cursor);
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("query", BASELINE_QUERY);
        body.set("variables", variables);
        return mapper.writeValueAsString(body);
    }

    static boolean isSha(String value) {
        return value != null && SHA.matcher(value).matches();
    }

    record LatestReview(boolean available, String commitOid) {
        static final LatestReview UNAVAILABLE = new LatestReview(false, null);
    }

    enum BaselineOutcome {
        NOT_AUTHENTICATED,
        FAILED,
        NOT_FOUND,
        NO_PRIOR_REVIEW,
        UP_TO_DATE,
        NOT_IN_HISTORY,
        FOUND
    }

    record ReviewBaseline(BaselineOutcome outcome, String headSha, String baselineSha) {
        static ReviewBaseline of(BaselineOutcome outcome) {
            return new ReviewBaseline(outcome, null, null);
        }
    }

    private static PrListService.ReviewStatusResponse unavailable(
            List<PullRequestSummary> pullRequests) {
        return new PrListService.ReviewStatusResponse(
                false,
                pullRequests.stream()
                        .map(pr -> pr.withReviewStatus(ReviewStatus.UNAVAILABLE))
                        .toList());
    }

    private static String alias(int index) {
        return "pr" + index;
    }

    interface ApiClient {
        GitHubResponse get(String url, String token);

        GitHubResponse postJson(String url, String token, String body);
    }

    static final class HttpApiClient implements ApiClient {
        private final GitHubHttpClient httpClient = new GitHubHttpClient();
        private final Duration timeout;

        HttpApiClient() {
            this(OPTIONAL_REQUEST_TIMEOUT);
        }

        HttpApiClient(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout);
        }

        @Override
        public GitHubResponse get(String url, String token) {
            return httpClient.getOnce(url, token, timeout);
        }

        @Override
        public GitHubResponse postJson(String url, String token, String body) {
            return httpClient.postJsonOnce(url, token, body, timeout);
        }
    }
}
