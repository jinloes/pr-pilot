package com.jinloes.prpilot.review;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs buffered subprocesses with bounded output, timeout, and dedicated blocking-I/O threads. */
final class BoundedProcessRunner {
    static final int DEFAULT_MAX_OUTPUT_BYTES = 1024 * 1024;

    private static final int IO_THREADS = 4;
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();
    private static final Semaphore SHARED_IO_PERMITS = new Semaphore(IO_THREADS);
    private static final ExecutorService SHARED_IO_EXECUTOR =
            new ThreadPoolExecutor(
                    IO_THREADS,
                    IO_THREADS,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new SynchronousQueue<>(),
                    runnable -> {
                        Thread thread =
                                new Thread(
                                        runnable,
                                        "pr-pilot-process-io-" + THREAD_SEQUENCE.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy());

    private final ProcessStarter processStarter;
    private final ExecutorService ioExecutor;
    private final Semaphore ioPermits;
    private final int maxOutputBytes;

    BoundedProcessRunner() {
        this(ProcessBuilder::start);
    }

    BoundedProcessRunner(ProcessStarter processStarter) {
        this(processStarter, SHARED_IO_EXECUTOR, SHARED_IO_PERMITS, DEFAULT_MAX_OUTPUT_BYTES);
    }

    BoundedProcessRunner(
            ProcessStarter processStarter, ExecutorService ioExecutor, int maxOutputBytes) {
        this(processStarter, ioExecutor, new Semaphore(1), maxOutputBytes);
    }

    BoundedProcessRunner(
            ProcessStarter processStarter,
            ExecutorService ioExecutor,
            Semaphore ioPermits,
            int maxOutputBytes) {
        this.processStarter = Objects.requireNonNull(processStarter);
        this.ioExecutor = Objects.requireNonNull(ioExecutor);
        this.ioPermits = Objects.requireNonNull(ioPermits);
        if (maxOutputBytes < 1) {
            throw new IllegalArgumentException("maxOutputBytes must be positive");
        }
        this.maxOutputBytes = maxOutputBytes;
    }

    /**
     * Opt-in ownership of this launch and all observed descendants; existing callers are unchanged.
     */
    ProcessResult runOwnedTree(ProcessBuilder builder, long timeout, TimeUnit unit)
            throws IOException, InterruptedException, TimeoutException {
        if (timeout < 1) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        if (!ioPermits.tryAcquire(2, timeout, unit)) {
            throw new IOException("Process I/O executor is saturated.");
        }
        Process process = null;
        Future<DrainResult> stdout = null;
        Future<DrainResult> stderr = null;
        Map<Long, ProcessHandle> descendants = new LinkedHashMap<>();
        try {
            builder.redirectErrorStream(false);
            process = processStarter.start(builder);
            Process child = process;
            AtomicInteger remainingOutput = new AtomicInteger(maxOutputBytes);
            stdout = ioExecutor.submit(() -> drain(child.getInputStream(), remainingOutput));
            stderr = ioExecutor.submit(() -> drain(child.getErrorStream(), remainingOutput));
            while (true) {
                recordDescendants(process.toHandle(), descendants);
                if (Thread.interrupted()) {
                    throw new InterruptedException("Process cancelled");
                }
                if (System.nanoTime() >= deadline) {
                    throw new TimeoutException("Owned process tree timed out");
                }
                if (!process.isAlive() && stdout.isDone() && stderr.isDone()) {
                    break;
                }
                TimeUnit.MILLISECONDS.sleep(10);
            }
            DrainResult out = completedOutput(stdout), err = completedOutput(stderr);
            if (err.bytes().size() != 0 || err.truncated()) {
                throw new IOException("Subprocess stderr pollution");
            }
            return new ProcessResult(
                    process.exitValue(),
                    out.bytes().toString(StandardCharsets.UTF_8),
                    out.truncated());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (RejectedExecutionException e) {
            throw new IOException("Process I/O executor is saturated.", e);
        } finally {
            try {
                if (process != null) {
                    teardownTree(process, descendants);
                }
            } finally {
                if (process != null) {
                    closeProcessStreams(process);
                }
                if (stdout != null) stdout.cancel(true);
                if (stderr != null) stderr.cancel(true);
                ioPermits.release(2);
            }
        }
    }

    private static void recordDescendants(ProcessHandle parent, Map<Long, ProcessHandle> recorded)
            throws IOException {
        try (var stream = parent.descendants()) {
            for (ProcessHandle handle : stream.limit(1025).toList()) {
                recorded.putIfAbsent(handle.pid(), handle);
                if (recorded.size() > 1024) {
                    throw new IOException("Owned process tree limit exceeded");
                }
            }
        }
        // Keep observing retained parents even after their own parent exits.
        for (ProcessHandle handle : new ArrayList<>(recorded.values())) {
            if (handle.isAlive()) {
                try (var stream = handle.children()) {
                    for (ProcessHandle child : stream.limit(1025).toList()) {
                        recorded.putIfAbsent(child.pid(), child);
                    }
                }
                if (recorded.size() > 1024) {
                    throw new IOException("Owned process tree limit exceeded");
                }
            }
        }
    }

    private static void teardownTree(Process process, Map<Long, ProcessHandle> recorded)
            throws IOException {
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        IOException enumerationFailure = null;
        try {
            try {
                recordDescendants(process.toHandle(), recorded);
            } catch (IOException | RuntimeException e) {
                enumerationFailure = new IOException("Cannot enumerate owned process tree", e);
            }
            // Leaves first lets still-running parents reap their children.
            var handles = new ArrayList<>(recorded.values());
            java.util.Collections.reverse(handles);
            for (ProcessHandle handle : handles) {
                if (handle.isAlive()) handle.destroyForcibly();
            }
            while (handles.stream().anyMatch(ProcessHandle::isAlive)
                    && System.nanoTime() < deadline) {
                try {
                    TimeUnit.MILLISECONDS.sleep(10);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            try {
                process.waitFor(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (process.isAlive() || recorded.values().stream().anyMatch(ProcessHandle::isAlive)) {
            throw new IOException("Owned process tree did not terminate");
        }
        if (enumerationFailure != null) throw enumerationFailure;
    }

    ProcessResult run(ProcessBuilder processBuilder, long timeout, TimeUnit unit)
            throws IOException, InterruptedException, TimeoutException {
        Objects.requireNonNull(processBuilder);
        Objects.requireNonNull(unit);
        if (timeout < 1) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        processBuilder.redirectErrorStream(true);
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        if (!ioPermits.tryAcquire(timeout, unit)) {
            throw new IOException("Process I/O executor is saturated.");
        }
        if (deadlineNanos - System.nanoTime() <= 0) {
            ioPermits.release();
            throw new TimeoutException("Timed out waiting for process I/O capacity.");
        }

        Process process = null;
        Future<DrainResult> outputFuture = null;
        try {
            process = processStarter.start(processBuilder);
            Process drainProcess = process;
            try {
                outputFuture = ioExecutor.submit(() -> drain(drainProcess.getInputStream()));
            } catch (RejectedExecutionException exception) {
                throw new IOException("Process I/O executor is saturated.", exception);
            }

            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0 || !process.waitFor(remainingNanos, TimeUnit.NANOSECONDS)) {
                throw new TimeoutException("Process timed out.");
            }

            DrainResult drained;
            if (outputFuture.isDone()) {
                drained = completedOutput(outputFuture);
            } else {
                long remainingDrainNanos = deadlineNanos - System.nanoTime();
                if (remainingDrainNanos <= 0) {
                    throw new TimeoutException("Timed out draining process output.");
                }
                drained = output(outputFuture, remainingDrainNanos);
            }
            return new ProcessResult(
                    process.exitValue(),
                    drained.bytes().toString(StandardCharsets.UTF_8),
                    drained.truncated());
        } catch (InterruptedException exception) {
            if (process != null) {
                terminate(process, outputFuture);
            }
            Thread.currentThread().interrupt();
            throw exception;
        } catch (IOException | TimeoutException exception) {
            if (process != null) {
                terminate(process, outputFuture);
            }
            throw exception;
        } finally {
            if (process != null && process.isAlive()) {
                terminate(process, outputFuture);
            }
            if (process != null) {
                closeProcessStreams(process);
            }
            if (outputFuture != null && !outputFuture.isDone()) {
                outputFuture.cancel(true);
            }
            ioPermits.release();
        }
    }

    private DrainResult drain(InputStream input) throws IOException {
        return drain(input, new AtomicInteger(maxOutputBytes));
    }

    private DrainResult drain(InputStream input, AtomicInteger remainingOutput) throws IOException {
        ByteArrayOutputStream retained =
                new ByteArrayOutputStream(Math.min(maxOutputBytes, 16 * 1024));
        byte[] buffer = new byte[8192];
        boolean truncated = false;
        int read;
        while ((read = input.read(buffer)) != -1) {
            int count = read;
            int remaining = remainingOutput.getAndUpdate(value -> Math.max(0, value - count));
            int toRetain = Math.min(read, Math.max(0, remaining));
            if (toRetain > 0) {
                retained.write(buffer, 0, toRetain);
            }
            if (toRetain < read) {
                truncated = true;
            }
        }
        return new DrainResult(retained, truncated);
    }

    private static DrainResult output(Future<DrainResult> outputFuture, long remainingNanos)
            throws IOException, InterruptedException, TimeoutException {
        try {
            return outputFuture.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (ExecutionException exception) {
            throw outputFailure(exception);
        }
    }

    private static DrainResult completedOutput(Future<DrainResult> outputFuture)
            throws IOException, InterruptedException {
        try {
            return outputFuture.get();
        } catch (ExecutionException exception) {
            throw outputFailure(exception);
        }
    }

    private static IOException outputFailure(ExecutionException exception) {
        Throwable cause = exception.getCause();
        return cause instanceof IOException ioException
                ? ioException
                : new IOException("Failed to drain process output.", cause);
    }

    private static void terminate(Process process, Future<?> outputFuture) {
        process.destroyForcibly();
        closeProcessStreams(process);
        if (outputFuture != null) {
            outputFuture.cancel(true);
        }
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeProcessStreams(Process process) {
        close(process.getInputStream());
        close(process.getErrorStream());
        close(process.getOutputStream());
    }

    private static void close(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Best effort during process teardown.
        }
    }

    record ProcessResult(int exitCode, String output, boolean outputTruncated) {}

    private record DrainResult(ByteArrayOutputStream bytes, boolean truncated) {}

    @FunctionalInterface
    interface ProcessStarter {
        Process start(ProcessBuilder processBuilder) throws IOException;
    }
}
