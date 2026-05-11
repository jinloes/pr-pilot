package com.jinloes.prpilot.util

@JsModule("fs")
@JsNonModule
private external object Fs {
    fun existsSync(path: String): Boolean
    fun statSync(path: String): dynamic
}

actual object ProcessUtil {
    actual fun findBinary(name: String, candidates: List<String>): String =
        candidates.firstOrNull { path ->
            try {
                if (!Fs.existsSync(path)) return@firstOrNull false
                val stat = Fs.statSync(path)
                // stat.isFile() is a function in Node.js
                stat.isFile().unsafeCast<Boolean>()
            } catch (_: Throwable) {
                false
            }
        } ?: name
}
