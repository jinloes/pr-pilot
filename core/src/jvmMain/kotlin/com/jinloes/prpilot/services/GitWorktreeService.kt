package com.jinloes.prpilot.services

import org.apache.commons.io.IOUtils
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Manages temporary git worktrees for PR branch reviews.
 *
 * A worktree lets Claude read source files at the PR branch state rather than the user's
 * currently checked-out branch, improving review accuracy for type lookups and cross-file
 * references. The shared git object store means no re-clone is needed — only the working tree
 * files are written for the new worktree.
 */
class GitWorktreeService {

    /**
     * Walks up from [startDir] to find the git repository root — the closest ancestor
     * (inclusive) that contains a `.git` entry. Returns null if no git root is found.
     */
    fun findGitRoot(startDir: File): File? {
        var dir: File? = startDir.canonicalFile
        while (dir != null) {
            if (File(dir, ".git").exists()) return dir
            dir = dir.parentFile
        }
        return null
    }

    /**
     * Creates a git worktree at [worktreeDir] checked out to `origin/<branch>`.
     *
     * Runs `git fetch origin <branch>` first so the ref is current, then
     * `git worktree add --detach <worktreeDir> origin/<branch>`.
     *
     * @param repoDir     git repository root (must contain `.git`)
     * @param branch      branch name on the origin remote (without the `origin/` prefix)
     * @param worktreeDir destination path for the worktree; must not exist
     * @throws IOException if a git command fails or times out
     */
    @Throws(IOException::class)
    fun createWorktree(repoDir: File, branch: String, worktreeDir: File) {
        log.info("Fetching branch {} from origin in {}", branch, repoDir)
        runGit(repoDir, 60, "fetch", "origin", branch)
        log.info("Creating worktree at {} for origin/{}", worktreeDir, branch)
        runGit(repoDir, 30, "worktree", "add", "--detach", worktreeDir.absolutePath, "origin/$branch")
    }

    /**
     * Creates a git worktree at [worktreeDir] by fetching a branch from a fork's remote URL.
     * Use this for fork PRs where the branch is not available on `origin`.
     *
     * Runs `git fetch <forkCloneUrl> <branch>` then
     * `git worktree add --detach <worktreeDir> FETCH_HEAD`.
     *
     * @param repoDir       git repository root
     * @param forkCloneUrl  HTTPS or SSH clone URL of the fork
     * @param branch        branch name on the fork
     * @param worktreeDir   destination path for the worktree; must not exist
     * @throws IOException if a git command fails or times out
     */
    @Throws(IOException::class)
    fun createWorktreeFromFork(
        repoDir: File,
        forkCloneUrl: String,
        branch: String,
        worktreeDir: File,
    ) {
        log.info("Fetching branch {} from fork {} in {}", branch, forkCloneUrl, repoDir)
        runGit(repoDir, 120, "fetch", forkCloneUrl, branch)
        log.info("Creating worktree at {} from FETCH_HEAD", worktreeDir)
        runGit(repoDir, 30, "worktree", "add", "--detach", worktreeDir.absolutePath, "FETCH_HEAD")
    }

    /**
     * Removes a previously created worktree. Uses `--force` to tolerate a missing directory.
     * Logs a warning on failure but does not rethrow — cleanup failures are non-fatal.
     *
     * @param repoDir     git repository root
     * @param worktreeDir the worktree directory to remove
     */
    fun removeWorktree(repoDir: File, worktreeDir: File) {
        try {
            runGit(repoDir, 30, "worktree", "remove", "--force", worktreeDir.absolutePath)
            log.info("Removed worktree at {}", worktreeDir)
        } catch (e: Exception) {
            log.warn("Failed to remove worktree at {}: {}", worktreeDir, e.message)
        }
    }

    @Throws(IOException::class)
    internal fun runGit(dir: File, timeoutSeconds: Long, vararg args: String) {
        val cmd = listOf("git") + args.toList()
        val pb = ProcessBuilder(cmd).directory(dir).redirectErrorStream(true)
        val env = pb.environment()
        env["HOME"] = System.getProperty("user.home", "/")
        val existingPath = env["PATH"] ?: ""
        env["PATH"] = "/opt/homebrew/bin:/usr/local/bin:$existingPath"
        val process = pb.start()
        val output = IOUtils.toString(process.inputStream, StandardCharsets.UTF_8)
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw IOException("git ${args[0]} timed out after ${timeoutSeconds}s")
        }
        val exitCode = process.exitValue()
        if (exitCode != 0) {
            throw IOException("git ${args[0]} failed (exit $exitCode): ${output.trim().take(300)}")
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(GitWorktreeService::class.java)
    }
}

