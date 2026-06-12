import { execFile } from 'child_process';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';

/**
 * Manages temporary git worktrees for PR branch reviews.
 *
 * A worktree lets the review CLI read source files at the PR branch state rather than the user's
 * currently checked-out branch, improving review accuracy for type lookups and cross-file
 * references. The shared git object store means no re-clone is needed — only the working tree
 * files are written for the new worktree.
 *
 * Mirrors core/src/jvmMain/.../GitWorktreeService.kt. Keep the two in sync (see AGENTS.md).
 */

/**
 * Walks up from `startDir` to find the git repository root — the closest ancestor (inclusive)
 * that contains a `.git` entry (directory or file). Returns null if no git root is found.
 */
export function findGitRoot(startDir: string): string | null {
    let dir: string;
    try {
        dir = fs.realpathSync(startDir);
    } catch {
        dir = path.resolve(startDir);
    }
    while (true) {
        if (fs.existsSync(path.join(dir, '.git'))) return dir;
        const parent = path.dirname(dir);
        if (parent === dir) return null;
        dir = parent;
    }
}

/**
 * Creates a git worktree at `worktreeDir` checked out to `origin/<branch>`.
 *
 * Runs `git fetch origin <branch>` first so the ref is current, then
 * `git worktree add --detach <worktreeDir> origin/<branch>`.
 *
 * @throws Error if a git command fails or times out
 */
export async function createWorktree(repoDir: string, branch: string, worktreeDir: string): Promise<void> {
    await runGit(repoDir, 60, ['fetch', 'origin', branch]);
    await runGit(repoDir, 30, ['worktree', 'add', '--detach', worktreeDir, `origin/${branch}`]);
}

/**
 * Creates a git worktree at `worktreeDir` by fetching a branch from a fork's remote URL.
 * Use this for fork PRs where the branch is not available on `origin`.
 *
 * Runs `git fetch <forkCloneUrl> <branch>` then
 * `git worktree add --detach <worktreeDir> FETCH_HEAD`.
 *
 * @throws Error if a git command fails or times out
 */
export async function createWorktreeFromFork(
    repoDir: string,
    forkCloneUrl: string,
    branch: string,
    worktreeDir: string,
): Promise<void> {
    await runGit(repoDir, 120, ['fetch', forkCloneUrl, branch]);
    await runGit(repoDir, 30, ['worktree', 'add', '--detach', worktreeDir, 'FETCH_HEAD']);
}

/**
 * Removes a previously created worktree. Uses `--force` to tolerate a missing directory.
 * Resolves even on failure — cleanup failures are non-fatal and only logged.
 */
export async function removeWorktree(repoDir: string, worktreeDir: string): Promise<void> {
    try {
        await runGit(repoDir, 30, ['worktree', 'remove', '--force', worktreeDir]);
    } catch (e) {
        console.warn(`[pr-pilot] Failed to remove worktree at ${worktreeDir}: ${e instanceof Error ? e.message : String(e)}`);
    }
}

/** Returns a unique temp path for a PR's worktree. The directory must not exist when created. */
export function worktreePath(prNumber: number): string {
    const unique = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    return path.join(os.tmpdir(), `pr-pilot-wt-${prNumber}-${unique}`);
}

/**
 * Runs a git command in `dir` with a timeout. stdout and stderr are combined for error messages,
 * mirroring the JVM service's `redirectErrorStream(true)`.
 *
 * @throws Error if git exits non-zero or the timeout elapses
 */
export function runGit(dir: string, timeoutSeconds: number, args: string[]): Promise<void> {
    return new Promise((resolve, reject) => {
        const env = {
            ...process.env,
            HOME: process.env.HOME || os.homedir(),
            PATH: `/opt/homebrew/bin:/usr/local/bin:${process.env.PATH ?? ''}`,
        };
        execFile(
            'git',
            args,
            { cwd: dir, env, timeout: timeoutSeconds * 1000, maxBuffer: 10 * 1024 * 1024 },
            (err, stdout, stderr) => {
                if (!err) {
                    resolve();
                    return;
                }
                const e = err as NodeJS.ErrnoException & { killed?: boolean; signal?: string; code?: number | string };
                if (e.killed && e.signal === 'SIGTERM') {
                    reject(new Error(`git ${args[0]} timed out after ${timeoutSeconds}s`));
                    return;
                }
                const output = `${stdout}${stderr}`.trim().slice(0, 300);
                const code = typeof e.code === 'number' ? e.code : '?';
                reject(new Error(`git ${args[0]} failed (exit ${code}): ${output}`));
            },
        );
    });
}
