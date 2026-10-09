package com.jinloes.prpilot.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.ReviewResult;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Parses and repairs model review output into a {@link ReviewResult}. */
final class ReviewResultParser {

    private static final Logger log = LoggerFactory.getLogger(ReviewResultParser.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private ReviewResultParser() {}

    /** Comment cap for a candidate-recall primary pass; the validator narrows it back down. */
    static final int RECALL_MAX_LINE_COMMENTS = 30;

    private static final int MAX_SUMMARY_CHARS = 800;
    private static final int MAX_RATIONALE_CHARS = 200;
    static final int MAX_LINE_COMMENTS = 20;
    private static final Set<String> VALID_TYPES = Set.of("issue", "suggestion", "note");
    private static final Set<String> VALID_SEVERITIES = Set.of("blocker", "major", "minor", "nit");
    private static final Set<String> VALID_CATEGORIES =
            Set.of(
                    "correctness",
                    "security",
                    "performance",
                    "tests",
                    "maintainability",
                    "compatibility");
    private static final Set<String> VALID_CONFIDENCES = Set.of("low", "medium", "high");
    private static final Set<String> VALID_VERDICTS =
            Set.of("APPROVE", "REQUEST_CHANGES", "COMMENT");

    /** Recall passes may surface more candidates, because a validator narrows them afterwards. */
    static int maxComments(PRReviewRequest request) {
        return request.isCandidateRecall() ? RECALL_MAX_LINE_COMMENTS : MAX_LINE_COMMENTS;
    }

    /**
     * Extracts a JSON object from the raw claude output (which may include markdown fences or
     * leading/trailing prose) and builds a {@link ReviewResult} from it.
     *
     * <p>Individual malformed line comments are dropped (and a low-confidence "issue" is downgraded
     * to "suggestion") rather than failing the entire review — capable models occasionally emit one
     * non-conforming comment among otherwise-valid output, and rejecting the whole review in that
     * case throws away 19 good comments to punish 1 bad one. The top-level shape (an object with a
     * string "summary" and an array "lineComments") is still a hard requirement, since there is
     * nothing to salvage without it.
     */
    static ReviewResult parseReview(String raw) throws IOException {
        return parseReview(raw, MAX_LINE_COMMENTS);
    }

    /** Parses a review keeping at most {@code maxComments} line comments in emission order. */
    static ReviewResult parseReview(String raw, int maxComments) throws IOException {
        String json = raw.trim();

        if (json.startsWith("```")) {
            int newline = json.indexOf('\n');
            int closing = json.lastIndexOf("```");
            if (newline > 0 && closing > newline) {
                json = json.substring(newline + 1, closing).trim();
            }
        }

        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start >= 0 && end > start) {
            json = json.substring(start, end + 1);
        }

        JsonNode root = JSON.readTree(json);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("review JSON is not an object");
        }

        JsonNode summaryNode = root.get("summary");
        if (summaryNode == null || !summaryNode.isTextual()) {
            throw new IllegalArgumentException("review JSON missing string summary");
        }
        String summary = summaryNode.textValue();
        if (summary.length() > MAX_SUMMARY_CHARS) {
            summary = summary.substring(0, MAX_SUMMARY_CHARS);
        }

        JsonNode verdictNode = root.get("verdict");
        String requestedVerdict =
                verdictNode != null
                                && verdictNode.isTextual()
                                && VALID_VERDICTS.contains(verdictNode.textValue())
                        ? verdictNode.textValue()
                        : null;

        JsonNode rawComments = root.get("lineComments");
        List<LineComment> comments = new ArrayList<>();
        if (rawComments != null && rawComments.isArray()) {
            for (JsonNode element : rawComments) {
                if (comments.size() >= maxComments) break;
                LineComment comment = repairLineComment(element);
                if (comment != null) comments.add(comment);
            }
        }

