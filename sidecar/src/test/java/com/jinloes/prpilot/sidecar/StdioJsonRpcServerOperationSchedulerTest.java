package com.jinloes.prpilot.sidecar;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class StdioJsonRpcServerOperationSchedulerTest {
    private static final JsonNode REQUEST_ID = IntNode.valueOf(7);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StdioJsonRpcServerSupport support = new StdioJsonRpcServerSupport(objectMapper);
    private final List<JsonNode> written = new CopyOnWriteArrayList<>();
    private ExecutorService executor;
    private StdioJsonRpcServerOperationScheduler scheduler;

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        scheduler = new StdioJsonRpcServerOperationScheduler(support, executor, written::add);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    /** Occupies the single executor thread so the next submission stays queued until released. */
    private CountDownLatch occupyExecutor() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.submit(
                () -> {
                    started.countDown();
                    release.await();
                    return null;
                });
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        return release;
    }

    /** Waits until every task submitted so far, including its cleanup, has finished. */
    private void drainExecutor() throws Exception {
        executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
    }

    private static void awaitInterruption() {
        try {
            new CountDownLatch(1).await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean submitResult(String operationId, String result) {
        return scheduler.submit(
                operationId,
                REQUEST_ID,
                true,
                "Interrupted.",
                operation -> operation.complete(support.result(REQUEST_ID, result)));
    }

    private List<String> writtenResults() {
        return written.stream().map(response -> response.path("result").asText()).toList();
    }

    @Nested
    class Submit {
        @Test
        void runsTheOperationAndWritesItsTerminalResponse() throws Exception {
            boolean accepted = submitResult("op-1", "done");
            drainExecutor();

            assertThat(accepted).isTrue();
            assertThat(written).hasSize(1);
            assertThat(written.get(0).path("id").asInt()).isEqualTo(7);
            assertThat(written.get(0).path("result").asText()).isEqualTo("done");
        }

        @Test
        void rejectsAnOperationIdAlreadyInFlight() throws Exception {
            CountDownLatch release = occupyExecutor();

            boolean first = submitResult("op-1", "first");
            boolean duplicate = submitResult("op-1", "second");
            release.countDown();
            drainExecutor();

            assertThat(first).isTrue();
            assertThat(duplicate).isFalse();
            assertThat(writtenResults()).containsExactly("first");
        }

        @Test
        void acceptsTheSameOperationIdAgainOnceTheFirstRunFinishes() throws Exception {
            submitResult("op-1", "first");
            drainExecutor();

            boolean reused = submitResult("op-1", "second");
            drainExecutor();

            assertThat(reused).isTrue();
            assertThat(writtenResults()).containsExactly("first", "second");
        }

        @Test
        void writesNothingWhenNoResponseIsRequired() throws Exception {
            scheduler.submit(
                    "op-1",
                    REQUEST_ID,
                    false,
                    "Interrupted.",
                    operation -> operation.complete(support.result(REQUEST_ID, "done")));
            drainExecutor();

            assertThat(written).isEmpty();
        }
    }

    @Nested
    class Cancel {
        @Test
        void returnsFalseForAnUnknownOperation() {
            assertThat(scheduler.cancel("missing")).isFalse();
            assertThat(written).isEmpty();
        }

        @Test
        void completesAQueuedOperationWithItsCancellationMessageWithoutRunningIt()
                throws Exception {
            CountDownLatch release = occupyExecutor();
            AtomicBoolean ran = new AtomicBoolean();
            scheduler.submit(
                    "op-1", REQUEST_ID, true, "Review interrupted.", operation -> ran.set(true));

            boolean cancelled = scheduler.cancel("op-1");
            release.countDown();
            drainExecutor();

            assertThat(cancelled).isTrue();
            assertThat(ran).isFalse();
            assertThat(written).hasSize(1);
            assertThat(written.get(0).path("id").asInt()).isEqualTo(7);
            assertThat(written.get(0).path("error").path("code").asInt()).isEqualTo(-32000);
            assertThat(written.get(0).path("error").path("message").asText())
                    .isEqualTo("Review interrupted.");
        }

        @Test
        void turnsARunningOperationsResponseIntoTheCancellationMessage() throws Exception {
            CountDownLatch started = new CountDownLatch(1);
            scheduler.submit(
                    "op-1",
                    REQUEST_ID,
                    true,
                    "Chat interrupted.",
                    operation -> {
                        started.countDown();
                        awaitInterruption();
                        operation.complete(support.result(REQUEST_ID, "late result"));
                    });
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

            boolean cancelled = scheduler.cancel("op-1");
            drainExecutor();

            assertThat(cancelled).isTrue();
            assertThat(written).hasSize(1);
            assertThat(written.get(0).has("result")).isFalse();
            assertThat(written.get(0).path("error").path("message").asText())
                    .isEqualTo("Chat interrupted.");
        }

        @Test
        void leavesAnAlreadyCompletedOperationsResponseAlone() throws Exception {
            CountDownLatch completed = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            scheduler.submit(
                    "op-1",
                    REQUEST_ID,
                    true,
                    "Interrupted.",
                    operation -> {
                        operation.complete(support.result(REQUEST_ID, "done"));
                        completed.countDown();
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    });
            assertThat(completed.await(1, TimeUnit.SECONDS)).isTrue();

            boolean cancelled = scheduler.cancel("op-1");
            release.countDown();
            drainExecutor();

            assertThat(cancelled).isFalse();
            assertThat(writtenResults()).containsExactly("done");
        }
    }

    @Nested
    class SendNotification {
        @Test
        void writesAJsonRpcNotificationWithoutAnId() throws Exception {
            ObjectNode params = objectMapper.createObjectNode().put("message", "Reviewing");
            scheduler.submit(
                    "op-1",
                    REQUEST_ID,
                    true,
                    "Interrupted.",
                    operation -> {
                        operation.sendNotification("reviews/status", params);
                        operation.complete(support.result(REQUEST_ID, "done"));
                    });
            drainExecutor();

            assertThat(written).hasSize(2);
            JsonNode notification = written.get(0);
            assertThat(notification.path("jsonrpc").asText()).isEqualTo("2.0");
            assertThat(notification.path("method").asText()).isEqualTo("reviews/status");
            assertThat(notification.path("params")).isEqualTo(params);
            assertThat(notification.has("id")).isFalse();
            assertThat(written.get(1).path("result").asText()).isEqualTo("done");
        }

        @Test
        void dropsNotificationsOnceCancelled() throws Exception {
            CountDownLatch started = new CountDownLatch(1);
            scheduler.submit(
                    "op-1",
                    REQUEST_ID,
                    true,
                    "Review interrupted.",
                    operation -> {
                        started.countDown();
                        awaitInterruption();
                        operation.sendNotification(
                                "reviews/chunk", objectMapper.createObjectNode());
                        operation.completeCancellation();
                    });
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

            scheduler.cancel("op-1");
            drainExecutor();

            assertThat(written).hasSize(1);
            assertThat(written.get(0).has("method")).isFalse();
            assertThat(written.get(0).path("error").path("message").asText())
                    .isEqualTo("Review interrupted.");
        }
    }

    @Nested
    class Complete {
        @Test
        void writesOnlyTheFirstTerminalResponse() throws Exception {
            scheduler.submit(
                    "op-1",
                    REQUEST_ID,
                    true,
                    "Interrupted.",
                    operation -> {
                        operation.complete(support.result(REQUEST_ID, "first"));
                        operation.complete(support.result(REQUEST_ID, "second"));
                        operation.completeCancellation();
                    });
            drainExecutor();

            assertThat(writtenResults()).containsExactly("first");
        }
    }
}
