package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.model.ReviewResult;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Java port of the former core/jvmTest Kotest suite for CopilotService. */
class CopilotServiceTest {

    private static PullRequest fakePr() {
        return new PullRequest(
                "T", "https://github.com/o/r/pull/1", "o", "r", 1, "", "a", "2024-01-01");
    }

    private static PRReviewRequest fakeRequest() {
        return new PRReviewRequest(fakePr(), "");
    }

    private static String reviewJson(String verdict) {
        return "{\"summary\":\"s\",\"verdict\":\"" + verdict + "\",\"lineComments\":[]}";
    }

    private static final class FakeRuntimeFactory implements CopilotService.RuntimeFactory {
        private final Supplier<FakeRuntimeClient> clientProvider;
        private volatile CopilotService.ClientRequest lastClientRequest;
        private volatile FakeRuntimeClient lastClient;

        FakeRuntimeFactory(Supplier<FakeRuntimeClient> clientProvider) {
            this.clientProvider = clientProvider;
        }

        FakeRuntimeFactory() {
            this(FakeRuntimeClient::new);
        }

        @Override
        public CopilotService.RuntimeClient createClient(CopilotService.ClientRequest request) {
            lastClientRequest = request;
            FakeRuntimeClient client = clientProvider.get();
            lastClient = client;
            return client;
        }
    }

    private static final class FakeRuntimeClient implements CopilotService.RuntimeClient {
        private final java.util.function.Function<CopilotService.SessionRequest, FakeRuntimeSession>
                sessionProvider;
        private volatile boolean started;
        private volatile boolean closed;
        private final AtomicReference<Integer> forceStopCount = new AtomicReference<>(0);
        private volatile CopilotService.SessionRequest lastSessionRequest;
        private volatile FakeRuntimeSession lastSession;
        volatile Runnable startAction = () -> {};

        FakeRuntimeClient(
                java.util.function.Function<CopilotService.SessionRequest, FakeRuntimeSession>
                        sessionProvider) {
            this.sessionProvider = sessionProvider;
        }

        FakeRuntimeClient() {
            this(request -> new FakeRuntimeSession());
        }

        @Override
        public void start() {
            started = true;
            startAction.run();
        }

        @Override
        public CopilotService.RuntimeSession createSession(CopilotService.SessionRequest request) {
            lastSessionRequest = request;
            FakeRuntimeSession session = sessionProvider.apply(request);
            lastSession = session;
            return session;
        }

