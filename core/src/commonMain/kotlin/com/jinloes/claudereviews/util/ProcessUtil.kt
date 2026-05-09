package com.jinloes.claudereviews.util

expect object ProcessUtil {
    fun findBinary(name: String, candidates: List<String>): String
}
