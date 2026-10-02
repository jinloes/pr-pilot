package com.jinloes.prpilot.ui;

import static com.jinloes.prpilot.ui.WebviewPrSupport.bridgePrKey;

import com.jinloes.prpilot.model.ChatMessage;
import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.model.ReviewProvider;
import com.jinloes.prpilot.services.IntellijClaudeService;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.ChatChunkMsg;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.ChatResponseMsg;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.ErrorMsg;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the webview's PR chat state. Every state transition happens under the host panel's lock so
 * chat ownership stays consistent with the panel's PR selection.
 */
final class PrChatController {

    private static final Logger log = LoggerFactory.getLogger(PrChatController.class);

    /** The panel members the chat flow reads; implemented by {@link WebviewPanel}. */
    interface Host {
        PullRequest activePR();

        long selectionRevision();

        /** The project-root service chat falls back to between requests. */
        IntellijClaudeService defaultService();

        IntellijClaudeService resolvePrClaudeService(PullRequest pr);

        String buildPrContext(PullRequest pr);

        void pushMessage(Object payload);
    }

    /** The chat request that owned the provider before a reset, so the caller can cancel it. */
    record ChatReset(IntellijClaudeService service, ReviewProvider provider, String operationId) {}

    private final Object hostLock;
    private final Host host;
    private final AtomicLong chatSequence = new AtomicLong();
    private volatile long activeChatId;
    private volatile String activeChatOperationId;
    private volatile List<ChatMessage> chatHistory = List.of();
    private volatile IntellijClaudeService activeChatService;
    private volatile ReviewProvider activeChatProvider = ReviewProvider.CLAUDE;

    PrChatController(Object hostLock, Host host) {
        this.hostLock = hostLock;
        this.host = host;
        this.activeChatService = host.defaultService();
    }

    /** Invalidates the active chat and clears history; the caller holds the host lock. */
    ChatReset resetLocked() {
        IntellijClaudeService chatService = activeChatService;
        ReviewProvider chatProvider = activeChatProvider;
        String chatOperationId = activeChatOperationId;
        activeChatService = host.defaultService();
        activeChatProvider = ReviewProvider.CLAUDE;
        activeChatOperationId = null;
        activeChatId = chatSequence.incrementAndGet();
        chatHistory = List.of();
        return new ChatReset(chatService, chatProvider, chatOperationId);
    }

    void ask(String question, String context, String operationId) {
        if (StringUtils.isBlank(question)) {
            return;
        }

        final PullRequest pr;
        final long selectionRevisionSnapshot;
        final long chatId;
        final List<ChatMessage> history;
        final IntellijClaudeService.ReviewRuntimeSettings runtimeSettings;
        final IntellijClaudeService previousChatService;
        final ReviewProvider previousChatProvider;
        synchronized (hostLock) {
            pr = host.activePR();
            selectionRevisionSnapshot = host.selectionRevision();
            chatId = chatSequence.incrementAndGet();
            activeChatId = chatId;
            history = List.copyOf(chatHistory);
            runtimeSettings = IntellijClaudeService.snapshotReviewRuntimeSettings();
            previousChatService = activeChatService;
            previousChatProvider = activeChatProvider;
            activeChatService = host.defaultService();
            activeChatProvider = runtimeSettings.provider();
            activeChatOperationId = operationId;
        }
        if (pr == null) {
            synchronized (hostLock) {
                if (activeChatId == chatId
                        && StringUtils.equals(activeChatOperationId, operationId)) {
                    activeChatService = host.defaultService();
                    activeChatProvider = ReviewProvider.CLAUDE;
                    activeChatOperationId = null;
                }
            }
            host.pushMessage(new ErrorMsg("chatError", null, "No PR selected."));
            return;
        }
        String key = bridgePrKey(pr.getNumber(), pr.getOwner(), pr.getRepo());
        previousChatService.cancelCurrentRequest(previousChatProvider);

        IntellijClaudeService chatService;
        try {
            chatService = host.resolvePrClaudeService(pr);
        } catch (Exception e) {
            log.warn("Worktree resolution for PR #{} failed: {}", pr.getNumber(), e.getMessage());
            host.pushMessage(
                    new ErrorMsg(
                            "chatError",
                            key,
                            "Unable to create an isolated pull request worktree."
                                    + " Open the PR repository and try again."));
            synchronized (hostLock) {
                if (isCurrentChatLocked(key, selectionRevisionSnapshot, chatId)) {
                    activeChatService = host.defaultService();
                    activeChatProvider = ReviewProvider.CLAUDE;
                    activeChatOperationId = null;
                }
            }
            return;
        }
        synchronized (hostLock) {
            if (!isCurrentChatLocked(key, selectionRevisionSnapshot, chatId)) {
                return;
            }
            activeChatService = chatService;
        }

        // When the user has selected a code snippet, use a focused prompt (no history, no PR
        // context) — matching VS Code's buildFocusedChatPrompt path. Responses for focused
        // questions are not stored in chatHistory since they are context-specific.
        if (StringUtils.isNotBlank(context)) {
            chatService.chatFocused(
                    context,
                    question,
                    runtimeSettings,
                    chunk ->
                            publishIfCurrentChat(
                                    key,
                                    selectionRevisionSnapshot,
                                    chatId,
                                    new ChatChunkMsg("chatChunk", key, chunk)),
                    response -> {
                        synchronized (hostLock) {
                            if (!isCurrentChatLocked(key, selectionRevisionSnapshot, chatId)) {
                                return;
                            }
                            activeChatService = host.defaultService();
                            activeChatProvider = ReviewProvider.CLAUDE;
                            activeChatOperationId = null;
                        }
                        publishIfCurrentChat(
                                key,
                                selectionRevisionSnapshot,
                                chatId,
                                new ChatResponseMsg("chatResponse", key, response));
                    },
                    err -> {
                        synchronized (hostLock) {
                            if (!isCurrentChatLocked(key, selectionRevisionSnapshot, chatId)) {
                                return;
                            }
                            activeChatService = host.defaultService();
                            activeChatProvider = ReviewProvider.CLAUDE;
                            activeChatOperationId = null;
                        }
                        publishIfCurrentChat(
                                key,
                                selectionRevisionSnapshot,
                                chatId,
                                new ErrorMsg("chatError", key, err));
                    });
            return;
        }

        String prContext = host.buildPrContext(pr);

        chatService.chat(
                prContext,
                history,
                question,
                runtimeSettings,
                chunk ->
                        publishIfCurrentChat(
                                key,
                                selectionRevisionSnapshot,
                                chatId,
                                new ChatChunkMsg("chatChunk", key, chunk)),
                response -> {
                    synchronized (hostLock) {
                        if (!isCurrentChatLocked(key, selectionRevisionSnapshot, chatId)) {
                            return;
                        }
                        List<ChatMessage> updated = new ArrayList<>(history);
                        updated.add(new ChatMessage(ChatMessage.Role.USER, question));
                        updated.add(new ChatMessage(ChatMessage.Role.ASSISTANT, response));
                        chatHistory = List.copyOf(updated);
                        activeChatService = host.defaultService();
                        activeChatProvider = ReviewProvider.CLAUDE;
                        activeChatOperationId = null;
                    }
                    publishIfCurrentChat(
                            key,
                            selectionRevisionSnapshot,
                            chatId,
                            new ChatResponseMsg("chatResponse", key, response));
                },
                err -> {
                    synchronized (hostLock) {
                        if (!isCurrentChatLocked(key, selectionRevisionSnapshot, chatId)) {
                            return;
                        }
                        activeChatService = host.defaultService();
                        activeChatProvider = ReviewProvider.CLAUDE;
                        activeChatOperationId = null;
                    }
                    publishIfCurrentChat(
                            key,
                            selectionRevisionSnapshot,
                            chatId,
                            new ErrorMsg("chatError", key, err));
                });
    }

