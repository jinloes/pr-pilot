package com.jinloes.prpilot.services.stream

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StreamEvent(
    val type: String? = null,
    val subtype: String? = null,
    @SerialName("is_error") @get:JvmName("getIsError") val isError: Boolean = false,
    val message: EventMessage? = null,
    val result: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
)
