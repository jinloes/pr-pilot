package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.copilot.rpc.ModelInfo;
import com.github.copilot.rpc.ModelPolicy;
import com.jinloes.prpilot.review.CopilotModelDiscovery.Result;
import com.jinloes.prpilot.review.CopilotModelDiscovery.Source;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Java port of the former core/jvmTest Kotest suite for CopilotModelDiscovery. */
class CopilotModelDiscoveryTest {

    /** Sample taken verbatim from `copilot help config` v1.0.54. */
    private static final String REAL_HELP_SAMPLE =
            "`keepAlive`: keep-alive mode applied at CLI startup; prevents the system from sleeping"
                    + " while the session is active. Defaults to `\"off\"`.\n\n"
                    + "  `model`: AI model to use for Copilot CLI; can be changed with /model command or"
                    + " --model flag option.\n"
                    + "    - \"claude-sonnet-4.6\"\n"
                    + "    - \"claude-sonnet-4.5\"\n"
                    + "    - \"claude-haiku-4.5\"\n"
                    + "    - \"claude-opus-4.7\"\n"
                    + "    - \"claude-opus-4.6\"\n"
                    + "    - \"claude-opus-4.6-fast\"\n"
                    + "    - \"claude-opus-4.5\"\n"
                    + "    - \"gpt-5.5\"\n"
                    + "    - \"gpt-5.4\"\n"
                    + "    - \"gpt-5.3-codex\"\n"
                    + "    - \"gpt-5.2-codex\"\n"
                    + "    - \"gpt-5.2\"\n"
                    + "    - \"gpt-5.4-mini\"\n\n"
                    + "  `mouse`: whether to enable mouse support in alt screen mode; defaults to `true`"
                    + " on macOS, `false` elsewhere.\n";

    @Nested
    class ParseModelsFromHelp {

        @Test
        void realHelpSampleExtractsAllModelIdsInOrder() {
            List<String> models = CopilotModelDiscovery.parseModelsFromHelp(REAL_HELP_SAMPLE);
            assertThat(models)
                    .containsExactly(
                            "claude-sonnet-4.6",
                            "claude-sonnet-4.5",
                            "claude-haiku-4.5",
                            "claude-opus-4.7",
                            "claude-opus-4.6",
                            "claude-opus-4.6-fast",
                            "claude-opus-4.5",
                            "gpt-5.5",
                            "gpt-5.4",
                            "gpt-5.3-codex",
                            "gpt-5.2-codex",
                            "gpt-5.2",
                            "gpt-5.4-mini");
        }

        @Test
        void sectionEndsAtBlankLineBeforeNextSetting() {
            String help =
                    "`model`: AI model to use for Copilot CLI.\n  - \"a\"\n  - \"b\"\n\n`theme`: theme to color and"
                            + " stylize output; defaults to \"auto\".\n  - \"auto\"\n  - \"dark\"\n";
            assertThat(CopilotModelDiscovery.parseModelsFromHelp(help)).containsExactly("a", "b");
        }

        @Test
        void noModelSectionReturnsEmptyList() {
            String help =
                    "`theme`: theme to color and stylize output; defaults to \"auto\".\n  - \"auto\"\n  - \"dark\"\n";
            assertThat(CopilotModelDiscovery.parseModelsFromHelp(help)).isEmpty();
        }

        @Test
        void emptyHelpTextReturnsEmptyList() {
            assertThat(CopilotModelDiscovery.parseModelsFromHelp("")).isEmpty();
        }

        @Test
        void modelSectionPresentButNoItemsReturnsEmptyList() {
            String help = "`model`: AI model to use for Copilot CLI.\n(none configured)\n";
            assertThat(CopilotModelDiscovery.parseModelsFromHelp(help)).isEmpty();
        }

        @Test
        void nonQuotedBulletsAreIgnored() {
            String help =
                    "`model`: AI model to use for Copilot CLI.\n  - plain-text\n  - \"real-id\"\n";
            assertThat(CopilotModelDiscovery.parseModelsFromHelp(help)).containsExactly("real-id");
        }

        @Test
        void descriptionContinuationBetweenHeaderAndBulletsStillFindsItems() {
            String help =
                    "`model`: AI model to use for Copilot CLI.\n  Long wrapped description that continues here without"
                            + " dashes.\n  - \"first\"\n  - \"second\"\n";
            assertThat(CopilotModelDiscovery.parseModelsFromHelp(help))
                    .containsExactly("first", "second");
        }

        @Test
        void backtickRequiredBareModelHeadingIsNotMatched() {
            String help = "model: bare heading without backticks\n  - \"should-not-match\"\n";
            assertThat(CopilotModelDiscovery.parseModelsFromHelp(help)).isEmpty();
        }
    }