        @Override
        public void forceStop() {
            forceStopCount.updateAndGet(v -> v + 1);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class FakeRuntimeSession implements CopilotService.RuntimeSession {
        private final List<Consumer<String>> deltaListeners = new ArrayList<>();
        private final List<Consumer<String>> messageListeners = new ArrayList<>();
        private final List<Consumer<String>> toolListeners = new ArrayList<>();
        private final List<Consumer<String>> errorListeners = new ArrayList<>();

        private volatile String lastPrompt;
        private volatile Long lastTimeoutMs;
        private final AtomicReference<Integer> abortCount = new AtomicReference<>(0);
        private final AtomicReference<Integer> closeCount = new AtomicReference<>(0);
        Exception sendFailure;
        String sendResult;
        Consumer<FakeRuntimeSession> sendAction = ignored -> {};

        @Override
        public Closeable onAssistantMessageDelta(Consumer<String> listener) {
            return register(deltaListeners, listener);
        }

        @Override
        public Closeable onAssistantMessage(Consumer<String> listener) {
            return register(messageListeners, listener);
        }

        @Override
        public Closeable onToolExecutionStart(Consumer<String> listener) {
            return register(toolListeners, listener);
        }

        @Override
        public Closeable onSessionError(Consumer<String> listener) {
            return register(errorListeners, listener);
        }

        @Override
        public String sendAndWait(String prompt, long timeoutMs) throws Exception {
            lastPrompt = prompt;
            lastTimeoutMs = timeoutMs;
            sendAction.accept(this);
            if (sendFailure != null) throw sendFailure;
            return sendResult;
        }

        @Override
        public void abort() {
            abortCount.updateAndGet(v -> v + 1);
        }

        @Override
        public void close() {
            closeCount.updateAndGet(v -> v + 1);
        }

        void emitDelta(String text) {
            deltaListeners.forEach(l -> l.accept(text));
        }

        void emitMessage(String text) {
            messageListeners.forEach(l -> l.accept(text));
        }

        void emitTool(String name) {
            toolListeners.forEach(l -> l.accept(name));
        }

        void emitError(String message) {
            errorListeners.forEach(l -> l.accept(message));
        }

        int listenerCount() {
            return deltaListeners.size()
                    + messageListeners.size()
                    + toolListeners.size()
                    + errorListeners.size();
        }

        private Closeable register(List<Consumer<String>> listeners, Consumer<String> listener) {
            listeners.add(listener);
            return () -> listeners.remove(listener);
        }
    }

    private static FakeRuntimeFactory factoryFor(Supplier<FakeRuntimeSession> sessionSupplier) {
        return new FakeRuntimeFactory(() -> new FakeRuntimeClient(req -> sessionSupplier.get()));
    }

    @Nested
    class PermissionDecision {

        @Test
        void allowsReadOnlyDeniesMutatingTools() {
            assertThat(CopilotService.permissionDecision("read", false).getKind())
                    .isEqualTo("approve-once");
            assertThat(CopilotService.permissionDecision("shell", false).getKind())
                    .isEqualTo("reject");
            assertThat(CopilotService.permissionDecision("write", false).getKind())
                    .isEqualTo("reject");
            assertThat(CopilotService.permissionDecision("mcp", false).getKind())
                    .isEqualTo("reject");
        }

        @Test
        void onlyApprovesMcpAfterExplicitElevation() {
            assertThat(CopilotService.permissionDecision("mcp", true).getKind())
                    .isEqualTo("approve-once");
            assertThat(CopilotService.permissionDecision("write", true).getKind())
                    .isEqualTo("reject");
        }

        @Test
        void deniesReadToolsForToolFreeSupervisorSessions() {
            assertThat(CopilotService.permissionDecision("read", false, false).getKind())
                    .isEqualTo("reject");
        }
    }

    @Nested
    class NormalizeReasoningEffort {

        @Test
        void blankOrUnknownDefaultsToHigh() {
            assertThat(CopilotService.normalizeReasoningEffort("")).isEqualTo("high");
            assertThat(CopilotService.normalizeReasoningEffort("  ")).isEqualTo("high");
            assertThat(CopilotService.normalizeReasoningEffort(null)).isEqualTo("high");
            assertThat(CopilotService.normalizeReasoningEffort("turbo")).isEqualTo("high");
        }

        @Test
        void supportedSdkValuesPassThroughLowercased() {
            assertThat(CopilotService.normalizeReasoningEffort("LOW")).isEqualTo("low");
            assertThat(CopilotService.normalizeReasoningEffort("medium")).isEqualTo("medium");
            assertThat(CopilotService.normalizeReasoningEffort("High")).isEqualTo("high");
            assertThat(CopilotService.normalizeReasoningEffort("xhigh")).isEqualTo("xhigh");
        }

        @Test
        void legacyCliOnlyValuesMapToNearestSdkValue() {
            assertThat(CopilotService.normalizeReasoningEffort("none")).isEqualTo("low");
            assertThat(CopilotService.normalizeReasoningEffort("max")).isEqualTo("xhigh");
        }
    }

    @Nested
    class ReviewPr {

        @Test
        void buildsClientAndSessionRequestsFromSettingsAndProjectDir() throws Exception {
            FakeRuntimeFactory factory =
                    factoryFor(
                            () -> {
                                FakeRuntimeSession s = new FakeRuntimeSession();
                                s.sendAction =
                                        session -> session.emitMessage(reviewJson("APPROVE"));
                                return s;
                            });
            CopilotService svc = new CopilotService("/tmp/pr-pilot-repo", factory);

            svc.reviewPR(fakeRequest(), "claude-sonnet-4.6", "high", ignored -> {});

            CopilotService.ClientRequest clientRequest = factory.lastClientRequest;
            assertThat(clientRequest).isNotNull();
            assertThat(clientRequest.cliPath()).isEqualTo(CopilotService.findCopilotBinary());
            assertThat(clientRequest.workingDir()).isEqualTo(new File("/tmp/pr-pilot-repo"));
            assertThat(clientRequest.environment().get("HOME"))
                    .isEqualTo(System.getProperty("user.home", "/"));
            assertThat(clientRequest.environment().get("PATH"))
                    .isEqualTo(
                            BinaryLocator.providerPath(
                                    System.getProperty("user.home", "/"),
                                    System.getenv().getOrDefault("PATH", "")));

            FakeRuntimeClient client = factory.lastClient;
            assertThat(client.started).isTrue();
            assertThat(client.closed).isTrue();
            CopilotService.SessionRequest sessionRequest = client.lastSessionRequest;
            assertThat(sessionRequest).isNotNull();
            assertThat(sessionRequest.model()).isEqualTo("claude-sonnet-4.6");
            assertThat(sessionRequest.effort()).isEqualTo("high");
            assertThat(sessionRequest.workingDir()).isEqualTo(new File("/tmp/pr-pilot-repo"));
            assertThat(sessionRequest.inheritMcp()).isFalse();
            assertThat(sessionRequest.configDir()).isNull();
            assertThat(sessionRequest.allowReadTools()).isTrue();

            FakeRuntimeSession session = client.lastSession;
            assertThat(session).isNotNull();
            assertThat(session.lastPrompt).contains("<pr_diff>");
            assertThat(session.lastTimeoutMs).isEqualTo(30L * 60L * 1000L);
            assertThat(session.closeCount.get()).isEqualTo(1);
        }

        @Test
        void toolFreeCompletionUsesItsBoundedTimeout() throws Exception {
            FakeRuntimeFactory factory =
                    factoryFor(
                            () -> {
                                FakeRuntimeSession session = new FakeRuntimeSession();
                                session.sendAction = current -> current.emitMessage("{}");
                                return session;
                            });
            CopilotService service = new CopilotService(null, factory);

            service.completeReviewPrompt(
                    "supervise", "", "high", false, null, false, 90_000, ignored -> {});

            assertThat(factory.lastClient.lastSessionRequest.allowReadTools()).isFalse();
            assertThat(factory.lastClient.lastSession.lastTimeoutMs).isEqualTo(90_000);
        }

        @Test
        void threadsMcpInheritanceFlagAndConfigDirOverrideIntoTheSessionRequest() throws Exception {
            FakeRuntimeFactory factory =
                    factoryFor(
                            () -> {
                                FakeRuntimeSession s = new FakeRuntimeSession();
                                s.sendAction =
                                        session -> session.emitMessage(reviewJson("APPROVE"));
                                return s;
                            });
            CopilotService svc = new CopilotService(null, factory);

            svc.reviewPR(
                    fakeRequest(),
                    "",
                    "medium",
                    ignored -> {},
                    null,
                    false,
                    "  /custom/.copilot  ");

            CopilotService.SessionRequest sessionRequest = factory.lastClient.lastSessionRequest;
            assertThat(sessionRequest.inheritMcp()).isFalse();
            assertThat(sessionRequest.configDir()).isEqualTo("/custom/.copilot");
        }

        @Test
        void blankConfigDirOverrideResolvesToNull() throws Exception {
            FakeRuntimeFactory factory =
                    factoryFor(
                            () -> {
                                FakeRuntimeSession s = new FakeRuntimeSession();
                                s.sendAction =
                                        session -> session.emitMessage(reviewJson("APPROVE"));
                                return s;
                            });
            CopilotService svc = new CopilotService(null, factory);

            svc.reviewPR(fakeRequest(), "", "medium", ignored -> {}, null, false, "   ");

            assertThat(factory.lastClient.lastSessionRequest.configDir()).isNull();
        }

        @Test
        void selfCritiqueRunsSecondPassEmbeddingTheFirstReview() throws Exception {
            FakeRuntimeFactory factory =
                    factoryFor(
                            () -> {
                                FakeRuntimeSession s = new FakeRuntimeSession();
                                s.sendAction =
                                        session -> session.emitMessage(reviewJson("APPROVE"));
                                return s;
                            });
            CopilotService svc = new CopilotService(null, factory);

            svc.reviewPR(fakeRequest(), "", "high", ignored -> {}, null, false, null, true);

            // The second (critique) pass is the last client/session; its prompt embeds the draft.
            assertThat(factory.lastClient.lastSession.lastPrompt).contains("<draft_review>");
        }

        @Test
        void selfCritiqueDisabledRunsSinglePassWithoutDraftReview() throws Exception {
            FakeRuntimeFactory factory =
                    factoryFor(
                            () -> {
                                FakeRuntimeSession s = new FakeRuntimeSession();
                                s.sendAction =
                                        session -> session.emitMessage(reviewJson("APPROVE"));
                                return s;
                            });
            CopilotService svc = new CopilotService(null, factory);

            svc.reviewPR(fakeRequest(), "", "high", ignored -> {}, null, false, null, false);

            assertThat(factory.lastClient.lastSession.lastPrompt).doesNotContain("<draft_review>");
        }

        @Test
        void streamsTextDeltasAsChunksToolNamesAsStatusesAndParsesFinalAssistantMessage()
                throws Exception {
            FakeRuntimeFactory factory =
                    factoryFor(
                            () -> {
                                FakeRuntimeSession s = new FakeRuntimeSession();
                                s.sendAction =
                                        session -> {
                                            session.emitDelta("{\"summary\":\"draft\"");
                                            session.emitTool("view");
                                            session.emitMessage(reviewJson("APPROVE"));
                                        };
                                return s;
                            });
            CopilotService svc = new CopilotService(null, factory);
            List<String> statuses = new ArrayList<>();
            List<String[]> chunks = new ArrayList<>();

            ReviewResult result =
                    svc.reviewPR(
                            fakeRequest(),
                            "",
                            "medium",
                            statuses::add,
                            (kind, chunk) -> chunks.add(new String[] {kind, chunk}),
                            false,
                            null);

            assertThat(result.getVerdict()).isEqualTo("APPROVE");
            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0)).containsExactly("text", "{\"summary\":\"draft\"");
            assertThat(statuses).containsExactly("Generating review…", "view", "Parsing review…");
        }

