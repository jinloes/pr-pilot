package com.jinloes.claudereviews.services

/** Bridges suspend functions into synchronous calls for platform-specific callers. */
internal expect fun <T> runBlockingCompat(block: suspend () -> T): T
