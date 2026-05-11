package com.jinloes.prpilot.services;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.model.ReviewResult;
import com.jinloes.prpilot.settings.PluginSettings;
import java.io.IOException;
import java.util.List;

/**
 * IntelliJ application service adapter that delegates to the core {@link GitHubService}.
 *
 * <p>A fresh {@link GitHubService} is constructed per call so that changes to the GitHub base URL
 * in settings take effect immediately without requiring a restart.
 */
@Service
public final class IntellijGitHubService {

    public static IntellijGitHubService getInstance() {
        return ApplicationManager.getApplication().getService(IntellijGitHubService.class);
    }

    private GitHubService core() {
        return new GitHubService(PluginSettings.getInstance().getApiBaseUrl());
    }

    public List<PullRequest> searchPRs(String token, String query)
            throws IOException, InterruptedException {
        return core().searchPRs(token, query);
    }

    public List<String> getStarredRepos(String token) throws IOException, InterruptedException {
        return core().getStarredRepos(token);
    }

    public String getPRDiff(String token, String owner, String repo, int prNumber)
            throws IOException, InterruptedException {
        return core().getPRDiff(token, owner, repo, prNumber);
    }

    public String getPendingReviewId(String token, String owner, String repo, int number)
            throws IOException, InterruptedException {
        return core().getPendingReviewId(token, owner, repo, number);
    }

    public GitHubService.SaveDraftResult saveDraftReview(
            String token, String owner, String repo, int number, ReviewResult review)
            throws IOException, InterruptedException {
        return core().saveDraftReview(token, owner, repo, number, review);
    }

    public GitHubService.PendingReview loadDraftReview(
            String token, String owner, String repo, int number)
            throws IOException, InterruptedException {
        return core().loadDraftReview(token, owner, repo, number);
    }

    public void submitDraftReview(
            String token,
            String owner,
            String repo,
            int number,
            String reviewId,
            String event,
            String body)
            throws IOException, InterruptedException {
        core().submitDraftReview(token, owner, repo, number, reviewId, event, body);
    }

    public void deleteDraftReview(
            String token, String owner, String repo, int number, String reviewId)
            throws IOException, InterruptedException {
        core().deleteDraftReview(token, owner, repo, number, reviewId);
    }

    public String getExistingReviewsSummary(String token, String owner, String repo, int number)
            throws IOException, InterruptedException {
        return core().getExistingReviewsSummary(token, owner, repo, number);
    }

    public boolean isPRMerged(String token, String owner, String repo, int number)
            throws IOException, InterruptedException {
        return core().isPRMerged(token, owner, repo, number);
    }

    public String getPRHeadSha(String token, String owner, String repo, int number)
            throws IOException, InterruptedException {
        return core().getPRHeadSha(token, owner, repo, number);
    }
}
