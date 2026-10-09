package com.jinloes.prpilot.sidecar.pr;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encodes/decodes PR Pilot review metadata embedded in a GitHub review body, falling back to GitHub
 * inline comments when absent or invalid.
 */
public final class DraftReviewCodec {
    private static final Logger log = LoggerFactory.getLogger(DraftReviewCodec.class);
    private static final String VERDICT_TAG = "<!-- claude-verdict: ";
    private static final String SUMMARY_TAG = "<!-- claude-summary: ";
    private static final String COMMENTS_TAG = "<!-- claude-comments: ";
    private static final String PAYLOAD_TAG = "<!-- pr-pilot-review:v1:";
    private static final String TAG_END = " -->";
    private static final String DETACHED_COMMENTS_HEADER =
            "**Comments not attached inline (invalid diff positions):**";
    private static final Set<String> VALID_VERDICTS =
            Set.of("APPROVE", "REQUEST_CHANGES", "COMMENT");
    private static final Set<String> VALID_TYPES = Set.of("issue", "suggestion", "note");
    static final int MAX_SUGGESTION_LINES = 6;
    static final int MAX_SUGGESTION_CHARS = 1_000;
    private final ObjectMapper mapper;

    DraftReviewCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    DecodedReview decode(String body, List<ApiComment> apiComments) {
        String safeBody = body == null ? "" : body;
        if (safeBody.startsWith(PAYLOAD_TAG)) {
            try {
                int end = safeBody.indexOf(TAG_END, PAYLOAD_TAG.length());
                if (end < 0) throw new IllegalArgumentException("Unterminated review payload");
                byte[] json =
                        Base64.getDecoder()
                                .decode(safeBody.substring(PAYLOAD_TAG.length(), end).trim());
                EncodedReview payload = mapper.readValue(json, EncodedReview.class);
                if (payload == null
                        || payload.summary() == null
                        || !VALID_VERDICTS.contains(payload.verdict())
                        || payload.comments() == null
                        || payload.comments().stream().anyMatch(c -> !validEncodedComment(c))) {
                    throw new IllegalArgumentException("Invalid embedded review payload");
                }
                return new DecodedReview(
                        payload.summary(),
                        payload.verdict(),
                        payload.comments().stream().map(DraftReviewCodec::toLineComment).toList(),
                        false);
            } catch (Exception exception) {
                log.debug(
                        "Embedded draft review payload was invalid; using GitHub comments ({})",
                        exception.getClass().getName());
                return fromApiComments("", "COMMENT", apiComments);
            }
        }

        String verdict = tag(safeBody, VERDICT_TAG, "COMMENT");
        boolean validVerdict = VALID_VERDICTS.contains(verdict);
        String summary = tag(safeBody, SUMMARY_TAG, "");
        String encoded = tag(safeBody, COMMENTS_TAG, null);
        if (encoded != null) {
            try {
                List<EncodedComment> comments = mapper.readValue(encoded, new TypeReference<>() {});
                if (!validVerdict || comments.stream().anyMatch(c -> !validEncodedComment(c))) {
                    throw new IllegalArgumentException("Invalid embedded review metadata");
                }
                List<LineComment> lines = new ArrayList<>();
                for (EncodedComment c : comments) lines.add(toLineComment(c));
                return new DecodedReview(summary, verdict, lines, false);
            } catch (Exception exception) {
                log.debug(
                        "Embedded draft review metadata was invalid; using GitHub comments ({})",
                        exception.getClass().getName());
            }
        }
        if (!validVerdict) {
            verdict = "COMMENT";
        }
        return fromApiComments(summary, verdict, apiComments);
    }

