package com.jinloes.prpilot.review;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovers the Antigravity model slugs available to the signed-in account via {@code agy models}.
 *
 * <p>{@code agy models} writes progress diagnostics to {@code stderr} and tab-separated {@code
 * <slug>\t<Display Name>} lines to {@code stdout}, so {@code stdout} and {@code stderr} are drained
 * independently. Successful {@link Source#ACCOUNT} results are cached in memory and concurrent
 * refreshes share one probe; a failed refresh never replaces a previously cached list.
 */
public final class AntigravityModelDiscovery {

    private static final Logger log = LoggerFactory.getLogger(AntigravityModelDiscovery.class);

    private static final Pattern MODEL_SLUG = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$");
    private static final long MODELS_TIMEOUT_SECONDS = 15;

    private static final ExecutorService ioExecutor =
            Executors.newCachedThreadPool(
                    runnable -> {
                        Thread thread = new Thread(runnable, "pr-pilot-agy-models");
                        thread.setDaemon(true);
                        return thread;
                    });

    public enum Source {
        /** Live {@code agy models} catalog for the signed-in account. */
        ACCOUNT,
        /** Discovery failed; the caller should show its own suggestions. */
        NONE
    }

    public record Result(List<String> models, Source source, String failureReason) {
        public Result {
            models = List.copyOf(models);
            failureReason = failureReason != null ? failureReason : "";
        }

        public static Result none(String reason) {
            return new Result(List.of(), Source.NONE, reason);
        }
    }

    @FunctionalInterface
    interface LiveCatalog {
        List<String> fetch() throws Exception;
    }

    private static final AtomicReference<Result> cache = new AtomicReference<>(null);
    private static final AtomicReference<CompletableFuture<Result>> inFlight =
            new AtomicReference<>(null);

    private AntigravityModelDiscovery() {}

    public static List<String> listModels() {
        Result cached = cache.get();
        return cached != null ? cached.models() : refresh().models();
    }

    public static Result cached() {
        return cache.get();
    }

    public static Result refresh() {
        return refresh(() -> discover(AntigravityModelDiscovery::fetchAccountModels));
    }

    static Result refresh(Supplier<Result> discoverer) {
        CompletableFuture<Result> mine = new CompletableFuture<>();
        CompletableFuture<Result> running = inFlight.compareAndExchange(null, mine);
        if (running != null) return running.join();
        try {
            Result result = discoverer.get();
            if (result.source() == Source.ACCOUNT && !result.models().isEmpty()) {
                cache.set(result);
            }
            mine.complete(result);
            return result;
        } catch (RuntimeException e) {
            Result failed = Result.none(e.getMessage());
            mine.complete(failed);
            return failed;
        } finally {
            inFlight.compareAndSet(mine, null);
        }
    }

    public static void invalidate() {
        cache.set(null);
    }

    static Result discover(LiveCatalog live) {
        try {
            List<String> models = live.fetch();
            if (!models.isEmpty()) {
                log.info("Discovered {} Antigravity models from agy models.", models.size());
                return new Result(models, Source.ACCOUNT, "");
            }
            return Result.none("Antigravity returned no models for this account");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.none("Model discovery was interrupted");
        } catch (Exception e) {
            String reason = rootMessage(e);
            log.warn("Antigravity model discovery failed: {}", reason);
            return Result.none(reason);
        }
    }

    /**
     * Extracts ordered, deduplicated model slugs from {@code agy models} stdout. Lines are {@code
     * <slug>\t<Label>} (or bare single-token slugs); multi-word prose lines without a tab are
     * ignored.
     */
    static List<String> parseModelsOutput(String stdout) {
        if (StringUtils.isBlank(stdout)) {
            return List.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String rawLine : stdout.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            int tabIndex = line.indexOf('\t');
            String candidate = tabIndex >= 0 ? line.substring(0, tabIndex).trim() : line;
            if (MODEL_SLUG.matcher(candidate).matches()) {
                ids.add(candidate);
            }
        }
        return List.copyOf(ids);
    }

    private static List<String> fetchAccountModels() throws Exception {
        if (!AntigravityService.isBinaryAvailable()) {
            throw new IOException("Antigravity CLI (`agy`) is not installed");
        }
        String binary = AntigravityService.findAntigravityBinary();
        boolean cmdShim = BinaryLocator.isWindowsBatchShim(binary);
        List<String> cmd =
                cmdShim
                        ? BinaryLocator.buildCommand(
                                binary,
                                true,
                                Map.of(
                                        "ComSpec",
                                        StringUtils.defaultIfBlank(
                                                BinaryLocator.resolveComSpec(System.getenv()),
                                                "cmd.exe")),
                                List.of("models"))
                        : List.of(binary, "models");
        String userHome = System.getProperty("user.home", "/");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File(userHome));
        pb.environment().put("HOME", userHome);
        BinaryLocator.putPathEnv(pb.environment(), userHome);
        Process process = pb.start();
        CompletableFuture<String> stdoutFuture =
                CompletableFuture.supplyAsync(
                        () -> readStreamQuietly(process.getInputStream()), ioExecutor);
        CompletableFuture<String> stderrFuture =
                CompletableFuture.supplyAsync(
                        () -> readStreamQuietly(process.getErrorStream()), ioExecutor);
        try {
            boolean finished = process.waitFor(MODELS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                ClaudeService.destroyProcessTree(process);
                throw new TimeoutException(
                        "agy models timed out after " + MODELS_TIMEOUT_SECONDS + "s");
            }
            String stdout = awaitStream(stdoutFuture);
            String stderr = awaitStream(stderrFuture);
            if (process.exitValue() != 0) {
                String detail = StringUtils.trimToEmpty(stderr);
                throw new IOException(
                        detail.isEmpty() ? "agy models exited " + process.exitValue() : detail);
            }
            return parseModelsOutput(stdout);
        } finally {
            if (process.isAlive()) {
                ClaudeService.destroyProcessTree(process);
            }
            stdoutFuture.cancel(true);
            stderrFuture.cancel(true);
        }
    }

    private static String readStreamQuietly(java.io.InputStream stream) {
        try {
            return IOUtils.toString(stream, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String awaitStream(CompletableFuture<String> future) throws Exception {
        try {
            return future.get(3, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw (e.getCause() instanceof Exception ex) ? ex : new IOException(e.getCause());
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return StringUtils.isNotBlank(message) ? message.trim() : cursor.getClass().getSimpleName();
    }
}