        @Test
        void fallsBackToAccumulatedDeltasWhenNoFinalAssistantMessageArrives() throws Exception {
            FakeRuntimeFactory factory =
                    factoryFor(
                            () -> {
                                FakeRuntimeSession s = new FakeRuntimeSession();
                                s.sendAction =
                                        session -> {
                                            session.emitDelta("{\"summary\":\"s\",");
                                            session.emitDelta("\"verdict\":\"COMMENT\",");
                                            session.emitDelta("\"lineComments\":[]}");
                                        };
                                return s;
                            });
            CopilotService svc = new CopilotService(null, factory);
            List<String> chunks = new ArrayList<>();

            ReviewResult result =
                    svc.reviewPR(
                            fakeRequest(),
                            "",
                            "medium",
                            ignored -> {},
                            (kind, chunk) -> chunks.add(chunk),
                            false,
                            null);

            assertThat(result.getVerdict()).isEqualTo("COMMENT");
            assertThat(chunks)
                    .containsExactly(
                            "{\"summary\":\"s\",",
                            "\"verdict\":\"COMMENT\",",
                            "\"lineComments\":[]}");
        }

        @Test
        void surfacesSessionErrorWhenTheRuntimeEmitsNoOutput() {
            FakeRuntimeFactory factory =
                    factoryFor(
                            () -> {
                                FakeRuntimeSession s = new FakeRuntimeSession();
                                s.sendAction =
                                        session ->
                                                session.emitError(
                                                        "Copilot account is not authorized for this model");
                                return s;
                            });
            CopilotService svc = new CopilotService(null, factory);

            assertThatThrownBy(() -> svc.reviewPR(fakeRequest(), "", "medium", ignored -> {}))
                    .isInstanceOf(IOException.class)
                    .hasMessage("Copilot account is not authorized for this model");
        }

