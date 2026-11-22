package com.jinloes.prpilot.services;

import com.intellij.openapi.application.ApplicationManager;
import com.jinloes.prpilot.model.ChatContext;
import com.jinloes.prpilot.model.ChatMessage;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.ReviewProvider;
import com.jinloes.prpilot.model.ReviewResult;
import com.jinloes.prpilot.review.AntigravityService;
import com.jinloes.prpilot.review.ClaudeService;
import com.jinloes.prpilot.review.CopilotService;
import com.jinloes.prpilot.review.ReviewPipelineService;
import com.jinloes.prpilot.review.SemanticReviewService;
import com.jinloes.prpilot.settings.PluginSettings;
import com.jinloes.prpilot.util.LogRedactor;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IntelliJ adapter that fronts the Claude, Copilot, and Antigravity CLI backends. The active
 * provider is read from {@link PluginSettings} on every call so a settings change takes effect
 * immediately.
 *
 * <p>Dispatches all blocking I/O to a pooled thread and marshals callbacks back to the EDT so
 * callers in the UI layer never need to manage threading themselves.
 */
public class IntellijClaudeService {

    private static final Logger log = LoggerFactory.getLogger(IntellijClaudeService.class);

    @FunctionalInterface
    interface CheckedRunnable {
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
        private final String secondReviewerEffort;

        private ReviewRuntimeSettings(
                ReviewProvider provider,
                String model,
                String effort,
                boolean inheritMcp,
                boolean forceMcpOnReview,
                String configDir,
                boolean selfCritique,
                boolean supervisorEnabled,
                String secondReviewerModel,
                String secondReviewerEffort) {
            this.provider = provider;
            this.model = model;
            this.effort = effort;
            this.inheritMcp = inheritMcp;
            this.forceMcpOnReview = forceMcpOnReview;
            this.configDir = configDir;
            this.selfCritique = selfCritique;
            this.supervisorEnabled = supervisorEnabled;
            this.secondReviewerModel = StringUtils.defaultString(secondReviewerModel);
            this.secondReviewerEffort = StringUtils.trimToEmpty(secondReviewerEffort);
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

        /** Reasoning effort for the second Copilot reviewer, independent of the primary's. */
        String secondReviewerEffort() {
            return secondReviewerEffort;
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
                    secondReviewerModel,
                    secondReviewerEffort);
        }
    }

