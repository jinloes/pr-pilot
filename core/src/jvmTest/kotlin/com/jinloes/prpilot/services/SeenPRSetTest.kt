package com.jinloes.prpilot.services

import com.jinloes.prpilot.model.PullRequest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

private fun pr(owner: String, repo: String, number: Int) =
    PullRequest("title", "http://example.com", owner, repo, number, "", "author", "2024-01-01")

class SeenPRSetTest : FunSpec({

    lateinit var tempDir: File
    beforeTest { tempDir = Files.createTempDirectory("sps-test-").toFile() }
    afterTest { tempDir.deleteRecursively() }

    fun set(): SeenPRSet = SeenPRSet(Path.of(tempDir.absolutePath, "seen-prs.json"))

    // ── initial state ────────────────────────────────────────────────────

    context("initialState") {

        test("new set is not seeded") {
            set().isSeeded().shouldBeFalse()
        }

        test("new set does not contain anything") {
            set().contains(pr("o", "r", 1)).shouldBeFalse()
        }
    }

    // ── add and contains ─────────────────────────────────────────────────

    context("addAndContains") {

        test("add then contains returns true") {
            val s = set()
            s.add(pr("o", "r", 1))
            s.contains(pr("o", "r", 1)).shouldBeTrue()
        }

        test("contains different number returns false") {
            val s = set()
            s.add(pr("o", "r", 1))
            s.contains(pr("o", "r", 2)).shouldBeFalse()
        }

        test("multiple distinct PRs are all contained") {
            val s = set()
            s.add(pr("o", "r", 1))
            s.add(pr("o", "r", 2))
            s.add(pr("other", "repo", 5))
            s.contains(pr("o", "r", 1)).shouldBeTrue()
            s.contains(pr("o", "r", 2)).shouldBeTrue()
            s.contains(pr("other", "repo", 5)).shouldBeTrue()
            s.contains(pr("o", "r", 3)).shouldBeFalse()
        }
    }

    // ── seeded ───────────────────────────────────────────────────────────

    context("seeded") {

        test("markSeeded sets seeded to true") {
            val s = set()
            s.markSeeded()
            s.isSeeded().shouldBeTrue()
        }
    }

    // ── retain ───────────────────────────────────────────────────────────

    context("retain") {

        test("retain with live list removes absent entries") {
            val s = set()
            s.add(pr("o", "r", 1))
            s.add(pr("o", "r", 2))
            s.add(pr("o", "r", 3))

            s.retain(listOf(pr("o", "r", 2)))

            s.contains(pr("o", "r", 1)).shouldBeFalse()
            s.contains(pr("o", "r", 2)).shouldBeTrue()
            s.contains(pr("o", "r", 3)).shouldBeFalse()
        }

        test("retain with empty list removes all") {
            val s = set()
            s.add(pr("o", "r", 1))
            s.retain(emptyList())
            s.contains(pr("o", "r", 1)).shouldBeFalse()
        }

        test("retain with full list keeps all") {
            val s = set()
            s.add(pr("o", "r", 1))
            s.add(pr("o", "r", 2))
            s.retain(listOf(pr("o", "r", 1), pr("o", "r", 2)))
            s.contains(pr("o", "r", 1)).shouldBeTrue()
            s.contains(pr("o", "r", 2)).shouldBeTrue()
        }
    }

    // ── trim ─────────────────────────────────────────────────────────────

    context("trim") {

        test("below max size — no change") {
            val s = set()
            s.add(pr("o", "r", 1))
            s.add(pr("o", "r", 2))
            s.trim(10)
            s.contains(pr("o", "r", 1)).shouldBeTrue()
            s.contains(pr("o", "r", 2)).shouldBeTrue()
        }

        test("drops oldest entries") {
            val s = set()
            for (i in 1..5) s.add(pr("o", "r", i))
            s.trim(3)
            // oldest (1, 2) dropped; newest (3, 4, 5) kept
            s.contains(pr("o", "r", 1)).shouldBeFalse()
            s.contains(pr("o", "r", 2)).shouldBeFalse()
            s.contains(pr("o", "r", 3)).shouldBeTrue()
            s.contains(pr("o", "r", 4)).shouldBeTrue()
            s.contains(pr("o", "r", 5)).shouldBeTrue()
        }

        test("exactly at max size — no change") {
            val s = set()
            s.add(pr("o", "r", 1))
            s.add(pr("o", "r", 2))
            s.trim(2)
            s.contains(pr("o", "r", 1)).shouldBeTrue()
            s.contains(pr("o", "r", 2)).shouldBeTrue()
        }
    }

    // ── persistence ──────────────────────────────────────────────────────

    context("persistence") {

        test("after save and reload — isSeeded is true") {
            val file = Path.of(tempDir.absolutePath, "seen-prs.json")
            val first = SeenPRSet(file)
            first.add(pr("o", "r", 1))
            first.markSeeded()
            first.save()

            val second = SeenPRSet(file)
            second.isSeeded().shouldBeTrue()
        }

        test("after save and reload — contains added PRs") {
            val file = Path.of(tempDir.absolutePath, "seen-prs.json")
            val first = SeenPRSet(file)
            first.add(pr("org", "repo", 42))
            first.markSeeded()
            first.save()

            val second = SeenPRSet(file)
            second.contains(pr("org", "repo", 42)).shouldBeTrue()
            second.contains(pr("org", "repo", 99)).shouldBeFalse()
        }
    }
})
