package com.jinloes.prpilot.services

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.io.IOException

class ClaudeServiceKotestTest : FunSpec({

    context("parseReview") {

        test("plain JSON — parsed correctly") {
            val json = """{"summary":"s","verdict":"APPROVE","lineComments":[]}"""
            val result = ClaudeService.parseReview(json)
            result.getVerdict() shouldBe "APPROVE"
            result.getSummary() shouldBe "s"
        }

        test("JSON wrapped in markdown fence — fence stripped") {
            val json = "```json\n{\"summary\":\"s\",\"verdict\":\"COMMENT\",\"lineComments\":[]}\n```"
            val result = ClaudeService.parseReview(json)
            result.getVerdict() shouldBe "COMMENT"
        }

        test("JSON embedded in surrounding prose — braces extracted") {
            val json = "Here is the review: {\"summary\":\"s\",\"verdict\":\"REQUEST_CHANGES\",\"lineComments\":[]} done."
            val result = ClaudeService.parseReview(json)
            result.getVerdict() shouldBe "REQUEST_CHANGES"
        }

        test("invalid JSON — throws Exception") {
            shouldThrow<Exception> {
                ClaudeService.parseReview("not json at all")
            }
        }

        test("JSON with line comment — round-trips correctly") {
            val json = """{"summary":"overview","verdict":"REQUEST_CHANGES","lineComments":[{"file":"src/Foo.java","line":10,"type":"issue","body":"null check"}]}"""
            val result = ClaudeService.parseReview(json)
            result.getLineComments() shouldHaveSize 1
            result.getLineComments()[0].getFile() shouldBe "src/Foo.java"
        }
    }

    context("findErrorInfo") {

        test("file does not exist — returns nulls") {
            val svc = ClaudeService()
            val info = svc.findErrorInfo(java.io.File("/nonexistent/path/file.ndjson"))
            info.subtype shouldBe null
            info.sessionId shouldBe null
        }

        test("empty file — returns nulls") {
            val file = java.nio.file.Files.createTempFile("test", ".ndjson").toFile()
            try {
                val svc = ClaudeService()
                val info = svc.findErrorInfo(file)
                info.subtype shouldBe null
                info.sessionId shouldBe null
            } finally {
                file.delete()
            }
        }

        test("file with error_max_turns event and session_id — returns both") {
            val file = java.nio.file.Files.createTempFile("test", ".ndjson").toFile()
            try {
                file.writeText("""{"type":"result","subtype":"error_max_turns","is_error":true,"session_id":"sess-abc"}""" + "\n")
                val svc = ClaudeService()
                val info = svc.findErrorInfo(file)
                info.subtype shouldBe "error_max_turns"
                info.sessionId shouldBe "sess-abc"
            } finally {
                file.delete()
            }
        }

        test("file with non-error result — returns nulls") {
            val file = java.nio.file.Files.createTempFile("test", ".ndjson").toFile()
            try {
                file.writeText("""{"type":"result","subtype":"success","is_error":false}""" + "\n")
                val svc = ClaudeService()
                val info = svc.findErrorInfo(file)
                info.subtype shouldBe null
                info.sessionId shouldBe null
            } finally {
                file.delete()
            }
        }

        test("file with corrupt line followed by valid error event — skips corrupt, finds error") {
            val file = java.nio.file.Files.createTempFile("test", ".ndjson").toFile()
            try {
                file.writeText(
                    "NOT_JSON\n" +
                    """{"type":"result","subtype":"error_max_turns","is_error":true,"session_id":"s1"}""" + "\n"
                )
                val svc = ClaudeService()
                val info = svc.findErrorInfo(file)
                info.subtype shouldBe "error_max_turns"
            } finally {
                file.delete()
            }
        }
    }

    context("parseStdoutFileToResult") {

        test("result event with JSON — parsed into ReviewResult") {
            val file = java.nio.file.Files.createTempFile("test", ".ndjson").toFile()
            try {
                val reviewJson = """{"summary":"overview","verdict":"APPROVE","lineComments":[]}"""
                val escapedJson = reviewJson.replace("\"", "\\\"")
                file.writeText(
                    """{"type":"result","subtype":"success","is_error":false,"result":"$escapedJson"}""" + "\n"
                )
                val svc = ClaudeService()
                val statuses = mutableListOf<String>()
                val result = svc.parseStdoutFileToResult(file, "", { statuses.add(it) }, null)
                result.getVerdict() shouldBe "APPROVE"
            } finally {
                file.delete()
            }
        }

        test("no result event — throws IOException with diagnostic detail") {
            val file = java.nio.file.Files.createTempFile("test", ".ndjson").toFile()
            try {
                file.writeText("""{"type":"assistant","message":null}""" + "\n")
                val svc = ClaudeService()
                shouldThrow<IOException> {
                    svc.parseStdoutFileToResult(file, "", {}, null)
                }
            } finally {
                file.delete()
            }
        }

        test("text block fallback — text accumulated in textBuffer used when resultBuffer empty") {
            val file = java.nio.file.Files.createTempFile("test", ".ndjson").toFile()
            try {
                val reviewJson = """{"summary":"fallback","verdict":"COMMENT","lineComments":[]}"""
                val escaped = reviewJson.replace("\"", "\\\"")
                file.writeText(
                    """{"type":"assistant","message":{"content":[{"type":"text","text":"$escaped"}]}}""" + "\n"
                )
                val svc = ClaudeService()
                val result = svc.parseStdoutFileToResult(file, "", {}, null)
                result.getVerdict() shouldBe "COMMENT"
            } finally {
                file.delete()
            }
        }
    }
})
