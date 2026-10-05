import * as path from 'path';
import type { ReviewResult } from './models';
import { parseChatResult, parseReviewResult } from './sidecarProtocol';
import { SidecarGitHubClient } from './sidecarGitHubClient';
import type {
    DeepReviewPreparation,
    RetainedDeepReview,
    SidecarChatParams,
    SidecarGenerateReviewParams,
    SidecarGitHubAuthResult,
    SidecarPrListResult,
    SidecarPrSearchResult,
    SidecarStarredReposResult,
    SidecarExistingReviewsResult,
    SidecarPrDetailResult,
    SidecarPrDiffResult,
    SidecarCheckStatus,
    SidecarCommitContext,
    SidecarDraftReviewResult,
    SidecarDraftReviewMutationResult,
    SidecarCommentInput,
    SidecarWorktreeResult,
    SidecarLineComment,
} from './sidecarTypes';
import type { OutcomeComment } from './sidecarTypes';

const REVIEW_REQUEST_TIMEOUT_MS = 35 * 60 * 1000;
const WORKTREE_REQUEST_TIMEOUT_MS = 5 * 60 * 1000;

export class SidecarReviewClient extends SidecarGitHubClient {
    async generateReview(
        params: SidecarGenerateReviewParams,
        onStatus: (message: string) => void,
        onChunk: (kind: 'text' | 'thinking', text: string) => void,
    ): Promise<ReviewResult> {
        await this.initialize();
        const value = await this.requestRaw('reviews/generate', params, {
            timeoutMs: REVIEW_REQUEST_TIMEOUT_MS,
            notificationHandlers: { onStatus, onChunk },
        });
        return this.parseResult('review generation', parseReviewResult, value);
    }

    /**
     * Answers a chat question via the shared {@code review-engine} services, routed through the
     * sidecar. `onChunk` is driven by `reviews/chatChunk` notifications correlated to this
     * request's id.
     */
    async chatReview(params: SidecarChatParams, onChunk: (text: string) => void): Promise<string> {
        await this.initialize();
        const value = await this.requestRaw('reviews/chat', params, {
            timeoutMs: REVIEW_REQUEST_TIMEOUT_MS,
            notificationHandlers: { onChatChunk: onChunk },
        });
        const result = parseChatResult(value);
        if (result === null) throw this.invalidResponse('chat response');
        return result;
    }

    /** Cancels only the matching review/chat operation on the sidecar. */
    async cancelReview(operationId: string): Promise<boolean> {
        if (!this.child) return false;
        const value = await this.request('reviews/cancel', { operationId });
        if (!value || typeof value !== 'object' || typeof (value as { cancelled?: unknown }).cancelled !== 'boolean') {
            throw this.invalidResponse('cancellation response');
        }
        return (value as { cancelled: boolean }).cancelled;
    }

    /**
     * Records what the reviewer did with each generated comment. Instrumentation: failures are
     * swallowed because the submission this follows has already succeeded, and a metrics write
     * must never surface as a user-visible error.
     */
    async recordReviewOutcome(
        provider: string,
        model: string,
        reviewSupervisorEnabled: boolean,
        generated: OutcomeComment[],
        submitted: OutcomeComment[],
    ): Promise<void> {
        try {
            await this.request('reviews/recordOutcome', {
                provider,
                model,
                reviewSupervisorEnabled,
                generated,
                submitted,
            });
        } catch (err) {
            console.warn('[pr-pilot] Review outcome logging failed:', err instanceof Error ? err.message : String(err));
        }
    }

    /** Returns null only when the shared detector reports that no repository was found. */
    async getRepoProfile(projectDir: string): Promise<string> {
        return this.contextSummary('repo/getProfile', { projectDir });
    }

    /**
     * Reads the repository's review-guidance docs (AGENTS.md, CONTRIBUTING.md, configured globs).
     *
     * Resolution lives in the engine rather than here: glob translation, the bounded directory
     * walk, ordering, and the size cap all change what reaches the prompt, and the previous
     * hand-mirrored TypeScript copy had already drifted from the JVM one. Passing an empty `globs`
     * selects the engine's default file list, so this client carries no copy of it.
     *
     * Best-effort like the context reads above — guidance is additive, so a failure degrades the
     * prompt rather than failing the review.
     */
    async readRepoGuidelines(projectDir: string, globs: string[]): Promise<string> {
        try {
            const value = await this.request('reviews/readGuidelines', { projectDir, globs }) as
                { guidelines?: unknown };
            return typeof value?.guidelines === 'string' ? value.guidelines : '';
        } catch {
            return '';
        }
    }

    /*
     * Worktree lifecycle. The engine owns the whole policy — destination naming, the fork-versus-
     * origin fetch decision, and the head-SHA pinning that keeps the agent reading the code the
     * diff was rendered from. This host keeps only the caching: which directory belongs to the
     * active PR, and when to tear it down. The previous hand-mirrored TypeScript implementation is
     * retired (AGENTS.md guardrail #5).
     */