        @Test
        void emptyOutputWithNoSessionErrorProducedNoOutputMessage() {
            FakeRuntimeFactory factory = new FakeRuntimeFactory();
            CopilotService svc = new CopilotService(null, factory);

            assertThatThrownBy(() -> svc.reviewPR(fakeRequest(), "", "medium", ignored -> {}))
                    .isInstanceOf(IOException.class)
                    .hasMessage("copilot produced no output.");
        }

        @Test
        void runtimeFailureWrappedInExecutionExceptionUnwrapsCauseMessage() {
            FakeRuntimeFactory factory =
                    factoryFor(
                            () -> {
                                FakeRuntimeSession s = new FakeRuntimeSession();
                                s.sendFailure =
                                        new ExecutionException(
                                                new IOException("policy denied tool access"));
                                return s;
                            });
            CopilotService svc = new CopilotService(null, factory);

            assertThatThrownBy(() -> svc.reviewPR(fakeRequest(), "", "medium", ignored -> {}))
                    .isInstanceOf(IOException.class)
                    .hasMessage("policy denied tool access");
        }

        @Test
        void runtimeFailureClosesAllSdkEventSubscriptions() {
            AtomicReference<FakeRuntimeSession> sessionRef = new AtomicReference<>();
            FakeRuntimeFactory factory =
                    factoryFor(
                            () -> {
                                FakeRuntimeSession s = new FakeRuntimeSession();
                                sessionRef.set(s);
                                s.sendFailure = new IOException("send failed");
                                return s;
                            });
            CopilotService svc = new CopilotService(null, factory);

            assertThatThrownBy(() -> svc.reviewPR(fakeRequest(), "", "medium", ignored -> {}))
                    .isInstanceOf(IOException.class);

            assertThat(sessionRef.get().listenerCount()).isEqualTo(0);
        }

