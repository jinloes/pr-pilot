package com.jinloes.prpilot.review;

import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.PullRequest;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Request fixtures and the fake Copilot runtime shared by the {@link CopilotService} tests. */
final class CopilotServiceTestSupport {

    private CopilotServiceTestSupport() {}

    static PullRequest fakePr() {
        return new PullRequest(
                "T", "https://github.com/o/r/pull/1", "o", "r", 1, "", "a", "2024-01-01");
    }

    static PRReviewRequest fakeRequest() {
        return new PRReviewRequest(fakePr(), "");
    }

    static String reviewJson(String verdict) {
        return "{\"summary\":\"s\",\"verdict\":\"" + verdict + "\",\"lineComments\":[]}";
    }

    static final class FakeRuntimeFactory implements CopilotSdkRuntime.RuntimeFactory {
        private final Supplier<FakeRuntimeClient> clientProvider;
        volatile CopilotSdkRuntime.ClientRequest lastClientRequest;
        volatile FakeRuntimeClient lastClient;

        FakeRuntimeFactory(Supplier<FakeRuntimeClient> clientProvider) {
            this.clientProvider = clientProvider;
        }

        FakeRuntimeFactory() {
            this(FakeRuntimeClient::new);
        }

        @Override
        public CopilotSdkRuntime.RuntimeClient createClient(
                CopilotSdkRuntime.ClientRequest request) {
            lastClientRequest = request;
            FakeRuntimeClient client = clientProvider.get();
            lastClient = client;
            return client;
        }
    }

    static final class FakeRuntimeClient implements CopilotSdkRuntime.RuntimeClient {
        private final Function<CopilotSdkRuntime.SessionRequest, FakeRuntimeSession>
                sessionProvider;
        volatile boolean started;
        volatile boolean closed;
        final AtomicReference<Integer> forceStopCount = new AtomicReference<>(0);
        volatile CopilotSdkRuntime.SessionRequest lastSessionRequest;
        volatile FakeRuntimeSession lastSession;
        volatile Runnable startAction = () -> {};

        FakeRuntimeClient(
                Function<CopilotSdkRuntime.SessionRequest, FakeRuntimeSession> sessionProvider) {
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
        public CopilotSdkRuntime.RuntimeSession createSession(
                CopilotSdkRuntime.SessionRequest request) {
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

    static final class FakeRuntimeSession implements CopilotSdkRuntime.RuntimeSession {
        private final List<Consumer<String>> deltaListeners = new ArrayList<>();
        private final List<Consumer<String>> messageListeners = new ArrayList<>();
        private final List<Consumer<String>> toolListeners = new ArrayList<>();
        private final List<Consumer<String>> errorListeners = new ArrayList<>();

        volatile String lastPrompt;
        volatile Long lastTimeoutMs;
        final AtomicReference<Integer> abortCount = new AtomicReference<>(0);
        final AtomicReference<Integer> closeCount = new AtomicReference<>(0);
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

    static FakeRuntimeFactory factoryFor(Supplier<FakeRuntimeSession> sessionSupplier) {
        return new FakeRuntimeFactory(() -> new FakeRuntimeClient(req -> sessionSupplier.get()));
    }
}
