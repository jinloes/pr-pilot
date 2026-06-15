package com.jinloes.prpilot.services

import com.jinloes.prpilot.model.LineComment
import com.jinloes.prpilot.model.ReviewResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldNotStartWith
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// ── helpers ─────────────────────────────────────────────────────────────

private fun review(summary: String, verdict: String, comments: List<LineComment> = emptyList()) =
    ReviewResult(summary, verdict, comments)

private fun fieldText(
    arr: kotlinx.serialization.json.JsonArray,
    index: Int,
    key: String,
): String {
    val obj = arr[index] as JsonObject
    val prim = obj[key] as JsonPrimitive
    return prim.content
}

// ─────────────────────────────────────────────────────────────────────────

class GitHubServiceTest : FunSpec({

    // ── encodeBody ───────────────────────────────────────────────────────

    context("encodeBody") {

        test("summary stored in hidden tag — not visible at start") {
            val body = GitHubService.encodeBody(review("My summary", "APPROVE"))
            body shouldContain "<!-- claude-summary: My summary -->"
            body shouldNotStartWith "My summary"
        }

        test("contains verdict tag") {
            val body = GitHubService.encodeBody(review("s", "REQUEST_CHANGES"))
            body shouldContain "<!-- claude-verdict: REQUEST_CHANGES -->"
        }

        test("contains comments JSON block") {
            val c = LineComment("src/Foo.java", 42, "issue", "null check missing")
            val body = GitHubService.encodeBody(review("s", "COMMENT", listOf(c)))
            body shouldContain "<!-- claude-comments:"
            body shouldContain "src/Foo.java"
            body shouldContain "42"
            body shouldContain "issue"
            body shouldContain "null check missing"
        }

        test("general comment — no [TYPE] prefix in visible notes") {
            val general = LineComment("", 0, "issue", "look at this")
            val body = GitHubService.encodeBody(review("s", "COMMENT", listOf(general)))
            body shouldNotContain "[ISSUE]"
            body shouldContain "look at this"
        }

        test("general comment included in embedded JSON") {
            val general = LineComment("", 0, "note", "overall looks good")
            val body = GitHubService.encodeBody(review("s", "APPROVE", listOf(general)))
            body shouldContain "overall looks good"
        }

        test("summary containing --> is escaped so HTML comment is well-formed") {
            val r = review("See: <!-- end --> tag", "APPROVE")
            val body = GitHubService.encodeBody(r)
            body shouldNotContain "<!-- claude-summary: See: <!-- end --> tag -->"
            // round-trip still recovers a readable summary
            val decoded = GitHubService.decodeReview(body, emptyList())
            decoded.getSummary() shouldContain "See:"
        }

        test("comment body containing --> is escaped in embedded JSON") {
            val c = LineComment("src/Foo.java", 5, "issue", "closes --> stream")
            val body = GitHubService.encodeBody(review("s", "COMMENT", listOf(c)))
            val commentsStart = body.indexOf("<!-- claude-comments:")
            val commentsEnd = body.indexOf(" -->", commentsStart + "<!-- claude-comments:".length)
            val jsonBlob = body.substring(commentsStart, commentsEnd)
            jsonBlob shouldNotContain "-->"
        }

        test("blank-body comment preserved in embedded JSON (round-trips correctly)") {
            val blank = LineComment("src/Foo.java", 7, "note", "")
            val body = GitHubService.encodeBody(review("s", "COMMENT", listOf(blank)))
            val decoded = GitHubService.decodeReview(body, emptyList())
            decoded.getLineComments() shouldHaveSize 1
            decoded.getLineComments()[0].getFile() shouldBe "src/Foo.java"
            decoded.getLineComments()[0].getLine() shouldBe 7
            decoded.getLineComments()[0].getBody() shouldBe ""
        }
    }

    // ── decodeReview ─────────────────────────────────────────────────────

    context("decodeReview") {

        test("parses summary from embedded tag") {
            val body = GitHubService.encodeBody(review("The summary text", "COMMENT"))
            val decoded = GitHubService.decodeReview(body, emptyList())
            decoded.getSummary() shouldBe "The summary text"
        }

        test("parses verdict from embedded tag") {
            val body = GitHubService.encodeBody(review("s", "REQUEST_CHANGES"))
            val decoded = GitHubService.decodeReview(body, emptyList())
            decoded.getVerdict() shouldBe "REQUEST_CHANGES"
        }

        test("parses inline comment from embedded JSON") {
            val c = LineComment("src/Bar.java", 10, "suggestion", "extract method")
            val body = GitHubService.encodeBody(review("s", "COMMENT", listOf(c)))
            val decoded = GitHubService.decodeReview(body, emptyList())
            decoded.getLineComments() shouldHaveSize 1
            val got = decoded.getLineComments()[0]
            got.getFile() shouldBe "src/Bar.java"
            got.getLine() shouldBe 10
            got.getType() shouldBe "suggestion"
            got.getBody() shouldBe "extract method"
        }

        test("round-trips severity, category, confidence, and rationale") {
            val c = LineComment("src/Bar.java", 10, "issue", "fix this").apply {
                setSeverity("major")
                setCategory("security")
                setConfidence("high")
                setRationale("traced the call site")
            }
            val body = GitHubService.encodeBody(review("s", "REQUEST_CHANGES", listOf(c)))
            val decoded = GitHubService.decodeReview(body, emptyList())
            val got = decoded.getLineComments()[0]
            got.getSeverity() shouldBe "major"
            got.getCategory() shouldBe "security"
            got.getConfidence() shouldBe "high"
            got.getRationale() shouldBe "traced the call site"
        }

        test("omits rich fields from JSON when blank and decodes them as empty") {
            val c = LineComment("src/Bar.java", 10, "note", "minor")
            val body = GitHubService.encodeBody(review("s", "COMMENT", listOf(c)))
            body shouldNotContain "\"s\":"
            body shouldNotContain "\"cf\":"
            val got = GitHubService.decodeReview(body, emptyList()).getLineComments()[0]
            got.getSeverity() shouldBe ""
            got.getConfidence() shouldBe ""
            got.getRationale() shouldBe ""
        }

        test("embedded JSON takes precedence over API comments") {
            val embedded = LineComment("src/A.java", 5, "issue", "from embedded")
            val body = GitHubService.encodeBody(review("s", "COMMENT", listOf(embedded)))
            val apiComments = listOf(
                GitHubService.GhReviewComment("src/B.java", 99, null, "[NOTE] from api")
            )
            val decoded = GitHubService.decodeReview(body, apiComments)
            decoded.getLineComments() shouldHaveSize 1
            decoded.getLineComments()[0].getFile() shouldBe "src/A.java"
        }

        test("falls back to API comments when no embedded JSON") {
            val body = "Summary text\n\n<!-- claude-verdict: APPROVE -->"
            val apiComments = listOf(
                GitHubService.GhReviewComment("src/Foo.java", 7, null, "[ISSUE] legacy comment")
            )
            val decoded = GitHubService.decodeReview(body, apiComments)
            decoded.getLineComments() shouldHaveSize 1
            decoded.getLineComments()[0].getFile() shouldBe "src/Foo.java"
            decoded.getLineComments()[0].getLine() shouldBe 7
            decoded.getLineComments()[0].getType() shouldBe "issue"
        }

        test("null line in API comment uses originalLine") {
            val body = "Summary\n\n<!-- claude-verdict: COMMENT -->"
            val apiComments = listOf(
                GitHubService.GhReviewComment("src/Foo.java", null, 15, "[NOTE] note text")
            )
            val decoded = GitHubService.decodeReview(body, apiComments)
            decoded.getLineComments()[0].getLine() shouldBe 15
        }

        test("body without any tags defaults verdict to COMMENT and summary to empty") {
            val decoded = GitHubService.decodeReview("just some text", emptyList())
            decoded.getVerdict() shouldBe "COMMENT"
        }

        test("legacy body-only format — summary extracted from text before verdict tag") {
            val body = "The old summary\n\n<!-- claude-verdict: APPROVE -->"
            val decoded = GitHubService.decodeReview(body, emptyList())
            decoded.getSummary() shouldContain "The old summary"
            decoded.getVerdict() shouldBe "APPROVE"
        }

        test("type prefix stripped from API comments") {
            val body = "<!-- claude-verdict: COMMENT -->"
            val apiComments = listOf(
                GitHubService.GhReviewComment("src/Foo.java", 7, null, "[SUGGESTION] refactor this")
            )
            val decoded = GitHubService.decodeReview(body, apiComments)
            decoded.getLineComments()[0].getType() shouldBe "suggestion"
            decoded.getLineComments()[0].getBody() shouldBe "refactor this"
        }

        test("corrupt embedded JSON — falls back gracefully") {
            val body = "<!-- claude-summary: s --> \n<!-- claude-verdict: COMMENT --> \n<!-- claude-comments: NOTJSON -->"
            // should not throw; comments will be empty
            val decoded = GitHubService.decodeReview(body, emptyList())
            decoded.getVerdict() shouldBe "COMMENT"
        }

        test("hasUsableEmbeddedComments detects valid embedded comment metadata") {
            val body = "<!-- claude-summary: s --> \n<!-- claude-verdict: COMMENT --> \n<!-- claude-comments: [] -->"
            GitHubService.hasUsableEmbeddedComments(body).shouldBeTrue()
        }

        test("hasUsableEmbeddedComments rejects missing or corrupt metadata") {
            GitHubService.hasUsableEmbeddedComments("plain GitHub review body").shouldBeFalse()
            GitHubService.hasUsableEmbeddedComments("<!-- claude-comments: NOTJSON -->").shouldBeFalse()
        }
    }

    // ── buildCommentArray ────────────────────────────────────────────────

    context("buildCommentArray") {

        test("valid comment is included") {
            val r = review("s", "APPROVE", listOf(LineComment("src/Foo.java", 5, "issue", "body")))
            GitHubService.buildCommentArray(r).size shouldBe 1
        }

        test("blank file is skipped") {
            val r = review("s", "APPROVE", listOf(LineComment("", 5, "issue", "body")))
            GitHubService.buildCommentArray(r).size shouldBe 0
        }

        test("zero line is skipped") {
            val r = review("s", "APPROVE", listOf(LineComment("src/Foo.java", 0, "issue", "body")))
            GitHubService.buildCommentArray(r).size shouldBe 0
        }

        test("negative line is skipped") {
            val r = review("s", "APPROVE", listOf(LineComment("src/Foo.java", -1, "issue", "body")))
            GitHubService.buildCommentArray(r).size shouldBe 0
        }

        test("blank body is skipped") {
            val r = review("s", "APPROVE", listOf(LineComment("src/Foo.java", 5, "issue", "")))
            GitHubService.buildCommentArray(r).size shouldBe 0
        }

        test("a/ prefix stripped from path") {
            val r = review("s", "APPROVE", listOf(LineComment("a/src/Foo.java", 5, "issue", "body")))
            fieldText(GitHubService.buildCommentArray(r), 0, "path") shouldBe "src/Foo.java"
        }

        test("b/ prefix stripped from path") {
            val r = review("s", "APPROVE", listOf(LineComment("b/src/Foo.java", 5, "issue", "body")))
            fieldText(GitHubService.buildCommentArray(r), 0, "path") shouldBe "src/Foo.java"
        }

        test("exact duplicate deduplicated") {
            val comments = listOf(
                LineComment("src/Foo.java", 5, "issue", "body"),
                LineComment("src/Foo.java", 5, "issue", "body"),
            )
            GitHubService.buildCommentArray(review("s", "APPROVE", comments)).size shouldBe 1
        }

        test("distinct comments on same line both included") {
            val comments = listOf(
                LineComment("src/Foo.java", 5, "issue", "first"),
                LineComment("src/Foo.java", 5, "note", "second"),
            )
            GitHubService.buildCommentArray(review("s", "APPROVE", comments)).size shouldBe 2
        }

        test("distinct comments are not falsely deduped by field-boundary collision") {
            // Under a space-joined dedup key both collapse to "f 1 2 x"; the NUL delimiter
            // keeps them distinct so both inline comments survive.
            val comments = listOf(
                LineComment("f", 1, "issue", "2 x"),
                LineComment("f 1", 2, "issue", "x"),
            )
            GitHubService.buildCommentArray(review("s", "APPROVE", comments)).size shouldBe 2
        }

        test("comment node has side = RIGHT") {
            val r = review("s", "APPROVE", listOf(LineComment("src/Foo.java", 5, "issue", "body")))
            fieldText(GitHubService.buildCommentArray(r), 0, "side") shouldBe "RIGHT"
        }
    }

    // ── effectiveBody ────────────────────────────────────────────────────

    context("effectiveBody") {

        test("APPROVE with blank body defaults to 'Looks good to me!'") {
            GitHubService.effectiveBody("APPROVE", "") shouldBe "Looks good to me!"
        }

        test("APPROVE with whitespace body defaults to 'Looks good to me!'") {
            GitHubService.effectiveBody("APPROVE", "   ") shouldBe "Looks good to me!"
        }

        test("APPROVE with non-blank body preserves body") {
            GitHubService.effectiveBody("APPROVE", "Custom message") shouldBe "Custom message"
        }

        test("REQUEST_CHANGES with blank body defaults to 'Requesting changes.'") {
            GitHubService.effectiveBody("REQUEST_CHANGES", "") shouldBe "Requesting changes."
        }

        test("REQUEST_CHANGES with whitespace body defaults to 'Requesting changes.'") {
            GitHubService.effectiveBody("REQUEST_CHANGES", "   ") shouldBe "Requesting changes."
        }

        test("REQUEST_CHANGES with non-blank body preserves body") {
            GitHubService.effectiveBody("REQUEST_CHANGES", "Need to fix X") shouldBe "Need to fix X"
        }

        test("COMMENT with blank body defaults to 'Leaving comments.'") {
            GitHubService.effectiveBody("COMMENT", "") shouldBe "Leaving comments."
        }

        test("COMMENT with non-blank body preserves body") {
            GitHubService.effectiveBody("COMMENT", "Some notes") shouldBe "Some notes"
        }

        test("unknown event with blank body preserves empty body") {
            GitHubService.effectiveBody("UNKNOWN", "") shouldBe ""
        }
    }

    // ── round-trip ───────────────────────────────────────────────────────

    context("round-trip") {

        test("multiple comments all preserved") {
            val comments = listOf(
                LineComment("src/Foo.java", 10, "issue", "null check"),
                LineComment("src/Bar.java", 20, "suggestion", "extract method"),
                LineComment("", 0, "note", "general note"),
            )
            val original = review("Full summary", "REQUEST_CHANGES", comments)
            val body = GitHubService.encodeBody(original)
            val decoded = GitHubService.decodeReview(body, emptyList())

            decoded.getSummary() shouldBe "Full summary"
            decoded.getVerdict() shouldBe "REQUEST_CHANGES"
            decoded.getLineComments() shouldHaveSize 3

            val all = decoded.getLineComments()
            all.any { it.getFile() == "src/Foo.java" && it.getLine() == 10 && it.getType() == "issue" && it.getBody() == "null check" }.shouldBeTrue()
            all.any { it.getFile() == "src/Bar.java" && it.getLine() == 20 }.shouldBeTrue()
            all.any { it.getFile() == "" && it.getLine() == 0 && it.getBody() == "general note" }.shouldBeTrue()
        }

        test("empty comments preserved as empty") {
            val original = review("Summary only", "APPROVE")
            val body = GitHubService.encodeBody(original)
            val decoded = GitHubService.decodeReview(body, emptyList())
            decoded.getLineComments().shouldBeEmpty()
            decoded.getSummary() shouldBe "Summary only"
            decoded.getVerdict() shouldBe "APPROVE"
        }
    }
})
