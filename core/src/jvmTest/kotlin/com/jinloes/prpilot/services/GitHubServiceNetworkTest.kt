package com.jinloes.prpilot.services

import com.jinloes.prpilot.model.ReviewResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

private val MOCK_JSON = Json { ignoreUnknownKeys = true }

private fun mockServiceResponses(vararg bodies: String): GitHubService {
    var idx = 0
    val engine = MockEngine { _ ->
        val body = if (idx < bodies.size) bodies[idx++] else "{}"
        respond(
            content = body,
            status = HttpStatusCode.OK,
            headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
        )
    }
    val client = HttpClient(engine) { install(ContentNegotiation) { json(MOCK_JSON) } }
    return GitHubService("https://api.github.com", client)
}

private fun mockServiceWith422ThenOk(body422: String = "{}", bodyOk: String = "{}"): GitHubService {
    var callCount = 0
    val engine = MockEngine { _ ->
        callCount++
        if (callCount == 1) {
            respond(
                content = body422,
                status = HttpStatusCode.UnprocessableEntity,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        } else {
            respond(
                content = bodyOk,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
    }
    val client = HttpClient(engine) { install(ContentNegotiation) { json(MOCK_JSON) } }
    return GitHubService("https://api.github.com", client)
}

class GitHubServiceNetworkTest : FunSpec({

    context("searchPRs") {

        test("maps items to PullRequest list") {
            val responseBody = """{"items":[{"title":"Fix bug","html_url":"https://github.com/owner/repo/pull/1","number":1,"body":"desc","user":{"login":"alice"},"created_at":"2024-01-01","repository_url":"https://api.github.com/repos/owner/repo"}]}"""
            val svc = mockServiceResponses(responseBody)
            val prs = svc.searchPRs("token", "is:open")
            prs shouldHaveSize 1
            prs[0].title shouldBe "Fix bug"
            prs[0].number shouldBe 1
            prs[0].owner shouldBe "owner"
            prs[0].repo shouldBe "repo"
            prs[0].author shouldBe "alice"
        }

        test("empty items list returns empty list") {
            val svc = mockServiceResponses("""{"items":[]}""")
            svc.searchPRs("token", "is:open").shouldBeEmpty()
        }
    }

    context("getPRDiff") {

        test("returns diff string") {
            val svc = mockServiceResponses("diff --git a/Foo.java b/Foo.java")
            val diff = svc.getPRDiff("token", "owner", "repo", 1)
            diff shouldContain "Foo.java"
        }

        test("truncates diff at 80KB") {
            val bigDiff = "x".repeat(90_000)
            val svc = mockServiceResponses(bigDiff)
            val diff = svc.getPRDiff("token", "owner", "repo", 1)
            diff shouldContain "[... diff truncated at 80 KB ...]"
            (diff.length < 90_000).shouldBeTrue()
        }
    }

    context("getPendingReviewId") {

        test("returns id when PENDING review present") {
            val svc = mockServiceResponses("""[{"id":42,"state":"PENDING","body":"body"}]""")
            svc.getPendingReviewId("token", "owner", "repo", 1) shouldBe "42"
        }

        test("returns null when no PENDING review") {
            val svc = mockServiceResponses("""[{"id":42,"state":"APPROVED","body":"body"}]""")
            svc.getPendingReviewId("token", "owner", "repo", 1).shouldBeNull()
        }

        test("returns null when list is empty") {
            val svc = mockServiceResponses("[]")
            svc.getPendingReviewId("token", "owner", "repo", 1).shouldBeNull()
        }
    }

    context("saveDraftReview") {
        // For saveDraftReview the call order is:
        // 1. GET reviews (check for existing pending review)
        // 2. GET pull/{number} (get HEAD sha)
        // 3. POST reviews (create review)

        test("creates review and returns its id") {
            val review = ReviewResult("summary", "APPROVE", emptyList())
            val svc = mockServiceResponses(
                "[]",
                """{"merged":false,"head":{"sha":"abc"}}""",
                """{"id":77}"""
            )
            val result = svc.saveDraftReview("token", "owner", "repo", 1, review)
            result.reviewId shouldBe "77"
            result.commentsDropped.shouldBeFalse()
        }

        test("deletes existing pending review before creating new one") {
            // 1. GET reviews → existing PENDING 55
            // 2. DELETE review/55
            // 3. GET pull/1 (HEAD sha)
            // 4. POST reviews → new review 88
            val review = ReviewResult("s", "COMMENT", emptyList())
            val svc = mockServiceResponses(
                """[{"id":55,"state":"PENDING","body":""}]""",
                """{}""",
                """{"merged":false,"head":{"sha":"sha1"}}""",
                """{"id":88}"""
            )
            val result = svc.saveDraftReview("token", "owner", "repo", 1, review)
            result.reviewId shouldBe "88"
        }
    }

    context("loadDraftReview") {

        test("returns null when no pending review") {
            val svc = mockServiceResponses("[]")
            svc.loadDraftReview("token", "owner", "repo", 1).shouldBeNull()
        }

        test("returns PendingReview when pending review exists") {
            val body = "<!-- claude-summary: my summary --> \n<!-- claude-verdict: APPROVE --> \n<!-- claude-comments: [] -->"
            val escapedBody = body.replace("\"", "\\\"")
            val svc = mockServiceResponses(
                """[{"id":5,"state":"PENDING","body":""}]""",
                """{"id":5,"state":"PENDING","body":"$escapedBody"}""",
                "[]"
            )
            val pending = svc.loadDraftReview("token", "owner", "repo", 1)
            pending.shouldNotBeNull()
            pending.id shouldBe "5"
            pending.result.getVerdict() shouldBe "APPROVE"
        }
    }

    context("submitDraftReview") {

        test("posts to events endpoint without throwing") {
            val svc = mockServiceResponses("""{"id":1}""")
            svc.submitDraftReview("token", "owner", "repo", 1, "5", "APPROVE", "")
        }

        test("uses provided body for COMMENT event") {
            val svc = mockServiceResponses("""{"id":1}""")
            svc.submitDraftReview("token", "owner", "repo", 1, "5", "COMMENT", "looks good")
        }
    }

    context("deleteDraftReview") {

        test("calls delete without throwing") {
            val svc = mockServiceResponses("{}")
            svc.deleteDraftReview("token", "owner", "repo", 1, "5")
        }
    }

    context("isPRMerged") {

        test("returns true when merged") {
            val svc = mockServiceResponses("""{"merged":true,"head":{"sha":"abc"}}""")
            svc.isPRMerged("token", "owner", "repo", 1).shouldBeTrue()
        }

        test("returns false when not merged") {
            val svc = mockServiceResponses("""{"merged":false,"head":{"sha":"abc"}}""")
            svc.isPRMerged("token", "owner", "repo", 1).shouldBeFalse()
        }
    }

    context("getPRHeadSha") {

        test("returns head sha") {
            val svc = mockServiceResponses("""{"merged":false,"head":{"sha":"deadbeef"}}""")
            svc.getPRHeadSha("token", "owner", "repo", 1) shouldBe "deadbeef"
        }

        test("returns empty string when head is null") {
            val svc = mockServiceResponses("""{"merged":false}""")
            svc.getPRHeadSha("token", "owner", "repo", 1) shouldBe ""
        }
    }

    context("getExistingReviewsSummary") {

        test("returns empty string when no submitted reviews") {
            val svc = mockServiceResponses("[]")
            svc.getExistingReviewsSummary("token", "owner", "repo", 1) shouldBe ""
        }

        test("returns empty string when only PENDING reviews") {
            val svc = mockServiceResponses("""[{"id":1,"state":"PENDING","body":null,"user":{"login":"alice"}}]""")
            svc.getExistingReviewsSummary("token", "owner", "repo", 1) shouldBe ""
        }

        test("formats submitted review with reviewer and state") {
            val svc = mockServiceResponses(
                """[{"id":1,"state":"APPROVED","body":"lgtm","user":{"login":"alice"},"submitted_at":"2024-01-15T10:00:00Z"}]""",
                "[]"
            )
            val summary = svc.getExistingReviewsSummary("token", "owner", "repo", 1)
            summary shouldContain "@alice"
            summary shouldContain "APPROVED"
            summary shouldContain "lgtm"
        }

        test("includes inline comment paths and lines") {
            val svc = mockServiceResponses(
                """[{"id":1,"state":"APPROVED","body":"","user":{"login":"bob"},"submitted_at":"2024-01-15T10:00:00Z"}]""",
                """[{"path":"src/Foo.java","line":42,"body":"fix this"}]"""
            )
            val summary = svc.getExistingReviewsSummary("token", "owner", "repo", 1)
            summary shouldContain "src/Foo.java"
            summary shouldContain "42"
            summary shouldContain "fix this"
        }
    }
})
