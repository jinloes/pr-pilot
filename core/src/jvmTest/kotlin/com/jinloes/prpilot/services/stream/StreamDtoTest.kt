package com.jinloes.prpilot.services.stream

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.optional.shouldBeEmpty
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class StreamDtoTest : FunSpec({

    // ── StreamEvent ──────────────────────────────────────────────────────

    context("StreamEvent") {

        test("type() returns set value") {
            val event = StreamEvent().also { it.type = "assistant" }
            event.type() shouldBe "assistant"
        }

        test("type() returns null when not set") {
            StreamEvent().type().shouldBeNull()
        }

        test("subtype() returns set value") {
            val event = StreamEvent().also { it.subtype = "success" }
            event.subtype() shouldBe "success"
        }

        test("subtype() returns null when not set") {
            StreamEvent().subtype().shouldBeNull()
        }

        test("isError() defaults to false") {
            StreamEvent().isError().shouldBeFalse()
        }

        test("isError() returns true when set") {
            val event = StreamEvent().also { it.isError = true }
            event.isError().shouldBeTrue()
        }

        test("message() is present when set") {
            val msg = EventMessage()
            val event = StreamEvent().also { it.message = msg }
            event.message().shouldBePresent { it shouldBe msg }
        }

        test("message() is empty when null") {
            StreamEvent().message().shouldBeEmpty()
        }

        test("result() is present when set") {
            val event = StreamEvent().also { it.result = "the result" }
            event.result().shouldBePresent { it shouldBe "the result" }
        }

        test("result() is empty when null") {
            StreamEvent().result().shouldBeEmpty()
        }

        test("sessionId() returns value when set") {
            val event = StreamEvent().also { it.sessionId = "abc-123" }
            event.sessionId() shouldBe "abc-123"
        }

        test("sessionId() returns null when not set") {
            StreamEvent().sessionId().shouldBeNull()
        }
    }

    // ── EventMessage ─────────────────────────────────────────────────────

    context("EventMessage") {

        test("content() is present when set") {
            val block = ContentBlock()
            val msg = EventMessage().also { it.content = listOf(block) }
            msg.content().shouldBePresent { it shouldBe listOf(block) }
        }

        test("content() is empty when null") {
            EventMessage().content().shouldBeEmpty()
        }
    }

    // ── ContentBlock ─────────────────────────────────────────────────────

    context("ContentBlock") {

        test("type() returns set value") {
            val block = ContentBlock().also { it.type = "tool_use" }
            block.type() shouldBe "tool_use"
        }

        test("type() returns null when not set") {
            ContentBlock().type().shouldBeNull()
        }

        test("name() is present when set") {
            val block = ContentBlock().also { it.name = "mcp__github__get_file" }
            block.name().shouldBePresent { it shouldBe "mcp__github__get_file" }
        }

        test("name() is empty when null") {
            ContentBlock().name().shouldBeEmpty()
        }

        test("input() is present when set") {
            val input = mapOf("owner" to "alice" as Any)
            val block = ContentBlock().also { it.input = input }
            block.input().shouldBePresent { it shouldBe input }
        }

        test("input() is empty when null") {
            ContentBlock().input().shouldBeEmpty()
        }

        test("text() is present when set") {
            val block = ContentBlock().also { it.text = "hello" }
            block.text().shouldBePresent { it shouldBe "hello" }
        }

        test("text() is empty when null") {
            ContentBlock().text().shouldBeEmpty()
        }

        test("thinking() is present when set") {
            val block = ContentBlock().also { it.thinking = "deep thought" }
            block.thinking().shouldBePresent { it shouldBe "deep thought" }
        }

        test("thinking() is empty when null") {
            ContentBlock().thinking().shouldBeEmpty()
        }
    }

    // ── AnySerializer ────────────────────────────────────────────────────

    context("AnySerializer.fromElement") {

        test("JsonNull deserializes to 'null' string") {
            AnySerializer.fromElement(JsonNull) shouldBe "null"
        }

        test("string primitive deserializes to String") {
            val result = AnySerializer.fromElement(JsonPrimitive("hello"))
            result shouldBe "hello"
            (result is String).shouldBeTrue()
        }

        test("boolean primitive true deserializes to Boolean") {
            val result = AnySerializer.fromElement(JsonPrimitive(true))
            result shouldBe true
            (result is Boolean).shouldBeTrue()
        }

        test("boolean primitive false deserializes to Boolean") {
            val result = AnySerializer.fromElement(JsonPrimitive(false))
            result shouldBe false
            (result is Boolean).shouldBeTrue()
        }

        test("long primitive deserializes to Long") {
            val result = AnySerializer.fromElement(JsonPrimitive(42L))
            (result is Long).shouldBeTrue()
            result shouldBe 42L
        }

        test("double primitive deserializes to Double") {
            val result = AnySerializer.fromElement(JsonPrimitive(3.14))
            (result is Double).shouldBeTrue()
        }

        test("JsonObject deserializes to Map") {
            val obj: JsonObject = buildJsonObject { put("key", JsonPrimitive("val")) }
            val result = AnySerializer.fromElement(obj)
            (result is Map<*, *>).shouldBeTrue()
            @Suppress("UNCHECKED_CAST")
            (result as Map<String, Any>)["key"] shouldBe "val"
        }
    }
})
