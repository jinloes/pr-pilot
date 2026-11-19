import {
    candidateBinaryPaths,
    existsOnPath,
    resolveCandidateBinary,
    resolveFromPath,
} from './claude';
import type { SidecarCopilotModelsResult } from './sidecarTypes';

// ── Constants ─────────────────────────────────────────────────────────────────

/**
 * Default for PR review work: `high` trades some latency for materially deeper reasoning, which
 * catches more real correctness/security issues while still following the strict JSON schema.
 */
export const DEFAULT_REASONING_EFFORT = 'high';

export type PermissionDecision =
    | { kind: 'approve-once' }
    | { kind: 'reject'; feedback: string };

export function permissionDecision(kind: string, allowMcp: boolean): PermissionDecision {
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

export function findCopilotBinary(): string {
    for (const p of candidateBinaryPaths('copilot')) {
        const resolved = resolveCandidateBinary(p);
        if (resolved) return resolved;
    }
    return resolveFromPath('copilot') ?? 'copilot';
}

/**
 * Proactive preflight: true when the `copilot` CLI is resolvable without spawning it — either a
 * hard-coded candidate path exists, or `copilot` is found on PATH.
 */
export function copilotBinaryAvailable(): boolean {
    for (const p of candidateBinaryPaths('copilot')) {
        if (resolveCandidateBinary(p)) return true;
    }
    return existsOnPath('copilot');
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

// ── Model discovery (sidecar-backed) ──────────────────────────────────────────

export interface CopilotModelClient {
    listCopilotModels(forceRefresh?: boolean): Promise<SidecarCopilotModelsResult>;
}

/**
 * Last successful list of Copilot model IDs, or `null` when no probe has succeeded yet. A failed
 * probe never overwrites a good list, so a transient failure doesn't strip newer models from the
 * dropdown.
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
 * Extracts usable model IDs from a model catalog: drops non-object entries, policy-`disabled`
 * models and blank IDs, then de-dupes while preserving catalog order.
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
 * Discovers Copilot models via the sidecar's `reviews/listCopilotModels` capability, preserving
 * `source` (`ACCOUNT` | `CLI_HELP` | `NONE`) and `failureReason`. Only live `ACCOUNT` catalogs are
 * cached in `modelCache`, so a `CLI_HELP` fallback never overwrites previously loaded account models.
 */
export async function discoverModels(
    sidecar: CopilotModelClient,
    forceRefresh = false,
): Promise<SidecarCopilotModelsResult> {
    if (!forceRefresh && modelCache !== null) {
        return { models: modelCache, source: 'ACCOUNT', failureReason: '' };
    }

    try {
        const result = await sidecar.listCopilotModels(forceRefresh);
        const ids = Array.from(
            new Set(
                result.models
                    .map((id) => id.trim())
                    .filter((id) => id.length > 0),
            ),
        );
        if (result.source === 'ACCOUNT' && ids.length > 0) {
            modelCache = ids;
        } else if (ids.length === 0 && result.failureReason) {
            console.warn('[pr-pilot] Copilot model discovery returned no models:', result.failureReason);
        }
        return {
            models: ids,
            source: result.source,
            failureReason: result.failureReason,
        };
    } catch (err) {
        const reason = err instanceof Error ? err.message : String(err);
        console.warn('[pr-pilot] Failed to probe copilot models via sidecar:', reason);
        return { models: [], source: 'NONE', failureReason: reason };
    }
}

/**
 * Returns the Copilot model IDs the current account can select via the sidecar's
 * `reviews/listCopilotModels` capability (`CopilotModelDiscovery` in `review-engine`). Serves the
 * cached list unless `forceRefresh` is set. On failure returns an empty array and keeps any
 * previously cached list, so callers can fall back without losing a good result.
 */
export async function listModels(
    sidecar: CopilotModelClient,
    forceRefresh = false,
): Promise<string[]> {
    const result = await discoverModels(sidecar, forceRefresh);
    return result.models;
}

