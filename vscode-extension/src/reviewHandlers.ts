import * as claude from './claude';
import * as copilot from './copilot';
import { composeCustomInstructions } from './repositoryInstructions';
import { GitHubOperationError, providerNotInstalledMessage, toUserFacingError } from './userFacingError';
import {
    canPersistDraft,
    invalidateChatAndCancel,
} from './operationCorrelation';
import type { LineComment, ReviewResult } from './models';
import type { OutcomeComment } from './sidecar';
import type { HandlerDependencies, Provider, ViewState } from './extensionTypes';
import type { PreparedDeepReview } from './deepReview';

export interface ReviewHandlerApi {
    handleGenerateReview: (
        state: ViewState,
        msg: Record<string, unknown>,
        deep?: { pending: import('./deepReview').PreparedDeepReview; server: string },
    ) => Promise<void>;
    handleSaveDraft: (state: ViewState, msg: Record<string, unknown>) => Promise<void>;
    handleSubmitReview: (state: ViewState, msg: Record<string, unknown>) => Promise<void>;
    handleDeleteDraft: (state: ViewState, msg: Record<string, unknown>) => Promise<void>;
    handleCancelChat: (state: ViewState, msg: Record<string, unknown>) => Promise<void>;
    handleAskClaude: (state: ViewState, msg: Record<string, unknown>) => Promise<void>;
}

