import type { SidecarAntigravityModelsResult } from './sidecarTypes';

/**
 * Fallback Antigravity model IDs when `agy models` discovery is unavailable. Mirrors
 * `ANTIGRAVITY_MODEL_SUGGESTIONS` in IntelliJ's `PluginSettingsComponent`.
 */
export const ANTIGRAVITY_MODEL_SUGGESTIONS: string[] = [
    'claude-opus-4-6-thinking',
    'claude-sonnet-4-6',
    'gemini-3-flash',
    'gemini-3.1-pro-high',
    'gemini-3.1-pro-low',
    'gpt-oss-120b-medium',
];

export interface AntigravityModelClient {
    listAntigravityModels(forceRefresh?: boolean): Promise<SidecarAntigravityModelsResult>;
}

let modelCache: string[] | null = null;

/** Drops the cached Antigravity model list so the next {@link discoverModels} call re-probes. */
export function invalidateModelCache(): void {
    modelCache = null;
}

/** Last successful Antigravity model list without probing, or `null` if none has succeeded yet. */
export function cachedModels(): string[] | null {
    return modelCache;
}

/**
 * Discovers Antigravity models via the sidecar's `reviews/listAntigravityModels` capability
 * (`AntigravityModelDiscovery` in `review-engine`). Only live `ACCOUNT` catalogs are cached so a
 * transient failure never overwrites previously loaded models.
 */
export async function discoverModels(
    sidecar: AntigravityModelClient,
    forceRefresh = false,
): Promise<SidecarAntigravityModelsResult> {
    if (!forceRefresh && modelCache !== null) {
        return { models: modelCache, source: 'ACCOUNT', failureReason: '' };
    }

    try {
        const result = await sidecar.listAntigravityModels(forceRefresh);
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
            console.warn('[pr-pilot] Antigravity model discovery returned no models:', result.failureReason);
        }
        return {
            models: ids,
            source: result.source,
            failureReason: result.failureReason,
        };
    } catch (err) {
        const reason = err instanceof Error ? err.message : String(err);
        console.warn('[pr-pilot] Failed to probe Antigravity models via sidecar:', reason);
        return { models: [], source: 'NONE', failureReason: reason };
    }
}

/**
 * Builds the ordered, de-duplicated list of Antigravity model options to show in the dropdown:
 * discovered models (or the hardcoded suggestions when discovery returned nothing), with the
 * currently-saved value appended if it isn't already present. Blank IDs are excluded — "CLI
 * default" is rendered separately.
 */
export function mergeAntigravityModelOptions(discovered: string[], current: string): string[] {
    const base = discovered.length > 0 ? discovered : ANTIGRAVITY_MODEL_SUGGESTIONS;
    const merged: string[] = [];
    const seen = new Set<string>();
    for (const id of [...base, current]) {
        const trimmed = id.trim();
        if (trimmed === '' || seen.has(trimmed)) continue;
        seen.add(trimmed);
        merged.push(trimmed);
    }
    return merged;
}

/** Host → webview message carrying a refreshed Antigravity model list. */
export interface AntigravityModelsMessage {
    type: 'antigravityModels';
    ok: boolean;
    /** True for automatic refreshes: update the list without a status toast on success. */
    quiet: boolean;
    message: string;
    antigravityModels: string[];
}

/** Builds the `antigravityModels` message for a refresh, preserving any prior good list on error. */
export function buildAntigravityModelsMessage(
    discovered: string[],
    previous: string[] | null,
    current: string,
    quiet: boolean,
    source: 'ACCOUNT' | 'NONE' = discovered.length > 0 ? 'ACCOUNT' : 'NONE',
    failureReason = '',
): AntigravityModelsMessage {
    const hasPrevious = previous !== null && previous.length > 0;
    const reasonDetail = failureReason.trim();
    const reasonSuffix = reasonDetail ? ` (${reasonDetail})` : '';

    if (source === 'ACCOUNT' && discovered.length > 0) {
        return {
            type: 'antigravityModels',
            ok: true,
            quiet,
            message: `${discovered.length} models reported by Antigravity CLI.`,
            antigravityModels: mergeAntigravityModelOptions(discovered, current),
        };
    }

    const message = hasPrevious
        ? `Could not refresh Antigravity models${reasonSuffix}. Showing the last loaded list.`
        : `Could not load Antigravity models${reasonSuffix}. Showing suggestions.`;
    return {
        type: 'antigravityModels',
        ok: false,
        quiet,
        message,
        antigravityModels: mergeAntigravityModelOptions(hasPrevious ? previous : [], current),
    };
}
