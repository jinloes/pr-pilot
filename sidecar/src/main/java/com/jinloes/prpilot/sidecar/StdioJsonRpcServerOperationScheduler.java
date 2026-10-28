package com.jinloes.prpilot.sidecar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs cancellable background operations keyed by client operation ID. Every operation produces
 * exactly one terminal response; once cancelled, that response is the cancellation message and
 * progress notifications stop.
 */
final class StdioJsonRpcServerOperationScheduler {
    static final String UNFINISHED_MESSAGE =
            "The operation ended unexpectedly without a result. Try again.";
    private static final Logger log =
            LoggerFactory.getLogger(StdioJsonRpcServerOperationScheduler.class);
    private final StdioJsonRpcServerSupport support;
    private final ExecutorService reviewExecutor;
    private final Consumer<JsonNode> writer;
    private final Object operationLock = new Object();
    private final ConcurrentMap<String, ScheduledOperation> scheduledOperations =
            new ConcurrentHashMap<>();

    /** {@code writer} sends one frame to the client and must be safe to call from any thread. */
    StdioJsonRpcServerOperationScheduler(
            StdioJsonRpcServerSupport support,
            ExecutorService reviewExecutor,
            Consumer<JsonNode> writer) {
        this.support = support;
        this.reviewExecutor = reviewExecutor;
        this.writer = writer;
    }

    /** Returns {@code false}, without scheduling, when {@code operationId} is already in flight. */
    boolean submit(
            String operationId,
            JsonNode requestId,
            boolean responseRequired,
            String cancellationMessage,
            Consumer<ScheduledOperation> operation) {
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
                                } catch (RuntimeException | Error failure) {
                                    // The executor's Future would swallow this, leaving the
                                    // client waiting on a response that never comes.
                                    log.error("Operation {} failed", operationId, failure);
                                } finally {
                                    if (scheduled.completeIfUnfinished(UNFINISHED_MESSAGE)) {
                                        log.error(
                                                "Operation {} ended without a response",
                                                operationId);
                                    }
                                    scheduledOperations.remove(operationId, scheduled);
                                }
                            });
            scheduled.future = future;
            scheduledOperations.put(operationId, scheduled);
            return true;
        }
    }

    /** Returns whether an unfinished operation was found and cancelled. */
    boolean cancel(String operationId) {
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

    /** One in-flight operation; its handler reports progress and completion through it. */
    final class ScheduledOperation {
        private final JsonNode requestId;
        private final boolean responseRequired;
        private final String cancellationMessage;
        private final Consumer<ScheduledOperation> operation;
        private final AtomicBoolean completed = new AtomicBoolean();
        private volatile Future<?> future;
        private volatile boolean started;
        private volatile boolean cancelled;

        private ScheduledOperation(
                JsonNode requestId,
                boolean responseRequired,
                String cancellationMessage,
                Consumer<ScheduledOperation> operation) {
            this.requestId = requestId;
            this.responseRequired = responseRequired;
            this.cancellationMessage = cancellationMessage;
            this.operation = operation;
        }

        boolean isCancelled() {
            return cancelled;
        }

        void sendNotification(String method, ObjectNode params) {
            if (!isCancelled()) writer.accept(support.notification(method, params));
        }

        void complete(JsonNode response) {
            JsonNode terminalResponse;
            synchronized (operationLock) {
                if (!completed.compareAndSet(false, true)) return;
                terminalResponse =
                        isCancelled()
                                ? support.error(requestId, -32000, cancellationMessage)
                                : response;
            }
            if (responseRequired) {
                writer.accept(terminalResponse);
            }
        }

        void completeCancellation() {
            complete(support.error(requestId, -32000, cancellationMessage));
        }

        /** Sends an error response unless one was already sent; returns whether it sent one. */
        boolean completeIfUnfinished(String message) {
            if (completed.get()) return false;
            complete(support.error(requestId, -32000, message));
            return true;
        }
    }
}
