package com.jinloes.prpilot.services;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.jinloes.prpilot.engine.GitHubEngine;
import com.jinloes.prpilot.engine.GitHubEngineApi;
import com.jinloes.prpilot.model.CiAnnotation;
import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.model.ReviewResult;
import com.jinloes.prpilot.settings.PluginSettings;
import com.jinloes.prpilot.sidecar.github.CheckAuthResult;
import com.jinloes.prpilot.sidecar.pr.CheckRunService;
import com.jinloes.prpilot.sidecar.pr.CheckStatusResult;
import com.jinloes.prpilot.sidecar.pr.DraftReviewCodec;
import com.jinloes.prpilot.sidecar.pr.DraftReviewMutationResult;
import com.jinloes.prpilot.sidecar.pr.DraftReviewMutationService;
import com.jinloes.prpilot.sidecar.pr.DraftReviewResult;
import com.jinloes.prpilot.sidecar.pr.ExistingReviewsResult;
import com.jinloes.prpilot.sidecar.pr.IncrementalDiffResult;
import com.jinloes.prpilot.sidecar.pr.IncrementalDiffService;
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
import com.jinloes.prpilot.sidecar.repo.DetectStatus;
import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;

/**
 * IntelliJ adapter over the shared Java GitHub engine. All calls execute in the IDE JVM.
 *
 * <p>This class <em>consumes</em> {@link GitHubEngineApi} rather than re-declaring the GitHub
 * surface: it previously instantiated eleven engine services directly and repeated the delegation
 * {@link GitHubEngine} already performs, which meant a capability added to the engine interface was
 * invisible here. What stays host-specific is the adaptation around the engine — reading the
 * configured base URL, mapping engine records to the shared host models, and turning non-{@code ok}
 * statuses into {@link IOException} for the IDE's error handling. That adaptation is not engine
 * logic and deliberately does not belong behind the interface.
 */
@Service
public final class IntellijGitHubService {
    private final GitHubEngineApi engine;
    private final Supplier<String> baseUrlSupplier;

    /** Required by {@code @Service}; binds to the real engine and the persisted settings. */
    public IntellijGitHubService() {
        this(new GitHubEngine(), () -> PluginSettings.getInstance().getGithubBaseUrl());
    }

    /**
     * Test seam, matching the engine services' own convention. {@code baseUrlSupplier} is injected
     * separately because {@link PluginSettings} needs a running IDE application, which unit tests
     * do not have.
     */
    IntellijGitHubService(GitHubEngineApi engine, Supplier<String> baseUrlSupplier) {
        this.engine = engine;
        this.baseUrlSupplier = baseUrlSupplier;
    }

    public static IntellijGitHubService getInstance() {
        return ApplicationManager.getApplication().getService(IntellijGitHubService.class);
    }

    private String baseUrl() {
        return baseUrlSupplier.get();
    }

    public List<PullRequest> searchPRs(String query) throws IOException {
        PrSearchResult result =
                engine.searchPullRequests(
                        new PrSupplementalService.SearchParams(baseUrl(), query, 50));
        requireOk(result.status(), result.message());
        return toPullRequests(result.prs());
    }

    public PullRequestList listPullRequests(String projectPath, String state, String searchScope)
            throws IOException {
        String currentRepo = detectCurrentRepo(projectPath);
        PrListResult result =
                engine.listPullRequests(
                        new PrListService.PrListParams(baseUrl(), state, searchScope, currentRepo));
        requireOk(result.status(), result.message());
        return new PullRequestList(
                toPullRequests(result.prs()),
                result.limited(),
                result.reviewStatusAvailable(),
                currentRepo,
                result.query());
    }

    public String detectCurrentRepo(String projectPath) {
        DetectResult result = engine.detectRepo(projectPath);
        return result.status() == DetectStatus.FOUND
                ? result.repository().owner() + "/" + result.repository().repo()
                : null;
    }

    /**
     * Verifies GitHub CLI credentials for an explicit origin; never returns a token. Takes the URL
     * as a parameter rather than reading settings because the settings dialog checks the value the
     * user has just typed, before it is persisted.
     */
    public CheckAuthResult checkAuth(String githubBaseUrl) {
        return engine.checkAuth(githubBaseUrl);
    }

    public List<String> getStarredRepos() throws IOException {
        StarredReposResult result = engine.listStarredRepositories(baseUrl());
        requireOk(result.status(), result.message());
        return result.repositories();
    }

    public String getPRDiff(String owner, String repo, int prNumber) throws IOException {
        return getDiff(owner, repo, prNumber, "review");
    }

    public String getPRDiffFull(String owner, String repo, int prNumber) throws IOException {
        return getDiff(owner, repo, prNumber, "validation");
    }

