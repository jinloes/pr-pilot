package com.jinloes.claudereviews.services;

import com.intellij.openapi.application.ApplicationManager;
import com.jinloes.claudereviews.model.ChatMessage;
import com.jinloes.claudereviews.model.PRReviewRequest;
import com.jinloes.claudereviews.model.ReviewResult;
import com.jinloes.claudereviews.settings.PluginSettings;
import java.util.List;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;

/**
 * IntelliJ wrapper around the core {@link ClaudeService}.
 *
 * <p>Dispatches all blocking I/O to a pooled thread and marshals callbacks back to the EDT so
 * callers in the UI layer never need to manage threading themselves.
 */
public class IntellijClaudeService {

    private final ClaudeService core = new ClaudeService();

    /**
     * Generates a review asynchronously. {@code onStatus}, {@code onComplete}, and {@code onError}
     * are called on the EDT.
     */
    public void reviewPR(
            PRReviewRequest request,
            Consumer<String> onStatus,
            Consumer<ReviewResult> onComplete,
            Consumer<String> onError) {
        String model = PluginSettings.getInstance().getReviewModel();
        ApplicationManager.getApplication()
                .executeOnPooledThread(
                        () -> {
                            try {
                                ReviewResult result =
                                        core.reviewPR(
                                                request,
                                                model,
                                                status ->
                                                        invokeLater(() -> onStatus.accept(status)));
                                invokeLater(() -> onComplete.accept(result));
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                invokeLater(() -> onError.accept("Review interrupted."));
                            } catch (Exception e) {
                                invokeLater(() -> onError.accept(friendlyMessage(e)));
                            }
                        });
    }

    /**
     * Sends a chat message asynchronously. {@code onChunk}, {@code onDone}, and {@code onError} are
     * called on the EDT.
     */
    public void chat(
            String prContext,
            List<ChatMessage> history,
            String userMessage,
            Consumer<String> onChunk,
            Consumer<String> onDone,
            Consumer<String> onError) {
        ApplicationManager.getApplication()
                .executeOnPooledThread(
                        () -> {
                            try {
                                String result =
                                        core.chat(
                                                prContext,
                                                history,
                                                userMessage,
                                                chunk -> invokeLater(() -> onChunk.accept(chunk)));
                                invokeLater(() -> onDone.accept(result));
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                invokeLater(() -> onError.accept("Chat interrupted."));
                            } catch (Exception e) {
                                invokeLater(() -> onError.accept(friendlyMessage(e)));
                            }
                        });
    }

    /**
     * Sends a focused code question asynchronously. {@code onChunk}, {@code onDone}, and {@code
     * onError} are called on the EDT.
     */
    public void chatFocused(
            String focusedContext,
            String question,
            Consumer<String> onChunk,
            Consumer<String> onDone,
            Consumer<String> onError) {
        ApplicationManager.getApplication()
                .executeOnPooledThread(
                        () -> {
                            try {
                                String result =
                                        core.chatFocused(
                                                focusedContext,
                                                question,
                                                chunk -> invokeLater(() -> onChunk.accept(chunk)));
                                invokeLater(() -> onDone.accept(result));
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                invokeLater(() -> onError.accept("Chat interrupted."));
                            } catch (Exception e) {
                                invokeLater(() -> onError.accept(friendlyMessage(e)));
                            }
                        });
    }

    /** Cancels the currently running request, if any. */
    public void cancelCurrentRequest() {
        core.cancelCurrentRequest();
    }

    private static void invokeLater(Runnable r) {
        ApplicationManager.getApplication().invokeLater(r);
    }

    private static String friendlyMessage(Exception e) {
        String msg = StringUtils.defaultString(e.getMessage());
        if (msg.contains("No such file") || msg.contains("error=2"))
            return "'claude' not found on PATH. Make sure Claude Code is installed and accessible"
                    + " from your terminal.";
        return msg.isBlank() ? "Unexpected error." : msg;
    }
}
