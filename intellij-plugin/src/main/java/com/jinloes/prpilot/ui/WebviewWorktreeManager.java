package com.jinloes.prpilot.ui;

import static com.intellij.openapi.application.ApplicationManager.getApplication;
import static com.jinloes.prpilot.ui.WebviewPrSupport.worktreeKey;

import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.review.GitWorktreeService;
import com.jinloes.prpilot.services.IntellijClaudeService;
import com.jinloes.prpilot.services.IntellijGitHubService;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.PrWorktree;
import java.io.File;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns isolated PR worktree acquisition, cleanup, and Claude service resolution. */
final class WebviewWorktreeManager {

    private static final Logger log = LoggerFactory.getLogger(WebviewWorktreeManager.class);

    private final String projectPath;
    private final IntellijGitHubService ghSvc;
    private final GitWorktreeService worktreeService;
    private final WorktreeCoordinator<PrWorktree> worktrees;

    WebviewWorktreeManager(
            String projectPath,
            IntellijGitHubService ghSvc,
            GitWorktreeService worktreeService,
            WorktreeCoordinator<PrWorktree> worktrees) {
        this.projectPath = projectPath;
        this.ghSvc = ghSvc;
        this.worktreeService = worktreeService;
        this.worktrees = worktrees;
    }

    IntellijClaudeService resolve(PullRequest pr) {
        WorktreeCoordinator.WorktreeLease<PrWorktree> lease;
        File detectedRoot;
        String key;
        synchronized (this) {
            if (pr == null) {
                throw new IllegalStateException("The selected pull request changed.");
            }
            key = worktreeKey(pr.getNumber(), pr.getOwner(), pr.getRepo());
            if (projectPath == null) {
                throw new IllegalStateException(
                        "Open the pull request repository before starting a review or chat.");
            }
            detectedRoot = worktreeService.findGitRoot(new File(projectPath));
            String currentRepo = ghSvc.detectCurrentRepo(projectPath);
            boolean sameRepo =
                    currentRepo != null
                            && currentRepo.equalsIgnoreCase(pr.getOwner() + "/" + pr.getRepo());
            if (detectedRoot == null || !sameRepo) {
                throw new IllegalStateException(
                        "Open the pull request repository before starting a review or chat.");
            }
            lease = worktrees.acquire(key);
        }

        if (!lease.owner()) {
            return serviceForWorktree(lease.future().join().directory());
        }

        File wt = worktreeService.newWorktreePath(pr.getNumber());
        try {
            IntellijGitHubService.PRHeadInfo headInfo =
                    ghSvc.getPRHeadInfo(pr.getOwner(), pr.getRepo(), pr.getNumber());
            if (headInfo.ref().isBlank()) {
                worktrees.fail(lease);
                throw new IllegalStateException("Unable to determine the pull request branch.");
            }

            if (headInfo.isFork()) {
                worktreeService.createWorktreeFromFork(
                        detectedRoot, headInfo.forkCloneUrl(), headInfo.ref(), headInfo.sha(), wt);
            } else {
                worktreeService.createWorktree(detectedRoot, headInfo.ref(), headInfo.sha(), wt);
            }

            PrWorktree created = new PrWorktree(wt, detectedRoot);
            if (worktrees.install(lease, created)) {
                log.info("Using worktree {} for PR #{}", wt, pr.getNumber());
                return serviceForWorktree(created.directory());
            }
            if (!worktreeService.removeWorktree(detectedRoot, wt)) {
                log.warn("Failed to remove discarded worktree at {}", wt);
            }
            throw new IllegalStateException("The selected pull request changed.");
        } catch (Exception e) {
            worktrees.fail(lease);
            if (wt.exists() && !worktreeService.removeWorktree(detectedRoot, wt)) {
                log.warn("Failed to remove incomplete worktree at {}", wt);
            }
            log.warn("Worktree creation for PR #{} failed: {}", pr.getNumber(), e.getMessage());
            throw new IllegalStateException(
                    "Unable to create an isolated pull request worktree.", e);
        }
    }

    PrWorktree clear() {
        return worktrees.clear();
    }

    PrWorktree activeValue() {
        return worktrees.activeValue();
    }

    void removeAsync(PrWorktree worktree) {
        if (worktree != null && worktree.directory() != null && worktree.gitRoot() != null) {
            getApplication()
                    .executeOnPooledThread(
                            () -> {
                                if (!worktreeService.removeWorktree(
                                        worktree.gitRoot(), worktree.directory())) {
                                    log.warn(
                                            "Failed to remove worktree at {}",
                                            worktree.directory());
                                }
                            });
        }
    }

    static IntellijClaudeService serviceForWorktree(File directory) {
        return new IntellijClaudeService(directory.getAbsolutePath());
    }
}
