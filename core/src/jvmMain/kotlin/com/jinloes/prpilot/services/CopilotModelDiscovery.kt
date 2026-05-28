package com.jinloes.prpilot.services

import org.apache.commons.io.IOUtils
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs `copilot help config` once per session and extracts the list of accepted `--model` IDs.
 * The result is cached in memory; call [invalidate] to force a re-probe (e.g., after the user
 * updates the Copilot CLI in another terminal).
 *
 * The CLI's help text is the source of truth — Copilot's model catalog changes frequently and we
 * intentionally do not pin a list in code. If probing fails (binary missing, policy-blocked
 * account, schema drift in the help output), [listModels] returns an empty list and the caller is
 * expected to fall back to its own hardcoded suggestions.
 */
object CopilotModelDiscovery {

    private val log = LoggerFactory.getLogger(CopilotModelDiscovery::class.java)

    /** Null when no probe has run yet; an empty list after a failed probe (so we don't retry every call). */
    private val cache = AtomicReference<List<String>?>(null)

    /**
     * Returns the cached model list, probing the CLI synchronously on the first call. Subsequent
     * calls return the cached result instantly. Callers should run this off the EDT — the first
     * call can take up to 10 seconds.
     */
    @JvmStatic
    fun listModels(): List<String> {
        cache.get()?.let { return it }
        val probed = probe()
        // Compare-and-set: another caller racing us only wins once; both threads return the same
        // immutable list either way.
        cache.compareAndSet(null, probed)
        return cache.get() ?: probed
    }

    /** Drops the cached result so the next [listModels] call re-probes. */
    @JvmStatic
    fun invalidate() {
        cache.set(null)
    }

    private fun probe(): List<String> {
        return try {
            val pb = ProcessBuilder(CopilotService.findCopilotBinary(), "help", "config")
            pb.environment()["HOME"] = System.getProperty("user.home", "/")
            val existingPath = pb.environment()["PATH"] ?: ""
            pb.environment()["PATH"] = "/opt/homebrew/bin:/usr/local/bin:$existingPath"
            pb.redirectErrorStream(true)
            val process = pb.start()
            val output = IOUtils.toString(process.inputStream, StandardCharsets.UTF_8)
            val finished = process.waitFor(10, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                log.warn("copilot help config timed out after 10s — skipping model discovery.")
                return emptyList()
            }
            if (process.exitValue() != 0) {
                log.warn(
                    "copilot help config exited {} — skipping model discovery. Output: {}",
                    process.exitValue(),
                    output.lines().take(3).joinToString(" | ").take(300),
                )
                return emptyList()
            }
            val models = parseModelsFromHelp(output)
            if (models.isEmpty()) {
                log.warn(
                    "copilot help config produced no recognized model entries — schema may have changed.",
                )
            } else {
                log.info("Discovered {} Copilot model IDs from `copilot help config`.", models.size)
            }
            models
        } catch (e: IOException) {
            log.warn("Failed to probe copilot models: {}", e.message)
            emptyList()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn("Interrupted while probing copilot models")
            emptyList()
        }
    }

    /**
     * Parses model IDs from the output of `copilot help config`. Looks for the `` `model`: ``
     * section header and then collects every subsequent line matching ``    - "model-id"`` until a
     * blank line ends the section. Returns an empty list if the section is not present or no
     * matching items are found — leaving the caller to fall back to its own suggestion list.
     *
     * Exposed `internal` for unit tests so the parser can be exercised without spawning a real CLI.
     */
    @JvmStatic
    internal fun parseModelsFromHelp(helpText: String): List<String> {
        val models = mutableListOf<String>()
        // Leading whitespace is a formatting artifact, not a signal — match with or without it.
        val sectionStart = Regex("^\\s*`model`:.*")
        val quotedItem = Regex("^\\s*-\\s+\"([^\"]+)\"\\s*$")
        var inSection = false

        for (line in helpText.lineSequence()) {
            if (!inSection) {
                if (sectionStart.matches(line)) inSection = true
                continue
            }
            val match = quotedItem.matchEntire(line)
            when {
                match != null -> models.add(match.groupValues[1])
                line.isBlank() && models.isNotEmpty() -> return models
                // Otherwise (continuation of the section's description, etc.) keep scanning.
            }
        }
        return models
    }
}
