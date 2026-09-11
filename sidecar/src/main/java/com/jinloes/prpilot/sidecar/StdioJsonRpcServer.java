package com.jinloes.prpilot.sidecar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinloes.prpilot.engine.GitHubEngineApi;
import com.jinloes.prpilot.engine.ReviewEngineApi;
import com.jinloes.prpilot.sidecar.pr.CheckRunService;
import com.jinloes.prpilot.sidecar.pr.DraftReviewMutationService;
import com.jinloes.prpilot.sidecar.pr.LinkedIssueService;
import com.jinloes.prpilot.sidecar.pr.PrDetailService;
import com.jinloes.prpilot.sidecar.pr.PrDiffService;
import com.jinloes.prpilot.sidecar.pr.PrListService;
import com.jinloes.prpilot.sidecar.pr.PrSupplementalService;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
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
    private interface MethodHandler {
        JsonNode handle(JsonNode request);
    }

    private final ObjectMapper objectMapper;
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
        handlers.put("repo/detect", this::detectRepo);
        handlers.put("github/checkAuth", this::checkGitHubAuth);
        handlers.put("prs/list", this::listPullRequests);
        handlers.put("prs/search", this::searchPullRequests);
        handlers.put("repos/listStarred", this::listStarredRepositories);
        handlers.put("prs/getDetail", this::getPullRequestDetail);
        handlers.put("prs/getDiff", this::getPullRequestDiff);
        handlers.put("prs/getExistingReviews", this::getExistingReviews);
        handlers.put("prs/getDraftReview", this::getDraftReview);
        handlers.put("prs/saveDraftReview", this::saveDraftReview);
        handlers.put("prs/submitReview", this::submitReview);
        handlers.put("prs/deleteDraftReview", this::deleteDraftReview);
        handlers.put("prs/getCheckStatus", this::getCheckStatus);
        handlers.put("prs/getCommits", this::getCommits);
        handlers.put("prs/getLinkedIssues", this::getLinkedIssues);
        handlers.put("repo/getProfile", this::getRepoProfile);
        handlers.put("reviews/generate", this::generateReview);
        handlers.put("reviews/chat", this::chatReview);
        handlers.put("reviews/cancel", this::cancelReview);
        handlers.put("reviews/recordOutcome", this::recordReviewOutcome);
        handlers.put("reviews/readGuidelines", this::readGuidelines);
        handlers.put("reviews/findGitRoot", this::findGitRoot);
        handlers.put("reviews/createWorktree", this::createWorktree);
        handlers.put("reviews/removeWorktree", this::removeWorktree);
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

    private ObjectNode detectRepo(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || params.size() != 1
                || !params.path("path").isTextual()) {
            return error(requestId(request), -32602, "Invalid params");
        }
        return result(requestId(request), github.detectRepo(params.path("path").textValue()));
    }

    private ObjectNode checkGitHubAuth(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || params.size() != 1
                || !params.path("githubBaseUrl").isTextual()) {
            return error(requestId(request), -32602, "Invalid params");
        }
        return result(
                requestId(request), github.checkAuth(params.path("githubBaseUrl").textValue()));
    }

    private ObjectNode listPullRequests(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || !hasOnlyFields(
                        params, Set.of("githubBaseUrl", "state", "searchScope", "currentRepo"))
                || !hasOnlyTextValues(params)
                || !params.path("githubBaseUrl").isTextual()) {
            return error(requestId(request), -32602, "Invalid params");
        }
        return result(
                requestId(request),
                github.listPullRequests(
                        new PrListService.PrListParams(
                                params.path("githubBaseUrl").textValue(),
                                optionalText(params, "state"),
                                optionalText(params, "searchScope"),
                                optionalText(params, "currentRepo"))));
    }

    private ObjectNode getPullRequestDetail(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || !hasOnlyFields(params, Set.of("githubBaseUrl", "owner", "repo", "number"))
                || !params.path("githubBaseUrl").isTextual()
                || !params.path("owner").isTextual()
                || !params.path("repo").isTextual()
                || !params.path("number").isIntegralNumber()
                || !params.path("number").canConvertToInt()) {
            return error(requestId(request), -32602, "Invalid params");
        }
        return result(
                requestId(request),
                github.getPullRequestDetail(
                        new PrDetailService.PrDetailParams(
                                params.path("githubBaseUrl").textValue(),
                                params.path("owner").textValue(),
                                params.path("repo").textValue(),
                                params.path("number").intValue())));
    }

    private ObjectNode getPullRequestDiff(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || !hasOnlyFields(
                        params, Set.of("githubBaseUrl", "owner", "repo", "number", "mode"))
                || !params.path("githubBaseUrl").isTextual()
                || !params.path("owner").isTextual()
                || !params.path("repo").isTextual()
                || !params.path("number").isInt()
                || !params.path("mode").isTextual())
            return error(requestId(request), -32602, "Invalid params");
        return result(
                requestId(request),
                github.getPullRequestDiff(
                        new PrDiffService.Params(
                                params.path("githubBaseUrl").textValue(),
                                params.path("owner").textValue(),
                                params.path("repo").textValue(),
                                params.path("number").intValue(),
                                params.path("mode").textValue())));
    }

    private ObjectNode searchPullRequests(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || !hasOnlyFields(params, Set.of("githubBaseUrl", "query", "limit"))
                || !params.path("githubBaseUrl").isTextual()
                || !params.path("query").isTextual()
                || !params.path("limit").isIntegralNumber()
                || !params.path("limit").canConvertToInt()) {
            return error(requestId(request), -32602, "Invalid params");
        }
        return result(
                requestId(request),
                github.searchPullRequests(
                        new PrSupplementalService.SearchParams(
                                params.path("githubBaseUrl").textValue(),
                                params.path("query").textValue(),
                                params.path("limit").intValue())));
    }

    private ObjectNode listStarredRepositories(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || params.size() != 1
                || !params.path("githubBaseUrl").isTextual()) {
            return error(requestId(request), -32602, "Invalid params");
        }
        return result(
                requestId(request),
                github.listStarredRepositories(params.path("githubBaseUrl").textValue()));
    }

    private ObjectNode getExistingReviews(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || !hasOnlyFields(params, Set.of("githubBaseUrl", "owner", "repo", "number"))
                || !params.path("githubBaseUrl").isTextual()
                || !params.path("owner").isTextual()
                || !params.path("repo").isTextual()
                || !params.path("number").isIntegralNumber()
                || !params.path("number").canConvertToInt()) {
            return error(requestId(request), -32602, "Invalid params");
        }
        return result(
                requestId(request),
                github.getExistingReviews(
                        new PrSupplementalService.IdentityParams(
                                params.path("githubBaseUrl").textValue(),
                                params.path("owner").textValue(),
                                params.path("repo").textValue(),
                                params.path("number").intValue())));
    }

    private ObjectNode getDraftReview(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || !hasOnlyFields(params, Set.of("githubBaseUrl", "owner", "repo", "number"))
                || !params.path("githubBaseUrl").isTextual()
                || !params.path("owner").isTextual()
                || !params.path("repo").isTextual()
                || !params.path("number").isIntegralNumber()
                || !params.path("number").canConvertToInt()) {
            return error(requestId(request), -32602, "Invalid params");
        }
        return result(
                requestId(request),
                github.getDraftReview(
                        params.path("githubBaseUrl").textValue(),
                        params.path("owner").textValue(),
                        params.path("repo").textValue(),
                        params.path("number").intValue()));
    }

    private ObjectNode saveDraftReview(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || !hasOnlyFields(
                        params,
                        Set.of(
                                "githubBaseUrl",
                                "owner",
                                "repo",
                                "number",
                                "summary",
                                "verdict",
                                "lineComments",
                                "orphans"))
                || !params.path("githubBaseUrl").isTextual()
                || !params.path("owner").isTextual()
                || !params.path("repo").isTextual()
                || !params.path("number").isIntegralNumber()
                || !params.path("number").canConvertToInt()
                || !params.path("summary").isTextual()
                || !params.path("verdict").isTextual()) {
            return error(requestId(request), -32602, "Invalid params");
        }
        List<DraftReviewMutationService.CommentInput> lineComments =
                parseComments(params.path("lineComments"));
        List<DraftReviewMutationService.CommentInput> orphans =
                params.has("orphans") ? parseComments(params.path("orphans")) : List.of();
        if (lineComments == null || orphans == null) {
            return error(requestId(request), -32602, "Invalid params");
        }
        return result(
                requestId(request),
                github.saveDraftReview(
                        new DraftReviewMutationService.SaveParams(
                                params.path("githubBaseUrl").textValue(),
                                params.path("owner").textValue(),
                                params.path("repo").textValue(),
                                params.path("number").intValue(),
                                params.path("summary").textValue(),
                                params.path("verdict").textValue(),
                                lineComments,
                                orphans)));
    }

    private ObjectNode submitReview(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || !hasOnlyFields(
                        params,
                        Set.of(
                                "githubBaseUrl",
                                "owner",
                                "repo",
                                "number",
                                "reviewId",
                                "event",
                                "body"))
                || !params.path("githubBaseUrl").isTextual()
                || !params.path("owner").isTextual()
                || !params.path("repo").isTextual()
                || !params.path("number").isIntegralNumber()
                || !params.path("number").canConvertToInt()
                || !params.path("reviewId").isTextual()
                || !params.path("event").isTextual()
                || !params.path("body").isTextual()) {
            return error(requestId(request), -32602, "Invalid params");
        }
        return result(
                requestId(request),
                github.submitReview(
                        new DraftReviewMutationService.SubmitParams(
                                params.path("githubBaseUrl").textValue(),
                                params.path("owner").textValue(),
                                params.path("repo").textValue(),
                                params.path("number").intValue(),
                                params.path("reviewId").textValue(),
                                params.path("event").textValue(),
                                params.path("body").textValue())));
    }

    private ObjectNode deleteDraftReview(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || !hasOnlyFields(
                        params, Set.of("githubBaseUrl", "owner", "repo", "number", "reviewId"))
                || !params.path("githubBaseUrl").isTextual()
                || !params.path("owner").isTextual()
                || !params.path("repo").isTextual()
                || !params.path("number").isIntegralNumber()
                || !params.path("number").canConvertToInt()
                || !params.path("reviewId").isTextual()) {
            return error(requestId(request), -32602, "Invalid params");
        }
        return result(
                requestId(request),
                github.deleteDraftReview(
                        new DraftReviewMutationService.DeleteParams(
                                params.path("githubBaseUrl").textValue(),
                                params.path("owner").textValue(),
                                params.path("repo").textValue(),
                                params.path("number").intValue(),
                                params.path("reviewId").textValue())));
    }

    private ObjectNode getCheckStatus(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || !hasOnlyFields(params, Set.of("githubBaseUrl", "owner", "repo", "headSha"))
                || !hasOnlyTextValues(params)
                || !params.path("githubBaseUrl").isTextual()
                || !params.path("owner").isTextual()
                || !params.path("repo").isTextual()
                || !params.path("headSha").isTextual()) {
            return error(requestId(request), -32602, "Invalid params");
        }
        return result(
                requestId(request),
                github.getCheckStatus(
                        new CheckRunService.Params(
                                params.path("githubBaseUrl").textValue(),
                                params.path("owner").textValue(),
                                params.path("repo").textValue(),
                                params.path("headSha").textValue())));
    }

    private ObjectNode getCommits(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || !hasOnlyFields(params, Set.of("githubBaseUrl", "owner", "repo", "number"))
                || !params.path("githubBaseUrl").isTextual()
                || !params.path("owner").isTextual()
                || !params.path("repo").isTextual()
                || !params.path("number").isIntegralNumber()
                || !params.path("number").canConvertToInt()) {
            return error(requestId(request), -32602, "Invalid params");
        }
        return result(
                requestId(request),
                github.getCommits(
                        new PrSupplementalService.IdentityParams(
                                params.path("githubBaseUrl").textValue(),
                                params.path("owner").textValue(),
                                params.path("repo").textValue(),
                                params.path("number").intValue())));
    }

    private ObjectNode getLinkedIssues(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || !hasOnlyFields(
                        params,
                        Set.of("githubBaseUrl", "owner", "repo", "prBody", "commitIssueNumbers"))
                || !params.path("githubBaseUrl").isTextual()
                || !params.path("owner").isTextual()
                || !params.path("repo").isTextual()
                || !params.path("prBody").isTextual()) {
            return error(requestId(request), -32602, "Invalid params");
        }
        List<Integer> commitIssueNumbers =
                parseCommitIssueNumbers(params.path("commitIssueNumbers"));
        if (commitIssueNumbers == null) {
            return error(requestId(request), -32602, "Invalid params");
        }
        return result(
                requestId(request),
                github.getLinkedIssues(
                        new LinkedIssueService.Params(
                                params.path("githubBaseUrl").textValue(),
                                params.path("owner").textValue(),
                                params.path("repo").textValue(),
                                params.path("prBody").textValue(),
                                commitIssueNumbers)));
    }

    private ObjectNode getRepoProfile(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || params.size() != 1
                || !params.path("projectDir").isTextual()) {
            return error(requestId(request), -32602, "Invalid params");
        }
        return result(
                requestId(request), github.getRepoProfile(params.path("projectDir").textValue()));
    }

    /**
     * Reads repository guidance docs. {@code globs} is optional: omitting it (or sending an empty
     * array) selects the engine's default file list, so a client never carries its own copy.
     */
    private ObjectNode readGuidelines(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || !hasOnlyFields(params, Set.of("projectDir", "globs"))
                || !params.path("projectDir").isTextual()) {
            return error(requestId(request), -32602, "Invalid params");
        }
        JsonNode globsNode = params.path("globs");
        if (!globsNode.isMissingNode() && !globsNode.isNull() && !globsNode.isArray()) {
            return error(requestId(request), -32602, "Invalid params");
        }
        List<String> globs = new ArrayList<>();
        if (globsNode.isArray()) {
            for (JsonNode glob : globsNode) {
                if (!glob.isTextual()) {
                    return error(requestId(request), -32602, "Invalid params");
                }
                globs.add(glob.textValue());
            }
        }
        return result(
                requestId(request),
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
            return error(requestId(request), -32602, "Invalid params");
        }
        return result(requestId(request), review.findGitRoot(params.path("startDir").textValue()));
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

    private ObjectNode createWorktree(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || !hasOnlyFields(
                        params, Set.of("gitRoot", "prNumber", "branch", "headSha", "forkCloneUrl"))
                || !params.path("gitRoot").isTextual()
                || !params.path("prNumber").isInt()
                || !params.path("branch").isTextual()
                || !isTextualOrAbsent(params.path("headSha"))
                || !isTextualOrAbsent(params.path("forkCloneUrl"))) {
            return error(requestId(request), -32602, "Invalid params");
        }
        return result(
                requestId(request),
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
                || !hasOnlyFields(params, Set.of("gitRoot", "worktreeDir"))
                || !params.path("gitRoot").isTextual()
                || !params.path("worktreeDir").isTextual()) {
            return error(requestId(request), -32602, "Invalid params");
        }
        return result(
                requestId(request),
                review.removeWorktree(
                        new ReviewEngineApi.RemoveWorktreeParams(
                                params.path("gitRoot").textValue(),
                                params.path("worktreeDir").textValue())));
    }

    /** Optional string field: present and textual, or absent/null. */
    private static boolean isTextualOrAbsent(JsonNode node) {
        return node.isMissingNode() || node.isNull() || node.isTextual();
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
                || !hasOnlyFields(
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
            return error(requestId(request), -32602, "Invalid params");
        }
        List<ReviewEngineApi.OutcomeCommentParam> generated =
                parseOutcomeComments(params.path("generated"));
        List<ReviewEngineApi.OutcomeCommentParam> submitted =
                parseOutcomeComments(params.path("submitted"));
        if (generated == null || submitted == null) {
            return error(requestId(request), -32602, "Invalid params");
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
        return result(requestId(request), recorded);
    }

    /**
     * Parses an outcome comment array, or returns {@code null} if the shape is invalid. A missing
     * array is treated as empty: one side of the diff being absent is meaningful (an all-deleted or
     * all-added review), not an error.
     */
    private List<ReviewEngineApi.OutcomeCommentParam> parseOutcomeComments(JsonNode array) {
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

    /**
     * Parses a comment array into strict {@link DraftReviewMutationService.CommentInput}s, or
     * returns {@code null} if the shape is invalid (missing required fields or wrong types).
     */
    private List<DraftReviewMutationService.CommentInput> parseComments(JsonNode array) {
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

    private boolean hasOnlyFields(JsonNode object, Set<String> allowedFields) {
        return object.properties().stream()
                .allMatch(entry -> allowedFields.contains(entry.getKey()));
    }

    private boolean hasOnlyTextValues(JsonNode object) {
        return object.properties().stream().allMatch(entry -> entry.getValue().isTextual());
    }

    private List<Integer> parseCommitIssueNumbers(JsonNode array) {
        if (!array.isArray() || array.size() > LinkedIssueService.MAX_ISSUES) return null;
        List<Integer> issueNumbers = new ArrayList<>();
        for (JsonNode number : array) {
            if (!number.isIntegralNumber() || !number.canConvertToInt()) {
                return null;
            }
            int value = number.intValue();
            if (value <= 0
                    || value > LinkedIssueService.MAX_ISSUE_NUMBER
                    || issueNumbers.contains(value)) {
                return null;
            }
            issueNumbers.add(value);
        }
        return List.copyOf(issueNumbers);
    }

    private String optionalText(JsonNode object, String field) {
        JsonNode value = object.get(field);
        return value == null ? null : value.textValue();
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
