/**
 * Pure view logic for the PR Pilot settings webview. This module intentionally imports nothing
 * from `vscode` so it can be unit-tested under node:test. The `vscode`-coupled controller lives in
 * settings.ts.
 *
 * This webview is the VS Code equivalent of the IntelliJ PluginSettingsComponent dialog: a
 * provider-aware settings surface that shows only the relevant model selector and offers a live
 * dropdown of discovered Copilot models. See AGENTS.md cross-host parity rules.
 */

import type { ReviewGuidanceProfile } from './reviewGuidanceProfiles';
import type { NotificationHealth } from './notifications';

export type Provider = 'claude' | 'copilot';

export interface ClaudeModelOption {
    label: string;
    id: string;
}

/** Claude model presets. Mirrors CLAUDE_MODELS in PluginSettingsComponent. */
export const CLAUDE_MODEL_OPTIONS: ClaudeModelOption[] = [
    { label: 'CLI default (unset)', id: '' },
    { label: 'Haiku — fastest', id: 'claude-haiku-4-5-20251001' },
    { label: 'Sonnet — balanced', id: 'claude-sonnet-4-6' },
    { label: 'Opus — most thorough', id: 'claude-opus-4-7' },
];

/** Fallback Copilot model IDs when SDK discovery is unavailable. Mirrors COPILOT_MODEL_SUGGESTIONS. */
export const COPILOT_MODEL_SUGGESTIONS: string[] = [
    'claude-sonnet-4.6', 'claude-opus-4.7', 'claude-opus-4.8', 'gpt-5.5', 'gpt-5.4',
];

/** Reasoning-effort levels accepted by `copilot --reasoning-effort`. Mirrors COPILOT_EFFORTS. */
export const COPILOT_EFFORTS: string[] = ['none', 'low', 'medium', 'high', 'xhigh', 'max'];

export const GITHUB_BASE_URL_ERROR = 'GitHub base URL must be an HTTPS origin without credentials, a port, path, query, or fragment.';
export interface SettingsState {
    provider: Provider;
    reviewModel: string;
    reviewModelCopilot: string;
    reviewEffort: string;
    githubBaseUrl: string;
    copilotInheritMcp: boolean;
    copilotAutoEnableMcpOnReview: boolean;
    copilotConfigDir: string;
    reviewFocusAreas: string;
    reviewCustomInstructions: string;
    reviewGuidanceGlobs: string[];
    reviewGuidanceProfiles: ReviewGuidanceProfile[];
    activeReviewGuidanceProfileId: string;
    /** Remembered instructions keyed by lowercase `owner/repo`. */
    repositoryReviewInstructions: Record<string, string>;
    reviewSelfCritique: boolean;
    reviewSupervisorEnabled: boolean;
    reviewRulesDirectory: string;
    reviewBuiltInRules: boolean;
    reviewSecondReviewerModel: string;
    experimentalIntellijAssistedReview: boolean;
    notificationsEnabled: boolean;
    notifyReviewRequested: boolean;
    notifyStarredRepos: boolean;
    notificationPollMinutes: number;
    notificationHealth: NotificationHealth;
}

export function normalizeProvider(value: unknown): Provider {
    return value === 'copilot' ? 'copilot' : 'claude';
}

export function normalizeGithubBaseUrl(value: string): string {
    const candidate = value.trim().replace(/\/+$/, '');
    if (candidate === '') return 'https://github.com';
    try {
        if (candidate.endsWith(':')) throw new Error(GITHUB_BASE_URL_ERROR);
        const url = new URL(candidate);
        if (
            url.protocol !== 'https:'
            || url.username !== ''
            || url.password !== ''
            || url.port !== ''
            || url.pathname !== '/'
            || url.search !== ''
            || url.hash !== ''
        ) {
            throw new Error(GITHUB_BASE_URL_ERROR);
        }
        return url.origin;
    } catch {
        throw new Error(GITHUB_BASE_URL_ERROR);
    }
}

export function profileNameError(value: string): string | null {
    return value.trim() === '' ? 'Enter a profile name.' : null;
}

/**
 * Builds the ordered, de-duplicated list of Copilot model options to show in the dropdown:
 * discovered models (or the hardcoded suggestions when discovery returned nothing), with the
 * currently-saved value appended if it isn't already present (so a custom/older ID still shows as
 * selected). Blank IDs are excluded — "CLI default" is rendered separately.
 */
export function mergeCopilotModelOptions(discovered: string[], current: string): string[] {
    const base = discovered.length > 0 ? discovered : COPILOT_MODEL_SUGGESTIONS;
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

/** Host → webview message carrying a refreshed Copilot model list. */
export interface ModelsMessage {
    type: 'models';
    ok: boolean;
    /** True for the automatic refresh on open: update the list without a status toast on success. */
    quiet: boolean;
    message: string;
    copilotModels: string[];
}

/**
 * Builds the `models` message for a refresh. `discovered` empty means the live probe failed; the
 * dropdown then keeps the last good list (`previous`) or falls back to suggestions.
 */
export function buildModelsMessage(
    discovered: string[],
    previous: string[] | null,
    current: string,
    quiet: boolean,
): ModelsMessage {
    const ok = discovered.length > 0;
    const hasPrevious = previous !== null && previous.length > 0;
    let message: string;
    if (ok) {
        message = `${discovered.length} models available to your Copilot account.`;
    } else if (hasPrevious) {
        message = 'Could not refresh Copilot models. Showing the last loaded list.';
    } else {
        message = 'Could not load Copilot models. Showing suggestions.';
    }
    return {
        type: 'models',
        ok,
        quiet,
        message,
        copilotModels: mergeCopilotModelOptions(ok ? discovered : (previous ?? []), current),
    };
}

/** Escapes a string for safe interpolation into HTML text/attribute contexts. */
export function escapeHtml(value: string): string {
    return value
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

export { buildSettingsHtml } from './settingsViewHtml';
