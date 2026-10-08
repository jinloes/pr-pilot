package com.jinloes.prpilot.sidecar.pr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinloes.prpilot.sidecar.github.GitHubApiBase;
import com.jinloes.prpilot.sidecar.github.GitHubAuthService;
import com.jinloes.prpilot.sidecar.github.GitHubHttpClient;
import com.jinloes.prpilot.sidecar.github.GitHubResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Provides token-safe GitHub reads used by notifications and review prompt context. */
public final class PrSupplementalService {
    private static final int MAX_QUERY_LENGTH = 8 * 1024;
    private static final int STARRED_LIMIT = 200;
    private static final int PAGE_SIZE = 100;
    private static final int MAX_EXISTING_REVIEWS = 200;
    private static final int MAX_EXISTING_REVIEW_COMMENTS = 500;
    static final int MAX_EXISTING_REVIEWS_CHARS = 12_000;
    private static final String CONTEXT_TRUNCATION_MARKER =
            "\n...(existing review context truncated)";
    private static final String INLINE_COMMENTS_UNAVAILABLE_MARKER =
            "(Inline review comments were unavailable.)";
    private static final String THREAD_STATE_UNAVAILABLE_MARKER =
            "(Thread resolution state was unavailable.)";
    private static final int MAX_THREAD_STATE_PAGES = 5;
    private static final Duration OPTIONAL_REQUEST_TIMEOUT = Duration.ofSeconds(3);
    private static final String THREAD_STATE_QUERY =
            "query ThreadState($owner: String!, $repo: String!, $number: Int!, $cursor: String) {"
                    + " repository(owner: $owner, name: $repo) { pullRequest(number: $number) {"
                    + " reviewThreads(first: 100, after: $cursor) {"
                    + " pageInfo { hasNextPage endCursor }"
                    + " nodes { isResolved comments(first: 1) { nodes { fullDatabaseId } } }"
                    + " } } } }";
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_.-]+");

    private final GitHubAuthService.TokenResolver tokenResolver;
    private final ApiClient client;
    private final ObjectMapper mapper;

    public PrSupplementalService() {
        this(new GitHubAuthService.ProcessTokenResolver(), new HttpApiClient(), new ObjectMapper());
    }

    PrSupplementalService(
            GitHubAuthService.TokenResolver tokenResolver, ApiClient client, ObjectMapper mapper) {
        this.tokenResolver = Objects.requireNonNull(tokenResolver);
        this.client = Objects.requireNonNull(client);
        this.mapper = Objects.requireNonNull(mapper);
    }

    public PrSearchResult search(SearchParams params) {
        int limit = Math.max(1, Math.min(params.limit(), 100));
        if (params.query() == null
                || params.query().isBlank()
                || params.query().length() > MAX_QUERY_LENGTH
                || params.limit() < 1
                || params.limit() > 100) {
            return PrSearchResult.failure(
                    "invalid_request", "Pull request search is invalid.", limit);
        }
        Session session = openSession(params.githubBaseUrl());
        if (session.failure() != null) {
            return PrSearchResult.failure(
                    session.failure().status(), session.failure().message(), limit);
        }
        String path =
                "/search/issues?q="
                        + URLEncoder.encode(params.query(), StandardCharsets.UTF_8)
                        + "&per_page="
                        + (limit + 1)
                        + "&sort=updated";
        GitHubResponse response = client.get(session.apiBase(), session.token(), path);
        Failure failure = failure(response);
        if (failure != null)
            return PrSearchResult.failure(failure.status(), failure.message(), limit);
        try {
            JsonNode items = mapper.readTree(response.body()).path("items");
            if (!items.isArray()) {
                return PrSearchResult.failure(
                        "api_failed", "GitHub API response was invalid.", limit);
            }
            List<PullRequestSummary> prs = new ArrayList<>();
            for (JsonNode item : items) {
                if (prs.size() > limit) break;
                PullRequestSummary pr = mapSearchItem(item);
                if (pr == null) {
                    return PrSearchResult.failure(
                            "api_failed", "GitHub API response was invalid.", limit);
                }
                prs.add(pr);
            }
            boolean limited = prs.size() > limit;
            return PrSearchResult.success(limit, limited, prs.stream().limit(limit).toList());
        } catch (IOException exception) {
            return PrSearchResult.failure("api_failed", "GitHub API response was invalid.", limit);
        }
    }

    private PullRequestSummary mapSearchItem(JsonNode item) {
        String repositoryUrl = item.path("repository_url").textValue();
        String[] parts = repositoryUrl == null ? new String[0] : repositoryUrl.split("/");
        if (parts.length < 2
                || !SEGMENT.matcher(parts[parts.length - 2]).matches()
                || !SEGMENT.matcher(parts[parts.length - 1]).matches()
                || !item.path("number").canConvertToInt()
                || item.path("number").intValue() < 1
                || !item.path("title").isTextual()
                || item.path("title").textValue().isBlank()
                || !item.path("user").path("login").isTextual()
                || item.path("user").path("login").textValue().isBlank()
                || !item.path("created_at").isTextual()
                || item.path("created_at").textValue().isBlank()
                || !item.path("html_url").isTextual()
                || item.path("html_url").textValue().isBlank()) {
            return null;
        }
        return new PullRequestSummary(
                item.path("number").intValue(),
                item.path("title").textValue(),
                parts[parts.length - 2],
                parts[parts.length - 1],
                item.path("user").path("login").textValue(),
                item.path("created_at").textValue(),
                item.path("html_url").textValue(),
                item.path("draft").asBoolean(false));
    }

    public StarredReposResult starred(String githubBaseUrl) {
        Session session = openSession(githubBaseUrl);
        if (session.failure() != null) {
            return StarredReposResult.failure(
                    session.failure().status(), session.failure().message());
        }
        List<String> repositories = new ArrayList<>();
        for (int page = 1; repositories.size() < STARRED_LIMIT; page++) {
            GitHubResponse response =
                    client.get(
                            session.apiBase(),
                            session.token(),
                            "/user/starred?per_page=100&sort=updated&page=" + page);
            Failure failure = failure(response);
            if (failure != null)
                return StarredReposResult.failure(failure.status(), failure.message());
            try {
                JsonNode items = mapper.readTree(response.body());
                if (!items.isArray())
                    return StarredReposResult.failure(
                            "api_failed", "GitHub API response was invalid.");
                if (items.isEmpty()) break;
                for (JsonNode item : items) {
                    String fullName = item.path("full_name").asText("");
                    if (!fullName.isBlank() && repositories.size() < STARRED_LIMIT)
                        repositories.add(fullName);
                }
                if (items.size() < 100) break;
            } catch (IOException exception) {
                return StarredReposResult.failure("api_failed", "GitHub API response was invalid.");
            }
        }
        return StarredReposResult.success(repositories.size() == STARRED_LIMIT, repositories);
    }

    public ExistingReviewsResult existingReviews(IdentityParams params) {
        if (!validIdentity(params))
            return ExistingReviewsResult.failure(
                    "invalid_request", "Pull request identity is invalid.");
        Session session = openSession(params.githubBaseUrl());
        if (session.failure() != null)
            return ExistingReviewsResult.failure(
                    session.failure().status(), session.failure().message());
        String reviewsPath =
                "/repos/"
                        + params.owner()
                        + "/"
                        + params.repo()
                        + "/pulls/"
                        + params.number()
                        + "/reviews";
        PageResult reviews = getAllPages(session, reviewsPath, MAX_EXISTING_REVIEWS);
        if (reviews.failure() != null) {
            return ExistingReviewsResult.failure(
                    reviews.failure().status(), reviews.failure().message());
        }
        String commentsPath =
                "/repos/"
                        + params.owner()
                        + "/"
                        + params.repo()
                        + "/pulls/"
                        + params.number()
                        + "/comments";
        PageResult comments = getAllPages(session, commentsPath, MAX_EXISTING_REVIEW_COMMENTS);
        boolean commentsUnavailable = comments.failure() != null;
        List<JsonNode> commentItems = commentsUnavailable ? List.of() : comments.items();
        Set<Long> resolvedRoots = Set.of();
        boolean threadStateUnavailable = false;
        if (!commentItems.isEmpty()) {
            Set<Long> fetched = resolvedThreadRoots(session, params);
            threadStateUnavailable = fetched == null;
            if (fetched != null) resolvedRoots = fetched;
        }

        Map<String, List<JsonNode>> commentsByReview = new HashMap<>();
        for (JsonNode comment : commentItems) {
            JsonNode reviewId = comment.path("pull_request_review_id");
            if (!reviewId.canConvertToLong()) continue;
            commentsByReview
                    .computeIfAbsent(reviewId.asText(), ignored -> new ArrayList<>())
                    .add(comment);
        }

        List<String> lines = new ArrayList<>();
        for (JsonNode review : reviews.items()) {
            if ("PENDING".equals(review.path("state").asText())) continue;
            String id = review.path("id").asText();
            String reviewer = review.path("user").path("login").asText("");
            String state = review.path("state").asText("COMMENTED");
            String submittedAt = review.path("submitted_at").asText("");
            String date = submittedAt.length() >= 10 ? submittedAt.substring(0, 10) : "";
            lines.add(
                    "Review by @"
                            + reviewer
                            + " ("
                            + state
                            + (date.isEmpty() ? "" : ", " + date)
                            + "):");
            String body = oneLine(review.path("body").asText(""), 300);
            if (!body.isEmpty()) lines.add("  Overall: \"" + body + "\"");
            appendComments(lines, commentsByReview.getOrDefault(id, List.of()), resolvedRoots);
            lines.add("");
        }
        if (commentsUnavailable) {
            lines.add(0, INLINE_COMMENTS_UNAVAILABLE_MARKER);
            lines.add(1, "");
        }
        if (threadStateUnavailable) {
            lines.add(0, THREAD_STATE_UNAVAILABLE_MARKER);
            lines.add(1, "");
        }
        if (reviews.limited() || (!commentsUnavailable && comments.limited())) {
            lines.add("Existing review context limit reached; additional items may be omitted.");
        }
        return ExistingReviewsResult.success(
                capContext(String.join("\n", lines).trim()), commentsUnavailable);
    }

    /**
     * Returns the database IDs of root comments in resolved review threads, or null when thread
     * state is unavailable. All-or-nothing: a failure on any page discards earlier pages.
     */
    private Set<Long> resolvedThreadRoots(Session session, IdentityParams params) {
        Set<Long> resolved = new HashSet<>();
        String cursor = null;
        for (int page = 1; page <= MAX_THREAD_STATE_PAGES; page++) {
            String body;
            try {
                body = threadStateRequestBody(params, cursor);
            } catch (IOException exception) {
                return null;
            }
            GitHubResponse response = client.postJson(session.graphqlUrl(), session.token(), body);
            if (!response.isSuccess()) return null;
            JsonNode threads;
            try {
                JsonNode root = mapper.readTree(response.body());
                JsonNode errors = root.path("errors");
                if (errors.isArray() && !errors.isEmpty()) return null;
                threads =
                        root.path("data")
                                .path("repository")
                                .path("pullRequest")
                                .path("reviewThreads");
            } catch (IOException exception) {
                return null;
            }
            if (!threads.isObject() || !threads.path("nodes").isArray()) return null;
            for (JsonNode thread : threads.path("nodes")) {
                if (!thread.path("isResolved").asBoolean(false)) continue;
                Long rootId =
                        parseLong(
                                thread.path("comments")
                                        .path("nodes")
                                        .path(0)
                                        .path("fullDatabaseId"));
                if (rootId != null) resolved.add(rootId);
            }
            JsonNode pageInfo = threads.path("pageInfo");
            if (!pageInfo.path("hasNextPage").asBoolean(false)) return resolved;
            cursor = pageInfo.path("endCursor").asText(null);
            if (cursor == null) return null;
        }
        return null;
    }

    private String threadStateRequestBody(IdentityParams params, String cursor) throws IOException {
        ObjectNode request = mapper.createObjectNode();
        request.put("query", THREAD_STATE_QUERY);
        ObjectNode variables = request.putObject("variables");
        variables.put("owner", params.owner());
        variables.put("repo", params.repo());
        variables.put("number", params.number());
        if (cursor != null) variables.put("cursor", cursor);
        return mapper.writeValueAsString(request);
    }

    private static Long parseLong(JsonNode value) {
        if (value.isIntegralNumber()) return value.longValue();
        if (!value.isTextual()) return null;
        try {
            return Long.parseLong(value.textValue());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private PageResult getAllPages(Session session, String basePath, int maxItems) {
        List<JsonNode> items = new ArrayList<>();
        boolean limited = false;
        for (int page = 1; ; page++) {
            String separator = basePath.contains("?") ? "&" : "?";
            String path = basePath + separator + "per_page=" + PAGE_SIZE + "&page=" + page;
            GitHubResponse response = client.get(session.apiBase(), session.token(), path);
            Failure failure = failure(response);
            if (failure != null) return new PageResult(List.of(), failure, false);
            JsonNode pageItems;
            try {
                pageItems = mapper.readTree(response.body());
            } catch (IOException exception) {
                return new PageResult(
                        List.of(),
                        new Failure("api_failed", "GitHub API response was invalid."),
                        false);
            }
            if (!pageItems.isArray()) {
                return new PageResult(
                        List.of(),
                        new Failure("api_failed", "GitHub API response was invalid."),
                        false);
            }
            int remaining = maxItems - items.size();
            if (remaining == 0) {
                limited = !pageItems.isEmpty();
                break;
            }
            for (JsonNode item : pageItems) {
                if (items.size() >= maxItems) {
                    limited = true;
                    break;
                }
                items.add(item);
            }
            if (pageItems.size() > remaining) limited = true;
            if (limited) break;
            if (pageItems.size() < PAGE_SIZE) break;
        }
        return new PageResult(List.copyOf(items), null, limited);
    }

    private static void appendComments(
            List<String> lines, List<JsonNode> comments, Set<Long> resolvedRoots) {
        for (JsonNode comment : comments) {
            String text = oneLine(comment.path("body").asText(""), 200);
            if (text.isEmpty()) continue;
            JsonNode currentLine = comment.path("line");
            int originalLine = comment.path("original_line").asInt(0);
            boolean outdated = !currentLine.canConvertToInt() && originalLine > 0;
            int line = currentLine.canConvertToInt() ? currentLine.intValue() : originalLine;
            boolean resolved =
                    isResolved(comment.path("id"), resolvedRoots)
                            || isResolved(comment.path("in_reply_to_id"), resolvedRoots);
            String location = comment.path("path").asText("") + (line > 0 ? ":" + line : "");
            lines.add("  - " + location + tags(resolved, outdated) + ": \"" + text + "\"");
        }
    }

    private static boolean isResolved(JsonNode id, Set<Long> resolvedRoots) {
        return id.canConvertToLong() && resolvedRoots.contains(id.longValue());
    }

    private static String tags(boolean resolved, boolean outdated) {
        if (resolved && outdated) return " [resolved, outdated]";
        if (resolved) return " [resolved]";
        if (outdated) return " [outdated]";
        return "";
    }

    private static String capContext(String summary) {
        if (summary.length() <= MAX_EXISTING_REVIEWS_CHARS) return summary;
        int end = MAX_EXISTING_REVIEWS_CHARS - CONTEXT_TRUNCATION_MARKER.length();
        end = PromptContext.safeTruncationEnd(summary, end);
        return summary.substring(0, Math.max(0, end)) + CONTEXT_TRUNCATION_MARKER;
    }

    private Session openSession(String githubBaseUrl) {
        GitHubApiBase base = GitHubApiBase.parse(githubBaseUrl);
        if (base == null)
            return new Session(
                    null,
                    null,
                    null,
                    new Failure("invalid_base_url", "GitHub base URL must be an HTTPS origin."));
        GitHubAuthService.TokenResolution token = tokenResolver.resolve(base.hostnameArgument());
        if (token.status() == GitHubAuthService.TokenStatus.NOT_INSTALLED)
            return new Session(
                    null, null, null, new Failure("not_installed", "GitHub CLI is not installed."));
        if (token.status() != GitHubAuthService.TokenStatus.RESOLVED)
            return new Session(
                    null,
                    null,
                    null,
                    new Failure(
                            "not_authenticated",
                            "Run 'gh auth login' in a terminal for this GitHub host."));
        return new Session(base.apiBaseUrl(), base.graphqlUrl(), token.token(), null);
    }

    private static Failure failure(GitHubResponse response) {
        if (response.isSuccess()) return null;
        if (response.isRateLimited())
            return new Failure("rate_limited", "GitHub rate limit exceeded. Try again shortly.");
        if (response.isUnauthenticated())
            return new Failure(
                    "not_authenticated", "Run 'gh auth login' in a terminal for this GitHub host.");
        if (response.isNetworkError())
            return new Failure("network_error", "Unable to reach GitHub. Check your connection.");
        return new Failure("api_failed", "GitHub API request failed.");
    }

    private static boolean validIdentity(IdentityParams params) {
        return params.number() > 0
                && params.owner() != null
                && SEGMENT.matcher(params.owner()).matches()
                && params.repo() != null
                && SEGMENT.matcher(params.repo()).matches();
    }

    private static String oneLine(String value, int limit) {
        String normalized = value.trim().replace('\n', ' ');
        return normalized.substring(0, Math.min(normalized.length(), limit));
    }

    public record SearchParams(String githubBaseUrl, String query, int limit) {}

    public record IdentityParams(String githubBaseUrl, String owner, String repo, int number) {}

    interface ApiClient {
        GitHubResponse get(String apiBase, String token, String path);

        GitHubResponse postJson(String url, String token, String body);
    }

    private record Session(String apiBase, String graphqlUrl, String token, Failure failure) {}

    private record Failure(String status, String message) {}

    private record PageResult(List<JsonNode> items, Failure failure, boolean limited) {}

    /** Issues path-relative GETs through the shared GitHub transport. */
    private static final class HttpApiClient implements ApiClient {
        private final GitHubHttpClient httpClient = new GitHubHttpClient();

        @Override
        public GitHubResponse get(String apiBase, String token, String path) {
            return httpClient.get(apiBase + path, token);
        }

        @Override
        public GitHubResponse postJson(String url, String token, String body) {
            return httpClient.postJsonOnce(url, token, body, OPTIONAL_REQUEST_TIMEOUT);
        }
    }
}
