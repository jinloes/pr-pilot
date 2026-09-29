import * as vscode from 'vscode';
import * as crypto from 'crypto';
import * as copilot from './copilot';
import type { SidecarClient } from './sidecar';
import {
    buildModelsMessage,
    buildSettingsHtml,
    GITHUB_BASE_URL_ERROR,
    mergeCopilotModelOptions,
    normalizeGithubBaseUrl,
    normalizeProvider,
    type SettingsState,
} from './settingsView';
import {
    normalizeReviewGuidanceGlobs,
    normalizeReviewGuidanceProfiles,
    normalizeReviewGuidanceState,
} from './reviewGuidanceProfiles';
import { EMPTY_NOTIFICATION_HEALTH, type NotificationHealth } from './notifications';
import { normalizeRepositoryInstructions, parseRepositoryInstructionsUpdate } from './repositoryInstructions';

let panel: vscode.WebviewPanel | undefined;

function config(): vscode.WorkspaceConfiguration {
    return vscode.workspace.getConfiguration('pr-pilot');
}

function readState(notificationHealth: NotificationHealth = EMPTY_NOTIFICATION_HEALTH): SettingsState {
    const c = config();
    return {
        provider: normalizeProvider(c.get<string>('reviewProvider', 'claude')),
        reviewModel: c.get<string>('reviewModel', ''),
        reviewModelCopilot: c.get<string>('reviewModelCopilot', ''),
        reviewEffort: c.get<string>('reviewEffort', 'high'),
        githubBaseUrl: c.get<string>('githubBaseUrl', 'https://github.com'),
        copilotInheritMcp: c.get<boolean>('copilotInheritMcp', false),
        copilotAutoEnableMcpOnReview: c.get<boolean>('copilotAutoEnableMcpOnReview', false),
        copilotConfigDir: c.get<string>('copilotConfigDir', ''),
        reviewFocusAreas: c.get<string>('reviewFocusAreas', ''),
        reviewCustomInstructions: c.get<string>('reviewCustomInstructions', ''),
        reviewGuidanceGlobs: normalizeReviewGuidanceGlobs(c.get<unknown>('reviewGuidanceGlobs', [])) ?? [],
        reviewGuidanceProfiles: normalizeReviewGuidanceProfiles(c.get<unknown>('reviewGuidanceProfiles', [])) ?? [],
        activeReviewGuidanceProfileId: c.get<string>('activeReviewGuidanceProfileId', ''),
        repositoryReviewInstructions: normalizeRepositoryInstructions(c.get<unknown>('repositoryReviewInstructions', {})),
        reviewSelfCritique: c.get<boolean>('reviewSelfCritique', true),
        reviewSupervisorEnabled: c.get<boolean>('reviewSupervisorEnabled', true),
        reviewSecondReviewerModel: c.get<string>('reviewSecondReviewerModel', '').trim(),
        experimentalIntellijAssistedReview: c.get<boolean>('experimentalIntellijAssistedReview', false),
        notificationsEnabled: c.get<boolean>('notificationsEnabled', false),
        notifyReviewRequested: c.get<boolean>('notifyReviewRequested', true),
        notifyStarredRepos: c.get<boolean>('notifyStarredRepos', false),
        notificationPollMinutes: c.get<number>('notificationPollMinutes', 5),
        notificationHealth,
    };
}

const ALLOWED_KEYS = new Set([
    'reviewProvider', 'reviewModel', 'reviewModelCopilot', 'reviewEffort', 'githubBaseUrl',
    'copilotInheritMcp', 'copilotAutoEnableMcpOnReview', 'copilotConfigDir', 'reviewFocusAreas',
    'reviewCustomInstructions', 'reviewGuidanceProfiles',
    'activeReviewGuidanceProfileId', 'repositoryReviewInstructions', 'reviewSelfCritique', 'reviewSupervisorEnabled',
    'reviewSecondReviewerModel',
    'experimentalIntellijAssistedReview',
    'notificationsEnabled', 'notifyReviewRequested', 'notifyStarredRepos', 'notificationPollMinutes',
]);

const BOOLEAN_KEYS = new Set([
    'copilotInheritMcp',
    'copilotAutoEnableMcpOnReview',
    'reviewSelfCritique',
    'reviewSupervisorEnabled',
    'experimentalIntellijAssistedReview',
    'notificationsEnabled',
    'notifyReviewRequested',
    'notifyStarredRepos',
]);

