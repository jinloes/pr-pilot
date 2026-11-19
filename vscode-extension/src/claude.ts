import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';

export interface ChatMessage {
    role: 'USER' | 'ASSISTANT';
    content: string;
}

// Review and chat prompts are built by review-engine and reached over the sidecar; this file
// must not grow a copy of them (AGENTS.md modularity guardrail #5).

// ── Binary resolution ──────────────────────────────────────────────────────────

const DEFAULT_WIN_EXTS = ['.exe', '.cmd', '.bat', '.com'];

function windowsExtensions(env: NodeJS.ProcessEnv = process.env): string[] {
    const raw = env.PATHEXT ?? '';
    const extra = raw
        .split(';')
        .map((ext) => ext.trim().toLowerCase())
        .filter((ext) => ext.startsWith('.') && !DEFAULT_WIN_EXTS.includes(ext));
    return [...DEFAULT_WIN_EXTS, ...extra];
}

export function resolveCandidateBinary(
    candidate: string,
    platform: NodeJS.Platform = process.platform,
    env: NodeJS.ProcessEnv = process.env,
): string | null {
    if (platform !== 'win32') {
        try { return fs.statSync(candidate).isFile() ? candidate : null; } catch { return null; }
    }
    const ext = path.win32.extname(candidate).toLowerCase();
    const winExts = windowsExtensions(env);
    if (ext && winExts.includes(ext)) {
        try { return fs.statSync(candidate).isFile() ? candidate : null; } catch { return null; }
    }
    for (const winExt of winExts) {
        const withExt = `${candidate}${winExt}`;
        try { if (fs.statSync(withExt).isFile()) return withExt; } catch { /* next */ }
    }
    return null;
}

export function candidateBinaryPaths(
    name: string,
    platform: NodeJS.Platform = process.platform,
    env: NodeJS.ProcessEnv = process.env,
    homeDir: string = env.HOME || env.USERPROFILE || os.homedir(),
): string[] {
    if (platform !== 'win32') {
        return [
            `${homeDir}/.local/bin/${name}`,
            `${homeDir}/.npm-global/bin/${name}`,
            `${homeDir}/.volta/bin/${name}`,
            `/usr/local/bin/${name}`,
            `/opt/homebrew/bin/${name}`,
            `/usr/bin/${name}`,
        ];
    }
    const candidates: string[] = [];
    if (homeDir) {
        candidates.push(path.win32.join(homeDir, '.local', 'bin', name));
        candidates.push(path.win32.join(homeDir, '.volta', 'bin', name));
    }
    if (env.LOCALAPPDATA) {
        candidates.push(path.win32.join(env.LOCALAPPDATA, 'Programs', name, name));
        candidates.push(path.win32.join(env.LOCALAPPDATA, 'Volta', 'bin', name));
    }
    if (env.ProgramFiles) {
        candidates.push(path.win32.join(env.ProgramFiles, 'Volta', name));
    }
    if (env.APPDATA) {
        candidates.push(path.win32.join(env.APPDATA, 'npm', name));
    }
    return candidates;
}

export function findClaudeBinary(): string {
    for (const p of candidateBinaryPaths('claude')) {
        const resolved = resolveCandidateBinary(p);
        if (resolved) return resolved;
    }
    return resolveFromPath('claude') ?? 'claude';
}

export function resolveFromPath(
    name: string,
    platform: NodeJS.Platform = process.platform,
    env: NodeJS.ProcessEnv = process.env,
): string | null {
    const delimiter = platform === 'win32' ? ';' : ':';
    const dirs = (env.PATH ?? env.Path ?? '').split(delimiter);
    const winExts = platform === 'win32' ? windowsExtensions(env) : [];
    const nameExt = platform === 'win32' ? path.win32.extname(name).toLowerCase() : '';
    const exts = platform === 'win32'
        ? (nameExt && winExts.includes(nameExt) ? [''] : winExts)
        : [''];
    for (const dir of dirs) {
        const trimmed = dir.trim();
        if (!trimmed) continue;
        for (const ext of exts) {
            const full = platform === 'win32'
                ? path.win32.join(trimmed, `${name}${ext}`)
                : path.posix.join(trimmed, `${name}${ext}`);
            try {
                if (fs.statSync(full).isFile()) {
                    return platform === 'win32' ? full : name;
                }
            } catch { /* not here */ }
        }
    }
    return null;
}

/** True when `name` exists as a file in any directory on the process PATH. */
export function existsOnPath(name: string): boolean {
    return resolveFromPath(name) !== null;
}

/**
 * Proactive preflight: true when the `claude` CLI is resolvable without spawning it — either a
 * hard-coded candidate path exists, or `claude` is found on PATH.
 */
export function claudeBinaryAvailable(): boolean {
    for (const p of candidateBinaryPaths('claude')) {
        if (resolveCandidateBinary(p)) return true;
    }
    return existsOnPath('claude');
}

const UNSAFE_BATCH_SHIM_CHARS = /["&|<>^%!\r\n\0]/;

export interface ClaudeAuthProbeCommand {
    command: string;
    args: string[];
}

/**
 * Builds the `execFile` invocation for `claude auth status --text`. On Windows, `.cmd`/`.bat`
 * shims must be invoked via `[ComSpec, '/d', '/c', 'call', binary, ...]` (`call` prevents `cmd /c`
 * from stripping outer quotes on spaced paths) and rejected if `binary` contains batch metacharacters.
 */
export function buildClaudeAuthProbeCommand(
    binary: string,
    platform: NodeJS.Platform = process.platform,
    env: NodeJS.ProcessEnv = process.env,
): ClaudeAuthProbeCommand | null {
    if (!binary) return null;
    const isCmdShim = platform === 'win32' && /\.(cmd|bat)$/i.test(binary);
    if (!isCmdShim) {
        return { command: binary, args: ['auth', 'status', '--text'] };
    }
    if (UNSAFE_BATCH_SHIM_CHARS.test(binary)) {
        return null;
    }
    return {
        command: env.ComSpec || 'cmd.exe',
        args: ['/d', '/c', 'call', binary, 'auth', 'status', '--text'],
    };
}
