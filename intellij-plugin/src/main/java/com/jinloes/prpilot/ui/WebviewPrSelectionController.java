package com.jinloes.prpilot.ui;

import static com.intellij.openapi.application.ApplicationManager.getApplication;
import static com.jinloes.prpilot.ui.WebviewPrSupport.bridgePrKey;
import static com.jinloes.prpilot.ui.WebviewPrSupport.hydratePullRequest;

import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.model.ReviewResult;
import com.jinloes.prpilot.services.DraftRecoveryStore;
import com.jinloes.prpilot.services.IntellijGitHubService;
import com.jinloes.prpilot.services.PendingReviewIndex;
import com.jinloes.prpilot.services.PendingReviewIndexNotifications;
import com.jinloes.prpilot.sidecar.pr.PrDetail;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.DraftLoadedMsg;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.DraftLoadingMsg;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.ErrorMsg;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.PrDraftStatusMsg;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.ProviderReadinessDto;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Loads a selected pull request and restores its draft state. */
final class WebviewPrSelectionController {
    private static final Logger log = LoggerFactory.getLogger(WebviewPrSelectionController.class);
    private final WebviewPanel panel;

    WebviewPrSelectionController(WebviewPanel panel) {
        this.panel = panel;
    }

    void handleSelectPR(int number, String owner, String repo) {
        String key = bridgePrKey(number, owner, repo);
        PullRequest pr =
                panel.cachedPRs.stream()
                        .filter(
                                p ->
                                        p.getNumber() == number
                                                && p.getOwner().equals(owner)
                                                && p.getRepo().equals(repo))
                        .findFirst()
                        .orElse(null);
        if (pr == null) {
            panel.pushMessage(
                    new DraftLoadedMsg(
                            "draftLoaded",
                            key,
                            "NO_DRAFT",
                            null,
                            null,
                            null,
                            null,
                            false,
                            false,
                            false,
                            "Pull request is no longer available. Refresh the pull request list and try again.",
                            panel.currentProviderReadiness(),
                            panel.intellijAssistedEnabled.getAsBoolean(),
                            panel.rememberedRepositoryInstructions(owner, repo)));
            return;
        }

        WebviewPanel.LifecycleTransition transition;
        synchronized (panel) {
            transition = panel.transitionToSelection(pr);
        }
        panel.finishLifecycleTransition(transition);
        long revision = transition.selectionRevision();

        getApplication()
                .invokeLater(
                        () -> {
                            if (panel.isCurrentSelection(key, revision)) {
                                panel.onPRSelected.accept(pr);
                            }
                        });
        panel.publishIfCurrentSelection(key, revision, new DraftLoadingMsg("draftLoading", key));

        getApplication()
                .executeOnPooledThread(
                        () -> {
                            // Check local index upfront (no network) so we can prefetch
                            // the current HEAD SHA in parallel if a staleness check is
                            // likely to be needed.
                            Optional<List<PendingReviewIndex.Entry>> localEntries =
                                    panel.loadHealthyDraftEntries();
                            if (localEntries.isEmpty()) {
                                panel.publishIfCurrentSelection(
                                        key,
                                        revision,
                                        new ErrorMsg(
                                                "reviewError",
                                                key,
                                                PendingReviewIndexNotifications.userMessage()));
                                return;
                            }
                            PendingReviewIndex.Entry localEntry =
                                    localEntries.orElseThrow().stream()
                                            .filter(
                                                    e ->
                                                            e.owner().equals(owner)
                                                                    && e.repo().equals(repo)
                                                                    && e.number() == number)
                                            .findFirst()
                                            .orElse(null);
                            String savedHeadSha = localEntry != null ? localEntry.headSha() : "";

                            // All calls are independent — run concurrently so total
                            // latency is max(each) instead of sum(each).
                            CompletableFuture<PrDetail> detailFuture =
                                    CompletableFuture.supplyAsync(
                                            () -> {
                                                try {
                                                    return panel.ghSvc.getPRDetail(
                                                            owner, repo, number);
                                                } catch (Exception e) {
                                                    log.warn(
                                                            "getPRDetail prefetch failed: {}",
                                                            e.getMessage());
                                                    return null;
                                                }
                                            });

                            CompletableFuture<WebviewPanel.PendingReviewLoad> pendingFuture =
                                    CompletableFuture.supplyAsync(
                                            () ->
                                                    WebviewPanel.loadPendingReview(
                                                            () ->
                                                                    panel.ghSvc.loadDraftReview(
                                                                            owner, repo, number)));

                            CompletableFuture<String> diffFuture =
                                    CompletableFuture.supplyAsync(
                                            () -> {
                                                try {
                                                    return panel.ghSvc.getPRDiff(
                                                            owner, repo, number);
                                                } catch (Exception e) {
                                                    log.warn(
                                                            "getPRDiff prefetch failed: {}",
                                                            e.getMessage());
                                                    return null;
                                                }
                                            });

                            CompletableFuture<String> validationDiffFuture =
                                    CompletableFuture.supplyAsync(
                                            () -> {
                                                try {
                                                    return panel.ghSvc.getPRDiffFull(
                                                            owner, repo, number);
                                                } catch (Exception e) {
                                                    log.warn(
                                                            "getPRDiffFull prefetch failed: {}",
                                                            e.getMessage());
                                                    return null;
                                                }
                                            });

                            CompletableFuture<String> reviewsFuture =
                                    CompletableFuture.supplyAsync(
                                            () -> {
                                                try {
                                                    return panel.ghSvc.getExistingReviewsSummary(
                                                            owner, repo, number);
                                                } catch (Exception e) {
                                                    log.warn(
                                                            "getExistingReviewsSummary prefetch"
                                                                    + " failed: {}",
                                                            e.getMessage());
                                                    return "";
                                                }
                                            });

                            PrDetail detail = detailFuture.join();
                            PullRequest hydratedPr = hydratePullRequest(pr, detail);
                            boolean merged = detail != null && detail.merged();
                            WebviewPanel.PendingReviewLoad pendingLoad = pendingFuture.join();
                            IntellijGitHubService.PendingReview pending = pendingLoad.review();
                            String fetchedDiff = diffFuture.join();
                            String fetchedValidationDiff = validationDiffFuture.join();
                            String fetchedReviews = reviewsFuture.join();
                            String currentHeadSha =
                                    detail != null && detail.head() != null
                                            ? StringUtils.defaultString(detail.head().sha())
                                            : "";
                            String effectiveValidationDiff =
                                    StringUtils.isNotBlank(fetchedValidationDiff)
                                            ? fetchedValidationDiff
                                            : fetchedDiff;
                            ProviderReadinessDto providerReadiness =
                                    panel.currentProviderReadiness();

                            synchronized (panel) {
                                if (!panel.isCurrentSelectionLocked(key, revision)) {
                                    return;
                                }
                                panel.activePR = hydratedPr;
                                panel.prefetchedDiff = fetchedDiff;
                                panel.prefetchedValidationDiff = effectiveValidationDiff;
                                panel.prefetchedExistingReviews = fetchedReviews;
                            }

                            if (pendingLoad.status()
                                    == WebviewPanel.PendingReviewLoadStatus.FAILED) {
                                log.warn(
                                        "loadDraftReview failed: {}",
                                        pendingLoad.failure().getMessage());
                                panel.publishIfCurrentSelection(
                                        key,
                                        revision,
                                        WebviewPanel.pendingReviewFailureMessage(key, pendingLoad));
                                return;
                            }

                            // Delete stale draft on a merged PR, best-effort.
                            if (merged
                                    && pending != null
                                    && panel.isCurrentSelection(key, revision)) {
                                try {
                                    panel.ghSvc.deleteDraftReview(
                                            owner, repo, number, pending.id());
                                } catch (Exception e) {
                                    log.warn("deleteDraftReview failed: {}", e.getMessage());
                                }
                                pending = null;
                            }

                            if (merged) {
                                panel.draftRecoveryStore.clear(key);
                                synchronized (panel) {
                                    if (!panel.isCurrentSelectionLocked(key, revision)) {
                                        return;
                                    }
                                    panel.pendingReviewId = null;
                                    panel.pendingReviewKey = null;
                                    panel.lastResult = null;
                                }
                                panel.publishIfCurrentSelection(
                                        key,
                                        revision,
                                        new PrDraftStatusMsg(
                                                "prDraftStatusUpdated",
                                                number,
                                                owner,
                                                repo,
                                                false));
                                panel.publishIfCurrentSelection(
                                        key,
                                        revision,
                                        new DraftLoadedMsg(
                                                "draftLoaded",
                                                key,
                                                "MERGED",
                                                null,
                                                null,
                                                fetchedDiff,
                                                effectiveValidationDiff,
                                                false,
                                                false,
                                                false,
                                                "PR is merged.",
                                                providerReadiness,
                                                panel.intellijAssistedEnabled.getAsBoolean(),
                                                panel.rememberedRepositoryInstructions(
                                                        owner, repo)));
                                return;
                            }

                            DraftRecoveryStore.Snapshot recovery =
                                    panel.draftRecoveryStore.get(key);
                            if (pending != null || recovery != null) {
                                boolean stale =
                                        pending != null
                                                && StringUtils.isNotBlank(savedHeadSha)
                                                && !savedHeadSha.equals(currentHeadSha);
                                ReviewResult restored =
                                        recovery != null ? recovery.result() : pending.result();
                                String reviewId = pending != null ? pending.id() : null;
                                ReviewResultDto dto = ReviewMapper.INSTANCE.toDto(restored);
                                synchronized (panel) {
                                    if (!panel.isCurrentSelectionLocked(key, revision)) {
                                        return;
                                    }
                                    panel.pendingReviewId = reviewId;
                                    panel.pendingReviewKey =
                                            StringUtils.isNotBlank(reviewId) ? key : null;
                                    panel.lastResult = restored;
                                }
                                panel.publishIfCurrentSelection(
                                        key,
                                        revision,
                                        new PrDraftStatusMsg(
                                                "prDraftStatusUpdated", number, owner, repo, true));
                                panel.publishIfCurrentSelection(
                                        key,
                                        revision,
                                        new DraftLoadedMsg(
                                                "draftLoaded",
                                                key,
                                                "DRAFT_PRESENT",
                                                reviewId,
                                                dto,
                                                fetchedDiff,
                                                effectiveValidationDiff,
                                                stale,
                                                pending != null && pending.importedFromGitHub(),
                                                recovery != null,
                                                recovery != null
                                                        ? "Recovered a local draft snapshot; save is pending."
                                                        : "Loaded pending draft review.",
                                                providerReadiness,
                                                panel.intellijAssistedEnabled.getAsBoolean(),
                                                panel.rememberedRepositoryInstructions(
                                                        owner, repo)));
                                return;
                            }

                            synchronized (panel) {
                                if (!panel.isCurrentSelectionLocked(key, revision)) {
                                    return;
                                }
                                panel.pendingReviewId = null;
                                panel.pendingReviewKey = null;
                                panel.lastResult = null;
                            }
                            panel.publishIfCurrentSelection(
                                    key,
                                    revision,
                                    new PrDraftStatusMsg(
                                            "prDraftStatusUpdated", number, owner, repo, false));
                            panel.publishIfCurrentSelection(
                                    key,
                                    revision,
                                    new DraftLoadedMsg(
                                            "draftLoaded",
                                            key,
                                            "NO_DRAFT",
                                            null,
                                            null,
                                            fetchedDiff,
                                            effectiveValidationDiff,
                                            false,
                                            false,
                                            false,
                                            "",
                                            providerReadiness,
                                            panel.intellijAssistedEnabled.getAsBoolean(),
                                            panel.rememberedRepositoryInstructions(owner, repo)));
                        });
    }
}
