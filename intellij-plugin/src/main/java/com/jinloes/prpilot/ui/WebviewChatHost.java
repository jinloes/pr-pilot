package com.jinloes.prpilot.ui;

import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.services.IntellijClaudeService;

/** Adapts panel state to the PR chat controller. */
final class WebviewChatHost implements PrChatController.Host {
    private final WebviewPanel panel;

    WebviewChatHost(WebviewPanel panel) {
        this.panel = panel;
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
    public IntellijClaudeService defaultService() {
        return panel.claudeService;
    }

    @Override
    public IntellijClaudeService resolvePrClaudeService(PullRequest pr) {
        return panel.resolvePrClaudeService(pr);
    }

    @Override
    public String buildPrContext(PullRequest pr) {
        return panel.buildPrContext(pr);
    }

    @Override
    public void pushMessage(Object payload) {
        panel.pushMessage(payload);
    }
}
