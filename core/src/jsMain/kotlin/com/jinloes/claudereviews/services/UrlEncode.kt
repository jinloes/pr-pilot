package com.jinloes.claudereviews.services

internal actual fun urlEncode(value: String): String =
    js("encodeURIComponent(value)").unsafeCast<String>()
