import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'fs';
import * as path from 'path';
import { runInNewContext } from 'node:vm';

import {
    ANTIGRAVITY_MODEL_SUGGESTIONS,
    buildAntigravityModelsMessage,
    cachedModels,
    discoverModels,
    invalidateModelCache,
    mergeAntigravityModelOptions,
} from '../src/antigravity';
import { EMPTY_NOTIFICATION_HEALTH } from '../src/notifications';
import { toProviderReadiness } from '../src/providerSetup';
import { buildSettingsHtml } from '../src/settingsViewHtml';
import { SidecarClient, parseAntigravityModelsResult } from '../src/sidecar';
import { providerNotInstalledMessage, toUserFacingError } from '../src/userFacingError';
import { createSidecarHarness, fakeJar, initializeResult } from './sidecarHarness';

function findExtensionRoot(): string {
    let dir = __dirname;
    for (let i = 0; i < 6; i++) {
        const candidate = path.join(dir, 'package.json');
        if (fs.existsSync(candidate)) {
            const parsed = JSON.parse(fs.readFileSync(candidate, 'utf8'));
            if (parsed?.name === 'pr-pilot-vscode') return dir;
        }
        dir = path.dirname(dir);
    }
    throw new Error('could not locate the vscode-extension root from ' + __dirname);
}

test('package.json declares antigravity in reviewProvider and reviewModelAntigravity default', () => {
    const root = findExtensionRoot();
    const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
    const properties = pkg.contributes.configuration.properties;

    assert.deepEqual(properties['pr-pilot.reviewProvider'].enum, ['claude', 'copilot', 'antigravity']);
    assert.equal(properties['pr-pilot.reviewProvider'].enumDescriptions.length, 3);
    assert.equal(properties['pr-pilot.reviewModelAntigravity'].type, 'string');
    assert.equal(properties['pr-pilot.reviewModelAntigravity'].default, '');
});

test('parseAntigravityModelsResult validates sidecar model payloads and rejects invalid shapes', () => {
    assert.deepEqual(
        parseAntigravityModelsResult({
            models: ['claude-sonnet-4-6', 'gemini-3.1-pro-high'],
            source: 'ACCOUNT',
            failureReason: '',
        }),
        {
            models: ['claude-sonnet-4-6', 'gemini-3.1-pro-high'],
            source: 'ACCOUNT',
            failureReason: '',
        },
    );
    assert.deepEqual(
        parseAntigravityModelsResult({
            models: [],
            source: 'NONE',
            failureReason: 'agy not found',
        }),
        {
            models: [],
            source: 'NONE',
            failureReason: 'agy not found',
        },
    );
    assert.equal(parseAntigravityModelsResult({ models: ['a'], source: 'CLI_HELP', failureReason: '' }), null);
    assert.equal(parseAntigravityModelsResult({ models: [42], source: 'ACCOUNT', failureReason: '' }), null);
    assert.equal(parseAntigravityModelsResult(null), null);
});

test('SidecarClient.listAntigravityModels calls reviews/listAntigravityModels and caches ACCOUNT catalogs', async () => {
    invalidateModelCache();
    const harness = createSidecarHarness((method, params) => {
        if (method === 'initialize') return initializeResult;
        if (method === 'reviews/listAntigravityModels') {
            assert.deepEqual(params, { forceRefresh: true });
            return {
                models: ['gemini-3.1-pro-high', 'claude-sonnet-4-6', 'gemini-3.1-pro-high'],
                source: 'ACCOUNT',
                failureReason: '',
            };
        }
        throw new Error(`unexpected method: ${method}`);
    });
    const client = new SidecarClient(fakeJar, 'java', harness.spawnSidecar, 2000);
    try {
        const discovered = await discoverModels(client, true);
        assert.deepEqual(discovered, {
            models: ['gemini-3.1-pro-high', 'claude-sonnet-4-6'],
            source: 'ACCOUNT',
            failureReason: '',
        });
        assert.deepEqual(cachedModels(), ['gemini-3.1-pro-high', 'claude-sonnet-4-6']);

        // Subsequent unforced call serves cache without hitting sidecar again.
        const fromCache = await discoverModels(client, false);
        assert.deepEqual(fromCache.models, ['gemini-3.1-pro-high', 'claude-sonnet-4-6']);
        assert.equal(harness.methods.filter((m) => m === 'reviews/listAntigravityModels').length, 1);
    } finally {
        client.dispose();
        invalidateModelCache();
    }
});

