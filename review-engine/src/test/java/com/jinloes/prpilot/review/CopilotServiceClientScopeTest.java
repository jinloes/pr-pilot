package com.jinloes.prpilot.review;

import static com.jinloes.prpilot.review.CopilotServiceTestSupport.fakeRequest;
import static com.jinloes.prpilot.review.CopilotServiceTestSupport.reviewJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jinloes.prpilot.review.CopilotServiceTestSupport.FakeRuntimeClient;
import com.jinloes.prpilot.review.CopilotServiceTestSupport.FakeRuntimeFactory;
import com.jinloes.prpilot.review.CopilotServiceTestSupport.FakeRuntimeSession;
import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for {@link CopilotService#openClientScope()}: multi-session client reuse and recovery. */
class CopilotServiceClientScopeTest {

    @Nested
    class OpenClientScope {

        @Test
        void reusesOneStartedClientAcrossConcurrentAndSequentialSessions() throws Exception {
            List<FakeRuntimeClient> clients = new CopyOnWriteArrayList<>();
            List<FakeRuntimeSession> sessions = new CopyOnWriteArrayList<>();
            CountDownLatch concurrent = new CountDownLatch(2);
            FakeRuntimeFactory factory =
                    new FakeRuntimeFactory(
                            () -> {
                                FakeRuntimeClient client =
                                        new FakeRuntimeClient(
                                                req -> {
                                                    FakeRuntimeSession session =
                                                            new FakeRuntimeSession();
                                                    session.sendAction =
                                                            s -> {
                                                                if (s.lastPrompt.startsWith(
                                                                        "parallel-")) {
                                                                    concurrent.countDown();
                                                                    try {
                                                                        concurrent.await(
                                                                                5,
                                                                                TimeUnit.SECONDS);
                                                                    } catch (
                                                                            InterruptedException
                                                                                    e) {
                                                                        Thread.currentThread()
                                                                                .interrupt();
                                                                    }
                                                                }
                                                                s.emitMessage(
                                                                        reviewJson("APPROVE"));
                                                            };
                                                    sessions.add(session);
                                                    return session;
                                                });
                                clients.add(client);
                                return client;
                            });
            CopilotService service = new CopilotService(null, factory);

            try (Closeable outer = service.openClientScope();
                    Closeable nested = service.openClientScope()) {
                Thread first =
                        new Thread(
                                () -> {
                                    try {
                                        service.completeReviewPrompt(
                                                "parallel-1",
                                                "",
                                                "high",
                                                false,
                                                null,
                                                false,
                                                5_000,
                                                ignored -> {});
                                    } catch (Exception e) {
                                        throw new AssertionError(e);
                                    }
                                });
                first.start();
                service.completeReviewPrompt(
                        "parallel-2", "", "high", false, null, false, 5_000, ignored -> {});
                first.join(5_000);
                service.reviewPass(
                        fakeRequest(), "claude-sonnet-4.6", "high", ignored -> {}, null, false, "");

                assertThat(clients).hasSize(1);
                assertThat(clients.get(0).started).isTrue();
                assertThat(clients.get(0).closed).isFalse();
                nested.close();
                assertThat(clients.get(0).closed).isFalse();
            }

            assertThat(clients)
                    .singleElement()
                    .satisfies(client -> assertThat(client.closed).isTrue());
            assertThat(sessions)
                    .hasSize(3)
                    .allSatisfy(session -> assertThat(session.closeCount.get()).isEqualTo(1));
        }

        @Test
        void invalidatesAndReplacesSharedClientWhenSessionCreationFails() throws Exception {
            AtomicInteger createdClients = new AtomicInteger();
            FakeRuntimeFactory factory =
                    new FakeRuntimeFactory(
                            () -> {
                                int index = createdClients.incrementAndGet();
                                return new FakeRuntimeClient(
                                        req -> {
                                            if (index == 1) {
                                                throw new RuntimeException("broken daemon pipe");
                                            }
                                            FakeRuntimeSession session = new FakeRuntimeSession();
                                            session.sendResult = "recovered";
                                            return session;
                                        });
                            });
            CopilotService service = new CopilotService(null, factory);

            try (Closeable ignored = service.openClientScope()) {
                assertThatThrownBy(
                                () ->
                                        service.completeReviewPrompt(
                                                "first", "", "high", false, null, false, 5_000,
                                                s -> {}))
                        .isInstanceOf(IOException.class)
                        .hasMessageContaining("broken daemon pipe");

                String second =
                        service.completeReviewPrompt(
                                "second", "", "high", false, null, false, 5_000, s -> {});
                assertThat(second).isEqualTo("recovered");
                assertThat(createdClients).hasValue(2);
            }
        }
    }
}