    @Nested
    class Discover {

        @Test
        void liveCatalogWinsOverHelpFallback() {
            AtomicInteger helpCalls = new AtomicInteger();

            Result result =
                    CopilotModelDiscovery.discover(
                            () -> List.of("claude-opus-5.5", "gpt-5.5"),
                            () -> {
                                helpCalls.incrementAndGet();
                                return List.of("stale");
                            });

            assertThat(result.models()).containsExactly("claude-opus-5.5", "gpt-5.5");
            assertThat(result.source()).isEqualTo(Source.ACCOUNT);
            assertThat(result.failureReason()).isEmpty();
            assertThat(helpCalls).hasValue(0);
        }

        @Test
        void liveFailureFallsBackToHelpAndKeepsTheReason() {
            Result result =
                    CopilotModelDiscovery.discover(
                            () -> {
                                throw new ExecutionException(new IOException("not signed in"));
                            },
                            () -> List.of("claude-opus-5"));

            assertThat(result.models()).containsExactly("claude-opus-5");
            assertThat(result.source()).isEqualTo(Source.CLI_HELP);
            assertThat(result.failureReason()).isEqualTo("not signed in");
        }

        @Test
        void emptyLiveCatalogFallsBackToHelp() {
            Result result = CopilotModelDiscovery.discover(List::of, () -> List.of("a"));

            assertThat(result.source()).isEqualTo(Source.CLI_HELP);
            assertThat(result.failureReason()).contains("no enabled models");
        }

        @Test
        void timeoutIsReportedInPlainLanguage() {
            Result result =
                    CopilotModelDiscovery.discover(
                            () -> {
                                throw new TimeoutException();
                            },
                            List::of);

            assertThat(result.source()).isEqualTo(Source.NONE);
            assertThat(result.models()).isEmpty();
            assertThat(result.failureReason()).isEqualTo("Copilot did not respond in time");
        }

