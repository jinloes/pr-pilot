package com.jinloes.prpilot.util

import java.io.File

actual object ProcessUtil {
    @JvmStatic
    actual fun findBinary(name: String, candidates: List<String>): String =
        candidates.firstOrNull { File(it).isFile } ?: name
}
