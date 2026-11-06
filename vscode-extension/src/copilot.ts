import * as fs from 'fs';
import * as os from 'os';
import {
    CopilotClient,
    RuntimeConnection,
    type PermissionRequestResult,
} from '@github/copilot-sdk';
import { existsOnPath } from './claude';

// ── Constants ─────────────────────────────────────────────────────────────────

/**
 * Default for PR review work: `high` trades some latency for materially deeper reasoning, which
 * catches more real correctness/security issues while still following the strict JSON schema.
 */
export const DEFAULT_REASONING_EFFORT = 'high';
export const SDK_BOOT_TIMEOUT_MS = 60 * 1000;

export function permissionDecision(kind: string, allowMcp: boolean): PermissionRequestResult {
    if (kind === 'read') {
        return { kind: 'approve-once' };
    }
    if (allowMcp && kind === 'mcp') {
        return { kind: 'approve-once' };
    }
    return {
        kind: 'reject',
        feedback: 'PR Pilot reviews allow read-only file access only; write, shell, and network tools are disabled.',
    };
}

/**
 * Resolves whether MCP should be enabled for Copilot review generation.
 * `forceForReview` is an explicit opt-in that affects review only; chat still uses `inheritMcp`.
 */
export function resolveReviewInheritMcp(inheritMcp: boolean, forceForReview: boolean): boolean {
    return inheritMcp || forceForReview;
}

// ── Binary resolution ──────────────────────────────────────────────────────────

function findCopilotBinary(): string {
    const home = process.env.HOME || os.homedir();
    const candidates = [
        `${home}/.local/bin/copilot`,
        `${home}/.npm-global/bin/copilot`,
        '/usr/local/bin/copilot',
        '/opt/homebrew/bin/copilot',
        '/usr/bin/copilot',
    ];
    for (const p of candidates) {
        try { if (fs.statSync(p).isFile()) return p; } catch { /* not found */ }
    }
    return 'copilot';
}

/**
 * Proactive preflight: true when the `copilot` CLI is resolvable without spawning it — either a
 * hard-coded candidate path exists, or `copilot` is found on PATH.
 */
export function copilotBinaryAvailable(): boolean {
    return findCopilotBinary() !== 'copilot' || existsOnPath('copilot');
}

type ReasoningEffort = 'low' | 'medium' | 'high' | 'xhigh';

export function normalizeReasoningEffort(effort: string): ReasoningEffort {
    switch (effort.trim().toLowerCase()) {
        case 'low':
        case 'medium':
        case 'high':
        case 'xhigh':
            return effort.trim().toLowerCase() as ReasoningEffort;
        case 'none':
            return 'low';
        case 'max':
            return 'xhigh';
        default:
            return DEFAULT_REASONING_EFFORT;
    }
}

// ── Timeout helper ─────────────────────────────────────────────────────────────

export async function withTimeout<T>(promise: Promise<T>, timeoutMs: number, operation: string): Promise<T> {
    const timeoutSeconds = Math.max(1, Math.ceil(timeoutMs / 1000));
    let timer: NodeJS.Timeout | undefined;
    const timeoutPromise = new Promise<never>((_, reject) => {
        timer = setTimeout(() => {
            reject(new Error(`copilot ${operation} timed out after ${timeoutSeconds}s`));
        }, timeoutMs);
    });
    try {
        return await Promise.race([promise, timeoutPromise]);
    } finally {
        if (timer) {
            clearTimeout(timer);
        }
    }
}

// ── Runtime management ────────────────────────────────────────────────────────

function buildRuntimeEnv(): NodeJS.ProcessEnv {
    return {
        ...process.env,
        HOME: process.env.HOME || os.homedir(),
        PATH: `/opt/homebrew/bin:/usr/local/bin:${process.env.PATH ?? ''}`,
    };
}

// Review/chat generation now runs sidecar-side (shared review-engine CopilotService), so this
// module retains only model discovery (no RPC endpoint exists for that yet) and the small pure
// helpers (permissionDecision, normalizeReasoningEffort, resolveReviewInheritMcp) still exercised
// directly by tests and by extension.ts's provider-agnostic settings wiring.

