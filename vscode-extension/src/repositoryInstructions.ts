/**
 * Remembered per-repository review instructions (`pr-pilot.repositoryReviewInstructions`).
 *
 * Mirrors `intellij-plugin` `RepositoryReviewInstructions`: keys are lowercase `owner/repo` because
 * GitHub treats repository names case-insensitively, and the remembered text is folded into the
 * engine's existing `customInstructions` input rather than a new engine field.
 */

export const MAX_REPOSITORY_INSTRUCTIONS = 10_000;
export const MAX_REMEMBERED_REPOSITORIES = 200;

const REPOSITORY_KEY = /^[a-z0-9](?:[a-z0-9-]{0,38})\/[a-z0-9._-]{1,100}$/;

export type RepositoryInstructionsMap = Record<string, string>;

/** Returns the lowercase `owner/repo` key, or `null` when either part is not a valid GitHub name. */
export function repositoryKey(owner: string, repo: string): string | null {
    const key = `${owner.trim()}/${repo.trim()}`.toLowerCase();
    return REPOSITORY_KEY.test(key) ? key : null;
}

/**
 * Sanitizes the raw setting: keeps string entries with valid keys, trims values, drops blank or
 * over-limit values, and keeps the first entry when keys collide after lowercasing.
 */
export function normalizeRepositoryInstructions(raw: unknown): RepositoryInstructionsMap {
    const normalized: RepositoryInstructionsMap = {};
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return normalized;
    for (const [rawKey, rawValue] of Object.entries(raw as Record<string, unknown>)) {
        if (Object.keys(normalized).length >= MAX_REMEMBERED_REPOSITORIES) break;
        if (typeof rawValue !== 'string') continue;
        const slash = rawKey.indexOf('/');
        if (slash <= 0) continue;
        const key = repositoryKey(rawKey.slice(0, slash), rawKey.slice(slash + 1));
        const value = rawValue.trim();
        if (!key || !value || value.length > MAX_REPOSITORY_INSTRUCTIONS) continue;
        if (!Object.prototype.hasOwnProperty.call(normalized, key)) normalized[key] = value;
    }
    return normalized;
}

/**
 * Returns a copy with `key` set to the trimmed `instructions`, or removed when they are blank.
 * Returns `null` when the text is over the limit or a new key would exceed the repository cap.
 */
export function withRepositoryInstructions(
    current: RepositoryInstructionsMap,
    key: string,
    instructions: string,
): RepositoryInstructionsMap | null {
    const value = instructions.trim();
    if (value.length > MAX_REPOSITORY_INSTRUCTIONS) return null;
    const next: RepositoryInstructionsMap = { ...current };
    if (!value) {
        delete next[key];
        return next;
    }
    if (!Object.prototype.hasOwnProperty.call(next, key)
        && Object.keys(next).length >= MAX_REMEMBERED_REPOSITORIES) {
        return null;
    }
    next[key] = value;
    return next;
}

/** Places the remembered repository instructions ahead of the per-review or default instructions. */
export function composeCustomInstructions(repository: string, remembered: string, base: string): string {
    const rememberedText = remembered.trim();
    const baseText = base.trim();
    if (!rememberedText) return baseText;
    const section = `Instructions remembered for ${repository}:\n${rememberedText}`;
    return baseText ? `${section}\n\n${baseText}` : section;
}

/**
 * Validates a complete map sent by the settings view. Blank values mean "forget"; any other invalid
 * entry rejects the whole update (`null`) so a bad edit is reported instead of silently dropped.
 */
export function parseRepositoryInstructionsUpdate(raw: unknown): RepositoryInstructionsMap | null {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
    const parsed: RepositoryInstructionsMap = {};
    for (const [rawKey, rawValue] of Object.entries(raw as Record<string, unknown>)) {
        if (typeof rawValue !== 'string') return null;
        const slash = rawKey.indexOf('/');
        const key = slash > 0 ? repositoryKey(rawKey.slice(0, slash), rawKey.slice(slash + 1)) : null;
        if (!key || Object.prototype.hasOwnProperty.call(parsed, key)) return null;
        const value = rawValue.trim();
        if (value.length > MAX_REPOSITORY_INSTRUCTIONS) return null;
        if (value) parsed[key] = value;
    }
    return Object.keys(parsed).length > MAX_REMEMBERED_REPOSITORIES ? null : parsed;
}
