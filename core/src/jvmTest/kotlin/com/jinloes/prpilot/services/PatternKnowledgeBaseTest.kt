package com.jinloes.prpilot.services

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.string.shouldBeEmpty
import io.kotest.matchers.string.shouldNotBeEmpty
import io.kotest.matchers.string.shouldMatch
import java.io.File
import java.nio.file.Files

class PatternKnowledgeBaseTest : FunSpec({

    lateinit var tempDir: File
    beforeTest { tempDir = Files.createTempDirectory("pkb-test-").toFile() }
    afterTest { tempDir.deleteRecursively() }

    // ── load ─────────────────────────────────────────────────────────────

    context("load") {

        test("returns empty string when no file exists") {
            val kb = PatternKnowledgeBase(tempDir)
            kb.load("owner", "repo").shouldBeEmpty()
        }

        test("returns contents after append") {
            val kb = PatternKnowledgeBase(tempDir)
            kb.append("owner", "repo", "the comment", "the finding")
            kb.load("owner", "repo").shouldNotBeEmpty()
        }

        test("separates repos by file") {
            val kb = PatternKnowledgeBase(tempDir)
            kb.append("owner", "repo-a", "comment a", "finding a")
            kb.append("owner", "repo-b", "comment b", "finding b")

            kb.load("owner", "repo-a") shouldContain "finding a"
            kb.load("owner", "repo-a") shouldNotContain "finding b"
            kb.load("owner", "repo-b") shouldContain "finding b"
        }
    }

    // ── fileFor (via append) ─────────────────────────────────────────────

    context("fileFor") {

        test("path traversal in owner throws SecurityException") {
            val kb = PatternKnowledgeBase(tempDir)
            shouldThrow<SecurityException> {
                kb.append("../evil", "repo", "ctx", "response")
            }
        }

        test("normal owner/repo does not throw") {
            val kb = PatternKnowledgeBase(tempDir)
            kb.append("owner", "repo", "ctx", "response") // should not throw
        }
    }

    // ── append ───────────────────────────────────────────────────────────

    context("append") {

        test("creates file and parent directories") {
            val nested = File(tempDir, "sub/patterns")
            val kb = PatternKnowledgeBase(nested)
            kb.append("owner", "repo", "comment", "finding")
            kb.load("owner", "repo").shouldNotBeEmpty()
        }

        test("accumulates multiple entries in the same file") {
            val kb = PatternKnowledgeBase(tempDir)
            kb.append("owner", "repo", "comment one", "finding one")
            kb.append("owner", "repo", "comment two", "finding two")

            val contents = kb.load("owner", "repo")
            contents shouldContain "finding one"
            contents shouldContain "finding two"
        }
    }

    // ── formatEntry ──────────────────────────────────────────────────────

    context("formatEntry") {

        test("contains quoted context") {
            val entry = PatternKnowledgeBase.formatEntry("the comment", "the finding")
            entry shouldContain "> the comment"
        }

        test("contains response") {
            val entry = PatternKnowledgeBase.formatEntry("q", "the finding")
            entry shouldContain "the finding"
        }

        test("multi-line context is quoted per line") {
            val entry = PatternKnowledgeBase.formatEntry("line one\nline two", "finding")
            entry shouldContain "> line one\n> line two"
        }

        test("contains ISO date pattern") {
            val entry = PatternKnowledgeBase.formatEntry("q", "r")
            entry shouldMatch Regex("(?s).*\\d{4}-\\d{2}-\\d{2}.*")
        }

        test("starts with separator") {
            val entry = PatternKnowledgeBase.formatEntry("q", "r")
            entry shouldStartWith "\n---\n"
        }
    }
})
