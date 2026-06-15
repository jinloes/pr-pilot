import * as fs from 'fs';
import * as path from 'path';

export const TARGET_REPO_ENV = 'PR_PILOT_TARGET_REPO';

function defaultDirectoryExists(dir: string): boolean {
    try {
        return fs.statSync(dir).isDirectory();
    } catch {
        return false;
    }
}

export function resolveWorkspaceDir(
    workspaceFolders: readonly string[],
    env: NodeJS.ProcessEnv = process.env,
    directoryExists: (dir: string) => boolean = defaultDirectoryExists,
): string {
    const override = env[TARGET_REPO_ENV]?.trim();
    if (override && path.isAbsolute(override) && directoryExists(override)) {
        return override;
    }
    return workspaceFolders[0] ?? '';
}
