import { createHash } from 'crypto';
import * as fs from 'fs';
import * as path from 'path';
import type * as vscodeTypes from 'vscode';
import { isValidRepoRelativeFile } from './bridgeValidation';
import type { ActivePR, ViewState } from './extensionTypes';

export const WORKTREE_PREVIEW_SCHEME = 'pr-pilot-worktree';

export interface ReadOnlyWorktreeUriParams {
    filePath: string;
    relativeFile: string;
    owner: string;
    repo: string;
    prNumber: number;
}

export function buildReadOnlyWorktreeUriKey(
    owner: string,
    repo: string,
    prNumber: number,
    relativeFile: string,
): string {
    return `/${owner.toLowerCase()}/${repo.toLowerCase()}/PR-${prNumber}/${relativeFile}`;
}

export function buildReadOnlyWorktreeQuery(filePath: string): string {
    const digest = createHash('sha256').update(filePath).digest('hex').slice(0, 12);
    return `wt=${digest}`;
}

export interface ResolvedEditorTarget {
    filePath: string;
    zeroBasedLine: number;
    temporaryWorktree: boolean;
}

export interface ResolveOpenFileOptions {
    workspaceGitRoot: string;
    getWorktreeDir: () => Promise<string | null>;
    relativeFile: string;
    line?: number;
}

async function isInsideRealRoot(realRoot: string, candidatePath: string): Promise<string | null> {
    let stat: fs.Stats;
    try {
        stat = await fs.promises.stat(candidatePath);
    } catch {
        return null;
    }
    if (!stat.isFile()) return null;
    let realCandidate: string;
    try {
        realCandidate = await fs.promises.realpath(candidatePath);
    } catch {
        return null;
    }
    const relative = path.relative(realRoot, realCandidate);
    if (!relative || relative === '..' || relative.startsWith(`..${path.sep}`) || path.isAbsolute(relative)) {
        return null;
    }
    let realStat: fs.Stats;
    try {
        realStat = await fs.promises.lstat(realCandidate);
    } catch {
        return null;
    }
    return realStat.isFile() ? realCandidate : null;
}

/**
 * Resolves `relativeFile` under `workspace-checkout-first` semantics: prefers the open matching
 * workspace checkout (`temporaryWorktree: false`) so reviewers get full IDE language features and
 * editable files, falling back to the pinned PR-head worktree (`temporaryWorktree: true`) when a
 * file only exists in the PR branch. Both roots enforce `fs.promises.realpath` confinement.
 */
export async function resolveOpenFileTarget({
    workspaceGitRoot,
    getWorktreeDir,
    relativeFile,
    line,
}: ResolveOpenFileOptions): Promise<ResolvedEditorTarget | null> {
    if (!isValidRepoRelativeFile(relativeFile)) {
        return null;
    }
    const clampedOneBased = line !== undefined && Number.isFinite(line)
        ? Math.min(10_000_000, Math.max(1, Math.trunc(line)))
        : 1;
    const zeroBasedLine = clampedOneBased - 1;

    if (workspaceGitRoot) {
        try {
            const realWorkspaceRoot = await fs.promises.realpath(workspaceGitRoot);
            const workspaceCandidate = path.resolve(realWorkspaceRoot, ...relativeFile.split('/'));
            const confinedWorkspaceFile = await isInsideRealRoot(realWorkspaceRoot, workspaceCandidate);
            if (confinedWorkspaceFile) {
                return { filePath: confinedWorkspaceFile, zeroBasedLine, temporaryWorktree: false };
            }
        } catch {
            // Fall through to PR worktree fallback.
        }
    }

    const worktreeDir = await getWorktreeDir();
    if (worktreeDir) {
        try {
            const realWorktreeRoot = await fs.promises.realpath(worktreeDir);
            const worktreeCandidate = path.resolve(realWorktreeRoot, ...relativeFile.split('/'));
            const confinedWorktreeFile = await isInsideRealRoot(realWorktreeRoot, worktreeCandidate);
            if (confinedWorktreeFile) {
                return { filePath: confinedWorktreeFile, zeroBasedLine, temporaryWorktree: true };
            }
        } catch {
            return null;
        }
    }
    return null;
}