        boolean hasBlockingIssue =
                comments.stream()
                        .anyMatch(
                                c ->
                                        "issue".equals(c.getType())
                                                && ("blocker".equals(c.getSeverity())
                                                        || "major".equals(c.getSeverity())));
        String verdict;
        if ("REQUEST_CHANGES".equals(requestedVerdict) && !hasBlockingIssue) {
            verdict = "COMMENT";
        } else if (!"REQUEST_CHANGES".equals(requestedVerdict) && hasBlockingIssue) {
            verdict = "REQUEST_CHANGES";
        } else if (requestedVerdict != null) {
            verdict = requestedVerdict;
        } else if (hasBlockingIssue) {
            verdict = "REQUEST_CHANGES";
        } else {
            verdict = "COMMENT";
        }

        return new ReviewResult(summary, verdict, comments);
    }

    /**
     * Validates and normalizes a single line comment element, returning {@code null} (and logging
     * at debug level) when the comment is unsalvageable.
     *
     * <p>Two rules here exist to stop unconfirmed findings entering the review under a
     * low-confidence label. A low-confidence "issue" is <em>dropped</em>, not downgraded to
     * "suggestion": the prompt forbids emitting one, and downgrading turned that violation into an
     * accepted comment, so breaking the rule cost the model nothing. And a low-confidence comment
     * of any type must carry a "rationale" — without that, a bare low-confidence "note" is the
     * cheapest comment the model can emit, and nothing downstream removes it (the webview quality
     * check exempts low-confidence comments from its own rationale rule).
     *
     * <p>A "nit"-severity "issue" is still downgraded rather than dropped: that is a type/severity
     * disagreement, not an evidence problem.
     */
    private static LineComment repairLineComment(JsonNode element) {
        if (element == null || !element.isObject()) return dropComment("non-object line comment");
        String file = optionalString(element, "file");
        if (StringUtils.isBlank(file)) return dropComment("blank/missing file");

        JsonNode lineNode = element.get("line");
        if (lineNode == null || !lineNode.isIntegralNumber() || lineNode.asInt() <= 0) {
            return dropComment("invalid line");
        }
        int line = lineNode.asInt();

        String type = optionalString(element, "type");
        if (type == null || !VALID_TYPES.contains(type)) return dropComment("invalid type");

        String body = optionalString(element, "body");
        if (body != null) {
            body = body.replaceAll("[\\r\\n]+", " ").trim();
        }
        if (StringUtils.isBlank(body)) return dropComment("blank/missing body");

        String severity = optionalString(element, "severity");
        if (severity == null || !VALID_SEVERITIES.contains(severity))
            return dropComment("invalid severity");

        String category = optionalString(element, "category");
        if (category == null || !VALID_CATEGORIES.contains(category))
            return dropComment("invalid category");

        String confidence = optionalString(element, "confidence");
        if (confidence == null || !VALID_CONFIDENCES.contains(confidence))
            return dropComment("invalid confidence");

        boolean lowConfidence = "low".equals(confidence);
        if (lowConfidence && "issue".equals(type)) return dropComment("low-confidence issue");

        String effectiveType = type;
        if ("issue".equals(effectiveType) && "nit".equals(severity)) {
            effectiveType = "suggestion";
        }
        String rationale = optionalString(element, "rationale");
        if (!"note".equals(effectiveType) || lowConfidence) {
            if (StringUtils.isBlank(rationale)) return dropComment("missing rationale");
            if (rationale.length() > MAX_RATIONALE_CHARS)
                rationale = rationale.substring(0, MAX_RATIONALE_CHARS);
        }

        LineComment result = new LineComment(file, line, effectiveType, body);
        result.setSeverity(severity);
        result.setCategory(category);
        result.setConfidence(confidence);
        result.setRationale(rationale);
        result.setSuggestedChange(optionalString(element, "suggestedChange"));
        return result;
    }

    private static LineComment dropComment(String reason) {
        log.debug("Dropping malformed review line comment: {}", reason);
        return null;
    }

    private static String optionalString(JsonNode object, String key) {
        JsonNode value = object.get(key);
        return value != null && value.isTextual() ? value.textValue() : null;
    }
}
