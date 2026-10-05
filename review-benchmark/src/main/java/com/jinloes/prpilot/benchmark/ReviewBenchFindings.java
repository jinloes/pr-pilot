package com.jinloes.prpilot.benchmark;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.model.LineComment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;

/**
 * PR Pilot's line comments in ReviewBench's normalized findings format (see {@code
 * docs/JUDGING_INPUT.md} in the ReviewBench repository).
 */
record ReviewBenchFindings(Pr pr, String agent, List<Item> findings, Usage usage) {

    static final String AGENT = "pr-pilot";
    private static final String NOTE = "note";

    record Pr(String repo, @JsonProperty("pr_number") int prNumber, String base, String head) {}

    record Item(
            String producer,
            String file,
            @JsonProperty("start_line") int startLine,
            @JsonProperty("end_line") int endLine,
            String message) {}

    record Usage(@JsonProperty("time_in_ms") long timeInMs) {}

    /**
     * Converts review comments. Notes are dropped unless {@code includeNotes}: they are
     * observations rather than defects, and the judge scores every submitted finding as a true or
     * false positive. File-level comments (line 0) are anchored to line 1 because ReviewBench
     * requires a line.
     */
    static ReviewBenchFindings of(
            ReviewBenchTask task,
            List<LineComment> comments,
            long timeMillis,
            boolean includeNotes) {
        List<Item> items = new ArrayList<>();
        for (LineComment comment : comments == null ? List.<LineComment>of() : comments) {
            if (!includeNotes && NOTE.equals(comment.getType())) continue;
            String file = normalizePath(comment.getFile());
            String message = StringUtils.strip(comment.getBody());
            if (file.isEmpty() || StringUtils.isEmpty(message)) continue;
            int line = Math.max(1, comment.getLine());
            items.add(new Item(AGENT, file, line, line, message));
        }
        return new ReviewBenchFindings(
                new Pr(task.repo(), task.prNumber(), task.base(), task.head()),
                AGENT,
                List.copyOf(items),
                new Usage(Math.max(0, timeMillis)));
    }

    /** Repository-relative, forward slashes, no leading {@code ./} or {@code /}. */
    static String normalizePath(String path) {
        String normalized = StringUtils.defaultString(path).strip().replace('\\', '/');
        while (normalized.startsWith("./") || normalized.startsWith("/")) {
            normalized = normalized.substring(normalized.startsWith("./") ? 2 : 1);
        }
        return normalized;
    }

    /** Writes atomically so an interrupted run never leaves a half-written file to resume past. */
    void write(Path file, ObjectMapper mapper) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), this);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
