package com.jinloes.prpilot.engine;

import com.jinloes.prpilot.model.ReviewResult;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The complete host-neutral AI review capability surface of this engine.
 *
 * <p>Counterpart to {@code GitHubEngineApi} in the {@code github-engine} module; see that type's
 * javadoc for the parity-boundary rules and the coverage enforcement, which apply identically here.
 *
 * <p>The request records live on this interface rather than on an implementation so any client —
 * the sidecar, IntelliJ in-process, or a future CLI — can build a request without depending on a
 * particular host or transport.
 *
 * <p>Do not add overloaded methods — {@link #RPC_METHODS} is keyed by bare method name.
 */
public interface ReviewEngineApi {

    /** Java method name to JSON-RPC wire method name. */
    Map<String, String> RPC_METHODS =
            Map.ofEntries(
                    Map.entry("generate", "reviews/generate"),
                    Map.entry("chat", "reviews/chat"),
                    Map.entry("cancel", "reviews/cancel"),
                    Map.entry("recordOutcome", "reviews/recordOutcome"),
                    Map.entry("readGuidelines", "reviews/readGuidelines"),
                    Map.entry("findGitRoot", "reviews/findGitRoot"),
                    Map.entry("createWorktree", "reviews/createWorktree"),
                    Map.entry("prepareDeepReview", "reviews/prepareDeepReview"),
                    Map.entry("listDeepReviews", "reviews/listDeepReviews"),
                    Map.entry("cleanupDeepReview", "reviews/cleanupDeepReview"),
                    Map.entry("removeWorktree", "reviews/removeWorktree"));

    /** Pull-request identity and metadata needed to build a review prompt. */
    record PrParams(
            String title,
            String htmlUrl,
            String owner,
            String repo,
            int number,
            String body,
            String author,
            String createdAt,
            boolean isDraft) {}

    /** A file-anchored finding CI already reported, used to drop review comments restating it. */
    record CiAnnotationParam(String file, int line, String level, String message) {}

    /**
     * Everything needed for one full review generation.
     *
     * <p>{@code ciStatus}, {@code commits}, {@code linkedIssue}, and {@code repoProfile} are the
     * pre-rendered outputs of the corresponding {@code GitHubEngineApi} context capabilities. They
     * are rendered engine-side rather than per host so no host has to reimplement the formatting,
     * and all four are optional — omitting one drops its prompt section and nothing else.
     *
     * <p>{@code ciAnnotations} is the structured form of {@code ciStatus}: same data, but
     * machine-comparable, so duplicate findings can be dropped deterministically rather than by
     * asking the model nicely.
     *
     * <p>{@code incrementalBaselineSha} is set when {@code diff} holds only the commits pushed
     * since the reviewer's last review at that SHA; the prompt then scopes the review to those
     * changes.
     */
    record GenerateReviewParams(
            String operationId,
            String provider,
            String projectDir,
            String model,
            String effort,
            boolean inheritMcp,
            String configDir,
            boolean selfCritique,
            boolean reviewSupervisorEnabled,
            PrParams pr,
            String diff,
            String priorReview,
            String existingReviews,
            String repoGuidelines,
            String focusAreas,
            String customInstructions,
            String ciStatus,
            String commits,
            String linkedIssue,
            String repoProfile,
            List<CiAnnotationParam> ciAnnotations,
            boolean chunkedReview,
            DeepReviewParams deepReview,
            String baseSha,
            String secondReviewerModel,
            List<String> guidanceGlobs,
            String rulesDirectory,
            String incrementalBaselineSha) {
        public GenerateReviewParams(
                String operationId,
                String provider,
                String projectDir,
                String model,
                String effort,
                boolean inheritMcp,
                String configDir,
                boolean selfCritique,
                boolean reviewSupervisorEnabled,
                PrParams pr,
                String diff,
                String priorReview,
                String existingReviews,
                String repoGuidelines,
                String focusAreas,
                String customInstructions,
                String ciStatus,
                String commits,
                String linkedIssue,
                String repoProfile,
                List<CiAnnotationParam> ciAnnotations,
                boolean chunkedReview,
                DeepReviewParams deepReview,
                String baseSha,
                String secondReviewerModel,
                List<String> guidanceGlobs,
                String rulesDirectory) {
            this(
                    operationId,
                    provider,
                    projectDir,
                    model,
                    effort,
                    inheritMcp,
                    configDir,
                    selfCritique,
                    reviewSupervisorEnabled,
                    pr,
                    diff,
                    priorReview,
                    existingReviews,
                    repoGuidelines,
                    focusAreas,
                    customInstructions,
                    ciStatus,
                    commits,
                    linkedIssue,
                    repoProfile,
                    ciAnnotations,
                    chunkedReview,
                    deepReview,
                    baseSha,
                    secondReviewerModel,
                    guidanceGlobs,
                    rulesDirectory,
                    null);
        }

        public GenerateReviewParams(
                String operationId,
                String provider,
                String projectDir,
                String model,
                String effort,
                boolean inheritMcp,
                String configDir,
                boolean selfCritique,
                boolean reviewSupervisorEnabled,
                PrParams pr,
                String diff,
                String priorReview,
                String existingReviews,
                String repoGuidelines,
                String focusAreas,
                String customInstructions,
                String ciStatus,
                String commits,
                String linkedIssue,
                String repoProfile,
                List<CiAnnotationParam> ciAnnotations,
                boolean chunkedReview,
                DeepReviewParams deepReview,
                String baseSha,
                String secondReviewerModel,
                List<String> guidanceGlobs) {
            this(
                    operationId,
                    provider,
                    projectDir,
                    model,
                    effort,
                    inheritMcp,
                    configDir,
                    selfCritique,
                    reviewSupervisorEnabled,
                    pr,
                    diff,
                    priorReview,
                    existingReviews,
                    repoGuidelines,
                    focusAreas,
                    customInstructions,
                    ciStatus,
                    commits,
                    linkedIssue,
                    repoProfile,
                    ciAnnotations,
                    chunkedReview,
                    deepReview,
                    baseSha,
                    secondReviewerModel,
                    guidanceGlobs,
                    null,
                    null);
        }

        public GenerateReviewParams(
                String operationId,
                String provider,
                String projectDir,
                String model,
                String effort,
                boolean inheritMcp,
                String configDir,
                boolean selfCritique,
                boolean reviewSupervisorEnabled,
                PrParams pr,
                String diff,
                String priorReview,
                String existingReviews,
                String repoGuidelines,
                String focusAreas,
                String customInstructions,
                String ciStatus,
                String commits,
                String linkedIssue,
                String repoProfile,
                List<CiAnnotationParam> ciAnnotations,
                boolean chunkedReview,
                DeepReviewParams deepReview,
                String baseSha,
                String secondReviewerModel) {
            this(
                    operationId,
                    provider,
                    projectDir,
                    model,
                    effort,
                    inheritMcp,
                    configDir,
                    selfCritique,
                    reviewSupervisorEnabled,
                    pr,
                    diff,
                    priorReview,
                    existingReviews,
                    repoGuidelines,
                    focusAreas,
                    customInstructions,
                    ciStatus,
                    commits,
                    linkedIssue,
                    repoProfile,
                    ciAnnotations,
                    chunkedReview,
                    deepReview,
                    baseSha,
                    secondReviewerModel,
                    null,
                    null,
                    null);
        }

        public GenerateReviewParams(
                String operationId,
                String provider,
                String projectDir,
                String model,
                String effort,
                boolean inheritMcp,
                String configDir,
                boolean selfCritique,
                boolean reviewSupervisorEnabled,
                PrParams pr,
                String diff,
                String priorReview,
                String existingReviews,
                String repoGuidelines,
                String focusAreas,
                String customInstructions,
                String ciStatus,
                String commits,
                String linkedIssue,
                String repoProfile,
                List<CiAnnotationParam> ciAnnotations,
                boolean chunkedReview,
                DeepReviewParams deepReview) {
            this(
                    operationId,
                    provider,
                    projectDir,
                    model,
                    effort,
                    inheritMcp,
                    configDir,
                    selfCritique,
                    reviewSupervisorEnabled,
                    pr,
                    diff,
                    priorReview,
                    existingReviews,
                    repoGuidelines,
                    focusAreas,
                    customInstructions,
                    ciStatus,
                    commits,
                    linkedIssue,
                    repoProfile,
                    ciAnnotations,
                    chunkedReview,
                    deepReview,
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        public GenerateReviewParams(
                String operationId,
                String provider,
                String projectDir,
                String model,
                String effort,
                boolean inheritMcp,
                String configDir,
                boolean selfCritique,
                boolean reviewSupervisorEnabled,
                PrParams pr,
                String diff,
                String priorReview,
                String existingReviews,
                String repoGuidelines,
                String focusAreas,
                String customInstructions,
                String ciStatus,
                String commits,
                String linkedIssue,
                String repoProfile,
                List<CiAnnotationParam> ciAnnotations,
                boolean chunkedReview) {
            this(
                    operationId,
                    provider,
                    projectDir,
                    model,
                    effort,
                    inheritMcp,
                    configDir,
                    selfCritique,
                    reviewSupervisorEnabled,
                    pr,
                    diff,
                    priorReview,
                    existingReviews,
                    repoGuidelines,
                    focusAreas,
                    customInstructions,
                    ciStatus,
                    commits,
                    linkedIssue,
                    repoProfile,
                    ciAnnotations,
                    chunkedReview,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }
    }

    record DeepReviewParams(String retainedId, String server) {}

    record PrepareDeepReviewParams(
            String operationId,
            String gitRoot,
            int prNumber,
            String branch,
            String headSha,
            String forkCloneUrl,
            String prIdentity,
            String diffDigest) {}

    record CleanupDeepReviewParams(String retainedId, boolean projectClosed) {}

    default com.jinloes.prpilot.review.SemanticReviewService.Preparation prepareDeepReview(
            PrepareDeepReviewParams params) throws IOException, InterruptedException {
        throw new IOException("Deep review unavailable");
    }

    default List<com.jinloes.prpilot.review.SemanticWorktreeStore.Retained> listDeepReviews()
            throws IOException {
        throw new IOException("Deep review unavailable");
    }

    default WorktreeRemovalResult cleanupDeepReview(CleanupDeepReviewParams params)
            throws IOException {
        throw new IOException("Deep review unavailable");
    }

    /** One prior conversation turn. */
    record ChatMessageParam(String role, String content) {}

    /**
     * Chat request. Exactly one of {@code rawPrompt} (focused question, already fully built) or
     * {@code userMessage} (ordinary chat, wrapped with persona and context) is expected.
     */
    record ChatParams(
            String operationId,
            String provider,
            String projectDir,
            String effort,
            boolean inheritMcp,
            String configDir,
            String prContext,
            List<ChatMessageParam> history,
            String userMessage,
            String rawPrompt) {}

    /** Result of {@link #chat}: the complete assistant response text. */
    record ChatResult(String content) {}

    /** Request to cancel one specific review or chat operation. */
    record CancelParams(String operationId) {}

    /** Result of {@link #cancel}: whether the requested operation was still active. */
    record CancelResult(boolean cancelled) {}

    /**
     * One comment, in either the generated or the submitted set. Carries only what classification
     * and segmentation need — the outcome log persists no comment text.
     */
    record OutcomeCommentParam(
            String file, int line, String type, String body, String severity, String confidence) {}

    /**
     * A completed review, for outcome logging.
     *
     * <p>Deliberately <b>stateless</b>: the caller supplies both sets rather than the engine
     * remembering the generated review between calls. IntelliJ never routes generation through
     * {@link ReviewSessionService} (it calls the provider services in-process), so an engine-held
     * snapshot would populate for VS Code only and silently record nothing on the host with most of
     * the users.
     *
     * <p>{@code promptVersion} is filled in engine-side and is not a parameter — it identifies the
     * prompt this engine build produces, which a host cannot know.
     */
    record RecordOutcomeParams(
            String provider,
            String model,
            boolean reviewSupervisorEnabled,
            List<OutcomeCommentParam> generated,
            List<OutcomeCommentParam> submitted) {}

    /** Result of {@link #recordOutcome}: how many outcome records were written. */
    record RecordOutcomeResult(int recorded) {}

    /**
     * Request for {@link #readGuidelines}.
     *
     * <p>Configured {@code globs} are prioritized and added to the engine defaults. An empty or
     * null list therefore means "use only the engine defaults", so a host never has to carry its
     * own copy of the default file list.
     */
    record ReadGuidelinesParams(String projectDir, List<String> globs) {}

    /** Result of {@link #readGuidelines}: the concatenated, size-capped guidance text. */
    record GuidelinesResult(String guidelines) {}

    /**
     * Result of {@link #findGitRoot}: the repository root, or blank when {@code startDir} is not in
     * one.
     */
    record GitRootResult(String gitRoot) {}

    /**
     * Request for {@link #createWorktree}.
     *
     * <p>A non-blank {@code forkCloneUrl} selects the fork fetch path; otherwise the branch is
     * fetched from {@code origin}. The destination path is chosen by the engine, so no host picks
     * its own temp-directory naming.
     */
    record CreateWorktreeParams(
            String gitRoot, int prNumber, String branch, String headSha, String forkCloneUrl) {}

    /**
     * Result of {@link #createWorktree}.
     *
     * <p>{@code status} is {@code created}, {@code skipped} (nothing to check out — a blank
     * branch), or {@code failed}. Failure is a normal domain result rather than an exception; hosts
     * must surface it and must not substitute their open checkout for the exact PR worktree.
     */
    record WorktreeResult(String status, String worktreeDir, String message) {}

    /** Request for {@link #removeWorktree}. */
    record RemoveWorktreeParams(String gitRoot, String worktreeDir) {}

    /** Result of {@link #removeWorktree}. Cleanup failure is reported, never thrown. */
    record WorktreeRemovalResult(boolean removed) {}

    /**
     * Generates a review. Blocks until the provider CLI completes; callers own threading.
     *
     * @param onStatus receives human-readable progress labels
     * @param onChunk receives streaming output as {@code (kind, text)} where kind is {@code text}
     *     or {@code thinking}
     */
    ReviewResult generate(
            GenerateReviewParams params,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk)
            throws IOException, InterruptedException;

    /** Sends a chat message. Blocks until the provider CLI completes; callers own threading. */
    ChatResult chat(ChatParams params, Consumer<String> onChunk)
            throws IOException, InterruptedException;

    /** Cancels only the active request identified by {@code params.operationId()}. */
    CancelResult cancel(CancelParams params);

    /**
     * Records what the reviewer did with each generated comment. Instrumentation only — never
     * throws, and a failure to write must not affect the submission that triggered it.
     */
    RecordOutcomeResult recordOutcome(RecordOutcomeParams params);

    /**
     * Reads a checkout's review-guidance docs (AGENTS.md, CONTRIBUTING.md, configured globs) as the
     * pre-rendered text that feeds {@link GenerateReviewParams#repoGuidelines()}.
     *
     * <p>Exposed as a capability rather than left to each host because the resolution rules — glob
     * translation, the bounded directory walk, ordering, and the size cap — are prompt-affecting
     * logic. Two hand-mirrored copies had already diverged on the truncation marker.
     */
    GuidelinesResult readGuidelines(ReadGuidelinesParams params);

    /**
     * Walks up from {@code startDir} to the closest ancestor containing a {@code .git} entry.
     *
     * <p>Exposed so no host reimplements the walk. The two hand-mirrored copies had already
     * diverged on what happens when the path cannot be canonicalized.
     */
    GitRootResult findGitRoot(String startDir);

    /**
     * Creates a detached worktree pinned to the PR's head commit, so the agent reads exactly the
     * code the diff was rendered from rather than a branch tip that can move mid-review.
     *
     * <p>Owns the whole policy — destination naming, the fork-versus-origin fetch decision, and the
     * exact-head pinning in {@code GitWorktreeService.pinnedCommitish}. Hosts keep only the
     * lifecycle: caching the returned directory and removing it when the active PR changes.
     */
    WorktreeResult createWorktree(CreateWorktreeParams params);

    /** Removes a worktree created by {@link #createWorktree}. Never throws. */
    WorktreeRemovalResult removeWorktree(RemoveWorktreeParams params);
}