export function createReviewHandlers(deps: HandlerDependencies): ReviewHandlerApi {

async function handleGenerateReview(state: ViewState, msg: Record<string, unknown>,
    deep?: { pending: PreparedDeepReview; server: string }): Promise<void> {
    const number = msg.number as number;
    const owner = msg.owner as string;
    const repo = msg.repo as string;
    if (!number || !owner || !repo) return;
    const key = deps.prKeyFromParts(number, owner, repo);
    if (deps.prKey(state.activePR) !== key) {
        deps.push(state, { type: 'reviewError', prKey: key, message: 'The selected pull request changed. Try again.' });
        return;
    }
    const operationId = msg.operationId as string;
    const previousOperationId = state.activeProviderOperation?.operationId;
    const selectionRevision = state.selectionRevision;
    const generationRevision = ++state.generationRevision;
    const activePr = state.activePR;
    const settings = {
        ...deps.snapshotReviewGenerationSettings(),
        rememberedRepositoryInstructions: deps.rememberedRepositoryInstructions(owner, repo),
    };
    const priorReview = deps.formatPriorReview(state.activeReviewResult);
    const isCurrentGeneration = () =>
        !state.disposed
        && deps.prKey(state.activePR) === key
        && state.selectionRevision === selectionRevision
        && state.generationRevision === generationRevision;
    const providerOperation = {
        kind: 'review' as const,
        revision: generationRevision,
        operationId,
    };
    state.activeProviderOperation = providerOperation;
    if (previousOperationId && previousOperationId !== operationId) {
        await deps.cancelActiveProvider(previousOperationId);
    }

    // Provider preflight: fail fast with actionable guidance instead of a raw CLI spawn error
    // when the configured review provider's binary isn't installed/resolvable.
    const isCopilot = settings.provider === 'copilot';
    if (isCopilot ? !copilot.copilotBinaryAvailable() : !claude.claudeBinaryAvailable()) {
        deps.push(state, {
            type: 'reviewError',
            prKey: key,
            message: providerNotInstalledMessage(isCopilot ? 'copilot' : 'claude'),
        });
        if (state.activeProviderOperation === providerOperation) state.activeProviderOperation = null;
        return;
    }

    deps.push(state, { type: 'reviewGenerating', prKey: key, message: 'Fetching PR data…' });
    try {
        const base = settings.githubBaseUrl;

        const requestedDiff = typeof msg.diff === 'string' && msg.diff.trim() ? msg.diff : undefined;
        let diff = requestedDiff ?? state.activeDiff;
        if (!diff) {
            const result = await deps.client().getPullRequestDiff(base, owner, repo, number, 'review');
            if (!result || result.status !== 'ok' || result.diff === null) {
                throw new GitHubOperationError(
                    result?.status ?? 'invalid_response',
                    result?.message ?? 'Invalid sidecar diff response.',
                );
            }
            diff = result.diff;
            if (!isCurrentGeneration()) return;
            state.activeDiff = diff;
        }
        // The incremental diff only feeds the model: activeDiff and every diff published to the
        // webview stay PR diffs so anchoring, validation and publishing are unchanged.
        let modelDiff = diff;
        let incrementalBaselineSha: string | undefined;
        let reviewScope: { kind: 'incremental'; baselineSha: string } | { kind: 'full'; fallbackReason: string } | undefined;
        if (msg.incremental === true && !deep) {
            const incremental = await deps.client().getIncrementalDiff(base, owner, repo, number);
            if (!incremental || incremental.status !== 'ok') {
                throw new GitHubOperationError(
                    incremental?.status ?? 'invalid_response',
                    incremental?.message ?? 'Invalid sidecar incremental diff response.',
                );
            }
            if (!isCurrentGeneration()) return;
            if (incremental.scope === 'incremental') {
                modelDiff = incremental.diff;
                incrementalBaselineSha = incremental.baselineSha;
                reviewScope = { kind: 'incremental', baselineSha: incremental.baselineSha };
            } else {
                reviewScope = { kind: 'full', fallbackReason: incremental.fallbackReason };
            }
        }
        let resolvedValidationDiff = state.activeValidationDiff;
        let reviewResultPublished = false;
        const validationDiffPromise = resolvedValidationDiff
            ? Promise.resolve(resolvedValidationDiff)
            : deps.client().getPullRequestDiff(base, owner, repo, number, 'validation')
                .then((result) => result?.status === 'ok' && result.diff !== null ? result.diff : diff)
                .catch(() => diff);
        void validationDiffPromise.then((fullDiff) => {
            if (!isCurrentGeneration()) return;
            resolvedValidationDiff = fullDiff;
            state.activeValidationDiff = fullDiff;
            if (reviewResultPublished) {
                deps.push(state, { type: 'validationDiffUpdated', prKey: key, validationDiff: fullDiff });
            }
        });

        const reviewsResult = await deps.client().getExistingReviews(base, owner, repo, number).catch(() => null);
        const existingReviews = reviewsResult?.status === 'ok' ? reviewsResult.summary : '';

        const title = activePr?.title ?? '';
        const body = activePr?.body ?? '';

        const reviewDir = deep?.pending.preparation.worktree ?? await deps.resolveWorkingDir(
            state,
            { number, owner, repo, title, body },
            true,
            key,
        );
        if (!isCurrentGeneration()) return;

        // Phase 1 prompt context. Fetched in parallel and best-effort: every one of these degrades
        // to an omitted prompt section rather than failing the review, so none is awaited
        // individually or allowed to reject. Mirrors WebviewPanel's context block.
        const revisions = await deps.client()
            .getPullRequestDetail(base, owner, repo, number)
            .then((d) => (d.status === 'ok'
                ? { headSha: d.detail?.head?.sha ?? '', baseSha: d.detail?.baseSha ?? '' }
                : { headSha: '', baseSha: '' }))
            .catch(() => ({ headSha: '', baseSha: '' }));
        const headSha = revisions.headSha;
        const commitsPromise = deps.client().getCommits(base, owner, repo, number);
        const [checkStatus, commits, linkedIssue, repoProfile] = await Promise.all([
            headSha
                ? deps.client().getCheckStatus(base, owner, repo, headSha)
                : Promise.resolve({ summary: '', annotations: [] }),
            commitsPromise,
            commitsPromise.then((commitContext) =>
                deps.client().getLinkedIssues(
                    base,
                    owner,
                    repo,
                    body,
                    commitContext.closingIssueNumbers,
                )),
            deps.client().getRepoProfile(reviewDir),
        ]);
        if (!isCurrentGeneration()) return;

        // Per-review overrides from the webview take precedence over the saved settings defaults.
        const guidance = settings.guidance;
        const focusAreas = typeof msg.focusAreas === 'string' && msg.focusAreas.trim()
            ? msg.focusAreas.trim()
            : guidance.focusAreas;
        const customInstructions = composeCustomInstructions(
            `${owner}/${repo}`,
            settings.rememberedRepositoryInstructions,
            typeof msg.customInstructions === 'string' && msg.customInstructions.trim()
                ? msg.customInstructions.trim()
                : guidance.customInstructions,
        );

        // Prompt construction happens sidecar-side (shared review-engine ReviewPrompts via ClaudeService/CopilotService);
        // the extension only supplies raw PR/diff/context fields.
        let result: ReviewResult | null;
        try {
            result = await deps.client().generateReview(
                {
                operationId,
                deepReview: deep ? { retainedId: deep.pending.preparation.retainedId, server: deep.server } : undefined,
                provider: settings.provider,
                projectDir: reviewDir,
                model: settings.model,
                effort: settings.effort,
                inheritMcp: settings.inheritMcp,
                configDir: isCopilot ? settings.configDir : undefined,
                selfCritique: settings.selfCritique,
                reviewSupervisorEnabled: settings.supervisorEnabled,
                secondReviewerModel: settings.secondReviewerModel || undefined,
                baseSha: revisions.baseSha || undefined,
                incrementalBaselineSha,
                chunkedReview: msg.chunkedReview === true && !incrementalBaselineSha,
                pr: {
                    title,
                    // htmlUrl/author/createdAt/isDraft aren't used by ClaudeService/CopilotService's
                    // prompt building (see review-engine ReviewPrompts.buildPrompt) — ActivePR
                    // doesn't track them, so placeholders are supplied to satisfy the sidecar's
                    // PrParams shape without any loss of behavior.
                    htmlUrl: '',
                    owner,
                    repo,
                    number,
                    body,
                    author: '',
                    createdAt: '',
                    isDraft: false,
                },
                diff: modelDiff,
                priorReview,
                existingReviews,
                // Guidance in the PR worktree is authored by the change under review, so the host
                // never reads it; the engine resolves guidance and file history from baseSha.
                repoGuidelines: '',
                guidanceGlobs: guidance.guidanceGlobs,
                rulesDirectory: settings.rulesDirectory || undefined,
                focusAreas,
                customInstructions,
                ciStatus: checkStatus.summary,
                commits: commits.summary,
                linkedIssue,
                repoProfile,
                ciAnnotations: checkStatus.annotations.map((a) => ({
                    file: a.path,
                    line: a.startLine,
                    level: a.level,
                    message: a.message,
                })),
                },
                (status) => {
                    if (isCurrentGeneration()) deps.push(state, { type: 'reviewGenerating', prKey: key, message: status });
                },
                (kind, chunk) => {
                    if (isCurrentGeneration()) deps.push(state, { type: 'reviewChunk', prKey: key, kind, chunk });
                },
            );
        } finally {
            if (state.activeProviderOperation === providerOperation) state.activeProviderOperation = null;
        }

        if (!result) throw new Error('Provider produced no output.');
        if (deep) {
            const fresh = await deps.freshDeepPr(number, owner, repo);
            if (fresh.detail.head?.sha !== deep.pending.preparation.head || fresh.diff !== deep.pending.diff
                || deep.pending.settings !== JSON.stringify(deps.snapshotReviewGenerationSettings())) {
                throw new Error('Deep review identity changed before delivery. Output was not published.');
            }
        }
        if (!isCurrentGeneration()) return;
        state.activeReviewResult = result;
        state.generatedReviews.set(key, {
            result,
            editedResult: null,
            attribution: {
                provider: settings.provider,
                model: settings.model,
                reviewSupervisorEnabled: settings.supervisorEnabled,
            },
        });
        deps.push(state, {
            type: 'reviewResult',
            prKey: key,
            result,
            diff,
            validationDiff: resolvedValidationDiff ?? diff,
            ...(reviewScope ? { reviewScope } : {}),
        });
        reviewResultPublished = true;
    } catch (err) {
        if (isCancellationError(err) || !isCurrentGeneration()) return;
        if (deep) {
            state.deepReview.retry(deep.pending);
            deps.push(state, { type: 'deepReviewPrepared', operationId, prKey: key, ...deep.pending.preparation,
                message: deepReviewError(err) });
            return;
        }
        deps.push(state, { type: 'reviewError', prKey: key, message: toUserFacingError(err, 'generate review') });
    }
}

function deepReviewError(error: unknown): string {
    return (error instanceof Error ? error.message : 'IntelliJ-assisted review could not continue.').slice(0, 4096);
}

async function handleSaveDraft(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    const number = msg.number as number;
    const owner = msg.owner as string;
    const repo = msg.repo as string;
    const saveId = msg.saveId as number;
    const resultFromMsg = msg.result as ReviewResult | undefined;
    const generatedResultFromMsg = msg.generatedResult as ReviewResult | undefined;
    const orphansFromMsg = (msg.orphans as LineComment[] | undefined) ?? [];
    const review = resultFromMsg ?? state.activeReviewResult;
    if (!number || !owner || !repo || !review) return;
    const key = deps.prKeyFromParts(number, owner, repo);
    const activeAtStart = deps.prKey(state.activePR) === key;
    if (!canPersistDraft(activeAtStart, resultFromMsg !== undefined)) {
        deps.push(state, { type: 'draftSaveError', prKey: key, saveId, message: 'The selected pull request changed before the draft could be saved.' });
        return;
    }
    const selectionRevision = state.selectionRevision;

    try {
        await state.draftRecoveryStore.save(key, review, orphansFromMsg);
        const mutation = await deps.client().saveDraftReview(
            deps.githubBaseUrl(), owner, repo, number, review.summary, review.verdict,
            review.lineComments, orphansFromMsg,
        );
        if (mutation.status !== 'ok' || !mutation.reviewId) throw new Error(mutation.message);
        const { reviewId, commentsDropped } = mutation;
        await state.draftRecoveryStore.clear(key);
        const tracked = state.generatedReviews.get(key);
        if (tracked) {
            state.generatedReviews.set(key, {
                ...tracked,
                result: generatedResultFromMsg ?? tracked.result,
                editedResult: review,
            });
        }
        if (deps.prKey(state.activePR) !== key || state.selectionRevision !== selectionRevision) return;
        state.activeReviewResult = review;
        state.pendingReviewId = reviewId;
        state.pendingReviewKey = key;
        deps.push(state, { type: 'draftSaved', prKey: key, saveId, reviewId, commentsDropped });
        deps.push(state, { type: 'prDraftStatusUpdated', number, owner, repo, hasReviewDraft: true });
    } catch (err) {
        if (deps.prKey(state.activePR) !== key || state.selectionRevision !== selectionRevision) return;
        deps.push(state, {
            type: 'draftSaveError',
            prKey: key,
            saveId,
            message: toUserFacingError(err, 'save draft review'),
        });
    }
}

async function handleSubmitReview(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    const number = msg.number as number;
    const owner = msg.owner as string;
    const repo = msg.repo as string;
    const verdict = msg.verdict as string;
    const comment = msg.comment as string ?? '';
    if (!number || !owner || !repo || !verdict) return;
    const key = deps.prKeyFromParts(number, owner, repo);
    // Always notify the webview when there is no usable draft to submit — the webview has
    // already flipped into a "submitting" spinner state before sending this message, so a
    // silent return here leaves the UI stuck forever with no way to recover.
    if (deps.prKey(state.activePR) !== key || state.pendingReviewKey !== key || !state.pendingReviewId) {
        deps.push(state, { type: 'reviewSubmitError', prKey: key, message: 'No pending draft review belongs to the selected pull request.' });
        return;
    }
    const reviewId = state.pendingReviewId;
    const selectionRevision = state.selectionRevision;

    try {
        const mutation = await deps.client().submitReview(
            deps.githubBaseUrl(), owner, repo, number, reviewId, verdict, comment);
        if (mutation.status !== 'ok') throw new Error(mutation.message);
        await state.draftRecoveryStore.clear(key);
        if (deps.prKey(state.activePR) !== key || state.selectionRevision !== selectionRevision) return;
        void recordReviewOutcome(state, key);
        if (state.pendingReviewId === reviewId && state.pendingReviewKey === key) {
            state.pendingReviewId = null;
            state.pendingReviewKey = null;
        }
        deps.push(state, { type: 'reviewSubmitted', prKey: key });
        deps.push(state, { type: 'prDraftStatusUpdated', number, owner, repo, hasReviewDraft: false });
    } catch (err) {
        if (deps.prKey(state.activePR) !== key || state.selectionRevision !== selectionRevision) return;
        deps.push(state, {
            type: 'reviewSubmitError',
            prKey: key,
            message: toUserFacingError(err, 'submit draft review'),
        });
    }
}

/**
 * Logs what the reviewer did with each generated comment. Deliberately not awaited: the review has
 * already been submitted, so instrumentation must not delay the UI or be able to fail the submit.
 * A no-op when no generated review is held — a draft loaded from GitHub in a later session was
 * never generated locally, so there is nothing to compare it against.
 *
 * Mirrors WebviewPanel.recordReviewOutcome.
 */

async function recordReviewOutcome(state: ViewState, key: string): Promise<void> {
    const tracked = state.generatedReviews.get(key);
    if (!tracked) return;
    state.generatedReviews.delete(key);
    const generated = tracked.result;
    const submitted = tracked.editedResult ?? generated;
    const toOutcome = (comments: LineComment[] | undefined): OutcomeComment[] =>
        (comments ?? []).map((c) => ({
            file: c.file,
            line: c.line,
            type: c.type,
            body: c.body,
            severity: c.severity,
            confidence: c.confidence,
        }));
    await deps.client().recordReviewOutcome(
        tracked.attribution.provider,
        tracked.attribution.model,
        tracked.attribution.reviewSupervisorEnabled,
        toOutcome(generated.lineComments),
        toOutcome(submitted.lineComments),
    );
}

async function handleDeleteDraft(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    const number = msg.number as number;
    const owner = msg.owner as string;
    const repo = msg.repo as string;
    if (!number || !owner || !repo) return;
    const key = deps.prKeyFromParts(number, owner, repo);
    // Same reasoning as handleSubmitReview: the webview is already showing a "deleting"
    // spinner, so any early exit here must push an error or the UI hangs forever.
    if (deps.prKey(state.activePR) !== key || state.pendingReviewKey !== key || !state.pendingReviewId) {
        deps.push(state, { type: 'draftDeleteError', prKey: key, message: 'The pending draft does not belong to the selected pull request.' });
        return;
    }
    const reviewId = state.pendingReviewId;
    const selectionRevision = state.selectionRevision;

    try {
        const mutation = await deps.client().deleteDraftReview(
            deps.githubBaseUrl(), owner, repo, number, reviewId);
        if (mutation.status !== 'ok') throw new Error(mutation.message);
        await state.draftRecoveryStore.clear(key);
        state.generatedReviews.delete(key);
        if (deps.prKey(state.activePR) !== key || state.selectionRevision !== selectionRevision) return;
        if (state.pendingReviewId === reviewId && state.pendingReviewKey === key) {
            state.pendingReviewId = null;
            state.pendingReviewKey = null;
        }
        deps.push(state, { type: 'draftDeleted', prKey: key });
        deps.push(state, { type: 'prDraftStatusUpdated', number, owner, repo, hasReviewDraft: false });
    } catch (err) {
        if (deps.prKey(state.activePR) !== key || state.selectionRevision !== selectionRevision) return;
        deps.push(state, {
            type: 'draftDeleteError',
            prKey: key,
            message: toUserFacingError(err, 'delete draft review'),
        });
    }
}

/**
 * Stops the chat answer the webview is waiting for without deleting conversation history. Only the
 * operation that owns the provider is cancelled; the revision bump drops its late output silently.
 */

async function handleCancelChat(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    const ownsProvider = state.activeProviderOperation?.kind === 'chat'
        && state.activeProviderOperation.operationId === msg.operationId;
    if (!ownsProvider) return;
    await invalidateChatAndCancel(state, () => deps.cancelActiveProvider(msg.operationId as string));
}

async function handleAskClaude(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    const context = msg.context as string ?? '';
    const question = msg.question as string ?? '';
    if (!question.trim()) return;

    const key = deps.prKey(state.activePR);
    const activePr = state.activePR;
    const selectionRevision = state.selectionRevision;
    const chatRevision = ++state.chatRevision;
    const history = key ? [...(state.chatHistory.get(key) ?? [])] : [];
    const prContext = buildPrContext(state);
    const c = deps.config();
    const selectedProvider: Provider = c.get<string>('reviewProvider', 'claude') === 'copilot'
        ? 'copilot'
        : 'claude';
    const effort = c.get<string>('reviewEffort', 'high').trim() || 'high';
    const inheritMcp = selectedProvider === 'copilot'
        ? c.get<boolean>('copilotInheritMcp', false)
        : false;
    const configDir = selectedProvider === 'copilot'
        ? c.get<string>('copilotConfigDir', '').trim()
        : undefined;
    const isCurrentChat = () =>
        !state.disposed
        && state.chatRevision === chatRevision
        && state.selectionRevision === selectionRevision
        && deps.prKey(state.activePR) === key;
    const operationId = msg.operationId as string;
    const previousOperationId = state.activeProviderOperation?.operationId;
    const providerOperation = {
        kind: 'chat' as const,
        revision: chatRevision,
        operationId,
    };
    state.activeProviderOperation = providerOperation;

    // Focused chat builds its (small) prompt client-side, matching IntelliJ's
    // IntellijClaudeService.chatFocused; regular chat sends raw context/history and lets the
    // shared review-engine service build the full prompt server-side.
    const focused = context.trim().length > 0;
    const rawPrompt = focused ? claude.buildFocusedChatPrompt(context, question) : undefined;

    try {
        if (previousOperationId && previousOperationId !== operationId) {
            await deps.cancelActiveProvider(previousOperationId);
        }
        // Reuse the PR-branch worktree if one was built for the active PR (e.g. during review) so
        // chat sees the same source.
        const chatDir = activePr
            ? await deps.resolveWorkingDir(state, activePr, false)
            : deps.workingDir();
        if (!isCurrentChat()) return;
        let response: string;
        try {
            response = await deps.client().chatReview(
                {
                operationId,
                provider: selectedProvider,
                projectDir: chatDir,
                effort,
                inheritMcp,
                configDir,
                ...(focused
                    ? { rawPrompt }
                    : { prContext, history, userMessage: question }),
                },
                (chunk) => {
                    if (isCurrentChat()) deps.push(state, { type: 'chatChunk', prKey: key ?? undefined, chunk });
                },
            );
        } finally {
            if (state.activeProviderOperation === providerOperation) state.activeProviderOperation = null;
        }
        if (!isCurrentChat()) return;
        if (key) {
            state.chatHistory.set(key, [
                ...history,
                { role: 'USER', content: question },
                { role: 'ASSISTANT', content: response },
            ]);
        }
        deps.push(state, { type: 'chatResponse', prKey: key ?? undefined, response });
    } catch (err) {
        if (isCancellationError(err) || !isCurrentChat()) return;
        deps.push(state, { type: 'chatError', prKey: key ?? undefined, message: toUserFacingError(err, 'answer chat question') });
    }
}

// ── Utilities ──────────────────────────────────────────────────────────────────

function buildPrContext(state: ViewState): string {
    if (!state.activePR) return '';
    const pr = state.activePR;
    const lines = [
        `PR #${pr.number}: ${pr.title}`,
        `Repo: ${pr.owner}/${pr.repo}`,
    ];
    if (state.activeReviewResult) {
        const r = state.activeReviewResult;
        lines.push('', `Review verdict: ${r.verdict}`);
        if (r.summary) lines.push(`Summary: ${r.summary}`);
    }
    if (state.activeDiff) {
        // Pass the full diff (already capped at 250 KB by getPRDiff) for parity with the IntelliJ
        // host, which also sends the complete diff as chat context.
        lines.push('', 'Diff:', state.activeDiff);
    }
    return lines.join('\n');
}

function errorMessage(err: unknown): string {
    return err instanceof Error ? err.message : String(err);
}

function isCancellationError(err: unknown): boolean {
    return errorMessage(err).toLowerCase().includes('cancel');
}
    return {
        handleGenerateReview,
        handleSaveDraft,
        handleSubmitReview,
        handleDeleteDraft,
        handleCancelChat,
        handleAskClaude,
    };
}
