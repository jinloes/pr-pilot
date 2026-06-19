package com.jinloes.prpilot.services

import com.jinloes.prpilot.model.LineComment
import com.jinloes.prpilot.model.ReviewResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val MOCK_JSON = Json { ignoreUnknownKeys = true }

private fun mockServiceWithResponseSequence(vararg pairs: Pair<HttpStatusCode, String>): GitHubService {
    var idx = 0
    val engine = MockEngine { _ ->
        val (status, body) = if (idx < pairs.size) pairs[idx++] else HttpStatusCode.OK to "{}"
        respond(
            content = body,
            status = status,
            headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
        )
    }
    val client = HttpClient(engine) { install(ContentNegotiation) { json(MOCK_JSON) } }
    return GitHubService("https://api.github.com", client)
}

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

// Matches responses by request URL rather than call order, so it stays deterministic even when
// requests are issued concurrently (e.g. the parallel comment fetch in getExistingReviewsSummary).
private fun mockServiceByUrl(routes: Map<String, String>): GitHubService {
    val engine = MockEngine { request ->
        val match = routes.entries.firstOrNull { request.url.encodedPath.endsWith(it.key) }
        respond(
            content = match?.value ?: "[]",
            status = HttpStatusCode.OK,
            headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
        )
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

        test("defaults to per_page 50") {
            var capturedPerPage: String? = null
            val engine = MockEngine { request ->
                capturedPerPage = request.url.parameters["per_page"]
                respond(
                    content = """{"items":[]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            }
            val client = HttpClient(engine) { install(ContentNegotiation) { json(MOCK_JSON) } }
            GitHubService("https://api.github.com", client).searchPRs("token", "is:open")
            capturedPerPage shouldBe "50"
        }

        test("forwards an explicit perPage to the request URL") {
            var capturedPerPage: String? = null
            val engine = MockEngine { request ->
                capturedPerPage = request.url.parameters["per_page"]
                respond(
                    content = """{"items":[]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            }
            val client = HttpClient(engine) { install(ContentNegotiation) { json(MOCK_JSON) } }
            GitHubService("https://api.github.com", client).searchPRs("token", "is:open", 51)
            capturedPerPage shouldBe "51"
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

        test("returns the full diff for validation") {
            val bigDiff = "x".repeat(90_000)
            val svc = mockServiceResponses(bigDiff)
            val diff = svc.getPRDiffFull("token", "owner", "repo", 1)
            diff.length shouldBe 90_000
            diff shouldNotContain "[... diff truncated at 80 KB ...]"
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
            pending.importedFromGitHub shouldBe false
        }

        test("marks pending review imported when embedded metadata is missing") {
            val svc = mockServiceResponses(
                """[{"id":5,"state":"PENDING","body":""}]""",
                """{"id":5,"state":"PENDING","body":"plain review body"}""",
                "[]"
            )
            val pending = svc.loadDraftReview("token", "owner", "repo", 1)
            pending.shouldNotBeNull()
            pending.importedFromGitHub shouldBe true
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

        test("aggregates multiple reviews' comments in order (parallel fetch)") {
            val svc = mockServiceByUrl(
                mapOf(
                    "/pulls/1/reviews" to """[
                        {"id":1,"state":"APPROVED","body":"","user":{"login":"alice"},"submitted_at":"2024-01-15T10:00:00Z"},
                        {"id":2,"state":"APPROVED","body":"","user":{"login":"bob"},"submitted_at":"2024-01-16T10:00:00Z"}
                    ]""",
                    "/reviews/1/comments" to """[{"path":"A.kt","line":1,"body":"alice-comment"}]""",
                    "/reviews/2/comments" to """[{"path":"B.kt","line":2,"body":"bob-comment"}]""",
                )
            )
            val summary = svc.getExistingReviewsSummary("token", "owner", "repo", 1)
            // Both reviewers' comments are present and stay matched to the right review, in order.
            summary shouldContain "alice-comment"
            summary shouldContain "bob-comment"
            summary.indexOf("@alice") shouldBeLessThan summary.indexOf("@bob")
            summary.indexOf("alice-comment") shouldBeLessThan summary.indexOf("bob-comment")
        }
    }

    context("buildDroppedSection") {

        test("formats a single dropped comment with file and line") {
            val obj = JsonObject(mapOf(
                "path" to JsonPrimitive("src/Foo.java"),
                "line" to JsonPrimitive(42),
                "body" to JsonPrimitive("null check needed"),
            ))
            val section = GitHubService.buildDroppedSection(listOf(obj))
            section shouldContain "Comments not attached inline"
            section shouldContain "src/Foo.java:42"
            section shouldContain "null check needed"
        }

        test("omits line number when line is zero") {
            val obj = JsonObject(mapOf(
                "path" to JsonPrimitive("README.md"),
                "line" to JsonPrimitive(0),
                "body" to JsonPrimitive("note"),
            ))
            val section = GitHubService.buildDroppedSection(listOf(obj))
            section shouldContain "`README.md`"
            (section.contains(":0")) shouldBe false
        }
    }

    context("saveDraftReview — 422 fallback with dropped comments") {
        // Call sequence when initial POST 422s and a comment also fails individually:
        // 1. GET reviews → []
        // 2. GET pull/1 → head sha
        // 3. POST reviews (with comments) → 422
        // 4. POST reviews (body-only) → {"id":99}
        // 5. POST /99/comments (first comment) → 422
        // 6. PUT /99 (update body with dropped section) → {}

        test("dropped comment recovered via PUT — commentsDropped is false") {
            val review = ReviewResult("s", "COMMENT",
                listOf(LineComment("src/Foo.java", 10, "note", "bad line")))
            val svc = mockServiceWithResponseSequence(
                HttpStatusCode.OK to "[]",
                HttpStatusCode.OK to """{"merged":false,"head":{"sha":"sha1"}}""",
                HttpStatusCode.UnprocessableEntity to """{"message":"Validation Failed"}""",
                HttpStatusCode.OK to """{"id":99}""",
                HttpStatusCode.UnprocessableEntity to """{"message":"Validation Failed"}""",
                HttpStatusCode.OK to """{}"""
            )
            val result = svc.saveDraftReview("token", "owner", "repo", 1, review)
            result.reviewId shouldBe "99"
            result.commentsDropped.shouldBeFalse()
        }

        test("PUT body update fails — commentsDropped remains true") {
            val review = ReviewResult("s", "COMMENT",
                listOf(LineComment("src/Foo.java", 10, "note", "bad line")))
            val svc = mockServiceWithResponseSequence(
                HttpStatusCode.OK to "[]",
                HttpStatusCode.OK to """{"merged":false,"head":{"sha":"sha1"}}""",
                HttpStatusCode.UnprocessableEntity to """{"message":"Validation Failed"}""",
                HttpStatusCode.OK to """{"id":99}""",
                HttpStatusCode.UnprocessableEntity to """{"message":"Validation Failed"}""",
                HttpStatusCode.UnprocessableEntity to """{"message":"Validation Failed"}"""
            )
            val result = svc.saveDraftReview("token", "owner", "repo", 1, review)
            result.commentsDropped.shouldBeTrue()
        }
    }

    context("buildCommentArray with orphans") {

        test("orphan-matching comment is excluded from inline array") {
            val anchor = LineComment("src/Foo.java", 10, "issue", "good")
            val orphan = LineComment("src/Foo.java", 999, "note", "bad")
            val r = ReviewResult("s", "COMMENT", listOf(anchor, orphan))
            val arr = GitHubService.buildCommentArray(r, listOf(orphan))
            arr.size shouldBe 1
            val obj = arr[0].jsonObject
            obj["path"]?.jsonPrimitive?.content shouldBe "src/Foo.java"
            obj["line"]?.jsonPrimitive?.content shouldBe "10"
        }

        test("orphan matching is by file+line+type+body — different type still included inline") {
            val target = LineComment("src/Foo.java", 10, "issue", "body")
            val similar = LineComment("src/Foo.java", 10, "note", "body")
            val r = ReviewResult("s", "COMMENT", listOf(target, similar))
            // Only `target` is flagged orphan; `similar` has a different type so should remain.
            val arr = GitHubService.buildCommentArray(r, listOf(target))
            arr.size shouldBe 1
        }

        test("empty orphan list is equivalent to single-arg overload") {
            val c = LineComment("src/Foo.java", 5, "issue", "body")
            val r = ReviewResult("s", "APPROVE", listOf(c))
            GitHubService.buildCommentArray(r, emptyList()).size shouldBe
                GitHubService.buildCommentArray(r).size
        }
    }

    context("buildOrphanSection") {

        test("formats orphan with file and line") {
            val c = LineComment("src/Foo.java", 42, "issue", "throw inside try")
            val section = GitHubService.buildOrphanSection(listOf(c))
            section shouldContain "Comments not attached inline (invalid diff positions)"
            section shouldContain "src/Foo.java:42"
            section shouldContain "throw inside try"
        }

        test("omits line when line is zero") {
            val c = LineComment("README.md", 0, "note", "general")
            val section = GitHubService.buildOrphanSection(listOf(c))
            section shouldContain "`README.md`"
            (section.contains(":0")) shouldBe false
        }

        test("renders multiple orphans") {
            val orphans = listOf(
                LineComment("src/A.java", 10, "issue", "first"),
                LineComment("src/B.java", 20, "note", "second"),
            )
            val section = GitHubService.buildOrphanSection(orphans)
            section shouldContain "src/A.java:10"
            section shouldContain "src/B.java:20"
            section shouldContain "first"
            section shouldContain "second"
        }
    }

    context("saveDraftReview with pre-known orphans") {
        // When orphans are provided the inline POST excludes them and the body carries the
        // "Comments not attached inline" section. The 422 fallback is not triggered.
        //
        // Call sequence:
        // 1. GET reviews → []                  (getPendingReviewId)
        // 2. GET pull/1 → head sha             (getPRHeadShaSuspend)
        // 3. POST reviews → {"id":42}          (the only inline POST — succeeds)

        test("orphan is stripped from inline POST and added to body") {
            val anchor = LineComment("src/Foo.java", 10, "issue", "good")
            val orphan = LineComment("src/Bar.java", 999, "note", "unanchored body")
            val review = ReviewResult("s", "COMMENT", listOf(anchor, orphan))
            val capturedBodies = mutableListOf<String>()
            val engine = MockEngine { req ->
                val text = req.body.toByteArray().decodeToString()
                capturedBodies.add(text)
                val (status, content) = when (capturedBodies.size) {
                    1 -> HttpStatusCode.OK to "[]"
                    2 -> HttpStatusCode.OK to """{"merged":false,"head":{"sha":"sha1"}}"""
                    else -> HttpStatusCode.OK to """{"id":42}"""
                }
                respond(
                    content = content,
                    status = status,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            }
            val client = HttpClient(engine) {
                install(ContentNegotiation) { json(MOCK_JSON) }
            }
            val svc = GitHubService("https://api.github.com", client)
            val result = svc.saveDraftReview("token", "owner", "repo", 1, review, listOf(orphan))
            result.reviewId shouldBe "42"
            result.commentsDropped.shouldBeFalse()
            // Three calls total — no 422 fallback was needed.
            capturedBodies.size shouldBe 3
            val createReviewBody = capturedBodies.last()
            createReviewBody shouldContain "src/Foo.java"        // anchored comment present
            createReviewBody shouldContain "Comments not attached inline"
            createReviewBody shouldContain "unanchored body"     // orphan body present in section
            createReviewBody shouldContain "src/Bar.java"        // orphan path in the section
        }
    }
})