    /**
     * Returns the git repository root containing `startDir`, or `''` when it is not in one.
     *
     * A blank result is a normal answer, not an error: the only caller uses it to choose between a
     * PR worktree and the user's plain checkout.
     */
    async findGitRoot(startDir: string): Promise<string> {
        try {
            const value = await this.request('reviews/findGitRoot', { startDir }) as { gitRoot?: unknown };
            return typeof value?.gitRoot === 'string' ? value.gitRoot : '';
        } catch {
            return '';
        }
    }

    /**
     * Creates a detached worktree pinned to the PR's head commit.
     *
     * Failure is reported as a `failed` status rather than thrown, matching the engine: callers
     * fall back to the open workspace folder, so a missing worktree degrades review accuracy
     * instead of failing the review. Pass a blank `forkCloneUrl` to fetch from `origin`.
     */
    async createWorktree(
        gitRoot: string,
        prNumber: number,
        branch: string,
        headSha: string,
        forkCloneUrl: string,
    ): Promise<SidecarWorktreeResult> {
        try {
            await this.initialize();
            const value = await this.requestRaw(
                'reviews/createWorktree',
                { gitRoot, prNumber, branch, headSha, forkCloneUrl },
                { timeoutMs: WORKTREE_REQUEST_TIMEOUT_MS },
            ) as { status?: unknown; worktreeDir?: unknown; message?: unknown };
            const status = value?.status === 'created' || value?.status === 'skipped' ? value.status : 'failed';
            const worktreeDir = typeof value?.worktreeDir === 'string' ? value.worktreeDir : '';
            return {
                // A 'created' status with no directory is a contract violation, not a usable
                // worktree; treat it as failure so the caller falls back rather than passing '' as
                // a working directory.
                status: status === 'created' && !worktreeDir ? 'failed' : status,
                worktreeDir,
                message: typeof value?.message === 'string' ? value.message : '',
            };
        } catch (err) {
            return { status: 'failed', worktreeDir: '', message: err instanceof Error ? err.message : String(err) };
        }
    }

    async prepareDeepReview(params: {
        operationId: string; gitRoot: string; prNumber: number; branch: string; headSha: string;
        forkCloneUrl: string; prIdentity: string; diffDigest: string;
    }): Promise<DeepReviewPreparation> {
        await this.initialize();
        const value = await this.requestRaw('reviews/prepareDeepReview', params,
            { timeoutMs: WORKTREE_REQUEST_TIMEOUT_MS }) as Partial<DeepReviewPreparation> | null;
        if (!value || typeof value.retainedId !== 'string' || !/^[0-9a-f-]{36}$/.test(value.retainedId)
            || value.head !== params.headSha || typeof value.worktree !== 'string' || !path.isAbsolute(value.worktree)
            || !Array.isArray(value.servers) || !value.servers.length || value.servers.length > 100
            || !value.servers.every(s => typeof s === 'string' && s.length > 0 && s.length < 256)
            || new Set(value.servers).size !== value.servers.length) {
            throw new Error('Invalid deep preparation response; retained worktrees remain available in cleanup.');
        }
        return value as DeepReviewPreparation;
    }

    async listDeepReviews(): Promise<RetainedDeepReview[]> {
        const value = await this.request('reviews/listDeepReviews', {});
        if (!Array.isArray(value) || value.length > 1000 || !value.every((r: Partial<RetainedDeepReview>) =>
            r && typeof r.id === 'string' && /^[0-9a-f-]{36}$/.test(r.id)
            && typeof r.repository === 'string' && path.isAbsolute(r.repository)
            && typeof r.worktree === 'string' && path.isAbsolute(r.worktree)
            && typeof r.head === 'string' && /^[0-9a-f]{40}(?:[0-9a-f]{24})?$/.test(r.head)
            && typeof r.createdAt === 'number' && Number.isSafeInteger(r.createdAt) && r.createdAt > 0)) {
            throw new Error('Invalid retained review response');
        }
        return value as RetainedDeepReview[];
    }

    async cleanupDeepReview(retainedId: string, projectClosed: boolean): Promise<boolean> {
        const value = await this.request('reviews/cleanupDeepReview', { retainedId, projectClosed }) as { removed?: unknown };
        if (!value || typeof value.removed !== 'boolean') throw new Error('Invalid retained cleanup response');
        return value.removed;
    }

    /** Removes a worktree created by {@link createWorktree}. Cleanup failure is logged, never thrown. */
    async removeWorktree(gitRoot: string, worktreeDir: string): Promise<boolean> {
        try {
            const value = await this.request('reviews/removeWorktree', { gitRoot, worktreeDir }) as { removed?: unknown };
            if (value?.removed === true) return true;
            console.warn(`[pr-pilot] Failed to remove worktree at ${worktreeDir}: engine reported cleanup failure`);
            return false;
        } catch (err) {
            console.warn(`[pr-pilot] Failed to remove worktree at ${worktreeDir}:`,
                err instanceof Error ? err.message : String(err));
            return false;
        }
    }

    /** Saves a pending review without returning a GitHub token to the extension. */
}
