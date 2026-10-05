import * as vscode from 'vscode';
import { createHash } from 'crypto';
import { hasStaleCommits } from './draftState';
import { classifySetupAuthError } from './authError';
import { GitHubOperationError, toUserFacingError } from './userFacingError';
import {
    normalizeRepositoryInstructions,
    repositoryKey,
    withRepositoryInstructions,
} from './repositoryInstructions';
import {
    cancelForSelection,
    invalidateGenerationAndCancel,
} from './operationCorrelation';
import type { PreparedDeepReview } from './deepReview';
import type { PRSearchScope, ReviewResult } from './models';
import type { HandlerDependencies, ViewState } from './extensionTypes';

export interface PrHandlerApi {
    handleRefreshPRs: (state: ViewState, msg: Record<string, unknown>) => Promise<void>;
    handleSelectPR: (state: ViewState, msg: Record<string, unknown>) => Promise<void>;
    freshDeepPr: HandlerDependencies['freshDeepPr'];
    handleDeepMaintenance: (state: ViewState, msg: Record<string, unknown>) => Promise<void>;
    handleSaveRepositoryInstructions: (
        state: Pick<ViewState, 'disposed'>,
        msg: Record<string, unknown>,
        pushMessage: (message: Record<string, unknown>) => void,
        configuration?: Pick<vscode.WorkspaceConfiguration, 'get' | 'update'>,
    ) => Promise<void>;
    handlePrepareDeepReview: (state: ViewState, msg: Record<string, unknown>) => Promise<void>;
    handleContinueDeepReview: (state: ViewState, msg: Record<string, unknown>) => Promise<void>;
}