    /**
     * Diff of commits pushed since the viewer's latest submitted review, or a full-review fallback
     * reason. A non-{@code ok} status throws, like {@link #getPRDiff}.
     */
    public IncrementalDiffResult getIncrementalDiff(String owner, String repo, int prNumber)
            throws IOException {
        IncrementalDiffResult result =
                engine.getIncrementalDiff(
                        new IncrementalDiffService.Params(baseUrl(), owner, repo, prNumber));
        requireOk(result.status(), result.message());
        return result;
    }

    public SaveDraftResult saveDraftReview(
            String owner, String repo, int number, ReviewResult review, List<LineComment> orphans)
            throws IOException {
        DraftReviewMutationResult result =
                engine.saveDraftReview(
                        new DraftReviewMutationService.SaveParams(
                                baseUrl(),
                                owner,
                                repo,
                                number,
                                review.getSummary(),
                                review.getVerdict(),
                                review.getLineComments().stream()
                                        .map(IntellijGitHubService::toInput)
                                        .toList(),
                                orphans.stream().map(IntellijGitHubService::toInput).toList()));
        requireOk(result.status(), result.message());
        return new SaveDraftResult(result.reviewId(), result.commentsDropped());
    }

    public PendingReview loadDraftReview(String owner, String repo, int number) throws IOException {
        DraftReviewResult result = engine.getDraftReview(baseUrl(), owner, repo, number);
        if ("none".equals(result.status())) return null;
        requireOk(result.status(), result.message());
        DraftReviewCodec.DecodedReview decoded = result.review();
        List<LineComment> comments =
                decoded.lineComments().stream().map(IntellijGitHubService::toCore).toList();
        return new PendingReview(
                result.id(),
                new ReviewResult(decoded.summary(), decoded.verdict(), comments),
                decoded.importedFromGitHub(),
                result.commitId());
    }

    public void submitDraftReview(
            String owner, String repo, int number, String reviewId, String event, String body)
            throws IOException {
        DraftReviewMutationResult result =
                engine.submitReview(
                        new DraftReviewMutationService.SubmitParams(
                                baseUrl(), owner, repo, number, reviewId, event, body));
        requireOk(result.status(), result.message());
    }

    public void deleteDraftReview(String owner, String repo, int number, String reviewId)
            throws IOException {
        DraftReviewMutationResult result =
                engine.deleteDraftReview(
                        new DraftReviewMutationService.DeleteParams(
                                baseUrl(), owner, repo, number, reviewId));
        requireOk(result.status(), result.message());
    }

    public String getExistingReviewsSummary(String owner, String repo, int number)
            throws IOException {
        ExistingReviewsResult result =
                engine.getExistingReviews(
                        new PrSupplementalService.IdentityParams(baseUrl(), owner, repo, number));
        requireOk(result.status(), result.message());
        return result.summary();
    }

    public boolean isPRMerged(String owner, String repo, int number) throws IOException {
        return getPRDetail(owner, repo, number).merged();
    }

    public String getPRHeadSha(String owner, String repo, int number) throws IOException {
        PrDetail.Head head = getPRDetail(owner, repo, number).head();
        return head == null ? "" : head.sha();
    }

    /**
     * Head and base commit SHAs from one PR-detail fetch. Either may be blank when GitHub omits it;
     * the base SHA is only ever a validated full hex object id.
     */
    public PRRevisions getPRRevisions(String owner, String repo, int number) throws IOException {
        PrDetail detail = getPRDetail(owner, repo, number);
        PrDetail.Head head = detail.head();
        return new PRRevisions(
                head == null ? "" : StringUtils.defaultString(head.sha()),
                StringUtils.defaultString(detail.baseSha()));
    }

    public PRHeadInfo getPRHeadInfo(String owner, String repo, int number) throws IOException {
        PrDetail detail = getPRDetail(owner, repo, number);
        PrDetail.Head head = detail.head();
        if (head == null) return new PRHeadInfo("", "", false, "");
        boolean fork =
                head.repoFullName() != null
                        && !head.repoFullName().isBlank()
                        && !head.repoFullName().equals(detail.baseRepoFullName());
        return new PRHeadInfo(
                head.ref(),
                head.sha() == null ? "" : head.sha(),
                fork,
                fork && head.cloneUrl() != null ? head.cloneUrl() : "");
    }

    /*
     * The four prompt-context reads below deliberately do NOT call requireOk. Unlike the diff, this
     * context is purely additive: a review without it is exactly as good as before it existed, so a
     * CI outage or a missing token must degrade the prompt rather than fail the review.
     */

