package com.jinloes.prpilot.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jinloes.prpilot.engine.GitHubEngineApi;
import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.model.ReviewStatus;
import com.jinloes.prpilot.sidecar.github.CheckAuthResult;
import com.jinloes.prpilot.sidecar.pr.CheckAnnotation;
import com.jinloes.prpilot.sidecar.pr.CheckRunService;
import com.jinloes.prpilot.sidecar.pr.CheckStatusResult;
import com.jinloes.prpilot.sidecar.pr.DraftReviewMutationResult;
import com.jinloes.prpilot.sidecar.pr.DraftReviewMutationService;
import com.jinloes.prpilot.sidecar.pr.DraftReviewResult;
import com.jinloes.prpilot.sidecar.pr.ExistingReviewsResult;
import com.jinloes.prpilot.sidecar.pr.LinkedIssueResult;
import com.jinloes.prpilot.sidecar.pr.LinkedIssueService;
import com.jinloes.prpilot.sidecar.pr.PrCommitsResult;
import com.jinloes.prpilot.sidecar.pr.PrDetail;
import com.jinloes.prpilot.sidecar.pr.PrDetailResult;
import com.jinloes.prpilot.sidecar.pr.PrDetailService;
import com.jinloes.prpilot.sidecar.pr.PrDiffResult;
import com.jinloes.prpilot.sidecar.pr.PrDiffService;
import com.jinloes.prpilot.sidecar.pr.PrListResult;
import com.jinloes.prpilot.sidecar.pr.PrListService;
import com.jinloes.prpilot.sidecar.pr.PrSearchResult;
import com.jinloes.prpilot.sidecar.pr.PrSupplementalService;
import com.jinloes.prpilot.sidecar.pr.PullRequestSummary;
import com.jinloes.prpilot.sidecar.pr.StarredReposResult;
import com.jinloes.prpilot.sidecar.repo.DetectResult;
import com.jinloes.prpilot.sidecar.repo.RepoProfileResult;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class IntellijGitHubServiceTest {

    private static final String BASE_URL = "https://github.com";

    private static IntellijGitHubService serviceOver(GitHubEngineApi engine) {
        return new IntellijGitHubService(engine, () -> BASE_URL);
    }

    /**
     * Base stub for the engine surface. Every method throws unless a test overrides it, so a test
     * cannot accidentally pass by exercising a call it did not mean to make.
     */
    private static class StubEngine implements GitHubEngineApi {
        String seenBaseUrl;

        @Override
        public DetectResult detectRepo(String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CheckAuthResult checkAuth(String githubBaseUrl) {
            seenBaseUrl = githubBaseUrl;
            return new CheckAuthResult("authenticated", "octocat", "ok");
        }

        @Override
        public PrListResult listPullRequests(PrListService.PrListParams params) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PrSearchResult searchPullRequests(PrSupplementalService.SearchParams params) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StarredReposResult listStarredRepositories(String githubBaseUrl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PrDetailResult getPullRequestDetail(PrDetailService.PrDetailParams params) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PrDiffResult getPullRequestDiff(PrDiffService.Params params) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ExistingReviewsResult getExistingReviews(
                PrSupplementalService.IdentityParams params) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DraftReviewResult getDraftReview(
                String githubBaseUrl, String owner, String repo, int number) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DraftReviewMutationResult saveDraftReview(
                DraftReviewMutationService.SaveParams params) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DraftReviewMutationResult submitReview(
                DraftReviewMutationService.SubmitParams params) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DraftReviewMutationResult deleteDraftReview(
                DraftReviewMutationService.DeleteParams params) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CheckStatusResult getCheckStatus(CheckRunService.Params params) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PrCommitsResult getCommits(PrSupplementalService.IdentityParams params) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LinkedIssueResult getLinkedIssues(LinkedIssueService.Params params) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RepoProfileResult getRepoProfile(String projectDir) {
            throw new UnsupportedOperationException();
        }
    }

    @Nested
    class ToPullRequests {

        @Test
        void mapsSharedEngineResultsToHostModels() {
            List<PullRequest> result =
                    IntellijGitHubService.toPullRequests(
                            List.of(
                                    new PullRequestSummary(
                                            42,
                                            "Fix the race",
                                            "acme",
                                            "widgets",
                                            "octocat",
                                            "2026-07-22T12:00:00Z",
                                            "https://github.com/acme/widgets/pull/42",
                                            true,
                                            ReviewStatus.UPDATED_SINCE_REVIEW)));

            assertThat(result).hasSize(1);
            PullRequest pullRequest = result.get(0);
            assertThat(pullRequest.getNumber()).isEqualTo(42);
            assertThat(pullRequest.getTitle()).isEqualTo("Fix the race");
            assertThat(pullRequest.getOwner()).isEqualTo("acme");
            assertThat(pullRequest.getRepo()).isEqualTo("widgets");
            assertThat(pullRequest.getAuthor()).isEqualTo("octocat");
            assertThat(pullRequest.getCreatedAt()).isEqualTo("2026-07-22T12:00:00Z");
            assertThat(pullRequest.getHtmlUrl())
                    .isEqualTo("https://github.com/acme/widgets/pull/42");
            assertThat(pullRequest.isDraft()).isTrue();
            assertThat(pullRequest.getReviewStatus()).isEqualTo(ReviewStatus.UPDATED_SINCE_REVIEW);
        }
    }

    @Nested
    class CheckAuth {

        @Test
        void passesTheSuppliedOriginRatherThanTheConfiguredOne() {
            StubEngine engine = new StubEngine();

            CheckAuthResult result = serviceOver(engine).checkAuth("https://github.example.com");

            // The settings dialog checks the value the user just typed, before it is persisted,
            // so this must not silently fall back to the stored base URL.
            assertThat(engine.seenBaseUrl).isEqualTo("https://github.example.com");
            assertThat(result.username()).isEqualTo("octocat");
        }
    }

    @Nested
    class GetPrDetail {

        @Test
        void returnsDetailedTitleBodyAndHead() throws IOException {
            PrDetail detail =
                    new PrDetail(
                            false,
                            "Detailed title",
                            "Closes #7",
                            new PrDetail.Head("sha", "branch", "acme/widgets", "clone"),
                            "acme/widgets",
                            null);
            IntellijGitHubService service =
                    serviceOver(
                            new StubEngine() {
                                @Override
                                public PrDetailResult getPullRequestDetail(
                                        PrDetailService.PrDetailParams params) {
                                    return new PrDetailResult("ok", "loaded", detail);
                                }
                            });

            assertThat(service.getPRDetail("acme", "widgets", 42)).isEqualTo(detail);
        }
    }

    @Nested
    class GetPRRevisions {

        private IntellijGitHubService serviceReturning(PrDetail detail) {
            return serviceOver(
                    new StubEngine() {
                        @Override
                        public PrDetailResult getPullRequestDetail(
                                PrDetailService.PrDetailParams params) {
                            return new PrDetailResult("ok", "loaded", detail);
                        }
                    });
        }

        @Test
        void returnsHeadAndBaseFromOneDetail() throws IOException {
            String base = "c".repeat(40);
            IntellijGitHubService service =
                    serviceReturning(
                            new PrDetail(
                                    false,
                                    "t",
                                    "",
                                    new PrDetail.Head("head-sha", "b", "acme/widgets", "clone"),
                                    "acme/widgets",
                                    base));

            assertThat(service.getPRRevisions("acme", "widgets", 42))
                    .isEqualTo(new IntellijGitHubService.PRRevisions("head-sha", base));
        }

        @Test
        void missingBaseAndHeadBecomeBlank() throws IOException {
            IntellijGitHubService service =
                    serviceReturning(new PrDetail(false, "t", "", null, "acme/widgets", null));

            assertThat(service.getPRRevisions("acme", "widgets", 42))
                    .isEqualTo(new IntellijGitHubService.PRRevisions("", ""));
        }
    }

    @Nested
    class GetExistingReviewsSummary {

        @Test
        void threadsTheConfiguredBaseUrlIntoTheEngineCall() throws IOException {
            PrSupplementalService.IdentityParams[] seen =
                    new PrSupplementalService.IdentityParams[1];
            IntellijGitHubService service =
                    serviceOver(
                            new StubEngine() {
                                @Override
                                public ExistingReviewsResult getExistingReviews(
                                        PrSupplementalService.IdentityParams params) {
                                    seen[0] = params;
                                    return new ExistingReviewsResult("ok", "loaded", "2 reviews");
                                }
                            });

            assertThat(service.getExistingReviewsSummary("acme", "widgets", 42))
                    .isEqualTo("2 reviews");
            assertThat(seen[0].githubBaseUrl()).isEqualTo(BASE_URL);
            assertThat(seen[0].owner()).isEqualTo("acme");
            assertThat(seen[0].repo()).isEqualTo("widgets");
            assertThat(seen[0].number()).isEqualTo(42);
        }

        @Test
        void preservesANonOkStatusInTheIoException() {
            IntellijGitHubService service =
                    serviceOver(
                            new StubEngine() {
                                @Override
                                public ExistingReviewsResult getExistingReviews(
                                        PrSupplementalService.IdentityParams params) {
                                    return new ExistingReviewsResult(
                                            "api_failed", "GitHub said no", "");
                                }
                            });

            assertThatThrownBy(() -> service.getExistingReviewsSummary("acme", "widgets", 42))
                    .isInstanceOf(IOException.class)
                    .hasMessage("GitHub said no");
        }
    }

    /**
     * The four prompt-context reads are deliberately best-effort: a review without them is exactly
     * as good as before they existed, so a CI outage must degrade the prompt rather than fail the
     * review. That decision previously lived only in a comment.
     */
    @Nested
    class ContextReadsDegradeRatherThanFail {

        @Test
        void checkContextReturnsEmptyWithoutThrowingWhenTheRequestFails() {
            IntellijGitHubService service =
                    serviceOver(
                            new StubEngine() {
                                @Override
                                public CheckStatusResult getCheckStatus(
                                        CheckRunService.Params params) {
                                    return new CheckStatusResult(
                                            "api_failed", "boom", "", List.of(), null, "");
                                }
                            });

            IntellijGitHubService.CheckContext context =
                    service.getCheckContext("acme", "widgets", "abc123");

            assertThat(context.summary()).isEmpty();
            assertThat(context.annotations()).isEmpty();
        }

        @Test
        void checkContextMapsAnnotationsOntoTheSharedModel() {
            IntellijGitHubService service =
                    serviceOver(
                            new StubEngine() {
                                @Override
                                public CheckStatusResult getCheckStatus(
                                        CheckRunService.Params params) {
                                    return new CheckStatusResult(
                                            "ok",
                                            "loaded",
                                            "failure",
                                            List.of(),
                                            List.of(
                                                    new CheckAnnotation(
                                                            "src/Main.java",
                                                            10,
                                                            12,
                                                            "failure",
                                                            "unused import")),
                                            "1 failing");
                                }
                            });

            IntellijGitHubService.CheckContext context =
                    service.getCheckContext("acme", "widgets", "abc123");

            assertThat(context.summary()).isEqualTo("1 failing");
            assertThat(context.annotations()).hasSize(1);
            assertThat(context.annotations().get(0).getFile()).isEqualTo("src/Main.java");
            assertThat(context.annotations().get(0).getLine()).isEqualTo(10);
            assertThat(context.annotations().get(0).getLevel()).isEqualTo("failure");
            assertThat(context.annotations().get(0).getMessage()).isEqualTo("unused import");
        }

        @Test
        void commitContextReturnsEmptyValuesRatherThanThrowing() {
            IntellijGitHubService service =
                    serviceOver(
                            new StubEngine() {
                                @Override
                                public PrCommitsResult getCommits(
                                        PrSupplementalService.IdentityParams params) {
                                    return new PrCommitsResult(
                                            "api_failed", "boom", 0, "", List.of());
                                }
                            });

            IntellijGitHubService.CommitContext context =
                    service.getCommitContext("acme", "widgets", 42);

            assertThat(context.summary()).isEmpty();
            assertThat(context.closingIssueNumbers()).isEmpty();
        }

        @Test
        void commitContextCarriesClosingIssueNumbersFromTheEngine() {
            IntellijGitHubService service =
                    serviceOver(
                            new StubEngine() {
                                @Override
                                public PrCommitsResult getCommits(
                                        PrSupplementalService.IdentityParams params) {
                                    return new PrCommitsResult(
                                            "ok", "loaded", 1, "- Fix", List.of(7, 8));
                                }
                            });

            IntellijGitHubService.CommitContext context =
                    service.getCommitContext("acme", "widgets", 42);

            assertThat(context.summary()).isEqualTo("- Fix");
            assertThat(context.closingIssueNumbers()).containsExactly(7, 8);
        }

        @Test
        void linkedIssueSummaryReturnsEmptyRatherThanThrowing() {
            LinkedIssueService.Params[] seen = new LinkedIssueService.Params[1];
            IntellijGitHubService service =
                    serviceOver(
                            new StubEngine() {
                                @Override
                                public LinkedIssueResult getLinkedIssues(
                                        LinkedIssueService.Params params) {
                                    seen[0] = params;
                                    return new LinkedIssueResult("api_failed", "boom", 0, "");
                                }
                            });

            assertThat(service.getLinkedIssueSummary("acme", "widgets", "Closes #1", List.of(7, 8)))
                    .isEmpty();
            assertThat(seen[0].prBody()).isEqualTo("Closes #1");
            assertThat(seen[0].commitIssueNumbers()).containsExactly(7, 8);
        }
    }

    @Nested
    class GetPrDiff {

        @Test
        void requestsReviewModeAndReturnsTheDiff() throws IOException {
            PrDiffService.Params[] seen = new PrDiffService.Params[1];
            IntellijGitHubService service =
                    serviceOver(
                            new StubEngine() {
                                @Override
                                public PrDiffResult getPullRequestDiff(
                                        PrDiffService.Params params) {
                                    seen[0] = params;
                                    return new PrDiffResult(
                                            "ok", "loaded", "@@ -1 +1 @@", false, 0);
                                }
                            });

            assertThat(service.getPRDiff("acme", "widgets", 42)).isEqualTo("@@ -1 +1 @@");
            assertThat(seen[0].mode()).isEqualTo("review");
            assertThat(seen[0].githubBaseUrl()).isEqualTo(BASE_URL);
        }

        @Test
        void fullDiffRequestsValidationMode() throws IOException {
            PrDiffService.Params[] seen = new PrDiffService.Params[1];
            IntellijGitHubService service =
                    serviceOver(
                            new StubEngine() {
                                @Override
                                public PrDiffResult getPullRequestDiff(
                                        PrDiffService.Params params) {
                                    seen[0] = params;
                                    return new PrDiffResult("ok", "loaded", "diff", false, 0);
                                }
                            });

            service.getPRDiffFull("acme", "widgets", 42);

            assertThat(seen[0].mode()).isEqualTo("validation");
        }

        @Test
        void surfacesANonOkStatusAsAnIoException() {
            IntellijGitHubService service =
                    serviceOver(
                            new StubEngine() {
                                @Override
                                public PrDiffResult getPullRequestDiff(
                                        PrDiffService.Params params) {
                                    return new PrDiffResult(
                                            "rate_limited", "slow down", "", false, 0);
                                }
                            });

            assertThatThrownBy(() -> service.getPRDiff("acme", "widgets", 42))
                    .isInstanceOfSatisfying(
                            IntellijGitHubService.GitHubOperationException.class,
                            error -> {
                                assertThat(error.status()).isEqualTo("rate_limited");
                                assertThat(error).hasMessage("slow down");
                            });
        }
    }
}
