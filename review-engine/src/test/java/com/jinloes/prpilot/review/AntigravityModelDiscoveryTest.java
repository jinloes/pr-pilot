package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AntigravityModelDiscoveryTest {

    @BeforeEach
    @AfterEach
    void resetCache() {
        AntigravityModelDiscovery.invalidate();
    }

    @Nested
    class ParseModelsOutput {

        @Test
        void parsesTabSeparatedSlugsDeduplicatesAndSkipsProseLines() {
            String stdout =
                    String.join(
                            "\n",
                            "",
                            "gemini-3.8-flash-high\tGemini 3.8 Flash (High)",
                            "gemini-3.8-flash-low\tGemini 3.8 Flash (Low)",
                            "claude-sonnet-4-6\tClaude Sonnet 4.6",
                            "gemini-3.8-flash-high\tDuplicate entry",
                            "bare-model-slug",
                            "Fetching available models...",
                            "No models available for account");

            assertThat(AntigravityModelDiscovery.parseModelsOutput(stdout))
                    .containsExactly(
                            "gemini-3.8-flash-high",
                            "gemini-3.8-flash-low",
                            "claude-sonnet-4-6",
                            "bare-model-slug");
            assertThat(AntigravityModelDiscovery.parseModelsOutput("   ")).isEmpty();
        }
    }

    @Nested
    class DiscoverAndRefresh {

        @Test
        void cachesSuccessfulAccountDiscoveryAndPreservesCacheOnLaterFailure() {
            AntigravityModelDiscovery.Result ok =
                    AntigravityModelDiscovery.refresh(
                            () ->
                                    AntigravityModelDiscovery.discover(
                                            () -> List.of("gemini-3.8-flash-high")));

            assertThat(ok.source()).isEqualTo(AntigravityModelDiscovery.Source.ACCOUNT);
            assertThat(ok.models()).containsExactly("gemini-3.8-flash-high");
            assertThat(AntigravityModelDiscovery.cached()).isEqualTo(ok);

            AntigravityModelDiscovery.Result failed =
                    AntigravityModelDiscovery.refresh(
                            () ->
                                    AntigravityModelDiscovery.discover(
                                            () -> {
                                                throw new IOException("not signed in");
                                            }));

            assertThat(failed.source()).isEqualTo(AntigravityModelDiscovery.Source.NONE);
            assertThat(failed.failureReason()).isEqualTo("not signed in");
            // Previous good cache remains intact so settings dropdowns do not blank out.
            assertThat(AntigravityModelDiscovery.cached()).isEqualTo(ok);
        }

        @Test
        void coalescesConcurrentRefreshesIntoSingleProbe() throws Exception {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch secondEntered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();

            var pool = Executors.newFixedThreadPool(2);
            try {
                Future<AntigravityModelDiscovery.Result> first =
                        pool.submit(
                                () ->
                                        AntigravityModelDiscovery.refresh(
                                                () -> {
                                                    calls.incrementAndGet();
                                                    entered.countDown();
                                                    try {
                                                        release.await(5, TimeUnit.SECONDS);
                                                    } catch (InterruptedException e) {
                                                        Thread.currentThread().interrupt();
                                                    }
                                                    return new AntigravityModelDiscovery.Result(
                                                            List.of("claude-sonnet-4-6"),
                                                            AntigravityModelDiscovery.Source
                                                                    .ACCOUNT,
                                                            "");
                                                }));
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var secondResult = new AtomicReference<AntigravityModelDiscovery.Result>();
                Thread secondThread =
                        new Thread(
                                () -> {
                                    secondEntered.countDown();
                                    secondResult.set(
                                            AntigravityModelDiscovery.refresh(
                                                    () -> {
                                                        calls.incrementAndGet();
                                                        return AntigravityModelDiscovery.Result
                                                                .none("unexpected");
                                                    }));
                                });
                secondThread.start();
                assertThat(secondEntered.await(5, TimeUnit.SECONDS)).isTrue();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (secondThread.isAlive()
                        && secondThread.getState() != Thread.State.WAITING
                        && secondThread.getState() != Thread.State.TIMED_WAITING
                        && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                release.countDown();
                secondThread.join(5_000);

                assertThat(first.get(5, TimeUnit.SECONDS).models())
                        .containsExactly("claude-sonnet-4-6");
                assertThat(secondResult.get().models()).containsExactly("claude-sonnet-4-6");
                assertThat(calls.get()).isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
