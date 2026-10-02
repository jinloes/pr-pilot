package com.jinloes.prpilot.ui;

import static com.jinloes.prpilot.ui.WebviewPrSupport.bridgePrKey;
import static com.jinloes.prpilot.ui.WebviewPrSupport.matchesPrRequest;

import com.fasterxml.jackson.databind.JsonNode;
import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.review.SemanticReviewService;
import com.jinloes.prpilot.review.SemanticWorktreeStore;
import com.jinloes.prpilot.services.IntellijGitHubService;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.ErrorMsg;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Owns IntelliJ-assisted (deep) review preparation and Continue correlation. State transitions
 * happen under the host panel's lock so they stay consistent with the panel's PR selection.
 */
final class DeepReviewController {

    /** The panel members the deep-review flow reads; implemented by {@link WebviewPanel}. */
    interface Host {
        boolean isDisposed();

        PullRequest activePR();

        long selectionRevision();

        boolean isCurrentSelectionLocked(String expectedKey, long expectedRevision);

        void pushMessage(Object payload);

        String activeReviewOperationId();

        void cancelActiveReview(String operationId);

        boolean intellijAssistedEnabled();
    }

    record DeepPending(
            SemanticReviewService.Preparation preparation,
            JsonNode options,
            String key,
            long selection,
            Object settings,
            String diff,
            long revision) {}

    record DeepInvocation(DeepPending pending, String server) {}

    /**
     * External effects only; correlation, head checks and Continue consumption stay in the host.
     */
    interface DeepReviewIo {
        SemanticReviewService.Preparation prepare(JsonNode options, FreshDeepPr fresh)
                throws Exception;

        FreshDeepPr fresh(int number, String owner, String repo) throws Exception;

        void generate(DeepPending pending, String operationId, String server);

        List<SemanticWorktreeStore.Retained> list() throws Exception;

        void cleanup(String id, boolean closed) throws Exception;
    }

    record FreshDeepPr(IntellijGitHubService.PRHeadInfo head, String diff) {}

    private final Object hostLock;
    private final Host host;
    private final DeepReviewIo io;
    private final Consumer<Runnable> background;
    private final Supplier<Object> settingsSupplier;
    private DeepPending deepPending;
    private long deepPreparationRevision;
    private String deepOperationId;
    private final Set<String> consumedDeepOperations = new HashSet<>();

    DeepReviewController(
            Object hostLock,
            Host host,
            DeepReviewIo io,
            Consumer<Runnable> background,
            Supplier<Object> settings) {
        this.hostLock = hostLock;
        this.host = host;
        this.io = io;
        this.background = background;
        this.settingsSupplier = settings;
    }

    /** Drops a prepared deep review when an ordinary review starts; caller holds the host lock. */
    void invalidateLocked() {
        deepPending = null;
        deepPreparationRevision++;
    }

    /** Invalidates the deep operation the user cancelled; caller holds the host lock. */
    void onReviewCancelledLocked(String operationId) {
        if (operationId != null && operationId.equals(deepOperationId)) {
            deepPending = null;
            deepOperationId = null;
            deepPreparationRevision++;
        }
    }

    /**
     * Re-offers a still-current preparation after its generation failed so the user can retry;
     * caller holds the host lock and has checked that the generation is still current.
     */
    void restoreAfterGenerationFailureLocked(
            DeepPending pending, String operationId, String message) {
        if (!host.isDisposed() && pending.revision() == deepPreparationRevision) {
            deepPending = pending;
            publishDeepPrepared(pending, operationId, message);
        }
    }

    void publishDeepPrepared(DeepPending pending, String operationId, String message) {
        var p = pending.preparation();
        host.pushMessage(
                java.util.Map.of(
                        "type",
                        "deepReviewPrepared",
                        "prKey",
                        pending.key(),
                        "operationId",
                        operationId,
                        "retainedId",
                        p.retainedId(),
                        "worktree",
                        p.worktree(),
                        "head",
                        p.head(),
                        "servers",
                        p.servers(),
                        "message",
                        message));
    }

