package com.jinloes.claudereviews.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.claudereviews.model.PullRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Persists the set of PR IDs that have already triggered a notification, so we don't spam the user
 * across restarts.
 *
 * <p>Stored as a JSON array of {@code "owner/repo#number"} strings at {@code
 * ~/.claude-reviews/seen-prs.json}.
 */
@Slf4j
public class SeenPRSet {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Set<String>> SET_TYPE = new TypeReference<>() {};

    private final Path file;
    private volatile Set<String> seen;

    /** True once the initial seed poll has been persisted (first run should not notify). */
    private volatile boolean seeded;

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
                Set<String> concurrent = ConcurrentHashMap.newKeySet();
                if (loaded != null) concurrent.addAll(loaded);
                seen = concurrent;
                seeded = true; // file existed → already seeded in a previous session
            } catch (Exception e) {
                log.warn("Corrupt seen-PR JSON; resetting", e);
                seen = ConcurrentHashMap.newKeySet();
                seeded = false;
            }
        } else {
            seen = ConcurrentHashMap.newKeySet();
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
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, MAPPER.writeValueAsString(seen), StandardCharsets.UTF_8);
            Files.move(
                    tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("Failed to save seen PR set", e);
        }
    }
}
