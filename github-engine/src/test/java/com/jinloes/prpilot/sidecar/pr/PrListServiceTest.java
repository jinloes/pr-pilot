package com.jinloes.prpilot.sidecar.pr;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.ReviewStatus;
import com.jinloes.prpilot.sidecar.github.GitHubAuthService;
import com.jinloes.prpilot.sidecar.github.GitHubFailure;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PrListServiceTest {
    @Test
    void listsPullRequestsWithTheNormalizedGitHubDotComApiUrlAndQuery() {
        AtomicReference<String> hostname = new AtomicReference<>("not-called");
        AtomicReference<String> apiBaseUrl = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        PrListService service =
                new PrListService(
                        value -> {
                            hostname.set(value);
                            return GitHubAuthService.TokenResolution.resolved("secret-token");
                        },
                        (baseUrl, token, searchQuery) -> {
                            apiBaseUrl.set(baseUrl);
                            query.set(searchQuery);
                            assertThat(token).isEqualTo("secret-token");
                            return PrListService.SearchResponse.success(
                                    false, List.of(pullRequest(1)));
                        },
                        (baseUrls, token, prs) -> {
                            assertThat(baseUrls.graphqlUrl())
                                    .isEqualTo("https://api.github.com/graphql");
                            return available(prs);
                        },
                        new PrSearchQueryService());

        PrListResult result =
                service.list(
                        new PrListService.PrListParams(
                                "https://github.com/", "open", "currentRepo", "acme/widgets"));

        assertThat(hostname.get()).isNull();
        assertThat(apiBaseUrl.get()).isEqualTo("https://api.github.com");
        assertThat(query.get()).isEqualTo("is:pr is:open repo:acme/widgets");
        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.limited()).isFalse();
        assertThat(result.reviewStatusAvailable()).isTrue();
        assertThat(result.prs())
                .containsExactly(pullRequest(1).withReviewStatus(ReviewStatus.UNREVIEWED));
        assertThat(result.toString()).doesNotContain("secret-token");
    }

    @Test
    void usesEnterpriseApiPrefixAndRetainsTheLimitedFlag() {
        AtomicReference<String> hostname = new AtomicReference<>();
        AtomicReference<String> apiBaseUrl = new AtomicReference<>();
        PrListService service =
                new PrListService(
                        value -> {
                            hostname.set(value);
                            return GitHubAuthService.TokenResolution.resolved("token");
                        },
                        (baseUrl, token, query) -> {
                            apiBaseUrl.set(baseUrl);
                            return PrListService.SearchResponse.success(
                                    true, List.of(pullRequest(2)));
                        },
                        (baseUrls, token, prs) -> available(prs),
                        new PrSearchQueryService());

        PrListResult result =
                service.list(
                        new PrListService.PrListParams(
                                "https://github.example.test", "closed", "assigned", null));

        assertThat(hostname.get()).isEqualTo("github.example.test");
        assertThat(apiBaseUrl.get()).isEqualTo("https://github.example.test/api/v3");
        assertThat(result.query()).isEqualTo("is:pr is:closed assignee:@me");
        assertThat(result.limited()).isTrue();
        assertThat(result.prs()).hasSize(1);
    }

    @Test
    void mapsCliAndGitHubFailuresToTokenFreeDomainResults() {
        PrListService notInstalled =
                new PrListService(
                        ignored -> GitHubAuthService.TokenResolution.notInstalled(),
                        (baseUrl, token, query) -> {
                            throw new AssertionError("search must not be called");
                        },
                        (baseUrls, token, prs) -> available(prs),
                        new PrSearchQueryService());
        PrListService notAuthenticated =
                new PrListService(
                        ignored -> GitHubAuthService.TokenResolution.notAuthenticated(),
                        (baseUrl, token, query) -> {
                            throw new AssertionError("search must not be called");
                        },
                        (baseUrls, token, prs) -> available(prs),
                        new PrSearchQueryService());
        PrListService rateLimited =
                new PrListService(
                        ignored -> GitHubAuthService.TokenResolution.resolved("token"),
                        (baseUrl, token, query) ->
                                PrListService.SearchResponse.of(
                                        PrListService.SearchStatus.RATE_LIMITED),
                        (baseUrls, token, prs) -> available(prs),
                        new PrSearchQueryService());

        assertThat(list(notInstalled).status()).isEqualTo("not_installed");
        assertThat(list(notAuthenticated).status()).isEqualTo("not_authenticated");
        assertThat(list(rateLimited).status()).isEqualTo("rate_limited");
    }

    @Test
    void rejectsUnsafeBaseUrlsBeforeResolvingAToken() {
        AtomicReference<Boolean> resolverCalled = new AtomicReference<>(false);
        PrListService service =
                new PrListService(
                        ignored -> {
                            resolverCalled.set(true);
                            return GitHubAuthService.TokenResolution.resolved("token");
                        },
                        (baseUrl, token, query) ->
                                PrListService.SearchResponse.success(false, List.of()),
                        (baseUrls, token, prs) -> available(prs),
                        new PrSearchQueryService());

        PrListResult result =
                service.list(
                        new PrListService.PrListParams(
                                "https://github.com/api/v3", "open", "authored", null));

        assertThat(result.status()).isEqualTo("invalid_base_url");
        assertThat(resolverCalled.get()).isFalse();
    }

    @Test
    void reportsCertificateFailuresWithTheTruststoreMessageUnderTheNetworkStatus() {
        PrListService tls = searchReturning(PrListService.SearchStatus.TLS_ERROR);
        PrListService network = searchReturning(PrListService.SearchStatus.NETWORK_ERROR);

        PrListResult tlsResult = list(tls);
        PrListResult networkResult = list(network);

        assertThat(tlsResult.status()).isEqualTo("network_error");
        assertThat(tlsResult.message()).isEqualTo(GitHubFailure.TLS_UNTRUSTED.message());
        assertThat(networkResult.status()).isEqualTo("network_error");
        assertThat(networkResult.message()).contains("Unable to reach GitHub");
    }

    private PrListService searchReturning(PrListService.SearchStatus status) {
        return new PrListService(
                ignored -> GitHubAuthService.TokenResolution.resolved("token"),
                (baseUrl, token, query) -> PrListService.SearchResponse.of(status),
                (baseUrls, token, prs) -> available(prs),
                new PrSearchQueryService());
    }

    private PrListResult list(PrListService service) {
        return service.list(
                new PrListService.PrListParams("https://github.com", "open", "authored", null));
    }

    private PullRequestSummary pullRequest(int number) {
        return new PullRequestSummary(
                number,
                "Title " + number,
                "acme",
                "widgets",
                "octocat",
                "2026-01-01T00:00:00Z",
                "https://github.com/acme/widgets/pull/" + number,
                false);
    }

    private static PrListService.ReviewStatusResponse available(
            List<PullRequestSummary> pullRequests) {
        return new PrListService.ReviewStatusResponse(
                true,
                pullRequests.stream()
                        .map(pr -> pr.withReviewStatus(ReviewStatus.UNREVIEWED))
                        .toList());
    }
}
