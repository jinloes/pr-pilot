package com.jinloes.prpilot.sidecar.pr;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.sidecar.github.GitHubAuthService;
import com.jinloes.prpilot.sidecar.github.GitHubResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class IncrementalDiffServiceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HEAD = "b".repeat(40);
    private static final String BASELINE = "a".repeat(40);

    @Nested
    class Get {

        @Test
        void returnsTheBaselineToHeadDiffWhenTheBaselineIsInHistory() throws Exception {
            Fixture fixture =
                    new Fixture(
                            page(HEAD, BASELINE, List.of(BASELINE, HEAD)),
                            compare(PrDiffService.Response.ok("diff --git a/x b/x\n", true), 200));

            IncrementalDiffResult result = fixture.get();

            assertThat(result)
                    .isEqualTo(
                            new IncrementalDiffResult(
                                    "ok",
                                    "Changes since your last review loaded.",
                                    IncrementalDiffResult.SCOPE_INCREMENTAL,
                                    null,
                                    BASELINE,
                                    HEAD,
                                    "diff --git a/x b/x\n",
                                    true,
                                    PrDiffService.REVIEW_LIMIT_BYTES));
            assertThat(fixture.compareCalls)
                    .containsExactly(
                            "https://github.example.test/api/v3|secret-token|acme|widgets|"
                                    + BASELINE
                                    + "|"
                                    + HEAD
                                    + "|"
                                    + PrDiffService.REVIEW_LIMIT_BYTES);
        }

        @Test
        void fallsBackWithoutComparingWhenThereIsNoPriorReview() throws Exception {
            Fixture fixture = new Fixture(page(HEAD, null, List.of(HEAD)), null);

            IncrementalDiffResult result = fixture.get();

            assertFullFallback(result, IncrementalDiffResult.NO_PRIOR_REVIEW);
            assertThat(fixture.compareCalls).isEmpty();
        }

        @Test
        void fallsBackWhenTheBaselineIsTheHead() throws Exception {
            Fixture fixture = new Fixture(page(HEAD, HEAD, List.of(HEAD)), null);

            assertFullFallback(fixture.get(), IncrementalDiffResult.UP_TO_DATE);
            assertThat(fixture.compareCalls).isEmpty();
        }

        @Test
        void fallsBackWhenTheBaselineIsNotInTheCurrentHistory() throws Exception {
            Fixture fixture = new Fixture(page(HEAD, BASELINE, List.of(HEAD)), null);

            IncrementalDiffResult result = fixture.get();

            assertFullFallback(result, IncrementalDiffResult.BASELINE_NOT_IN_HISTORY);
            assertThat(result.baselineSha()).isEqualTo(BASELINE);
            assertThat(fixture.compareCalls).isEmpty();
        }

        @ParameterizedTest
        @CsvSource({
            "404, NOT_FOUND, baseline_unavailable",
            "422, API, baseline_unavailable",
            "406, TOO_LARGE, incremental_diff_too_large"
        })
        void mapsCompareFailuresToFallbacks(
                int statusCode, PrDiffService.Status status, String reason) throws Exception {
            Fixture fixture =
                    new Fixture(
                            page(HEAD, BASELINE, List.of(BASELINE, HEAD)),
                            compare(PrDiffService.Response.of(status), statusCode));

            assertFullFallback(fixture.get(), reason);
        }

        @Test
        void fallsBackWhenTheCompareDiffIsBlank() throws Exception {
            Fixture fixture =
                    new Fixture(
                            page(HEAD, BASELINE, List.of(BASELINE, HEAD)),
                            compare(PrDiffService.Response.ok("  \n", false), 200));

            assertFullFallback(fixture.get(), IncrementalDiffResult.EMPTY_INCREMENTAL_DIFF);
        }

        @ParameterizedTest
        @CsvSource({
            "400, API, api_failed",
            "409, API, api_failed",
            "500, TRANSIENT_API, api_failed",
            "401, UNAUTHENTICATED, not_authenticated",
            "429, RATE_LIMITED, rate_limited",
            "0, NETWORK, network_error"
        })
        void mapsOtherCompareFailuresToStatusesWithoutScope(
                int statusCode, PrDiffService.Status status, String expected) throws Exception {
            Fixture fixture =
                    new Fixture(
                            page(HEAD, BASELINE, List.of(BASELINE, HEAD)),
                            compare(PrDiffService.Response.of(status), statusCode));

            assertNoScope(fixture.get(), expected);
        }

        @Test
        void mapsBaselineResolutionFailures() throws Exception {
            assertNoScope(
                    new Fixture(new GitHubResponse(401, ""), null).get(), "not_authenticated");
            assertNoScope(new Fixture(new GitHubResponse(500, ""), null).get(), "api_failed");
            Map<String, Object> missingPr = new LinkedHashMap<>();
            missingPr.put("pullRequest", null);
            assertNoScope(
                    new Fixture(ok(Map.of("data", Map.of("repository", missingPr))), null).get(),
                    PrDiffResult.STATUS_NOT_FOUND_OR_INACCESSIBLE);
        }

        @Test
        void rejectsInvalidRequestsBeforeResolvingAToken() throws Exception {
            Fixture fixture = new Fixture(page(HEAD, BASELINE, List.of(BASELINE)), null);

            assertNoScope(
                    fixture.get(
                            new IncrementalDiffService.Params(
                                    "https://github.example.test", "acme", "bad/repo", 7)),
                    "invalid_request");
            assertNoScope(
                    fixture.get(
                            new IncrementalDiffService.Params(
                                    "https://github.example.test", "acme", "widgets", 0)),
                    "invalid_request");
            assertNoScope(
                    fixture.get(
                            new IncrementalDiffService.Params(
                                    "http://github.example.test", "acme", "widgets", 7)),
                    "invalid_base_url");
            assertThat(fixture.tokenCalls).isZero();
            assertThat(fixture.apiCalls).isEmpty();
        }

        @Test
        void reportsMissingOrUnauthenticatedGh() throws Exception {
            Fixture missing = new Fixture(page(HEAD, BASELINE, List.of(BASELINE)), null);
            missing.token = GitHubAuthService.TokenResolution.notInstalled();
            assertNoScope(missing.get(), "not_installed");

            Fixture unauthenticated = new Fixture(page(HEAD, BASELINE, List.of(BASELINE)), null);
            unauthenticated.token = GitHubAuthService.TokenResolution.notAuthenticated();
            assertNoScope(unauthenticated.get(), "not_authenticated");
            assertThat(unauthenticated.apiCalls).isEmpty();
        }
    }

    private static void assertFullFallback(IncrementalDiffResult result, String reason) {
        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.scope()).isEqualTo(IncrementalDiffResult.SCOPE_FULL);
        assertThat(result.fallbackReason()).isEqualTo(reason);
        assertThat(result.diff()).isNull();
        assertThat(result.headSha()).isEqualTo(HEAD);
    }

    private static void assertNoScope(IncrementalDiffResult result, String status) {
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.scope()).isNull();
        assertThat(result.fallbackReason()).isNull();
        assertThat(result.diff()).isNull();
    }

    private static PrDiffService.CompareResponse compare(
            PrDiffService.Response response, int statusCode) {
        return new PrDiffService.CompareResponse(response, statusCode);
    }

    private static GitHubResponse ok(Object body) throws Exception {
        return new GitHubResponse(200, MAPPER.writeValueAsString(body));
    }

    private static GitHubResponse page(String head, String reviewedOid, List<String> commitOids)
            throws Exception {
        List<Map<String, Object>> reviews =
                reviewedOid == null
                        ? List.of()
                        : List.of(
                                Map.of(
                                        "state",
                                        "COMMENTED",
                                        "submittedAt",
                                        "2026-08-01T00:00:00Z",
                                        "commit",
                                        Map.of("oid", reviewedOid)));
        return ok(
                Map.of(
                        "data",
                        Map.of(
                                "repository",
                                Map.of(
                                        "pullRequest",
                                        Map.of(
                                                "headRefOid",
                                                head,
                                                "reviews",
                                                Map.of("nodes", reviews),
                                                "commits",
                                                Map.of(
                                                        "pageInfo",
                                                        Map.of("hasPreviousPage", false),
                                                        "nodes",
                                                        commitOids.stream()
                                                                .map(
                                                                        oid ->
                                                                                Map.of(
                                                                                        "commit",
                                                                                        Map.of(
                                                                                                "oid",
                                                                                                oid)))
                                                                .toList()))))));
    }

    private static final class Fixture implements PrReviewStatusService.ApiClient {
        private final GitHubResponse graphQl;
        private final PrDiffService.CompareResponse compareResponse;
        private final List<String> apiCalls = new ArrayList<>();
        private final List<String> compareCalls = new ArrayList<>();
        private GitHubAuthService.TokenResolution token =
                GitHubAuthService.TokenResolution.resolved("secret-token");
        private int tokenCalls;

        Fixture(GitHubResponse graphQl, PrDiffService.CompareResponse compareResponse) {
            this.graphQl = graphQl;
            this.compareResponse = compareResponse;
        }

        IncrementalDiffResult get() {
            return get(
                    new IncrementalDiffService.Params(
                            "https://github.example.test", "acme", "widgets", 7));
        }

        IncrementalDiffResult get(IncrementalDiffService.Params params) {
            PrDiffService diffService =
                    new PrDiffService(
                            hostname -> {
                                tokenCalls++;
                                return token;
                            },
                            (api, token, owner, repo, number, limit) -> null,
                            (api, token, owner, repo, base, head, limit) -> {
                                compareCalls.add(
                                        String.join(
                                                "|",
                                                api,
                                                token,
                                                owner,
                                                repo,
                                                base,
                                                head,
                                                String.valueOf(limit)));
                                return compareResponse;
                            },
                            attempt -> {});
            return new IncrementalDiffService(new PrReviewStatusService(this, MAPPER), diffService)
                    .get(params);
        }

        @Override
        public GitHubResponse get(String url, String token) {
            apiCalls.add(url);
            if (graphQl.statusCode() == 401) {
                return new GitHubResponse(401, "");
            }
            return new GitHubResponse(200, "{\"login\":\"octocat\"}");
        }

        @Override
        public GitHubResponse postJson(String url, String token, String body) {
            apiCalls.add(url);
            return graphQl;
        }
    }
}
