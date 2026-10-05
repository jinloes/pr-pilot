package com.jinloes.prpilot.ui;

import static com.jinloes.prpilot.ui.WebviewPrSupport.healthyDraftEntries;
import static com.jinloes.prpilot.ui.WebviewPrSupport.isSamePr;
import static com.jinloes.prpilot.ui.WebviewPrSupport.mergeActivatedPr;
import static com.jinloes.prpilot.ui.WebviewPrSupport.toWebviewPr;

import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.review.ProviderSetupProbe;
import com.jinloes.prpilot.services.PendingReviewIndex;
import com.jinloes.prpilot.services.PendingReviewIndexNotifications;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.ActivatePrMsg;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.PrListMessage;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.PrListStatus;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.ProviderReadinessDto;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.WebviewPr;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Publishes PR lists and maintains the pending-draft index view. */
final class WebviewPrListController {
    private static final Logger log = LoggerFactory.getLogger(WebviewPrListController.class);
    private final WebviewPanel panel;

    WebviewPrListController(WebviewPanel panel) {
        this.panel = panel;
    }

    /** Pushes the PR list into the webview via the bridge. Call from the EDT. */
    public void loadPRs(
            List<PullRequest> prs,
            String defaultRepo,
            String searchScope,
            String currentRepo,
            boolean limited,
            boolean reviewStatusAvailable,
            ProviderSetupProbe.Result providerSetup) {
        panel.cachedPRs = prs;
        ProviderReadinessDto providerReadiness = panel.providerReadiness(providerSetup);
        if (!providerReadiness.available()) {
            panel.pushSetupRequired(
                    "provider_not_installed", providerReadiness.detail(), providerReadiness);
            return;
        }
        if ("unavailable".equals(providerReadiness.authenticationStatus())) {
            panel.pushSetupRequired(
                    "provider_not_authenticated", providerReadiness.detail(), providerReadiness);
            return;
        }
        Optional<List<PendingReviewIndex.Entry>> pendingEntries = loadHealthyDraftEntries();
        if (pendingEntries.isEmpty()) {
            panel.pushSetupRequired(
                    "draft_index_unavailable", PendingReviewIndexNotifications.userMessage());
            return;
        }
        Set<String> draftKeys =
                pendingEntries.orElseThrow().stream()
                        .map(e -> e.owner() + "/" + e.repo() + "#" + e.number())
                        .collect(java.util.stream.Collectors.toSet());
        List<WebviewPr> dtos =
                prs.stream()
                        .map(
                                pr ->
                                        toWebviewPr(
                                                pr,
                                                draftKeys.contains(
                                                        pr.getOwner()
                                                                + "/"
                                                                + pr.getRepo()
                                                                + "#"
                                                                + pr.getNumber())))
                        .toList();
        panel.pushMessage(
                prListMessage(
                        dtos,
                        defaultRepo,
                        new PrListStatus(
                                panel.searchScope,
                                currentRepo,
                                WebviewPanel.PR_SEARCH_LIMIT,
                                limited,
                                reviewStatusAvailable),
                        providerReadiness));
    }

    /**
     * Carries the experimental setting on the session-level list message so PR-agnostic webview
     * surfaces (retained-worktree maintenance) can honor it before any PR is selected.
     */
    PrListMessage prListMessage(
            List<WebviewPr> prs,
            String defaultRepo,
            PrListStatus listStatus,
            ProviderReadinessDto providerReadiness) {
        return new PrListMessage(
                "prListLoaded",
                prs,
                defaultRepo,
                listStatus,
                providerReadiness,
                panel.intellijAssistedEnabled.getAsBoolean());
    }

    public void activatePr(PullRequest pr, String source) {
        Optional<List<PendingReviewIndex.Entry>> pendingEntries = loadHealthyDraftEntries();
        if (pendingEntries.isEmpty()) {
            panel.pushSetupRequired(
                    "draft_index_unavailable", PendingReviewIndexNotifications.userMessage());
            return;
        }
        boolean hasReviewDraft =
                pendingEntries.orElseThrow().stream()
                        .anyMatch(
                                entry ->
                                        entry.owner().equals(pr.getOwner())
                                                && entry.repo().equals(pr.getRepo())
                                                && entry.number() == pr.getNumber());
        PullRequest activatedPr =
                panel.cachedPRs.stream()
                        .filter(existing -> isSamePr(existing, pr))
                        .findFirst()
                        .map(existing -> mergeActivatedPr(existing, pr))
                        .orElse(pr);
        if (panel.cachedPRs.stream().anyMatch(existing -> isSamePr(existing, activatedPr))) {
            panel.cachedPRs =
                    panel.cachedPRs.stream()
                            .map(
                                    existing ->
                                            isSamePr(existing, activatedPr)
                                                    ? activatedPr
                                                    : existing)
                            .toList();
        } else {
            List<PullRequest> next = new ArrayList<>();
            next.add(activatedPr);
            next.addAll(panel.cachedPRs);
            panel.cachedPRs = next;
        }
        panel.pushMessage(
                new ActivatePrMsg("activatePR", toWebviewPr(activatedPr, hasReviewDraft), source));
    }

    Optional<List<PendingReviewIndex.Entry>> loadHealthyDraftEntries() {
        PendingReviewIndex.LoadResult result = panel.pendingIndex.listResult();
        observePendingIndex(result);
        return healthyDraftEntries(result);
    }

    void reportPendingIndexMutation(String operation, PendingReviewIndex.MutationResult result) {
        if (result == PendingReviewIndex.MutationResult.UPDATED) {
            return;
        }
        log.warn("Pending review index was not updated after {}: {}", operation, result);
        if (result == PendingReviewIndex.MutationResult.BLOCKED_CORRUPT) {
            PendingReviewIndex.LoadResult loadResult = panel.pendingIndex.listResult();
            observePendingIndex(loadResult);
        }
    }

    private void observePendingIndex(PendingReviewIndex.LoadResult result) {
        panel.pendingIndexRecoveryRegistration.close();
        panel.pendingIndexRecoveryRegistration =
                PendingReviewIndexNotifications.observe(
                        panel.pendingIndex, result, panel.pendingIndexRecoveryAction);
    }
}
