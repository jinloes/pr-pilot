package com.jinloes.prpilot.services.stream

import kotlinx.serialization.Serializable

@Serializable
data class ContentBlock(
    val type: String? = null,
    val name: String? = null,
    val input: Map<String, @Serializable(with = AnySerializer::class) Any>? = null,
    val text: String? = null,
    val thinking: String? = null,
)