test('mergeAntigravityModelOptions and buildAntigravityModelsMessage preserve prior good catalogs on failure', () => {
    assert.deepEqual(mergeAntigravityModelOptions([], ''), ANTIGRAVITY_MODEL_SUGGESTIONS);
    assert.deepEqual(
        mergeAntigravityModelOptions(['gemini-3-flash'], 'custom-agy-slug'),
        ['gemini-3-flash', 'custom-agy-slug'],
    );

    const okMsg = buildAntigravityModelsMessage(['gemini-3-flash', 'claude-sonnet-4-6'], null, '', false, 'ACCOUNT');
    assert.equal(okMsg.ok, true);
    assert.match(okMsg.message, /2 models reported by Antigravity CLI/);
    assert.deepEqual(okMsg.antigravityModels, ['gemini-3-flash', 'claude-sonnet-4-6']);

    const fallbackWithPrev = buildAntigravityModelsMessage([], ['gemini-3-flash'], 'custom', false, 'NONE', 'timed out');
    assert.equal(fallbackWithPrev.ok, false);
    assert.match(fallbackWithPrev.message, /timed out/);
    assert.match(fallbackWithPrev.message, /last loaded list/);
    assert.deepEqual(fallbackWithPrev.antigravityModels, ['gemini-3-flash', 'custom']);
});

test('Antigravity readiness and user-facing CLI errors name agy and Antigravity CLI', () => {
    const missingMsg = providerNotInstalledMessage('antigravity');
    assert.match(missingMsg, /Antigravity CLI/);
    assert.match(missingMsg, /'agy'/);

    const spawnErr = toUserFacingError(new Error('Cannot run program "agy": error=2, No such file or directory'), 'generate review');
    assert.match(spawnErr, /Antigravity CLI/);
    assert.match(spawnErr, /agy/);

    const ready = toProviderReadiness('antigravity', {
        available: true,
        binaryStatus: 'ready',
        authenticationStatus: 'unverified',
        authCommand: 'agy',
    });
    assert.equal(ready.available, true);
    assert.equal(ready.authenticationStatus, 'unverified');
    assert.equal(ready.authCommand, 'agy');
    assert.match(ready.detail, /Authentication cannot be verified without starting a provider session/);

    const missing = toProviderReadiness('antigravity', {
        available: false,
        binaryStatus: 'missing',
        authenticationStatus: 'unavailable',
        authCommand: 'agy',
    });
    assert.equal(missing.available, false);
    assert.equal(missing.binaryStatus, 'missing');
    assert.match(missing.detail, /Antigravity CLI/);
});