export interface OpenFileHostDependencies {
    vscode: typeof vscodeTypes;
    workingDir: () => string;
    findGitRoot: (startDir: string) => Promise<string>;
    detectRepo: (startDir: string) => Promise<string | null>;
    resolveWorkingDir: (state: ViewState, pr: ActivePR, emitStatus: boolean) => Promise<string>;
    toReadOnlyWorktreeUri?: (params: ReadOnlyWorktreeUriParams) => vscodeTypes.Uri;
    openReviewedBlob?: (gitRoot: string, commit: string, file: string, line: number, isCurrent: () => boolean) => Promise<void>;
}

function matchesActivePr(active: ActivePR | null, number: number, owner: string, repo: string): active is ActivePR {
    return !!active
        && active.number === number
        && active.owner.toLowerCase() === owner.toLowerCase()
        && active.repo.toLowerCase() === repo.toLowerCase();
}

export async function handleOpenFileInEditor(
    state: ViewState,
    msg: { number: number; owner: string; repo: string; file: string; line?: number; reviewedFileToken?: string },
    deps: OpenFileHostDependencies,
): Promise<void> {
    const pr = state.activePR;
    if (state.disposed || !matchesActivePr(pr, msg.number, msg.owner, msg.repo)) {
        return;
    }
    const expectedRevision = state.selectionRevision;
    const isStillSelected = () =>
        !state.disposed
        && state.selectionRevision === expectedRevision
        && matchesActivePr(state.activePR, msg.number, msg.owner, msg.repo);

    const workspaceDir = deps.workingDir();
    let gitRoot = '';
    let detectedRepo: string | null = null;
    if (workspaceDir) {
        try {
            [gitRoot, detectedRepo] = await Promise.all([
                deps.findGitRoot(workspaceDir),
                deps.detectRepo(workspaceDir),
            ]);
        } catch {
            if (isStillSelected()) {
                void deps.vscode.window.showInformationMessage(
                    `Could not open ${msg.file} in the editor.`,
                );
            }
            return;
        }
    }
    if (!isStillSelected()) {
        return;
    }
    const sameRepo = Boolean(gitRoot)
        && detectedRepo !== null
        && detectedRepo.toLowerCase() === `${msg.owner}/${msg.repo}`.toLowerCase();
    if (!sameRepo) {
        void deps.vscode.window.showInformationMessage(
            `Open ${msg.owner}/${msg.repo} in VS Code to jump to pull request files in the editor.`,
        );
        return;
    }

    if (msg.line !== undefined) {
        const reviewed = state.reviewedFile;
        if (!reviewed || reviewed.token !== msg.reviewedFileToken || !deps.openReviewedBlob) {
            void deps.vscode.window.showInformationMessage('The exact reviewed revision is unavailable for this finding.');
            return;
        }
        const isCurrent = () => isStillSelected() && state.reviewedFile === reviewed;
        try {
            await deps.openReviewedBlob(gitRoot, reviewed.commit, msg.file, msg.line, isCurrent);
        } catch (error) {
            if (isCurrent()) void deps.vscode.window.showInformationMessage(
                error instanceof Error ? error.message : 'Could not open the reviewed file.',
            );
        }
        return;
    }

    let target: ResolvedEditorTarget | null;
    try {
        target = await resolveOpenFileTarget({
            workspaceGitRoot: gitRoot,
            getWorktreeDir: async () => {
                try {
                    return await deps.resolveWorkingDir(state, pr, false);
                } catch {
                    return null;
                }
            },
            relativeFile: msg.file,
            line: msg.line,
        });
    } catch {
        if (isStillSelected()) {
            void deps.vscode.window.showInformationMessage(
                `Could not open ${msg.file} in the editor.`,
            );
        }
        return;
    }
    if (!isStillSelected()) {
        return;
    }
    if (!target) {
        void deps.vscode.window.showInformationMessage(
            `Could not find ${msg.file} in the current checkout or PR branch.`,
        );
        return;
    }

    const uri = target.temporaryWorktree && deps.toReadOnlyWorktreeUri
        ? deps.toReadOnlyWorktreeUri({
            filePath: target.filePath,
            relativeFile: msg.file,
            owner: msg.owner,
            repo: msg.repo,
            prNumber: msg.number,
        })
        : deps.vscode.Uri.file(target.filePath);
    const range = new deps.vscode.Range(target.zeroBasedLine, 0, target.zeroBasedLine, 0);
    try {
        await deps.vscode.window.showTextDocument(uri, {
            viewColumn: deps.vscode.ViewColumn.Beside,
            preview: true,
            selection: range,
        });
    } catch {
        void deps.vscode.window.showInformationMessage(
            `Could not open ${msg.file} in the editor.`,
        );
    }
}