type SettingsMessage = { type?: string } & Record<string, unknown>;

function saveRequestId(msg: SettingsMessage): number | undefined {
    return typeof msg.requestId === 'number' && Number.isSafeInteger(msg.requestId)
        ? msg.requestId
        : undefined;
}

function postSaveResult(
    webview: vscode.Webview,
    msg: SettingsMessage,
    key: string,
    ok: boolean,
    message: string,
): void {
    webview.postMessage({ type: 'saveResult', requestId: saveRequestId(msg), ok, key, message });
}

async function persistReviewGuidanceState(webview: vscode.Webview, msg: SettingsMessage): Promise<void> {
    const guidance = normalizeReviewGuidanceState(msg.profiles, msg.activeProfileId);
    if (guidance === null) {
        postSaveResult(webview, msg, 'reviewGuidanceState', false, 'Invalid review-guidance profile state.');
        return;
    }
    const current = readState();
    const previousProfiles = current.reviewGuidanceProfiles;
    const previousActiveId = current.reviewGuidanceProfiles.some(
        (profile) => profile.id === current.activeReviewGuidanceProfileId,
    ) ? current.activeReviewGuidanceProfileId : '';
    try {
        await config().update('reviewGuidanceProfiles', guidance.profiles, vscode.ConfigurationTarget.Global);
        await config().update('activeReviewGuidanceProfileId', guidance.activeProfileId, vscode.ConfigurationTarget.Global);
        postSaveResult(webview, msg, 'reviewGuidanceState', true, 'Saved.');
    } catch (err) {
        await Promise.allSettled([
            config().update('reviewGuidanceProfiles', previousProfiles, vscode.ConfigurationTarget.Global),
            config().update('activeReviewGuidanceProfileId', previousActiveId, vscode.ConfigurationTarget.Global),
        ]);
        postSaveResult(
            webview,
            msg,
            'reviewGuidanceState',
            false,
            err instanceof Error ? err.message : 'Could not save review-guidance profiles.',
        );
    }
}

async function persistSetting(webview: vscode.Webview, msg: SettingsMessage): Promise<void> {
    const key = typeof msg.key === 'string' ? msg.key : '';
    if (!ALLOWED_KEYS.has(key)) return;
    try {
        if (BOOLEAN_KEYS.has(key)) {
            await config().update(key, msg.value === true, vscode.ConfigurationTarget.Global);
            postSaveResult(webview, msg, key, true, 'Saved.');
            return;
        }
        if (key === 'notificationPollMinutes') {
            const numericValue = typeof msg.value === 'number' ? msg.value : Number.NaN;
            if (!Number.isInteger(numericValue) || numericValue < 1 || numericValue > 60) {
                postSaveResult(webview, msg, key, false, 'Polling interval must be between 1 and 60 minutes.');
                return;
            }
            await config().update(key, numericValue, vscode.ConfigurationTarget.Global);
            postSaveResult(webview, msg, key, true, 'Saved.');
            return;
        }
        if (key === 'reviewGuidanceProfiles') {
            const profiles = normalizeReviewGuidanceProfiles(msg.value);
            if (profiles === null) {
                postSaveResult(webview, msg, key, false, 'Invalid review-guidance profiles.');
                return;
            }
            await config().update(key, profiles, vscode.ConfigurationTarget.Global);
            postSaveResult(webview, msg, key, true, 'Saved.');
            return;
        }
        if (key === 'repositoryReviewInstructions') {
            const instructions = parseRepositoryInstructionsUpdate(msg.value);
            if (instructions === null) {
                postSaveResult(webview, msg, key, false,
                    'Repository instructions are limited to 10,000 characters and 200 repositories.');
                return;
            }
            await config().update(key, instructions, vscode.ConfigurationTarget.Global);
            postSaveResult(webview, msg, key, true, 'Saved.');
            return;
        }
        let value = typeof msg.value === 'string' ? msg.value : '';
        if (key === 'activeReviewGuidanceProfileId') {
            value = value.trim();
            const profiles = readState().reviewGuidanceProfiles;
            if (value.length > 128 || (value && !profiles.some((profile) => profile.id === value))) {
                postSaveResult(webview, msg, key, false, 'Invalid review-guidance profile ID.');
                return;
            }
        }
        if (key === 'githubBaseUrl') {
            try {
                value = normalizeGithubBaseUrl(value);
            } catch {
                postSaveResult(webview, msg, key, false, GITHUB_BASE_URL_ERROR);
                return;
            }
        }
        await config().update(key, value, vscode.ConfigurationTarget.Global);
        postSaveResult(webview, msg, key, true, 'Saved.');
    } catch (err) {
        postSaveResult(
            webview,
            msg,
            key,
            false,
            err instanceof Error ? err.message : 'Could not save setting.',
        );
    }
}

