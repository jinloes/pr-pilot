package com.jinloes.prpilot.benchmark;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.jinloes.prpilot.review.GitWorktreeService;
import com.jinloes.prpilot.sidecar.pr.PrDiffResult;
import com.jinloes.prpilot.sidecar.pr.PrDiffService;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * A detached worktree of a local clone at the exact commit the reference reviewer reviewed, plus
 * the bounded review diff against the PR base. The diff is rendered locally because GitHub only
 * serves a PR's <em>current</em> diff, which can differ from what the reference reviewer saw.
 */
final class LocalCheckout implements AutoCloseable {
    private static final Pattern SHA = Pattern.compile("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}");
    private static final long FETCH_TIMEOUT_SECONDS = 180;
    private static final long DIFF_TIMEOUT_SECONDS = 120;

    private final GitWorktreeService worktrees;
    private final File repoDir;
    private final File worktreeDir;
    private final PrDiffResult diff;

    private LocalCheckout(
            GitWorktreeService worktrees, File repoDir, File worktreeDir, PrDiffResult diff) {
        this.worktrees = worktrees;
        this.repoDir = repoDir;
        this.worktreeDir = worktreeDir;
        this.diff = diff;
    }

    /**
     * Fetches the PR head ref and both commits, then creates the worktree at {@code commit}. The
     * base and commit are fetched by object name as well, so a later force push that dropped the
     * reference reviewer's commit from the PR head does not by itself fail the checkout. {@code
     * validationDiff} bounds the diff at the validation limit the hosts use for chunked reviews
     * instead of the review limit.
     */
    static LocalCheckout open(
            GitWorktreeService worktrees,
            File repoDir,
            int prNumber,
            String baseSha,
            String commit,
            boolean validationDiff)
            throws IOException, InterruptedException {
        requireSha(baseSha, "base");
        requireSha(commit, "reviewed");
        for (String sha : List.of(baseSha, commit)) {
            fetchIfMissing(repoDir, sha);
        }
        if (!commitExists(repoDir, baseSha)) {
            throw new IOException("Base commit " + baseSha + " is not available in " + repoDir);
        }
        File worktreeDir = worktrees.newWorktreePath(prNumber);
        worktrees.createWorktree(repoDir, "refs/pull/" + prNumber + "/head", commit, worktreeDir);
        try {
            return new LocalCheckout(
                    worktrees,
                    repoDir,
                    worktreeDir,
                    renderDiff(repoDir, baseSha, commit, validationDiff));
        } catch (IOException | InterruptedException | RuntimeException failure) {
            worktrees.removeWorktree(repoDir, worktreeDir);
            throw failure;
        }
    }

    /** A candidate base and how many commits its three-dot diff to the reviewed commit spans. */
    record Base(String sha, int commits) {}

    /**
     * The candidate base whose three-dot diff to {@code commit} spans the fewest commits. A stacked
     * PR's current base is often the default branch after its parent was squash-merged, so diffing
     * against it would re-review the whole stack; the parent branch tip the reference reviewer saw
     * yields only this PR's commits. Candidates that are malformed, unavailable after a best-effort
     * fetch, or already contain {@code commit} (an empty diff) are skipped; ties keep the earlier
     * candidate. Returns null when no candidate qualifies.
     */
    static Base reviewBase(File repoDir, String commit, List<String> candidates)
            throws IOException, InterruptedException {
        requireSha(commit, "reviewed");
        fetchIfMissing(repoDir, commit);
        Base best = null;
        for (String candidate : new LinkedHashSet<>(candidates)) {
            if (candidate == null || !SHA.matcher(candidate).matches()) continue;
            fetchIfMissing(repoDir, candidate);
            if (!commitExists(repoDir, candidate)) continue;
            if (run(repoDir, 15, "merge-base", "--is-ancestor", commit, candidate) == 0) continue;
            String mergeBase = output(repoDir, 30, "merge-base", candidate, commit);
            if (mergeBase == null) continue;
            String count = output(repoDir, 60, "rev-list", "--count", mergeBase + ".." + commit);
            if (count == null || !count.matches("\\d+")) continue;
            int commits = Integer.parseInt(count);
            if (best == null || commits < best.commits()) {
                best = new Base(candidate, commits);
            }
        }
        return best;
    }

    private static void fetchIfMissing(File repoDir, String sha)
            throws IOException, InterruptedException {
        if (!commitExists(repoDir, sha)) {
            // Best effort: servers that refuse fetch-by-SHA still get the PR head fetch in open.
            run(repoDir, FETCH_TIMEOUT_SECONDS, "fetch", "--no-tags", "origin", "--", sha);
        }
    }

    static PrDiffResult renderDiff(
            File repoDir, String baseSha, String commit, boolean validationDiff)
            throws IOException, InterruptedException {
        Process process =
                new ProcessBuilder(
                                gitCommand(
                                        "diff",
                                        "--no-color",
                                        "--no-ext-diff",
                                        "--no-textconv",
                                        baseSha + "..." + commit))
                        .directory(repoDir)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
        try {
            PrDiffResult result =
                    validationDiff
                            ? PrDiffService.boundValidationDiff(process.getInputStream())
                            : PrDiffService.boundReviewDiff(process.getInputStream());
            // Bounding may stop reading at the scan ceiling; git would then block on the pipe.
            if (result.truncated()) return result;
            if (!process.waitFor(DIFF_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("git diff timed out");
            }
            if (process.exitValue() != 0) {
                throw new IOException("git diff exited with " + process.exitValue());
            }
            return result;
        } finally {
            process.destroy();
        }
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

    private static void requireSha(String value, String name) {
        if (value == null || !SHA.matcher(value).matches()) {
            throw new IllegalArgumentException("The " + name + " commit is missing or invalid.");
        }
    }

    static boolean commitExists(File repoDir, String sha) throws InterruptedException {
        try {
            return run(repoDir, 15, "cat-file", "-e", sha + "^{commit}") == 0;
        } catch (IOException e) {
            return false;
        }
    }

    static int run(File dir, long timeoutSeconds, String... args)
            throws IOException, InterruptedException {
        Process process =
                new ProcessBuilder(gitCommand(args))
                        .directory(dir)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("git " + args[0] + " timed out");
        }
        return process.exitValue();
    }

    /** Trimmed stdout of a git command, or null when it fails. */
    static String output(File dir, long timeoutSeconds, String... args)
            throws IOException, InterruptedException {
        Process process =
                new ProcessBuilder(gitCommand(args))
                        .directory(dir)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
        // Outputs read here (a SHA or a count) are tiny, so reading before waiting cannot block.
        String stdout = new String(process.getInputStream().readAllBytes(), UTF_8);
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("git " + args[0] + " timed out");
        }
        return process.exitValue() == 0 ? stdout.strip() : null;
    }

    private static List<String> gitCommand(String... args) {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        return command;
    }
}
