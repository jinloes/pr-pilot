package com.jinloes.claudereviews.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Lightweight local index recording which PRs have a pending (draft) review on GitHub. Stored as
 * JSON at {@code ~/.claude-reviews/pending-prs.json}.
 *
 * <p>This is intentionally minimal — it holds only enough data to populate the "Load Draft" list.
 * The actual review content is always fetched live from GitHub.
 */
@Slf4j
public class PendingReviewIndex {

    public record Entry(String owner, String repo, int number, String title, String savedAt) {
        public String displayLabel() {
            return owner
                    + "/"
                    + repo
                    + " #"
                    + number
                    + " \u2014 "
                    + title
                    + "  ("
                    + savedAt.replace("T", " ").substring(0, Math.min(16, savedAt.length()))
                    + ")";
        }
    }

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final TypeReference<List<Entry>> LIST_TYPE = new TypeReference<>() {};

    private final Path indexFile;

    public PendingReviewIndex() {
        indexFile = Path.of(System.getProperty("user.home"), ".claude-reviews", "pending-prs.json");
    }

    PendingReviewIndex(java.nio.file.Path indexFile) {
        this.indexFile = indexFile;
    }

    public List<Entry> list() {
        if (!Files.exists(indexFile)) return new ArrayList<>();
        try {
            String json = Files.readString(indexFile, StandardCharsets.UTF_8);
            List<Entry> entries = MAPPER.readValue(json, LIST_TYPE);
            return entries != null ? entries : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public void add(String owner, String repo, int number, String title) {
        List<Entry> entries = list();
        entries.removeIf(
                e -> e.owner().equals(owner) && e.repo().equals(repo) && e.number() == number);
        entries.add(
                0,
                new Entry(
                        owner,
                        repo,
                        number,
                        title,
                        LocalDateTime.now().toString().substring(0, 16)));
        save(entries);
    }

    public void remove(String owner, String repo, int number) {
        List<Entry> entries = list();
        entries.removeIf(
                e -> e.owner().equals(owner) && e.repo().equals(repo) && e.number() == number);
        save(entries);
    }

    private void save(List<Entry> entries) {
        try {
            Files.createDirectories(indexFile.getParent());
            Files.writeString(
                    indexFile, MAPPER.writeValueAsString(entries), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to save pending review index", e);
        }
    }
}
