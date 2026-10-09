package com.jinloes.prpilot.sidecar.pr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.sidecar.github.GitHubApiBase;
import com.jinloes.prpilot.sidecar.github.GitHubAuthService;
import java.time.Duration;
import java.util.Objects;

/**
 * Resolves the diff of commits pushed since the viewer's latest submitted review. The baseline is
 * the commit the list badge compares against; it must be in the pull request's current history, and
 * anything that prevents a trustworthy incremental diff falls back to a full review instead.
 */
public final class IncrementalDiffService {
    private static final Duration BASELINE_TIMEOUT = Duration.ofSeconds(10);
    private static final int LIMIT_BYTES = PrDiffService.REVIEW_LIMIT_BYTES;

    private final PrReviewStatusService reviewStatusService;
    private final PrDiffService diffService;

    public IncrementalDiffService() {
        this(
                new PrReviewStatusService(
                        new PrReviewStatusService.HttpApiClient(BASELINE_TIMEOUT),
                        new ObjectMapper()),
                new PrDiffService());
    }

    IncrementalDiffService(PrReviewStatusService reviewStatusService, PrDiffService diffService) {
        this.reviewStatusService = Objects.requireNonNull(reviewStatusService);
        this.diffService = Objects.requireNonNull(diffService);
    }

    public IncrementalDiffResult get(Params params) {
        if (params.number() <= 0
                || !PrDiffService.valid(params.owner())
                || !PrDiffService.valid(params.repo())) {
            return IncrementalDiffResult.failure(
                    "invalid_request", "Incremental diff request is invalid.", LIMIT_BYTES);
        }
        GitHubApiBase base = GitHubApiBase.parse(params.githubBaseUrl());
        if (base == null) {
            return IncrementalDiffResult.failure(
                    "invalid_base_url", "GitHub base URL must be an HTTPS origin.", LIMIT_BYTES);
        }
        GitHubAuthService.TokenResolution token = diffService.resolveToken(base.hostnameArgument());
        if (token.status() == GitHubAuthService.TokenStatus.NOT_INSTALLED) {
            return IncrementalDiffResult.failure(
                    "not_installed", "GitHub CLI is not installed.", LIMIT_BYTES);
        }
        if (token.status() != GitHubAuthService.TokenStatus.RESOLVED) {
            return notAuthenticated();
        }

        PrReviewStatusService.ReviewBaseline baseline =
                reviewStatusService.reviewBaseline(
                        base, token.token(), params.owner(), params.repo(), params.number());
        String baselineSha = baseline.baselineSha();
        String headSha = baseline.headSha();
        switch (baseline.outcome()) {
            case NOT_AUTHENTICATED:
                return notAuthenticated();
            case FAILED:
                return apiFailed();
            case NOT_FOUND:
                return notFound();
            case NO_PRIOR_REVIEW:
                return fallback(IncrementalDiffResult.NO_PRIOR_REVIEW, baselineSha, headSha);
            case UP_TO_DATE:
                return fallback(IncrementalDiffResult.UP_TO_DATE, baselineSha, headSha);
            case NOT_IN_HISTORY:
                return fallback(
                        IncrementalDiffResult.BASELINE_NOT_IN_HISTORY, baselineSha, headSha);
            case FOUND:
                break;
        }

        PrDiffService.CompareResponse compare =
                diffService.compare(
                        base.apiBaseUrl(),
                        token.token(),
                        params.owner(),
                        params.repo(),
                        baselineSha,
                        headSha);
        int statusCode = compare.statusCode();
        if (statusCode == 404 || statusCode == 422) {
            return fallback(IncrementalDiffResult.BASELINE_UNAVAILABLE, baselineSha, headSha);
        }
        if (statusCode == 406) {
            return fallback(IncrementalDiffResult.INCREMENTAL_DIFF_TOO_LARGE, baselineSha, headSha);
        }
        PrDiffService.Response response = compare.response();
        return switch (response.status()) {
            case OK ->
                    response.diff() == null || response.diff().isBlank()
                            ? fallback(
                                    IncrementalDiffResult.EMPTY_INCREMENTAL_DIFF,
                                    baselineSha,
                                    headSha)
                            : IncrementalDiffResult.incremental(
                                    baselineSha,
                                    headSha,
                                    response.diff(),
                                    response.truncated(),
                                    LIMIT_BYTES);
            case UNAUTHENTICATED -> notAuthenticated();
            case RATE_LIMITED ->
                    IncrementalDiffResult.failure(
                            "rate_limited",
                            "GitHub rate limit exceeded. Try again shortly.",
                            LIMIT_BYTES);
            case NETWORK ->
                    IncrementalDiffResult.failure(
                            "network_error",
                            "Unable to reach GitHub. Check your connection.",
                            LIMIT_BYTES);
            case NOT_FOUND, TOO_LARGE, API, TRANSIENT_API -> apiFailed();
        };
    }

    private static IncrementalDiffResult fallback(
            String reason, String baselineSha, String headSha) {
        return IncrementalDiffResult.fullFallback(reason, baselineSha, headSha, LIMIT_BYTES);
    }

    private static IncrementalDiffResult notAuthenticated() {
        return IncrementalDiffResult.failure(
                "not_authenticated",
                "Run 'gh auth login' in a terminal for this GitHub host.",
                LIMIT_BYTES);
    }

    private static IncrementalDiffResult apiFailed() {
        return IncrementalDiffResult.failure(
                "api_failed", "GitHub API request failed.", LIMIT_BYTES);
    }

    private static IncrementalDiffResult notFound() {
        return IncrementalDiffResult.failure(
                PrDiffResult.STATUS_NOT_FOUND_OR_INACCESSIBLE,
                "Pull request not found or inaccessible to the active gh account.",
                LIMIT_BYTES);
    }

    public record Params(String githubBaseUrl, String owner, String repo, int number) {}
}
