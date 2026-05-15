package com.jinloes.prpilot.services.stream

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class StreamDtoTest : FunSpec({

    // ── StreamEvent ──────────────────────────────────────────────────────

    context("StreamEvent") {

        test("type returns set value") {
            StreamEvent(type = "assistant").type shouldBe "assistant"
        }

        test("type returns null when not set") {
            StreamEvent().type.shouldBeNull()
        }

        test("subtype returns set value") {
            StreamEvent(subtype = "success").subtype shouldBe "success"
        }

        test("subtype returns null when not set") {
            StreamEvent().subtype.shouldBeNull()
        }

        test("isError defaults to false") {
            StreamEvent().isError.shouldBeFalse()
        }

        test("isError returns true when set") {
            StreamEvent(isError = true).isError.shouldBeTrue()
        }

        test("message is non-null when set") {
            val msg = EventMessage()
            StreamEvent(message = msg).message.shouldNotBeNull()
        }

        test("message is null when not set") {
            StreamEvent().message.shouldBeNull()
        }

        test("result is non-null when set") {
            StreamEvent(result = "the result").result shouldBe "the result"
        }

        test("result is null when not set") {
            StreamEvent().result.shouldBeNull()
        }

        test("sessionId returns value when set") {
            StreamEvent(sessionId = "abc-123").sessionId shouldBe "abc-123"
        }

        test("sessionId returns null when not set") {
            StreamEvent().sessionId.shouldBeNull()
        }
    }

    // ── EventMessage ─────────────────────────────────────────────────────

    context("EventMessage") {

        test("content is non-null when set") {
            val block = ContentBlock()
            EventMessage(content = listOf(block)).content shouldBe listOf(block)
        }

        test("content is null when not set") {
            EventMessage().content.shouldBeNull()
        }
    }

    // ── ContentBlock ─────────────────────────────────────────────────────

    context("ContentBlock") {

        test("type returns set value") {
            ContentBlock(type = "tool_use").type shouldBe "tool_use"
        }

        test("type returns null when not set") {
            ContentBlock().type.shouldBeNull()
        }

        test("name is non-null when set") {
            ContentBlock(name = "mcp__github__get_file").name shouldBe "mcp__github__get_file"
        }

        test("name is null when not set") {
            ContentBlock().name.shouldBeNull()
        }

        test("input is non-null when set") {
            val input = mapOf("owner" to "alice" as Any)
            ContentBlock(input = input).input shouldBe input
        }

        test("input is null when not set") {
            ContentBlock().input.shouldBeNull()
        }

        test("text is non-null when set") {
            ContentBlock(text = "hello").text shouldBe "hello"
        }

        test("text is null when not set") {
            ContentBlock().text.shouldBeNull()
        }

        test("thinking is non-null when set") {
            ContentBlock(thinking = "deep thought").thinking shouldBe "deep thought"
        }

        test("thinking is null when not set") {
            ContentBlock().thinking.shouldBeNull()
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
