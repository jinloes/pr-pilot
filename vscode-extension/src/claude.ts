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

export function findClaudeBinary(): string {
    const home = process.env.HOME || os.homedir();
    const candidates = [
        `${home}/.local/bin/claude`,
        `${home}/.npm-global/bin/claude`,
        '/usr/local/bin/claude',
        '/opt/homebrew/bin/claude',
        '/usr/bin/claude',
    ];
    for (const p of candidates) {
        try { if (fs.statSync(p).isFile()) return p; } catch { /* not found */ }
    }
    return 'claude';
}

/** True when `name` exists as a file in any directory on the process PATH. */
export function existsOnPath(name: string): boolean {
    const dirs = (process.env.PATH ?? '').split(path.delimiter);
    for (const dir of dirs) {
        if (!dir) continue;
        try { if (fs.statSync(path.join(dir, name)).isFile()) return true; } catch { /* not here */ }
    }
    return false;
}

/**
 * Proactive preflight: true when the `claude` CLI is resolvable without spawning it — either a
 * hard-coded candidate path exists, or `claude` is found on PATH.
 */
export function claudeBinaryAvailable(): boolean {
    return findClaudeBinary() !== 'claude' || existsOnPath('claude');
}
