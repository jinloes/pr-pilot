package com.jinloes.claudereviews.services.stream

import java.util.Optional
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StreamEvent(
    var type: String? = null,
    var subtype: String? = null,
    @SerialName("is_error") @get:JvmName("getIsError") var isError: Boolean = false,
    var message: EventMessage? = null,
    var result: String? = null,
) {
    // Record-style component accessors for Java callers using method-call syntax.
    fun type(): String? = type
    fun subtype(): String? = subtype

    /** Returns whether this is an error event. */
    fun isError(): Boolean = isError

    fun message(): Optional<EventMessage> = Optional.ofNullable(message)
    fun result(): Optional<String> = Optional.ofNullable(result)
}
