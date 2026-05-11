package com.jinloes.prpilot.services

internal actual fun <T> runBlockingCompat(block: suspend () -> T): T =
    throw UnsupportedOperationException(
        "Blocking calls are not supported in JS — use the suspend API instead"
    )