// ── Model discovery ───────────────────────────────────────────────────────────

/**
 * Last successful list of Copilot model IDs, or `null` when no probe has succeeded yet. A failed
 * probe never overwrites a good list, so a transient failure doesn't strip newer models from the
 * dropdown. Mirrors CopilotModelDiscovery's cache semantics.
 */
let modelCache: string[] | null = null;

/** Drops the cached model list so the next {@link listModels} call re-probes. */
export function invalidateModelCache(): void {
    modelCache = null;
}

/** Last successful model list without probing, or `null` if none has succeeded yet. */
export function cachedModels(): string[] | null {
    return modelCache;
}

interface ModelInfoLike {
    id?: unknown;
    policy?: { state?: unknown } | null;
}

/**
 * Extracts usable model IDs from a model catalog (`session.rpc.model.list()` entries or
 * `client.listModels()` output): drops non-object entries, policy-`disabled` models and blank IDs,
 * then de-dupes while preserving catalog order. Pure (no I/O) so it can be tested without spawning
 * the CLI. Mirrors CopilotModelDiscovery.filterModelIds.
 */
export function filterModelIds(models: readonly unknown[]): string[] {
    const ids = models
        .filter((m): m is ModelInfoLike => m !== null && typeof m === 'object' && !Array.isArray(m))
        .filter((m) => m.policy?.state !== 'disabled')
        .map((m) => (typeof m.id === 'string' ? m.id.trim() : ''))
        .filter((id) => id.length > 0);
    return Array.from(new Set(ids));
}

/**
 * Lists the models a session can select, as the CLI's own `/model` picker does. The global
 * `models.list` RPC omits models the session catalog includes (for example newly enabled Gemini
 * models), so a short-lived, tool-free session is opened, queried, and deleted. No prompt is sent,
 * so this uses no model quota.
 */
async function listSessionModels(client: CopilotClient): Promise<unknown[]> {
    const session = await withTimeout(client.createSession({
        workingDirectory: os.homedir(),
        availableTools: [],
        enableConfigDiscovery: false,
        onPermissionRequest: () => ({ kind: 'reject' }),
    }), SDK_BOOT_TIMEOUT_MS, 'session creation');
    try {
        const result = await withTimeout(session.rpc.model.list({}), SDK_BOOT_TIMEOUT_MS, 'model discovery');
        return Array.isArray(result?.list) ? result.list : [];
    } finally {
        await client.deleteSession(session.sessionId).catch(() => undefined);
    }
}

/**
 * Returns the Copilot model IDs the current account can select: the session model catalog, falling
 * back to the global `client.listModels()` catalog when the session route fails or returns
 * nothing. Only models whose policy is not `disabled` are returned. Serves the cached list unless
 * `forceRefresh` is set. On failure returns an empty array and keeps any previously cached list, so
 * callers can fall back without losing a good result.
 *
 * Mirrors CopilotModelDiscovery.refresh (IntelliJ), which uses the same catalogs.
 */
export async function listModels(forceRefresh = false): Promise<string[]> {
    if (!forceRefresh && modelCache !== null) return modelCache;

    const client = new CopilotClient({
        connection: RuntimeConnection.forStdio({ path: findCopilotBinary() }),
        workingDirectory: os.homedir(),
        env: buildRuntimeEnv(),
        mode: 'copilot-cli',
    });
    try {
        await withTimeout(client.start(), SDK_BOOT_TIMEOUT_MS, 'runtime startup');
        let ids: string[] = [];
        try {
            ids = filterModelIds(await listSessionModels(client));
        } catch (err) {
            console.warn('[pr-pilot] Session model catalog unavailable; using models.list:',
                err instanceof Error ? err.message : String(err));
        }
        if (ids.length === 0) {
            ids = filterModelIds(await withTimeout(client.listModels(), SDK_BOOT_TIMEOUT_MS, 'model discovery'));
        }
        if (ids.length > 0) modelCache = ids;
        return ids;
    } catch (err) {
        console.warn('[pr-pilot] Failed to probe copilot models:',
            err instanceof Error ? err.message : String(err));
        return [];
    } finally {
        await client.stop().catch(() => undefined);
    }
}

