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

/** Lists pull requests without exposing the GitHub token outside the sidecar process. */
public final class PrListService {
    private static final int RESULT_LIMIT = 50;
    private static final int REQUEST_LIMIT = RESULT_LIMIT + 1;

    private final GitHubAuthService.TokenResolver tokenResolver;
    private final SearchClient searchClient;
    private final ReviewStatusClient reviewStatusClient;
    private final PrSearchQueryService queryService;

    public PrListService() {
        this(
                new GitHubAuthService.ProcessTokenResolver(),
                new HttpSearchClient(),
                new PrReviewStatusService(),
                new PrSearchQueryService());
    }

    PrListService(
            GitHubAuthService.TokenResolver tokenResolver,
            SearchClient searchClient,
            ReviewStatusClient reviewStatusClient,
            PrSearchQueryService queryService) {
        this.tokenResolver = Objects.requireNonNull(tokenResolver);
        this.searchClient = Objects.requireNonNull(searchClient);
        this.reviewStatusClient = Objects.requireNonNull(reviewStatusClient);
        this.queryService = Objects.requireNonNull(queryService);
    }

    public PrListResult list(PrListParams params) {
        GitHubApiBase baseUrls;
        try {
            baseUrls = GitHubApiBase.require(params.githubBaseUrl());
        } catch (IllegalArgumentException exception) {
            return PrListResult.failure(
                    "invalid_base_url", "GitHub base URL must be an HTTPS origin.");
        }

        GitHubAuthService.TokenResolution tokenResolution =
                tokenResolver.resolve(baseUrls.hostnameArgument());
        if (tokenResolution.status() == GitHubAuthService.TokenStatus.NOT_INSTALLED) {
            return PrListResult.failure("not_installed", "GitHub CLI is not installed.");
        }
        if (tokenResolution.status() != GitHubAuthService.TokenStatus.RESOLVED) {
            return PrListResult.failure(
                    "not_authenticated", "Run 'gh auth login' in a terminal for this GitHub host.");
        }

        String query =
                queryService
                        .build(
                                new PrSearchQueryService.QueryParams(
                                        params.state(), params.searchScope(), params.currentRepo()))
                        .query();
        SearchResponse response =
                searchClient.search(baseUrls.apiBaseUrl(), tokenResolution.token(), query);
        return switch (response.status()) {
            case OK -> {
                ReviewStatusResponse reviewStatuses =
                        reviewStatusClient.enrich(
                                baseUrls, tokenResolution.token(), response.prs());
                yield PrListResult.success(
                        query,
                        response.limited(),
                        reviewStatuses.available(),
                        reviewStatuses.prs());
            }
            case NOT_AUTHENTICATED ->
                    PrListResult.failure(
                            "not_authenticated",
                            "Run 'gh auth login' in a terminal for this GitHub host.");
            case RATE_LIMITED ->
                    PrListResult.failure(
                            "rate_limited", "GitHub rate limit exceeded. Try again shortly.");
            case NETWORK_ERROR ->
                    PrListResult.failure(
                            "network_error", "Unable to reach GitHub. Check your connection.");
            case TLS_ERROR ->
                    PrListResult.failure(
                            GitHubFailure.TLS_UNTRUSTED.status(),
                            GitHubFailure.TLS_UNTRUSTED.message());
            case API_FAILED -> PrListResult.failure("api_failed", "GitHub API request failed.");
        };
    }

    public record PrListParams(
            String githubBaseUrl, String state, String searchScope, String currentRepo) {}

    interface SearchClient {
        SearchResponse search(String apiBaseUrl, String token, String query);
    }

    interface ReviewStatusClient {
        ReviewStatusResponse enrich(
                GitHubApiBase baseUrls, String token, List<PullRequestSummary> pullRequests);
    }

    record ReviewStatusResponse(boolean available, List<PullRequestSummary> prs) {
        ReviewStatusResponse {
            prs = List.copyOf(prs);
        }
    }

    record SearchResponse(SearchStatus status, boolean limited, List<PullRequestSummary> prs) {
        static SearchResponse success(boolean limited, List<PullRequestSummary> prs) {
            return new SearchResponse(SearchStatus.OK, limited, List.copyOf(prs));
        }

        static SearchResponse of(SearchStatus status) {
            return new SearchResponse(status, false, List.of());
        }
    }

    enum SearchStatus {
        OK,
        NOT_AUTHENTICATED,
        RATE_LIMITED,
        NETWORK_ERROR,
        TLS_ERROR,
        API_FAILED
    }

    private static final class HttpSearchClient implements SearchClient {
        private final GitHubHttpClient httpClient = new GitHubHttpClient();

        @Override
        public SearchResponse search(String apiBaseUrl, String token, String query) {
            String url =
                    apiBaseUrl
                            + "/search/issues?q="
                            + URLEncoder.encode(query, StandardCharsets.UTF_8)
                            + "&per_page="
                            + REQUEST_LIMIT
                            + "&sort=updated";
            GitHubResponse response = httpClient.get(url, token);
            if (response.isUnauthenticated()) {
                return SearchResponse.of(SearchStatus.NOT_AUTHENTICATED);
            }
            if (response.isRateLimited()) {
                return SearchResponse.of(SearchStatus.RATE_LIMITED);
            }
            if (response.isTlsError()) {
                return SearchResponse.of(SearchStatus.TLS_ERROR);
            }
            if (response.isNetworkError()) {
                return SearchResponse.of(SearchStatus.NETWORK_ERROR);
            }
            if (!response.isSuccess()) {
                return SearchResponse.of(SearchStatus.API_FAILED);
            }
            return parseSearchResponse(response.body());
        }
    }

    static SearchResponse parseSearchResponse(String body) {
        try {
            JsonNode items = new ObjectMapper().readTree(body).path("items");
            if (!items.isArray()) {
                return SearchResponse.of(SearchStatus.API_FAILED);
            }
            List<PullRequestSummary> prs = new ArrayList<>();
            for (JsonNode item : items) {
                if (prs.size() == REQUEST_LIMIT) {
                    break;
                }
                PullRequestSummary pr = map(item);
                if (pr == null) {
                    return SearchResponse.of(SearchStatus.API_FAILED);
                }
                prs.add(pr);
            }
            boolean limited = prs.size() > RESULT_LIMIT;
            return SearchResponse.success(limited, limited ? prs.subList(0, RESULT_LIMIT) : prs);
        } catch (IOException exception) {
            return SearchResponse.of(SearchStatus.API_FAILED);
        }
    }

    private static PullRequestSummary map(JsonNode item) {
        String repositoryUrl = text(item, "repository_url");
        if (repositoryUrl == null) {
            return null;
        }
        String[] repositoryParts = repositoryUrl.split("/");
        if (repositoryParts.length < 2) {
            return null;
        }
        JsonNode user = item.path("user");
        if (!item.path("number").canConvertToInt()
                || !item.path("title").isTextual()
                || !item.path("html_url").isTextual()
                || !item.path("created_at").isTextual()
                || !user.path("login").isTextual()) {
            return null;
        }
        return new PullRequestSummary(
                item.path("number").intValue(),
                text(item, "title"),
                repositoryParts[repositoryParts.length - 2],
                repositoryParts[repositoryParts.length - 1],
                user.path("login").textValue(),
                text(item, "created_at"),
                text(item, "html_url"),
                item.path("draft").asBoolean(false));
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).textValue();
    }
}
