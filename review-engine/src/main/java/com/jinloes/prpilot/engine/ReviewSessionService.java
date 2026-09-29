package com.jinloes.prpilot.engine;

import com.jinloes.prpilot.model.ChatMessage;
import com.jinloes.prpilot.model.CiAnnotation;
import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.model.ReviewResult;
import com.jinloes.prpilot.review.CancellationToken;
import com.jinloes.prpilot.review.ClaudeService;
import com.jinloes.prpilot.review.CopilotService;
import com.jinloes.prpilot.review.GitWorktreeService;
import com.jinloes.prpilot.review.RepoGuidelinesReader;
import com.jinloes.prpilot.review.ReviewOutcomeLog;
import com.jinloes.prpilot.review.ReviewPipelineService;
import com.jinloes.prpilot.review.SemanticReviewService;
import com.jinloes.prpilot.review.SemanticWorktreeStore;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;

/**
 * Default {@link ReviewEngineApi} implementation: dispatches to the Claude or Copilot provider
 * service and adapts transport-neutral request records into {@link PRReviewRequest}.
 *
 * <p>Lives in {@code review-engine} rather than the sidecar so every client can reach it the same
 * way — the sidecar wraps it in JSON-RPC for VS Code, while an in-process client (IntelliJ, a
 * future CLI) can call it directly. It has no transport, Spring, or IDE dependencies.
 *
 * <p>Tracks active provider instances by operation ID so cancellation can reach exactly the request
 * that owns it.
 */
public class ReviewSessionService implements ReviewEngineApi {

    private static final int MAX_OPERATION_ID_LENGTH = 128;

    private final OperationRegistry activeOperations = new OperationRegistry();
    private final ReviewOutcomeLog outcomeLog;
    private final GitWorktreeService worktreeService;
    private final SemanticReviewService semanticReviews = new SemanticReviewService();

    public ReviewSessionService() {
        this(new ReviewOutcomeLog(), new GitWorktreeService());
    }

    /** Test seam so outcome logging can be pointed at a temp file. */
    public ReviewSessionService(ReviewOutcomeLog outcomeLog) {
        this(outcomeLog, new GitWorktreeService());
    }

    ReviewSessionService(ReviewOutcomeLog outcomeLog, GitWorktreeService worktreeService) {
        this.outcomeLog = outcomeLog;
        this.worktreeService = worktreeService;
    }

    private static PullRequest toPullRequest(PrParams p) {
        return new PullRequest(
                p.title(),
                p.htmlUrl(),
                p.owner(),
                p.repo(),
                p.number(),
                p.body(),
                p.author(),
                p.createdAt(),
                p.isDraft());
    }

    @Override
    public ReviewResult generate(
            GenerateReviewParams params,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk)
            throws IOException, InterruptedException {
        throwIfInterrupted();
        requireValidGenerateParams(params);
        boolean copilot = "copilot".equals(params.provider());
        PRReviewRequest request = toReviewRequest(params);
        if (params.deepReview() != null) return generateDeep(params, request, onStatus, onChunk);
        if (copilot) {
            CancellationToken cancellationToken = new CancellationToken();
            CopilotService service = new CopilotService(params.projectDir(), cancellationToken);
            ActiveOperation operation =
                    startOperation(
                            params.operationId(), cancellationToken, service::cancelCurrentRequest);
            try {
                return withSecondReviewer(
                                ReviewPipelineService.forCopilot(
                                        service,
                                        params.model(),
                                        params.effort(),
                                        params.inheritMcp(),
                                        params.configDir()),
                                params)
                        .review(
                                request,
                                params.chunkedReview(),
                                params.selfCritique(),
                                params.reviewSupervisorEnabled(),
                                onStatus,
                                onChunk);
            } finally {
                activeOperations.finish(operation);
            }
        }
        CancellationToken cancellationToken = new CancellationToken();
        ClaudeService service = new ClaudeService(params.projectDir(), cancellationToken);
        ActiveOperation operation =
                startOperation(
                        params.operationId(), cancellationToken, service::cancelCurrentRequest);
        try {
            return withSecondReviewer(
                            ReviewPipelineService.forClaude(service, params.model()), params)
                    .review(
                            request,
                            params.chunkedReview(),
                            params.selfCritique(),
                            params.reviewSupervisorEnabled(),
                            onStatus,
                            onChunk);
        } finally {
            activeOperations.finish(operation);
        }
    }

