package com.jinloes.prpilot.services

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.io.IOException
import java.nio.file.Files

class GitWorktreeServiceTest : FunSpec({

    val service = GitWorktreeService()

    // ── findGitRoot ──────────────────────────────────────────────────────────

    context("findGitRoot") {

        var tmpDir: File? = null

        beforeTest { tmpDir = Files.createTempDirectory("gw-test-root").toFile() }
        afterTest { tmpDir?.deleteRecursively() }

        test("returns null for directory with no .git ancestor") {
            service.findGitRoot(tmpDir!!).shouldBeNull()
        }

        test("returns the directory itself when .git is a child") {
            val gitDir = File(tmpDir!!, ".git")
            gitDir.mkdirs()
            service.findGitRoot(tmpDir!!).shouldNotBeNull() shouldBe tmpDir!!.canonicalFile
        }

        test("finds .git in a parent directory") {
            val gitDir = File(tmpDir!!, ".git")
            gitDir.mkdirs()
            val nested = File(tmpDir!!, "a/b/c")
            nested.mkdirs()
            service.findGitRoot(nested).shouldNotBeNull() shouldBe tmpDir!!.canonicalFile
        }

        test("accepts .git as a file (git worktree metadata)") {
            // git worktree sub-directories have a .git file, not a directory
            File(tmpDir!!, ".git").writeText("gitdir: ../../.git/worktrees/foo")
            service.findGitRoot(tmpDir!!).shouldNotBeNull() shouldBe tmpDir!!.canonicalFile
        }
    }

    // ── createWorktree / removeWorktree (real git) ───────────────────────────

    context("createWorktree") {

        var repoDir: File? = null
        var worktreeDir: File? = null

        beforeTest {
            repoDir = Files.createTempDirectory("gw-repo").toFile()
            worktreeDir = Files.createTempDirectory("gw-wt").toFile().also { it.delete() }
            initBareRepo(repoDir!!)
        }
        afterTest {
            worktreeDir?.deleteRecursively()
            repoDir?.deleteRecursively()
        }

        test("creates a worktree on an existing branch") {
            service.createWorktree(repoDir!!, "main", worktreeDir!!)
            worktreeDir!!.exists() shouldBe true
            File(worktreeDir!!, "hello.txt").exists() shouldBe true
        }

        test("throws IOException for a non-existent branch") {
            val ex = shouldThrow<IOException> {
                service.createWorktree(repoDir!!, "does-not-exist", worktreeDir!!)
            }
            ex.message shouldContain "git fetch"
        }
    }

    context("removeWorktree") {

        var repoDir: File? = null
        var worktreeDir: File? = null

        beforeTest {
            repoDir = Files.createTempDirectory("gw-repo-rm").toFile()
            worktreeDir = Files.createTempDirectory("gw-wt-rm").toFile().also { it.delete() }
            initBareRepo(repoDir!!)
        }
        afterTest {
            worktreeDir?.deleteRecursively()
            repoDir?.deleteRecursively()
        }

        test("removes a worktree that was created") {
            service.createWorktree(repoDir!!, "main", worktreeDir!!)
            worktreeDir!!.exists() shouldBe true
            service.removeWorktree(repoDir!!, worktreeDir!!)
            worktreeDir!!.exists() shouldBe false
        }

        test("does not throw when worktree directory does not exist") {
            val nonExistent = File(repoDir!!, "no-such-wt")
            // Should complete without exception (--force handles missing dir)
            service.removeWorktree(repoDir!!, nonExistent)
        }
    }

    // ── runGit (error handling) ──────────────────────────────────────────────

    context("runGit") {

        var tmpDir: File? = null
        beforeTest { tmpDir = Files.createTempDirectory("gw-run").toFile() }
        afterTest { tmpDir?.deleteRecursively() }

        test("throws IOException with exit code on non-zero git command") {
            val ex = shouldThrow<IOException> {
                service.runGit(tmpDir!!, 10, "rev-parse", "--verify", "nonexistent-ref-xyz")
            }
            ex.message shouldContain "git rev-parse"
        }
    }
})

// ── helpers ──────────────────────────────────────────────────────────────────

/**
 * Initialises a minimal git repository with one commit on `main` so tests can call
 * `createWorktree(..., "main", ...)` without depending on a remote.
 */
private fun initBareRepo(dir: File) {
    fun git(vararg args: String) {
        val pb = ProcessBuilder(listOf("git") + args.toList())
            .directory(dir)
            .redirectErrorStream(true)
        val process = pb.start()
        process.inputStream.readAllBytes() // drain
        val exit = process.waitFor()
        check(exit == 0) { "git ${args[0]} failed in $dir" }
    }

    git("init", "-b", "main")
    git("config", "user.email", "test@test.com")
    git("config", "user.name", "Test")
    File(dir, "hello.txt").writeText("hello")
    git("add", ".")
    git("commit", "-m", "initial")

    // Point origin at the repo itself so `git fetch origin main` works locally.
    git("remote", "add", "origin", dir.absolutePath)
}

