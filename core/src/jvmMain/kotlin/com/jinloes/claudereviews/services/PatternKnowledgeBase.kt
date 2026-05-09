package com.jinloes.claudereviews.services

import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.LocalDate

/**
 * Persists verified pattern findings per repository so future reviews can benefit from prior
 * verification work. Stored as Markdown at `~/.claude-reviews/patterns/{owner}_{repo}.md`.
 *
 * Each entry records the comment that was verified and Claude's conclusion. The full file is
 * injected into the review prompt so future reviews know which patterns are established.
 */
class PatternKnowledgeBase(private val baseDir: File) {

    constructor() : this(
        File(System.getProperty("user.home"), ".claude-reviews/patterns")
    )

    companion object {
        private val log = LoggerFactory.getLogger(PatternKnowledgeBase::class.java)

        /** Formats a single knowledge entry as a Markdown block. Visible for testing. */
        @JvmStatic
        fun formatEntry(questionContext: String, response: String): String {
            return "\n---\n**Verified ${LocalDate.now()}**\n\n" +
                "> ${questionContext.trim().replace("\n", "\n> ")}\n\n" +
                "${response.trim()}\n"
        }
    }

    /**
     * Appends a new verified finding. `questionContext` is the comment body that was verified;
     * `response` is Claude's conclusion.
     */
    fun append(owner: String, repo: String, questionContext: String, response: String) {
        val file = fileFor(owner, repo)
        file.parentFile.mkdirs()
        val entry = formatEntry(questionContext, response)
        try {
            FileWriter(file, StandardCharsets.UTF_8, true).use { fw -> fw.write(entry) }
        } catch (e: IOException) {
            log.warn("Failed to write pattern knowledge for {}/{}", owner, repo, e)
        }
    }

    /**
     * Returns the full contents of the knowledge file for this repo, or an empty string if none
     * exists yet.
     */
    fun load(owner: String, repo: String): String {
        val file = fileFor(owner, repo)
        if (!file.isFile) return ""
        return try {
            Files.readString(file.toPath(), StandardCharsets.UTF_8)
        } catch (e: IOException) {
            log.warn("Failed to read pattern knowledge for {}/{}", owner, repo, e)
            ""
        }
    }

    private fun fileFor(owner: String, repo: String): File {
        // % cannot appear in GitHub owner/repo names, so it serves as an unambiguous separator
        val target = File(baseDir, "$owner%$repo.md")
        try {
            val canonicalTarget = target.canonicalPath
            val canonicalBase = baseDir.canonicalPath
            if (!canonicalTarget.startsWith(canonicalBase + File.separator)) {
                throw SecurityException("Refusing path outside base dir: $target")
            }
        } catch (e: IOException) {
            throw SecurityException("Cannot resolve canonical path: $target", e)
        }
        return target
    }
}
