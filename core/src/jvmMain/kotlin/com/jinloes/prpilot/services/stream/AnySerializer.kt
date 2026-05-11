package com.jinloes.prpilot.services.stream

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Serializes/deserializes `Any` values in tool-use input maps via JsonElement. */
internal object AnySerializer : KSerializer<Any> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Any")

    override fun serialize(encoder: Encoder, value: Any) {
        (encoder as JsonEncoder).encodeJsonElement(toElement(value))
    }

    override fun deserialize(decoder: Decoder): Any = fromElement((decoder as JsonDecoder).decodeJsonElement())

    private fun toElement(value: Any): JsonElement = when (value) {
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    internal fun fromElement(element: JsonElement): Any = when (element) {
        is JsonNull -> "null"
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.boolean
            element.longOrNull != null -> element.longOrNull!!
            element.doubleOrNull != null -> element.doubleOrNull!!
            else -> element.content
        }
        is JsonObject -> element.entries.associate { (k, v) -> k to fromElement(v) }
        else -> element.toString()
    }
}
