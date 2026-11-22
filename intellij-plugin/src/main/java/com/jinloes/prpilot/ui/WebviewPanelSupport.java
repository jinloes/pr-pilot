package com.jinloes.prpilot.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.jinloes.prpilot.model.ChatContext;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.JPanel;

/** Small stateless helpers shared by the webview panel and its collaborators. */
final class WebviewPanelSupport {
    private WebviewPanelSupport() {}

    record ResolvedEditorTarget(Path file, int zeroBasedLine, boolean temporaryWorktree) {}

    /**
     * Resolves a repository-relative file under workspace-checkout-first semantics: prefers the
     * matching open workspace checkout when the file exists inside {@code workspaceGitRoot}, and
     * falls back to the PR-head worktree (for example for files newly added in the PR), marking
     * temporary worktree targets read-only. Every candidate is {@code toRealPath()}-confined to its
     * root.
     */
    static ResolvedEditorTarget resolveOpenFileTarget(
            Path workspaceGitRoot,
            Supplier<Path> worktreeSupplier,
            String relativeFile,
            int oneBasedLine)
            throws IOException {
        if (!BridgeMessageValidator.validRepoRelativeFile(relativeFile)) {
            return null;
        }
        int zeroBasedLine =
                (oneBasedLine >= 1 && oneBasedLine <= 10_000_000) ? oneBasedLine - 1 : 0;
        if (workspaceGitRoot != null
                && Files.isDirectory(workspaceGitRoot, LinkOption.NOFOLLOW_LINKS)) {
            Path resolvedWorkspace = resolveConfinedRegularFile(workspaceGitRoot, relativeFile);
            if (resolvedWorkspace != null) {
                return new ResolvedEditorTarget(resolvedWorkspace, zeroBasedLine, false);
            }
        }
        Path worktreeRoot = worktreeSupplier == null ? null : worktreeSupplier.get();
        if (worktreeRoot != null && Files.isDirectory(worktreeRoot, LinkOption.NOFOLLOW_LINKS)) {
            Path resolvedWorktree = resolveConfinedRegularFile(worktreeRoot, relativeFile);
            if (resolvedWorktree != null) {
                return new ResolvedEditorTarget(resolvedWorktree, zeroBasedLine, true);
            }
        }
        return null;
    }

    private static Path resolveConfinedRegularFile(Path root, String relativeFile)
            throws IOException {
        try {
            Path realRoot = root.toRealPath();
            Path candidate = realRoot.resolve(relativeFile).normalize();
            if (!candidate.startsWith(realRoot) || !Files.exists(candidate)) {
                return null;
            }
            Path realCandidate = candidate.toRealPath();
            if (!realCandidate.startsWith(realRoot)
                    || !Files.isRegularFile(realCandidate, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            return realCandidate;
        } catch (InvalidPathException invalidPath) {
            return null;
        }
    }

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

    private static ReviewProvider configuredReviewProvider() {
        return com.intellij.openapi.application.ApplicationManager.getApplication() != null
                ? PluginSettings.getInstance().getReviewProvider()
                : ReviewProvider.CLAUDE;
    }

    static WebviewBridgeMessages.ProviderReadinessDto currentProviderReadiness() {
        ReviewProvider provider = configuredReviewProvider();
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
        if (setup == null) {
            return currentProviderReadiness();
        }
        ReviewProvider provider = configuredReviewProvider();
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

    static ChatContext buildChatContext(WebviewPanel panel, PullRequest pr) {
        ReviewResult result = panel.lastResult;
        List<ChatContext.ReviewComment> comments =
                result == null || result.getLineComments() == null
                        ? List.of()
                        : result.getLineComments().stream()
                                .map(ChatContext.ReviewComment::of)
                                .toList();
        return new ChatContext(
                pr.getOwner(),
                pr.getRepo(),
                pr.getNumber(),
                pr.getTitle(),
                pr.getAuthor(),
                pr.getBody(),
                panel.prefetchedDiff,
                result == null ? null : result.getVerdict(),
                result == null ? null : result.getSummary(),
                comments,
                null);
    }
}