/** Opens (or reveals) the PR Pilot settings webview panel. */
export function openSettings(
    context: vscode.ExtensionContext,
    sidecar: SidecarClient,
    getNotificationHealth: () => NotificationHealth = () => EMPTY_NOTIFICATION_HEALTH,
    retryNotifications: () => Promise<void> = () => Promise.resolve(),
): void {
    if (panel) {
        panel.reveal();
        return;
    }
    panel = vscode.window.createWebviewPanel(
        'pr-pilot.settings',
        'PR Pilot Settings',
        vscode.ViewColumn.Active,
        { enableScripts: true, retainContextWhenHidden: true },
    );
    const current = panel;
    const nonce = crypto.randomBytes(16).toString('base64');
    current.webview.html = buildSettingsHtml(current.webview.cspSource, nonce);
    let saveQueue = Promise.resolve();
    const enqueueSave = (save: () => Promise<void>): Promise<void> => {
        saveQueue = saveQueue.then(save, save);
        return saveQueue;
    };

    const refreshModels = async (quiet: boolean) => {
        const previous = copilot.cachedModels();
        const discovered = await copilot.listModels(true).catch(() => []);
        try {
            void current.webview.postMessage(
                buildModelsMessage(discovered, previous, readState().reviewModelCopilot, quiet),
            );
        } catch {
            // The panel was closed while the probe was running; the next open refreshes again.
        }
    };

    // Render immediately from the last known list, then re-probe: the probe boots the Copilot
    // runtime (seconds), and the account's catalog changes as new models roll out.
    const sendInit = async (refresh = true) => {
        const state = readState(getNotificationHealth());
        current.webview.postMessage({
            type: 'init',
            state,
            refreshingModels: refresh,
            copilotModels: mergeCopilotModelOptions(copilot.cachedModels() ?? [], state.reviewModelCopilot),
        });
        if (refresh) await refreshModels(true);
    };

    current.webview.onDidReceiveMessage(async (msg: SettingsMessage) => {
        if (!msg || typeof msg.type !== 'string') return;
        switch (msg.type) {
            case 'ready':
                await sendInit();
                break;
            case 'update': {
                await enqueueSave(() => persistSetting(current.webview, msg));
                break;
            }
            case 'updateReviewGuidanceState': {
                await enqueueSave(() => persistReviewGuidanceState(current.webview, msg));
                break;
            }
            case 'refreshModels': {
                await refreshModels(false);
                break;
            }
            case 'retryNotifications': {
                await retryNotifications();
                await sendInit(false);
                break;
            }
            case 'testConnection': {
                const configuredBaseUrl = typeof msg.githubBaseUrl === 'string' && msg.githubBaseUrl.trim()
                    ? msg.githubBaseUrl.trim()
                    : readState().githubBaseUrl;
                let baseUrl: string;
                try {
                    baseUrl = normalizeGithubBaseUrl(configuredBaseUrl);
                } catch {
                    current.webview.postMessage({
                        type: 'testResult',
                        ok: false,
                        message: GITHUB_BASE_URL_ERROR,
                    });
                    return;
                }
                try {
                    const authResult = await sidecar.checkGitHubAuth(baseUrl);
                    current.webview.postMessage({
                        type: 'testResult',
                        ok: authResult.status === 'authenticated',
                        message: authResult.status === 'authenticated' && authResult.username
                            ? `Signed in as @${authResult.username}.`
                            : authResult.message,
                    });
                } catch (err) {
                    current.webview.postMessage({
                        type: 'testResult',
                        ok: false,
                        message: err instanceof Error ? err.message : 'Could not verify gh authentication.',
                    });
                }
                break;
            }
            default:
                break;
        }
    }, undefined, context.subscriptions);

    current.onDidDispose(() => { panel = undefined; }, undefined, context.subscriptions);
}
