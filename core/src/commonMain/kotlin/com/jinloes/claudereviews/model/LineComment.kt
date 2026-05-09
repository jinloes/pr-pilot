package com.jinloes.claudereviews.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * A single inline comment on a diff line. Mutable setters are preserved for Jackson
 * deserialization and for Java interop from intellij-plugin.
 */
@Serializable
class LineComment() {

    // Backing fields use distinct names to avoid JVM signature clashes with property accessors.
    @SerialName("file") @Suppress("PropertyName") internal var _file: String? = null
    @SerialName("line") @Suppress("PropertyName") internal var _line: Int = 0
    @SerialName("type") @Suppress("PropertyName") internal var _type: String? = null
    @SerialName("body") @Suppress("PropertyName") internal var _body: String? = null

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
}
