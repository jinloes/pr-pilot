package com.jinloes.prpilot.services

import com.jinloes.prpilot.model.ChatMessage
import com.jinloes.prpilot.model.PRReviewRequest
import com.jinloes.prpilot.model.ReviewResult
import com.jinloes.prpilot.util.ProcessUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.apache.commons.io.IOUtils
import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BiConsumer
import java.util.function.Consumer

/**
 * Shells out to the GitHub Copilot CLI. Mirrors the synchronous API of [ClaudeService] so callers
 * can swap providers without changing threading.
 *
 * Uses `--output-format json --stream on` so we can stream tool-use status and text deltas to the
 * UI as the agent works. The exact JSONL schema is not documented publicly, so [parseCopilotEvent]
 * is permissive: it recognizes Claude-style (`content_block_delta`, `assistant.message.content[]`),
 * OpenAI-style (`choices[0].delta.content`), and bare-shape events (`{type:"text", text:"..."}`),
 * and silently ignores unknown event types.
 */
open class CopilotService @JvmOverloads constructor(projectDir: String? = null) {

    private val workingDir: File =
        if (!projectDir.isNullOrBlank()) File(projectDir)
        else File(System.getProperty("user.home", "/"))

    private val activeProcess = AtomicReference<Process?>()

    @Throws(IOException::class, InterruptedException::class)
    fun reviewPR(
        request: PRReviewRequest,
        model: String,
        effort: String,
        onStatus: Consumer<String>,
    ): ReviewResult = reviewPR(request, model, effort, onStatus, null)

    @Throws(IOException::class, InterruptedException::class)
    fun reviewPR(
        request: PRReviewRequest,
        model: String,
        effort: String,
        onStatus: Consumer<String>,
        onChunk: BiConsumer<String, String>?,
    ): ReviewResult {
        val prompt = ClaudeService.buildPrompt(request)
        log.info(
            "Copilot review prompt: {} chars — diff {} chars, knownPatterns {} chars",
            prompt.length,
            StringUtils.length(request.diff),
            StringUtils.length(request.knownPatterns),
        )
        return runReview(prompt, model, effort, onStatus, onChunk)
    }

    private fun runReview(
        prompt: String,
        model: String,
        effort: String,
        onStatus: Consumer<String>,
        onChunk: BiConsumer<String, String>?,
    ): ReviewResult {
        onStatus.accept(STATUS_GENERATING)
        val result = runProcess(prompt, model, effort, onStatus, onChunk)
        if (result.text.isBlank()) {
            throw IOException(
                "copilot produced no output. " +
                    buildNoOutputDetail(result.unparseableSample, result.stdoutFile),
            )
        }
        onStatus.accept(STATUS_PARSING)
        return try {
            ClaudeService.parseReview(result.text)
        } catch (parseEx: Exception) {
            log.warn(
                "Failed to parse Copilot review JSON (first 500 chars): {}",
                if (result.text.length > 500) result.text.substring(0, 500) else result.text,
            )
            throw IOException(
                "Failed to parse review JSON from copilot output: ${parseEx.message}. " +
                    "Full stdout at ${result.stdoutFile.absolutePath}",
                parseEx,
            )
        }
    }

    @Throws(IOException::class, InterruptedException::class)
    fun chat(
        prContext: String,
        history: List<ChatMessage>,
        userMessage: String,
        effort: String,
        onChunk: Consumer<String>,
    ): String {
        val prompt = ClaudeService.buildChatPrompt(prContext, history, userMessage)
        return runProcess(prompt, "", effort, { /* ignore status during chat */ }) { _, chunk ->
            onChunk.accept(chunk)
        }.text
    }

