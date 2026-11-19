package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class BoundedProcessRunnerTest {

    @Nested
    class OwnedTree {
        @Test
        void enumerationFailureStillTerminatesPreviouslyRecordedDescendants() throws Exception {
            var alive = new java.util.concurrent.atomic.AtomicBoolean(true);
            ProcessHandle descendant =
                    (ProcessHandle)
                            java.lang.reflect.Proxy.newProxyInstance(
                                    getClass().getClassLoader(),
                                    new Class<?>[] {ProcessHandle.class},
                                    (proxy, method, args) ->
                                            switch (method.getName()) {
                                                case "isAlive" -> alive.get();
                                                case "destroyForcibly" -> {
                                                    alive.set(false);
                                                    yield true;
                                                }
                                                default ->
                                                        throw new AssertionError(method.getName());
                                            });
            var teardown =
                    BoundedProcessRunner.class.getDeclaredMethod(
                            "teardownTree", Process.class, java.util.Map.class);
            teardown.setAccessible(true);
            // The completed stub cannot enumerate handles. Cleanup must nevertheless drain
            // the already-owned set, rather than abandon it on that error.
            assertThatThrownBy(
                            () ->
                                    teardown.invoke(
                                            null,
                                            StubProcess.completed(""),
                                            new java.util.LinkedHashMap<>(
                                                    java.util.Map.of(1L, descendant))))
                    .cause()
                    .isInstanceOf(IOException.class);
            assertThat(alive.get()).isFalse();
        }

        @Test
        void timeoutTerminatesAndWaitsForRecordedChildrenAndGrandchildren() throws Exception {
            var file = Files.createTempFile("owned-tree-", ".pids");
            try {
                BoundedProcessRunner.ProcessStarter starter =
                        pb -> {
                            Process started = pb.start();
                            awaitTreePids(file, 3, 25);
                            return started;
                        };
                assertThatThrownBy(
                                () ->
                                        new BoundedProcessRunner(starter)
                                                .runOwnedTree(
                                                        treeCommand(file), 6, TimeUnit.SECONDS))
                        .isInstanceOf(TimeoutException.class);
                var pids = Files.readAllLines(file);
                assertThat(pids).hasSize(3);
                for (String pid : pids) {
                    assertThat(
                                    ProcessHandle.of(Long.parseLong(pid))
                                            .map(ProcessHandle::isAlive)
                                            .orElse(false))
                            .as("terminated PID %s", pid)
                            .isFalse();
                }
            } finally {
                killFixture(file);
            }
        }

        @Test
        void interruptionWaitsForTreeAndPreservesInterrupt() throws Exception {
            var file = Files.createTempFile("owned-tree-", ".pids");
            var outcome = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
            BoundedProcessRunner.ProcessStarter starter =
                    pb -> {
                        Process started = pb.start();
                        awaitTreePids(file, 3, 25);
                        return started;
                    };
            Thread thread =
                    new Thread(
                            () -> {
                                try {
                                    new BoundedProcessRunner(starter)
                                            .runOwnedTree(treeCommand(file), 30, TimeUnit.SECONDS);
                                } catch (Throwable e) {
                                    outcome.set(e);
                                    interrupted.set(Thread.currentThread().isInterrupted());
                                }
                            });
            try {
                thread.start();
                awaitTreePids(file, 3, 25);
                Thread.sleep(150); // Allow the runner to record the grandchild before cancellation.
                thread.interrupt();
                thread.join(10_000);
                assertThat(thread.isAlive()).isFalse();
                assertThat(outcome.get()).isInstanceOf(InterruptedException.class);
                assertThat(interrupted.get()).isTrue();
                for (String pid : Files.readAllLines(file)) {
                    assertThat(
                                    ProcessHandle.of(Long.parseLong(pid))
                                            .map(ProcessHandle::isAlive)
                                            .orElse(false))
                            .isFalse();
                }
            } finally {
                thread.interrupt();
                thread.join(10_000);
                killFixture(file);
            }
        }

        @Test
        void stderrIsNotAcceptedEvenWhenStdoutIsValidJson() {
            assertThatThrownBy(
                            () ->
                                    new BoundedProcessRunner()
                                            .runOwnedTree(
                                                    stderrFixtureCommand(), 10, TimeUnit.SECONDS))
                    .hasMessageContaining("stderr");
        }

        private ProcessBuilder stderrFixtureCommand() throws Exception {
            return new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp",
                    fixtureClassPath(),
                    StderrFixture.class.getName());
        }

        private ProcessBuilder treeCommand(Path file) throws Exception {
            return new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-XX:TieredStopAtLevel=1",
                    "-cp",
                    fixtureClassPath(),
                    TreeFixture.class.getName(),
                    file.toString(),
                    "2");
        }

        private static void awaitTreePids(Path file, int expectedCount, long timeoutSeconds)
                throws IOException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (Files.readAllLines(file).size() < expectedCount
                    && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(15);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted waiting for fixture PIDs", e);
                }
            }
            assertThat(Files.readAllLines(file)).hasSize(expectedCount);
        }

        private void killFixture(Path file) throws IOException {
            for (String pid : Files.readAllLines(file)) {
                ProcessHandle.of(Long.parseLong(pid))
                        .ifPresent(
                                handle -> {
                                    if (handle.isAlive()) handle.destroyForcibly();
                                });
            }
            Files.delete(file);
        }
    }

    private static String fixtureClassPath() throws Exception {
        return Path.of(
                        TreeFixture.class
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toURI())
                .toString();
    }

    public static class StderrFixture {
        public static void main(String[] args) {
            System.out.print("{}");
            System.out.flush();
            System.err.print("polluted");
            System.err.flush();
        }
    }

    public static class TreeFixture {
        public static void main(String[] args) throws Exception {
            Files.writeString(
                    Path.of(args[0]),
                    ProcessHandle.current().pid() + "\n",
                    java.nio.file.StandardOpenOption.APPEND);
            int depth = Integer.parseInt(args[1]);
            if (depth == 0) {
                Thread.sleep(60_000);
            } else {
                Process child =
                        new ProcessBuilder(
                                        Path.of(System.getProperty("java.home"), "bin", "java")
                                                .toString(),
                                        "-XX:TieredStopAtLevel=1",
                                        "-cp",
                                        fixtureClassPath(),
                                        TreeFixture.class.getName(),
                                        args[0],
                                        Integer.toString(depth - 1))
                                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                .redirectError(ProcessBuilder.Redirect.DISCARD)
                                .start();
                child.waitFor();
            }
        }
    }

    @Nested
    class Run {

        @Test
        void drainsAllOutputWhileRetainingOnlyTheConfiguredPrefix() throws Exception {
            ExecutorService executor = singleThreadExecutor(1);
            try {
                BoundedProcessRunner runner =
                        new BoundedProcessRunner(
                                ignored -> StubProcess.completed("abcdefghijklmnopqrstuvwxyz"),
                                executor,
                                10);

                BoundedProcessRunner.ProcessResult result =
                        runner.run(new ProcessBuilder("ignored"), 1, TimeUnit.SECONDS);

                assertThat(result.exitCode()).isZero();
                assertThat(result.output()).isEqualTo("abcdefghij");
                assertThat(result.outputTruncated()).isTrue();
            } finally {
                executor.shutdownNow();
            }
        }

        @Test
        void waitsForDrainCapacityBeforeStartingAnotherProcess() throws Exception {
            Semaphore permits = new Semaphore(1);
            ExecutorService executor = singleThreadExecutor(0);
            ExecutorService invocations = Executors.newSingleThreadExecutor();
            CountDownLatch firstStarted = new CountDownLatch(1);
            Future<?> firstInvocation =
                    invocations.submit(
                            () -> {
                                BoundedProcessRunner firstRunner =
                                        new BoundedProcessRunner(
                                                ignored -> {
                                                    firstStarted.countDown();
                                                    return StubProcess.hanging(false);
                                                },
                                                executor,
                                                permits,
                                                1024);
                                return firstRunner.run(
                                        new ProcessBuilder("ignored"), 10, TimeUnit.SECONDS);
                            });
            assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
            AtomicInteger secondProcessStarts = new AtomicInteger();
            try {
                BoundedProcessRunner runner =
                        new BoundedProcessRunner(
                                ignored -> {
                                    secondProcessStarts.incrementAndGet();
                                    return StubProcess.hanging(false);
                                },
                                executor,
                                permits,
                                1024);

                assertThatThrownBy(
                                () ->
                                        runner.run(
                                                new ProcessBuilder("ignored"),
                                                50,
                                                TimeUnit.MILLISECONDS))
                        .isInstanceOf(IOException.class)
                        .hasMessageContaining("executor is saturated");
                assertThat(secondProcessStarts).hasValue(0);
            } finally {
                firstInvocation.cancel(true);
                invocations.shutdownNow();
                executor.shutdownNow();
            }
        }

        @Test
        void interruptionRestoresTheFlagForceStopsTheProcessAndReleasesCapacity() {
            ExecutorService executor = singleThreadExecutor(1);
            Semaphore permits = new Semaphore(1);
            StubProcess process = StubProcess.hanging(true);
            try {
                BoundedProcessRunner runner =
                        new BoundedProcessRunner(ignored -> process, executor, permits, 1024);

                assertThatThrownBy(
                                () ->
                                        runner.run(
                                                new ProcessBuilder("ignored"), 1, TimeUnit.SECONDS))
                        .isInstanceOf(InterruptedException.class);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
                assertThat(process.isAlive()).isFalse();
                assertThat(permits.availablePermits()).isEqualTo(1);
            } finally {
                Thread.interrupted();
                executor.shutdownNow();
            }
        }

        @Test
        void startupFailureReleasesDrainCapacity() {
            ExecutorService executor = singleThreadExecutor(1);
            Semaphore permits = new Semaphore(1);
            try {
                BoundedProcessRunner runner =
                        new BoundedProcessRunner(
                                ignored -> {
                                    throw new IOException("cannot start");
                                },
                                executor,
                                permits,
                                1024);

                assertThatThrownBy(
                                () ->
                                        runner.run(
                                                new ProcessBuilder("ignored"), 1, TimeUnit.SECONDS))
                        .isInstanceOf(IOException.class)
                        .hasMessageContaining("cannot start");
                assertThat(permits.availablePermits()).isEqualTo(1);
            } finally {
                executor.shutdownNow();
            }
        }

        @Test
        void timeoutReleasesDrainCapacity() {
            ExecutorService executor = singleThreadExecutor(1);
            Semaphore permits = new Semaphore(1);
            StubProcess process = StubProcess.hanging(false);
            try {
                BoundedProcessRunner runner =
                        new BoundedProcessRunner(ignored -> process, executor, permits, 1024);

                assertThatThrownBy(
                                () ->
                                        runner.run(
                                                new ProcessBuilder("ignored"),
                                                10,
                                                TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                assertThat(process.isAlive()).isFalse();
                assertThat(permits.availablePermits()).isEqualTo(1);
            } finally {
                executor.shutdownNow();
            }
        }

        @Test
        void executorRejectionStopsTheProcessAndReleasesDrainCapacity() {
            ExecutorService executor = singleThreadExecutor(0);
            Semaphore permits = new Semaphore(1);
            executor.shutdown();
            StubProcess process = StubProcess.hanging(false);
            BoundedProcessRunner runner =
                    new BoundedProcessRunner(ignored -> process, executor, permits, 1024);

            assertThatThrownBy(() -> runner.run(new ProcessBuilder("ignored"), 1, TimeUnit.SECONDS))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("executor is saturated");
            assertThat(process.isAlive()).isFalse();
            assertThat(permits.availablePermits()).isEqualTo(1);
        }
    }

    private static ExecutorService singleThreadExecutor(int queueCapacity) {
        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                queueCapacity == 0
                        ? new java.util.concurrent.SynchronousQueue<>()
                        : new ArrayBlockingQueue<>(queueCapacity),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static final class StubProcess extends Process {
        private final InputStream input;
        private final boolean interruptWait;
        private volatile boolean alive;

        private StubProcess(InputStream input, boolean alive, boolean interruptWait) {
            this.input = input;
            this.alive = alive;
            this.interruptWait = interruptWait;
        }

        static StubProcess completed(String output) {
            return new StubProcess(
                    new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)),
                    false,
                    false);
        }

        static StubProcess hanging(boolean interruptWait) {
            return new StubProcess(new BlockingInputStream(), true, interruptWait);
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
            if (interruptWait) {
                throw new InterruptedException("interrupted");
            }
            while (alive) {
                TimeUnit.MILLISECONDS.sleep(10);
            }
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            if (interruptWait) {
                throw new InterruptedException("interrupted");
            }
            if (alive) {
                unit.sleep(timeout);
            }
            return !alive;
        }

        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException("still running");
            }
            return 0;
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
