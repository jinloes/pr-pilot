package com.jinloes.prpilot.engine;

import com.jinloes.prpilot.sidecar.github.CheckAuthResult;
import com.jinloes.prpilot.sidecar.github.GitHubAuthService;
import com.jinloes.prpilot.sidecar.pr.CheckRunService;
import com.jinloes.prpilot.sidecar.pr.CheckStatusResult;
import com.jinloes.prpilot.sidecar.pr.DraftReviewMutationResult;
import com.jinloes.prpilot.sidecar.pr.DraftReviewMutationService;
import com.jinloes.prpilot.sidecar.pr.DraftReviewResult;
import com.jinloes.prpilot.sidecar.pr.DraftReviewService;
import com.jinloes.prpilot.sidecar.pr.ExistingReviewsResult;
import com.jinloes.prpilot.sidecar.pr.IncrementalDiffResult;
import com.jinloes.prpilot.sidecar.pr.IncrementalDiffService;
import com.jinloes.prpilot.sidecar.pr.LinkedIssueResult;
import com.jinloes.prpilot.sidecar.pr.LinkedIssueService;
import com.jinloes.prpilot.sidecar.pr.PrCommitsResult;
import com.jinloes.prpilot.sidecar.pr.PrCommitsService;
import com.jinloes.prpilot.sidecar.pr.PrDetailResult;
import com.jinloes.prpilot.sidecar.pr.PrDetailService;
import com.jinloes.prpilot.sidecar.pr.PrDiffResult;
import com.jinloes.prpilot.sidecar.pr.PrDiffService;
import com.jinloes.prpilot.sidecar.pr.PrListResult;
import com.jinloes.prpilot.sidecar.pr.PrListService;
import com.jinloes.prpilot.sidecar.pr.PrSearchResult;
import com.jinloes.prpilot.sidecar.pr.PrSupplementalService;
import com.jinloes.prpilot.sidecar.pr.StarredReposResult;
import com.jinloes.prpilot.sidecar.repo.DetectResult;
import com.jinloes.prpilot.sidecar.repo.RepoDetector;
import com.jinloes.prpilot.sidecar.repo.RepoFingerprint;
import com.jinloes.prpilot.sidecar.repo.RepoProfileResult;
import java.util.Objects;

/**
 * Default {@link GitHubEngineApi} implementation: a thin composition root over the individual
 * engine services. It holds no logic of its own — behavior stays in the services so their existing
 * tests keep covering it. Its job is to give every host and the sidecar one object to depend on
 * instead of a growing constructor of loose services.
 */
public final class GitHubEngine implements GitHubEngineApi {

    private final RepoDetector repoDetector;
    private final GitHubAuthService authService;
    private final PrListService listService;
    private final PrDetailService detailService;
    private final PrDiffService diffService;
    private final IncrementalDiffService incrementalDiffService;
    private final PrSupplementalService supplementalService;
    private final DraftReviewService draftReviewService;
    private final DraftReviewMutationService mutationService;
    private final CheckRunService checkRunService;
    private final PrCommitsService commitsService;
    private final LinkedIssueService linkedIssueService;
    private final RepoFingerprint repoFingerprint;

    /** Creates an engine backed by freshly constructed default services. */
    public GitHubEngine() {
        this(
                new RepoDetector(),
                new GitHubAuthService(),
                new PrListService(),
                new PrDetailService(),
                new PrDiffService(),
                new IncrementalDiffService(),
                new PrSupplementalService(),
                new DraftReviewService(),
                new DraftReviewMutationService(),
                new CheckRunService(),
                new PrCommitsService(),
                new LinkedIssueService(),
                new RepoFingerprint());
    }

    public GitHubEngine(
            RepoDetector repoDetector,
            GitHubAuthService authService,
            PrListService listService,
            PrDetailService detailService,
            PrDiffService diffService,
            IncrementalDiffService incrementalDiffService,
            PrSupplementalService supplementalService,
            DraftReviewService draftReviewService,
            DraftReviewMutationService mutationService,
            CheckRunService checkRunService,
            PrCommitsService commitsService,
            LinkedIssueService linkedIssueService,
            RepoFingerprint repoFingerprint) {
        this.repoDetector = Objects.requireNonNull(repoDetector);
        this.authService = Objects.requireNonNull(authService);
        this.listService = Objects.requireNonNull(listService);
        this.detailService = Objects.requireNonNull(detailService);
        this.diffService = Objects.requireNonNull(diffService);
        this.incrementalDiffService = Objects.requireNonNull(incrementalDiffService);
        this.supplementalService = Objects.requireNonNull(supplementalService);
        this.draftReviewService = Objects.requireNonNull(draftReviewService);
        this.mutationService = Objects.requireNonNull(mutationService);
        this.checkRunService = Objects.requireNonNull(checkRunService);
        this.commitsService = Objects.requireNonNull(commitsService);
        this.linkedIssueService = Objects.requireNonNull(linkedIssueService);
        this.repoFingerprint = Objects.requireNonNull(repoFingerprint);
    }

    @Override
    public DetectResult detectRepo(String path) {
        return repoDetector.detect(path);
    }

    @Override
    public CheckAuthResult checkAuth(String githubBaseUrl) {
        return authService.check(githubBaseUrl);
    }

    @Override
    public PrListResult listPullRequests(PrListService.PrListParams params) {
        return listService.list(params);
    }

    @Override
    public PrSearchResult searchPullRequests(PrSupplementalService.SearchParams params) {
        return supplementalService.search(params);
    }

    @Override
    public StarredReposResult listStarredRepositories(String githubBaseUrl) {
        return supplementalService.starred(githubBaseUrl);
    }

    @Override
    public PrDetailResult getPullRequestDetail(PrDetailService.PrDetailParams params) {
        return detailService.get(params);
    }

    @Override
    public PrDiffResult getPullRequestDiff(PrDiffService.Params params) {
        return diffService.get(params);
    }

    @Override
    public IncrementalDiffResult getIncrementalDiff(IncrementalDiffService.Params params) {
        return incrementalDiffService.get(params);
    }

    @Override
    public ExistingReviewsResult getExistingReviews(PrSupplementalService.IdentityParams params) {
        return supplementalService.existingReviews(params);
    }

    @Override
    public DraftReviewResult getDraftReview(
            String githubBaseUrl, String owner, String repo, int number) {
        return draftReviewService.load(githubBaseUrl, owner, repo, number);
    }

    @Override
    public DraftReviewMutationResult saveDraftReview(DraftReviewMutationService.SaveParams params) {
        return mutationService.save(params);
    }

    @Override
    public DraftReviewMutationResult submitReview(DraftReviewMutationService.SubmitParams params) {
        return mutationService.submit(params);
    }

    @Override
    public DraftReviewMutationResult deleteDraftReview(
            DraftReviewMutationService.DeleteParams params) {
        return mutationService.delete(params);
    }

    @Override
    public CheckStatusResult getCheckStatus(CheckRunService.Params params) {
        return checkRunService.checkStatus(params);
    }

    @Override
    public PrCommitsResult getCommits(PrSupplementalService.IdentityParams params) {
        return commitsService.commits(params);
    }

    @Override
    public LinkedIssueResult getLinkedIssues(LinkedIssueService.Params params) {
        return linkedIssueService.linkedIssues(params);
    }

    @Override
    public RepoProfileResult getRepoProfile(String projectDir) {
        return repoFingerprint.profile(projectDir);
    }
}
