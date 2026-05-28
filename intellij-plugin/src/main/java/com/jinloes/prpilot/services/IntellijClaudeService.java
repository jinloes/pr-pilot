package com.jinloes.prpilot.services;

import com.intellij.openapi.application.ApplicationManager;
import com.jinloes.prpilot.model.ChatMessage;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.ReviewProvider;
import com.jinloes.prpilot.model.ReviewResult;
import com.jinloes.prpilot.settings.PluginSettings;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;

/**
 * IntelliJ adapter that fronts both the Claude and Copilot CLI backends. The active provider is
 * read from {@link PluginSettings} on every call so a settings change takes effect immediately.
 *
 * <p>Dispatches all blocking I/O to a pooled thread and marshals callbacks back to the EDT so
 * callers in the UI layer never need to manage threading themselves.
 */
public class IntellijClaudeService {

    private final ClaudeService claude;
    private final CopilotService copilot;

    public IntellijClaudeService() {
        this.claude = new ClaudeService();
        this.copilot = new CopilotService();
    }

    public IntellijClaudeService(String projectDir) {
        this.claude = new ClaudeService(projectDir);
        this.copilot = new CopilotService(projectDir);
    }

    public void reviewPR(
            PRReviewRequest request,
            Consumer<String> onStatus,
            Consumer<ReviewResult> onComplete,
            Consumer<String> onError) {
        reviewPR(request, onStatus, null, onComplete, onError);
    }

    public void reviewPR(
            PRReviewRequest request,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk,
            Consumer<ReviewResult> onComplete,
            Consumer<String> onError) {
        PluginSettings settings = PluginSettings.getInstance();
        ReviewProvider provider = settings.getReviewProvider();
        String model = settings.getActiveReviewModel();
        String effort = settings.getReviewEffort();
        ApplicationManager.getApplication()
                .executeOnPooledThread(
                        () -> {
                            try {
                                BiConsumer<String, String> wrappedChunk =
                                        onChunk == null
                                                ? null
                                                : (kind, chunk) ->
                                                        invokeLater(
                                                                () -> onChunk.accept(kind, chunk));
                                Consumer<String> wrappedStatus =
                                        status -> invokeLater(() -> onStatus.accept(status));
                                ReviewResult result =
                                        provider == ReviewProvider.COPILOT
                                                ? copilot.reviewPR(
                                                        request,
                                                        model,
                                                        effort,
                                                        wrappedStatus,
                                                        wrappedChunk)
                                                : claude.reviewPR(
                                                        request,
                                                        model,
                                                        wrappedStatus,
                                                        wrappedChunk);
                                invokeLater(() -> onComplete.accept(result));
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                invokeLater(() -> onError.accept("Review interrupted."));
                            } catch (Exception e) {
                                invokeLater(() -> onError.accept(friendlyMessage(provider, e)));
                            }
                        });
    }

    public void chat(
            String prContext,
            List<ChatMessage> history,
            String userMessage,
            Consumer<String> onChunk,
            Consumer<String> onDone,
            Consumer<String> onError) {
        PluginSettings settings = PluginSettings.getInstance();
        ReviewProvider provider = settings.getReviewProvider();
        String effort = settings.getReviewEffort();
        ApplicationManager.getApplication()
                .executeOnPooledThread(
                        () -> {
                            try {
                                Consumer<String> wrappedChunk =
                                        chunk -> invokeLater(() -> onChunk.accept(chunk));
                                String result =
                                        provider == ReviewProvider.COPILOT
                                                ? copilot.chat(
                                                        prContext,
                                                        history,
                                                        userMessage,
                                                        effort,
                                                        wrappedChunk)
                                                : claude.chat(
                                                        prContext,
                                                        history,
                                                        userMessage,
                                                        wrappedChunk);
                                invokeLater(() -> onDone.accept(result));
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                invokeLater(() -> onError.accept("Chat interrupted."));
                            } catch (Exception e) {
                                invokeLater(() -> onError.accept(friendlyMessage(provider, e)));
                            }
                        });
    }

    /** Cancels the currently running request on either backend. */
    public void cancelCurrentRequest() {
        // Cancel both — only one has an active process at any time, but reading the provider
        // setting here can race with a settings change so we just send the signal to both.
        claude.cancelCurrentRequest();
        copilot.cancelCurrentRequest();
    }

    private static void invokeLater(Runnable r) {
        ApplicationManager.getApplication().invokeLater(r);
    }

    static String friendlyMessage(ReviewProvider provider, Exception e) {
        String msg = StringUtils.defaultString(e.getMessage());
        if (msg.contains("No such file") || msg.contains("error=2")) {
            String binary = provider == ReviewProvider.COPILOT ? "copilot" : "claude";
            return "'"
                    + binary
                    + "' not found on PATH. Make sure the "
                    + provider.getDisplayName()
                    + " CLI is installed and accessible from your terminal.";
        }
        return msg.isBlank() ? "Unexpected error." : msg;
    }
}
