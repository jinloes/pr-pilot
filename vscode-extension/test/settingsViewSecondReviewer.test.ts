import test from 'node:test';
import assert from 'node:assert/strict';
import { runInNewContext } from 'node:vm';
import { EMPTY_NOTIFICATION_HEALTH } from '../src/notifications';
import { buildSettingsHtml } from '../src/settingsViewHtml';

interface FakeSelectElement {
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
    options: Array<{ value: string; textContent: string }>;
    listeners: Map<string, Array<(event: Record<string, unknown>) => void>>;
    classList: { toggle: (name: string, force?: boolean) => void };
    setAttribute: () => void;
    removeAttribute: () => void;
    addEventListener: (type: string, listener: (event: Record<string, unknown>) => void) => void;
    dispatch: (type: string) => void;
    appendChild: (child: { value: string; textContent: string }) => void;
    focus: () => void;
    select: () => void;
    querySelectorAll: () => never[];
}

function runSecondReviewerHarness() {
    const html = buildSettingsHtml('csp', 'n');
    const script = /<script nonce="n">([\s\S]*)<\/script>/.exec(html)?.[1];
    assert.ok(script, 'settings script not found');
    const elements = new Map<string, FakeSelectElement>();
    const makeEl = (id: string): FakeSelectElement => {
        const el: FakeSelectElement = {
            id, value: '', checked: false, disabled: false, hidden: false, textContent: '', innerHTML: '',
            className: '', style: {}, hiddenClass: false, options: [], listeners: new Map(),
            classList: {
                toggle: (name, force) => {
                    if (name === 'hidden') el.hiddenClass = force ?? !el.hiddenClass;
                },
            },
            setAttribute() {},
            removeAttribute() {},
            addEventListener(type, listener) {
                this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener]);
            },
            dispatch(type) {
                for (const listener of this.listeners.get(type) ?? []) listener({ target: this, preventDefault() {} });
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
    const element = (id: string) => {
        if (!elements.has(id)) elements.set(id, makeEl(id));
        return elements.get(id)!;
    };
    const posted: Array<Record<string, unknown>> = [];
    const messageListeners: Array<(event: { data: unknown }) => void> = [];
    runInNewContext(script, {
        document: {
            visibilityState: 'visible',
            getElementById: element,
            createElement: () => makeEl(''),
            addEventListener: () => undefined,
            activeElement: null,
        },
        window: {
            addEventListener: (type: string, listener: (event: { data: unknown }) => void) => {
                if (type === 'message') messageListeners.push(listener);
            },
        },
        setTimeout: () => 1,
        clearTimeout: () => undefined,
        acquireVsCodeApi: () => ({ postMessage: (message: Record<string, unknown>) => posted.push(message) }),
        URL,
        Number,
        String,
        Date,
    });
    const deliver = (data: unknown) => { for (const listener of messageListeners) listener({ data }); };
    return { element, posted, deliver };
}

function baseState(): Record<string, unknown> {
    return {
        provider: 'claude',
        reviewModel: '',
        reviewModelCopilot: '',
        reviewModelAntigravity: '',
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
        reviewSecondReviewerModel: 'gpt-5.4',
        reviewSecondReviewerModelClaude: 'claude-sonnet-4-6',
        reviewSecondReviewerModelAntigravity: 'gemini-3.1-pro-high',
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
    };
}

test('switching providers switches the visible second-reviewer model picker and preserves saved per-provider values', () => {
    const { element, posted, deliver } = runSecondReviewerHarness();
    deliver({
        type: 'init',
        state: baseState(),
        copilotModels: ['gpt-5.4', 'claude-opus-4.6'],
        antigravityModels: ['gemini-3.1-pro-high', 'gemini-3-flash'],
    });

    // Initial provider is Claude: Claude second reviewer shown, effort hidden even with a Claude model chosen.
    assert.equal(element('secondReviewerClaudeModelField').hiddenClass, false);
    assert.equal(element('secondReviewerCopilotModelField').hiddenClass, true);
    assert.equal(element('secondReviewerAntigravityModelField').hiddenClass, true);
    assert.equal(element('secondReviewerEffortField').hiddenClass, true);
    assert.equal(element('reviewSecondReviewerModelClaude').value, 'claude-sonnet-4-6');

    // Switch to Copilot: Copilot second reviewer + effort shown with its saved value intact.
    element('provider').value = 'copilot';
    element('provider').dispatch('change');
    assert.equal(element('secondReviewerClaudeModelField').hiddenClass, true);
    assert.equal(element('secondReviewerCopilotModelField').hiddenClass, false);
    assert.equal(element('secondReviewerAntigravityModelField').hiddenClass, true);
    assert.equal(element('secondReviewerEffortField').hiddenClass, false);
    assert.equal(element('reviewSecondReviewerModel').value, 'gpt-5.4');

    // Switch to Antigravity: Antigravity second reviewer shown, effort hidden even with an Antigravity model chosen.
    element('provider').value = 'antigravity';
    element('provider').dispatch('change');
    assert.equal(element('secondReviewerClaudeModelField').hiddenClass, true);
    assert.equal(element('secondReviewerCopilotModelField').hiddenClass, true);
    assert.equal(element('secondReviewerAntigravityModelField').hiddenClass, false);
    assert.equal(element('secondReviewerEffortField').hiddenClass, true);
    assert.equal(element('reviewSecondReviewerModelAntigravity').value, 'gemini-3.1-pro-high');

    // Saving Claude and Antigravity second-reviewer selections writes to their dedicated keys.
    element('reviewSecondReviewerModelAntigravity').value = '__cli_default__';
    element('reviewSecondReviewerModelAntigravity').dispatch('change');
    element('provider').value = 'claude';
    element('provider').dispatch('change');
    element('reviewSecondReviewerModelClaude').value = 'claude-haiku-4-5';
    element('reviewSecondReviewerModelClaude').dispatch('change');

    const updates = posted
        .filter((m) => m.type === 'update' && String(m.key).startsWith('reviewSecondReviewerModel'))
        .map((m) => ({ key: m.key, value: m.value }));
    assert.deepEqual(updates, [
        { key: 'reviewSecondReviewerModelAntigravity', value: '' },
        { key: 'reviewSecondReviewerModelClaude', value: 'claude-haiku-4-5' },
    ]);
});
