package com.jinloes.prpilot.util

expect object ProcessUtil {
    fun findBinary(name: String, candidates: List<String>): String
}
