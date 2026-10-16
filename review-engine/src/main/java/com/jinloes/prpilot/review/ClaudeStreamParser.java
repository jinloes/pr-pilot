package com.jinloes.prpilot.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.model.ReviewResult;
import com.jinloes.prpilot.review.stream.ContentBlock;
import com.jinloes.prpilot.review.stream.StreamEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.LineIterator;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses the {@code --output-format stream-json} ndjson that {@link ClaudeService} redirects to a
 * temp file: the final review text, the error subtype and session ID that drive {@code --resume},
 * and the status and chunk callbacks raised as events stream in. Stateless.
 */
final class ClaudeStreamParser {

    // Logs under ClaudeService so log categories configured for it keep covering stream parsing.
    private static final Logger log = LoggerFactory.getLogger(ClaudeService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String STATUS_GENERATING = "Generating review…";
    private static final String STATUS_PARSING = "Parsing review…";

    private static final String CLAUDE_DIR_UNIX = "/.claude/";
    private static final String CLAUDE_DIR_WIN = "\\.claude\\";

    private ClaudeStreamParser() {}

    /** Holds the subtype and session ID from an error result event in the stream output. */
    record ErrorInfo(String subtype, String sessionId) {}

    /**
     * Reads the ndjson stdout file produced by a claude process and parses it into a {@link
     * ReviewResult}. Package-private for unit testing without spawning a real process.
     */
    static ReviewResult parseStdoutFileToResult(
            File stdoutFile,
            String stderr,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk)
            throws IOException {
        String raw = parseStdoutFileToRaw(stdoutFile, stderr, onStatus, onChunk);
        try {
            return ReviewResultParser.parseReview(raw);
        } catch (Exception parseEx) {
            log.warn("Failed to parse Claude review JSON (output chars: {})", raw.length());
            throw new IOException("Failed to parse review JSON from Claude output.", parseEx);
        }
    }

    static String parseStdoutFileToRaw(
            File stdoutFile,
            String stderr,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk)
            throws IOException {
        long stdoutBytes = stdoutFile.length();
        StringBuilder resultBuffer = new StringBuilder();
        StringBuilder textBuffer = new StringBuilder();
        Map<String, Integer> eventTypeSeen = new LinkedHashMap<>();
        try (LineIterator it =
                IOUtils.lineIterator(
                        Files.newInputStream(stdoutFile.toPath()), StandardCharsets.UTF_8)) {
            while (it.hasNext()) {
                String line = it.next();
                if (StringUtils.isBlank(line)) continue;
                try {
                    StreamEvent event = JSON.readValue(line, StreamEvent.class);
                    String eventType = StringUtils.defaultString(event.getType(), "unknown");
                    eventTypeSeen.merge(eventType, 1, Integer::sum);
                    handleStreamEvent(event, onStatus, onChunk, resultBuffer, textBuffer);
                } catch (Exception e) {
                    log.warn(
                            "Claude stream event could not be parsed: {}",
                            e.getClass().getSimpleName());
                }
            }
        }

        String raw = !resultBuffer.isEmpty() ? resultBuffer.toString() : textBuffer.toString();
        if (StringUtils.isBlank(raw)) {
            String eventSummary =
                    eventTypeSeen.isEmpty()
                            ? "none"
                            : eventTypeSeen.entrySet().stream()
                                    .map(e -> e.getKey() + "×" + e.getValue())
                                    .reduce((a, b) -> a + ", " + b)
                                    .orElse("none");
            log.warn(
                    "Claude produced no review output. events: [{}], stdoutBytes: {}, stderrPresent: {}",
                    eventSummary,
                    stdoutBytes,
                    StringUtils.isNotBlank(stderr));
            throw new IOException(
                    "claude produced no output (events: "
                            + eventSummary
                            + ", stdout: "
                            + stdoutBytes
                            + "B)");
        }
        return raw;
    }

    /**
     * Scans {@code stdoutFile} for a result event with {@code isError == true} and returns its
     * subtype and session_id. Returns an {@link ErrorInfo} with null fields if no such event is
     * found or the file does not exist. Package-private for unit testing.
     */
    static ErrorInfo findErrorInfo(File stdoutFile) {
        if (!stdoutFile.exists()) return new ErrorInfo(null, null);
        try (LineIterator it =
                IOUtils.lineIterator(
                        Files.newInputStream(stdoutFile.toPath()), StandardCharsets.UTF_8)) {
            while (it.hasNext()) {
                String line = it.next();
                if (StringUtils.isBlank(line)) continue;
                try {
                    StreamEvent event = JSON.readValue(line, StreamEvent.class);
                    if (event.isError()) {
                        return new ErrorInfo(event.getSubtype(), event.getSessionId());
                    }
                } catch (Exception e) {
                    // Skip corrupt lines.
                }
            }
        } catch (Exception e) {
            // Non-fatal: file unreadable.
        }
        return new ErrorInfo(null, null);
    }

    private static void handleStreamEvent(
            StreamEvent event,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk,
            StringBuilder resultBuffer,
            StringBuilder textBuffer) {
        switch (StringUtils.defaultString(event.getType())) {
            case "assistant" -> {
                if (event.getMessage() != null && event.getMessage().getContent() != null) {
                    for (ContentBlock block : event.getMessage().getContent()) {
                        handleContentBlock(block, onStatus, onChunk, textBuffer);
                    }
                }
            }
            case "result" -> {
                if (!event.isError()
                        && (event.getSubtype() == null || "success".equals(event.getSubtype()))) {
                    String result = event.getResult();
                    if (result != null) {
                        if (StringUtils.isNotBlank(result)) {
                            resultBuffer.append(result);
                        }
                        onStatus.accept(STATUS_PARSING);
                    }
                }
            }
            default -> {
                // Ignore other event types.
            }
        }
    }

    static void handleContentBlock(
            ContentBlock block, Consumer<String> onStatus, BiConsumer<String, String> onChunk) {
        handleContentBlock(block, onStatus, onChunk, null);
    }

    static void handleContentBlock(
            ContentBlock block,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk,
            StringBuilder textBuffer) {
        log.debug("stream content block: type={}", block.getType());
        switch (StringUtils.defaultString(block.getType())) {
            case "tool_use" -> {
                String status =
                        toolUseStatus(
                                StringUtils.defaultString(block.getName()),
                                block.getInput() != null ? block.getInput() : Map.of());
                if (status != null) onStatus.accept(status);
            }
            case "text" -> {
                String text = StringUtils.defaultString(block.getText());
                if (StringUtils.isNotBlank(text) && textBuffer != null) {
                    textBuffer.append(text);
                }
                if (onChunk != null && StringUtils.isNotBlank(text)) {
                    onChunk.accept("text", text);
                } else {
                    onStatus.accept(STATUS_GENERATING);
                }
            }
            case "thinking" -> {
                String thinking = StringUtils.defaultString(block.getThinking());
                if (onChunk != null && StringUtils.isNotBlank(thinking)) {
                    onChunk.accept("thinking", thinking);
                }
            }
            default -> {
                // Ignore other block types.
            }
        }
    }

    /**
     * Formats a tool-use event as a compact CLI-style label, e.g. {@code
     * github/get_file_contents(owner=foo, repo=bar, path=CLAUDE.md)}.
     *
     * <p>Returns null for Claude Code's internal tool-result temp files, which are an
     * implementation detail and not meaningful to show.
     */
    static String toolUseStatus(String toolName, Map<String, Object> input) {
        for (String key : List.of("path", "file_path", "filename")) {
            Object value = input.get(key);
            if (value instanceof String stringValue) {
                if (stringValue.contains(CLAUDE_DIR_UNIX) || stringValue.contains(CLAUDE_DIR_WIN)) {
                    return null;
                }
            }
        }
        String display = Strings.CS.removeStart(toolName, "mcp__").replace("__", "/");
        String args =
                input.entrySet().stream()
                        .filter(e -> isScalar(e.getValue()))
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("");
        return display + "(" + args + ")";
    }

    private static boolean isScalar(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean;
    }
}
