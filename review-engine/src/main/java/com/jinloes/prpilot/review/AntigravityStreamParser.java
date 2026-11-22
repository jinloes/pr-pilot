package com.jinloes.prpilot.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinloes.prpilot.model.ReviewResult;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses {@code agy --input-format stream-json --output-format stream-json} NDJSON events for both
 * review passes and streaming chat turns, and encodes user prompt messages for {@code agy}'s stdin.
 */
final class AntigravityStreamParser {

    private static final Logger log = LoggerFactory.getLogger(AntigravityService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String STATUS_GENERATING = "Generating review…";
    private static final String STATUS_PARSING = "Parsing review…";
    private static final String AGENTS_DIR_UNIX = "/.agents/";
    private static final String AGENTS_DIR_WIN = "\\.agents\\";

    private AntigravityStreamParser() {}

    /** Terminal error metadata reported in an {@code event="result"} NDJSON frame. */
    record ErrorInfo(String status, String message, String conversationId) {}

    /**
     * Encodes one headless {@code stream-json} user turn for {@code agy} stdin, terminated by LF.
     */
    static String encodeUserPromptLine(String prompt) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("event", "user");
        ObjectNode message = root.putObject("message");
        message.put("content", StringUtils.defaultString(prompt));
        return JSON.writeValueAsString(root) + "\n";
    }

    static ReviewResult parseStdoutFileToResult(
            File stdoutFile,
            Path isolatedRoot,
            String stderr,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk)
            throws IOException {
        String raw = parseStdoutFileToRaw(stdoutFile, isolatedRoot, stderr, onStatus, onChunk);
        try {
            return ReviewResultParser.parseReview(raw);
        } catch (Exception parseEx) {
            log.warn("Failed to parse Antigravity review JSON (output chars: {})", raw.length());
            throw new IOException("Failed to parse review JSON from Antigravity output.", parseEx);
        }
    }

    static String parseStdoutFileToRaw(
            File stdoutFile,
            Path isolatedRoot,
            String stderr,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk)
            throws IOException {
        long stdoutBytes = stdoutFile.length();
        try (BufferedReader reader =
                Files.newBufferedReader(stdoutFile.toPath(), StandardCharsets.UTF_8)) {
            ParsedStream parsed = parseReader(reader, isolatedRoot, onStatus, onChunk);
            if (parsed.error() != null && parsed.rawOutput().isBlank()) {
                String detail = StringUtils.trimToEmpty(parsed.error().message());
                throw new IOException(
                        "agy failed ("
                                + parsed.error().status()
                                + ")"
                                + (detail.isEmpty() ? "" : ": " + detail));
            }
            String raw = parsed.rawOutput();
            if (StringUtils.isBlank(raw)) {
                String eventSummary = formatEventSummary(parsed.eventsSeen());
                log.warn(
                        "Antigravity produced no review output. events: [{}], stdoutBytes: {}, stderrPresent: {}",
                        eventSummary,
                        stdoutBytes,
                        StringUtils.isNotBlank(stderr));
                throw new IOException(
                        "agy produced no output (events: "
                                + eventSummary
                                + ", stdout: "
                                + stdoutBytes
                                + "B)");
            }
            return raw;
        }
    }

    /**
     * Streams a live {@code agy} chat NDJSON stdout reader, forwarding incremental text chunks via
     * {@code onChunk} as {@code step_update} deltas arrive and returning the complete response.
     */
    static String parseChatStream(Reader reader, Path isolatedRoot, Consumer<String> onChunk)
            throws IOException {
        BiConsumer<String, String> chunkAdapter =
                onChunk == null
                        ? null
                        : (kind, chunk) -> {
                            if ("text".equals(kind) && !chunk.isEmpty()) {
                                onChunk.accept(chunk);
                            }
                        };
        ParsedStream parsed = parseReader(reader, isolatedRoot, null, chunkAdapter);
        if (parsed.error() != null && parsed.rawOutput().isBlank()) {
            String detail = StringUtils.trimToEmpty(parsed.error().message());
            throw new IOException(
                    "agy failed ("
                            + parsed.error().status()
                            + ")"
                            + (detail.isEmpty() ? "" : ": " + detail));
        }
        return parsed.rawOutput();
    }

    /**
     * Scans {@code stdoutFile} for an {@code event="result"} frame whose status is not {@code
     * SUCCESS}. Used when {@code agy} exits non-zero so the structured error message is surfaced
     * alongside {@code stderr}.
     */
    static ErrorInfo findErrorInfo(File stdoutFile) {
        if (stdoutFile == null || !stdoutFile.exists()) {
            return new ErrorInfo(null, null, null);
        }
        try (BufferedReader reader =
                Files.newBufferedReader(stdoutFile.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (StringUtils.isBlank(line)) continue;
                try {
                    JsonNode root = JSON.readTree(line);
                    if (!"result".equals(root.path("event").asText())) continue;
                    JsonNode result = root.path("result");
                    String status = result.path("status").asText("");
                    if (!status.isEmpty() && !"SUCCESS".equalsIgnoreCase(status)) {
                        return new ErrorInfo(
                                status,
                                result.path("error").asText(null),
                                result.path("conversation_id").asText(null));
                    }
                } catch (Exception ignored) {
                    // Ignore malformed NDJSON lines while scanning for terminal error info.
                }
            }
        } catch (Exception ignored) {
            // Non-fatal: stdout file unreadable.
        }
        return new ErrorInfo(null, null, null);
    }

    private record ParsedStream(
            String rawOutput, ErrorInfo error, Map<String, Integer> eventsSeen) {}