    private static DecodedReview fromApiComments(
            String summary, String verdict, List<ApiComment> apiComments) {
        List<LineComment> lines = new ArrayList<>();
        for (ApiComment c : apiComments) {
            String text = c.body() == null ? "" : c.body();
            String type = "note";
            if (text.matches("^\\[[A-Z]+]\\s+.*")) {
                int end = text.indexOf(']');
                type = text.substring(1, end).toLowerCase();
                text = text.substring(end + 1).trim();
            }
            lines.add(
                    new LineComment(
                            c.path() == null ? "" : c.path(),
                            c.line() == null
                                    ? (c.originalLine() == null ? 0 : c.originalLine())
                                    : c.line(),
                            type,
                            text,
                            null,
                            null,
                            null,
                            null,
                            null));
        }
        return new DecodedReview(summary, verdict, lines, true);
    }

    private static LineComment toLineComment(EncodedComment comment) {
        return new LineComment(
                comment.f(),
                comment.l(),
                comment.t(),
                comment.b(),
                comment.s(),
                comment.c(),
                comment.cf(),
                comment.r(),
                comment.sg());
    }

    private String tag(String body, String start, String fallback) {
        int index = body.indexOf(start);
        if (index < 0) return fallback;
        int end = body.indexOf(TAG_END, index + start.length());
        return end < 0 ? fallback : body.substring(index + start.length(), end).trim();
    }

    /**
     * Returns the text GitHub renders for a pending review body, with PR Pilot's hidden metadata
     * removed: the v1 payload (always leading in encoded bodies) and the first occurrence of each
     * legacy tag. Everything else (General Notes, detached-comment sections, reviewer-added or
     * imported text) is kept verbatim. Both formats keep the summary only in hidden metadata, so
     * nothing visible is duplicated. An unterminated tag is left untouched; the encoder cannot
     * produce one because base64 never contains the terminator and legacy values escaped it.
     */
    String visibleBody(String pendingBody) {
        String visible = pendingBody == null ? "" : pendingBody;
        for (String metadataTag : List.of(PAYLOAD_TAG, SUMMARY_TAG, VERDICT_TAG, COMMENTS_TAG)) {
            visible = withoutFirstTag(visible, metadataTag);
        }
        return visible.strip();
    }

    private static String withoutFirstTag(String body, String start) {
        int index = body.indexOf(start);
        if (index < 0) return body;
        int end = body.indexOf(TAG_END, index + start.length());
        if (end < 0) return body;
        return body.substring(0, index) + body.substring(end + TAG_END.length());
    }

