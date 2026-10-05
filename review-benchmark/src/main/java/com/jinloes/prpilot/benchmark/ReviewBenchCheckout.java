package com.jinloes.prpilot.benchmark;

import com.jinloes.prpilot.review.GitWorktreeService;
import com.jinloes.prpilot.sidecar.pr.PrDiffResult;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A detached worktree at a ReviewBench task's head commit, cloned from the task's review-bench
 * mirror, plus the three-dot review diff from base. Clones are partial ({@code blob:none}) and
 * reused across tasks and runs, because several corpus repositories are large.
 */
final class ReviewBenchCheckout implements AutoCloseable {
    private static final long CLONE_TIMEOUT_SECONDS = 900;
    private static final long FETCH_TIMEOUT_SECONDS = 300;
    private static final long WORKTREE_TIMEOUT_SECONDS = 600;

    private final GitWorktreeService worktrees;
    private final File repoDir;
    private final File worktreeDir;
    private final PrDiffResult diff;

    private ReviewBenchCheckout(
            GitWorktreeService worktrees, File repoDir, File worktreeDir, PrDiffResult diff) {
        this.worktrees = worktrees;
        this.repoDir = repoDir;
        this.worktreeDir = worktreeDir;
        this.diff = diff;
    }

    /** Returns the clone at {@code reposDir/name}, cloning {@code url} without a checkout first. */
    static File ensureClone(Path reposDir, String name, String url)
            throws IOException, InterruptedException {
        Path clone = reposDir.resolve(name);
        if (Files.exists(clone.resolve(".git"))) return clone.toFile();
        Files.createDirectories(reposDir);
        Path partial = reposDir.resolve(name + ".partial");
        if (Files.exists(partial)) {
            throw new IOException(
                    "A previous clone of " + name + " was interrupted; delete " + partial);
        }
        int exit =
                LocalCheckout.run(
                        reposDir.toFile(),
                        CLONE_TIMEOUT_SECONDS,
                        "clone",
                        "--quiet",
                        "--filter=blob:none",
                        "--no-checkout",
                        "--",
                        url,
                        partial.toString());
        if (exit != 0) throw new IOException("git clone " + url + " exited with " + exit);
        Files.move(partial, clone);
        return clone.toFile();
    }

    /**
     * Makes both commits available, falling back to {@code upstreamUrl} only when the mirror lacks
     * one, then checks out {@code head} in a fresh worktree.
     */
    static ReviewBenchCheckout open(
            GitWorktreeService worktrees,
            File repoDir,
            int prNumber,
            String base,
            String head,
            String upstreamUrl,
            boolean validationDiff)
            throws IOException, InterruptedException {
        for (String sha : List.of(base, head)) {
            ensureCommit(repoDir, sha, upstreamUrl);
        }
        File worktreeDir = worktrees.newWorktreePath(prNumber);
        int exit =
                LocalCheckout.run(
                        repoDir,
                        WORKTREE_TIMEOUT_SECONDS,
                        "worktree",
                        "add",
                        "--detach",
                        worktreeDir.getAbsolutePath(),
                        head);
        if (exit != 0) throw new IOException("git worktree add exited with " + exit);
        try {
            return new ReviewBenchCheckout(
                    worktrees,
                    repoDir,
                    worktreeDir,
                    LocalCheckout.renderDiff(repoDir, base, head, validationDiff));
        } catch (IOException | InterruptedException | RuntimeException failure) {
            worktrees.removeWorktree(repoDir, worktreeDir);
            throw failure;
        }
    }

    private static void ensureCommit(File repoDir, String sha, String upstreamUrl)
            throws IOException, InterruptedException {
        if (LocalCheckout.commitExists(repoDir, sha)) return;
        for (String remote : List.of("origin", upstreamUrl)) {
            // Best effort: a remote that refuses fetch-by-SHA falls through to the next one.
            LocalCheckout.run(
                    repoDir,
                    FETCH_TIMEOUT_SECONDS,
                    "fetch",
                    "--quiet",
                    "--no-tags",
                    "--filter=blob:none",
                    remote,
                    sha);
            if (LocalCheckout.commitExists(repoDir, sha)) return;
        }
        throw new IOException("Commit " + sha + " is in neither the mirror nor " + upstreamUrl);
    }

    File worktreeDir() {
        return worktreeDir;
    }

    PrDiffResult diff() {
        return diff;
    }

    @Override
    public void close() {
        worktrees.removeWorktree(repoDir, worktreeDir);
    }
}