        @Test
        void interruptionRestoresTheFlagAndSkipsTheFallback() {
            try {
                Result result =
                        CopilotModelDiscovery.discover(
                                () -> {
                                    throw new InterruptedException();
                                },
                                () -> List.of("should-not-run"));

                assertThat(result.source()).isEqualTo(Source.NONE);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Nested
    class FilterModelIds {

        @Test
        void dropsDisabledBlankAndDuplicateIdsPreservingOrder() {
            List<ModelInfo> models =
                    List.of(
                            model("auto", null),
                            model("claude-opus-5.5", "enabled"),
                            model("blocked", "disabled"),
                            model("  ", "enabled"),
                            model(null, "enabled"),
                            model("gpt-5.3-codex", "unconfigured"),
                            model("claude-opus-5.5", "enabled"));

            assertThat(CopilotModelDiscovery.filterModelIds(models))
                    .containsExactly("auto", "claude-opus-5.5", "gpt-5.3-codex");
        }

        @Test
        void nullListIsEmpty() {
            assertThat(CopilotModelDiscovery.filterModelIds(null)).isEmpty();
        }

        private ModelInfo model(String id, String policyState) {
            ModelInfo info = new ModelInfo().setId(id);
            if (policyState != null) info.setPolicy(new ModelPolicy().setState(policyState));
            return info;
        }
    }

    @Nested
    class RefreshAndCache {

        @BeforeEach
        @AfterEach
        void resetCache() {
            CopilotModelDiscovery.invalidate();
        }

        @Test
        void successfulRefreshIsCachedAndServedByListModels() {
            Result fresh = new Result(List.of("m1", "m2"), Source.ACCOUNT, "");

            assertThat(CopilotModelDiscovery.refresh(() -> fresh)).isEqualTo(fresh);

            assertThat(CopilotModelDiscovery.cached()).isEqualTo(fresh);
            assertThat(CopilotModelDiscovery.listModels()).containsExactly("m1", "m2");
        }

        @Test
        void refreshReplacesAStaleCachedList() {
            CopilotModelDiscovery.refresh(() -> new Result(List.of("old"), Source.CLI_HELP, "x"));

            CopilotModelDiscovery.refresh(() -> new Result(List.of("new"), Source.ACCOUNT, ""));

            assertThat(CopilotModelDiscovery.cached().models()).containsExactly("new");
        }

        @Test
        void failedRefreshKeepsThePreviousGoodList() {
            Result good = new Result(List.of("m1"), Source.ACCOUNT, "");
            CopilotModelDiscovery.refresh(() -> good);

            Result failed = CopilotModelDiscovery.refresh(() -> Result.none("offline"));

            assertThat(failed.failureReason()).isEqualTo("offline");
            assertThat(CopilotModelDiscovery.cached()).isEqualTo(good);
        }

        @Test
        void failuresAreNotCachedSoTheNextRefreshRetries() {
            CopilotModelDiscovery.refresh(() -> Result.none("offline"));

            assertThat(CopilotModelDiscovery.cached()).isNull();
        }

        @Test
        void discovererExceptionBecomesAFailedResult() {
            Result result =
                    CopilotModelDiscovery.refresh(
                            () -> {
                                throw new IllegalStateException("boom");
                            });

            assertThat(result.source()).isEqualTo(Source.NONE);
            assertThat(result.failureReason()).isEqualTo("boom");
        }

        @Test
        void concurrentRefreshesShareOneProbe() throws Exception {
            CountDownLatch probeStarted = new CountDownLatch(1);
            CountDownLatch releaseProbe = new CountDownLatch(1);
            AtomicInteger probes = new AtomicInteger();
            Supplier<Result> slow =
                    () -> {
                        probes.incrementAndGet();
                        probeStarted.countDown();
                        try {
                            releaseProbe.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return new Result(List.of("m1"), Source.ACCOUNT, "");
                    };
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<Result> first = pool.submit(() -> CopilotModelDiscovery.refresh(slow));
                assertThat(probeStarted.await(5, TimeUnit.SECONDS)).isTrue();
                AtomicReference<Result> secondResult = new AtomicReference<>();
                Thread second =
                        new Thread(() -> secondResult.set(CopilotModelDiscovery.refresh(slow)));
                second.start();
                // The joining caller parks on the in-flight future; wait for that before releasing.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (second.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                assertThat(second.getState()).isEqualTo(Thread.State.WAITING);

                releaseProbe.countDown();

                assertThat(first.get(5, TimeUnit.SECONDS).models()).containsExactly("m1");
                second.join(5_000);
                assertThat(secondResult.get().models()).containsExactly("m1");
                assertThat(probes).hasValue(1);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Nested
    class Probe {

        @Test
        void hangingProcessIsTerminatedAtTheConfiguredTimeout() {
            StubProcess process = StubProcess.hanging();
            long started = System.nanoTime();

            List<String> result = CopilotModelDiscovery.probe(ignored -> process, 1);

            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(result).isEmpty();
            assertThat(elapsedMs).isLessThan(3_000);
            assertThat(process.isAlive()).isFalse();
        }

        @Test
        void finiteLargeOutputIsDrainedBeforeParsing() {
            String help =
                    "`model`: AI model to use for Copilot CLI.\n"
                            + "x".repeat(100_000)
                            + "\n  - \"model-a\"\n";

            List<String> result =
                    CopilotModelDiscovery.probe(ignored -> StubProcess.completed(help, 0), 1);

            assertThat(result).containsExactly("model-a");
        }

        @Test
        void interruptionRestoresTheThreadFlagAndTerminatesTheProcess() {
            StubProcess process = StubProcess.interrupting();
            try {
                List<String> result = CopilotModelDiscovery.probe(ignored -> process, 1);

                assertThat(result).isEmpty();
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
                assertThat(process.isAlive()).isFalse();
            } finally {
                Thread.interrupted();
            }
        }
    }

    private static final class StubProcess extends Process {
        private final InputStream input;
        private final int exitCode;
        private final boolean interruptWait;
        private volatile boolean alive;

        private StubProcess(InputStream input, int exitCode, boolean alive, boolean interruptWait) {
            this.input = input;
            this.exitCode = exitCode;
            this.alive = alive;
            this.interruptWait = interruptWait;
        }

        static StubProcess completed(String output, int exitCode) {
            return new StubProcess(
                    new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)),
                    exitCode,
                    false,
                    false);
        }

        static StubProcess hanging() {
            return new StubProcess(new BlockingInputStream(), 0, true, false);
        }

        static StubProcess interrupting() {
            return new StubProcess(new BlockingInputStream(), 0, true, true);
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() throws InterruptedException {
            if (interruptWait) throw new InterruptedException("interrupted");
            return exitCode;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            if (interruptWait) throw new InterruptedException("interrupted");
            if (alive) unit.sleep(timeout);
            return !alive;
        }

        @Override
        public int exitValue() {
            if (alive) throw new IllegalThreadStateException("still running");
            return exitCode;
        }

        @Override
        public void destroy() {
            alive = false;
            try {
                input.close();
            } catch (IOException ignored) {
                // Test process only.
            }
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }
    }

    private static final class BlockingInputStream extends InputStream {
        private boolean closed;

        @Override
        public synchronized int read() throws IOException {
            while (!closed) {
                try {
                    wait();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", exception);
                }
            }
            return -1;
        }

        @Override
        public synchronized void close() {
            closed = true;
            notifyAll();
        }
    }
}