test('settings webview toggles Antigravity model controls and hides Copilot-only sections', () => {
    const html = buildSettingsHtml('csp', 'n');
    assert.match(html, /<option value="antigravity">Antigravity \(agy CLI\)<\/option>/);
    assert.match(html, /id="antigravityModelField"/);
    assert.match(html, /id="refreshAntigravityModels"/);

    const script = /<script nonce="n">([\s\S]*)<\/script>/.exec(html)?.[1];
    assert.ok(script, 'settings script not found');

    interface MiniEl {
        id: string;
        value: string;
        checked: boolean;
        disabled: boolean;
        hidden: boolean;
        textContent: string;
        innerHTML: string;
        className: string;
        style: Record<string, string>;
        hiddenClass: boolean;
        listeners: Map<string, Array<(ev: Record<string, unknown>) => void>>;
        options: Array<{ value: string; textContent: string }>;
        classList: { toggle: (name: string, force?: boolean) => void };
        setAttribute: () => void;
        removeAttribute: () => void;
        addEventListener: (type: string, fn: (ev: Record<string, unknown>) => void) => void;
        dispatch: (type: string) => void;
        appendChild: (child: { value: string; textContent: string }) => void;
        focus: () => void;
        select: () => void;
        querySelectorAll: () => never[];
    }
    const elements = new Map<string, MiniEl>();
    const makeEl = (id: string): MiniEl => {
        const el: MiniEl = {
            id, value: '', checked: false, disabled: false, hidden: false, textContent: '', innerHTML: '',
            className: '', style: {}, hiddenClass: false, listeners: new Map(), options: [],
            classList: {
                toggle: (name, force) => {
                    if (name === 'hidden') el.hiddenClass = force ?? !el.hiddenClass;
                },
            },
            setAttribute() {},
            removeAttribute() {},
            addEventListener(type, fn) {
                this.listeners.set(type, [...(this.listeners.get(type) ?? []), fn]);
            },
            dispatch(type) {
                for (const fn of this.listeners.get(type) ?? []) fn({ target: this, preventDefault() {} });
            },
            appendChild(child) {
                this.options.push(child);
            },
            focus() {},
            select() {},
            querySelectorAll() { return []; },
        };
        return el;
    };
    const getEl = (id: string) => {
        if (!elements.has(id)) elements.set(id, makeEl(id));
        return elements.get(id)!;
    };
    const posted: Array<Record<string, unknown>> = [];
    const msgListeners: Array<(ev: { data: unknown }) => void> = [];
    runInNewContext(script, {
        document: {
            visibilityState: 'visible',
            getElementById: getEl,
            createElement: () => makeEl(''),
            addEventListener: () => undefined,
            activeElement: null,
        },
        window: {
            addEventListener: (type: string, fn: (ev: { data: unknown }) => void) => {
                if (type === 'message') msgListeners.push(fn);
            },
        },
        setTimeout: () => 1,
        clearTimeout: () => undefined,
        acquireVsCodeApi: () => ({ postMessage: (m: Record<string, unknown>) => posted.push(m) }),
        URL,
        Number,
        String,
        Date,
    });
    const deliver = (data: unknown) => { for (const fn of msgListeners) fn({ data }); };

    deliver({
        type: 'init',
        state: {
            provider: 'antigravity',
            reviewModel: '',
            reviewModelCopilot: '',
            reviewModelAntigravity: 'gemini-3.1-pro-high',
            reviewEffort: 'high',
            githubBaseUrl: 'https://github.com',
            copilotInheritMcp: false,
            copilotAutoEnableMcpOnReview: false,
            copilotConfigDir: '',
            reviewFocusAreas: '',
            reviewCustomInstructions: '',
            reviewGuidanceGlobs: [],
            reviewGuidanceProfiles: [],
            activeReviewGuidanceProfileId: '',
            repositoryReviewInstructions: {},
            reviewSelfCritique: true,
            reviewSupervisorEnabled: true,
            reviewSecondReviewerModel: '',
            reviewSecondReviewerModelClaude: '',
            reviewSecondReviewerModelAntigravity: 'claude-sonnet-4-6',
            reviewSecondReviewerEffort: 'medium',
            reviewRulesDirectory: '',
            reviewBuiltInRules: true,
            experimentalIntellijAssistedReview: false,
            experimentalVsCodeCodeIntelligence: false,
            notificationsEnabled: false,
            notifyReviewRequested: true,
            notifyStarredRepos: false,
            notificationPollMinutes: 5,
            notificationHealth: EMPTY_NOTIFICATION_HEALTH,
        },
        copilotModels: ['claude-sonnet-4.6'],
        antigravityModels: ['gemini-3.1-pro-high', 'claude-sonnet-4-6'],
        refreshingModels: false,
        refreshingAntigravityModels: false,
    });

    assert.equal(getEl('antigravityModelField').hiddenClass, false);
    assert.equal(getEl('claudeModelField').hiddenClass, true);
    assert.equal(getEl('copilotModelField').hiddenClass, true);
    assert.equal(getEl('secondReviewerAntigravityModelField').hiddenClass, false);
    assert.equal(getEl('secondReviewerClaudeModelField').hiddenClass, true);
    assert.equal(getEl('secondReviewerCopilotModelField').hiddenClass, true);
    assert.equal(getEl('secondReviewerEffortField').hiddenClass, true);
    assert.equal(getEl('effortField').hiddenClass, true);
    assert.equal(getEl('copilotToolsSection').hiddenClass, true);
    assert.equal(getEl('antigravityModel').value, 'gemini-3.1-pro-high');
    assert.equal(getEl('reviewSecondReviewerModelAntigravity').value, 'claude-sonnet-4-6');

    getEl('antigravityModel').value = 'claude-sonnet-4-6';
    getEl('antigravityModel').dispatch('change');
    assert.equal(
        posted.some((m) => m.type === 'update' && m.key === 'reviewModelAntigravity' && m.value === 'claude-sonnet-4-6'),
        true,
    );

    getEl('refreshAntigravityModels').dispatch('click');
    assert.equal(getEl('refreshAntigravityModels').disabled, true);
    assert.equal(posted.some((m) => m.type === 'refreshAntigravityModels'), true);

    deliver({
        type: 'antigravityModels',
        ok: true,
        quiet: false,
        message: '2 models reported by Antigravity CLI.',
        antigravityModels: ['gemini-3-flash', 'claude-sonnet-4-6'],
    });
    assert.equal(getEl('refreshAntigravityModels').disabled, false);
    assert.equal(getEl('status').textContent, '2 models reported by Antigravity CLI.');
});
