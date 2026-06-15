package com.jinloes.prpilot.services

import com.jinloes.prpilot.model.ChatMessage
import com.jinloes.prpilot.model.PRReviewRequest
import com.jinloes.prpilot.model.PullRequest
import com.jinloes.prpilot.model.ReviewProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import java.io.File
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BiConsumer
import java.util.function.Consumer
import kotlin.concurrent.thread

private fun fakePr() = PullRequest(
    title = "T", htmlUrl = "https://github.com/o/r/pull/1",
    owner = "o", repo = "r", number = 1, body = "", author = "a", createdAt = "2024-01-01",
)

private fun fakeRequest() = PRReviewRequest(pr = fakePr(), diff = "", knownPatterns = "")

private class FakeRuntimeFactory(
    private val clientProvider: () -> FakeRuntimeClient = { FakeRuntimeClient() },
) : CopilotService.RuntimeFactory {
    var lastClientRequest: CopilotService.ClientRequest? = null
        private set
    var lastClient: FakeRuntimeClient? = null
        private set

    override fun createClient(request: CopilotService.ClientRequest): CopilotService.RuntimeClient {
        lastClientRequest = request
        return clientProvider().also { lastClient = it }
    }
}

private class FakeRuntimeClient(
    private val sessionProvider: (CopilotService.SessionRequest) -> FakeRuntimeSession = { FakeRuntimeSession() },
) : CopilotService.RuntimeClient {
    var started = false
        private set
    var closed = false
        private set
    var forceStopCount = 0
        private set
    var lastSessionRequest: CopilotService.SessionRequest? = null
        private set
    var lastSession: FakeRuntimeSession? = null
        private set

    override fun start() {
        started = true
    }

    override fun createSession(request: CopilotService.SessionRequest): CopilotService.RuntimeSession {
        lastSessionRequest = request
        return sessionProvider(request).also { lastSession = it }
    }

    override fun forceStop() {
        forceStopCount++
    }

    override fun close() {
        closed = true
    }
}

private class FakeRuntimeSession : CopilotService.RuntimeSession {
    private val deltaListeners = mutableListOf<Consumer<String?>>()
    private val messageListeners = mutableListOf<Consumer<String?>>()
    private val toolListeners = mutableListOf<Consumer<String?>>()
    private val errorListeners = mutableListOf<Consumer<String?>>()

    var lastPrompt: String? = null
        private set
    var lastTimeoutMs: Long? = null
        private set
    var abortCount = 0
        private set
    var closeCount = 0
        private set
    var sendFailure: Exception? = null
    var sendAction: (FakeRuntimeSession) -> Unit = {}

    override fun onAssistantMessageDelta(listener: Consumer<String?>): Closeable =
        register(deltaListeners, listener)

    override fun onAssistantMessage(listener: Consumer<String?>): Closeable =
        register(messageListeners, listener)

    override fun onToolExecutionStart(listener: Consumer<String?>): Closeable =
        register(toolListeners, listener)

    override fun onSessionError(listener: Consumer<String?>): Closeable =
        register(errorListeners, listener)

    override fun sendAndWait(prompt: String, timeoutMs: Long) {
        lastPrompt = prompt
        lastTimeoutMs = timeoutMs
        sendAction(this)
        sendFailure?.let { throw it }
    }

    override fun abort() {
        abortCount++
    }

    override fun close() {
        closeCount++
    }

    fun emitDelta(text: String) {
        deltaListeners.forEach { it.accept(text) }
    }

    fun emitMessage(text: String) {
        messageListeners.forEach { it.accept(text) }
    }

    fun emitTool(name: String) {
        toolListeners.forEach { it.accept(name) }
    }

    fun emitError(message: String) {
        errorListeners.forEach { it.accept(message) }
    }

    fun listenerCount(): Int =
        deltaListeners.size + messageListeners.size + toolListeners.size + errorListeners.size

    private fun register(
        listeners: MutableList<Consumer<String?>>,
        listener: Consumer<String?>,
    ): Closeable {
        listeners += listener
        return Closeable { listeners.remove(listener) }
    }
}

