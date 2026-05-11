package com.jinloes.prpilot.services.stream

import java.util.Optional
import kotlinx.serialization.Serializable

@Serializable
data class EventMessage(
    var content: List<ContentBlock>? = null,
) {
    // Record-style component accessor so callers can use msg.content() as Optional.
    fun content(): Optional<List<ContentBlock>> = Optional.ofNullable(content)
}
