package com.jinloes.prpilot.ui;

import com.intellij.openapi.util.Disposer;
import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.model.ReviewProvider;
import com.jinloes.prpilot.services.IntellijClaudeService;
import com.jinloes.prpilot.ui.PrChatController.ChatReset;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.PrWorktree;
import org.apache.commons.lang3.StringUtils;

final class WebviewPanelLifecycle {

    private final WebviewPanel panel;

    WebviewPanelLifecycle(WebviewPanel panel) {
        this.panel = panel;
    }

    WebviewPanel.LifecycleTransition transitionToSelection(PullRequest pr) {
        IntellijClaudeService reviewService = panel.activeReviewService;
        ReviewProvider reviewProvider = panel.activeReviewProvider;
        String reviewOperationId = panel.activeReviewOperationId;
        panel.activeReviewService = panel.claudeService;
        panel.activeReviewProvider = ReviewProvider.CLAUDE;
        panel.activeReviewOperationId = null;
        panel.activeGenerationId = panel.generationSequence.incrementAndGet();
        ChatReset chat = panel.chats.resetLocked();
        PrWorktree worktree = panel.worktreeManager.clear();

        boolean samePullRequest =
                panel.activePR != null
                        && panel.activePR.getNumber() == pr.getNumber()
                        && StringUtils.equals(panel.activePR.getOwner(), pr.getOwner())
                        && StringUtils.equals(panel.activePR.getRepo(), pr.getRepo());
        panel.activePR = pr;
        if (!samePullRequest) {
            panel.lastResult = null;
            panel.pendingReviewId = null;
            panel.pendingReviewKey = null;
        }
        panel.prefetchedDiff = null;
        panel.prefetchedValidationDiff = null;
        panel.prefetchedExistingReviews = null;
        return new WebviewPanel.LifecycleTransition(
                ++panel.selectionRevision,
                reviewService,
                chat.service(),
                reviewProvider,
                chat.provider(),
                reviewOperationId,
                chat.operationId(),
                worktree);
    }

    void finishLifecycleTransition(WebviewPanel.LifecycleTransition transition) {
        if (transition.reviewOperationId() != null) {
            transition.reviewService().cancelCurrentRequest(transition.reviewProvider());
        }
        if (transition.chatOperationId() != null) {
            transition.chatService().cancelCurrentRequest(transition.chatProvider());
        }
        panel.worktreeManager.removeAsync(transition.worktree());
    }

    void cancelActiveReview(String operationId) {
        IntellijClaudeService service;
        ReviewProvider provider;
        synchronized (panel) {
            panel.assistedReviews.onReviewCancelledLocked(operationId);
            if (!StringUtils.equals(panel.activeReviewOperationId, operationId)) return;
            panel.activeGenerationId = panel.generationSequence.incrementAndGet();
            service = panel.activeReviewService;
            provider = panel.activeReviewProvider;
            panel.activeReviewService = panel.claudeService;
            panel.activeReviewProvider = ReviewProvider.CLAUDE;
            panel.activeReviewOperationId = null;
        }
        if (service != null) service.cancelCurrentRequest(provider);
    }

    void dispose() {
        WebviewPanel.LifecycleTransition transition;
        synchronized (panel) {
            if (panel.disposed) {
                return;
            }
            panel.disposed = true;
            transition = transitionToSelection(null);
        }
        finishLifecycleTransition(transition);
        panel.pendingIndexRecoveryRegistration.close();
        panel.resourceServer.stop();
        Disposer.dispose(panel.bridgeQuery);
        Disposer.dispose(panel.browser);
    }
}
