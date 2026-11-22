package com.jinloes.prpilot.review;

import com.microsoft.copilot.CopilotClient;
import com.microsoft.copilot.CopilotSession;
import com.microsoft.copilot.generated.rpc.Model;
import com.microsoft.copilot.generated.rpc.ModelPolicy;
import com.microsoft.copilot.generated.rpc.SessionModelListResult;
import com.microsoft.copilot.rpc.CopilotClientMode;
import com.microsoft.copilot.rpc.CopilotClientOptions;
import com.microsoft.copilot.rpc.CreateSessionOptions;
import com.microsoft.copilot.rpc.PermissionRequestResult;
import com.microsoft.copilot.rpc.RuntimeConnection;
import com.microsoft.copilot.rpc.SessionPermissionOptions;
import com.microsoft.copilot.rpc.SessionToolOptions;
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
 * <p>The primary source is the CLI runtime's live session model catalog (via the Copilot SDK),
 * which is what the CLI's own {@code /model} picker shows, with the global {@code models.list} RPC
 * as a fallback. Both reflect the account's policy, but {@code models.list} omits some models the
 * session catalog includes. {@code copilot help config} is only the last fallback: its model list
 * is baked into the installed CLI binary, so it lags newly rolled-out models and ignores account
 * policy.
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

    /** Bounds each live-catalog step (runtime boot, then {@code models.list}) within RPC budget. */
    private static final long LIVE_TIMEOUT_SECONDS = 12;

    /** Overall monotonic budget across all live-catalog steps before falling back to CLI help. */
    static final long LIVE_TOTAL_BUDGET_SECONDS = 45;

    private static final long HELP_TIMEOUT_SECONDS = 8;

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
            Result currentCache = cache.get();
            if (result.source() == Source.ACCOUNT
                    || (!result.models().isEmpty()
                            && (currentCache == null || currentCache.source() != Source.ACCOUNT))) {
                cache.set(result);
            }
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
    static List<String> filterModelIds(List<Model> models) {
        if (models == null) return List.of();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (Model model : models) {
            if (model == null || StringUtils.isBlank(model.id())) continue;
            ModelPolicy policy = model.policy();
            if (policy != null
                    && policy.state() != null
                    && "disabled".equalsIgnoreCase(policy.state().getValue())) continue;
            ids.add(model.id().trim());
        }
        return List.copyOf(ids);
    }

    /**
     * Same filter for session {@code model.list} entries, which the SDK leaves as untyped JSON
     * maps. Non-object entries are skipped.
     */
    static List<String> filterCatalogEntries(List<?> entries) {
        if (entries == null) return List.of();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> model)) continue;
            if (!(model.get("id") instanceof String id) || StringUtils.isBlank(id)) continue;
            if (model.get("policy") instanceof Map<?, ?> policy
                    && policy.get("state") instanceof String state
                    && "disabled".equalsIgnoreCase(state)) continue;
            ids.add(id.trim());
        }
        return List.copyOf(ids);
    }

    private static List<String> fetchAccountModels() throws Exception {
        long deadlineNanos =
                System.nanoTime() + TimeUnit.SECONDS.toNanos(LIVE_TOTAL_BUDGET_SECONDS);
        String userHome = System.getProperty("user.home", "/");
        Map<String, String> env = new LinkedHashMap<>(System.getenv());
        env.put("HOME", userHome);
        BinaryLocator.putPathEnv(env, userHome);
        try (CopilotClient client =
                new CopilotClient(
                        new CopilotClientOptions()
                                .setRuntime(
                                        RuntimeConnection.forSpawnedStdio(
                                                        CopilotService.findCopilotBinary())
                                                .setWorkingDirectory(userHome)
                                                .setEnvironment(env))
                                .setMode(CopilotClientMode.CODING_AGENT)
                                .setAutoStart(false))) {
            client.start().get(stepBudgetMillis(deadlineNanos), TimeUnit.MILLISECONDS);
            return accountModels(
                    () -> fetchSessionModels(client, userHome, deadlineNanos),
                    () ->
                            filterModelIds(
                                    client.listModels()
                                            .get(
                                                    stepBudgetMillis(deadlineNanos),
                                                    TimeUnit.MILLISECONDS)));
        }
    }

    static long stepBudgetMillis(long deadlineNanos) throws TimeoutException {
        long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        if (remainingMillis <= 0) {
            throw new TimeoutException("Copilot live model discovery budget exhausted");
        }
        return Math.min(remainingMillis, TimeUnit.SECONDS.toMillis(LIVE_TIMEOUT_SECONDS));
    }

    /**
     * Prefers the session catalog, which is what the CLI's {@code /model} picker shows: the global
     * {@code models.list} omits models it includes, such as newly enabled Gemini models. Falls back
     * to the global catalog when the session route fails or returns nothing.
     */
    static List<String> accountModels(LiveCatalog sessionCatalog, LiveCatalog globalCatalog)
            throws Exception {
        try {
            List<String> models = sessionCatalog.fetch();
            if (!models.isEmpty()) return models;
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Session model catalog unavailable; using models.list: {}", rootMessage(e));
        }
        return globalCatalog.fetch();
    }

    /**
     * Opens a short-lived, tool-free session, lists its selectable models, and deletes it. No
     * prompt is sent, so this uses no model quota.
     */
    private static List<String> fetchSessionModels(
            CopilotClient client, String workingDirectory, long deadlineNanos) throws Exception {
        CreateSessionOptions config =
                new CreateSessionOptions()
                        .setPermissions(
                                new SessionPermissionOptions()
                                        .setOnRequest(
                                                (request, ctx) ->
                                                        CompletableFuture.completedFuture(
                                                                PermissionRequestResult.reject(
                                                                        "Model discovery runs no tools."))))
                        .setWorkingDirectory(workingDirectory)
                        .setTools(new SessionToolOptions().setAvailableTools(List.of()))
                        .setEnableConfigDiscovery(false);
        CopilotSession session =
                client.createSession(config)
                        .get(stepBudgetMillis(deadlineNanos), TimeUnit.MILLISECONDS);
        try {
            SessionModelListResult result =
                    session.getRpc()
                            .model
                            .list()
                            .get(stepBudgetMillis(deadlineNanos), TimeUnit.MILLISECONDS);
            return filterCatalogEntries(result != null ? result.list() : null);
        } finally {
            try {
                long cleanupMillis =
                        Math.max(
                                1_000L,
                                Math.min(
                                        TimeUnit.NANOSECONDS.toMillis(
                                                deadlineNanos - System.nanoTime()),
                                        TimeUnit.SECONDS.toMillis(LIVE_TIMEOUT_SECONDS)));
                client.deleteSession(session.getSessionId())
                        .get(cleanupMillis, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                log.debug("Could not delete the model discovery session", e);
            }
            session.close();
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

    static List<String> buildHelpProbeCommand(
            String binary, boolean windows, Map<String, String> env) throws IOException {
        return BinaryLocator.buildCommand(binary, windows, env, List.of("help", "config"));
    }

    static List<String> probe(
            BoundedProcessRunner.ProcessStarter processStarter, long timeoutSeconds) {
        try {
            ProcessBuilder pb =
                    new ProcessBuilder(
                            buildHelpProbeCommand(
                                    CopilotService.findCopilotBinary(),
                                    BinaryLocator.isWindows(),
                                    System.getenv()));
            String userHome = System.getProperty("user.home", "/");
            pb.environment().put("HOME", userHome);
            BinaryLocator.putPathEnv(pb.environment(), userHome);
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