    static PRReviewRequest toReviewRequest(GenerateReviewParams params) {
        return PRReviewRequest.builder(toPullRequest(params.pr()), params.diff())
                .priorReview(params.priorReview())
                .existingReviews(params.existingReviews())
                .repoGuidelines(params.repoGuidelines())
                .focusAreas(params.focusAreas())
                .customInstructions(params.customInstructions())
                .ciStatus(params.ciStatus())
                .commits(params.commits())
                .linkedIssue(params.linkedIssue())
                .repoProfile(params.repoProfile())
                .ciAnnotations(toCiAnnotations(params.ciAnnotations()))
                .baseSha(params.baseSha())
                .build();
    }

    /**
     * Attaches the opt-in second Copilot reviewer for ordinary reviews. The reasoning effort is
     * only meaningful for Copilot, so a Claude primary's effort is not forwarded.
     */
    static ReviewPipelineService withSecondReviewer(
            ReviewPipelineService pipeline, GenerateReviewParams params) {
        if (StringUtils.isBlank(params.secondReviewerModel())) {
            return pipeline;
        }
        return pipeline.withSecondReviewer(
                params.secondReviewerModel(), secondReviewerEffort(params), params.configDir());
    }

    static String secondReviewerEffort(GenerateReviewParams params) {
        return "copilot".equals(params.provider())
                ? params.effort()
                : CopilotService.DEFAULT_REASONING_EFFORT;
    }

    private ReviewResult generateDeep(
            GenerateReviewParams params,
            PRReviewRequest request,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk)
            throws IOException, InterruptedException {
        CancellationToken token = new CancellationToken();
        Thread owner = Thread.currentThread();
        java.util.concurrent.atomic.AtomicReference<Runnable> providerCancel =
                new java.util.concurrent.atomic.AtomicReference<>(() -> {});
        ActiveOperation operation =
                startOperation(
                        params.operationId(),
                        token,
                        () -> {
                            providerCancel.get().run();
                            owner.interrupt();
                        });
        try (var execution =
                semanticReviews.begin(
                        params.deepReview().retainedId(),
                        params.deepReview().server(),
                        params.pr().owner() + "/" + params.pr().repo() + "#" + params.pr().number(),
                        params.operationId(),
                        request)) {
            token.throwIfCancelled();
            // Client projectDir is not authority; the provider only sees the leased worktree.
            String root = execution.worktree();
            onStatus.accept(
                    "IntelliJ-assisted review: streamed output is provisional until final validation.");
            ReviewResult result;
            if ("copilot".equals(params.provider())) {
                CopilotService service = new CopilotService(root, token);
                providerCancel.set(service::cancelCurrentRequest);
                result =
                        ReviewPipelineService.forCopilot(
                                        service,
                                        params.model(),
                                        params.effort(),
                                        false,
                                        params.configDir())
                                .review(
                                        request,
                                        params.chunkedReview(),
                                        params.selfCritique(),
                                        params.reviewSupervisorEnabled(),
                                        onStatus,
                                        onChunk,
                                        execution);
            } else {
                ClaudeService service = new ClaudeService(root, token);
                providerCancel.set(service::cancelCurrentRequest);
                result =
                        ReviewPipelineService.forClaude(service, params.model())
                                .review(
                                        request,
                                        params.chunkedReview(),
                                        params.selfCritique(),
                                        params.reviewSupervisorEnabled(),
                                        onStatus,
                                        onChunk,
                                        execution);
            }
            token.throwIfCancelled();
            execution.validate();
            return result;
        } finally {
            activeOperations.finish(operation);
        }
    }

    @Override
    public SemanticReviewService.Preparation prepareDeepReview(PrepareDeepReviewParams params)
            throws IOException, InterruptedException {
        if (params == null
                || !validOperationId(params.operationId())
                || params.gitRoot() == null
                || params.prNumber() <= 0
                || params.branch() == null
                || params.branch().isBlank()) throw new IOException("Invalid deep preparation");
        CancellationToken token = new CancellationToken();
        Thread owner = Thread.currentThread();
        ActiveOperation operation = startOperation(params.operationId(), token, owner::interrupt);
        try {
            return semanticReviews.prepare(
                    new File(params.gitRoot()),
                    params.prNumber(),
                    params.branch(),
                    params.headSha(),
                    params.forkCloneUrl(),
                    params.prIdentity(),
                    params.diffDigest(),
                    params.operationId());
        } finally {
            activeOperations.finish(operation);
        }
    }

    @Override
    public List<SemanticWorktreeStore.Retained> listDeepReviews() throws IOException {
        return semanticReviews.list();
    }