    void prepare(JsonNode options) {
        int number = options.path("number").asInt();
        String owner = options.path("owner").asText(), repo = options.path("repo").asText();
        String key = bridgePrKey(number, owner, repo);
        if (!host.intellijAssistedEnabled()) {
            // Reject rather than downgrade: an ordinary review must be an explicit user choice.
            host.pushMessage(
                    new ErrorMsg(
                            "reviewError", key, WebviewPanel.INTELLIJ_ASSISTED_DISABLED_ERROR));
            return;
        }
        final long revision, selection;
        final Object settings = settingsSupplier.get();
        synchronized (hostLock) {
            if (host.isDisposed() || !matchesPrRequest(host.activePR(), number, owner, repo))
                return;
            revision = ++deepPreparationRevision;
            selection = host.selectionRevision();
            deepPending = null;
            deepOperationId = options.path("operationId").asText();
        }
        host.cancelActiveReview(host.activeReviewOperationId());
        background.accept(
                () -> {
                    try {
                        var fresh = io.fresh(number, owner, repo);
                        var prepared = io.prepare(options, fresh);
                        synchronized (hostLock) {
                            if (host.isDisposed()
                                    || revision != deepPreparationRevision
                                    || !host.isCurrentSelectionLocked(key, selection)
                                    || !settings.equals(settingsSupplier.get())) return;
                            deepPending =
                                    new DeepPending(
                                            prepared,
                                            options,
                                            key,
                                            selection,
                                            settings,
                                            fresh.diff(),
                                            revision);
                            publishDeepPrepared(
                                    deepPending,
                                    options.path("operationId").asText(),
                                    "Open this exact worktree in IntelliJ, enable MCP and import Gradle. Continue may first arm tracking; then run one manual Gradle sync and Retry.");
                        }
                    } catch (Exception error) {
                        synchronized (hostLock) {
                            if (!host.isDisposed()
                                    && revision == deepPreparationRevision
                                    && host.isCurrentSelectionLocked(key, selection))
                                host.pushMessage(
                                        new ErrorMsg(
                                                "reviewError",
                                                key,
                                                String.valueOf(error.getMessage())));
                        }
                    }
                });
    }

    void continueReview(JsonNode input) {
        final DeepPending pending;
        String operationId = input.path("operationId").asText(),
                server = input.path("server").asText();
        String key =
                bridgePrKey(
                        input.path("number").asInt(),
                        input.path("owner").asText(),
                        input.path("repo").asText());
        synchronized (hostLock) {
            pending = deepPending;
            if (host.isDisposed()
                    || pending == null
                    || consumedDeepOperations.contains(operationId)
                    || !pending.key().equals(key)
                    || !host.isCurrentSelectionLocked(key, pending.selection())
                    || !pending.preparation()
                            .retainedId()
                            .equals(input.path("retainedId").asText())) return;
            if (pending.revision() != deepPreparationRevision
                    || !pending.settings().equals(settingsSupplier.get())
                    || !pending.preparation().servers().contains(server)) {
                host.pushMessage(
                        new ErrorMsg(
                                "reviewError",
                                key,
                                "Stale, duplicate or changed deep review; prepare again."));
                return;
            }
            consumedDeepOperations.add(operationId);
            deepOperationId = operationId;
            deepPending = null;
        }
        background.accept(
                () -> {
                    boolean refreshing = true;
                    try {
                        var o = pending.options();
                        var fresh =
                                io.fresh(
                                        o.path("number").asInt(),
                                        o.path("owner").asText(),
                                        o.path("repo").asText());
                        refreshing = false;
                        validateHead(pending, fresh);
                        io.generate(pending, operationId, server);
                    } catch (Exception error) {
                        synchronized (hostLock) {
                            if (!host.isDisposed()
                                    && pending.revision() == deepPreparationRevision
                                    && operationId.equals(deepOperationId)
                                    && host.isCurrentSelectionLocked(key, pending.selection())) {
                                if (refreshing
                                        && pending.settings().equals(settingsSupplier.get())) {
                                    deepPending = pending;
                                }
                                host.pushMessage(
                                        new ErrorMsg(
                                                "reviewError",
                                                key,
                                                String.valueOf(error.getMessage())));
                            }
                        }
                    }
                });
    }

    void validateHead(DeepPending pending) throws Exception {
        var o = pending.options();
        var fresh =
                io.fresh(
                        o.path("number").asInt(),
                        o.path("owner").asText(),
                        o.path("repo").asText());
        validateHead(pending, fresh);
    }

    void validateHead(DeepPending pending, FreshDeepPr fresh) throws Exception {
        synchronized (hostLock) {
            if (host.isDisposed()
                    || pending.revision() != deepPreparationRevision
                    || !fresh.head().sha().equals(pending.preparation().head())
                    || !fresh.diff().equals(pending.diff())
                    || !pending.settings().equals(settingsSupplier.get())
                    || !host.isCurrentSelectionLocked(pending.key(), pending.selection()))
                throw new java.io.IOException(
                        "Deep review PR, settings or selection changed; prepare again.");
        }
    }

    void maintenance(JsonNode input) {
        if (host.isDisposed()) return;
        background.accept(
                () -> {
                    try {
                        if ("cleanupDeepReview".equals(input.path("type").asText()))
                            io.cleanup(
                                    input.path("retainedId").asText(),
                                    input.path("projectClosed").asBoolean());
                        host.pushMessage(
                                java.util.Map.of(
                                        "type",
                                        "retainedDeepReviews",
                                        "operationId",
                                        input.path("operationId").asText(),
                                        "retained",
                                        io.list()));
                    } catch (Exception error) {
                        host.pushMessage(
                                java.util.Map.of(
                                        "type",
                                        "deepReviewMaintenanceError",
                                        "operationId",
                                        input.path("operationId").asText(),
                                        "message",
                                        String.valueOf(error.getMessage())));
                    }
                });
    }
}
