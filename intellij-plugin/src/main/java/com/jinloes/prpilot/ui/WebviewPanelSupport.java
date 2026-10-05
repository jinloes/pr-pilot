package com.jinloes.prpilot.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.model.ReviewProvider;
import com.jinloes.prpilot.model.ReviewResult;
import com.jinloes.prpilot.review.ClaudeService;
import com.jinloes.prpilot.review.CopilotService;
import com.jinloes.prpilot.review.ProviderSetupProbe;
import com.jinloes.prpilot.services.IntellijGitHubService;
import com.jinloes.prpilot.services.UserFacingErrors;
import com.jinloes.prpilot.settings.PluginSettings;
import java.awt.BorderLayout;
import java.util.function.BooleanSupplier;
import javax.swing.JComponent;
import javax.swing.JPanel;
import org.apache.commons.lang3.StringUtils;

/** Small stateless helpers shared by the webview panel and its collaborators. */
final class WebviewPanelSupport {
    private WebviewPanelSupport() {}

    static boolean isWebviewPage(String loadedUrl, String webviewUrl) {
        return webviewUrl != null && loadedUrl != null && loadedUrl.startsWith(webviewUrl);
    }

    static JPanel createBrowserHostPanel(JComponent browserComponent) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(browserComponent, BorderLayout.CENTER);
        return panel;
    }

    static boolean isValidIncomingMessage(JsonNode node) {
        return BridgeMessageValidator.isValid(node);
    }

    static WebviewPanel.PendingReviewLoad loadPendingReview(
            WebviewPanel.PendingReviewLoader loader) {
        try {
            IntellijGitHubService.PendingReview review = loader.load();
            return review == null
                    ? WebviewPanel.PendingReviewLoad.none()
                    : WebviewPanel.PendingReviewLoad.loaded(review);
        } catch (Exception e) {
            return WebviewPanel.PendingReviewLoad.failed(e);
        }
    }

    static Object pendingReviewFailureMessage(String prKey, WebviewPanel.PendingReviewLoad load) {
        if (load.status() != WebviewPanel.PendingReviewLoadStatus.FAILED
                || load.failure() == null) {
            throw new IllegalArgumentException("A failed pending-review load is required");
        }
        return new WebviewBridgeMessages.ErrorMsg(
                "reviewError",
                prKey,
                UserFacingErrors.forGitHub(load.failure(), "load the pending review"));
    }

    static void publishIfActive(
            Object lifecycleLock, BooleanSupplier disposed, Runnable browserCall) {
        synchronized (lifecycleLock) {
            if (!disposed.getAsBoolean()) {
                browserCall.run();
            }
        }
    }

    static boolean isProviderBinaryAvailable(ReviewProvider provider) {
        return provider == ReviewProvider.COPILOT
                ? CopilotService.isBinaryAvailable()
                : ClaudeService.isBinaryAvailable();
    }

    static WebviewBridgeMessages.ProviderReadinessDto currentProviderReadiness() {
        ReviewProvider provider = PluginSettings.getInstance().getReviewProvider();
        boolean available = isProviderBinaryAvailable(provider);
        return new WebviewBridgeMessages.ProviderReadinessDto(
                provider == ReviewProvider.COPILOT ? "copilot" : "claude",
                available,
                available
                        ? "Provider CLI found. Authentication cannot be verified without starting a provider session."
                        : UserFacingErrors.forProviderNotInstalled(provider),
                available ? "ready" : "missing",
                available ? "unverified" : "unavailable",
                provider == ReviewProvider.COPILOT ? "copilot login" : "claude auth login");
    }

    static WebviewBridgeMessages.ProviderReadinessDto providerReadiness(
            ProviderSetupProbe.Result setup) {
        ReviewProvider provider = PluginSettings.getInstance().getReviewProvider();
        String detail;
        if (!setup.available()) {
            detail = UserFacingErrors.forProviderNotInstalled(provider);
        } else if ("ready".equals(setup.authenticationStatus())) {
            detail = "Provider CLI and authentication are ready.";
        } else if ("unavailable".equals(setup.authenticationStatus())) {
            detail =
                    "Provider authentication is unavailable. Run '"
                            + setup.authCommand()
                            + "' and check again.";
        } else {
            detail =
                    "Provider CLI found. Authentication cannot be verified non-interactively; run '"
                            + setup.authCommand()
                            + "' if sign-in is required.";
        }
        return new WebviewBridgeMessages.ProviderReadinessDto(
                provider == ReviewProvider.COPILOT ? "copilot" : "claude",
                setup.available(),
                detail,
                setup.binaryStatus(),
                setup.authenticationStatus(),
                setup.authCommand());
    }

    static String buildPrContext(WebviewPanel panel, PullRequest pr) {
        StringBuilder sb = new StringBuilder();
        sb.append("PR #").append(pr.getNumber()).append(": ").append(pr.getTitle()).append("\n");
        sb.append("Author: @").append(pr.getAuthor()).append("\n");
        sb.append("Repo: ").append(pr.getOwner()).append("/").append(pr.getRepo()).append("\n");

        String body = pr.getBody();
        if (StringUtils.isNotBlank(body)) {
            sb.append("\nPR Description:\n").append(body).append("\n");
        }

        ReviewResult result = panel.lastResult;
        if (result != null) {
            sb.append("\nReview verdict: ").append(result.getVerdict()).append("\n");
            sb.append("Review summary: ").append(result.getSummary()).append("\n");
        }

        String diff = panel.prefetchedDiff;
        if (StringUtils.isNotBlank(diff)) {
            sb.append("\nDiff:\n").append(diff);
        }

        return sb.toString();
    }
}
