package com.jinloes.prpilot.review;

import com.github.copilot.CopilotClient;
import com.github.copilot.rpc.CopilotClientMode;
import com.github.copilot.rpc.CopilotClientOptions;
import com.github.copilot.rpc.ModelInfo;
import com.github.copilot.rpc.ModelPolicy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovers the Copilot model IDs available to the signed-in account.
 *
 * <p>The primary source is the CLI runtime's live {@code models.list} RPC (via the Copilot SDK),
 * which reflects the account's actual, policy-filtered catalog. {@code copilot help config} is only
 * a fallback: its model list is baked into the installed CLI binary, so it lags newly rolled-out
 * models and ignores account policy.
 *
 * <p>Results are cached in memory so a settings page can render the last known list instantly;
 * {@link #refresh()} re-probes (callers do this every time the page opens). A failed refresh never
 * replaces a previously successful list. If every source fails, the result is empty and the caller
 * falls back to its own hardcoded suggestions.
 */
public final class CopilotModelDiscovery {

    private static final Logger log = LoggerFactory.getLogger(CopilotModelDiscovery.class);

    // Leading whitespace is a formatting artifact, not a signal — match with or without it.
    private static final Pattern SECTION_START = Pattern.compile("^\\s*`model`:.*");
    private static final Pattern QUOTED_ITEM = Pattern.compile("^\\s*-\\s+\"([^\"]+)\"\\s*$");

    /** Bounds each live-catalog step (runtime boot, then {@code models.list}). */
    private static final long LIVE_TIMEOUT_SECONDS = 20;

    private static final long HELP_TIMEOUT_SECONDS = 10;

    /** Where a discovered model list came from. */
    public enum Source {
        /** Live {@code models.list} catalog for the signed-in account. */
        ACCOUNT,
        /** Static list from {@code copilot help config}; may miss newer models. */
        CLI_HELP,
        /** Discovery failed; the caller should show its own suggestions. */
        NONE
    }

    /**
     * Discovered model IDs plus their provenance.
     *
     * @param failureReason short, user-presentable reason when the live catalog could not be
     *     loaded; empty when {@code source == ACCOUNT}
     */
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

    /** Null until a probe succeeds; failed probes never overwrite a previous good result. */
    private static final AtomicReference<Result> cache = new AtomicReference<>(null);

    /** Coalesces concurrent refreshes (e.g. settings reopened while a probe is still running). */
    private static final AtomicReference<CompletableFuture<Result>> inFlight =
            new AtomicReference<>(null);

    private CopilotModelDiscovery() {}

    /**
     * Returns the cached model list, probing synchronously when nothing is cached yet. Callers
     * should run this off the EDT — a probe can take several seconds.
     */
    public static List<String> listModels() {
        Result cached = cache.get();
        return cached != null ? cached.models() : refresh().models();
    }

    /** Last successful discovery result, or null if none has succeeded yet. Never probes. */
    public static Result cached() {
        return cache.get();
    }

    /**
     * Re-probes the model catalog, blocking until done. Run off the EDT. Concurrent callers share
     * one probe. On failure the previous good result stays cached, but the failed result is
     * returned so the caller can report it.
     */
    public static Result refresh() {
        return refresh(() -> discover(CopilotModelDiscovery::fetchAccountModels, () -> probe()));
    }

    static Result refresh(Supplier<Result> discoverer) {
        CompletableFuture<Result> mine = new CompletableFuture<>();
        CompletableFuture<Result> running = inFlight.compareAndExchange(null, mine);
        if (running != null) return running.join();
        try {
            Result result = discoverer.get();
            if (!result.models().isEmpty()) cache.set(result);
            mine.complete(result);
            return result;
        } catch (RuntimeException e) {
            Result failed = Result.none(e.getMessage());
            mine.complete(failed);
            return failed;
        } finally {
            inFlight.set(null);
        }
    }

    /** Drops the cached result so the next {@link #listModels()} call re-probes. */
    public static void invalidate() {
        cache.set(null);
    }

    /** Tries the live account catalog first, then the static help list. */
    static Result discover(LiveCatalog live, Supplier<List<String>> helpFallback) {
        String reason;
        try {
            List<String> models = live.fetch();
            if (!models.isEmpty()) {
                log.info("Discovered {} Copilot models from the account catalog.", models.size());
                return new Result(models, Source.ACCOUNT, "");
            }
            reason = "Copilot returned no enabled models for this account";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.none("Model discovery was interrupted");
        } catch (Exception e) {
            reason = rootMessage(e);
            log.warn("Live Copilot model discovery failed: {}", reason);
        }
        List<String> fromHelp = helpFallback.get();
        return fromHelp.isEmpty()
                ? Result.none(reason)
                : new Result(fromHelp, Source.CLI_HELP, reason);
    }

    /**
     * Keeps IDs whose policy is not {@code disabled}, dropping blanks and duplicates while
     * preserving catalog order. Mirrors {@code filterModelIds} in {@code vscode-extension}.
     */
    static List<String> filterModelIds(List<ModelInfo> models) {
        if (models == null) return List.of();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (ModelInfo model : models) {
            if (model == null || StringUtils.isBlank(model.getId())) continue;
            ModelPolicy policy = model.getPolicy();
            if (policy != null && "disabled".equalsIgnoreCase(policy.getState())) continue;
            ids.add(model.getId().trim());
        }
        return List.copyOf(ids);
    }

    private static List<String> fetchAccountModels() throws Exception {
        String userHome = System.getProperty("user.home", "/");
        Map<String, String> env = new LinkedHashMap<>(System.getenv());
        env.put("HOME", userHome);
        env.put("PATH", BinaryLocator.providerPath(userHome, env.getOrDefault("PATH", "")));
        try (CopilotClient client =
                new CopilotClient(
                        new CopilotClientOptions()
                                .setCliPath(CopilotService.findCopilotBinary())
                                .setCwd(userHome)
                                .setEnvironment(env)
                                .setMode(CopilotClientMode.COPILOT_CLI)
                                .setAutoStart(false))) {
            client.start().get(LIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return filterModelIds(client.listModels().get(LIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        if (root instanceof TimeoutException) return "Copilot did not respond in time";
        String message =
                StringUtils.defaultIfBlank(root.getMessage(), root.getClass().getSimpleName());
        return StringUtils.abbreviate(message.strip(), 200);
    }

    private static List<String> probe() {
        return probe(ProcessBuilder::start, HELP_TIMEOUT_SECONDS);
    }

    static List<String> probe(
            BoundedProcessRunner.ProcessStarter processStarter, long timeoutSeconds) {
        try {
            ProcessBuilder pb =
                    new ProcessBuilder(CopilotService.findCopilotBinary(), "help", "config");
            pb.environment().put("HOME", System.getProperty("user.home", "/"));
            String existingPath = pb.environment().getOrDefault("PATH", "");
            pb.environment().put("PATH", "/opt/homebrew/bin:/usr/local/bin:" + existingPath);
            pb.redirectErrorStream(true);
            BoundedProcessRunner.ProcessResult result =
                    new BoundedProcessRunner(processStarter)
                            .run(pb, timeoutSeconds, TimeUnit.SECONDS);
            if (result.outputTruncated()) {
                log.warn(
                        "copilot help config output exceeded {} bytes — skipping model discovery.",
                        BoundedProcessRunner.DEFAULT_MAX_OUTPUT_BYTES);
                return List.of();
            }
            String output = result.output();
            if (result.exitCode() != 0) {
                String[] lines = output.split("\n", 4);
                StringBuilder preview = new StringBuilder();
                for (int i = 0; i < Math.min(3, lines.length); i++) {
                    if (i > 0) preview.append(" | ");
                    preview.append(lines[i]);
                }
                String previewText =
                        preview.length() > 300 ? preview.substring(0, 300) : preview.toString();
                log.warn(
                        "copilot help config exited {} — skipping model discovery. Output: {}",
                        result.exitCode(),
                        previewText);
                return List.of();
            }
            List<String> models = parseModelsFromHelp(output);
            if (models.isEmpty()) {
                log.warn(
                        "copilot help config produced no recognized model entries — schema may have changed.");
            } else {
                log.info(
                        "Discovered {} Copilot model IDs from `copilot help config`.",
                        models.size());
            }
            return models;
        } catch (IOException e) {
            log.warn("Failed to probe copilot models: {}", e.getMessage());
            return List.of();
        } catch (TimeoutException e) {
            log.warn(
                    "copilot help config timed out after {}s — skipping model discovery.",
                    timeoutSeconds);
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while probing copilot models");
            return List.of();
        }
    }

    /**
     * Parses model IDs from the output of {@code copilot help config}. Looks for the {@code
     * `model`:} section header and then collects every subsequent line matching {@code -
     * "model-id"} until a blank line ends the section. Returns an empty list if the section is not
     * present or no matching items are found — leaving the caller to fall back to its own
     * suggestion list.
     *
     * <p>Package-private for unit tests so the parser can be exercised without spawning a real CLI.
     */
    static List<String> parseModelsFromHelp(String helpText) {
        List<String> models = new ArrayList<>();
        boolean inSection = false;

        for (String line : helpText.split("\n", -1)) {
            if (!inSection) {
                if (SECTION_START.matcher(line).matches()) inSection = true;
                continue;
            }
            Matcher match = QUOTED_ITEM.matcher(line);
            if (match.matches()) {
                models.add(match.group(1));
            } else if (line.isBlank() && !models.isEmpty()) {
                return models;
            }
            // Otherwise (continuation of the section's description, etc.) keep scanning.
        }
        return Collections.unmodifiableList(models);
    }
}
