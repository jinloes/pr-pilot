import type { ActivePR, ViewState } from './extensionTypes';
import type { SidecarClient } from './sidecar';

export interface WorktreeDependencies {
    client: () => SidecarClient;
    workingDir: () => string;
    githubBaseUrl: () => string;
    push: (state: ViewState, message: object) => void;
}

function worktreeKey(pr: ActivePR): string {
    return `${pr.owner.toLowerCase()}/${pr.repo.toLowerCase()}#${pr.number}`;
}

function isSameActivePR(state: ViewState, pr: ActivePR): boolean {
    const active = state.activePR;
    return !!active
        && active.number === pr.number
        && active.owner.toLowerCase() === pr.owner.toLowerCase()
        && active.repo.toLowerCase() === pr.repo.toLowerCase();
}

/** Creates the cached worktree operations used by the review and chat handlers. */
export function createWorktreeOperations(deps: WorktreeDependencies): {
    clearWorktree: (state: ViewState) => void;
    resolveWorkingDir: (
        state: ViewState,
        pr: ActivePR,
        emitStatus: boolean,
        bridgePrKey?: string,
    ) => Promise<string>;
} {
    /** Removes the active PR worktree (if any) and clears the cached fields. Non-blocking cleanup. */
    const clearWorktree = (state: ViewState): void => {
        const wt = state.worktreeDir;
        const root = state.gitRoot;
        state.worktreeDir = null;
        state.gitRoot = null;
        state.worktreeKey = null;
        state.worktreeEpoch++;
        state.worktreeCreation = null;
        if (wt && root) {
            void deps.client().removeWorktree(root, wt);
        }
    };

    /**
     * Resolves the working directory for a review/chat against `pr`. Asks the engine for a detached
     * git worktree pinned to the PR's head commit so the CLI reads exactly the code under review —
     * not the branch tip, which can move mid-review — then caches it for reuse. The operation fails
     * closed when a worktree cannot be created, preventing an AI provider from reading an unrelated
     * open checkout.
     */
    const resolveWorkingDir = async (
        state: ViewState,
        pr: ActivePR,
        emitStatus: boolean,
        bridgePrKey?: string,
    ): Promise<string> => {
        const fallback = deps.workingDir();
        const key = worktreeKey(pr);
        if (state.worktreeDir && state.worktreeKey === key) return state.worktreeDir;
        if (!fallback) throw new Error('Open the pull request repository before starting a review or chat.');

        if (state.worktreeCreation?.key === key) return state.worktreeCreation.promise;
        const epoch = state.worktreeEpoch;
        const promise = createWorkingDir(state, pr, fallback, key, epoch, emitStatus, bridgePrKey);
        state.worktreeCreation = { key, promise };
        try {
            return await promise;
        } finally {
            if (state.worktreeCreation?.promise === promise) state.worktreeCreation = null;
        }
    };

    async function createWorkingDir(
        state: ViewState,
        pr: ActivePR,
        fallback: string,
        key: string,
        epoch: number,
        emitStatus: boolean,
        bridgePrKey?: string,
    ): Promise<string> {
        const client = deps.client();
        const gitRoot = await client.findGitRoot(fallback);
        const currentRepo = await client.detectRepo(fallback);
        const sameRepo = currentRepo !== null
            && currentRepo.toLowerCase() === `${pr.owner}/${pr.repo}`.toLowerCase();
        if (!gitRoot || !sameRepo) {
            throw new Error('Open the pull request repository before starting a review or chat.');
        }

        if (emitStatus) {
            deps.push(state, { type: 'reviewGenerating', prKey: bridgePrKey, message: 'Preparing PR branch…' });
        }

        try {
            const detailResult = await client.getPullRequestDetail(
                deps.githubBaseUrl(), pr.owner, pr.repo, pr.number);
            if (detailResult.status !== 'ok' || !detailResult.detail) throw new Error(detailResult.message);
            const head = detailResult.detail.head;
            if (!head?.ref.trim()) throw new Error('Unable to determine the pull request branch.');
            const isFork = !!head.repoFullName && head.repoFullName !== detailResult.detail.baseRepoFullName;

            const created = await client.createWorktree(
                gitRoot,
                pr.number,
                head.ref,
                head.sha ?? '',
                isFork ? head.cloneUrl ?? '' : '',
            );
            if (created.status !== 'created') {
                throw new Error(created.message || 'Unable to create an isolated pull request worktree.');
            }

            if (state.disposed || state.worktreeEpoch !== epoch || !isSameActivePR(state, pr)) {
                void client.removeWorktree(gitRoot, created.worktreeDir);
                throw new Error('The selected pull request changed while its worktree was being prepared.');
            }

            state.worktreeDir = created.worktreeDir;
            state.gitRoot = gitRoot;
            state.worktreeKey = key;
            return created.worktreeDir;
        } catch (err) {
            console.warn(`[pr-pilot] Worktree creation for PR #${pr.number} failed:`,
                err instanceof Error ? err.message : String(err));
            throw err;
        }
    }

    return { clearWorktree, resolveWorkingDir };
}
