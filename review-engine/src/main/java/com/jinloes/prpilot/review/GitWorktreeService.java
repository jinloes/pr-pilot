package com.jinloes.prpilot.review;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages temporary git worktrees for PR branch reviews.
 *
 * <p>A worktree lets Claude/Copilot read source files at the PR branch state rather than the user's
 * currently checked-out branch, improving review accuracy for type lookups and cross-file
 * references. The shared git object store means no re-clone is needed — only the working tree files
 * are written for the new worktree.
 *
 * <p>Worktrees are pinned to the PR's head commit rather than its branch tip, so the tree the agent
 * reads matches the diff being reviewed even if the contributor pushes mid-review.
 */
public class GitWorktreeService {

    private static final Logger log = LoggerFactory.getLogger(GitWorktreeService.class);
    private final BoundedProcessRunner processRunner;

    /** Abbreviated or full hex object name; anything else is never passed to git as a revision. */
    private static final Pattern HEX_OBJECT_NAME = Pattern.compile("[0-9a-fA-F]{7,64}");

    public GitWorktreeService() {
        this(new BoundedProcessRunner());
    }

    GitWorktreeService(BoundedProcessRunner processRunner) {
        this.processRunner = processRunner;
    }

    /**
     * Walks up from {@code startDir} to find the git repository root — the closest ancestor
     * (inclusive) that contains a {@code .git} entry. Returns null if no git root is found.
     *
     * <p>Wraps a canonicalization failure in {@link java.io.UncheckedIOException} rather than
     * declaring a checked {@link IOException}, matching the unchecked propagation callers relied on
     * from the former Kotlin implementation.
     */
    public File findGitRoot(File startDir) {
        File dir;
        try {
            dir = startDir.getCanonicalFile();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        while (dir != null) {
            if (new File(dir, ".git").exists()) return dir;
            dir = dir.getParentFile();
        }
        return null;
    }

    /**
     * Creates a git worktree at {@code worktreeDir} pinned to {@code headSha}, the commit the
     * reviewed diff was rendered at.
     *
     * <p>Runs {@code git fetch origin <branch>} first so the commit is available, then {@code git
     * worktree add --detach <worktreeDir> <headSha>}. The reviewed SHA must be available locally; a
     * moving branch tip is never substituted for it.
     *
     * @param repoDir git repository root (must contain {@code .git})
     * @param branch branch name on the origin remote (without the {@code origin/} prefix)
     * @param headSha PR head commit the diff was rendered at
     * @param worktreeDir destination path for the worktree; must not exist
     * @throws IOException if a git command fails or times out
     */
    public void createWorktree(File repoDir, String branch, String headSha, File worktreeDir)
            throws IOException {
        log.info("Fetching branch {} from origin in {}", branch, repoDir);
        fetch(repoDir, 60, "origin", branch);
        String commitish = pinnedCommitish(repoDir, headSha);
        log.info("Creating worktree at {} for {}", worktreeDir, commitish);
        runGit(
                repoDir,
                30,
                "worktree",
                "add",
                "--detach",
                worktreeDir.getAbsolutePath(),
                commitish);
    }

    /**
     * Creates a git worktree at {@code worktreeDir} by fetching a branch from a fork's remote URL.
     * Use this for fork PRs where the branch is not available on {@code origin}.
     *
     * <p>Runs {@code git fetch <forkCloneUrl> <branch>} then pins to {@code headSha}. Forks need
     * the same exact pinning as origin branches: {@code FETCH_HEAD} is a moving branch tip and is
     * never substituted for the reviewed commit.
     *
     * @param repoDir git repository root
     * @param forkCloneUrl HTTPS or SSH clone URL of the fork
     * @param branch branch name on the fork
     * @param headSha PR head commit the diff was rendered at
     * @param worktreeDir destination path for the worktree; must not exist
     * @throws IOException if a git command fails or times out
     */
    public void createWorktreeFromFork(
            File repoDir, String forkCloneUrl, String branch, String headSha, File worktreeDir)
            throws IOException {
        log.info("Fetching branch {} from fork {} in {}", branch, forkCloneUrl, repoDir);
        fetch(repoDir, 120, forkCloneUrl, branch);
        String commitish = pinnedCommitish(repoDir, headSha);
        log.info("Creating worktree at {} from {}", worktreeDir, commitish);
        runGit(
                repoDir,
                30,
                "worktree",
                "add",
                "--detach",
                worktreeDir.getAbsolutePath(),
                commitish);
    }

    void fetch(File repoDir, long timeoutSeconds, String remote, String branch) throws IOException {
        if (StringUtils.isBlank(branch) || branch.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Pull request branch is invalid.");
        }
        runGit(repoDir, timeoutSeconds, "fetch", remote, "--", branch);
    }

    /**
     * Returns {@code headSha} only when the fetch made that exact commit available locally.
     *
     * <p>The reviewed diff was rendered at {@code headSha}, but a branch tip is a *moving* target:
     * a push between rendering the diff and building the worktree would otherwise leave the agent
     * grepping code that is not under review. Normally the tip is a descendant of the reviewed
     * commit, so the fetch brings the commit along and pinning succeeds.
     *
     * <p>A missing, malformed, or force-pushed SHA fails the operation. A provider must never read
     * a branch tip or an arbitrary open checkout while reviewing a different diff.
     */
    String pinnedCommitish(File repoDir, String headSha) {
        if (headSha == null || headSha.isBlank()) {
            throw new IllegalStateException("Pull request head commit is missing.");
        }
        if (!HEX_OBJECT_NAME.matcher(headSha).matches()) {
            // headSha comes from the GitHub API response; never hand git an argument that could
            // be read as an option or a different revision.
            throw new IllegalStateException("Pull request head commit is invalid.");
        }
        if (commitExists(repoDir, headSha)) return headSha;
        throw new IllegalStateException(
                "Pull request head commit is unavailable after fetch (it may have been force-pushed).");
    }

    private boolean commitExists(File repoDir, String sha) {
        try {
            return execGit(repoDir, 15, "cat-file", "-e", sha + "^{commit}").exitCode() == 0;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Returns a unique temp path for a PR's worktree. The directory must not exist when the
     * worktree is created, so the name carries both a timestamp and randomness — rapid consecutive
     * calls for the same PR would otherwise collide within a millisecond.
     *
     * <p>Lives here rather than in each host because the two hosts had drifted to different name
     * formats, and the cleanup path matches on the {@code pr-pilot-wt-} prefix.
     */
    public File newWorktreePath(int prNumber) {
        String unique =
                prNumber
                        + "-"
                        + System.currentTimeMillis()
                        + "-"
                        + Long.toHexString(ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE);
        return new File(System.getProperty("java.io.tmpdir"), "pr-pilot-wt-" + unique);
    }

    /**
     * Removes a previously created worktree. An already-absent directory is considered removed.
     * Cleanup failures are returned rather than thrown so hosts can log or retry without blocking
     * disposal.
     *
     * @param repoDir git repository root
     * @param worktreeDir the worktree directory to remove
     */
    public boolean removeWorktree(File repoDir, File worktreeDir) {
        try {
            return new SemanticWorktreeStore()
                    .ordinaryRemoval(
                            worktreeDir.toPath(),
                            () -> removeOrdinaryWorktree(repoDir, worktreeDir));
        } catch (IOException | RuntimeException failure) {
            log.warn("Retained worktree protection blocked cleanup", failure);
            return false;
        }
    }

    boolean isRegisteredHead(File repoDir, File worktreeDir, String head) throws IOException {
        GitResult current = execGit(worktreeDir, 15, "rev-parse", "HEAD");
        return current.exitCode() == 0
                && current.output().trim().equals(head)
                && isRegisteredWorktree(repoDir, worktreeDir);
    }

    boolean removeManagedWorktree(File repoDir, File worktreeDir) {
        try {
            runGit(repoDir, 30, "worktree", "remove", "--", worktreeDir.getAbsolutePath());
            return true;
        } catch (IOException failure) {
            log.warn("Non-force retained cleanup failed; preserving ownership", failure);
            return false;
        }
    }

    private boolean removeOrdinaryWorktree(File repoDir, File worktreeDir) {
        try {
            runGit(repoDir, 30, "worktree", "remove", "--force", worktreeDir.getAbsolutePath());
            log.info("Removed worktree at {}", worktreeDir);
            return true;
        } catch (IOException exception) {
            if (!worktreeDir.exists()) {
                try {
                    if (!isRegisteredWorktree(repoDir, worktreeDir)) {
                        log.info("Worktree at {} is already absent and unregistered", worktreeDir);
                        return true;
                    }
                } catch (IOException registrationException) {
                    exception.addSuppressed(registrationException);
                }
            }
            log.warn("Failed to remove worktree at {}", worktreeDir, exception);
            return false;
        }
    }

    private boolean isRegisteredWorktree(File repoDir, File worktreeDir) throws IOException {
        GitResult result = execGit(repoDir, 15, "worktree", "list", "--porcelain");
        if (result.exitCode() != 0) {
            throw new IOException(
                    "git worktree list failed (exit "
                            + result.exitCode()
                            + "): "
                            + result.output().trim());
        }
        File target = worktreeDir.getCanonicalFile();
        for (String line : result.output().split("\\R")) {
            if (line.startsWith("worktree ")
                    && new File(line.substring("worktree ".length()))
                            .getCanonicalFile()
                            .equals(target)) {
                return true;
            }
        }
        return false;
    }

    void runGit(File dir, long timeoutSeconds, String... args) throws IOException {
        GitResult result = execGit(dir, timeoutSeconds, args);
        if (result.exitCode() != 0) {
            String trimmed = result.output().trim();
            throw new IOException(
                    "git "
                            + args[0]
                            + " failed (exit "
                            + result.exitCode()
                            + "): "
                            + trimmed.substring(0, Math.min(300, trimmed.length())));
        }
    }

    /** Exit code and combined output of a git invocation. */
    private record GitResult(int exitCode, String output) {}

    /**
     * Runs git and returns its exit code instead of throwing, so callers can probe for a condition
     * (such as whether a commit exists) without treating a non-zero exit as an error.
     */
    private GitResult execGit(File dir, long timeoutSeconds, String... args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true);
        pb.environment().put("HOME", System.getProperty("user.home", "/"));
        String existingPath = pb.environment().getOrDefault("PATH", "");
        pb.environment().put("PATH", "/opt/homebrew/bin:/usr/local/bin:" + existingPath);
        try {
            BoundedProcessRunner.ProcessResult result =
                    processRunner.run(pb, timeoutSeconds, TimeUnit.SECONDS);
            String output =
                    result.outputTruncated()
                            ? result.output() + "\n...(process output truncated)"
                            : result.output();
            return new GitResult(result.exitCode(), output);
        } catch (TimeoutException e) {
            throw new IOException("git " + args[0] + " timed out after " + timeoutSeconds + "s", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git " + args[0] + " interrupted", e);
        }
    }
}
