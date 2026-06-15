package com.jinloes.prpilot.services

import com.github.copilot.CopilotClient
import com.github.copilot.CopilotSession
import com.github.copilot.generated.AssistantMessageDeltaEvent
import com.github.copilot.generated.AssistantMessageEvent
import com.github.copilot.generated.SessionErrorEvent
import com.github.copilot.generated.ToolExecutionStartEvent
import com.github.copilot.rpc.CopilotClientMode
import com.github.copilot.rpc.CopilotClientOptions
import com.github.copilot.rpc.MessageOptions
import com.github.copilot.rpc.PermissionHandler
import com.github.copilot.rpc.SessionConfig
import com.jinloes.prpilot.model.ChatMessage
import com.jinloes.prpilot.model.PRReviewRequest
import com.jinloes.prpilot.model.ReviewResult
import com.jinloes.prpilot.util.ProcessUtil
import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BiConsumer
import java.util.function.Consumer

/**
 * Drives the GitHub Copilot runtime via the official Java SDK. Mirrors the synchronous API of
 * [ClaudeService] so callers can swap providers without changing threading.
 *
 * The SDK still requires the local `copilot` CLI/runtime, but it gives us typed session events
 * instead of a hand-rolled JSONL parser. We keep the same outward behavior: stream text deltas to
 * the UI, surface tool names as status updates, and parse the final assistant message as review
 * JSON.
 */
