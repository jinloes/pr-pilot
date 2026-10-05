package com.jinloes.prpilot.ui;

import com.jinloes.prpilot.model.PullRequest;

/** Adapts panel state to the deep-review controller without coupling it to UI fields. */
final class WebviewDeepHost implements DeepReviewController.Host {
    private final WebviewPanel panel;

    WebviewDeepHost(WebviewPanel panel) {
        this.panel = panel;
    }

    @Override
    public boolean isDisposed() {
        return panel.isDisposedForHost();
    }

    @Override
    public PullRequest activePR() {
        return panel.activePR;
    }

    @Override
    public long selectionRevision() {
        return panel.selectionRevision;
    }

    @Override
    public boolean isCurrentSelectionLocked(String expectedKey, long expectedRevision) {
        return panel.isCurrentSelectionLocked(expectedKey, expectedRevision);
    }

    @Override
    public void pushMessage(Object payload) {
        panel.pushMessage(payload);
    }

    @Override
    public String activeReviewOperationId() {
        return panel.activeReviewOperationId;
    }

    @Override
    public void cancelActiveReview(String operationId) {
        panel.cancelActiveReview(operationId);
    }

    @Override
    public boolean intellijAssistedEnabled() {
        return panel.intellijAssistedEnabled.getAsBoolean();
    }
}