export function createPrHandlers(deps: HandlerDependencies): PrHandlerApi {

async function handleRefreshPRs(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    const refreshRevision = ++state.refreshRevision;
    try {
        if (typeof msg.state === 'string') state.prStateFilter = msg.state;
        if (typeof msg.searchScope === 'string') {
            state.searchScope = normalizeSearchScope(msg.searchScope);
        } else if (typeof msg.assignedToMe === 'boolean' && msg.assignedToMe) {
            state.searchScope = 'assigned';
        } else if (typeof msg.reviewRequested === 'boolean' && msg.reviewRequested) {
            state.searchScope = 'reviewRequested';
        }

        const prStateFilter = state.prStateFilter;
        const searchScope = state.searchScope;
        const baseUrl = deps.githubBaseUrl();

        const currentRepo = await deps.client().detectRepo(deps.workingDir() || process.cwd());
        const found = await deps.client().listPullRequests(
            baseUrl, prStateFilter, searchScope, currentRepo ?? undefined);
        if (state.refreshRevision !== refreshRevision || state.disposed) return;
        if (found.status !== 'ok') throw new Error(found.message);
        const prs = found.prs.map((item) => ({ ...item, hasReviewDraft: false })).map((pr) => {
            if (!state.activePR || !state.pendingReviewId) return pr;
            return pr.number === state.activePR.number && pr.owner === state.activePR.owner && pr.repo === state.activePR.repo
                ? { ...pr, hasReviewDraft: true }
                : pr;
        });
        prs.sort((a, b) => b.createdAt.localeCompare(a.createdAt));
        const readiness = await deps.providerSetupReadiness();
        if (!readiness.available) {
            deps.push(state, {
                type: 'setupRequired',
                reason: 'provider_not_installed',
                detail: readiness.detail,
                providerReadiness: readiness,
            });
            return;
        }
        if (readiness.authenticationStatus === 'unavailable') {
            deps.push(state, {
                type: 'setupRequired',
                reason: 'provider_not_authenticated',
                detail: readiness.detail,
                providerReadiness: readiness,
            });
            return;
        }
        deps.push(state, {
            type: 'prListLoaded',
            prs,
            defaultRepo: currentRepo ?? undefined,
            listStatus: {
                searchScope,
                currentRepo: currentRepo ?? undefined,
                resultLimit: found.resultLimit,
                limited: found.limited,
                reviewStatusAvailable: found.reviewStatusAvailable,
            },
            providerReadiness: readiness,
            intellijAssistedEnabled: deps.intellijAssistedEnabled(),
        });
    } catch (err) {
        if (state.refreshRevision !== refreshRevision || state.disposed) return;
        const reason = classifySetupAuthError(err);
        const detail = reason === 'gh_not_installed'
            ? "The 'gh' CLI was not found. Install it from https://cli.github.com, then run 'gh auth login' in a terminal and click Refresh."
            : reason === 'gh_not_authenticated'
                ? "Run 'gh auth login' in a terminal to authenticate, then click Refresh."
                : toUserFacingError(err, 'load pull requests');
        deps.push(state, {
            type: 'setupRequired',
            reason,
            detail,
            providerReadiness: deps.providerReadiness(),
        });
    }
}


function normalizeSearchScope(value: string): PRSearchScope {
    if (value === 'authored' || value === 'assigned' || value === 'reviewRequested') return value;
    return 'currentRepo';
}

async function handleSelectPR(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    const number = msg.number as number;
    const owner = msg.owner as string;
    const repo = msg.repo as string;
    if (!number || !owner || !repo) return;
    const key = deps.prKeyFromParts(number, owner, repo);
    const selectionRevision = ++state.selectionRevision;
    const title = typeof msg.title === 'string' ? msg.title : '';
    const body = typeof msg.body === 'string' ? msg.body : '';

    state.generationRevision++;
    state.chatRevision++;
    const operationId = state.activeProviderOperation?.operationId;
    if (!await cancelForSelection(
        state,
        selectionRevision,
        operationId ? () => deps.cancelActiveProvider(operationId) : () => Promise.resolve(),
    )) return;
    deps.clearWorktree(state);
    state.activePR = { number, owner, repo, title, body };
    state.activeDiff = '';
    state.activeValidationDiff = '';
    state.activeReviewResult = null;
    state.pendingReviewId = null;
    state.pendingReviewKey = null;
    deps.push(state, { type: 'draftLoading', prKey: key });
    try {
        const base = deps.githubBaseUrl();
        const readiness = deps.providerReadiness();

        const [diffResult, detailResult, draftResult] = await Promise.all([
            deps.client().getPullRequestDiff(base, owner, repo, number, 'review'),
            deps.client().getPullRequestDetail(base, owner, repo, number),
            deps.client().getDraftReview(base, owner, repo, number),
        ]);
        if (diffResult.status !== 'ok' || diffResult.diff === null) {
            throw new GitHubOperationError(diffResult.status, diffResult.message);
        }
        if (detailResult.status !== 'ok' || !detailResult.detail) throw new Error(detailResult.message);
        if (draftResult.status !== 'ok' && draftResult.status !== 'none') throw new Error(draftResult.message);
        const diff = diffResult.diff;
        const detail = detailResult.detail;
        const remoteDraft = draftResult.status === 'ok' && draftResult.review ? {
            id: draftResult.id ?? '',
            commitId: draftResult.commitId ?? '',
            result: draftResult.review as ReviewResult,
            importedFromGitHub: draftResult.review.importedFromGitHub,
        } : null;
        const recovery = state.draftRecoveryStore.get(key);
        const draft = recovery ? {
            id: remoteDraft?.id ?? '',
            commitId: remoteDraft?.commitId ?? '',
            result: recovery.result,
            importedFromGitHub: false,
            recoveryPending: true,
        } : remoteDraft ? { ...remoteDraft, recoveryPending: false } : null;
        const validationDiff = diff;

        if (deps.prKey(state.activePR) !== key || state.selectionRevision !== selectionRevision) return;

        state.activePR = {
            number,
            owner,
            repo,
            title: detail.title ?? title,
            body: detail.body ?? body,
        };
        state.activeDiff = diff;
        state.activeValidationDiff = validationDiff;
        state.activeReviewResult = draft?.result ?? null;
        state.pendingReviewId = draft?.id || null;
        state.pendingReviewKey = draft?.id ? key : null;

        if (detail.merged) {
            await state.draftRecoveryStore.clear(key);
            deps.push(state, { type: 'prDraftStatusUpdated', number, owner, repo, hasReviewDraft: false });
            deps.push(state, { type: 'draftLoaded', prKey: key, prState: 'MERGED', diff, validationDiff, providerReadiness: readiness,
                intellijAssistedEnabled: deps.intellijAssistedEnabled(),
                repositoryInstructions: deps.rememberedRepositoryInstructions(owner, repo) });
        } else if (draft) {
            const staleCommits = hasStaleCommits(draft.commitId, detail.head?.sha ?? '');
            deps.push(state, { type: 'prDraftStatusUpdated', number, owner, repo, hasReviewDraft: true });
            deps.push(state, {
                type: 'draftLoaded',
                prKey: key,
                prState: 'DRAFT_PRESENT',
                reviewId: draft.id,
                result: draft.result,
                diff,
                validationDiff,
                staleCommits,
                importedFromGitHub: draft.importedFromGitHub,
                recoveryPending: draft.recoveryPending,
                providerReadiness: readiness,
                intellijAssistedEnabled: deps.intellijAssistedEnabled(),
                repositoryInstructions: deps.rememberedRepositoryInstructions(owner, repo),
            });
        } else {
            deps.push(state, { type: 'prDraftStatusUpdated', number, owner, repo, hasReviewDraft: false });
            deps.push(state, { type: 'draftLoaded', prKey: key, prState: 'NO_DRAFT', diff, validationDiff, providerReadiness: readiness,
                intellijAssistedEnabled: deps.intellijAssistedEnabled(),
                repositoryInstructions: deps.rememberedRepositoryInstructions(owner, repo) });
        }

        // Comment-position validation benefits from an untruncated diff, but it must not block the
        // draft status UI on a large or slow response. The bounded review diff above is sufficient
        // until this optional fetch completes.
        void deps.client().getPullRequestDiff(base, owner, repo, number, 'validation')
            .then((fullResult) => {
                if (fullResult.status !== 'ok' || fullResult.diff === null) return;
                if (deps.prKey(state.activePR) === key && state.selectionRevision === selectionRevision) {
                    state.activeValidationDiff = fullResult.diff || diff;
                    deps.push(state, {
                        type: 'validationDiffUpdated',
                        prKey: key,
                        validationDiff: state.activeValidationDiff,
                    });
                }
            })
            .catch((err) => {
                console.warn(`[pr-pilot] Full validation diff for PR #${number} failed; using review diff:`,
                    err instanceof Error ? err.message : String(err));
            });
    } catch (err) {
        if (deps.prKey(state.activePR) !== key || state.selectionRevision !== selectionRevision) return;
        deps.push(state, {
            type: 'draftLoaded',
            prKey: key,
            prState: 'NO_DRAFT',
            status: toUserFacingError(err, 'load PR details'),
            providerReadiness: deps.providerReadiness(),
            intellijAssistedEnabled: deps.intellijAssistedEnabled(),
            repositoryInstructions: deps.rememberedRepositoryInstructions(owner, repo),
        });
    }
}


async function freshDeepPr(number: number, owner: string, repo: string) {
    const first = await deps.client().getPullRequestDetail(deps.githubBaseUrl(), owner, repo, number);
    const diff = await deps.client().getPullRequestDiff(deps.githubBaseUrl(), owner, repo, number, 'validation');
    const last = await deps.client().getPullRequestDetail(deps.githubBaseUrl(), owner, repo, number);
    if (first.status !== 'ok' || last.status !== 'ok' || !first.detail?.head?.sha
        || first.detail.head.sha !== last.detail?.head?.sha
        || diff.status !== 'ok' || !diff.diff) {
        throw new Error('Cannot bind a fresh PR head and diff. Prepare again.');
    }
    return { detail: last.detail, diff: diff.diff };
}

function deepReviewError(error: unknown): string {
    return (error instanceof Error ? error.message : 'IntelliJ-assisted review could not continue.').slice(0, 4096);
}

async function handleDeepMaintenance(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    if (state.disposed) return;
    try {
        if (msg.type === 'cleanupDeepReview') {
            await deps.client().cleanupDeepReview(msg.retainedId as string, msg.projectClosed === true);
        }
        deps.push(state, { type: 'retainedDeepReviews', operationId: msg.operationId,
            retained: await deps.client().listDeepReviews() });
    } catch (error) {
        deps.push(state, { type: 'deepReviewMaintenanceError', operationId: msg.operationId,
            message: deepReviewError(error) });
    }
}

async function handleSaveRepositoryInstructions(
    state: Pick<ViewState, 'disposed'>,
    msg: Record<string, unknown>,
    pushMessage: (message: Record<string, unknown>) => void,
    configuration: Pick<vscode.WorkspaceConfiguration, 'get' | 'update'> = deps.config(),
): Promise<void> {
    const number = msg.number as number;
    const owner = msg.owner as string;
    const repo = msg.repo as string;
    const key = deps.prKeyFromParts(number, owner, repo);
    const repository = repositoryKey(owner, repo);
    const fail = (message: string) =>
        pushMessage({ type: 'repositoryInstructionsSaveError', prKey: key, message });
    if (!repository) {
        fail('This repository name cannot be remembered.');
        return;
    }
    const current = normalizeRepositoryInstructions(configuration.get<unknown>('repositoryReviewInstructions', {}));
    const next = withRepositoryInstructions(
        current, repository, typeof msg.instructions === 'string' ? msg.instructions : '');
    if (!next) {
        fail('Repository instructions are limited to 10,000 characters and 200 repositories.');
        return;
    }
    try {
        await configuration.update('repositoryReviewInstructions', next, vscode.ConfigurationTarget.Global);
    } catch (err) {
        console.warn('[pr-pilot] saving repository instructions failed:', err instanceof Error ? err.message : String(err));
        if (!state.disposed) fail('Could not save PR Pilot settings. Try again.');
        return;
    }
    if (!state.disposed) {
        pushMessage({ type: 'repositoryInstructionsSaved', prKey: key, instructions: next[repository] ?? '' });
    }
}

const INTELLIJ_ASSISTED_DISABLED_ERROR =
    'IntelliJ-assisted review is disabled; enable it in PR Pilot settings (experimental)';

async function handlePrepareDeepReview(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    const number = msg.number as number;
    const owner = msg.owner as string;
    const repo = msg.repo as string;
    const key = deps.prKeyFromParts(number, owner, repo);
    if (!deps.intellijAssistedEnabled()) {
        // Reject rather than downgrade: an ordinary review must be an explicit user choice.
        deps.push(state, { type: 'reviewError', prKey: key, message: INTELLIJ_ASSISTED_DISABLED_ERROR });
        return;
    }
    const revision = state.deepReview.start(msg.operationId as string);
    const selectionRevision = state.selectionRevision;
    const configuration = JSON.stringify(deps.snapshotReviewGenerationSettings());
    const current = () => !state.disposed && deps.prKey(state.activePR) === key
        && state.selectionRevision === selectionRevision && state.deepReview.isCurrent(revision);
    try {
        const previousOperationId = state.activeProviderOperation?.operationId;
        await invalidateGenerationAndCancel(state, () => previousOperationId
            ? deps.cancelActiveProvider(previousOperationId) : Promise.resolve());
        const root = await deps.client().findGitRoot(deps.workingDir());
        const repository = await deps.client().detectRepo(deps.workingDir());
        if (!root || repository?.toLowerCase() !== `${owner}/${repo}`.toLowerCase()) {
            throw new Error('Open the pull request repository before preparing a deep review.');
        }
        const fresh = await deps.freshDeepPr(number, owner, repo);
        if (!current()) return;
        const head = fresh.detail.head!;
        const preparation = await deps.client().prepareDeepReview({
            operationId: msg.operationId as string, gitRoot: root, prNumber: number,
            branch: head.ref, headSha: head.sha,
            forkCloneUrl: head.repoFullName !== fresh.detail.baseRepoFullName ? head.cloneUrl ?? '' : '',
            prIdentity: `${owner}/${repo}#${number}`,
            diffDigest: createHash('sha256').update(fresh.diff).digest('hex'),
        });
        const pending: PreparedDeepReview = { preparation, diff: fresh.diff, prKey: key,
            settings: configuration, options: { ...msg }, selectionRevision };
        if (!current() || configuration !== JSON.stringify(deps.snapshotReviewGenerationSettings())
            || !state.deepReview.install(revision, pending)) return;
        deps.push(state, { type: 'deepReviewPrepared', prKey: key, operationId: msg.operationId, ...preparation,
            message: 'Open this exact worktree in IntelliJ. Enable MCP, import Gradle, then Continue. The first Continue may arm tracking and require one manual sync.' });
    } catch (error) {
        if (current()) deps.push(state, { type: 'reviewError', prKey: key,
            message: deepReviewError(error) });
    }
}

async function handleContinueDeepReview(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    const key = deps.prKeyFromParts(msg.number as number, msg.owner as string, msg.repo as string);
    const pendingAtStart = state.deepReview.peek();
    if (state.disposed || deps.prKey(state.activePR) !== key || !pendingAtStart
        || pendingAtStart.selectionRevision !== state.selectionRevision
        || pendingAtStart.preparation.retainedId !== msg.retainedId
        || state.deepReview.hasConsumed(msg.operationId as string)) return;
    const generationRevision = state.generationRevision;
    const current = () => !state.disposed && deps.prKey(state.activePR) === key
        && state.selectionRevision === pendingAtStart.selectionRevision
        && state.generationRevision === generationRevision
        && (state.deepReview.peek() === pendingAtStart
            || state.deepReview.isOperationCurrent(msg.operationId as string));
    let refreshing = false;
    try {
        const pending = state.deepReview.consume(msg.retainedId, msg.operationId as string, key,
            JSON.stringify(deps.snapshotReviewGenerationSettings()), state.selectionRevision, msg.server as string);
        refreshing = true;
        const fresh = await deps.freshDeepPr(msg.number as number, msg.owner as string, msg.repo as string);
        refreshing = false;
        if (!current()) return;
        if (fresh.detail.head?.sha !== pending.preparation.head || fresh.diff !== pending.diff
            || pending.settings !== JSON.stringify(deps.snapshotReviewGenerationSettings())) {
            throw new Error('The PR head or diff changed. Prepare a new deep review; the old tree is retained.');
        }
        await deps.handleGenerateReview(state, { ...pending.options, operationId: msg.operationId, diff: pending.diff },
            { pending, server: msg.server as string });
    } catch (error) {
        if (current()) {
            if (refreshing && pendingAtStart.settings === JSON.stringify(deps.snapshotReviewGenerationSettings())) {
                state.deepReview.retry(pendingAtStart);
            }
            deps.push(state, { type: 'reviewError', prKey: key, message: deepReviewError(error) });
        }
    }
}
    return {
        handleRefreshPRs,
        handleSelectPR,
        freshDeepPr,
        handleDeepMaintenance,
        handleSaveRepositoryInstructions,
        handlePrepareDeepReview,
        handleContinueDeepReview,
    };
}
