package com.jinloes.prpilot.services

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Lightweight local index recording which PRs have a pending (draft) review on GitHub. Stored as
 * JSON at `~/.pr-pilot/pending-prs.json`.
 *
 * This is intentionally minimal — it holds only enough data to populate the "Load Draft" list.
 * The actual review content is always fetched live from GitHub.
 */
class PendingReviewIndex(private val indexFile: Path) {

    constructor() : this(
        Path.of(System.getProperty("user.home"), ".pr-pilot", "pending-prs.json")
    )

    @Serializable
    data class Entry(
        val owner: String,
        val repo: String,
        val number: Int,
        val title: String,
        val savedAt: String,
        // Nullable with default to handle old JSON entries that lack this field.
        @SerialName("headSha") val headShaRaw: String? = null,
    ) {
        // Record-compat accessors for Java callers that use component-style foo() instead of getFoo().
        fun owner(): String = owner
        fun repo(): String = repo
        fun number(): Int = number
        fun title(): String = title
        fun savedAt(): String = savedAt

        /** Returns the head SHA, or empty string for entries saved before this field was added. */
        fun headSha(): String = headShaRaw ?: ""

        fun displayLabel(): String {
            val truncated = savedAt.replace("T", " ").substring(0, minOf(16, savedAt.length))
            return "$owner/$repo #$number — $title  ($truncated)"
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(PendingReviewIndex::class.java)
        private val JSON = Json { ignoreUnknownKeys = true; prettyPrint = true }
        private val SAVED_AT_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }

    @Synchronized
    fun list(): MutableList<Entry> {
        if (!Files.exists(indexFile)) return mutableListOf()
        return try {
            val json = Files.readString(indexFile, StandardCharsets.UTF_8)
            JSON.decodeFromString<List<Entry>>(json).toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    @Synchronized
    fun add(owner: String, repo: String, number: Int, title: String, headSha: String?) {
        val entries = list()
        entries.removeIf { e -> e.owner == owner && e.repo == repo && e.number == number }
        entries.add(
            0,
            Entry(
                owner,
                repo,
                number,
                title,
                LocalDateTime.now().format(SAVED_AT_FMT),
                headSha ?: ""
            )
        )
        save(entries)
    }

    @Synchronized
    fun hasDraft(owner: String, repo: String, number: Int): Boolean {
        return list().any { e -> e.owner == owner && e.repo == repo && e.number == number }
    }

    @Synchronized
    fun remove(owner: String, repo: String, number: Int) {
        val entries = list()
        entries.removeIf { e -> e.owner == owner && e.repo == repo && e.number == number }
        save(entries)
    }

    private fun save(entries: List<Entry>) {
        try {
            Files.createDirectories(indexFile.parent)
            val tmp = indexFile.resolveSibling(indexFile.fileName.toString() + ".tmp")
            Files.writeString(tmp, JSON.encodeToString(entries), StandardCharsets.UTF_8)
            Files.move(tmp, indexFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            log.warn("Failed to save pending review index", e)
        }
    }
}
