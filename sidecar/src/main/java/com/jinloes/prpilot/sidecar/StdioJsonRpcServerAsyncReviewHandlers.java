package com.jinloes.prpilot.sidecar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinloes.prpilot.engine.ReviewEngineApi;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Registers the review-engine JSON-RPC methods that run as cancellable background operations, plus
 * {@code reviews/cancel}. Handlers validate params on the calling thread; accepted work returns
 * {@code null} and its response, with any progress notifications, is written later through {@link
 * StdioJsonRpcServerOperationScheduler}.
 */
final class StdioJsonRpcServerAsyncReviewHandlers {
    private static final int MAX_OPERATION_ID_LENGTH = 128;

    private final ObjectMapper objectMapper;
    private final StdioJsonRpcServerSupport support;
    private final ReviewEngineApi review;
    private final StdioJsonRpcServerOperationScheduler scheduler;

    StdioJsonRpcServerAsyncReviewHandlers(
            ObjectMapper objectMapper,
            StdioJsonRpcServerSupport support,
            ReviewEngineApi review,
            StdioJsonRpcServerOperationScheduler scheduler) {
        this.objectMapper = objectMapper;
        this.support = support;
        this.review = review;
        this.scheduler = scheduler;
    }

    void register(Map<String, StdioJsonRpcServer.MethodHandler> handlers) {
        handlers.put("reviews/generate", this::generateReview);
        handlers.put("reviews/chat", this::chatReview);
        handlers.put("reviews/cancel", this::cancelReview);
        handlers.put("reviews/prepareDeepReview", this::prepareDeepReview);
        handlers.put("reviews/listDeepReviews", this::listDeepReviews);
        handlers.put("reviews/cleanupDeepReview", this::cleanupDeepReview);
    }

    /**
     * Kicks off review generation on the review executor and returns {@code null} immediately — the
     * eventual response (and any {@code reviews/status}/{@code reviews/chunk} notifications) are
     * written asynchronously from the background thread once the provider CLI completes, so the
     * read loop stays free to accept a {@code reviews/cancel} request meanwhile.
     */
    private JsonNode generateReview(JsonNode request) {
        JsonNode id = support.requestId(request);
        ReviewEngineApi.GenerateReviewParams params;
        try {
            params =
                    objectMapper.treeToValue(
                            request.get("params"), ReviewEngineApi.GenerateReviewParams.class);
        } catch (Exception exception) {
            return support.error(id, -32602, "Invalid params");
        }
        if (params == null
                || !validOperationId(params.operationId())
                || params.pr() == null
                || params.provider() == null
                || params.diff() == null) {
            return support.error(id, -32602, "Invalid params");
        }
        if (!scheduler.submit(
                params.operationId(),
                id,
                request.has("id"),
                "Review interrupted.",
                operation -> {
                    try {
                        var result =
                                review.generate(
                                        params,
                                        status ->
                                                operation.sendNotification(
                                                        "reviews/status", statusParams(id, status)),
                                        (kind, text) ->
                                                operation.sendNotification(
                                                        "reviews/chunk",
                                                        chunkParams(id, kind, text)));
                        operation.complete(support.result(id, result));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        operation.completeCancellation();
                    } catch (Exception exception) {
                        operation.complete(
                                operation.isCancelled()
                                        ? support.error(id, -32000, "Review interrupted.")
                                        : support.error(id, -32000, safeMessage(exception)));
                    }
                })) {
            return support.error(id, -32602, "Duplicate operation ID");
        }
        return null;
    }

    /** Same async-dispatch pattern as {@link #generateReview}, for chat requests. */
    private JsonNode chatReview(JsonNode request) {
        JsonNode id = support.requestId(request);
        ReviewEngineApi.ChatParams params;
        try {
            params =
                    objectMapper.treeToValue(
                            request.get("params"), ReviewEngineApi.ChatParams.class);
        } catch (Exception exception) {
            return support.error(id, -32602, "Invalid params");
        }
        if (params == null
                || !validOperationId(params.operationId())
                || params.provider() == null) {
            return support.error(id, -32602, "Invalid params");
        }
        if ((params.rawPrompt() == null) == (params.userMessage() == null)) {
            return support.error(id, -32602, "Invalid params");
        }
        if (!scheduler.submit(
                params.operationId(),
                id,
                request.has("id"),
                "Chat interrupted.",
                operation -> {
                    try {
                        var result =
                                review.chat(
                                        params,
                                        text ->
                                                operation.sendNotification(
                                                        "reviews/chatChunk",
                                                        chatChunkParams(id, text)));
                        operation.complete(support.result(id, result));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        operation.completeCancellation();
                    } catch (Exception exception) {
                        operation.complete(
                                operation.isCancelled()
                                        ? support.error(id, -32000, "Chat interrupted.")
                                        : support.error(id, -32000, safeMessage(exception)));
                    }
                })) {
            return support.error(id, -32602, "Duplicate operation ID");
        }
        return null;
    }