    @Override
    public WorktreeRemovalResult cleanupDeepReview(CleanupDeepReviewParams params)
            throws IOException {
        if (params == null) throw new IOException("Explicit retained cleanup required");
        return new WorktreeRemovalResult(
                semanticReviews.cleanup(params.retainedId(), params.projectClosed()));
    }

    @Override
    public ChatResult chat(ChatParams params, Consumer<String> onChunk)
            throws IOException, InterruptedException {
        throwIfInterrupted();
        requireValidChatParams(params);
        boolean focused = params.rawPrompt() != null;
        List<ChatMessage> history =
                params.history() == null
                        ? List.of()
                        : params.history().stream()
                                .map(
                                        m ->
                                                new ChatMessage(
                                                        "USER".equalsIgnoreCase(m.role())
                                                                ? ChatMessage.Role.USER
                                                                : ChatMessage.Role.ASSISTANT,
                                                        m.content()))
                                .toList();
        if ("copilot".equals(params.provider())) {
            CancellationToken cancellationToken = new CancellationToken();
            CopilotService service = new CopilotService(params.projectDir(), cancellationToken);
            ActiveOperation operation =
                    startOperation(
                            params.operationId(), cancellationToken, service::cancelCurrentRequest);
            try {
                String content =
                        focused
                                ? service.chatWithPrompt(
                                        params.rawPrompt(),
                                        params.effort(),
                                        onChunk,
                                        params.inheritMcp(),
                                        params.configDir())
                                : service.chat(
                                        params.prContext(),
                                        history,
                                        params.userMessage(),
                                        params.effort(),
                                        onChunk,
                                        params.inheritMcp(),
                                        params.configDir());
                return new ChatResult(content);
            } finally {
                activeOperations.finish(operation);
            }
        }
        CancellationToken cancellationToken = new CancellationToken();
        ClaudeService service = new ClaudeService(params.projectDir(), cancellationToken);
        ActiveOperation operation =
                startOperation(
                        params.operationId(), cancellationToken, service::cancelCurrentRequest);
        try {
            String content =
                    focused
                            ? service.chatWithPrompt(params.rawPrompt(), onChunk)
                            : service.chat(
                                    params.prContext(), history, params.userMessage(), onChunk);
            return new ChatResult(content);
        } finally {
            activeOperations.finish(operation);
        }
    }

    @Override
    public CancelResult cancel(CancelParams params) {
        return activeOperations.cancel(params);
    }

    private ActiveOperation startOperation(
            String operationId, CancellationToken cancellationToken, Runnable cancel)
            throws InterruptedException {
        ActiveOperation operation = activeOperations.start(operationId, cancellationToken, cancel);
        try {
            cancellationToken.throwIfCancelled();
            throwIfInterrupted();
            return operation;
        } catch (InterruptedException exception) {
            cancellationToken.cancel();
            activeOperations.finish(operation);
            throw exception;
        }
    }

