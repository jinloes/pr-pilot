package com.jinloes.prpilot.sidecar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinloes.prpilot.engine.GitHubEngineApi;
import com.jinloes.prpilot.engine.ReviewEngineApi;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class StdioJsonRpcServer {
    private static final String JSON_RPC_VERSION = "2.0";
    private static final int MAX_OPERATION_ID_LENGTH = 128;
    private static final int REQUEST_QUEUE_CAPACITY = 64;
    private static final int SERVER_BUSY_CODE = -32000;

    /** Handles one decoded request; returns {@code null} when the reply is sent asynchronously. */
    @FunctionalInterface
    interface MethodHandler {
        JsonNode handle(JsonNode request);
    }

    private final ObjectMapper objectMapper;
    private final StdioJsonRpcServerSupport support;
    private final StdioFrameCodec frameCodec;
    private final SidecarBootstrapService bootstrapService;
    private final GitHubEngineApi github;
    private final ReviewEngineApi review;
    private final ExecutorService reviewExecutor;
    private final ExecutorService requestExecutor;
    private final Map<String, MethodHandler> handlers = new LinkedHashMap<>();
    private final Object writeLock = new Object();
    private final Object operationLock = new Object();
    private final Object outcomeLogLock = new Object();
    private final ConcurrentMap<String, ScheduledOperation> scheduledOperations =
            new ConcurrentHashMap<>();
    private volatile OutputStream currentOutput;

    StdioJsonRpcServer(
            ObjectMapper objectMapper,
            StdioFrameCodec frameCodec,
            SidecarBootstrapService bootstrapService,
            GitHubEngineApi github,
            ReviewEngineApi review,
            ExecutorService reviewExecutor) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.support = new StdioJsonRpcServerSupport(this.objectMapper);
        this.frameCodec = Objects.requireNonNull(frameCodec);
        this.bootstrapService = Objects.requireNonNull(bootstrapService);
        this.github = Objects.requireNonNull(github);
        this.review = Objects.requireNonNull(review);
        this.reviewExecutor = Objects.requireNonNull(reviewExecutor);
        int requestThreads = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
        AtomicInteger threadSequence = new AtomicInteger();
        this.requestExecutor =
                new ThreadPoolExecutor(
                        requestThreads,
                        requestThreads,
                        0L,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(REQUEST_QUEUE_CAPACITY),
                        runnable -> {
                            Thread thread =
                                    new Thread(
                                            runnable,
                                            "pr-pilot-sidecar-io-"
                                                    + threadSequence.incrementAndGet());
                            thread.setDaemon(true);
                            return thread;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
        registerHandlers();
    }

    /**
     * Binds every wire method to its handler. The names here must cover {@link
     * GitHubEngineApi#RPC_METHODS} and {@link ReviewEngineApi#RPC_METHODS} completely — {@code
     * EngineCapabilityCoverageTest} fails the build otherwise, which is what keeps a host from
     * quietly re-implementing an engine capability locally.
     */
    private void registerHandlers() {
        handlers.put(
                "initialize", request -> result(requestId(request), bootstrapService.initialize()));
        new StdioJsonRpcServerGitHubHandlers(support, github).register(handlers);
        new StdioJsonRpcServerReviewHandlers(support, review, outcomeLogLock).register(handlers);
        handlers.put("reviews/generate", this::generateReview);
        handlers.put("reviews/chat", this::chatReview);
        handlers.put("reviews/cancel", this::cancelReview);
        handlers.put("reviews/prepareDeepReview", this::prepareDeepReview);
        handlers.put("reviews/listDeepReviews", this::listDeepReviews);
        handlers.put("reviews/cleanupDeepReview", this::cleanupDeepReview);
    }

    /** Wire method names this server answers. Used by the engine capability coverage test. */
    Set<String> registeredMethodNames() {
        return Set.copyOf(handlers.keySet());
    }

    void run(InputStream input, OutputStream output) {
        this.currentOutput = output;
        try {
            byte[] frame;
            while ((frame = frameCodec.readFrame(input)) != null) {
                JsonNode request;
                try {
                    request = objectMapper.readTree(frame);
                } catch (IOException exception) {
                    send(error(null, -32700, "Parse error"));
                    continue;
                }
                if (!isValidRequest(request)) {
                    send(error(requestId(request), -32600, "Invalid Request"));
                    continue;
                }

                String method = request.path("method").asText();
                if (handlers.get(method) == null || isReaderThreadMethod(method)) {
                    JsonNode response = handleRequest(request);
                    if (response != null) send(response);
                    continue;
                }
                try {
                    requestExecutor.execute(
                            () -> {
                                JsonNode response = handleRequest(request);
                                if (response != null) send(response);
                            });
                } catch (RejectedExecutionException exception) {
                    if (request.has("id")) {
                        send(error(requestId(request), SERVER_BUSY_CODE, "Server busy"));
                    }
                }
            }
        } catch (IOException exception) {
            throw new SidecarProtocolException(
                    "Unable to read or write JSON-RPC messages", exception);
        } finally {
            requestExecutor.shutdown();
            try {
                if (!requestExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    requestExecutor.shutdownNow();
                }
            } catch (InterruptedException exception) {
                requestExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    JsonNode handle(byte[] frame) {
        JsonNode request;
        try {
            request = objectMapper.readTree(frame);
        } catch (IOException exception) {
            return error(null, -32700, "Parse error");
        }

        if (!isValidRequest(request)) {
            return error(requestId(request), -32600, "Invalid Request");
        }

        return handleRequest(request);
    }

    private JsonNode handleRequest(JsonNode request) {
        boolean notification = !request.has("id");
        MethodHandler handler = handlers.get(request.path("method").asText());
        JsonNode response;
        try {
            response =
                    handler == null
                            ? error(requestId(request), -32601, "Method not found")
                            : handler.handle(request);
        } catch (RuntimeException exception) {
            response = error(requestId(request), -32603, "Internal error");
        }

        return notification ? null : response;
    }

    private static boolean isReaderThreadMethod(String method) {
        // Generate/chat only register background work; keeping registration here preserves frame
        // order when the next frame cancels that operation.
        return "initialize".equals(method)
                || "reviews/generate".equals(method)
                || "reviews/chat".equals(method)
                || "reviews/cancel".equals(method);
    }

    /**
     * Kicks off review generation on {@link #reviewExecutor} and returns {@code null} immediately —
     * the eventual response (and any {@code reviews/status}/{@code reviews/chunk} notifications)
     * are written asynchronously from the background thread once the provider CLI completes, so the
     * read loop stays free to accept a {@code reviews/cancel} request meanwhile.
     */
    private JsonNode generateReview(JsonNode request) {
        JsonNode id = requestId(request);
        ReviewEngineApi.GenerateReviewParams params;
        try {
            params =
                    objectMapper.treeToValue(
                            request.get("params"), ReviewEngineApi.GenerateReviewParams.class);
        } catch (Exception exception) {
            return error(id, -32602, "Invalid params");
        }
        if (params == null
                || !validOperationId(params.operationId())
                || params.pr() == null
                || params.provider() == null
                || params.diff() == null) {
            return error(id, -32602, "Invalid params");
        }
        if (!submitReviewOperation(
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
                        operation.complete(result(id, result));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        operation.completeCancellation();
                    } catch (Exception exception) {
                        operation.complete(
                                operation.isCancelled()
                                        ? error(id, -32000, "Review interrupted.")
                                        : error(id, -32000, safeMessage(exception)));
                    }
                })) {
            return error(id, -32602, "Duplicate operation ID");
        }
        return null;
    }

    /** Same async-dispatch pattern as {@link #generateReview}, for chat requests. */
    private JsonNode chatReview(JsonNode request) {
        JsonNode id = requestId(request);
        ReviewEngineApi.ChatParams params;
        try {
            params =
                    objectMapper.treeToValue(
                            request.get("params"), ReviewEngineApi.ChatParams.class);
        } catch (Exception exception) {
            return error(id, -32602, "Invalid params");
        }
        if (params == null
                || !validOperationId(params.operationId())
                || params.provider() == null) {
            return error(id, -32602, "Invalid params");
        }
        if ((params.rawPrompt() == null) == (params.userMessage() == null)) {
            return error(id, -32602, "Invalid params");
        }
        if (!submitReviewOperation(
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
                        operation.complete(result(id, result));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        operation.completeCancellation();
                    } catch (Exception exception) {
                        operation.complete(
                                operation.isCancelled()
                                        ? error(id, -32000, "Chat interrupted.")
                                        : error(id, -32000, safeMessage(exception)));
                    }
                })) {
            return error(id, -32602, "Duplicate operation ID");
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
            return error(requestId(request), -32602, "Invalid params");
        }
        if (params == null || !validOperationId(params.operationId())) {
            return error(requestId(request), -32602, "Invalid params");
        }
        boolean cancelled = cancelScheduledOperation(params.operationId());
        ReviewEngineApi.CancelResult engineResult = review.cancel(params);
        return result(
                requestId(request),
                new ReviewEngineApi.CancelResult(cancelled || engineResult.cancelled()));
    }

    private boolean submitReviewOperation(
            String operationId,
            JsonNode requestId,
            boolean responseRequired,
            String cancellationMessage,
            java.util.function.Consumer<ScheduledOperation> operation) {
        synchronized (operationLock) {
            if (scheduledOperations.containsKey(operationId)) {
                return false;
            }
            ScheduledOperation scheduled =
                    new ScheduledOperation(
                            requestId, responseRequired, cancellationMessage, operation);
            Future<?> future =
                    reviewExecutor.submit(
                            () -> {
                                synchronized (operationLock) {
                                    // Wait for the submitting thread to publish this future before
                                    // cancellation or completion can remove it.
                                    if (scheduled.cancelled) {
                                        scheduled.completeCancellation();
                                        return;
                                    }
                                    scheduled.started = true;
                                }
                                if (scheduled.isCancelled()) {
                                    scheduled.completeCancellation();
                                    scheduledOperations.remove(operationId, scheduled);
                                    return;
                                }
                                try {
                                    scheduled.operation.accept(scheduled);
                                } finally {
                                    scheduledOperations.remove(operationId, scheduled);
                                }
                            });
            scheduled.future = future;
            scheduledOperations.put(operationId, scheduled);
            return true;
        }
    }

    private boolean cancelScheduledOperation(String operationId) {
        ScheduledOperation operation;
        boolean queued;
        synchronized (operationLock) {
            operation = scheduledOperations.get(operationId);
            if (operation == null) return false;
            if (operation.completed.get()) {
                scheduledOperations.remove(operationId, operation);
                return false;
            }
            queued = !operation.started;
            operation.cancelled = true;
            if (queued) scheduledOperations.remove(operationId, operation);
        }
        operation.future.cancel(true);
        if (queued) operation.completeCancellation();
        return true;
    }

    private final class ScheduledOperation {
        private final JsonNode requestId;
        private final boolean responseRequired;
        private final String cancellationMessage;
        private final java.util.function.Consumer<ScheduledOperation> operation;
        private final AtomicBoolean completed = new AtomicBoolean();
        private volatile Future<?> future;
        private volatile boolean started;
        private volatile boolean cancelled;

        private ScheduledOperation(
                JsonNode requestId,
                boolean responseRequired,
                String cancellationMessage,
                java.util.function.Consumer<ScheduledOperation> operation) {
            this.requestId = requestId;
            this.responseRequired = responseRequired;
            this.cancellationMessage = cancellationMessage;
            this.operation = operation;
        }

        private boolean isCancelled() {
            return cancelled;
        }

        private void sendNotification(String method, ObjectNode params) {
            if (!isCancelled()) StdioJsonRpcServer.this.sendNotification(method, params);
        }

        private void complete(JsonNode response) {
            JsonNode terminalResponse;
            synchronized (operationLock) {
                if (!completed.compareAndSet(false, true)) return;
                terminalResponse =
                        isCancelled() ? error(requestId, -32000, cancellationMessage) : response;
            }
            if (responseRequired) {
                send(terminalResponse);
            }
        }

        private void completeCancellation() {
            complete(error(requestId, -32000, cancellationMessage));
        }
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

    /** Sends a fire-and-forget JSON-RPC notification (no {@code id} field). */
    private void sendNotification(String method, ObjectNode params) {
        ObjectNode notification = objectMapper.createObjectNode();
        notification.put("jsonrpc", JSON_RPC_VERSION);
        notification.put("method", method);
        notification.set("params", params);
        send(notification);
    }

    /**
     * Writes a frame to the shared stdout stream under {@link #writeLock} so the main read loop and
     * background {@link #reviewExecutor} tasks never interleave partial frames. Failures are
     * swallowed — a broken pipe here means the client already disconnected, which the main read
     * loop will independently observe and exit on.
     */
    private void send(JsonNode node) {
        OutputStream output = this.currentOutput;
        if (output == null) return;
        synchronized (writeLock) {
            try {
                frameCodec.writeFrame(output, objectMapper.writeValueAsBytes(node));
            } catch (IOException exception) {
                // Best effort; see method javadoc.
            }
        }
    }

    /**
     * Creates a PR-branch worktree. {@code forkCloneUrl} is optional — omitting it selects the
     * origin fetch path. A git failure comes back as a {@code failed} status rather than an RPC
     * error, because callers degrade to the user's own checkout instead of failing the review.
     */
    private JsonNode prepareDeepReview(JsonNode request) {
        JsonNode id = requestId(request);
        JsonNode input = request.get("params");
        if (input == null
                || !input.isObject()
                || !hasOnlyFields(
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
                || !isTextualOrAbsent(input.path("forkCloneUrl")))
            return error(id, -32602, "Invalid params");
        for (String field :
                List.of("operationId", "gitRoot", "branch", "headSha", "prIdentity", "diffDigest"))
            if (!input.path(field).isTextual() || input.path(field).textValue().isBlank())
                return error(id, -32602, "Invalid params");
        if (!validOperationId(input.get("operationId").textValue()))
            return error(id, -32602, "Invalid operation");
        ReviewEngineApi.PrepareDeepReviewParams params;
        try {
            params = objectMapper.treeToValue(input, ReviewEngineApi.PrepareDeepReviewParams.class);
        } catch (Exception failure) {
            return error(id, -32602, "Invalid params");
        }
        if (!submitReviewOperation(
                params.operationId(),
                id,
                request.has("id"),
                "Preparation interrupted.",
                operation -> {
                    try {
                        operation.complete(result(id, review.prepareDeepReview(params)));
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        operation.completeCancellation();
                    } catch (Exception failure) {
                        operation.complete(error(id, -32000, safeMessage(failure)));
                    }
                })) return error(id, -32602, "Duplicate operation ID");
        return null;
    }

    private JsonNode listDeepReviews(JsonNode request) {
        JsonNode input = request.get("params");
        if (input != null && (!input.isObject() || !input.isEmpty()))
            return error(requestId(request), -32602, "Invalid params");
        return deepMaintenance(request, () -> review.listDeepReviews());
    }

    private JsonNode cleanupDeepReview(JsonNode request) {
        JsonNode input = request.get("params");
        if (input == null
                || !input.isObject()
                || !hasOnlyFields(input, Set.of("retainedId", "projectClosed"))
                || !input.path("retainedId").isTextual()
                || !input.path("projectClosed").isBoolean())
            return error(requestId(request), -32602, "Invalid params");
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
        JsonNode id = requestId(request);
        if (!submitReviewOperation(
                "deep-maintenance-" + java.util.UUID.randomUUID(),
                id,
                request.has("id"),
                "Maintenance interrupted.",
                operation -> {
                    try {
                        operation.complete(result(id, action.run()));
                    } catch (Exception failure) {
                        operation.complete(error(id, -32000, safeMessage(failure)));
                    }
                })) return error(id, -32000, "Maintenance busy");
        return null;
    }

    /** Optional string field: present and textual, or absent/null. */
    private static boolean isTextualOrAbsent(JsonNode node) {
        return node.isMissingNode() || node.isNull() || node.isTextual();
    }

    private boolean hasOnlyFields(JsonNode object, Set<String> allowedFields) {
        return support.hasOnlyFields(object, allowedFields);
    }

    private boolean isValidRequest(JsonNode request) {
        return request != null
                && request.isObject()
                && JSON_RPC_VERSION.equals(request.path("jsonrpc").asText())
                && request.path("method").isTextual();
    }

    private JsonNode requestId(JsonNode request) {
        if (request != null && request.isObject() && request.has("id")) {
            return request.get("id");
        }
        return JsonNodeFactory.instance.nullNode();
    }

    private ObjectNode result(JsonNode id, Object value) {
        ObjectNode response = baseResponse(id);
        response.set("result", objectMapper.valueToTree(value));
        return response;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
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

    private static final class SidecarProtocolException extends RuntimeException {
        private SidecarProtocolException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
