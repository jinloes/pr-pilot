package com.jinloes.prpilot.sidecar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.engine.GitHubEngineApi;
import com.jinloes.prpilot.engine.ReviewEngineApi;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Frames JSON-RPC over stdio and dispatches each request to the handler registered for its method.
 * Handlers live in the {@code StdioJsonRpcServer*Handlers} classes; background review work runs
 * through {@link StdioJsonRpcServerOperationScheduler}.
 */
final class StdioJsonRpcServer {
    private static final Logger log = LoggerFactory.getLogger(StdioJsonRpcServer.class);
    private static final String JSON_RPC_VERSION = "2.0";
    private static final int REQUEST_QUEUE_CAPACITY = 64;
    private static final int SERVER_BUSY_CODE = -32000;
    private static final int RESPONSE_TOO_LARGE_CODE = -32000;
    static final String RESPONSE_TOO_LARGE_MESSAGE =
            "JSON-RPC response exceeded maximum frame size (8 MiB)";

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
    private final Object outcomeLogLock = new Object();
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
                "initialize",
                request ->
                        support.result(support.requestId(request), bootstrapService.initialize()));
        new StdioJsonRpcServerGitHubHandlers(support, github).register(handlers);
        new StdioJsonRpcServerReviewHandlers(support, review, outcomeLogLock).register(handlers);
        new StdioJsonRpcServerAsyncReviewHandlers(
                        objectMapper,
                        support,
                        review,
                        new StdioJsonRpcServerOperationScheduler(
                                support, reviewExecutor, this::send))
                .register(handlers);
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
                    log.warn("Malformed JSON-RPC frame ({} bytes)", frame.length, exception);
                    send(support.error(null, -32700, "Parse error"));
                    continue;
                }
                if (!isValidRequest(request)) {
                    send(support.error(support.requestId(request), -32600, "Invalid Request"));
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
                        send(
                                support.error(
                                        support.requestId(request),
                                        SERVER_BUSY_CODE,
                                        "Server busy"));
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
            log.warn("Malformed JSON-RPC frame ({} bytes)", frame.length, exception);
            return support.error(null, -32700, "Parse error");
        }

        if (!isValidRequest(request)) {
            return support.error(support.requestId(request), -32600, "Invalid Request");
        }

        return handleRequest(request);
    }

    private JsonNode handleRequest(JsonNode request) {
        boolean notification = !request.has("id");
        String method = request.path("method").asText();
        MethodHandler handler = handlers.get(method);
        JsonNode response;
        try {
            response =
                    handler == null
                            ? support.error(support.requestId(request), -32601, "Method not found")
                            : handler.handle(request);
        } catch (RuntimeException exception) {
            log.error(
                    "Unhandled exception in JSON-RPC method '{}' (id={})",
                    method,
                    support.requestId(request),
                    exception);
            response = support.error(support.requestId(request), -32603, "Internal error");
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
     * Writes a frame to the shared stdout stream under {@link #writeLock} so the main read loop and
     * background {@link #reviewExecutor} tasks never interleave partial frames. If a JSON-RPC
     * response exceeds {@link StdioFrameCodec#MAX_MESSAGE_BYTES}, emits a compact {@code -32000}
     * error frame for the same request ID so callers fail fast instead of hanging; oversized
     * notifications are logged and dropped. Genuine broken-pipe errors are logged at debug level.
     */
    private void send(JsonNode node) {
        OutputStream output = this.currentOutput;
        if (output == null) return;
        boolean isResponse = node != null && node.has("id") && !node.has("method");
        synchronized (writeLock) {
            try {
                frameCodec.writeFrame(output, objectMapper.writeValueAsBytes(node));
            } catch (StdioFrameCodec.FrameTooLargeException tooLarge) {
                if (isResponse) {
                    JsonNode id = node.get("id");
                    log.warn(
                            "JSON-RPC response for id={} exceeded maximum frame size ({} bytes); sending fallback error",
                            id,
                            tooLarge.payloadLength());
                    try {
                        JsonNode fallback =
                                support.error(
                                        id, RESPONSE_TOO_LARGE_CODE, RESPONSE_TOO_LARGE_MESSAGE);
                        frameCodec.writeFrame(output, objectMapper.writeValueAsBytes(fallback));
                    } catch (IOException fallbackFailure) {
                        log.debug(
                                "Failed to write fallback oversized-response error frame",
                                fallbackFailure);
                    }
                } else {
                    log.warn(
                            "Dropping oversized JSON-RPC notification method='{}' ({} bytes)",
                            node == null ? "<null>" : node.path("method").asText("<unknown>"),
                            tooLarge.payloadLength());
                }
            } catch (IOException exception) {
                log.debug("Failed to write JSON-RPC frame to stdout", exception);
            }
        }
    }

    private boolean isValidRequest(JsonNode request) {
        return request != null
                && request.isObject()
                && JSON_RPC_VERSION.equals(request.path("jsonrpc").asText())
                && request.path("method").isTextual();
    }

    private static final class SidecarProtocolException extends RuntimeException {
        private SidecarProtocolException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