    /**
     * Encodes a summary/verdict/comment set into one versioned, reversibly encoded PR Pilot
     * payload, plus a trailing "General Notes" section for comments with no file/line.
     */
    String encodeBody(String summary, String verdict, List<LineComment> lineComments) {
        List<EncodedComment> encoded = new ArrayList<>();
        for (LineComment c : lineComments) {
            encoded.add(
                    new EncodedComment(
                            c.file(),
                            c.line(),
                            c.type(),
                            c.body(),
                            blankToNull(c.severity()),
                            blankToNull(c.category()),
                            blankToNull(c.confidence()),
                            blankToNull(c.rationale()),
                            blankToNull(c.suggestedChange())));
        }
        String serialized;
        try {
            serialized =
                    Base64.getEncoder()
                            .encodeToString(
                                    mapper.writeValueAsBytes(
                                            new EncodedReview(
                                                    summary == null ? "" : summary,
                                                    verdict,
                                                    encoded)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to encode draft review metadata", exception);
        }
        StringBuilder sb = new StringBuilder(PAYLOAD_TAG).append(serialized).append(TAG_END);

        List<LineComment> general =
                lineComments.stream()
                        .filter(c -> c.file() == null || c.file().isBlank() || c.line() <= 0)
                        .toList();
        if (!general.isEmpty()) {
            sb.append("\n\n**General Notes:**");
            for (LineComment c : general) sb.append("\n- ").append(c.body());
        }
        return sb.toString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Builds the deduplicated inline-comment array from a review's line comments, excluding any
     * pre-known {@code orphans} (comments the caller already determined have no valid diff position
     * — those belong in the body's "Comments not attached inline" section instead).
     *
     * <p>Two different keys are used here on purpose, and they are not interchangeable:
     *
     * <ul>
     *   <li>{@link #orphanKey} matches a comment against the caller's orphan list. Both lists hold
     *       the same objects, so it keys on the raw fields including {@code type} — it is an
     *       identity check, not a payload concern.
     *   <li>{@code dedupeKey} collapses comments that would produce identical GitHub comments. The
     *       posted payload is only {@code path}/{@code line}/{@code side}/{@code body}, so {@code
     *       type} is deliberately excluded: two findings differing only in type would post as two
     *       byte-identical comments on the same line. It also keys on the normalized path, since
     *       {@code b/Foo.java} and {@code Foo.java} post to the same place.
     * </ul>
     *
     * <p>The webview's chunk-merge key ({@code file|line|type|body}) intentionally differs again —
     * it dedupes model <em>findings</em>, where the type is part of the finding's identity.
     */
    ArrayNode buildCommentArray(List<LineComment> lineComments, List<LineComment> orphans) {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> orphanKeys = new HashSet<>();
        for (LineComment o : orphans) orphanKeys.add(orphanKey(o));

        ArrayNode result = mapper.createArrayNode();
        for (LineComment c : lineComments) {
            String file = normalizePath(c.file());
            if (file.isBlank() || c.line() <= 0 || c.body() == null || c.body().isBlank()) continue;
            if (orphanKeys.contains(orphanKey(c))) continue;
            String body = postedBody(c);
            String dedupeKey = file + "\u0000" + c.line() + "\u0000" + body;
            if (!seen.add(dedupeKey)) continue;
            ObjectNode obj = mapper.createObjectNode();
            obj.put("path", file);
            obj.put("line", c.line());
            obj.put("side", "RIGHT");
            obj.put("body", body);
            result.add(obj);
        }
        return result;
    }

    /**
     * The inline-comment body GitHub receives: the comment body, followed by a {@code suggestion}
     * block when the comment carries a publishable suggested change. Every key that matches a
     * comment against what was posted or dropped must use this, not the raw body.
     */
    static String postedBody(LineComment c) {
        String body = c.body() == null ? "" : c.body();
        String suggestion = publishableSuggestion(c.suggestedChange());
        return suggestion == null ? body : body + "\n\n```suggestion\n" + suggestion + "\n```";
    }

    private static String publishableSuggestion(String value) {
        if (value == null) return null;
        int end = value.length();
        while (end > 0 && (value.charAt(end - 1) == '\n' || value.charAt(end - 1) == '\r')) end--;
        String suggestion = value.substring(0, end);
        if (suggestion.isBlank()
                || suggestion.length() > MAX_SUGGESTION_CHARS
                || suggestion.split("\n", -1).length > MAX_SUGGESTION_LINES
                || suggestion.contains("```")) {
            return null;
        }
        return suggestion;
    }

    private static String orphanKey(LineComment c) {
        return normalizePath(c.file()) + "|" + c.line() + "|" + c.type() + "|" + c.body();
    }

    List<LineComment> withoutDroppedComments(
            List<LineComment> lineComments, List<JsonNode> droppedComments) {
        Set<String> droppedKeys = new HashSet<>();
        for (JsonNode dropped : droppedComments) {
            droppedKeys.add(
                    payloadKey(
                            dropped.path("path").asText(""),
                            dropped.path("line").asInt(0),
                            dropped.path("body").asText("")));
        }
        return lineComments.stream()
                .filter(
                        comment ->
                                !droppedKeys.contains(
                                        payloadKey(
                                                comment.file(),
                                                comment.line(),
                                                postedBody(comment))))
                .toList();
    }

    List<LineComment> acceptedComments(
            List<LineComment> lineComments,
            List<LineComment> orphans,
            ArrayNode postedComments,
            List<JsonNode> droppedComments) {
        Set<String> orphanKeys = new HashSet<>();
        for (LineComment orphan : orphans) orphanKeys.add(orphanKey(orphan));
        Set<String> droppedKeys = new HashSet<>();
        for (JsonNode dropped : droppedComments) {
            droppedKeys.add(
                    payloadKey(
                            dropped.path("path").asText(""),
                            dropped.path("line").asInt(0),
                            dropped.path("body").asText("")));
        }
        Set<String> acceptedKeys = new LinkedHashSet<>();
        for (JsonNode posted : postedComments) {
            String key =
                    payloadKey(
                            posted.path("path").asText(""),
                            posted.path("line").asInt(0),
                            posted.path("body").asText(""));
            if (!droppedKeys.contains(key)) acceptedKeys.add(key);
        }

        Set<String> emitted = new LinkedHashSet<>();
        List<LineComment> accepted = new ArrayList<>();
        for (LineComment comment : lineComments) {
            String key = payloadKey(comment.file(), comment.line(), postedBody(comment));
            if (orphanKeys.contains(orphanKey(comment))
                    || !acceptedKeys.contains(key)
                    || !emitted.add(key)) {
                continue;
            }
            accepted.add(comment);
        }
        return accepted;
    }

    private static boolean validEncodedComment(EncodedComment comment) {
        return comment != null
                && comment.l() >= 0
                && VALID_TYPES.contains(comment.t())
                && comment.b() != null
                && !comment.b().isBlank()
                && (comment.sg() == null || comment.sg().length() <= MAX_SUGGESTION_CHARS);
    }

    private static String payloadKey(String file, int line, String body) {
        return normalizePath(file) + "\u0000" + line + "\u0000" + (body == null ? "" : body);
    }

    private static String normalizePath(String file) {
        String normalized = file == null ? "" : file;
        return normalized.startsWith("a/") || normalized.startsWith("b/")
                ? normalized.substring(2)
                : normalized;
    }

    /** Formats pre-known orphan comments into the body section GitHub renders verbatim. */
    String buildOrphanSection(List<LineComment> orphans) {
        StringBuilder sb = new StringBuilder(DETACHED_COMMENTS_HEADER).append("\n");
        for (LineComment c : orphans) {
            appendDetachedCommentLine(sb, c.file(), c.line(), c.body());
        }
        return sb.toString().stripTrailing();
    }

    /**
     * Formats comments GitHub rejected (422) into the same body section as {@link
     * #buildOrphanSection}, reading each comment's {@code path}/{@code line}/{@code body} from the
     * JSON payload that was POSTed and dropped.
     */
    String buildDroppedSection(List<JsonNode> dropped) {
        StringBuilder sb = new StringBuilder(DETACHED_COMMENTS_HEADER).append("\n");
        for (JsonNode c : dropped) {
            appendDetachedCommentLine(
                    sb,
                    c.path("path").asText(""),
                    c.path("line").asInt(0),
                    c.path("body").asText(""));
        }
        return sb.toString().stripTrailing();
    }

    private static void appendDetachedCommentLine(
            StringBuilder sb, String path, int line, String body) {
        sb.append("- `").append(path == null ? "" : path);
        if (line > 0) sb.append(":").append(line);
        sb.append("`: ").append(body == null ? "" : body).append("\n");
    }

    record ApiComment(String path, Integer line, Integer originalLine, String body) {}

    public record LineComment(
            String file,
            int line,
            String type,
            String body,
            String severity,
            String category,
            String confidence,
            String rationale,
            String suggestedChange) {}

    public record DecodedReview(
            String summary,
            String verdict,
            List<LineComment> lineComments,
            boolean importedFromGitHub) {}

    private record EncodedComment(
            String f,
            int l,
            String t,
            String b,
            String s,
            String c,
            String cf,
            String r,
            // Omitted when absent so a comment without a suggestion encodes as it did before.
            @JsonInclude(JsonInclude.Include.NON_NULL) String sg) {}

    private record EncodedReview(String summary, String verdict, List<EncodedComment> comments) {}
}
