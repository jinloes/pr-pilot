package com.jinloes.prpilot.engine;

import com.jinloes.prpilot.sidecar.github.CheckAuthResult;
import com.jinloes.prpilot.sidecar.pr.CheckRunService;
import com.jinloes.prpilot.sidecar.pr.CheckStatusResult;
import com.jinloes.prpilot.sidecar.pr.DraftReviewMutationResult;
import com.jinloes.prpilot.sidecar.pr.DraftReviewMutationService;
import com.jinloes.prpilot.sidecar.pr.DraftReviewResult;
import com.jinloes.prpilot.sidecar.pr.ExistingReviewsResult;
import com.jinloes.prpilot.sidecar.pr.LinkedIssueResult;
import com.jinloes.prpilot.sidecar.pr.LinkedIssueService;
import com.jinloes.prpilot.sidecar.pr.PrCommitsResult;
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
import com.jinloes.prpilot.sidecar.repo.RepoProfileResult;
import java.util.Map;

/**
 * The complete host-neutral GitHub capability surface of this engine.
 *
 * <p>This interface is the parity boundary described in {@code AGENTS.md} "Host parity
 * obligations": every capability is declared here exactly once, and the sidecar must expose
 * <em>all</em> of them over JSON-RPC. Individual hosts (IntelliJ, VS Code, a future CLI or GitHub
 * Action) may lag in <em>consuming</em> a capability, but none of them may re-implement one.
 *
 * <p>{@link #RPC_METHODS} maps each Java method name to its wire method name. Both directions are
 * enforced by {@code EngineCapabilityCoverageTest} in the sidecar module:
 *
 * <ol>
 *   <li>every method declared here has an {@code RPC_METHODS} entry, so a new capability cannot be
 *       added without naming its wire method; and
 *   <li>every {@code RPC_METHODS} value is registered in {@code StdioJsonRpcServer}, so a
 *       capability cannot be declared without actually being reachable over the protocol.
 * </ol>
 *
 * <p>Do not add overloaded methods — {@link #RPC_METHODS} is keyed by bare method name.
 */
public interface GitHubEngineApi {

    /** Java method name to JSON-RPC wire method name. See the class javadoc for enforcement. */
    Map<String, String> RPC_METHODS =
            Map.ofEntries(
                    Map.entry("detectRepo", "repo/detect"),
                    Map.entry("checkAuth", "github/checkAuth"),
                    Map.entry("listPullRequests", "prs/list"),
                    Map.entry("searchPullRequests", "prs/search"),
                    Map.entry("listStarredRepositories", "repos/listStarred"),
                    Map.entry("getPullRequestDetail", "prs/getDetail"),
                    Map.entry("getPullRequestDiff", "prs/getDiff"),
                    Map.entry("getExistingReviews", "prs/getExistingReviews"),
                    Map.entry("getDraftReview", "prs/getDraftReview"),
                    Map.entry("saveDraftReview", "prs/saveDraftReview"),
                    Map.entry("submitReview", "prs/submitReview"),
                    Map.entry("deleteDraftReview", "prs/deleteDraftReview"),
                    Map.entry("getCheckStatus", "prs/getCheckStatus"),
                    Map.entry("getCommits", "prs/getCommits"),
                    Map.entry("getLinkedIssues", "prs/getLinkedIssues"),
                    Map.entry("getRepoProfile", "repo/getProfile"));

    /** Resolves owner/repo for a local directory by reading git metadata; spawns no git process. */
    DetectResult detectRepo(String path);

    /** Verifies {@code gh} availability and token validity against the given GitHub origin. */
    CheckAuthResult checkAuth(String githubBaseUrl);

    /** Scope-aware PR search backing the main list. */
    PrListResult listPullRequests(PrListService.PrListParams params);

    /** Bounded arbitrary PR search, used by notification polling. */
    PrSearchResult searchPullRequests(PrSupplementalService.SearchParams params);

    /** Up to 200 starred repositories, used by optional notification polling. */
    StarredReposResult listStarredRepositories(String githubBaseUrl);

    /** PR metadata plus fork-aware head information for worktree creation. */
    PrDetailResult getPullRequestDetail(PrDetailService.PrDetailParams params);

    /** Bounded unified diff in either {@code review} or {@code validation} mode. */
    PrDiffResult getPullRequestDiff(PrDiffService.Params params);

    /** Formatted summary of already-submitted reviews, used as prompt context. */
    ExistingReviewsResult getExistingReviews(PrSupplementalService.IdentityParams params);

    /** Loads the pending (draft) review for a PR; {@code none} status is a normal result. */
    DraftReviewResult getDraftReview(String githubBaseUrl, String owner, String repo, int number);

    /** Updates or recoverably replaces an existing pending review. */
    DraftReviewMutationResult saveDraftReview(DraftReviewMutationService.SaveParams params);

    /** Submits a pending review with the given event and body. */
    DraftReviewMutationResult submitReview(DraftReviewMutationService.SubmitParams params);

    /** Deletes a pending review. */
    DraftReviewMutationResult deleteDraftReview(DraftReviewMutationService.DeleteParams params);

    /**
     * CI check runs and file-anchored annotations for the PR head commit, used as prompt context.
     * Purely additive — a review is never blocked on CI, which can take far longer than a review.
     */
    CheckStatusResult getCheckStatus(CheckRunService.Params params);

    /** Commit messages on the PR, used as prompt context for author intent. */
    PrCommitsResult getCommits(PrSupplementalService.IdentityParams params);

    /** Issues the PR declares it closes, used as prompt context for intended behavior. */
    LinkedIssueResult getLinkedIssues(LinkedIssueService.Params params);

    /** Language and build tooling detected from a working tree; performs no network call. */
    RepoProfileResult getRepoProfile(String projectDir);
}
