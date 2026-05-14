package com.jinloes.prpilot.services

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class PendingReviewIndexTest : FunSpec({

    lateinit var tempDir: File
    beforeTest { tempDir = Files.createTempDirectory("pri-test-").toFile() }
    afterTest { tempDir.deleteRecursively() }

    fun index(): PendingReviewIndex =
        PendingReviewIndex(Path.of(tempDir.absolutePath, "pending-prs.json"))

    // ── list ─────────────────────────────────────────────────────────────

    context("list") {

        test("returns empty list when file does not exist") {
            index().list().shouldBeEmpty()
        }
    }

    // ── add ──────────────────────────────────────────────────────────────

    context("add") {

        test("creates entry with correct fields") {
            val idx = index()
            idx.add("owner", "repo", 1, "My PR", "")
            val entries = idx.list()
            entries shouldHaveSize 1
            entries[0].owner() shouldBe "owner"
            entries[0].repo() shouldBe "repo"
            entries[0].number() shouldBe 1
            entries[0].title() shouldBe "My PR"
        }

        test("deduplicates same owner/repo/number — keeps new title") {
            val idx = index()
            idx.add("owner", "repo", 1, "Old title", "")
            idx.add("owner", "repo", 1, "New title", "")
            val entries = idx.list()
            entries shouldHaveSize 1
            entries[0].title() shouldBe "New title"
        }

        test("different PRs are both kept") {
            val idx = index()
            idx.add("owner", "repo", 1, "PR one", "")
            idx.add("owner", "repo", 2, "PR two", "")
            idx.list() shouldHaveSize 2
        }

        test("newest entry is first") {
            val idx = index()
            idx.add("owner", "repo", 1, "first", "")
            idx.add("owner", "repo", 2, "second", "")
            idx.list()[0].number() shouldBe 2
        }

        test("stores and returns headSha") {
            val idx = index()
            idx.add("owner", "repo", 1, "My PR", "abc123")
            idx.list()[0].headSha() shouldBe "abc123"
        }

        test("null headSha returned as empty string") {
            val entry = PendingReviewIndex.Entry("o", "r", 1, "t", "2024-01-01", null)
            entry.headSha().shouldBeEmpty()
        }
    }

    // ── hasDraft ─────────────────────────────────────────────────────────

    context("hasDraft") {

        test("returns false when empty") {
            index().hasDraft("owner", "repo", 1).shouldBeFalse()
        }

        test("returns true when matching entry exists") {
            val idx = index()
            idx.add("owner", "repo", 42, "My PR", "")
            idx.hasDraft("owner", "repo", 42).shouldBeTrue()
        }

        test("returns false when PR number does not match") {
            val idx = index()
            idx.add("owner", "repo", 42, "My PR", "")
            idx.hasDraft("owner", "repo", 99).shouldBeFalse()
        }

        test("returns false when owner differs") {
            val idx = index()
            idx.add("owner", "repo", 1, "PR", "")
            idx.hasDraft("other", "repo", 1).shouldBeFalse()
        }

        test("returns false when repo differs") {
            val idx = index()
            idx.add("owner", "repo", 1, "PR", "")
            idx.hasDraft("owner", "other", 1).shouldBeFalse()
        }
    }

    // ── remove ───────────────────────────────────────────────────────────

    context("remove") {

        test("removes matching entry") {
            val idx = index()
            idx.add("owner", "repo", 1, "PR one", "")
            idx.add("owner", "repo", 2, "PR two", "")
            idx.remove("owner", "repo", 1)
            val entries = idx.list()
            entries shouldHaveSize 1
            entries[0].number() shouldBe 2
        }

        test("non-existent entry is a no-op") {
            val idx = index()
            idx.add("owner", "repo", 1, "PR", "")
            idx.remove("owner", "repo", 99)
            idx.list() shouldHaveSize 1
        }
    }

    // ── persistence ──────────────────────────────────────────────────────

    context("persistence") {

        test("entries persist across instances") {
            val file = Path.of(tempDir.absolutePath, "pending-prs.json")
            val first = PendingReviewIndex(file)
            first.add("owner", "repo", 7, "Saved PR", "")

            val second = PendingReviewIndex(file)
            second.list() shouldHaveSize 1
            second.list()[0].number() shouldBe 7
        }
    }

    // ── displayLabel ─────────────────────────────────────────────────────

    context("displayLabel") {

        test("contains owner/repo and number") {
            val idx = index()
            idx.add("myorg", "myrepo", 42, "Fix bug", "")
            val label = idx.list()[0].displayLabel()
            label shouldContain "myorg/myrepo #42"
            label shouldContain "Fix bug"
        }

        test("savedAt is truncated to 16 chars when long") {
            val entry = PendingReviewIndex.Entry(
                "o", "r", 1, "title", "2024-01-15T10:30:00", "sha"
            )
            val label = entry.displayLabel()
            label shouldContain "2024-01-15 10:30"
        }

        test("short savedAt is not truncated beyond its length") {
            val entry = PendingReviewIndex.Entry("o", "r", 1, "title", "2024-01", "sha")
            val label = entry.displayLabel()
            label shouldContain "2024-01"
        }
    }
})