    private static void throwIfInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Operation interrupted before provider startup.");
        }
    }

    static boolean validOperationId(String operationId) {
        return operationId != null
                && !operationId.isBlank()
                && operationId.length() <= MAX_OPERATION_ID_LENGTH
                && operationId.chars().noneMatch(Character::isISOControl);
    }

    record ActiveOperation(
            String operationId, CancellationToken cancellationToken, Runnable cancel) {}

    static final class OperationRegistry {
        private final ConcurrentMap<String, ActiveOperation> operations = new ConcurrentHashMap<>();

        ActiveOperation start(String operationId, Runnable cancel) {
            return start(operationId, new CancellationToken(), cancel);
        }

        ActiveOperation start(
                String operationId, CancellationToken cancellationToken, Runnable cancel) {
            if (!validOperationId(operationId)) {
                throw new IllegalArgumentException(
                        "Operation ID is required and must be at most 128 characters.");
            }
            ActiveOperation operation = new ActiveOperation(operationId, cancellationToken, cancel);
            if (operations.putIfAbsent(operationId, operation) != null) {
                throw new IllegalStateException("An operation with this ID is already active.");
            }
            return operation;
        }

        CancelResult cancel(CancelParams params) {
            if (params == null || !validOperationId(params.operationId())) {
                return new CancelResult(false);
            }
            ActiveOperation operation = operations.remove(params.operationId());
            if (operation == null) return new CancelResult(false);
            operation.cancellationToken().cancel();
            operation.cancel().run();
            return new CancelResult(true);
        }

        void finish(ActiveOperation operation) {
            operations.remove(operation.operationId(), operation);
        }
    }

    private static void requireValidGenerateParams(GenerateReviewParams params) {
        if (params == null
                || !validOperationId(params.operationId())
                || params.pr() == null
                || params.provider() == null
                || params.diff() == null) {
            throw new IllegalArgumentException("Invalid review generation parameters.");
        }
    }

    private static void requireValidChatParams(ChatParams params) {
        if (params == null
                || !validOperationId(params.operationId())
                || params.provider() == null
                || (params.rawPrompt() == null) == (params.userMessage() == null)) {
            throw new IllegalArgumentException("Invalid chat parameters.");
        }
    }

    @Override
    public RecordOutcomeResult recordOutcome(RecordOutcomeParams params) {
        if (params == null) return new RecordOutcomeResult(0);
        ReviewOutcomeLog.Metadata metadata =
                new ReviewOutcomeLog.Metadata(
                        ClaudeService.reviewPipelineVersion(params.reviewSupervisorEnabled()),
                        params.provider(),
                        params.model());
        int recorded =
                outcomeLog.record(
                        toLineComments(params.generated()),
                        toLineComments(params.submitted()),
                        metadata);
        return new RecordOutcomeResult(recorded);
    }

    private static List<LineComment> toLineComments(List<OutcomeCommentParam> params) {
        if (params == null) return List.of();
        List<LineComment> comments = new ArrayList<>(params.size());
        for (OutcomeCommentParam param : params) {
            if (param == null) continue;
            LineComment comment =
                    new LineComment(param.file(), param.line(), param.type(), param.body());
            comment.setSeverity(param.severity());
            comment.setConfidence(param.confidence());
            comments.add(comment);
        }
        return comments;
    }

    private static List<CiAnnotation> toCiAnnotations(List<CiAnnotationParam> params) {
        if (params == null) return List.of();
        List<CiAnnotation> annotations = new ArrayList<>(params.size());
        for (CiAnnotationParam param : params) {
            if (param == null) continue;
            annotations.add(
                    new CiAnnotation(param.file(), param.line(), param.level(), param.message()));
        }
        return annotations;
    }

    @Override
    public GuidelinesResult readGuidelines(ReadGuidelinesParams params) {
        if (params == null || StringUtils.isBlank(params.projectDir())) {
            return new GuidelinesResult("");
        }
        // RepoGuidelinesReader adds configured globs to the engine defaults, so no host needs its
        // own copy of the default file list.
        return new GuidelinesResult(
                RepoGuidelinesReader.read(new File(params.projectDir()), params.globs()));
    }

    @Override
    public GitRootResult findGitRoot(String startDir) {
        if (StringUtils.isBlank(startDir)) return new GitRootResult("");
        try {
            File root = worktreeService.findGitRoot(new File(startDir));
            return new GitRootResult(root == null ? "" : root.getAbsolutePath());
        } catch (RuntimeException e) {
            // An uncanonicalizable path is "not a repository" from the caller's perspective; the
            // only consumer decides between worktree and plain checkout, and neither wants a throw.
            return new GitRootResult("");
        }
    }

    @Override
    public WorktreeResult createWorktree(CreateWorktreeParams params) {
        if (params == null
                || StringUtils.isBlank(params.gitRoot())
                || StringUtils.isBlank(params.branch())) {
            return new WorktreeResult("skipped", "", "No branch to check out.");
        }
        File repoDir = new File(params.gitRoot());
        File worktreeDir = worktreeService.newWorktreePath(params.prNumber());
        try {
            if (StringUtils.isNotBlank(params.forkCloneUrl())) {
                worktreeService.createWorktreeFromFork(
                        repoDir,
                        params.forkCloneUrl(),
                        params.branch(),
                        params.headSha(),
                        worktreeDir);
            } else {
                worktreeService.createWorktree(
                        repoDir, params.branch(), params.headSha(), worktreeDir);
            }
            return new WorktreeResult("created", worktreeDir.getAbsolutePath(), "");
        } catch (IOException | IllegalStateException e) {
            // Domain result, not an exception: hosts present an actionable failure rather than
            // granting a provider access to an arbitrary open checkout.
            return new WorktreeResult("failed", "", String.valueOf(e.getMessage()));
        }
    }

    @Override
    public WorktreeRemovalResult removeWorktree(RemoveWorktreeParams params) {
        if (params == null
                || StringUtils.isBlank(params.gitRoot())
                || StringUtils.isBlank(params.worktreeDir())) {
            return new WorktreeRemovalResult(false);
        }
        return new WorktreeRemovalResult(
                worktreeService.removeWorktree(
                        new File(params.gitRoot()), new File(params.worktreeDir())));
    }
}