    void clear(String operationId) {
        IntellijClaudeService service;
        ReviewProvider provider;
        synchronized (hostLock) {
            if (!StringUtils.equals(activeChatOperationId, operationId)) {
                chatHistory = List.of();
                return;
            }
            activeChatId = chatSequence.incrementAndGet();
            chatHistory = List.of();
            service = activeChatService;
            provider = activeChatProvider;
            activeChatService = host.defaultService();
            activeChatProvider = ReviewProvider.CLAUDE;
            activeChatOperationId = null;
        }
        service.cancelCurrentRequest(provider);
    }

    /**
     * Stops the chat answer that owns {@code operationId} without deleting conversation history.
     * Advancing the chat id drops the stopped answer's late chunks, response and error silently.
     */
    void cancel(String operationId) {
        IntellijClaudeService service;
        ReviewProvider provider;
        synchronized (hostLock) {
            if (activeChatOperationId == null
                    || !StringUtils.equals(activeChatOperationId, operationId)) {
                return;
            }
            activeChatId = chatSequence.incrementAndGet();
            service = activeChatService;
            provider = activeChatProvider;
            activeChatService = host.defaultService();
            activeChatProvider = ReviewProvider.CLAUDE;
            activeChatOperationId = null;
        }
        if (service != null) service.cancelCurrentRequest(provider);
    }

    static boolean isCurrentChat(
            PullRequest currentPr,
            long currentSelectionRevision,
            long currentChatId,
            String expectedKey,
            long expectedSelectionRevision,
            long expectedChatId) {
        return currentChatId == expectedChatId
                && WebviewPrSupport.isCurrentSelection(
                        currentPr,
                        currentSelectionRevision,
                        expectedKey,
                        expectedSelectionRevision);
    }

    boolean isCurrentChatLocked(
            String expectedKey, long expectedSelectionRevision, long expectedChatId) {
        return isCurrentChat(
                host.activePR(),
                host.selectionRevision(),
                activeChatId,
                expectedKey,
                expectedSelectionRevision,
                expectedChatId);
    }

    void publishIfCurrentChat(
            String expectedKey,
            long expectedSelectionRevision,
            long expectedChatId,
            Object message) {
        synchronized (hostLock) {
            if (!isCurrentChatLocked(expectedKey, expectedSelectionRevision, expectedChatId)) {
                return;
            }
            host.pushMessage(message);
        }
    }
}