class CopilotServiceTest : FunSpec({

    context("ReviewProvider.fromId") {
        test("blank — defaults to CLAUDE") {
            ReviewProvider.fromId("") shouldBe ReviewProvider.CLAUDE
            ReviewProvider.fromId(null) shouldBe ReviewProvider.CLAUDE
        }
        test("'copilot' — COPILOT") {
            ReviewProvider.fromId("copilot") shouldBe ReviewProvider.COPILOT
        }
        test("unknown — defaults to CLAUDE") {
            ReviewProvider.fromId("gemini") shouldBe ReviewProvider.CLAUDE
        }
    }

    context("normalizeReasoningEffort") {

        test("blank or unknown — defaults to medium") {
            CopilotService.normalizeReasoningEffort("") shouldBe "medium"
            CopilotService.normalizeReasoningEffort("  ") shouldBe "medium"
            CopilotService.normalizeReasoningEffort(null) shouldBe "medium"
            CopilotService.normalizeReasoningEffort("turbo") shouldBe "medium"
        }

        test("supported SDK values — pass through lowercased") {
            CopilotService.normalizeReasoningEffort("LOW") shouldBe "low"
            CopilotService.normalizeReasoningEffort("medium") shouldBe "medium"
            CopilotService.normalizeReasoningEffort("High") shouldBe "high"
            CopilotService.normalizeReasoningEffort("xhigh") shouldBe "xhigh"
        }

        test("legacy CLI-only values — map to nearest SDK value") {
            CopilotService.normalizeReasoningEffort("none") shouldBe "low"
            CopilotService.normalizeReasoningEffort("max") shouldBe "xhigh"
        }
    }

    context("reviewPR") {

        test("builds client + session requests from settings and project dir") {
            val factory = FakeRuntimeFactory {
                FakeRuntimeClient { FakeRuntimeSession().apply { sendAction = { emitMessage(reviewJson("APPROVE")) } } }
            }
            val svc = CopilotService("/tmp/pr-pilot-repo", factory)

            svc.reviewPR(fakeRequest(), "claude-sonnet-4.6", "high", Consumer {})

            val clientRequest = factory.lastClientRequest.shouldNotBeNull()
            clientRequest.cliPath shouldBe CopilotService.findCopilotBinary()
            clientRequest.workingDir shouldBe File("/tmp/pr-pilot-repo")
            clientRequest.environment["HOME"] shouldBe System.getProperty("user.home", "/")
            clientRequest.environment["PATH"].shouldNotBeNull().shouldStartWith("/opt/homebrew/bin:/usr/local/bin:")

            val client = factory.lastClient.shouldNotBeNull()
            client.started shouldBe true
            client.closed shouldBe true
            client.lastSessionRequest.shouldNotBeNull().apply {
                model shouldBe "claude-sonnet-4.6"
                effort shouldBe "high"
                workingDir shouldBe File("/tmp/pr-pilot-repo")
                enableConfigDiscovery shouldBe true
                configDir shouldBe null
            }
            client.lastSession.shouldNotBeNull().apply {
                lastPrompt.shouldNotBeNull().shouldContain("<fetch_diff>")
                lastTimeoutMs shouldBe 30L * 60L * 1000L
                closeCount shouldBe 1
            }
        }

        test("threads MCP inheritance flag and config dir override into the session request") {
            val factory = FakeRuntimeFactory {
                FakeRuntimeClient { FakeRuntimeSession().apply { sendAction = { emitMessage(reviewJson("APPROVE")) } } }
            }
            val svc = CopilotService(runtimeFactory = factory)

            svc.reviewPR(
                fakeRequest(), "", "medium", Consumer {},
                inheritMcp = false, configDir = "  /custom/.copilot  ",
            )

            factory.lastClient.shouldNotBeNull().lastSessionRequest.shouldNotBeNull().apply {
                enableConfigDiscovery shouldBe false
                configDir shouldBe "/custom/.copilot"
            }
        }

        test("blank config dir override resolves to null") {
            val factory = FakeRuntimeFactory {
                FakeRuntimeClient { FakeRuntimeSession().apply { sendAction = { emitMessage(reviewJson("APPROVE")) } } }
            }
            val svc = CopilotService(runtimeFactory = factory)

            svc.reviewPR(fakeRequest(), "", "medium", Consumer {}, configDir = "   ")

            factory.lastClient.shouldNotBeNull().lastSessionRequest.shouldNotBeNull().configDir shouldBe null
        }

        test("streams text deltas as chunks, tool names as statuses, and parses final assistant message") {
            val factory = FakeRuntimeFactory {
                FakeRuntimeClient {
                    FakeRuntimeSession().apply {
                        sendAction = {
                            emitDelta("{\"summary\":\"draft\"")
                            emitTool("view")
                            emitMessage(reviewJson("APPROVE"))
                        }
                    }
                }
            }
            val svc = CopilotService(runtimeFactory = factory)
            val statuses = mutableListOf<String>()
            val chunks = mutableListOf<Pair<String, String>>()

            val result = svc.reviewPR(
                fakeRequest(), "", "medium",
                Consumer { statuses.add(it) },
                BiConsumer { kind, chunk -> chunks.add(kind to chunk) },
            )

            result.getVerdict() shouldBe "APPROVE"
            chunks shouldHaveSize 1
            chunks.single() shouldBe ("text" to "{\"summary\":\"draft\"")
            statuses shouldContainInOrder listOf("Generating review…", "view", "Parsing review…")
        }

        test("falls back to accumulated deltas when no final assistant message arrives") {
            val factory = FakeRuntimeFactory {
                FakeRuntimeClient {
                    FakeRuntimeSession().apply {
                        sendAction = {
                            emitDelta("{\"summary\":\"s\",")
                            emitDelta("\"verdict\":\"COMMENT\",")
                            emitDelta("\"lineComments\":[]}")
                        }
                    }
                }
            }
            val svc = CopilotService(runtimeFactory = factory)
            val chunks = mutableListOf<String>()

            val result = svc.reviewPR(
                fakeRequest(), "", "medium", Consumer {}, BiConsumer { _, chunk -> chunks += chunk },
            )

            result.getVerdict() shouldBe "COMMENT"
            chunks shouldBe listOf(
                "{\"summary\":\"s\",",
                "\"verdict\":\"COMMENT\",",
                "\"lineComments\":[]}",
            )
        }

        test("surfaces session error when the runtime emits no output") {
            val factory = FakeRuntimeFactory {
                FakeRuntimeClient {
                    FakeRuntimeSession().apply {
                        sendAction = { emitError("Copilot account is not authorized for this model") }
                    }
                }
            }
            val svc = CopilotService(runtimeFactory = factory)

            val ex = shouldThrow<IOException> {
                svc.reviewPR(fakeRequest(), "", "medium", Consumer {})
            }

            ex.message shouldBe "Copilot account is not authorized for this model"
        }

        test("empty output with no session error — produced-no-output message") {
            val factory = FakeRuntimeFactory { FakeRuntimeClient { FakeRuntimeSession() } }
            val svc = CopilotService(runtimeFactory = factory)

            val ex = shouldThrow<IOException> {
                svc.reviewPR(fakeRequest(), "", "medium", Consumer {})
            }

            ex.message shouldBe "copilot produced no output."
        }

        test("runtime failure wrapped in ExecutionException — unwraps cause message") {
            val factory = FakeRuntimeFactory {
                FakeRuntimeClient {
                    FakeRuntimeSession().apply {
                        sendFailure = ExecutionException(IOException("policy denied tool access"))
                    }
                }
            }
            val svc = CopilotService(runtimeFactory = factory)

            val ex = shouldThrow<IOException> {
                svc.reviewPR(fakeRequest(), "", "medium", Consumer {})
            }

            ex.message shouldBe "policy denied tool access"
        }

        test("runtime failure — closes all SDK event subscriptions") {
            val sessionRef = AtomicReference<FakeRuntimeSession>()
            val factory = FakeRuntimeFactory {
                FakeRuntimeClient {
                    FakeRuntimeSession().apply {
                        sessionRef.set(this)
                        sendFailure = IOException("send failed")
                    }
                }
            }
            val svc = CopilotService(runtimeFactory = factory)

            shouldThrow<IOException> {
                svc.reviewPR(fakeRequest(), "", "medium", Consumer {})
            }

            sessionRef.get().shouldNotBeNull().listenerCount() shouldBe 0
        }

        test("wrapped interrupted exception — preserves interrupt flag") {
            val factory = FakeRuntimeFactory {
                FakeRuntimeClient {
                    FakeRuntimeSession().apply {
                        sendFailure = ExecutionException(InterruptedException("stopped by caller"))
                    }
                }
            }
            val svc = CopilotService(runtimeFactory = factory)

            try {
                val ex = shouldThrow<IOException> {
                    svc.reviewPR(fakeRequest(), "", "medium", Consumer {})
                }

                ex.message shouldBe "copilot request interrupted"
                Thread.currentThread().isInterrupted shouldBe true
            } finally {
                // Clear interrupted state so it does not leak into later tests.
                Thread.interrupted()
            }
        }

        test("non-JSON output — parse error") {
            val factory = FakeRuntimeFactory {
                FakeRuntimeClient {
                    FakeRuntimeSession().apply { sendAction = { emitMessage("not even close to JSON") } }
                }
            }
            val svc = CopilotService(runtimeFactory = factory)

            val ex = shouldThrow<IOException> {
                svc.reviewPR(fakeRequest(), "", "medium", Consumer {})
            }

            ex.message!!.shouldContain("Failed to parse review JSON")
        }

        test("legacy effort values — normalize before creating the session") {
            val lowFactory = FakeRuntimeFactory {
                FakeRuntimeClient { FakeRuntimeSession().apply { sendAction = { emitMessage(reviewJson("APPROVE")) } } }
            }
            val xhighFactory = FakeRuntimeFactory {
                FakeRuntimeClient { FakeRuntimeSession().apply { sendAction = { emitMessage(reviewJson("APPROVE")) } } }
            }

            CopilotService(runtimeFactory = lowFactory).reviewPR(fakeRequest(), "", "none", Consumer {})
            CopilotService(runtimeFactory = xhighFactory).reviewPR(fakeRequest(), "", "max", Consumer {})

            lowFactory.lastClient.shouldNotBeNull().lastSessionRequest.shouldNotBeNull().effort shouldBe "low"
            xhighFactory.lastClient.shouldNotBeNull().lastSessionRequest.shouldNotBeNull().effort shouldBe "xhigh"
        }
    }

    context("chat") {

        test("streams delta chunks and returns the final assistant message") {
            val factory = FakeRuntimeFactory {
                FakeRuntimeClient {
                    FakeRuntimeSession().apply {
                        sendAction = {
                            emitDelta("Hello ")
                            emitDelta("there")
                            emitMessage("Hello there.")
                        }
                    }
                }
            }
            val svc = CopilotService(runtimeFactory = factory)
            val collected = StringBuilder()
            val result = svc.chat("", emptyList(), "hi", "medium", Consumer { collected.append(it) })
            result shouldBe "Hello there."
            collected.toString() shouldBe "Hello there"
        }

        test("falls back to delta buffer when the SDK never emits a final message") {
            val factory = FakeRuntimeFactory {
                FakeRuntimeClient {
                    FakeRuntimeSession().apply {
                        sendAction = {
                            emitDelta("Hello ")
                            emitDelta("again")
                        }
                    }
                }
            }
            val svc = CopilotService(runtimeFactory = factory)
            val collected = StringBuilder()

            val result = svc.chat("", emptyList(), "q", "medium", Consumer { collected.append(it) })

            result shouldBe "Hello again"
            collected.toString() shouldBe "Hello again"
        }

        test("runtime failure — surfaces the underlying message") {
            val factory = FakeRuntimeFactory {
                FakeRuntimeClient {
                    FakeRuntimeSession().apply { sendFailure = IOException("tool sandbox startup failed") }
                }
            }
            val svc = CopilotService(runtimeFactory = factory)

            val ex = shouldThrow<IOException> {
                svc.chat("", emptyList(), "q", "medium", Consumer {})
            }
            ex.message shouldBe "tool sandbox startup failed"
        }
    }

    context("cancelCurrentRequest") {

        test("no active run — does not throw") {
            CopilotService().cancelCurrentRequest()
        }

        test("active run — aborts the session, force-stops the client, and interrupts the request") {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val clientRef = AtomicReference<FakeRuntimeClient>()
            val sessionRef = AtomicReference<FakeRuntimeSession>()
            val factory = FakeRuntimeFactory {
                FakeRuntimeClient {
                    FakeRuntimeSession().apply {
                        sessionRef.set(this)
                        sendAction = {
                            started.countDown()
                            release.await(5, TimeUnit.SECONDS)
                        }
                        sendFailure = IOException("cancelled by test")
                    }
                }.also { clientRef.set(it) }
            }
            val svc = CopilotService(runtimeFactory = factory)
            val failure = AtomicReference<Throwable?>()

            val worker = thread(start = true) {
                try {
                    svc.reviewPR(fakeRequest(), "", "medium", Consumer {})
                } catch (t: Throwable) {
                    failure.set(t)
                }
            }

            started.await(5, TimeUnit.SECONDS) shouldBe true
            svc.cancelCurrentRequest()
            release.countDown()
            worker.join(5_000)

            sessionRef.get().shouldNotBeNull().abortCount shouldBe 1
            clientRef.get().shouldNotBeNull().forceStopCount shouldBe 1
            failure.get() shouldBe InterruptedException("copilot request cancelled")
        }
    }

    context("findCopilotBinary") {

        test("returns a non-blank path") {
            CopilotService.findCopilotBinary().isNotBlank() shouldBe true
        }
    }

    context("DEFAULT_REASONING_EFFORT") {

        test("is medium — sane balance of depth and latency") {
            CopilotService.DEFAULT_REASONING_EFFORT shouldBe "medium"
        }
    }

    context("awaitWithTimeout") {

        test("completed future — returns value") {
            val future = CompletableFuture.completedFuture("ready")

            CopilotService.awaitWithTimeout(future, "runtime startup") shouldBe "ready"
        }

        test("incomplete future — wraps timeout as IOException") {
            val future = CompletableFuture<String>()

            val ex = shouldThrow<IOException> {
                CopilotService.awaitWithTimeout(future, "session creation")
            }

            ex.message shouldBe "copilot session creation timed out after 60s"
        }
    }
})

private fun reviewJson(verdict: String): String =
    "{\"summary\":\"s\",\"verdict\":\"$verdict\",\"lineComments\":[]}"