        @Test
        void nonJsonOutputParseError() {
            String sensitiveOutput = "not even close to JSON";
            FakeRuntimeFactory factory =
                    factoryFor(
                            () -> {
                                FakeRuntimeSession s = new FakeRuntimeSession();
                                s.sendAction = session -> session.emitMessage(sensitiveOutput);
                                return s;
                            });
            CopilotService svc = new CopilotService(null, factory);

            assertThatThrownBy(() -> svc.reviewPR(fakeRequest(), "", "medium", ignored -> {}))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Failed to parse review JSON")
                    .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain(sensitiveOutput));
        }

        @Test
        void legacyEffortValuesNormalizeBeforeCreatingTheSession() throws Exception {
            FakeRuntimeFactory lowFactory =
                    factoryFor(
                            () -> {
                                FakeRuntimeSession s = new FakeRuntimeSession();
                                s.sendAction =
                                        session -> session.emitMessage(reviewJson("APPROVE"));
                                return s;
                            });
            FakeRuntimeFactory xhighFactory =
                    factoryFor(
                            () -> {
                                FakeRuntimeSession s = new FakeRuntimeSession();
                                s.sendAction =
                                        session -> session.emitMessage(reviewJson("APPROVE"));
                                return s;
                            });

            new CopilotService(null, lowFactory).reviewPR(fakeRequest(), "", "none", ignored -> {});
            new CopilotService(null, xhighFactory)
                    .reviewPR(fakeRequest(), "", "max", ignored -> {});

            assertThat(lowFactory.lastClient.lastSessionRequest.effort()).isEqualTo("low");
            assertThat(xhighFactory.lastClient.lastSessionRequest.effort()).isEqualTo("xhigh");
        }
    }

    @Nested
    class Chat {

        @Test
        void streamsDeltaChunksAndReturnsTheFinalAssistantMessage() throws Exception {
            FakeRuntimeFactory factory =
                    factoryFor(
                            () -> {
                                FakeRuntimeSession s = new FakeRuntimeSession();
                                s.sendAction =
                                        session -> {
                                            session.emitDelta("Hello ");
                                            session.emitDelta("there");
                                            session.emitMessage("Hello there.");
                                        };
                                return s;
                            });
            CopilotService svc = new CopilotService(null, factory);
            StringBuilder collected = new StringBuilder();
            String result = svc.chat("", List.of(), "hi", "medium", collected::append);
            assertThat(result).isEqualTo("Hello there.");
            assertThat(collected.toString()).isEqualTo("Hello there");
        }

        @Test
        void fallsBackToDeltaBufferWhenTheSdkNeverEmitsAFinalMessage() throws Exception {
            FakeRuntimeFactory factory =
                    factoryFor(
                            () -> {
                                FakeRuntimeSession s = new FakeRuntimeSession();
                                s.sendAction =
                                        session -> {
                                            session.emitDelta("Hello ");
                                            session.emitDelta("again");
                                        };
                                return s;
                            });
            CopilotService svc = new CopilotService(null, factory);
            StringBuilder collected = new StringBuilder();

            String result = svc.chat("", List.of(), "q", "medium", collected::append);

            assertThat(result).isEqualTo("Hello again");
            assertThat(collected.toString()).isEqualTo("Hello again");
        }

        @Test
        void runtimeFailureSurfacesTheUnderlyingMessage() {
            FakeRuntimeFactory factory =
                    factoryFor(
                            () -> {
                                FakeRuntimeSession s = new FakeRuntimeSession();
                                s.sendFailure = new IOException("tool sandbox startup failed");
                                return s;
                            });
            CopilotService svc = new CopilotService(null, factory);

            assertThatThrownBy(() -> svc.chat("", List.of(), "q", "medium", ignored -> {}))
                    .isInstanceOf(IOException.class)
                    .hasMessage("tool sandbox startup failed");
        }
    }

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

    @Nested
    class FindCopilotBinary {

        @Test
        void returnsANonBlankPath() {
            assertThat(CopilotService.findCopilotBinary()).isNotBlank();
        }
    }

    @Nested
    class DefaultReasoningEffort {

        @Test
        void isHighForDeeperReviewReasoning() {
            assertThat(CopilotService.DEFAULT_REASONING_EFFORT).isEqualTo("high");
        }
    }

    @Nested
    class AwaitWithTimeout {

        @Test
        void completedFutureReturnsValue() throws IOException {
            CompletableFuture<String> future = CompletableFuture.completedFuture("ready");
            assertThat(CopilotService.awaitWithTimeout(future, "runtime startup"))
                    .isEqualTo("ready");
        }

        @Test
        void incompleteFutureWrapsTimeoutAsIOException() {
            CompletableFuture<String> future = new CompletableFuture<>();
            assertThatThrownBy(() -> CopilotService.awaitWithTimeout(future, "session creation"))
                    .isInstanceOf(IOException.class)
                    .hasMessage("copilot session creation timed out after 60s");
        }

        @Test
        void interruptedWaitRestoresTheThreadInterruptStatus() {
            CompletableFuture<String> future = new CompletableFuture<>();
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> CopilotService.awaitWithTimeout(future, "runtime startup"))
                        .isInstanceOf(IOException.class)
                        .hasMessage("copilot runtime startup failed")
                        .hasCauseInstanceOf(InterruptedException.class);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
        }
    }
}
