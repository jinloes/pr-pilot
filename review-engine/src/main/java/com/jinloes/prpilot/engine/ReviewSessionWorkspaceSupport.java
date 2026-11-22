package com.jinloes.prpilot.engine;

import com.jinloes.prpilot.engine.ReviewEngineApi.AntigravityModelsResult;
import com.jinloes.prpilot.engine.ReviewEngineApi.CopilotModelsResult;
import com.jinloes.prpilot.engine.ReviewEngineApi.CreateWorktreeParams;
import com.jinloes.prpilot.engine.ReviewEngineApi.GitRootResult;
import com.jinloes.prpilot.engine.ReviewEngineApi.GuidelinesResult;
import com.jinloes.prpilot.engine.ReviewEngineApi.ListAntigravityModelsParams;
import com.jinloes.prpilot.engine.ReviewEngineApi.ListCopilotModelsParams;
import com.jinloes.prpilot.engine.ReviewEngineApi.ReadGuidelinesParams;
import com.jinloes.prpilot.engine.ReviewEngineApi.RemoveWorktreeParams;
import com.jinloes.prpilot.engine.ReviewEngineApi.ReviewedBlobParams;
import com.jinloes.prpilot.engine.ReviewEngineApi.ReviewedBlobResult;
import com.jinloes.prpilot.engine.ReviewEngineApi.WorktreeRemovalResult;
import com.jinloes.prpilot.engine.ReviewEngineApi.WorktreeResult;
import com.jinloes.prpilot.review.AntigravityModelDiscovery;
import com.jinloes.prpilot.review.CopilotModelDiscovery;
import com.jinloes.prpilot.review.GitWorktreeService;
import com.jinloes.prpilot.review.RepoGuidelinesReader;
import com.jinloes.prpilot.review.ReviewedBlobReader;
import com.jinloes.prpilot.util.LogRedactor;
import java.io.File;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Workspace, reviewed-blob, repository-guideline, and model-discovery helpers backing {@link
 * ReviewSessionService}. Kept separate so {@link ReviewSessionService} focuses on active review and
 * chat session lifecycles.
 */
final class ReviewSessionWorkspaceSupport {
    private static final Logger log = LoggerFactory.getLogger(ReviewSessionWorkspaceSupport.class);

    private ReviewSessionWorkspaceSupport() {}

    static ReviewedBlobResult readReviewedBlob(ReviewedBlobParams params) {
        try {
            return new ReviewedBlobResult(
                    new ReviewedBlobReader()
                            .read(params.gitRoot(), params.commit(), params.file(), params.line()),
                    null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn(
                    "Interrupted while reading reviewed blob {} at {}",
                    params.file(),
                    params.commit(),
                    LogRedactor.sanitize(interrupted));
            return new ReviewedBlobResult(null, "Reviewed file lookup was interrupted.");
        } catch (IOException | TimeoutException | InvalidPathException failure) {
            log.warn(
                    "Failed to read reviewed blob {} at {}",
                    params.file(),
                    params.commit(),
                    LogRedactor.sanitize(failure));
            return new ReviewedBlobResult(
                    null,
                    StringUtils.defaultIfBlank(
                            LogRedactor.redact(failure.getMessage()),
                            LogRedactor.errorType(failure)));
        }
    }

    static GuidelinesResult readGuidelines(ReadGuidelinesParams params) {
        if (params == null || StringUtils.isBlank(params.projectDir())) {
            return new GuidelinesResult("");
        }
        // RepoGuidelinesReader adds configured globs to the engine defaults, so no host needs its
        // own copy of the default file list.
        return new GuidelinesResult(
                RepoGuidelinesReader.read(new File(params.projectDir()), params.globs()));
    }

    static CopilotModelsResult listCopilotModels(
            ListCopilotModelsParams params,
            Supplier<CopilotModelDiscovery.Result> cachedLookup,
            Supplier<CopilotModelDiscovery.Result> refreshLookup) {
        boolean forceRefresh = params != null && params.forceRefresh();
        CopilotModelDiscovery.Result cached = cachedLookup.get();
        CopilotModelDiscovery.Result result =
                (!forceRefresh && cached != null) ? cached : refreshLookup.get();
        return new CopilotModelsResult(
                result.models(), result.source().name(), result.failureReason());
    }

    static AntigravityModelsResult listAntigravityModels(
            ListAntigravityModelsParams params,
            Supplier<AntigravityModelDiscovery.Result> cachedLookup,
            Supplier<AntigravityModelDiscovery.Result> refreshLookup) {
        boolean forceRefresh = params != null && params.forceRefresh();
        AntigravityModelDiscovery.Result cached = cachedLookup.get();
        AntigravityModelDiscovery.Result result =
                (!forceRefresh && cached != null) ? cached : refreshLookup.get();
        return new AntigravityModelsResult(
                result.models(), result.source().name(), result.failureReason());
    }

    static GitRootResult findGitRoot(GitWorktreeService worktreeService, String startDir) {
        if (StringUtils.isBlank(startDir)) return new GitRootResult("");
        try {
            File root = worktreeService.findGitRoot(new File(startDir));
            return new GitRootResult(root == null ? "" : root.getAbsolutePath());
        } catch (RuntimeException e) {
            // An uncanonicalizable path is "not a repository" from the caller's perspective; the
            // only consumer decides between worktree and plain checkout, and neither wants a throw.
            return new GitRootResult("");
        }
    }

    static WorktreeResult createWorktree(
            GitWorktreeService worktreeService, CreateWorktreeParams params) {
        if (params == null
                || StringUtils.isBlank(params.gitRoot())
                || StringUtils.isBlank(params.branch())) {
            return new WorktreeResult("skipped", "", "No branch to check out.");
        }
        File repoDir = new File(params.gitRoot());
        File worktreeDir = worktreeService.newWorktreePath(params.prNumber());
        try {
            if (StringUtils.isNotBlank(params.forkCloneUrl())) {
                worktreeService.createWorktreeFromFork(
                        repoDir,
                        params.forkCloneUrl(),
                        params.branch(),
                        params.headSha(),
                        worktreeDir);
            } else {
                worktreeService.createWorktree(
                        repoDir, params.branch(), params.headSha(), worktreeDir);
            }
            return new WorktreeResult("created", worktreeDir.getAbsolutePath(), "");
        } catch (IOException | IllegalStateException e) {
            log.warn(
                    "Failed to create worktree for PR #{} in {}",
                    params.prNumber(),
                    params.gitRoot(),
                    LogRedactor.sanitize(e));
            // Domain result, not an exception: hosts present an actionable failure rather than
            // granting a provider access to an arbitrary open checkout.
            return new WorktreeResult(
                    "failed",
                    "",
                    StringUtils.defaultIfBlank(
                            LogRedactor.redact(e.getMessage()), LogRedactor.errorType(e)));
        }
    }

    static WorktreeRemovalResult removeWorktree(
            GitWorktreeService worktreeService, RemoveWorktreeParams params) {
        if (params == null
                || StringUtils.isBlank(params.gitRoot())
                || StringUtils.isBlank(params.worktreeDir())) {
            return new WorktreeRemovalResult(false);
        }
        return new WorktreeRemovalResult(
                worktreeService.removeWorktree(
                        new File(params.gitRoot()), new File(params.worktreeDir())));
    }
}
