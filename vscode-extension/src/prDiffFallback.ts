import type { ActivePR, HandlerDependencies, ViewState } from './extensionTypes';
import type { SidecarPrDetailResult, SidecarPrDiffResult } from './sidecarTypes';
import { LocalPrDiffError } from './userFacingError';

export function diffRevisions(metadata: SidecarPrDetailResult) {
    const headSha = metadata.detail?.head?.sha;
    const baseSha = metadata.detail?.baseSha;
    return metadata.status === 'ok' && headSha && baseSha ? { headSha, baseSha } : null;
}

export async function recoverOversizedDiff(
    result: SidecarPrDiffResult,
    deps: HandlerDependencies,
    state: ViewState,
    pr: ActivePR,
    base: string,
    mode: 'review' | 'validation',
    isCurrent: () => boolean,
    detail?: SidecarPrDetailResult,
): Promise<SidecarPrDiffResult> {
    if (result.status !== 'diff_too_large' || !isCurrent()) return result;
    const client = deps.client();
    const metadata = detail ?? await client.getPullRequestDetail(base, pr.owner, pr.repo, pr.number);
    if (!isCurrent()) return result;
    const revisions = diffRevisions(metadata);
    if (!revisions) {
        throw new LocalPrDiffError('Could not verify the base and head commits for a local pull request diff.');
    }
    const worktreeDir = await deps.resolveWorkingDir(state, pr, false);
    if (!isCurrent()) return result;
    const local = await client.getPullRequestDiff(base, pr.owner, pr.repo, pr.number, mode, {
        worktreeDir, ...revisions,
    });
    if (local.status !== 'ok') throw new LocalPrDiffError(local.message);
    return local;
}
