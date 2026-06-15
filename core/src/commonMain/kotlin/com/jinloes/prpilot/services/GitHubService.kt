package com.jinloes.prpilot.services

import com.jinloes.prpilot.model.LineComment
import com.jinloes.prpilot.model.PullRequest
import com.jinloes.prpilot.model.ReviewResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlin.jvm.JvmOverloads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val JSON = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/**
 * GitHub REST API client. All methods are synchronous from the Java caller's perspective —
 * Ktor suspend functions are bridged via [runBlockingCompat] inside each method body.
 *
 * [HTTP_CLIENT] is a companion singleton; a new [GitHubService] instance is cheap because it
 * only carries [apiBase].
 */
class GitHubService(
    private val apiBase: String,
    private val httpClient: HttpClient = HTTP_CLIENT,
) {
    constructor(apiBase: String) : this(apiBase, HTTP_CLIENT)

    companion object {
        private const val MAX_DIFF_BYTES = 80_000

        const val VERDICT_TAG = "<!-- claude-verdict: "
        const val SUMMARY_TAG = "<!-- claude-summary: "
        const val COMMENTS_TAG = "<!-- claude-comments: "
        const val TAG_END = " -->"

        const val APPROVE_BODY = "Looks good to me!"
        const val REQUEST_CHANGES_BODY = "Requesting changes."
        const val COMMENT_BODY = "Leaving comments."
        private const val DETACHED_COMMENTS_HEADER =
            "**Comments not attached inline (invalid diff positions):**"

        /**
         * Returns the effective review body, substituting a default for blank submissions.
         * GitHub rejects REQUEST_CHANGES (and COMMENT) submissions with an empty body
         * (`422: You need to leave a comment indicating the requested changes.`), so a
         * placeholder is required when the caller does not supply one.
         */
        fun effectiveBody(event: String, body: String): String =
            if (body.isBlank()) when (event) {
                "APPROVE" -> APPROVE_BODY
                "REQUEST_CHANGES" -> REQUEST_CHANGES_BODY
                "COMMENT" -> COMMENT_BODY
                else -> body
            } else body

        private val TYPE_PREFIX = Regex("""^\[([A-Z]+)]\s*""")

        /**
         * Shared Ktor client — equivalent of `private static final HttpClient HTTP_CLIENT`.
         * A single instance reuses the connection pool across all [GitHubService] instances.
         */
        val HTTP_CLIENT: HttpClient = HttpClient {
            install(ContentNegotiation) { json(JSON) }
        }

        // --- Static / companion methods called from Java tests and plugin code ---

        fun encodeBody(review: ReviewResult): String {
            val sb = StringBuilder(SUMMARY_TAG)
                .append(escapeComment(review.getSummary()))
                .append(TAG_END)
            sb.append("\n").append(VERDICT_TAG)
                .append(escapeComment(review.getVerdict()))
                .append(TAG_END)

            val arr = buildJsonArray {
                for (c in review.getLineComments()) {
                    add(buildJsonObject {
                        put("f", c.getFile())
                        put("l", c.getLine())
                        put("t", c.getType())
                        put("b", c.getBody())
                        if (c.getSeverity().isNotBlank()) put("s", c.getSeverity())
                        if (c.getCategory().isNotBlank()) put("c", c.getCategory())
                        if (c.getConfidence().isNotBlank()) put("cf", c.getConfidence())
                        if (c.getRationale().isNotBlank()) put("r", c.getRationale())
                    })
                }
            }
            sb.append("\n").append(COMMENTS_TAG)
                .append(arr.toString().replace("-->", "-- >"))
                .append(TAG_END)

            val general = review.getLineComments().filter { it.getFile().isBlank() || it.getLine() <= 0 }
            if (general.isNotEmpty()) {
                sb.append("\n\n**General Notes:**")
                for (c in general) sb.append("\n- ").append(c.getBody())
            }
            return sb.toString()
        }

        private fun escapeComment(s: String): String = s.replace("-->", "-- >")

        fun decodeReview(body: String, commentsArr: List<GhReviewComment>): ReviewResult {
            var verdict = "COMMENT"
            val vi = body.indexOf(VERDICT_TAG)
            if (vi >= 0) {
                val ei = body.indexOf(TAG_END, vi + VERDICT_TAG.length)
                if (ei >= 0) verdict = body.substring(vi + VERDICT_TAG.length, ei).trim()
            }

            var summary = ""
            val si = body.indexOf(SUMMARY_TAG)
            if (si >= 0) {
                val se = body.indexOf(TAG_END, si + SUMMARY_TAG.length)
                if (se >= 0) summary = body.substring(si + SUMMARY_TAG.length, se).trim()
            } else if (vi > 0) {
                summary = body.substring(0, vi).trim()
                val ci2 = summary.indexOf(COMMENTS_TAG)
                if (ci2 >= 0) summary = summary.substring(0, ci2).trim()
            }

            val comments = mutableListOf<LineComment>()

            val embeddedIdx = body.indexOf(COMMENTS_TAG)
            if (embeddedIdx >= 0) {
                val endIdx = body.indexOf(TAG_END, embeddedIdx + COMMENTS_TAG.length)
                if (endIdx >= 0) {
                    val json = body.substring(embeddedIdx + COMMENTS_TAG.length, endIdx).trim()
                    try {
                        val arr = JSON.parseToJsonElement(json).jsonArray
                        for (el in arr) {
                            val obj = el.jsonObject
                            val file = obj["f"]?.jsonPrimitive?.content ?: ""
                            val line = obj["l"]?.jsonPrimitive?.intOrNull ?: 0
                            val type = obj["t"]?.jsonPrimitive?.content ?: "note"
                            val text = obj["b"]?.jsonPrimitive?.content ?: ""
                            comments.add(
                                LineComment(file, line, type, text).apply {
                                    setSeverity(obj["s"]?.jsonPrimitive?.content)
                                    setCategory(obj["c"]?.jsonPrimitive?.content)
                                    setConfidence(obj["cf"]?.jsonPrimitive?.content)
                                    setRationale(obj["r"]?.jsonPrimitive?.content)
                                },
                            )
                        }
                        return ReviewResult(summary, verdict, comments)
                    } catch (_: Exception) {
                        comments.clear()
                    }
                }
            }

            val notesHeader = "\n\n**General Notes:**"
            val ni = summary.indexOf(notesHeader)
            if (ni >= 0) {
                val notes = summary.substring(ni + notesHeader.length)
                summary = summary.substring(0, ni).trim()
                for (rawLine in notes.split("\n")) {
                    var line = rawLine.trim()
                    if (line.startsWith("- ")) line = line.substring(2)
                    if (line.isBlank()) continue
                    var type = "note"
                    var text = line
                    val m = TYPE_PREFIX.find(line)
                    if (m != null) {
                        type = m.groupValues[1].lowercase()
                        text = line.substring(m.range.last + 1)
                    }
                    comments.add(LineComment("", 0, type, text))
                }
            }

            for (c in commentsArr) {
                val path = c.path ?: ""
                val line = c.line ?: c.originalLine ?: 0
                var text = c.body ?: ""
                var type = "note"
                val m = TYPE_PREFIX.find(text)
                if (m != null) {
                    type = m.groupValues[1].lowercase()
                    text = text.substring(m.range.last + 1)
                }
                comments.add(LineComment(path, line, type, text))
            }

            return ReviewResult(summary, verdict, comments)
        }

        fun hasUsableEmbeddedComments(body: String): Boolean {
            val embeddedIdx = body.indexOf(COMMENTS_TAG)
            if (embeddedIdx < 0) return false
            val endIdx = body.indexOf(TAG_END, embeddedIdx + COMMENTS_TAG.length)
            if (endIdx < 0) return false
            val json = body.substring(embeddedIdx + COMMENTS_TAG.length, endIdx).trim()
            return try {
                JSON.parseToJsonElement(json) is JsonArray
            } catch (_: Exception) {
                false
            }
        }

        /** Builds the deduplicated inline-comment [JsonArray] from a [ReviewResult]. */
        fun buildCommentArray(review: ReviewResult): JsonArray =
            buildCommentArray(review, emptyList())

        /**
         * Builds the deduplicated inline-comment [JsonArray], excluding any pre-known orphans.
         * The webview pre-validates positions against the diff and forwards orphans separately;
         * those go in the body's "Comments not attached inline" section, not as inline POSTs.
         */
        fun buildCommentArray(review: ReviewResult, orphans: List<LineComment>): JsonArray {
            val seen = linkedSetOf<String>()
            val orphanKeys = orphans.map { orphanKey(it) }.toHashSet()
            return buildJsonArray {
                for (c in review.getLineComments()) {
                    var file = c.getFile()
                    if (file.startsWith("a/") || file.startsWith("b/")) file = file.substring(2)
                    if (file.isBlank() || c.getLine() <= 0 || c.getBody().isBlank()) continue
                    if (orphanKeys.contains(orphanKey(c))) continue
                    if (!seen.add("$file ${c.getLine()} ${c.getBody()}")) continue
                    add(buildJsonObject {
                        put("path", file)
                        put("line", c.getLine())
                        put("side", "RIGHT")
                        put("body", c.getBody())
                    })
                }
            }
        }

        private fun truncate(s: String, max: Int): String =
            if (s.length > max) s.substring(0, max) + "..." else s

        private fun orphanKey(c: LineComment): String =
            c.getFile() + "|" + c.getLine() + "|" + c.getType() + "|" + c.getBody()

        /**
         * Formats pre-known orphan [LineComment]s into the body section that GitHub renders
         * verbatim. Mirrors [buildDroppedSection] but takes typed comments rather than the
         * intermediate [JsonObject] shape — used when the webview pre-validated and stripped
         * orphans before the inline POST.
         */
        fun buildOrphanSection(orphans: List<LineComment>): String {
            val sb = StringBuilder("$DETACHED_COMMENTS_HEADER\n")
            for (c in orphans) {
                appendDetachedCommentLine(sb, c.getFile(), c.getLine(), c.getBody())
            }
            return sb.toString().trimEnd()
        }

        fun buildDroppedSection(dropped: List<JsonObject>): String {
            val sb = StringBuilder("$DETACHED_COMMENTS_HEADER\n")
            for (c in dropped) {
                val path = c["path"]?.jsonPrimitive?.content ?: ""
                val line = c["line"]?.jsonPrimitive?.intOrNull ?: 0
                val body = c["body"]?.jsonPrimitive?.content ?: ""
                appendDetachedCommentLine(sb, path, line, body)
            }
            return sb.toString().trimEnd()
        }

        private fun appendDetachedCommentLine(sb: StringBuilder, path: String, line: Int, body: String) {
            sb.append("- `").append(path)
            if (line > 0) sb.append(":").append(line)
            sb.append("`: ").append(body).append("\n")
        }
    }

    // Internal data classes for GitHub API responses (kotlinx.serialization)

    @Serializable
    private data class SearchResult(val items: List<PrItem> = emptyList())

    @Serializable
    private data class PrItem(
        val title: String = "",
        @SerialName("html_url") val htmlUrl: String = "",
        val number: Int = 0,
        val body: String? = null,
        val user: GhUser? = null,
        @SerialName("created_at") val createdAt: String? = null,
        @SerialName("repository_url") val repositoryUrl: String? = null,
    )

    @Serializable
    private data class GhUser(val login: String = "")

    @Serializable
    private data class StarredRepo(@SerialName("full_name") val fullName: String = "")

    @Serializable
    private data class GhReview(
        val id: Long = 0,
        val state: String? = null,
        val body: String? = null,
    )

    @Serializable
    private data class GhSubmittedReview(
        val id: Long = 0,
        val user: GhUser? = null,
        val state: String? = null,
        val body: String? = null,
        @SerialName("submitted_at") val submittedAt: String? = null,
    )

    @Serializable
    data class GhReviewComment(
        val path: String? = null,
        val line: Int? = null,
        @SerialName("original_line") val originalLine: Int? = null,
        val body: String? = null,
    )

    @Serializable
    private data class PrDetail(
        val merged: Boolean = false,
        val head: HeadRef? = null,
        val base: BaseRef? = null,
    )

    @Serializable
    private data class HeadRef(
        val sha: String = "",
        val ref: String = "",
        val repo: HeadRepo? = null,
    )

    @Serializable
    private data class HeadRepo(
        @SerialName("full_name") val fullName: String = "",
        @SerialName("clone_url") val cloneUrl: String = "",
    )

    @Serializable
    private data class BaseRef(val repo: BaseRepoInfo? = null)

    @Serializable
    private data class BaseRepoInfo(@SerialName("full_name") val fullName: String = "")

    /**
     * Carries the head branch name and fork information for a pull request, as returned by
     * [getPRHeadInfo].
     */
    data class PRHeadInfo(
        val ref: String,
        val isFork: Boolean,
        val forkCloneUrl: String,
    )

    /** Carries a pending review ID together with its decoded [ReviewResult]. */
    data class PendingReview(val id: String, val result: ReviewResult, val importedFromGitHub: Boolean)

    /**
     * Result of saving a draft review. [commentsDropped] is true when inline comments were omitted
     * because GitHub rejected them (422 — invalid path or line number).
     */
    data class SaveDraftResult(val reviewId: String, val commentsDropped: Boolean)

    // --- HTTP helpers ---

    private suspend fun get(token: String, url: String, accept: String): String {
        val response = httpClient.get(url) {
            header("Authorization", "Bearer $token")
            header("Accept", accept)
            header("X-GitHub-Api-Version", "2022-11-28")
            header("User-Agent", "intellij-claude-reviews/1.0")
        }
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            throw Exception("GitHub API ${response.status.value}: ${truncate(body, 200)}")
        }
        return response.bodyAsText()
    }

    private suspend fun post(token: String, url: String, jsonBody: String): String {
        val response = httpClient.post(url) {
            header("Authorization", "Bearer $token")
            header("Accept", "application/vnd.github.v3+json")
            header("X-GitHub-Api-Version", "2022-11-28")
            header("User-Agent", "intellij-claude-reviews/1.0")
            contentType(ContentType.Application.Json)
            setBody(jsonBody)
        }
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            throw Exception("GitHub API POST ${response.status.value}: ${truncate(body, 300)}")
        }
        return response.bodyAsText()
    }

    private suspend fun httpPut(token: String, url: String, jsonBody: String): String {
        val response = httpClient.put(url) {
            header("Authorization", "Bearer $token")
            header("Accept", "application/vnd.github.v3+json")
            header("X-GitHub-Api-Version", "2022-11-28")
            header("User-Agent", "intellij-claude-reviews/1.0")
            contentType(ContentType.Application.Json)
            setBody(jsonBody)
        }
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            throw Exception("GitHub API PUT ${response.status.value}: ${truncate(body, 300)}")
        }
        return response.bodyAsText()
    }

    private suspend fun httpDelete(token: String, url: String) {
        val response = httpClient.delete(url) {
            header("Authorization", "Bearer $token")
            header("Accept", "application/vnd.github.v3+json")
            header("X-GitHub-Api-Version", "2022-11-28")
            header("User-Agent", "intellij-claude-reviews/1.0")
        }
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            throw Exception("GitHub API DELETE ${response.status.value}: ${truncate(body, 300)}")
        }
    }

    // --- PR search ---

    /**
     * Searches GitHub for pull requests matching [query]. Blocking — wraps the Ktor suspend call
     * with [runBlockingCompat] so Java callers can invoke this synchronously. [perPage] controls
     * the GitHub page size; callers that need to detect truncation can request one extra row.
     */
    @JvmOverloads
    fun searchPRs(token: String, query: String, perPage: Int = 50): List<PullRequest> = runBlockingCompat {
        val url = "$apiBase/search/issues?q=${urlEncode(query)}&per_page=$perPage&sort=updated"
        val body = get(token, url, "application/vnd.github.v3+json")
        val result = JSON.decodeFromString<SearchResult>(body)
        result.items.map { el ->
            val repoUrl = el.repositoryUrl ?: ""
            val parts = repoUrl.split("/")
            val owner = if (parts.size >= 2) parts[parts.size - 2] else ""
            val repo = if (parts.size >= 1) parts[parts.size - 1] else ""
            PullRequest(
                title = el.title,
                htmlUrl = el.htmlUrl,
                owner = owner,
                repo = repo,
                number = el.number,
                body = el.body ?: "",
                author = el.user?.login ?: "",
                createdAt = el.createdAt ?: "",
            )
        }
    }

    // --- Starred repos ---

    /** Returns up to 200 full repo names the authenticated user has starred. Blocking. */
    fun getStarredRepos(token: String): List<String> = runBlockingCompat {
        val repos = mutableListOf<String>()
        var page = 1
        while (repos.size < 200) {
            val url = "$apiBase/user/starred?per_page=100&sort=updated&page=$page"
            val body = get(token, url, "application/vnd.github.v3+json")
            val items = JSON.decodeFromString<List<StarredRepo>>(body)
            if (items.isEmpty()) break
            items.forEach { repos.add(it.fullName) }
            if (items.size < 100) break
            page++
        }
        repos
    }

    // --- PR diff ---

    /** Returns the unified diff for the given PR, truncated at 80 KB. Blocking. */
    fun getPRDiff(token: String, owner: String, repo: String, prNumber: Int): String = runBlockingCompat {
        val url = "$apiBase/repos/$owner/$repo/pulls/$prNumber"
        val diff = get(token, url, "application/vnd.github.v3.diff")
        if (diff.length > MAX_DIFF_BYTES) {
            diff.substring(0, MAX_DIFF_BYTES) + "\n\n[... diff truncated at 80 KB ...]"
        } else {
            diff
        }
    }

    // --- Draft review API ---

    /**
     * Returns the review ID of any PENDING (draft) review created by the authenticated user, or
     * null if none exists. Blocking.
     */
    fun getPendingReviewId(token: String, owner: String, repo: String, number: Int): String? =
        runBlockingCompat {
            val url = "$apiBase/repos/$owner/$repo/pulls/$number/reviews"
            val body = get(token, url, "application/vnd.github.v3+json")
            val reviews = JSON.decodeFromString<List<GhReview>>(body)
            reviews.firstOrNull { it.state == "PENDING" }?.id?.toString()
        }

    /**
     * Saves a [ReviewResult] as a GitHub pending (draft) review. Any existing pending review is
     * deleted first. Returns the new review ID. Blocking.
     */
    fun saveDraftReview(
        token: String,
        owner: String,
        repo: String,
        number: Int,
        review: ReviewResult,
    ): SaveDraftResult = saveDraftReview(token, owner, repo, number, review, emptyList())

    /**
     * Saves a draft review with a pre-validated list of [orphans] — comments the webview
     * determined have no valid position in the diff. Orphans are excluded from the inline
     * POST and instead appended to the review body in a "Comments not attached inline"
     * section.
     */
    fun saveDraftReview(
        token: String,
        owner: String,
        repo: String,
        number: Int,
        review: ReviewResult,
        orphans: List<LineComment>,
    ): SaveDraftResult = runBlockingCompat {
        val existing = getPendingReviewId(token, owner, repo, number)
        if (existing != null) {
            try {
                deleteDraftReviewSuspend(token, owner, repo, number, existing)
            } catch (_: Exception) {
                // non-fatal: already gone or in a non-deletable state
            }
        }

        val headSha = getPRHeadShaSuspend(token, owner, repo, number)
        val comments = buildCommentArray(review, orphans)
        val bodyWithOrphans = if (orphans.isNotEmpty())
            encodeBody(review) + "\n\n" + buildOrphanSection(orphans)
        else
            encodeBody(review)

        val payload = buildJsonObject {
            put("commit_id", headSha)
            put("body", bodyWithOrphans)
            // Omitting "event" creates a PENDING (draft) review.
            // Setting event:"PENDING" is invalid and causes a 422.
            put("comments", comments)
        }

        val url = "$apiBase/repos/$owner/$repo/pulls/$number/reviews"
        var commentsDropped = false
        val response = try {
            post(token, url, payload.toString())
        } catch (ex: Exception) {
            if (ex.message?.contains("422") == true) {
                // One or more inline comments reference an invalid path or line. Create the review
                // body-only first (guaranteed to succeed), then add each comment individually so
                // only the bad ones are dropped. This avoids creating orphaned probe reviews.
                val bodyPayload = buildJsonObject {
                    payload.forEach { (k, v) -> if (k != "comments") put(k, v) }
                    put("comments", buildJsonArray {})
                }
                val resp = post(token, url, bodyPayload.toString())
                val reviewId = JSON.parseToJsonElement(resp).jsonObject["id"]?.jsonPrimitive?.content ?: ""
                val commentsUrl = "$url/$reviewId/comments"
                val droppedComments = mutableListOf<JsonObject>()
                for (i in 0 until comments.size) {
                    try {
                        post(token, commentsUrl, comments[i].toString())
                    } catch (_: Exception) {
                        droppedComments.add(comments[i].jsonObject)
                    }
                }
                if (droppedComments.isNotEmpty()) {
                    // Preserve any pre-known orphan section we already added so its entries
                    // aren't lost when we PUT the updated body.
                    val updatedBody = bodyWithOrphans + "\n\n" + buildDroppedSection(droppedComments)
                    try {
                        httpPut(token, "$url/$reviewId", buildJsonObject { put("body", updatedBody) }.toString())
                    } catch (_: Exception) {
                        commentsDropped = true
                    }
                }
                resp
            } else {
                throw ex
            }
        }

        val reviewId = JSON.parseToJsonElement(response).jsonObject["id"]?.jsonPrimitive?.content ?: ""
        SaveDraftResult(reviewId, commentsDropped)
    }

    /**
     * Loads the pending draft review for a PR. Returns a [PendingReview] or null if no pending
     * review exists. Blocking.
     */
    fun loadDraftReview(token: String, owner: String, repo: String, number: Int): PendingReview? =
        runBlockingCompat {
            val id = getPendingReviewId(token, owner, repo, number) ?: return@runBlockingCompat null

            val reviewUrl = "$apiBase/repos/$owner/$repo/pulls/$number/reviews/$id"
            val review = JSON.decodeFromString<GhReview>(
                get(token, reviewUrl, "application/vnd.github.v3+json")
            )
            val body = review.body ?: ""

            val ghComments = JSON.decodeFromString<List<GhReviewComment>>(
                get(token, "$reviewUrl/comments", "application/vnd.github.v3+json")
            )

            PendingReview(id, decodeReview(body, ghComments), !hasUsableEmbeddedComments(body))
        }

    /**
     * Submits (publishes) a pending review. [event] must be one of APPROVE, REQUEST_CHANGES, or
     * COMMENT. Blocking.
     */
    fun submitDraftReview(
        token: String,
        owner: String,
        repo: String,
        number: Int,
        reviewId: String,
        event: String,
        body: String,
    ): Unit = runBlockingCompat {
        val url = "$apiBase/repos/$owner/$repo/pulls/$number/reviews/$reviewId/events"
        val effectiveBody = effectiveBody(event, body)
        val payload = buildJsonObject {
            put("event", event)
            put("body", effectiveBody)
        }
        post(token, url, payload.toString())
        Unit
    }

    /** Deletes a pending (unsubmitted) review. Blocking. */
    fun deleteDraftReview(
        token: String,
        owner: String,
        repo: String,
        number: Int,
        reviewId: String,
    ): Unit = runBlockingCompat {
        deleteDraftReviewSuspend(token, owner, repo, number, reviewId)
    }

    private suspend fun deleteDraftReviewSuspend(
        token: String,
        owner: String,
        repo: String,
        number: Int,
        reviewId: String,
    ) {
        httpDelete(token, "$apiBase/repos/$owner/$repo/pulls/$number/reviews/$reviewId")
    }

    /**
     * Returns a formatted plain-text summary of all submitted (non-pending) reviews for a PR.
     * Returns an empty string if there are no submitted reviews. Blocking.
     */
    fun getExistingReviewsSummary(token: String, owner: String, repo: String, number: Int): String =
        runBlockingCompat {
            val url = "$apiBase/repos/$owner/$repo/pulls/$number/reviews"
            val reviews = JSON.decodeFromString<List<GhSubmittedReview>>(
                get(token, url, "application/vnd.github.v3+json")
            )
            val submitted = reviews.filter { it.state != "PENDING" }
            if (submitted.isEmpty()) return@runBlockingCompat ""

            // Fetch every review's inline comments in parallel (order-preserving) to avoid an
            // N+1 sequential waterfall on PRs with many prior reviews. Concurrency is bounded to
            // stay well under GitHub's secondary rate limits on bursts of simultaneous requests.
            val gate = Semaphore(permits = 5)
            val commentsPerReview = coroutineScope {
                submitted.map { r ->
                    async {
                        gate.withPermit {
                            try {
                                JSON.decodeFromString<List<GhReviewComment>>(
                                    get(token, "$url/${r.id}/comments", "application/vnd.github.v3+json")
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                // Non-fatal: inline comments are optional context.
                                emptyList()
                            }
                        }
                    }
                }.awaitAll()
            }

            val sb = StringBuilder()
            submitted.forEachIndexed { i, r ->
                val reviewer = if (r.user != null) "@${r.user.login}" else "unknown"
                val state = r.state ?: "COMMENTED"
                val date = if (r.submittedAt != null && r.submittedAt.length >= 10)
                    r.submittedAt.substring(0, 10) else ""
                sb.append("Review by ").append(reviewer)
                    .append(" (").append(state)
                    .append(if (date.isBlank()) "" else ", $date")
                    .append("):\n")
                val body = r.body?.trim() ?: ""
                if (body.isNotBlank()) {
                    val oneLine = body.replace("\n", " ")
                    sb.append("  Overall: \"").append(truncate(oneLine, 300)).append("\"\n")
                }
                for (c in commentsPerReview[i]) {
                    val path = c.path ?: ""
                    val line = c.line ?: c.originalLine ?: 0
                    val text = c.body?.trim()?.replace("\n", " ") ?: ""
                    if (text.isBlank()) continue
                    sb.append("  - ").append(path)
                    if (line > 0) sb.append(":").append(line)
                    sb.append(": \"").append(truncate(text, 200)).append("\"\n")
                }
                sb.append("\n")
            }
            sb.toString().trim()
        }

    /** Returns true if the pull request has been merged. Blocking. */
    fun isPRMerged(token: String, owner: String, repo: String, number: Int): Boolean = runBlockingCompat {
        val url = "$apiBase/repos/$owner/$repo/pulls/$number"
        val detail = JSON.decodeFromString<PrDetail>(get(token, url, "application/vnd.github.v3+json"))
        detail.merged
    }

    /** Fetches the HEAD commit SHA for a pull request. Blocking. */
    fun getPRHeadSha(token: String, owner: String, repo: String, number: Int): String = runBlockingCompat {
        getPRHeadShaSuspend(token, owner, repo, number)
    }

    private suspend fun getPRHeadShaSuspend(
        token: String,
        owner: String,
        repo: String,
        number: Int,
    ): String {
        val url = "$apiBase/repos/$owner/$repo/pulls/$number"
        val detail = JSON.decodeFromString<PrDetail>(get(token, url, "application/vnd.github.v3+json"))
        return detail.head?.sha ?: ""
    }

    /**
     * Fetches the head branch name and fork status for a pull request. Blocking.
     *
     * Returns a [PRHeadInfo] with [PRHeadInfo.isFork] == true when the PR comes from a fork (i.e.
     * the head and base repos differ). [PRHeadInfo.forkCloneUrl] is populated only for forks.
     */
    fun getPRHeadInfo(token: String, owner: String, repo: String, number: Int): PRHeadInfo =
        runBlockingCompat {
            val url = "$apiBase/repos/$owner/$repo/pulls/$number"
            val detail = JSON.decodeFromString<PrDetail>(get(token, url, "application/vnd.github.v3+json"))
            val ref = detail.head?.ref ?: ""
            val headFullName = detail.head?.repo?.fullName ?: "$owner/$repo"
            val baseFullName = detail.base?.repo?.fullName ?: "$owner/$repo"
            val isFork = headFullName.isNotBlank() && headFullName != baseFullName
            PRHeadInfo(
                ref = ref,
                isFork = isFork,
                forkCloneUrl = if (isFork) detail.head?.repo?.cloneUrl ?: "" else "",
            )
        }
}

/** Platform-agnostic URL encoding. Uses java.net.URLEncoder on JVM, encodeURIComponent on JS. */
internal expect fun urlEncode(value: String): String
