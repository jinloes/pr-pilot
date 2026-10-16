package com.jinloes.prpilot.review;

import static com.jinloes.prpilot.review.CopilotServiceTestSupport.factoryFor;
import static com.jinloes.prpilot.review.CopilotServiceTestSupport.fakeRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jinloes.prpilot.review.CopilotServiceTestSupport.FakeRuntimeClient;
import com.jinloes.prpilot.review.CopilotServiceTestSupport.FakeRuntimeFactory;
import com.jinloes.prpilot.review.CopilotServiceTestSupport.FakeRuntimeSession;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Cancellation tests for {@link CopilotService}: one cancel aborts every concurrent run. */
class CopilotServiceCancellationTest {

    @Nested
    class CancelCurrentRequest {

        @Test
        void cancellationBeforeClientPublicationPreventsRuntimeStartup() {
            CancellationToken token = new CancellationToken();
            token.cancel();
            AtomicInteger created = new AtomicInteger();
            CopilotService service =
                    new CopilotService(
                            null,
                            factoryFor(
                                    () -> {
                                        created.incrementAndGet();
                                        return new FakeRuntimeSession();
                                    }),
                            token);

            assertThatThrownBy(() -> service.chatWithPrompt("prompt", "medium", ignored -> {}))
                    .isInstanceOf(InterruptedException.class);
            assertThat(created).hasValue(0);
        }

        @Test
        void noActiveRunDoesNotThrow() {
            new CopilotService().cancelCurrentRequest();
        }

        private FakeRuntimeSession blockingSession(CountDownLatch started, CountDownLatch release) {
            FakeRuntimeSession session = new FakeRuntimeSession();
            session.sendAction =
                    ignored -> {
                        started.countDown();
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    };
            session.sendFailure = new IOException("cancelled by test");
            return session;
        }

        private Thread chatInBackground(
                CopilotService service, String prompt, AtomicReference<Throwable> failure) {
            Thread worker =
                    new Thread(
                            () -> {
                                try {
                                    service.chatWithPrompt(prompt, "medium", ignored -> {});
                                } catch (Throwable t) {
                                    failure.set(t);
                                }
                            });
            worker.start();
            return worker;
        }

        @Test
        void oneCancelStopsEveryConcurrentRun() throws Exception {
            CountDownLatch started = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            List<FakeRuntimeSession> sessions = new CopyOnWriteArrayList<>();
            List<FakeRuntimeClient> clients = new CopyOnWriteArrayList<>();
            FakeRuntimeFactory factory =
                    new FakeRuntimeFactory(
                            () -> {
                                FakeRuntimeClient client =
                                        new FakeRuntimeClient(
                                                request -> {
                                                    FakeRuntimeSession session =
                                                            blockingSession(started, release);
                                                    sessions.add(session);
                                                    return session;
                                                });
                                clients.add(client);
                                return client;
                            });
            CopilotService service = new CopilotService(null, factory);
            AtomicReference<Throwable> firstFailure = new AtomicReference<>();
            AtomicReference<Throwable> secondFailure = new AtomicReference<>();

            Thread first = chatInBackground(service, "first", firstFailure);
            Thread second = chatInBackground(service, "second", secondFailure);
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                service.cancelCurrentRequest();
            } finally {
                release.countDown();
            }
            first.join(5_000);
            second.join(5_000);

            assertThat(sessions).hasSize(2);
            assertThat(sessions).allSatisfy(s -> assertThat(s.abortCount.get()).isEqualTo(1));
            assertThat(clients).allSatisfy(c -> assertThat(c.forceStopCount.get()).isEqualTo(1));
            assertThat(firstFailure.get()).isInstanceOf(InterruptedException.class);
            assertThat(secondFailure.get()).isInstanceOf(InterruptedException.class);
        }

        @Test
        void aFinishedRunDoesNotUntrackAnotherRun() throws Exception {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            FakeRuntimeSession blocking = blockingSession(started, release);
            FakeRuntimeSession quick = new FakeRuntimeSession();
            quick.sendResult = "done";
            CopilotService service =
                    new CopilotService(
                            null,
                            factoryFor(() -> calls.getAndIncrement() == 0 ? blocking : quick));
            AtomicReference<Throwable> failure = new AtomicReference<>();

            Thread worker = chatInBackground(service, "slow", failure);
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(service.chatWithPrompt("fast", "medium", ignored -> {}))
                        .isEqualTo("done");
                service.cancelCurrentRequest();
            } finally {
                release.countDown();
            }
            worker.join(5_000);

            assertThat(blocking.abortCount.get()).isEqualTo(1);
            assertThat(quick.abortCount.get()).isZero();
            assertThat(failure.get()).isInstanceOf(InterruptedException.class);
        }

        @Test
        void activeRunAbortsTheSessionForceStopsTheClientAndInterruptsTheRequest()
                throws Exception {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicReference<FakeRuntimeClient> clientRef = new AtomicReference<>();
            AtomicReference<FakeRuntimeSession> sessionRef = new AtomicReference<>();
            FakeRuntimeFactory factory =
                    factoryFor(
                            () -> {
                                FakeRuntimeSession s = new FakeRuntimeSession();
                                sessionRef.set(s);
                                s.sendAction =
                                        session -> {
                                            started.countDown();
                                            try {
                                                release.await(5, TimeUnit.SECONDS);
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                            }
                                        };
                                s.sendFailure = new IOException("cancelled by test");
                                return s;
                            });
            CopilotService svc = new CopilotService(null, factory);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            Thread worker =
                    new Thread(
                            () -> {
                                try {
                                    svc.reviewPR(fakeRequest(), "", "medium", ignored -> {});
                                } catch (Throwable t) {
                                    failure.set(t);
                                }
                            });
            worker.start();

            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            svc.cancelCurrentRequest();
            release.countDown();
            worker.join(5_000);

            clientRef.set(factory.lastClient);
            assertThat(sessionRef.get().abortCount.get()).isEqualTo(1);
            assertThat(clientRef.get().forceStopCount.get()).isEqualTo(1);
            assertThat(failure.get())
                    .isInstanceOf(InterruptedException.class)
                    .hasMessage("copilot request cancelled");
        }

        @Test
        void cancellationDuringRuntimeStartupCancelsThePublishedRun() throws Exception {
            CountDownLatch startupEntered = new CountDownLatch(1);
            CountDownLatch releaseStartup = new CountDownLatch(1);
            FakeRuntimeFactory factory =
                    new FakeRuntimeFactory(
                            () -> {
                                FakeRuntimeClient client = new FakeRuntimeClient();
                                client.startAction =
                                        () -> {
                                            startupEntered.countDown();
                                            try {
                                                releaseStartup.await(5, TimeUnit.SECONDS);
                                            } catch (InterruptedException exception) {
                                                Thread.currentThread().interrupt();
                                            }
                                        };
                                return client;
                            });
            CopilotService service = new CopilotService(null, factory);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker =
                    new Thread(
                            () -> {
                                try {
                                    service.chatWithPrompt("prompt", "medium", ignored -> {});
                                } catch (Throwable throwable) {
                                    failure.set(throwable);
                                }
                            });

            worker.start();
            try {
                assertThat(startupEntered.await(5, TimeUnit.SECONDS)).isTrue();
                service.cancelCurrentRequest();
            } finally {
                releaseStartup.countDown();
            }
            worker.join(5_000);

            assertThat(worker.isAlive()).isFalse();
            assertThat(factory.lastClient.forceStopCount.get()).isEqualTo(1);
            assertThat(failure.get())
                    .isInstanceOf(InterruptedException.class)
                    .hasMessage("Review request cancelled.");
        }
    }
}
