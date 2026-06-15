package com.jinloes.prpilot.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A single inline comment on a diff line. Mutable setters are preserved for Jackson
 * deserialization and for Java interop from intellij-plugin.
 *
 * The richer fields ([_severity], [_category], [_confidence], [_rationale]) are optional and
 * default to null so legacy drafts and older provider output deserialize unchanged. They let the
 * UI sort, filter, and explain findings beyond the coarse [_type] bucket.
 */
@Serializable
class LineComment() {

    // Backing fields use distinct names to avoid JVM signature clashes with property accessors.
    @SerialName("file") @Suppress("PropertyName") internal var _file: String? = null
    @SerialName("line") @Suppress("PropertyName") internal var _line: Int = 0
    @SerialName("type") @Suppress("PropertyName") internal var _type: String? = null
    @SerialName("body") @Suppress("PropertyName") internal var _body: String? = null
    @SerialName("severity") @Suppress("PropertyName") internal var _severity: String? = null
    @SerialName("category") @Suppress("PropertyName") internal var _category: String? = null
    @SerialName("confidence") @Suppress("PropertyName") internal var _confidence: String? = null
    @SerialName("rationale") @Suppress("PropertyName") internal var _rationale: String? = null

    constructor(file: String, line: Int, type: String, body: String) : this() {
        _file = file
        _line = line
        _type = type
        _body = body
    }

    fun getFile(): String = _file ?: ""
    fun setFile(value: String) { _file = value }

    fun getLine(): Int = _line
    fun setLine(value: Int) { _line = value }

    fun getType(): String = _type?.lowercase() ?: "note"
    fun setType(value: String) { _type = value }

    fun getBody(): String = _body ?: ""
    fun setBody(value: String) { _body = value }

    /** Severity bucket (blocker|major|minor|nit), or empty when the model omitted it. */
    fun getSeverity(): String = _severity?.lowercase() ?: ""
    fun setSeverity(value: String?) { _severity = value }

    /** Category (correctness|security|performance|tests|maintainability|style), or empty. */
    fun getCategory(): String = _category?.lowercase() ?: ""
    fun setCategory(value: String?) { _category = value }

    /** Model confidence (low|medium|high), or empty when omitted. */
    fun getConfidence(): String = _confidence?.lowercase() ?: ""
    fun setConfidence(value: String?) { _confidence = value }

    /** Short evidence/justification separate from the comment body, or empty. */
    fun getRationale(): String = _rationale ?: ""
    fun setRationale(value: String?) { _rationale = value }
}
