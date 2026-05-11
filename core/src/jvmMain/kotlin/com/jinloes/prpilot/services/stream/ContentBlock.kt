package com.jinloes.prpilot.services.stream

import java.util.Optional
import kotlinx.serialization.Serializable

@Serializable
data class ContentBlock(
    var type: String? = null,
    var name: String? = null,
    var input: Map<String, @Serializable(with = AnySerializer::class) Any>? = null,
    var text: String? = null,
    var thinking: String? = null,
) {
    // Record-style component accessors so callers can use block.type(), block.name(), etc.
    fun type(): String? = type
    fun name(): Optional<String> = Optional.ofNullable(name)
    fun input(): Optional<Map<String, Any>> = Optional.ofNullable(input)
    fun text(): Optional<String> = Optional.ofNullable(text)
    fun thinking(): Optional<String> = Optional.ofNullable(thinking)
}
