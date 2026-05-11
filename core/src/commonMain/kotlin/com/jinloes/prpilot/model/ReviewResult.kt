package com.jinloes.prpilot.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Holds the AI-generated review: a markdown summary, a verdict, and a list of inline line
 * comments. Default parameter values allow both no-arg Jackson deserialization and
 * kotlinx.serialization decoding.
 */
@Serializable
class ReviewResult() {

    // Backing fields use distinct names to avoid JVM signature clashes with accessor methods.
    @SerialName("summary") @Suppress("PropertyName") internal var _summary: String? = null
    @SerialName("verdict") @Suppress("PropertyName") internal var _verdict: String? = null
    @SerialName("lineComments") @Suppress("PropertyName") internal var _lineComments: MutableList<LineComment>? = null

    constructor(
        summary: String,
        verdict: String,
        lineComments: List<LineComment>?,
    ) : this() {
        _summary = summary
        _verdict = verdict
        _lineComments = lineComments?.toMutableList() ?: mutableListOf()
    }

    fun getSummary(): String = _summary ?: ""
    fun setSummary(value: String) { _summary = value }

    fun getVerdict(): String = _verdict ?: "COMMENT"
    fun setVerdict(value: String) { _verdict = value }

    fun getLineComments(): MutableList<LineComment> {
        if (_lineComments == null) _lineComments = mutableListOf()
        return _lineComments!!
    }
    fun setLineComments(value: MutableList<LineComment>) { _lineComments = value }
}
