package com.jinloes.prpilot.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.model.LineComment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records what a reviewer did with each generated comment, so ambiguous prompt/pipeline changes can
 * be evaluated after the fact rather than argued about.
 *
 * <p>This is instrumentation, not a feature: it improves no single review. Every failure here is
 * logged and swallowed — a metrics write must never break a review submission.
 *
 * <p>Engine-owned by construction. Both hosts had already drifted into holding *opposite* halves of
 * the data (IntelliJ overwrites the generated review with the edited one; VS Code never records the
 * edits), which is exactly the divergence host-local implementations produce.
 */
public final class ReviewOutcomeLog {

    private static final Logger log = LoggerFactory.getLogger(ReviewOutcomeLog.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Stop appending past this size. An append-only file that nothing prunes will otherwise grow
     * without bound on a user's machine; losing the tail of a metrics log is a far smaller harm.
     */
    static final long MAX_LOG_BYTES = 5L * 1024 * 1024;

    /** What the reviewer did with a comment between generation and submission. */
    public enum Outcome {
        /** Submitted with the body unchanged. */
        KEPT,
        /** Submitted at the same location with a different body. */
        EDITED,
        /** Generated but not submitted. */
        DELETED,
        /**
         * Submitted but never generated — the reviewer wrote it. Not in the original three
         * outcomes, but it is the only signal in this log for what the model *missed*, and it falls
         * out of the same diff for free.
         */
        ADDED
    }

    /** Identifies the pipeline that produced a review, so outcomes can be segmented by it. */
    public record Metadata(String promptVersion, String provider, String model) {}

    /**
     * One JSONL line. Deliberately carries no file path or comment text — only an identity hash.
     */
    public record OutcomeRecord(
            String recordedAt,
            String promptVersion,
            String provider,
            String model,
            String commentFingerprint,
            String outcome,
            String type,
            String severity,
            String category,
            String confidence) {}

    private final Path logFile;

    public ReviewOutcomeLog() {
        this(Path.of(System.getProperty("user.home"), ".pr-pilot", "review-outcomes.jsonl"));
    }

    /**
     * Writes to an explicit location instead of {@code ~/.pr-pilot}. Public because callers in
     * other packages (and their tests) must be able to redirect it — {@code AGENTS.md} forbids
     * tests writing to {@code ~/.pr-pilot}.
     */
    public ReviewOutcomeLog(Path logFile) {
        this.logFile = Objects.requireNonNull(logFile);
    }

    /**
     * Classifies each generated comment against what was actually submitted, and appends the
     * result. Never throws.
     *
     * @return how many outcome records were classified (whether or not the append succeeded)
     */
    public int record(List<LineComment> generated, List<LineComment> submitted, Metadata metadata) {
        try {
            List<OutcomeRecord> records = classify(generated, submitted, metadata);
            append(records);
            return records.size();
        } catch (RuntimeException exception) {
            log.warn("Failed to record review outcomes: {}", exception.getMessage());
            return 0;
        }
    }

    /**
     * Diffs generated against submitted comments.
     *
     * <p>Identity is {@code (file, line)} — {@link LineComment} has no id, and a reviewer editing a
     * comment's text keeps its location. Within one location, an exact body match is preferred over
     * an arbitrary one so a partially-edited cluster reports the smallest honest number of edits.
     */
    List<OutcomeRecord> classify(
            List<LineComment> generated, List<LineComment> submitted, Metadata metadata) {
        String recordedAt = Instant.now().toString();
        List<OutcomeRecord> records = new ArrayList<>();
        Map<String, Deque<LineComment>> remaining = new HashMap<>();
        for (LineComment comment : submitted == null ? List.<LineComment>of() : submitted) {
            if (comment == null) continue;
            remaining.computeIfAbsent(locationKey(comment), key -> new ArrayDeque<>()).add(comment);
        }

        for (LineComment comment : generated == null ? List.<LineComment>of() : generated) {
            if (comment == null) continue;
            Deque<LineComment> bucket = remaining.get(locationKey(comment));
            Outcome outcome;
            if (bucket == null || bucket.isEmpty()) {
                outcome = Outcome.DELETED;
            } else if (removeFirstWithSameBody(bucket, comment)) {
                outcome = Outcome.KEPT;
            } else {
                bucket.poll();
                outcome = Outcome.EDITED;
            }
            records.add(toRecord(recordedAt, metadata, comment, outcome));
        }

        for (Deque<LineComment> bucket : remaining.values()) {
            for (LineComment comment : bucket) {
                records.add(toRecord(recordedAt, metadata, comment, Outcome.ADDED));
            }
        }
        return records;
    }

    /**
     * Appends one JSON object per record. Uses {@code O_APPEND} rather than the tmp+atomic-move
     * pattern the whole-file JSON stores use: each line is a self-contained record well under a
     * page, and rewriting a growing log on every submit would not scale.
     */
    void append(List<OutcomeRecord> records) {
        if (records == null || records.isEmpty()) return;
        try {
            if (Files.exists(logFile) && Files.size(logFile) >= MAX_LOG_BYTES) {
                log.warn(
                        "Review outcome log at {} reached {} bytes; not appending",
                        logFile,
                        MAX_LOG_BYTES);
                return;
            }
            Files.createDirectories(logFile.getParent());
            StringBuilder lines = new StringBuilder();
            for (OutcomeRecord record : records) {
                lines.append(MAPPER.writeValueAsString(record)).append('\n');
            }
            Files.writeString(
                    logFile,
                    lines.toString(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException exception) {
            log.warn("Failed to append review outcomes to {}: {}", logFile, exception.getMessage());
        }
    }

    private static OutcomeRecord toRecord(
            String recordedAt, Metadata metadata, LineComment comment, Outcome outcome) {
        Metadata safe = metadata == null ? new Metadata("", "", "") : metadata;
        return new OutcomeRecord(
                recordedAt,
                StringUtils.defaultString(safe.promptVersion()),
                StringUtils.defaultString(safe.provider()),
                StringUtils.defaultString(safe.model()),
                fingerprint(comment),
                outcome.name().toLowerCase(Locale.ROOT),
                comment.getType(),
                comment.getSeverity(),
                comment.getCategory(),
                comment.getConfidence());
    }

    private static String locationKey(LineComment comment) {
        return comment.getFile() + "\u0000" + comment.getLine();
    }

    /**
     * Removes exactly one body-identical comment from the bucket. Removing every match would
     * under-count when a reviewer keeps two identical comments at one location.
     */
    private static boolean removeFirstWithSameBody(Deque<LineComment> bucket, LineComment target) {
        for (Iterator<LineComment> iterator = bucket.iterator(); iterator.hasNext(); ) {
            if (sameBody(iterator.next(), target)) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    private static boolean sameBody(LineComment left, LineComment right) {
        return normalize(left.getBody()).equals(normalize(right.getBody()));
    }

    /**
     * Collapses whitespace so a reflow or trailing-space change does not read as a substantive
     * edit. Case is preserved — a capitalization change is a real edit.
     */
    private static String normalize(String body) {
        return StringUtils.normalizeSpace(StringUtils.defaultString(body));
    }

    /**
     * Stable identity for a comment across runs: the location plus normalized body. Correlating the
     * same finding between two prompt versions is the whole point, so this must not include a
     * timestamp or any per-run value.
     */
    static String fingerprint(LineComment comment) {
        String material =
                comment.getFile() + "\n" + comment.getLine() + "\n" + normalize(comment.getBody());
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 is mandated by the platform; treat absence as unreachable rather than
            // degrading identity to something unstable.
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
