import * as vscode from 'vscode';
import * as crypto from 'crypto';
import * as copilot from './copilot';
import * as github from './github';
import {
    buildSettingsHtml,
    mergeCopilotModelOptions,
    normalizeProvider,
    type SettingsState,
} from './settingsView';

let panel: vscode.WebviewPanel | undefined;

function config(): vscode.WorkspaceConfiguration {
    return vscode.workspace.getConfiguration('pr-pilot');
}

function readState(): SettingsState {
    const c = config();
    return {
        provider: normalizeProvider(c.get<string>('reviewProvider', 'claude')),
        reviewModel: c.get<string>('reviewModel', ''),
        reviewModelCopilot: c.get<string>('reviewModelCopilot', ''),
        reviewEffort: c.get<string>('reviewEffort', 'medium'),
        githubBaseUrl: c.get<string>('githubBaseUrl', 'https://github.com'),
    };
}

const ALLOWED_KEYS = new Set([
    'reviewProvider', 'reviewModel', 'reviewModelCopilot', 'reviewEffort', 'githubBaseUrl',
]);

/** Opens (or reveals) the PR Pilot settings webview panel. */
export function openSettings(context: vscode.ExtensionContext): void {
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

    const sendInit = async () => {
        const state = readState();
        const discovered = await copilot.listModels().catch(() => []);
        current.webview.postMessage({
            type: 'init',
            state,
            copilotModels: mergeCopilotModelOptions(discovered, state.reviewModelCopilot),
        });
    };

    current.webview.onDidReceiveMessage(async (msg: { type?: string } & Record<string, unknown>) => {
        if (!msg || typeof msg.type !== 'string') return;
        switch (msg.type) {
            case 'ready':
                await sendInit();
                break;
            case 'update': {
                const key = typeof msg.key === 'string' ? msg.key : '';
                if (!ALLOWED_KEYS.has(key)) return;
                const value = typeof msg.value === 'string' ? msg.value : '';
                if (key === 'githubBaseUrl' && value && !value.startsWith('https://')) {
                    current.webview.postMessage({
                        type: 'saveResult',
                        ok: false,
                        key,
                        message: 'GitHub base URL must start with https://',
                    });
                    return;
                }
                try {
                    await config().update(key, value, vscode.ConfigurationTarget.Global);
                    current.webview.postMessage({ type: 'saveResult', ok: true, key, message: 'Saved.' });
                } catch (err) {
                    current.webview.postMessage({
                        type: 'saveResult',
                        ok: false,
                        key,
                        message: err instanceof Error ? err.message : 'Could not save setting.',
                    });
                }
                break;
            }
            case 'refreshModels': {
                let ok = true;
                let message = 'Model list refreshed.';
                const discovered = await copilot.listModels(true).catch((err) => {
                    ok = false;
                    message = err instanceof Error ? err.message : 'Could not refresh models.';
                    return [];
                });
                const state = readState();
                current.webview.postMessage({
                    type: 'models',
                    ok,
                    message,
                    copilotModels: mergeCopilotModelOptions(discovered, state.reviewModelCopilot),
                });
                break;
            }
            case 'testConnection': {
                const baseUrl = typeof msg.githubBaseUrl === 'string' && msg.githubBaseUrl.trim()
                    ? msg.githubBaseUrl.trim()
                    : readState().githubBaseUrl;
                if (!baseUrl.startsWith('https://')) {
                    current.webview.postMessage({
                        type: 'testResult',
                        ok: false,
                        message: 'GitHub base URL must start with https://',
                    });
                    return;
                }
                try {
                    await github.resolveToken(baseUrl);
                    current.webview.postMessage({
                        type: 'testResult',
                        ok: true,
                        message: 'gh authentication is available for this host.',
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
