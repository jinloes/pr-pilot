package com.jinloes.prpilot.services

import com.jinloes.prpilot.model.PullRequest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Persists the set of PR IDs that have already triggered a notification, so we don't spam the
 * user across restarts.
 *
 * Stored as a JSON array of `"owner/repo#number"` strings at `~/.pr-pilot/seen-prs.json`.
 */
class SeenPRSet(private val file: Path) {

    constructor() : this(
        Path.of(System.getProperty("user.home"), ".pr-pilot", "seen-prs.json")
    )

    companion object {
        private val log = LoggerFactory.getLogger(SeenPRSet::class.java)
        private val JSON = Json.Default

        // Entries older than this are dropped during trim() even if still live.
        const val MAX_SIZE = 500

        private fun key(pr: PullRequest): String =
            "${pr.owner}/${pr.repo}#${pr.number}"
    }

    // LinkedHashSet preserves insertion order so trim() drops the oldest entries.
    @Volatile
    private var seen: LinkedHashSet<String>

    /** True once the initial seed poll has been persisted (first run should not notify). */
    @Volatile
    private var seeded: Boolean

    init {
        val (loadedSeen, loadedSeeded) = load()
        seen = loadedSeen
        seeded = loadedSeeded
    }

    private fun load(): Pair<LinkedHashSet<String>, Boolean> {
        if (Files.exists(file)) {
            return try {
                val json = Files.readString(file, StandardCharsets.UTF_8)
                val loaded = LinkedHashSet(JSON.decodeFromString<List<String>>(json))
                Pair(loaded, true)
            } catch (e: Exception) {
                log.warn("Corrupt seen-PR JSON; resetting", e)
                Pair(LinkedHashSet(), false)
            }
        }
        return Pair(LinkedHashSet(), false)
    }

    fun isSeeded(): Boolean = seeded

    fun contains(pr: PullRequest): Boolean = seen.contains(key(pr))

    fun add(pr: PullRequest) {
        seen.add(key(pr))
    }

    fun markSeeded() {
        seeded = true
    }

    /**
     * Removes entries for PRs that are no longer in [livePrs]. Call after each poll to drop
     * closed/merged PRs and PRs where the review request was fulfilled.
     */
    fun retain(livePrs: Collection<PullRequest>) {
        val liveKeys = livePrs.mapTo(HashSet()) { key(it) }
        seen.retainAll(liveKeys)
    }

    /**
     * Drops the oldest entries if the set exceeds [maxSize]. The oldest entries are the ones
     * added first (LinkedHashSet insertion order).
     */
    @JvmOverloads
    fun trim(maxSize: Int = MAX_SIZE) {
        val excess = seen.size - maxSize
        if (excess <= 0) return
        val iter = seen.iterator()
        repeat(excess) { if (iter.hasNext()) { iter.next(); iter.remove() } }
    }

    fun save() {
        try {
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(tmp, JSON.encodeToString(seen.toList()), StandardCharsets.UTF_8)
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            log.warn("Failed to save seen PR set", e)
        }
    }
}