    private final ClaudeService claude;
    private final CopilotService copilot;
    private final AntigravityService antigravity;
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
        this.antigravity = new AntigravityService();
    }

    public IntellijClaudeService(String projectDir) {
        this.claude = new ClaudeService(projectDir);
        this.copilot = new CopilotService(projectDir);
        this.antigravity = new AntigravityService(projectDir);
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
                                        ReviewPipelineService pipeline =
                                                switch (settings.provider) {
                                                    case COPILOT -> {
                                                        var provider =
                                                                new CopilotService(
                                                                        execution.worktree());
                                                        deepProviderCancel =
                                                                provider::cancelCurrentRequest;
                                                        yield ReviewPipelineService.forCopilot(
                                                                provider,
                                                                settings.model,
                                                                settings.effort,
                                                                false,
                                                                settings.configDir);
                                                    }
                                                    case ANTIGRAVITY -> {
                                                        var provider =
                                                                new AntigravityService(
                                                                        execution.worktree());
                                                        deepProviderCancel =
                                                                provider::cancelCurrentRequest;
                                                        yield ReviewPipelineService.forAntigravity(
                                                                provider, settings.model);
                                                    }
                                                    case CLAUDE -> {
                                                        var provider =
                                                                new ClaudeService(
                                                                        execution.worktree());
                                                        deepProviderCancel =
                                                                provider::cancelCurrentRequest;
                                                        yield ReviewPipelineService.forClaude(
                                                                provider, settings.model);
                                                    }
                                                };
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
                                    String message =
                                            handleDeepFailure(settings.provider, failure, log);
                                    invokeLater(() -> onError.accept(message));
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
                            switch (settings.provider) {
                                case COPILOT ->
                                        ReviewPipelineService.forCopilot(
                                                copilot,
                                                settings.model,
                                                settings.effort,
                                                resolveReviewInheritMcp(
                                                        settings.inheritMcp,
                                                        settings.forceMcpOnReview),
                                                settings.configDir);
                                case ANTIGRAVITY ->
                                        ReviewPipelineService.forAntigravity(
                                                antigravity, settings.model);
                                case CLAUDE ->
                                        ReviewPipelineService.forClaude(claude, settings.model);
                            };
                    if (StringUtils.isNotBlank(settings.secondReviewerModel)) {
                        boolean copilotProvider = settings.provider == ReviewProvider.COPILOT;
                        pipeline =
                                pipeline.withSecondReviewer(
                                        settings.secondReviewerModel,
                                        copilotProvider ? settings.secondReviewerEffort() : "",
                                        copilotProvider ? settings.configDir : "");
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
            ChatContext context,
            List<ChatMessage> history,
            String userMessage,
            Consumer<String> onChunk,
            Consumer<String> onDone,
            Consumer<String> onError) {
        chat(
                context,
                history,
                userMessage,
                snapshotReviewRuntimeSettings(),
                onChunk,
                onDone,
                onError);
    }

    public void chat(
            ChatContext context,
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
                        switch (settings.provider) {
                            case COPILOT ->
                                    copilot.chat(
                                            context,
                                            history,
                                            userMessage,
                                            settings.effort,
                                            wrappedChunk,
                                            settings.inheritMcp,
                                            settings.configDir);
                            case ANTIGRAVITY ->
                                    antigravity.chat(
                                            context,
                                            history,
                                            userMessage,
                                            settings.model,
                                            wrappedChunk);
                            case CLAUDE -> claude.chat(context, history, userMessage, wrappedChunk);
                        },
                onDone);
    }

    /** Cancels the currently running request on any backend. */
    public void cancelCurrentRequest() {
        cancelDeep();
        // Cancel all — only one has an active process at any time, but reading the provider
        // setting here can race with a settings change so we just send the signal to all.
        claude.cancelCurrentRequest();
        copilot.cancelCurrentRequest();
        antigravity.cancelCurrentRequest();
    }

    /** Cancels only the backend selected by the operation being retired. */
    public void cancelCurrentRequest(ReviewProvider provider) {
        cancelDeep();
        switch (provider == null ? ReviewProvider.CLAUDE : provider) {
            case COPILOT -> copilot.cancelCurrentRequest();
            case ANTIGRAVITY -> antigravity.cancelCurrentRequest();
            case CLAUDE -> claude.cancelCurrentRequest();
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
                settings.getActiveSecondReviewerModel(),
                settings.getReviewSecondReviewerEffort());
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
                        () ->
                                runWithErrorHandling(
                                        provider,
                                        operation,
                                        interruptedMessage,
                                        onError,
                                        IntellijClaudeService::invokeLater,
                                        log,
                                        runnable));
    }

    static void runWithErrorHandling(
            ReviewProvider provider,
            String operation,
            String interruptedMessage,
            Consumer<String> onError,
            Consumer<Runnable> uiExecutor,
            Logger logger,
            CheckedRunnable runnable) {
        String safeOperation =
                StringUtils.defaultIfBlank(LogRedactor.redact(operation), "run request");
        String providerId = providerId(provider);
        try {
            runnable.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn(
                    "Review provider '{}' interrupted while trying to {}",
                    providerId,
                    safeOperation,
                    LogRedactor.sanitize(e));
            uiExecutor.accept(() -> onError.accept(interruptedMessage));
        } catch (Exception e) {
            logger.warn(
                    "Review provider '{}' failed to {}",
                    providerId,
                    safeOperation,
                    LogRedactor.sanitize(e));
            uiExecutor.accept(() -> onError.accept(friendlyMessage(provider, e, operation)));
        }
    }

    static String handleDeepFailure(ReviewProvider provider, Exception failure, Logger logger) {
        String providerId = providerId(provider);
        if (failure instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            logger.warn(
                    "Review provider '{}' interrupted while trying to generate IntelliJ-assisted review",
                    providerId,
                    LogRedactor.sanitize(failure));
            return StringUtils.defaultIfBlank(failure.getMessage(), "Review interrupted.");
        }
        logger.warn(
                "Review provider '{}' failed to generate IntelliJ-assisted review",
                providerId,
                LogRedactor.sanitize(failure));
        if (StringUtils.isBlank(failure.getMessage())) {
            return "Couldn't generate IntelliJ-assisted review ("
                    + LogRedactor.errorType(failure)
                    + "). See idea.log.";
        }
        return failure.getMessage();
    }

    private static String providerId(ReviewProvider provider) {
        return provider == null ? "unknown" : provider.getId();
    }

    static String friendlyMessage(ReviewProvider provider, Exception e, String operation) {
        return UserFacingErrors.forProvider(provider, e, operation);
    }

    static boolean resolveReviewInheritMcp(boolean inheritMcp, boolean forceMcpOnReview) {
        return inheritMcp || forceMcpOnReview;
    }
}
