package com.jinloes.claudereviews.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.claudereviews.model.PullRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Persists the set of PR IDs that have already triggered a notification, so we don't spam the user
 * across restarts.
 *
 * <p>Stored as a JSON array of {@code "owner/repo#number"} strings at {@code
 * ~/.claude-reviews/seen-prs.json}.
 */
public class SeenPRSet {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Set<String>> SET_TYPE = new TypeReference<>() {};

    private final Path file;
    private Set<String> seen;

    /** True once the initial seed poll has been persisted (first run should not notify). */
    private boolean seeded;

    public SeenPRSet() {
        file = Path.of(System.getProperty("user.home"), ".claude-reviews", "seen-prs.json");
        load();
    }

    SeenPRSet(java.nio.file.Path file) {
        this.file = file;
        load();
    }

    private void load() {
        if (Files.exists(file)) {
            try {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                Set<String> loaded = MAPPER.readValue(json, SET_TYPE);
                seen = loaded != null ? loaded : new HashSet<>();
                seeded = true; // file existed → already seeded in a previous session
            } catch (Exception e) {
                seen = new HashSet<>();
                seeded = false;
            }
        } else {
            seen = new HashSet<>();
            seeded = false;
        }
    }

    private static String key(PullRequest pr) {
        return pr.getOwner() + "/" + pr.getRepo() + "#" + pr.getNumber();
    }

    public boolean isSeeded() {
        return seeded;
    }

    public boolean contains(PullRequest pr) {
        return seen.contains(key(pr));
    }

    public void add(PullRequest pr) {
        seen.add(key(pr));
    }

    public void markSeeded() {
        seeded = true;
    }

    public void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, MAPPER.writeValueAsString(seen), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }
}