    /**
     * Rendered CI state plus the structured annotations behind it, in one request. The rendered
     * form goes into the prompt; the annotations are machine-comparable, which is what lets
     * duplicate review comments be dropped rather than merely discouraged in the prompt.
     */
    public CheckContext getCheckContext(String owner, String repo, String headSha) {
        CheckStatusResult result =
                engine.getCheckStatus(new CheckRunService.Params(baseUrl(), owner, repo, headSha));
        List<CiAnnotation> annotations =
                result.annotations() == null
                        ? List.of()
                        : result.annotations().stream()
                                .map(
                                        a ->
                                                new CiAnnotation(
                                                        a.path(),
                                                        a.startLine(),
                                                        a.level(),
                                                        a.message()))
                                .toList();
        return new CheckContext(result.summary(), annotations);
    }

    /** Rendered CI state and the structured findings behind it. */
    public record CheckContext(String summary, List<CiAnnotation> annotations) {}

    /** Rendered commit messages and validated closing references, or empty on failure. */
    public CommitContext getCommitContext(String owner, String repo, int number) {
        PrCommitsResult result =
                engine.getCommits(
                        new PrSupplementalService.IdentityParams(baseUrl(), owner, repo, number));
        return new CommitContext(result.summary(), result.closingIssueNumbers());
    }

    /** Prompt-ready commit context returned by the shared engine. */
    public record CommitContext(String summary, List<Integer> closingIssueNumbers) {
        public CommitContext {
            closingIssueNumbers = List.copyOf(closingIssueNumbers);
        }
    }

    /** Rendered issues the PR declares it closes, or empty when there are none. */
    public String getLinkedIssueSummary(
            String owner, String repo, String prBody, List<Integer> commitIssueNumbers) {
        LinkedIssueResult result =
                engine.getLinkedIssues(
                        new LinkedIssueService.Params(
                                baseUrl(), owner, repo, prBody, commitIssueNumbers));
        return result.summary();
    }

    /** Rendered language/build-tooling profile for a working tree; performs no network call. */
    public String getRepoProfileSummary(String projectDir) {
        return engine.getRepoProfile(projectDir).summary();
    }

    private String getDiff(String owner, String repo, int number, String mode) throws IOException {
        PrDiffResult result =
                engine.getPullRequestDiff(
                        new PrDiffService.Params(baseUrl(), owner, repo, number, mode));
        requireOk(result.status(), result.message());
        return result.diff();
    }

    public PrDetail getPRDetail(String owner, String repo, int number) throws IOException {
        PrDetailResult result =
                engine.getPullRequestDetail(
                        new PrDetailService.PrDetailParams(baseUrl(), owner, repo, number));
        requireOk(result.status(), result.message());
        return result.detail();
    }

    private static DraftReviewMutationService.CommentInput toInput(LineComment comment) {
        return new DraftReviewMutationService.CommentInput(
                comment.getFile(),
                comment.getLine(),
                comment.getType(),
                comment.getBody(),
                comment.getSeverity(),
                comment.getCategory(),
                comment.getConfidence(),
                comment.getRationale(),
                comment.getSuggestedChange());
    }

    private static LineComment toCore(DraftReviewCodec.LineComment comment) {
        LineComment mapped =
                new LineComment(comment.file(), comment.line(), comment.type(), comment.body());
        mapped.setSeverity(comment.severity());
        mapped.setCategory(comment.category());
        mapped.setConfidence(comment.confidence());
        mapped.setRationale(comment.rationale());
        mapped.setSuggestedChange(comment.suggestedChange());
        return mapped;
    }

    static List<PullRequest> toPullRequests(List<PullRequestSummary> prs) {
        return prs.stream()
                .map(
                        pr ->
                                new PullRequest(
                                        pr.title(),
                                        pr.htmlUrl(),
                                        pr.owner(),
                                        pr.repo(),
                                        pr.number(),
                                        "",
                                        pr.author(),
                                        pr.createdAt(),
                                        pr.isDraft(),
                                        pr.reviewStatus()))
                .toList();
    }

    private static void requireOk(String status, String message) throws IOException {
        if (!"ok".equals(status)) throw new GitHubOperationException(status, message);
    }

    static final class GitHubOperationException extends IOException {
        private final String status;

        GitHubOperationException(String status, String message) {
            super(message);
            this.status = status;
        }

        String status() {
            return status;
        }
    }

    public record SaveDraftResult(String reviewId, boolean commentsDropped) {}

    public record PendingReview(
            String id, ReviewResult result, boolean importedFromGitHub, String commitId) {}

    /**
     * PR head coordinates for worktree creation. {@code sha} is the commit the reviewed diff was
     * rendered at; the worktree pins to it so a mid-review push cannot change the tree under the
     * agent.
     */
    public record PRHeadInfo(String ref, String sha, boolean isFork, String forkCloneUrl) {}

    public record PRRevisions(String headSha, String baseSha) {}

    public record PullRequestList(
            List<PullRequest> pullRequests,
            boolean limited,
            boolean reviewStatusAvailable,
            String currentRepo,
            String query) {}
}
