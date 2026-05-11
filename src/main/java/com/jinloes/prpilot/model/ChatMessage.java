package com.jinloes.prpilot.model;

public record ChatMessage(Role role, String content) {
    public enum Role {
        USER,
        ASSISTANT
    }
}
