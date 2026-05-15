package com.jinloes.prpilot.services.stream

import kotlinx.serialization.Serializable

@Serializable
data class EventMessage(
    val content: List<ContentBlock>? = null,
)
