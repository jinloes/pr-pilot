package com.jinloes.prpilot.model;

/** Review provider CLI backend: Claude Code, GitHub Copilot, or Antigravity. */
public enum ReviewProvider {
    CLAUDE("claude", "Claude Code", "claude"),
    COPILOT("copilot", "GitHub Copilot", "copilot"),
    ANTIGRAVITY("antigravity", "Antigravity", "agy");

    private final String id;
    private final String displayName;
    private final String binary;

    ReviewProvider(String id, String displayName, String binary) {
        this.id = id;
        this.displayName = displayName;
        this.binary = binary;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getBinary() {
        return binary;
    }

    /**
     * Case-insensitive lookup by id, falling back to {@link #CLAUDE} for null/blank/unmatched ids.
     */
    public static ReviewProvider fromId(String id) {
        if (id == null || id.isBlank()) {
            return CLAUDE;
        }
        for (ReviewProvider provider : values()) {
            if (provider.id.equalsIgnoreCase(id)) {
                return provider;
            }
        }
        return CLAUDE;
    }
}
