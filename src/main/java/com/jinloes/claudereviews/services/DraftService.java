package com.jinloes.claudereviews.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jinloes.claudereviews.model.PullRequest;
import com.jinloes.claudereviews.model.ReviewDraft;
import com.jinloes.claudereviews.model.ReviewResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

/** Saves and loads review drafts as JSON files under {@code ~/.claude-reviews/drafts/}. */
@Slf4j
public class DraftService {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss");
    private static final DateTimeFormatter DISPLAY_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Path draftsDir;

    public DraftService() {
        this(Path.of(System.getProperty("user.home"), ".claude-reviews", "drafts"));
    }

    DraftService(Path draftsDir) {
        this.draftsDir = draftsDir;
    }

    public void save(PullRequest pr, ReviewResult review, String rawDiff) throws IOException {
        Files.createDirectories(draftsDir);

        LocalDateTime now = LocalDateTime.now();
        ReviewDraft draft = new ReviewDraft();
        draft.owner = pr.getOwner();
        draft.repo = pr.getRepo();
        draft.number = pr.getNumber();
        draft.title = pr.getTitle();
        draft.author = pr.getAuthor();
        draft.prBody = pr.getBody();
        draft.rawDiff = rawDiff;
        draft.savedAt = now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        draft.review = review;

        String filename =
                String.format(
                        "%s__%s__%d__%s.json",
                        pr.getOwner(), pr.getRepo(), pr.getNumber(), now.format(TIMESTAMP_FMT));
        MAPPER.writeValue(draftsDir.resolve(filename).toFile(), draft);
    }

    /** Returns all saved drafts, newest first. */
    public List<DraftEntry> listDrafts() throws IOException {
        if (!Files.isDirectory(draftsDir)) {
            return List.of();
        }
        try (var paths = Files.list(draftsDir)) {
            return paths.filter(path -> path.toString().endsWith(".json"))
                    .map(this::tryReadDraft)
                    .flatMap(Optional::stream)
                    .sorted(
                            Comparator.comparing(
                                    entry -> entry.draft().savedAt,
                                    Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
        }
    }

    public void delete(DraftEntry entry) throws IOException {
        Files.deleteIfExists(entry.path());
    }

    public static String formatSavedAt(String iso) {
        if (StringUtils.isBlank(iso)) {
            return "";
        }
        try {
            return LocalDateTime.parse(iso).format(DISPLAY_FMT);
        } catch (Exception ignored) {
            return iso;
        }
    }

    private Optional<DraftEntry> tryReadDraft(Path path) {
        try {
            ReviewDraft draft = MAPPER.readValue(path.toFile(), ReviewDraft.class);
            return Optional.of(new DraftEntry(path, draft));
        } catch (IOException e) {
            log.warn("Failed to read draft {}: {}", path, e.getMessage());
            return Optional.empty();
        }
    }

    public record DraftEntry(Path path, ReviewDraft draft) {
        public String displayLabel() {
            return String.format("%s  (%s)", draft.label(), formatSavedAt(draft.savedAt));
        }
    }
}
