package com.jinloes.prpilot.model

import kotlin.jvm.JvmStatic

enum class ReviewProvider(val id: String, val displayName: String, val binary: String) {
    CLAUDE("claude", "Claude Code", "claude"),
    COPILOT("copilot", "GitHub Copilot", "copilot");

    companion object {
        @JvmStatic
        fun fromId(id: String?): ReviewProvider {
            if (id.isNullOrBlank()) return CLAUDE
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: CLAUDE
        }
    }
}