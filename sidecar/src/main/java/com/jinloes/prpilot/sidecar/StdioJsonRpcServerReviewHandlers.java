package com.jinloes.prpilot.sidecar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinloes.prpilot.engine.ReviewEngineApi;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Registers synchronous JSON-RPC methods backed by the review engine. */
final class StdioJsonRpcServerReviewHandlers {
    private final StdioJsonRpcServerSupport support;
    private final ReviewEngineApi review;
    private final Object outcomeLogLock;

    StdioJsonRpcServerReviewHandlers(
            StdioJsonRpcServerSupport support, ReviewEngineApi review, Object outcomeLogLock) {
        this.support = support;
        this.review = review;
        this.outcomeLogLock = outcomeLogLock;
    }

    void register(Map<String, StdioJsonRpcServer.MethodHandler> handlers) {
        handlers.put("reviews/readGuidelines", this::readGuidelines);
        handlers.put("reviews/findGitRoot", this::findGitRoot);
        handlers.put("reviews/recordOutcome", this::recordReviewOutcome);
        handlers.put("reviews/createWorktree", this::createWorktree);
        handlers.put("reviews/removeWorktree", this::removeWorktree);
    }

    /**
     * Reads repository guidance docs. {@code globs} is optional: omitting it (or sending an empty
     * array) selects the engine's default file list, so a client never carries its own copy.
     */
    private ObjectNode readGuidelines(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || !support.hasOnlyFields(params, Set.of("projectDir", "globs"))
                || !params.path("projectDir").isTextual()) {
            return support.error(support.requestId(request), -32602, "Invalid params");
        }
        JsonNode globsNode = params.path("globs");
        if (!globsNode.isMissingNode() && !globsNode.isNull() && !globsNode.isArray()) {
            return support.error(support.requestId(request), -32602, "Invalid params");
        }
        List<String> globs = new ArrayList<>();
        if (globsNode.isArray()) {
            for (JsonNode glob : globsNode) {
                if (!glob.isTextual()) {
                    return support.error(support.requestId(request), -32602, "Invalid params");
                }
                globs.add(glob.textValue());
            }
        }
        return support.result(
                support.requestId(request),
                review.readGuidelines(
                        new ReviewEngineApi.ReadGuidelinesParams(
                                params.path("projectDir").textValue(), globs)));
    }

    private ObjectNode findGitRoot(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || params.size() != 1
                || !params.path("startDir").isTextual()) {
            return support.error(support.requestId(request), -32602, "Invalid params");
        }
        return support.result(
                support.requestId(request),
                review.findGitRoot(params.path("startDir").textValue()));
    }

    /**
     * Records review outcomes. Instrumentation: a malformed payload is rejected, but a write
     * failure inside the engine is swallowed there rather than surfaced as an RPC error, because
     * the submission this follows has already succeeded.
     */
    private ObjectNode recordReviewOutcome(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || !support.hasOnlyFields(
                        params,
                        Set.of(
                                "provider",
                                "model",
                                "reviewSupervisorEnabled",
                                "generated",
                                "submitted"))
                || !params.path("provider").isTextual()
                || !params.path("model").isTextual()
                || (params.has("reviewSupervisorEnabled")
                        && !params.path("reviewSupervisorEnabled").isBoolean())) {
            return support.error(support.requestId(request), -32602, "Invalid params");
        }
        List<ReviewEngineApi.OutcomeCommentParam> generated =
                support.parseOutcomeComments(params.path("generated"));
        List<ReviewEngineApi.OutcomeCommentParam> submitted =
                support.parseOutcomeComments(params.path("submitted"));
        if (generated == null || submitted == null) {
            return support.error(support.requestId(request), -32602, "Invalid params");
        }
        ReviewEngineApi.RecordOutcomeResult recorded;
        synchronized (outcomeLogLock) {
            recorded =
                    review.recordOutcome(
                            new ReviewEngineApi.RecordOutcomeParams(
                                    params.path("provider").textValue(),
                                    params.path("model").textValue(),
                                    params.path("reviewSupervisorEnabled").asBoolean(false),
                                    generated,
                                    submitted));
        }
        return support.result(support.requestId(request), recorded);
    }

    private ObjectNode createWorktree(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || !support.hasOnlyFields(
                        params, Set.of("gitRoot", "prNumber", "branch", "headSha", "forkCloneUrl"))
                || !params.path("gitRoot").isTextual()
                || !params.path("prNumber").isInt()
                || !params.path("branch").isTextual()
                || !support.isTextualOrAbsent(params.path("headSha"))
                || !support.isTextualOrAbsent(params.path("forkCloneUrl"))) {
            return support.error(support.requestId(request), -32602, "Invalid params");
        }
        return support.result(
                support.requestId(request),
                review.createWorktree(
                        new ReviewEngineApi.CreateWorktreeParams(
                                params.path("gitRoot").textValue(),
                                params.path("prNumber").intValue(),
                                params.path("branch").textValue(),
                                params.path("headSha").asText(""),
                                params.path("forkCloneUrl").asText(""))));
    }

    private ObjectNode removeWorktree(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || !support.hasOnlyFields(params, Set.of("gitRoot", "worktreeDir"))
                || !params.path("gitRoot").isTextual()
                || !params.path("worktreeDir").isTextual()) {
            return support.error(support.requestId(request), -32602, "Invalid params");
        }
        return support.result(
                support.requestId(request),
                review.removeWorktree(
                        new ReviewEngineApi.RemoveWorktreeParams(
                                params.path("gitRoot").textValue(),
                                params.path("worktreeDir").textValue())));
    }
}
