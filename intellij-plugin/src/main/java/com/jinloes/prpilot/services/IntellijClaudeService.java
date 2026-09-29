package com.jinloes.prpilot.services;

import com.intellij.openapi.application.ApplicationManager;
import com.jinloes.prpilot.model.ChatMessage;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.ReviewProvider;
import com.jinloes.prpilot.model.ReviewResult;
import com.jinloes.prpilot.review.ClaudeService;
import com.jinloes.prpilot.review.CopilotService;
import com.jinloes.prpilot.review.ReviewPipelineService;
import com.jinloes.prpilot.review.SemanticReviewService;
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

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    public static final class ReviewRuntimeSettings {
        private final ReviewProvider provider;
        private final String model;
        private final String effort;
        private final boolean inheritMcp;
        private final boolean forceMcpOnReview;
        private final String configDir;
        private final boolean selfCritique;
        private final boolean supervisorEnabled;
        private final String secondReviewerModel;

        private ReviewRuntimeSettings(
                ReviewProvider provider,
                String model,
                String effort,
                boolean inheritMcp,
                boolean forceMcpOnReview,
                String configDir,
                boolean selfCritique,
                boolean supervisorEnabled,
                String secondReviewerModel) {
            this.provider = provider;
            this.model = model;
            this.effort = effort;
            this.inheritMcp = inheritMcp;
            this.forceMcpOnReview = forceMcpOnReview;
            this.configDir = configDir;
            this.selfCritique = selfCritique;
            this.supervisorEnabled = supervisorEnabled;
            this.secondReviewerModel = StringUtils.defaultString(secondReviewerModel);
        }

        public ReviewProvider provider() {
            return provider;
        }

        public String model() {
            return model;
        }

        public boolean supervisorEnabled() {
            return supervisorEnabled;
        }

        public String secondReviewerModel() {
            return secondReviewerModel;
        }

        /**
         * Reasoning effort for the second Copilot reviewer: the configured effort only applies when
         * the primary is Copilot, since Claude efforts are not Copilot values.
         */
        String secondReviewerEffort() {
            return provider == ReviewProvider.COPILOT
                    ? effort
                    : CopilotService.DEFAULT_REASONING_EFFORT;
        }

        public Object identity() {
            return java.util.Arrays.asList(
                    provider,
                    model,
                    effort,
                    inheritMcp,
                    forceMcpOnReview,
                    configDir,
                    selfCritique,
                    supervisorEnabled,
                    secondReviewerModel);
        }
    }

    private final ClaudeService claude;
    private final CopilotService copilot;
    private volatile Thread deepThread;
    private volatile Runnable deepProviderCancel = () -> {};
    private final java.util.concurrent.atomic.AtomicBoolean deepCancelled =
            new java.util.concurrent.atomic.AtomicBoolean();

    @FunctionalInterface
    public interface FinalDeepAuthority {
        void validate() throws Exception;
    }

    public record DeepReviewOperation(
            SemanticReviewService service,
            String retainedId,
            String server,
            String prIdentity,
            String operationId,
            FinalDeepAuthority finalAuthority) {}

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
        reviewPR(request, snapshotReviewRuntimeSettings(), onStatus, onChunk, onComplete, onError);
    }

    public void reviewPR(
            PRReviewRequest request,
            ReviewRuntimeSettings settings,
            boolean chunkedReview,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk,
            Consumer<ReviewResult> onComplete,
            Consumer<String> onError) {
        reviewOrdinary(request, settings, chunkedReview, onStatus, onChunk, onComplete, onError);
    }

    public void reviewPR(
            PRReviewRequest request,
            ReviewRuntimeSettings settings,
            boolean chunkedReview,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk,
            Consumer<ReviewResult> onComplete,
            Consumer<String> onError,
            DeepReviewOperation deep) {
        if (deep != null) {
            ApplicationManager.getApplication()
                    .executeOnPooledThread(
                            () -> {
                                deepThread = Thread.currentThread();
                                try {
                                    if (deepCancelled.get())
                                        throw new InterruptedException("Review cancelled");
                                    try (var execution =
                                            deep.service()
                                                    .begin(
                                                            deep.retainedId(),
                                                            deep.server(),
                                                            deep.prIdentity(),
                                                            deep.operationId(),
                                                            request)) {
                                        if (deepCancelled.get())
                                            throw new InterruptedException("Review cancelled");
                                        ReviewPipelineService pipeline;
                                        if (settings.provider == ReviewProvider.COPILOT) {
                                            var provider = new CopilotService(execution.worktree());
                                            deepProviderCancel = provider::cancelCurrentRequest;
                                            pipeline =
                                                    ReviewPipelineService.forCopilot(
                                                            provider,
                                                            settings.model,
                                                            settings.effort,
                                                            false,
                                                            settings.configDir);
                                        } else {
                                            var provider = new ClaudeService(execution.worktree());
                                            deepProviderCancel = provider::cancelCurrentRequest;
                                            pipeline =
                                                    ReviewPipelineService.forClaude(
                                                            provider, settings.model);
                                        }
                                        ReviewResult result =
                                                pipeline.review(
                                                        request,
                                                        chunkedReview,
                                                        settings.selfCritique,
                                                        settings.supervisorEnabled,
                                                        wrapCallback(onStatus),
                                                        wrapChunkCallback(onChunk),
                                                        execution);
                                        deep.finalAuthority().validate();
                                        execution.validate();
                                        if (deepCancelled.get())
                                            throw new InterruptedException("Review cancelled");
                                        // Lease remains held through the actual host publication
                                        // callback.
                                        ApplicationManager.getApplication()
                                                .invokeAndWait(
                                                        () -> {
                                                            if (!deepCancelled.get())
                                                                onComplete.accept(result);
                                                        });
                                    }
                                } catch (Exception failure) {
                                    if (failure instanceof InterruptedException)
                                        Thread.currentThread().interrupt();
                                    invokeLater(
                                            () ->
                                                    onError.accept(
                                                            failure.getMessage() == null
                                                                    ? "IntelliJ-assisted review could not continue."
                                                                    : failure.getMessage()));
                                } finally {
                                    deepThread = null;
                                    deepProviderCancel = () -> {};
                                }
                            });
            return;
        }
        reviewPR(request, settings, chunkedReview, onStatus, onChunk, onComplete, onError);
    }

    private void reviewOrdinary(
            PRReviewRequest request,
            ReviewRuntimeSettings settings,
            boolean chunkedReview,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk,
            Consumer<ReviewResult> onComplete,
            Consumer<String> onError) {
        Consumer<String> wrappedStatus = wrapCallback(onStatus);
        BiConsumer<String, String> wrappedChunk = wrapChunkCallback(onChunk);
        runOnPooledThread(
                settings.provider,
                "generate review",
                "Review interrupted.",
                onError,
                () -> {
                    ReviewPipelineService pipeline =
                            settings.provider == ReviewProvider.COPILOT
                                    ? ReviewPipelineService.forCopilot(
                                            copilot,
                                            settings.model,
                                            settings.effort,
                                            resolveReviewInheritMcp(
                                                    settings.inheritMcp, settings.forceMcpOnReview),
                                            settings.configDir)
                                    : ReviewPipelineService.forClaude(claude, settings.model);
                    if (StringUtils.isNotBlank(settings.secondReviewerModel)) {
                        pipeline =
                                pipeline.withSecondReviewer(
                                        settings.secondReviewerModel,
                                        settings.secondReviewerEffort(),
                                        settings.configDir);
                    }
                    return pipeline.review(
                            request,
                            chunkedReview,
                            settings.selfCritique,
                            settings.supervisorEnabled,
                            wrappedStatus,
                            wrappedChunk);
                },
                onComplete);
    }

    public void reviewPR(
            PRReviewRequest request,
            ReviewRuntimeSettings settings,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk,
            Consumer<ReviewResult> onComplete,
            Consumer<String> onError) {
        reviewPR(request, settings, false, onStatus, onChunk, onComplete, onError);
    }

    public void chat(
            String prContext,
            List<ChatMessage> history,
            String userMessage,
            Consumer<String> onChunk,
            Consumer<String> onDone,
            Consumer<String> onError) {
        chat(
                prContext,
                history,
                userMessage,
                snapshotReviewRuntimeSettings(),
                onChunk,
                onDone,
                onError);
    }

    public void chat(
            String prContext,
            List<ChatMessage> history,
            String userMessage,
            ReviewRuntimeSettings settings,
            Consumer<String> onChunk,
            Consumer<String> onDone,
            Consumer<String> onError) {
        Consumer<String> wrappedChunk = wrapCallback(onChunk);
        runOnPooledThread(
                settings.provider,
                "answer chat question",
                "Chat interrupted.",
                onError,
                () ->
                        settings.provider == ReviewProvider.COPILOT
                                ? copilot.chat(
                                        prContext,
                                        history,
                                        userMessage,
                                        settings.effort,
                                        wrappedChunk,
                                        settings.inheritMcp,
                                        settings.configDir)
                                : claude.chat(prContext, history, userMessage, wrappedChunk),
                onDone);
    }

    /**
     * Sends a focused question about a specific code snippet. Builds the prompt with {@link
     * ClaudeService#buildFocusedChatPrompt} so the model receives only the code context and
     * question — no PR metadata or conversation history — matching VS Code's focused-chat path.
     */
    public void chatFocused(
            String focusedContext,
            String question,
            Consumer<String> onChunk,
            Consumer<String> onDone,
            Consumer<String> onError) {
        chatFocused(
                focusedContext,
                question,
                snapshotReviewRuntimeSettings(),
                onChunk,
                onDone,
                onError);
    }

    public void chatFocused(
            String focusedContext,
            String question,
            ReviewRuntimeSettings settings,
            Consumer<String> onChunk,
            Consumer<String> onDone,
            Consumer<String> onError) {
        String rawPrompt = ClaudeService.buildFocusedChatPrompt(focusedContext, question);
        Consumer<String> wrappedChunk = wrapCallback(onChunk);
        runOnPooledThread(
                settings.provider,
                "answer chat question",
                "Chat interrupted.",
                onError,
                () ->
                        settings.provider == ReviewProvider.COPILOT
                                ? copilot.chatWithPrompt(
                                        rawPrompt,
                                        settings.effort,
                                        wrappedChunk,
                                        settings.inheritMcp,
                                        settings.configDir)
                                : claude.chatWithPrompt(rawPrompt, wrappedChunk),
                onDone);
    }

    /** Cancels the currently running request on either backend. */
    public void cancelCurrentRequest() {
        cancelDeep();
        // Cancel both — only one has an active process at any time, but reading the provider
        // setting here can race with a settings change so we just send the signal to both.
        claude.cancelCurrentRequest();
        copilot.cancelCurrentRequest();
    }

    /** Cancels only the backend selected by the operation being retired. */
    public void cancelCurrentRequest(ReviewProvider provider) {
        cancelDeep();
        if (provider == ReviewProvider.COPILOT) {
            copilot.cancelCurrentRequest();
        } else {
            claude.cancelCurrentRequest();
        }
    }

    private void cancelDeep() {
        deepCancelled.set(true);
        deepProviderCancel.run();
        Thread thread = deepThread;
        if (thread != null) thread.interrupt();
    }

    private static void invokeLater(Runnable r) {
        ApplicationManager.getApplication().invokeLater(r);
    }

    private static Consumer<String> wrapCallback(Consumer<String> callback) {
        return value -> invokeLater(() -> callback.accept(value));
    }

    private static BiConsumer<String, String> wrapChunkCallback(
            BiConsumer<String, String> callback) {
        return callback == null
                ? null
                : (kind, chunk) -> invokeLater(() -> callback.accept(kind, chunk));
    }

    public static ReviewRuntimeSettings snapshotReviewRuntimeSettings() {
        return snapshotReviewRuntimeSettings(PluginSettings.getInstance());
    }

    static ReviewRuntimeSettings snapshotReviewRuntimeSettings(PluginSettings settings) {
        return new ReviewRuntimeSettings(
                settings.getReviewProvider(),
                settings.getActiveReviewModel(),
                settings.getReviewEffort(),
                settings.isCopilotInheritMcp(),
                settings.isCopilotAutoEnableMcpOnReview(),
                settings.getCopilotConfigDir(),
                settings.isReviewSelfCritique(),
                settings.isReviewSupervisorEnabled(),
                settings.getReviewSecondReviewerModel());
    }

    private static <T> void runOnPooledThread(
            ReviewProvider provider,
            String operation,
            String interruptedMessage,
            Consumer<String> onError,
            CheckedSupplier<T> supplier,
            Consumer<T> onSuccess) {
        runOnPooledThread(
                provider,
                operation,
                interruptedMessage,
                onError,
                () -> {
                    T value = supplier.get();
                    invokeLater(() -> onSuccess.accept(value));
                });
    }

    private static void runOnPooledThread(
            ReviewProvider provider,
            String operation,
            String interruptedMessage,
            Consumer<String> onError,
            CheckedRunnable runnable) {
        ApplicationManager.getApplication()
                .executeOnPooledThread(
                        () -> {
                            try {
                                runnable.run();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                invokeLater(() -> onError.accept(interruptedMessage));
                            } catch (Exception e) {
                                invokeLater(
                                        () ->
                                                onError.accept(
                                                        friendlyMessage(provider, e, operation)));
                            }
                        });
    }

    static String friendlyMessage(ReviewProvider provider, Exception e, String operation) {
        return UserFacingErrors.forProvider(provider, e, operation);
    }

    static boolean resolveReviewInheritMcp(boolean inheritMcp, boolean forceMcpOnReview) {
        return inheritMcp || forceMcpOnReview;
    }
}