open class CopilotService @JvmOverloads constructor(
    projectDir: String? = null,
) {

    internal constructor(
        projectDir: String? = null,
        runtimeFactory: RuntimeFactory,
    ) : this(projectDir) {
        this.runtimeFactory = runtimeFactory
    }

    private val workingDir: File =
        if (!projectDir.isNullOrBlank()) File(projectDir)
        else File(System.getProperty("user.home", "/"))

    private var runtimeFactory: RuntimeFactory = SdkRuntimeFactory()

    private val activeRun = AtomicReference<ActiveRun?>()

    /**
     * @param inheritMcp when true, the Copilot session enables config discovery so it inherits
     *   MCP servers from the Copilot CLI config (`~/.copilot/mcp-config.json`) and any repo-local
     *   `.mcp.json` in the working directory.
     * @param configDir optional override of the Copilot config directory; blank uses the CLI
     *   default (`~/.copilot`).
     */
    @JvmOverloads
    @Throws(IOException::class, InterruptedException::class)
    fun reviewPR(
        request: PRReviewRequest,
        model: String,
        effort: String,
        onStatus: Consumer<String>,
        onChunk: BiConsumer<String, String>? = null,
        inheritMcp: Boolean = true,
        configDir: String? = null,
    ): ReviewResult {
        val prompt = ClaudeService.buildPrompt(request)
        log.info(
            "Copilot review prompt: {} chars — diff {} chars, knownPatterns {} chars",
            prompt.length,
            StringUtils.length(request.diff),
            StringUtils.length(request.knownPatterns),
        )
        return runReview(prompt, model, effort, inheritMcp, configDir, onStatus, onChunk)
    }

    private fun runReview(
        prompt: String,
        model: String,
        effort: String,
        inheritMcp: Boolean,
        configDir: String?,
        onStatus: Consumer<String>,
        onChunk: BiConsumer<String, String>?,
    ): ReviewResult {
        onStatus.accept(STATUS_GENERATING)
        val raw = runSession(prompt, model, effort, inheritMcp, configDir, onStatus, onChunk)
        if (raw.isBlank()) {
            throw IOException("copilot produced no output.")
        }
        onStatus.accept(STATUS_PARSING)
        return try {
            ClaudeService.parseReview(raw)
        } catch (parseEx: Exception) {
            log.warn(
                "Failed to parse Copilot review JSON (first 500 chars): {}",
                if (raw.length > 500) raw.substring(0, 500) else raw,
            )
            throw IOException("Failed to parse review JSON from copilot output: ${parseEx.message}", parseEx)
        }
    }

    @JvmOverloads
    @Throws(IOException::class, InterruptedException::class)
    fun chat(
        prContext: String,
        history: List<ChatMessage>,
        userMessage: String,
        effort: String,
        onChunk: Consumer<String>,
        inheritMcp: Boolean = true,
        configDir: String? = null,
    ): String {
        val prompt = ClaudeService.buildChatPrompt(prContext, history, userMessage)
        return runSession(prompt, "", effort, inheritMcp, configDir, { /* ignore status during chat */ }) { _, chunk ->
            onChunk.accept(chunk)
        }
    }

    /**
     * Starts a fresh Copilot SDK client + session, forwards text deltas to [onChunk] (as `"text"`
     * chunks, matching ClaudeService's protocol) and tool names to [onStatus], then returns the
     * final assistant message content. If the SDK never delivers a consolidated assistant message,
     * we fall back to the accumulated deltas.
     */
    private fun runSession(
        prompt: String,
        model: String,
        effort: String,
        inheritMcp: Boolean,
        configDir: String?,
        onStatus: Consumer<String>,
        onChunk: BiConsumer<String, String>?,
    ): String {
        var currentRun: ActiveRun? = null
        val subscriptions = mutableListOf<Closeable>()
        try {
            val client = runtimeFactory.createClient(buildClientRequest())
            currentRun = ActiveRun(client)
            this.activeRun.set(currentRun)

            client.start()
            val session = client.createSession(buildSessionRequest(model, effort, inheritMcp, configDir))
            currentRun.attachSession(session)

            val deltaBuffer = StringBuilder()
            val finalMessage = AtomicReference<String?>()
            val sessionError = AtomicReference<String?>()

            subscriptions += session.onAssistantMessageDelta { delta ->
                if (StringUtils.isNotEmpty(delta)) {
                    val chunk = delta ?: ""
                    deltaBuffer.append(chunk)
                    onChunk?.accept("text", chunk)
                }
            }
            subscriptions += session.onAssistantMessage { content ->
                if (StringUtils.isNotBlank(content)) {
                    finalMessage.set(content)
                }
            }
            subscriptions += session.onToolExecutionStart { toolName ->
                if (StringUtils.isNotBlank(toolName)) {
                    onStatus.accept(toolName ?: "")
                }
            }
            subscriptions += session.onSessionError { message ->
                if (StringUtils.isNotBlank(message)) {
                    sessionError.compareAndSet(null, message)
                }
            }

            session.sendAndWait(prompt, REQUEST_TIMEOUT_MS)

            val raw = StringUtils.defaultIfBlank(finalMessage.get(), deltaBuffer.toString()) ?: ""
            if (StringUtils.isBlank(raw) && StringUtils.isNotBlank(sessionError.get())) {
                throw IOException(sessionError.get())
            }
            return raw
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ex
        } catch (ex: Exception) {
            if (currentRun?.cancelled?.get() == true) {
                throw InterruptedException("copilot request cancelled")
            }
            throw asIOException(ex)
        } finally {
            subscriptions.forEach { closeQuietly(it) }
            this.activeRun.compareAndSet(currentRun, null)
            closeQuietly(currentRun)
        }
    }

    fun cancelCurrentRequest() {
        activeRun.getAndSet(null)?.cancel()
    }

    private fun buildClientRequest(): ClientRequest {
        val env = linkedMapOf<String, String>()
        System.getenv().forEach { (key, value) -> env[key] = value }
        env["HOME"] = System.getProperty("user.home", "/")
        val existingPath = env["PATH"] ?: ""
        env["PATH"] = "/opt/homebrew/bin:/usr/local/bin:$existingPath"
        return ClientRequest(
            cliPath = findCopilotBinary(),
            workingDir = workingDir,
            environment = env,
        )
    }

    private fun buildSessionRequest(
        model: String,
        effort: String,
        inheritMcp: Boolean,
        configDir: String?,
    ): SessionRequest =
        SessionRequest(
            model = model,
            effort = normalizeReasoningEffort(effort),
            workingDir = workingDir,
            enableConfigDiscovery = inheritMcp,
            configDir = configDir?.trim()?.takeIf { it.isNotEmpty() },
        )

    private fun asIOException(ex: Exception): IOException {
        val root = if (ex is ExecutionException && ex.cause is Exception) ex.cause as Exception else ex
        if (root is InterruptedException) {
            Thread.currentThread().interrupt()
            return IOException("copilot request interrupted", root)
        }
        if (root is IOException) {
            return root
        }
        val message = StringUtils.defaultIfBlank(root.message, "copilot request failed")
        return IOException(message, root)
    }

    private fun closeQuietly(closeable: AutoCloseable?) {
        try {
            closeable?.close()
        } catch (_: Exception) {
            // Best effort cleanup only.
        }
    }

    internal data class ClientRequest(
        val cliPath: String,
        val workingDir: File,
        val environment: Map<String, String>,
    )

    internal data class SessionRequest(
        val model: String,
        val effort: String,
        val workingDir: File,
        val enableConfigDiscovery: Boolean = false,
        val configDir: String? = null,
    )

    internal interface RuntimeFactory {
        fun createClient(request: ClientRequest): RuntimeClient
    }

    internal interface RuntimeClient : AutoCloseable {
        @Throws(Exception::class)
        fun start()

        @Throws(Exception::class)
        fun createSession(request: SessionRequest): RuntimeSession

        fun forceStop()
    }

    internal interface RuntimeSession : AutoCloseable {
        fun onAssistantMessageDelta(listener: Consumer<String?>): Closeable

        fun onAssistantMessage(listener: Consumer<String?>): Closeable

        fun onToolExecutionStart(listener: Consumer<String?>): Closeable

        fun onSessionError(listener: Consumer<String?>): Closeable

        @Throws(Exception::class)
        fun sendAndWait(prompt: String, timeoutMs: Long)

        fun abort()
    }

    internal class SdkRuntimeFactory : RuntimeFactory {
        override fun createClient(request: ClientRequest): RuntimeClient = SdkRuntimeClient(request)
    }

    internal class SdkRuntimeClient(
        private val request: ClientRequest,
    ) : RuntimeClient {
        private val client = CopilotClient(
            CopilotClientOptions()
                .setCliPath(request.cliPath)
                .setCwd(request.workingDir.absolutePath)
                .setEnvironment(request.environment)
                .setMode(CopilotClientMode.COPILOT_CLI)
                .setAutoStart(false),
        )

        override fun start() {
            awaitWithTimeout(client.start(), "runtime startup")
        }

        override fun createSession(request: SessionRequest): RuntimeSession {
            val config = SessionConfig()
                .setOnPermissionRequest(PermissionHandler.APPROVE_ALL)
                .setStreaming(true)
                .setWorkingDirectory(request.workingDir.absolutePath)
                .setReasoningEffort(request.effort)
                .setEnableConfigDiscovery(request.enableConfigDiscovery)
            if (request.model.isNotBlank()) {
                config.setModel(request.model)
            }
            if (!request.configDir.isNullOrBlank()) {
                config.setConfigDirectory(request.configDir)
            }
            return SdkRuntimeSession(awaitWithTimeout(client.createSession(config), "session creation"))
        }

        override fun forceStop() {
            client.forceStop()
        }

        override fun close() {
            client.close()
        }
    }

    internal class SdkRuntimeSession(
        private val session: CopilotSession,
    ) : RuntimeSession {
        override fun onAssistantMessageDelta(listener: Consumer<String?>): Closeable =
            session.on(AssistantMessageDeltaEvent::class.java) { event ->
                listener.accept(event.getData()?.deltaContent())
            }

        override fun onAssistantMessage(listener: Consumer<String?>): Closeable =
            session.on(AssistantMessageEvent::class.java) { event ->
                listener.accept(event.getData()?.content())
            }

        override fun onToolExecutionStart(listener: Consumer<String?>): Closeable =
            session.on(ToolExecutionStartEvent::class.java) { event ->
                listener.accept(event.getData()?.toolName())
            }

        override fun onSessionError(listener: Consumer<String?>): Closeable =
            session.on(SessionErrorEvent::class.java) { event ->
                listener.accept(event.getData()?.message())
            }

        override fun sendAndWait(prompt: String, timeoutMs: Long) {
            session.sendAndWait(MessageOptions().setPrompt(prompt), timeoutMs).get()
        }

        override fun abort() {
            session.abort()
        }

        override fun close() {
            session.close()
        }
    }

    private class ActiveRun(
        private val client: RuntimeClient,
    ) : AutoCloseable {
        val cancelled = AtomicBoolean(false)
        private val sessionRef = AtomicReference<RuntimeSession?>()

        fun attachSession(session: RuntimeSession) {
            sessionRef.set(session)
        }

        fun cancel() {
            cancelled.set(true)
            try {
                sessionRef.get()?.abort()
            } catch (_: Exception) {
                // Best effort only.
            }
            try {
                client.forceStop()
            } catch (_: Exception) {
                // Best effort only.
            }
        }

        override fun close() {
            try {
                sessionRef.getAndSet(null)?.close()
            } finally {
                client.close()
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(CopilotService::class.java)

        private const val STATUS_GENERATING = "Generating review…"
        private const val STATUS_PARSING = "Parsing review…"
        private const val REQUEST_TIMEOUT_MS = 30L * 60L * 1000L
        private const val SDK_BOOT_TIMEOUT_MS = 60L * 1000L

        /**
         * Sane default for PR review work: enough depth to catch real bugs and follow the strict
         * JSON schema, without burning the latency of `high`/`xhigh`/`max`. Exposed as a constant
         * so the IntelliJ adapter and VS Code extension can mirror it.
         */
        const val DEFAULT_REASONING_EFFORT = "medium"

        @JvmStatic
        internal fun normalizeReasoningEffort(effort: String?): String = when (effort?.trim()?.lowercase()) {
            "low", "medium", "high", "xhigh" -> effort.trim().lowercase()
            "none" -> "low"
            "max" -> "xhigh"
            else -> DEFAULT_REASONING_EFFORT
        }

        @JvmStatic
        @Throws(IOException::class)
        internal fun <T> awaitWithTimeout(future: Future<T>, operation: String): T = try {
            future.get(SDK_BOOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            throw IOException(
                "copilot $operation timed out after ${SDK_BOOT_TIMEOUT_MS / 1000}s",
                timeout,
            )
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