    /**
     * Spawns the copilot CLI in JSONL streaming mode. Forwards extracted text to [onChunk] (as
     * `"text"` kind, matching ClaudeService's protocol) and tool-use markers to [onStatus]. Always
     * tees the raw stdout to a temp ndjson file so we have a forensic trail for diagnosing schema
     * drift — the path is included in the returned [RunResult] and surfaced in error messages.
     */
    private fun runProcess(
        prompt: String,
        model: String,
        effort: String,
        onStatus: Consumer<String>,
        onChunk: BiConsumer<String, String>?,
    ): RunResult {
        val stdoutFile = File(
            System.getProperty("java.io.tmpdir"),
            "copilot-stdout-${System.currentTimeMillis()}.ndjson",
        )
        var process: Process? = null
        try {
            process = buildProcess(prompt, model, effort)
            activeProcess.set(process)

            val stderrFuture = drainStderr(process)
            val textBuffer = StringBuilder()
            val unparseableSample = mutableListOf<String>()

            IOUtils.toBufferedReader(
                InputStreamReader(process.inputStream, StandardCharsets.UTF_8)
            ).use { reader ->
                stdoutFile.bufferedWriter(StandardCharsets.UTF_8).use { tee ->
                    reader.lineSequence().forEach { line ->
                        tee.write(line)
                        tee.newLine()
                        if (line.isBlank()) return@forEach
                        val event = parseCopilotEvent(line)
                        if (event.isEmpty()) {
                            if (unparseableSample.size < MAX_UNPARSEABLE_SAMPLES) {
                                unparseableSample.add(line.take(MAX_UNPARSEABLE_LINE_LEN))
                            }
                            return@forEach
                        }
                        if (StringUtils.isNotEmpty(event.text)) {
                            if (event.replacesText) {
                                // Consolidated turn content — discard accumulated deltas so the
                                // buffer ends each turn with just that turn's content. We don't
                                // re-stream to the UI; the deltas already covered it.
                                textBuffer.setLength(0)
                                textBuffer.append(event.text)
                            } else {
                                textBuffer.append(event.text)
                                onChunk?.accept("text", event.text!!)
                            }
                        }
                        if (StringUtils.isNotBlank(event.tool)) {
                            onStatus.accept(event.tool!!)
                        }
                    }
                }
            }

            val finished = process.waitFor(30, TimeUnit.MINUTES)
            if (!finished) {
                process.destroyForcibly()
                throw IOException(
                    "copilot did not finish within 30 minutes. Stdout at ${stdoutFile.absolutePath}",
                )
            }
            val exitCode = process.exitValue()
            val stderr = stderrFuture.join()
            if (exitCode != 0) {
                throw IOException(
                    buildExitErrorMessage(exitCode, stderr, unparseableSample) +
                        ". Full stdout at ${stdoutFile.absolutePath}",
                )
            }
            if (textBuffer.isEmpty() && unparseableSample.isNotEmpty()) {
                log.warn(
                    "copilot emitted {} unrecognized stdout lines but no text events — schema may have changed. Full stdout at {}. First lines:\n{}",
                    unparseableSample.size,
                    stdoutFile.absolutePath,
                    unparseableSample.joinToString("\n"),
                )
            }
            return RunResult(textBuffer.toString(), unparseableSample, stdoutFile)
        } finally {
            activeProcess.set(null)
            process?.destroy()
            // Note: we intentionally keep stdoutFile on disk so the path in error messages is
            // valid for the user to share. The OS /tmp cleaner handles eventual removal.
        }
    }

    /** Bundles the extracted text with diagnostics so callers can produce useful error messages. */
    internal data class RunResult(
        val text: String,
        val unparseableSample: List<String>,
        val stdoutFile: File,
    )

    fun cancelCurrentRequest() {
        activeProcess.getAndSet(null)?.destroyForcibly()
    }

    internal open fun buildProcess(prompt: String, model: String, effort: String): Process {
        val resolvedEffort = effort.ifBlank { DEFAULT_REASONING_EFFORT }
        val cmd = mutableListOf(
            findCopilotBinary(),
            "-p", prompt,
            "--allow-all-tools",
            "--no-color",
            "--output-format", "json",
            "--stream", "on",
            "--reasoning-effort", resolvedEffort,
        )
        if (model.isNotBlank()) {
            cmd.add("--model")
            cmd.add(model)
        }
        val pb = ProcessBuilder(cmd)
        pb.directory(workingDir)
        pb.environment()["HOME"] = System.getProperty("user.home", "/")
        // Prepend known tool paths so gh/git are found without relying on shell PATH inheritance.
        val existingPath = pb.environment()["PATH"] ?: ""
        pb.environment()["PATH"] = "/opt/homebrew/bin:/usr/local/bin:$existingPath"
        return pb.start()
    }