    private static ParsedStream parseReader(
            Reader reader,
            Path isolatedRoot,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk)
            throws IOException {
        BufferedReader buffered =
                reader instanceof BufferedReader br ? br : new BufferedReader(reader);
        StringBuilder resultBuffer = new StringBuilder();
        StringBuilder textBuffer = new StringBuilder();
        Map<String, Integer> eventsSeen = new LinkedHashMap<>();
        ErrorInfo errorInfo = null;

        String line;
        while ((line = buffered.readLine()) != null) {
            if (StringUtils.isBlank(line)) continue;
            try {
                JsonNode root = JSON.readTree(line);
                String eventType =
                        StringUtils.defaultIfBlank(root.path("event").asText(""), "unknown");
                eventsSeen.merge(eventType, 1, Integer::sum);
                switch (eventType) {
                    case "step_update" ->
                            handleStepUpdate(
                                    root.path("step_update"),
                                    isolatedRoot,
                                    onStatus,
                                    onChunk,
                                    textBuffer);
                    case "result" -> {
                        JsonNode result = root.path("result");
                        String status = result.path("status").asText("");
                        if ("SUCCESS".equalsIgnoreCase(status)) {
                            String response = result.path("response").asText("");
                            if (StringUtils.isNotBlank(response)) {
                                resultBuffer.setLength(0);
                                resultBuffer.append(response);
                            }
                            if (onStatus != null) {
                                onStatus.accept(STATUS_PARSING);
                            }
                        } else if (!status.isEmpty()) {
                            errorInfo =
                                    new ErrorInfo(
                                            status,
                                            result.path("error").asText(""),
                                            result.path("conversation_id").asText(null));
                        }
                    }
                    default -> {
                        // Ignore init and other informational frames.
                    }
                }
            } catch (Exception e) {
                log.warn(
                        "Antigravity stream event could not be parsed: {}",
                        e.getClass().getSimpleName());
            }
        }
        String raw = !resultBuffer.isEmpty() ? resultBuffer.toString() : textBuffer.toString();
        return new ParsedStream(raw, errorInfo, eventsSeen);
    }

    private static void handleStepUpdate(
            JsonNode step,
            Path isolatedRoot,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk,
            StringBuilder textBuffer) {
        if (!step.isObject()) return;
        String stepType = step.path("step_type").asText("");
        String state = step.path("state").asText("");
        switch (stepType) {
            case "tool" -> {
                if ("ACTIVE".equalsIgnoreCase(state) && onStatus != null) {
                    JsonNode toolInfo = step.path("tool_info");
                    String toolName =
                            StringUtils.defaultIfBlank(
                                    toolInfo.path("name").asText(""),
                                    step.path("tool_name").asText(""));
                    String status =
                            toolUseStatus(toolName, toolInfo.path("parameters"), isolatedRoot);
                    if (status != null) {
                        onStatus.accept(status);
                    }
                }
            }
            case "agent_response" -> {
                String thinkingDelta = step.path("thinking_delta").asText("");
                if (!thinkingDelta.isEmpty() && onChunk != null) {
                    onChunk.accept("thinking", thinkingDelta);
                }
                String textDelta = step.path("text_delta").asText("");
                if (!textDelta.isEmpty()) {
                    textBuffer.append(textDelta);
                    if (onChunk != null) {
                        onChunk.accept("text", textDelta);
                    } else if (onStatus != null && StringUtils.isNotBlank(textDelta)) {
                        onStatus.accept(STATUS_GENERATING);
                    }
                }
            }
            default -> {
                // Ignore user_input, checkpoint, and other step types.
            }
        }
    }

    static String toolUseStatus(String toolName, JsonNode parameters, Path isolatedRoot) {
        if (StringUtils.isBlank(toolName)) return null;
        List<String> renderedArgs = new ArrayList<>();
        if (parameters != null && parameters.isObject()) {
            for (Map.Entry<String, JsonNode> entry : parameters.properties()) {
                JsonNode val = entry.getValue();
                if (val == null || val.isNull() || val.isContainerNode()) continue;
                String renderedValue = val.asText();
                if (renderedValue.contains(AGENTS_DIR_UNIX)
                        || renderedValue.contains(AGENTS_DIR_WIN)) {
                    return null;
                }
                renderedValue = relativizeIsolatedPath(renderedValue, isolatedRoot);
                renderedArgs.add(entry.getKey() + "=" + renderedValue);
            }
        }
        return toolName + "(" + String.join(", ", renderedArgs) + ")";
    }

    private static String relativizeIsolatedPath(String value, Path isolatedRoot) {
        if (isolatedRoot == null || StringUtils.isBlank(value)) {
            return value;
        }
        try {
            Path root = isolatedRoot.toAbsolutePath().normalize();
            Path candidate = Path.of(value);
            if (candidate.isAbsolute()) {
                Path normalized = candidate.normalize();
                if (normalized.startsWith(root)) {
                    String rel = root.relativize(normalized).toString().replace('\\', '/');
                    return rel.isEmpty() ? "." : rel;
                }
            }
        } catch (Exception ignored) {
            // Value is not a filesystem path; leave unchanged.
        }
        return value;
    }

    private static String formatEventSummary(Map<String, Integer> eventsSeen) {
        if (eventsSeen.isEmpty()) return "none";
        return eventsSeen.entrySet().stream()
                .map(e -> e.getKey() + "×" + e.getValue())
                .reduce((a, b) -> a + ", " + b)
                .orElse("none");
    }
}
