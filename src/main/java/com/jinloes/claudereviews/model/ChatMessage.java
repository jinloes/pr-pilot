package com.jinloes.claudereviews.model;

public record ChatMessage(Role role, String content) {
    public enum Role {
        USER,
        ASSISTANT
    }
}
