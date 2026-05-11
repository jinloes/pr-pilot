package com.jinloes.prpilot.services

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal actual fun urlEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)
