package com.jinloes.prpilot.sidecar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinloes.prpilot.engine.ReviewEngineApi;
import com.jinloes.prpilot.sidecar.pr.DraftReviewMutationService;
import com.jinloes.prpilot.sidecar.pr.LinkedIssueService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Shared JSON-RPC response and parameter-shape helpers used by the handler groups. */
final class StdioJsonRpcServerSupport {
    private static final String JSON_RPC_VERSION = "2.0";

    private final ObjectMapper objectMapper;

    StdioJsonRpcServerSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    JsonNode requestId(JsonNode request) {
        if (request != null && request.isObject() && request.has("id")) {
            return request.get("id");
        }
        return JsonNodeFactory.instance.nullNode();
    }

    ObjectNode result(JsonNode id, Object value) {
        ObjectNode response = baseResponse(id);
        response.set("result", objectMapper.valueToTree(value));
        return response;
    }

    ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = baseResponse(id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return response;
    }

    private ObjectNode baseResponse(JsonNode id) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", JSON_RPC_VERSION);
        response.set("id", id == null ? JsonNodeFactory.instance.nullNode() : id);
        return response;
    }

    boolean hasOnlyFields(JsonNode object, Set<String> allowedFields) {
        return object.properties().stream()
                .allMatch(entry -> allowedFields.contains(entry.getKey()));
    }

    boolean hasOnlyTextValues(JsonNode object) {
        return object.properties().stream().allMatch(entry -> entry.getValue().isTextual());
    }

    boolean isTextualOrAbsent(JsonNode node) {
        return node.isMissingNode() || node.isNull() || node.isTextual();
    }

    String optionalText(JsonNode object, String field) {
        JsonNode value = object.get(field);
        return value == null ? null : value.textValue();
    }

    List<DraftReviewMutationService.CommentInput> parseComments(JsonNode array) {
        if (!array.isArray()) return null;
        List<DraftReviewMutationService.CommentInput> comments = new ArrayList<>();
        for (JsonNode comment : array) {
            if (!comment.isObject()
                    || !hasOnlyFields(
                            comment,
                            Set.of(
                                    "file",
                                    "line",
                                    "type",
                                    "body",
                                    "severity",
                                    "category",
                                    "confidence",
                                    "rationale"))
                    || !comment.path("file").isTextual()
                    || !comment.path("line").isIntegralNumber()
                    || !comment.path("line").canConvertToInt()
                    || !comment.path("type").isTextual()
                    || !comment.path("body").isTextual()
                    || (comment.has("severity") && !comment.path("severity").isTextual())
                    || (comment.has("category") && !comment.path("category").isTextual())
                    || (comment.has("confidence") && !comment.path("confidence").isTextual())
                    || (comment.has("rationale") && !comment.path("rationale").isTextual())) {
                return null;
            }
            comments.add(
                    new DraftReviewMutationService.CommentInput(
                            comment.path("file").textValue(),
                            comment.path("line").intValue(),
                            comment.path("type").textValue(),
                            comment.path("body").textValue(),
                            optionalText(comment, "severity"),
                            optionalText(comment, "category"),
                            optionalText(comment, "confidence"),
                            optionalText(comment, "rationale")));
        }
        return comments;
    }

    List<ReviewEngineApi.OutcomeCommentParam> parseOutcomeComments(JsonNode array) {
        if (array == null || array.isMissingNode() || array.isNull()) return List.of();
        if (!array.isArray()) return null;
        List<ReviewEngineApi.OutcomeCommentParam> comments = new ArrayList<>();
        for (JsonNode comment : array) {
            if (!comment.isObject()
                    || !hasOnlyFields(
                            comment,
                            Set.of("file", "line", "type", "body", "severity", "confidence"))
                    || !comment.path("file").isTextual()
                    || !comment.path("line").isIntegralNumber()
                    || !comment.path("line").canConvertToInt()
                    || !comment.path("body").isTextual()
                    || (comment.has("type") && !comment.path("type").isTextual())
                    || (comment.has("severity") && !comment.path("severity").isTextual())
                    || (comment.has("confidence") && !comment.path("confidence").isTextual())) {
                return null;
            }
            comments.add(
                    new ReviewEngineApi.OutcomeCommentParam(
                            comment.path("file").textValue(),
                            comment.path("line").intValue(),
                            optionalText(comment, "type"),
                            comment.path("body").textValue(),
                            optionalText(comment, "severity"),
                            optionalText(comment, "confidence")));
        }
        return comments;
    }

    List<Integer> parseCommitIssueNumbers(JsonNode array) {
        if (!array.isArray() || array.size() > LinkedIssueService.MAX_ISSUES) return null;
        List<Integer> issueNumbers = new ArrayList<>();
        for (JsonNode number : array) {
            if (!number.isIntegralNumber() || !number.canConvertToInt()) return null;
            int value = number.intValue();
            if (value <= 0
                    || value > LinkedIssueService.MAX_ISSUE_NUMBER
                    || issueNumbers.contains(value)) return null;
            issueNumbers.add(value);
        }
        return List.copyOf(issueNumbers);
    }
}