    /** Synchronous: cancelling only touches a flag/process reference, no CLI I/O. */
    private JsonNode cancelReview(JsonNode request) {
        ReviewEngineApi.CancelParams params;
        try {
            params =
                    objectMapper.treeToValue(
                            request.get("params"), ReviewEngineApi.CancelParams.class);
        } catch (Exception exception) {
            return support.error(support.requestId(request), -32602, "Invalid params");
        }
        if (params == null || !validOperationId(params.operationId())) {
            return support.error(support.requestId(request), -32602, "Invalid params");
        }
        boolean cancelled = scheduler.cancel(params.operationId());
        ReviewEngineApi.CancelResult engineResult = review.cancel(params);
        return support.result(
                support.requestId(request),
                new ReviewEngineApi.CancelResult(cancelled || engineResult.cancelled()));
    }

    private static boolean validOperationId(String operationId) {
        return operationId != null
                && !operationId.isBlank()
                && operationId.length() <= MAX_OPERATION_ID_LENGTH
                && operationId.chars().noneMatch(Character::isISOControl);
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null ? exception.getClass().getSimpleName() : message;
    }

    private ObjectNode statusParams(JsonNode id, String message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.set("requestId", id);
        node.put("message", message);
        return node;
    }

    private ObjectNode chunkParams(JsonNode id, String kind, String text) {
        ObjectNode node = objectMapper.createObjectNode();
        node.set("requestId", id);
        node.put("kind", kind);
        node.put("text", text);
        return node;
    }

    private ObjectNode chatChunkParams(JsonNode id, String text) {
        ObjectNode node = objectMapper.createObjectNode();
        node.set("requestId", id);
        node.put("text", text);
        return node;
    }

    /**
     * Creates a PR-branch worktree. {@code forkCloneUrl} is optional — omitting it selects the
     * origin fetch path. A git failure comes back as a {@code failed} status rather than an RPC
     * error, because callers degrade to the user's own checkout instead of failing the review.
     */
    private JsonNode prepareDeepReview(JsonNode request) {
        JsonNode id = support.requestId(request);
        JsonNode input = request.get("params");
        if (input == null
                || !input.isObject()
                || !support.hasOnlyFields(
                        input,
                        Set.of(
                                "operationId",
                                "gitRoot",
                                "prNumber",
                                "branch",
                                "headSha",
                                "forkCloneUrl",
                                "prIdentity",
                                "diffDigest"))
                || !input.path("prNumber").isInt()
                || input.path("prNumber").intValue() <= 0
                || !support.isTextualOrAbsent(input.path("forkCloneUrl")))
            return support.error(id, -32602, "Invalid params");
        for (String field :
                List.of("operationId", "gitRoot", "branch", "headSha", "prIdentity", "diffDigest"))
            if (!input.path(field).isTextual() || input.path(field).textValue().isBlank())
                return support.error(id, -32602, "Invalid params");
        if (!validOperationId(input.get("operationId").textValue()))
            return support.error(id, -32602, "Invalid operation");
        ReviewEngineApi.PrepareDeepReviewParams params;
        try {
            params = objectMapper.treeToValue(input, ReviewEngineApi.PrepareDeepReviewParams.class);
        } catch (Exception failure) {
            return support.error(id, -32602, "Invalid params");
        }
        if (!scheduler.submit(
                params.operationId(),
                id,
                request.has("id"),
                "Preparation interrupted.",
                operation -> {
                    try {
                        operation.complete(support.result(id, review.prepareDeepReview(params)));
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        operation.completeCancellation();
                    } catch (Exception failure) {
                        operation.complete(support.error(id, -32000, safeMessage(failure)));
                    }
                })) return support.error(id, -32602, "Duplicate operation ID");
        return null;
    }

    private JsonNode listDeepReviews(JsonNode request) {
        JsonNode input = request.get("params");
        if (input != null && (!input.isObject() || !input.isEmpty()))
            return support.error(support.requestId(request), -32602, "Invalid params");
        return deepMaintenance(request, () -> review.listDeepReviews());
    }

    private JsonNode cleanupDeepReview(JsonNode request) {
        JsonNode input = request.get("params");
        if (input == null
                || !input.isObject()
                || !support.hasOnlyFields(input, Set.of("retainedId", "projectClosed"))
                || !input.path("retainedId").isTextual()
                || !input.path("projectClosed").isBoolean())
            return support.error(support.requestId(request), -32602, "Invalid params");
        return deepMaintenance(
                request,
                () ->
                        review.cleanupDeepReview(
                                new ReviewEngineApi.CleanupDeepReviewParams(
                                        input.get("retainedId").textValue(),
                                        input.get("projectClosed").booleanValue())));
    }

    @FunctionalInterface
    private interface DeepMaintenance {
        Object run() throws IOException;
    }

    private JsonNode deepMaintenance(JsonNode request, DeepMaintenance action) {
        JsonNode id = support.requestId(request);
        if (!scheduler.submit(
                "deep-maintenance-" + UUID.randomUUID(),
                id,
                request.has("id"),
                "Maintenance interrupted.",
                operation -> {
                    try {
                        operation.complete(support.result(id, action.run()));
                    } catch (Exception failure) {
                        operation.complete(support.error(id, -32000, safeMessage(failure)));
                    }
                })) return support.error(id, -32000, "Maintenance busy");
        return null;
    }
}