    /**
     * Parsed view of one JSONL event line. All fields are null/empty/false when nothing was
     * recognized.
     *
     * @property replacesText if true, [text] is the *complete* content of a finalized message and
     *   the caller should clear any previously-accumulated text. We use this to discard
     *   intermediate-turn commentary so only the last turn's content (the review JSON) survives
     *   into the parseReview input.
     */
    internal data class CopilotEvent(
        val text: String? = null,
        val tool: String? = null,
        val replacesText: Boolean = false,
    ) {
        fun isEmpty(): Boolean = text.isNullOrEmpty() && tool.isNullOrBlank()
    }

    companion object {
        private val log = LoggerFactory.getLogger(CopilotService::class.java)

        private val JSON = Json { ignoreUnknownKeys = true }

        private const val STATUS_GENERATING = "Generating review…"
        private const val STATUS_PARSING = "Parsing review…"

        /**
         * Sane default for PR review work: enough depth to catch real bugs and follow the strict
         * JSON schema, without burning the latency of `high`/`xhigh`/`max`. Exposed as a constant
         * so the IntelliJ adapter and VS Code extension can mirror it.
         */
        const val DEFAULT_REASONING_EFFORT = "medium"

        /**
         * Caps on how much unparseable stdout to keep for error reporting. Copilot's policy /
         * auth / unknown-flag errors are written as plain text on stdout, not JSON events, so we
         * need to capture some of it to surface a useful message — but bounded, since a runaway
         * process could write megabytes.
         */
        private const val MAX_UNPARSEABLE_SAMPLES = 10
        private const val MAX_UNPARSEABLE_LINE_LEN = 500

        /**
         * Permissive JSONL event parser. Recognizes the four shapes documented in the class
         * KDoc; returns an empty [CopilotEvent] for unknown shapes so the caller can count them
         * and surface a schema-drift warning without crashing the run.
         */
        @JvmStatic
        internal fun parseCopilotEvent(line: String): CopilotEvent {
            val obj = try {
                JSON.parseToJsonElement(line) as? JsonObject ?: return CopilotEvent()
            } catch (_: Exception) {
                return CopilotEvent()
            }

            // Shape A: simple {type, text|name} events
            val type = obj.string("type")
            when (type) {
                "text" -> return CopilotEvent(text = obj.string("text"))
                "tool_use", "tool_call", "tool" ->
                    return CopilotEvent(
                        tool = obj.string("name")
                            ?: obj.string("tool")
                            ?: (obj["function"] as? JsonObject)?.string("name")
                    )
                "response" ->
                    return CopilotEvent(
                        text = obj.string("response") ?: obj.string("text")
                    )
            }

            // Shape E: Copilot CLI's actual JSONL schema (confirmed against v1.0.54 output). Full
            // catalog observed: session.*, user.message, assistant.message_start, assistant.message_delta,
            // assistant.reasoning, assistant.message, assistant.turn_start/end, tool.execution_start,
            // tool.execution_complete, result.
            //
            //   - `assistant.message_delta` data.deltaContent is the streaming text token; we
            //     forward it to the UI for live feedback and *also* accumulate it into the buffer
            //     as a fallback in case no consolidated `assistant.message` arrives.
            //   - `assistant.message` data.content is the consolidated content of a finished turn.
            //     Reviews go through 8+ tool-using turns before the final JSON; if we just
            //     accumulated all delta text we'd hand parseReview a mix of commentary + tool
            //     transcripts + JSON. Marking these events as `replacesText` clears the buffer
            //     each turn so the buffer ends up with *only* the last turn's content (the review).
            //   - `tool.execution_start` data.toolName drives the user-visible status updates.
            //
            // We intentionally skip `assistant.reasoning` (opaque blob), the session.* / user.* /
            // turn_start/end / tool.execution_complete / result events (setup metadata, no payload
            // we need).
            if (type == "assistant.message_delta") {
                val data = obj["data"] as? JsonObject
                return CopilotEvent(text = data?.string("deltaContent"))
            }
            if (type == "assistant.message") {
                val data = obj["data"] as? JsonObject
                val content = data?.string("content")
                if (!content.isNullOrEmpty()) {
                    return CopilotEvent(text = content, replacesText = true)
                }
            }
            if (type == "tool.execution_start") {
                val data = obj["data"] as? JsonObject
                return CopilotEvent(tool = data?.string("toolName"))
            }

            // Shape B: Claude streaming events
            if (type == "content_block_delta") {
                val delta = obj["delta"] as? JsonObject
                val deltaType = delta?.string("type")
                if (deltaType == "text_delta" || deltaType == "input_text_delta") {
                    return CopilotEvent(text = delta.string("text"))
                }
            }
            if (type == "content_block_start") {
                val block = obj["content_block"] as? JsonObject
                if (block != null) {
                    when (block.string("type")) {
                        "text" -> return CopilotEvent(text = block.string("text"))
                        "tool_use" -> return CopilotEvent(tool = block.string("name"))
                    }
                }
            }

            // Shape C: Claude full assistant message {type:"assistant", message:{content:[...]}}
            if (type == "assistant") {
                val content = (obj["message"] as? JsonObject)?.get("content") as? JsonArray
                if (content != null) {
                    val sb = StringBuilder()
                    var tool: String? = null
                    for (block in content) {
                        if (block !is JsonObject) continue
                        when (block.string("type")) {
                            "text" -> block.string("text")?.let { sb.append(it) }
                            "tool_use" -> if (tool == null) tool = block.string("name")
                        }
                    }
                    return CopilotEvent(text = sb.toString().takeIf { it.isNotEmpty() }, tool = tool)
                }
            }

            // Shape D: OpenAI-style {choices:[{delta:{content,tool_calls}}]}
            val firstChoice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            if (firstChoice != null) {
                val delta = (firstChoice["delta"] as? JsonObject)
                    ?: (firstChoice["message"] as? JsonObject)
                if (delta != null) {
                    val text = delta.string("content")
                    val tool = (delta["tool_calls"] as? JsonArray)?.firstOrNull()?.let { tc ->
                        ((tc as? JsonObject)?.get("function") as? JsonObject)?.string("name")
                    }
                    if (!text.isNullOrEmpty() || !tool.isNullOrBlank()) {
                        return CopilotEvent(text = text, tool = tool)
                    }
                }
            }

            return CopilotEvent()
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull

        /**
         * Joins exit code, stderr, and sampled unparseable stdout into a single error string.
         * Copilot writes user-facing errors (policy block, auth failure, unknown flag) as plain
         * text on stdout in JSON-output mode, so we have to dig them out of the sample buffer.
         */
        internal fun buildExitErrorMessage(
            exitCode: Int,
            stderr: String,
            unparseableSample: List<String>,
        ): String {
            val parts = mutableListOf("copilot exited $exitCode")
            if (stderr.isNotBlank()) parts.add(stderr.trim())
            if (unparseableSample.isNotEmpty()) {
                parts.add(unparseableSample.joinToString("\n").trim())
            }
            return parts.joinToString(": ")
        }

        /**
         * Builds a diagnostic detail string for the "produced no output" error path. Includes the
         * first unparseable lines (if any) and always points at the stdout file so the user has a
         * concrete artifact to share when reporting the issue.
         */
        internal fun buildNoOutputDetail(unparseableSample: List<String>, stdoutFile: File): String {
            val parts = mutableListOf<String>()
            if (unparseableSample.isEmpty()) {
                parts.add(
                    "The CLI exited cleanly but emitted no events we recognized — the review prompt may have been rejected or the model failed silently.",
                )
            } else {
                parts.add(
                    "The CLI emitted ${unparseableSample.size} unrecognized stdout line(s). First lines:\n" +
                        unparseableSample.joinToString("\n"),
                )
            }
            parts.add("Full stdout at ${stdoutFile.absolutePath}")
            return parts.joinToString(" ")
        }

        private fun drainStderr(process: Process): CompletableFuture<String> =
            CompletableFuture.supplyAsync {
                try {
                    IOUtils.toString(process.errorStream, StandardCharsets.UTF_8)
                } catch (e: IOException) {
                    ""
                }
            }

        @JvmStatic
        fun findCopilotBinary(): String {
            val home = System.getProperty("user.home", "")
            return ProcessUtil.findBinary(
                "copilot",
                listOf(
                    "$home/.local/bin/copilot",
                    "$home/.npm-global/bin/copilot",
                    "/usr/local/bin/copilot",
                    "/opt/homebrew/bin/copilot",
                    "/usr/bin/copilot",
                )
            )
        }
    }
}
