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
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.io.IOException
import java.util.function.BiConsumer
import java.util.function.Consumer

private fun fakePr() = PullRequest(
    title = "T", htmlUrl = "https://github.com/o/r/pull/1",
    owner = "o", repo = "r", number = 1, body = "", author = "a", createdAt = "2024-01-01",
)

private fun fakeRequest() = PRReviewRequest(pr = fakePr(), diff = "", knownPatterns = "")

/** Wraps a raw JSON string as a stdout chunk for the fake process to emit. */
private fun ndjson(vararg lines: String): String = lines.joinToString("\n", postfix = "\n")

/**
 * CopilotService subclass that writes a fixed JSONL payload via `printf` and exits with a chosen
 * code. Captures the prompt and model the real CLI would have received so tests can assert them.
 */
private class FakeCopilotService(
    private val stdoutPayload: String,
    private val exitCode: Int,
) : CopilotService() {
    var lastPrompt: String? = null
        private set
    var lastModel: String? = null
        private set
    var lastEffort: String? = null
        private set

    override fun buildProcess(prompt: String, model: String, effort: String): Process {
        lastPrompt = prompt
        lastModel = model
        lastEffort = effort
        val safe = stdoutPayload.replace("'", "'\\''")
        return ProcessBuilder("sh", "-c", "printf '%s' '$safe'; exit $exitCode").start()
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

    context("parseCopilotEvent — Shape A (simple {type,text|name})") {

        test("text event — extracts text") {
            val event = CopilotService.parseCopilotEvent("""{"type":"text","text":"hello"}""")
            event.text shouldBe "hello"
            event.tool.shouldBeNull()
        }

        test("tool_use event — extracts name") {
            val event = CopilotService.parseCopilotEvent("""{"type":"tool_use","name":"bash"}""")
            event.tool shouldBe "bash"
            event.text.shouldBeNull()
        }

        test("tool_call with function.name") {
            val event = CopilotService.parseCopilotEvent(
                """{"type":"tool_call","function":{"name":"read_file"}}""",
            )
            event.tool shouldBe "read_file"
        }

        test("response event with text field") {
            val event =
                CopilotService.parseCopilotEvent("""{"type":"response","response":"final answer"}""")
            event.text shouldBe "final answer"
        }
    }

    context("parseCopilotEvent — Shape E (Copilot CLI v1.0.54 actual schema)") {

        test("assistant.message_delta — extracts deltaContent as streaming text") {
            val line =
                """{"type":"assistant.message_delta","data":{"messageId":"m1","deltaContent":"Got"},"timestamp":"2026-05-28T19:47:22.259Z"}"""
            val event = CopilotService.parseCopilotEvent(line)
            event.text shouldBe "Got"
            event.replacesText shouldBe false
        }

        test("assistant.message — extracts content and marks replacesText=true") {
            // We use replacesText so multi-turn reviews end up with just the LAST turn's content
            // in the buffer, not an accumulation of commentary + tool transcripts + JSON.
            val line =
                """{"type":"assistant.message","data":{"messageId":"m1","model":"gpt-5.3-codex","content":"Final turn content","toolRequests":[]}}"""
            val event = CopilotService.parseCopilotEvent(line)
            event.text shouldBe "Final turn content"
            event.replacesText shouldBe true
        }

        test("assistant.message with empty content — ignored") {
            // Some messages have empty content (e.g., pure tool-request turns); we skip those so
            // they don't clobber a previous turn's accumulated text.
            val line = """{"type":"assistant.message","data":{"messageId":"m1","content":""}}"""
            CopilotService.parseCopilotEvent(line).isEmpty() shouldBe true
        }

        test("tool.execution_start — extracts toolName") {
            val line =
                """{"type":"tool.execution_start","data":{"toolCallId":"c1","toolName":"view","arguments":{"path":"/x"},"turnId":"0"}}"""
            val event = CopilotService.parseCopilotEvent(line)
            event.tool shouldBe "view"
        }

        test("session.* events — ignored (setup metadata)") {
            val line =
                """{"type":"session.mcp_server_status_changed","data":{"serverName":"github-mcp-server","status":"connected"}}"""
            CopilotService.parseCopilotEvent(line).isEmpty() shouldBe true
        }

        test("assistant.reasoning — ignored (opaque blob)") {
            val line =
                """{"type":"assistant.reasoning","data":{"reasoningId":"opaque-base64","content":""}}"""
            CopilotService.parseCopilotEvent(line).isEmpty() shouldBe true
        }

        test("result terminal event — ignored (no text payload)") {
            val line =
                """{"type":"result","timestamp":"2026-05-28T19:51:27.574Z","sessionId":"s1","exitCode":0,"usage":{"premiumRequests":1}}"""
            CopilotService.parseCopilotEvent(line).isEmpty() shouldBe true
        }
    }

    context("parseCopilotEvent — Shape B (Claude content_block_delta / start)") {

        test("text_delta — extracts text") {
            val event = CopilotService.parseCopilotEvent(
                """{"type":"content_block_delta","delta":{"type":"text_delta","text":"chunk"}}""",
            )
            event.text shouldBe "chunk"
        }

        test("content_block_start with tool_use") {
            val event = CopilotService.parseCopilotEvent(
                """{"type":"content_block_start","content_block":{"type":"tool_use","name":"grep"}}""",
            )
            event.tool shouldBe "grep"
        }
    }

    context("parseCopilotEvent — Shape C (assistant message with content[])") {

        test("text content block — extracts text") {
            val event = CopilotService.parseCopilotEvent(
                """{"type":"assistant","message":{"content":[{"type":"text","text":"hi"}]}}""",
            )
            event.text shouldBe "hi"
        }

        test("tool_use content block — extracts tool name") {
            val event = CopilotService.parseCopilotEvent(
                """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"bash","input":{}}]}}""",
            )
            event.tool shouldBe "bash"
        }

        test("mixed blocks — extracts both") {
            val event = CopilotService.parseCopilotEvent(
                """{"type":"assistant","message":{"content":[{"type":"text","text":"working"},{"type":"tool_use","name":"read_file"}]}}""",
            )
            event.text shouldBe "working"
            event.tool shouldBe "read_file"
        }
    }

    context("parseCopilotEvent — Shape D (OpenAI choices)") {

        test("delta.content — extracts text") {
            val event = CopilotService.parseCopilotEvent(
                """{"choices":[{"delta":{"content":"token"}}]}""",
            )
            event.text shouldBe "token"
        }

        test("delta.tool_calls — extracts tool name") {
            val event = CopilotService.parseCopilotEvent(
                """{"choices":[{"delta":{"tool_calls":[{"function":{"name":"bash"}}]}}]}""",
            )
            event.tool shouldBe "bash"
        }

        test("message.content (non-streaming response)") {
            val event = CopilotService.parseCopilotEvent(
                """{"choices":[{"message":{"content":"final answer"}}]}""",
            )
            event.text shouldBe "final answer"
        }
    }

    context("parseCopilotEvent — unknown shapes") {

        test("garbage line — returns empty event") {
            CopilotService.parseCopilotEvent("not json at all").isEmpty() shouldBe true
        }

        test("known JSON but unrecognized shape — returns empty event") {
            CopilotService.parseCopilotEvent("""{"banner":"hello"}""").isEmpty() shouldBe true
        }

        test("empty object — returns empty event") {
            CopilotService.parseCopilotEvent("{}").isEmpty() shouldBe true
        }
    }

    context("reviewPR — streams JSONL events into onChunk and onStatus") {

        test("text deltas — forwarded as chunks and concatenated into review JSON") {
            val payload = ndjson(
                """{"type":"content_block_delta","delta":{"type":"text_delta","text":"{\"summary\":\"s\","}}""",
                """{"type":"content_block_delta","delta":{"type":"text_delta","text":"\"verdict\":\"APPROVE\","}}""",
                """{"type":"content_block_delta","delta":{"type":"text_delta","text":"\"lineComments\":[]}"}}""",
            )
            val svc = FakeCopilotService(payload, 0)
            val chunks = mutableListOf<Pair<String, String>>()
            val result = svc.reviewPR(
                fakeRequest(), "", "medium",
                Consumer {},
                BiConsumer { kind, chunk -> chunks.add(kind to chunk) },
            )
            result.getVerdict() shouldBe "APPROVE"
            chunks shouldHaveSize 3
            chunks.all { it.first == "text" } shouldBe true
        }

        test("tool_use events — forwarded as status updates") {
            val payload = ndjson(
                """{"type":"tool_use","name":"bash"}""",
                """{"type":"tool_use","name":"read_file"}""",
                """{"type":"response","response":"{\"summary\":\"s\",\"verdict\":\"APPROVE\",\"lineComments\":[]}"}""",
            )
            val svc = FakeCopilotService(payload, 0)
            val statuses = mutableListOf<String>()
            svc.reviewPR(fakeRequest(), "", "medium", Consumer { statuses.add(it) })
            statuses shouldContain "bash"
            statuses shouldContain "read_file"
            statuses shouldContain "Generating review…"
            statuses shouldContain "Parsing review…"
        }

        test("blank lines and unparseable lines — ignored gracefully") {
            val payload = ndjson(
                "",
                "garbage",
                """{"banner":"ignored"}""",
                """{"type":"text","text":"{\"summary\":\"s\",\"verdict\":\"COMMENT\",\"lineComments\":[]}"}""",
            )
            val svc = FakeCopilotService(payload, 0)
            val result = svc.reviewPR(fakeRequest(), "", "medium", Consumer {})
            result.getVerdict() shouldBe "COMMENT"
        }

        test("status update ordering — Generating before tool events before Parsing") {
            val payload = ndjson(
                """{"type":"tool_use","name":"bash"}""",
                """{"type":"text","text":"{\"summary\":\"s\",\"verdict\":\"APPROVE\",\"lineComments\":[]}"}""",
            )
            val svc = FakeCopilotService(payload, 0)
            val statuses = mutableListOf<String>()
            svc.reviewPR(fakeRequest(), "", "medium", Consumer { statuses.add(it) })
            statuses shouldContainInOrder listOf("Generating review…", "bash", "Parsing review…")
        }

        test("empty stream with exit 0 — produced-no-output error + stdout file path") {
            val svc = FakeCopilotService("", 0)
            val ex = shouldThrow<IOException> {
                svc.reviewPR(fakeRequest(), "", "medium", Consumer {})
            }
            ex.message!!.shouldContain("no output")
            ex.message!!.shouldContain("Full stdout at")
        }

        test("no-output with unrecognized events — surfaces sample in error message") {
            // Process exits 0 but every line is a shape parseCopilotEvent doesn't recognize.
            // The user has to be able to see what the CLI actually emitted, otherwise they have
            // nothing to share with us when reporting the bug.
            val payload = ndjson(
                """{"type":"unknown_event","weird":"shape"}""",
                """{"meta":{"banner":true}}""",
            )
            val svc = FakeCopilotService(payload, 0)
            val ex = shouldThrow<IOException> {
                svc.reviewPR(fakeRequest(), "", "medium", Consumer {})
            }
            ex.message!!.shouldContain("unrecognized stdout")
            ex.message!!.shouldContain("Full stdout at")
        }

        test("non-zero exit — surfaces stderr-style error") {
            val svc = FakeCopilotService("", 1)
            val ex = shouldThrow<IOException> {
                svc.reviewPR(fakeRequest(), "", "medium", Consumer {})
            }
            ex.message!!.shouldContain("copilot exited 1")
        }

        test("non-zero exit with plain-text error on stdout — surfaces error text") {
            // Copilot writes policy / auth errors to stdout (not stderr) as plain text, so each
            // line fails JSON parsing. The sample buffer must surface those lines on exit-1.
            val payload = "Error: Access denied by policy settings (Request ID: ABCD:1234)\n" +
                "Your Copilot CLI policy setting may be preventing access.\n"
            val svc = FakeCopilotService(payload, 1)
            val ex = shouldThrow<IOException> {
                svc.reviewPR(fakeRequest(), "", "medium", Consumer {})
            }
            ex.message!!.shouldContain("Access denied by policy settings")
        }
    }

    context("buildExitErrorMessage") {

        test("exit + stderr + sample — all joined") {
            val msg = CopilotService.buildExitErrorMessage(
                exitCode = 1,
                stderr = "fatal: bad config",
                unparseableSample = listOf("Error: line one", "Error: line two"),
            )
            msg.shouldContain("copilot exited 1")
            msg.shouldContain("fatal: bad config")
            msg.shouldContain("Error: line one")
            msg.shouldContain("Error: line two")
        }

        test("exit + empty stderr + empty sample — bare exit message") {
            val msg = CopilotService.buildExitErrorMessage(127, "", emptyList())
            msg shouldBe "copilot exited 127"
        }

        test("exit + sample only — exit + sample joined") {
            val msg = CopilotService.buildExitErrorMessage(1, "", listOf("policy block"))
            msg.shouldContain("copilot exited 1")
            msg.shouldContain("policy block")
        }
    }

    context("buildNoOutputDetail") {

        test("empty sample — falls back to generic message + file path") {
            val file = File("/tmp/copilot-stdout-test.ndjson")
            val msg = CopilotService.buildNoOutputDetail(emptyList(), file)
            msg.shouldContain("emitted no events we recognized")
            msg.shouldContain("/tmp/copilot-stdout-test.ndjson")
        }

        test("non-empty sample — includes line count, sample, and file path") {
            val file = File("/tmp/copilot-stdout-test.ndjson")
            val msg =
                CopilotService.buildNoOutputDetail(listOf("event-a", "event-b"), file)
            msg.shouldContain("2 unrecognized")
            msg.shouldContain("event-a")
            msg.shouldContain("event-b")
            msg.shouldContain("/tmp/copilot-stdout-test.ndjson")
        }

        test("accumulated text not parseable as JSON — parse error") {
            val payload = ndjson("""{"type":"text","text":"not even close to JSON"}""")
            val svc = FakeCopilotService(payload, 0)
            val ex = shouldThrow<IOException> {
                svc.reviewPR(fakeRequest(), "", "medium", Consumer {})
            }
            ex.message!!.shouldContain("Failed to parse review JSON")
        }

        test("prompt and model forwarded to buildProcess") {
            val payload = ndjson("""{"type":"text","text":"{\"summary\":\"s\",\"verdict\":\"APPROVE\",\"lineComments\":[]}"}""")
            val svc = FakeCopilotService(payload, 0)
            svc.reviewPR(fakeRequest(), "claude-sonnet-4.6", "high", Consumer {})
            svc.lastPrompt!!.shouldContain("<fetch_diff>")
            svc.lastModel shouldBe "claude-sonnet-4.6"
            svc.lastEffort shouldBe "high"
        }

        test("non-default effort forwarded to buildProcess") {
            val payload = ndjson("""{"type":"text","text":"{\"summary\":\"s\",\"verdict\":\"APPROVE\",\"lineComments\":[]}"}""")
            val svc = FakeCopilotService(payload, 0)
            svc.reviewPR(fakeRequest(), "", "low", Consumer {})
            svc.lastEffort shouldBe "low"
        }

        test("Copilot multi-turn flow — intermediate turns discarded, last assistant.message wins") {
            // This mirrors what Copilot actually emits for a review (8+ turns of commentary +
            // tool use, then a final turn with the review JSON). If we accumulated all delta text
            // across every turn, parseReview would see commentary + tool transcripts + JSON and
            // fail. The replacesText flag on assistant.message events keeps the buffer clean.
            val finalReviewJson =
                """{\"summary\":\"s\",\"verdict\":\"APPROVE\",\"lineComments\":[]}"""
            val payload = ndjson(
                // Turn 1: commentary deltas + finalized message
                """{"type":"assistant.message_delta","data":{"deltaContent":"Got "}}""",
                """{"type":"assistant.message_delta","data":{"deltaContent":"it. "}}""",
                """{"type":"assistant.message","data":{"content":"Got it. I'll pull the diff.","toolRequests":[]}}""",
                // Turn 2: tool call
                """{"type":"tool.execution_start","data":{"toolName":"view","arguments":{}}}""",
                """{"type":"tool.execution_complete","data":{"success":true}}""",
                // Final turn: review JSON as deltas + message
                """{"type":"assistant.message_delta","data":{"deltaContent":"{"}}""",
                """{"type":"assistant.message_delta","data":{"deltaContent":"\"summary\":\"s\","}}""",
                """{"type":"assistant.message","data":{"content":"$finalReviewJson","toolRequests":[]}}""",
                """{"type":"result","timestamp":"x","sessionId":"s","exitCode":0,"usage":{}}""",
            )
            val svc = FakeCopilotService(payload, 0)
            val statuses = mutableListOf<String>()
            val result = svc.reviewPR(fakeRequest(), "", "medium", Consumer { statuses.add(it) })
            result.getVerdict() shouldBe "APPROVE"
            statuses shouldContain "view"
        }
    }

    context("chat") {

        test("streams text events as chunks and returns accumulated text") {
            val payload = ndjson(
                """{"type":"text","text":"Hello "}""",
                """{"type":"text","text":"there."}""",
            )
            val svc = FakeCopilotService(payload, 0)
            val collected = StringBuilder()
            val result = svc.chat("", emptyList(), "hi", "medium", Consumer { collected.append(it) })
            result shouldBe "Hello there."
            collected.toString() shouldBe "Hello there."
        }

        test("non-zero exit surfaces stderr-style error") {
            val svc = FakeCopilotService("", 2)
            val ex = shouldThrow<IOException> {
                svc.chat("", emptyList(), "q", "medium", Consumer {})
            }
            ex.message!!.shouldContain("copilot exited 2")
        }
    }

    context("cancelCurrentRequest") {

        test("no active process — does not throw") {
            CopilotService().cancelCurrentRequest()
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
})
