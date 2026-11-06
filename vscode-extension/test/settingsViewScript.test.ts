import test from 'node:test';
import assert from 'node:assert/strict';
import { runInNewContext } from 'node:vm';
import { EMPTY_NOTIFICATION_HEALTH } from '../src/notifications';
import { GITHUB_BASE_URL_ERROR } from '../src/settingsView';
import { buildSettingsHtml } from '../src/settingsViewHtml';

// ── Base URL field validation ─────────────────────────────────────────────────

interface FakeElement {
    id: string;
    value: string;
    checked: boolean;
    disabled: boolean;
    hidden: boolean;
    textContent: string;
    innerHTML: string;
    className: string;
    style: Record<string, string>;
    attributes: Map<string, string>;
    listeners: Map<string, Array<(event: Record<string, unknown>) => void>>;
    classList: { toggle: (name: string, force?: boolean) => void; add: () => void; remove: () => void };
    hiddenClass: boolean;
    setAttribute(name: string, value: string): void;
    removeAttribute(name: string): void;
    getAttribute(name: string): string | null;
    addEventListener(type: string, listener: (event: Record<string, unknown>) => void): void;
    dispatch(type: string): void;
    appendChild(): void;
    focus(): void;
    select(): void;
    querySelectorAll(): never[];
}

function fakeElement(id: string): FakeElement {
    const element: FakeElement = {
        id, value: '', checked: false, disabled: false, hidden: false, textContent: '', innerHTML: '', className: '',
        style: {}, attributes: new Map(), listeners: new Map(), hiddenClass: false,
        classList: {
            toggle: (name: string, force?: boolean) => {
                if (name === 'hidden') element.hiddenClass = force ?? !element.hiddenClass;
            },
            add: () => undefined,
            remove: () => undefined,
        },
        setAttribute(name, value) { this.attributes.set(name, value); },
        removeAttribute(name) { this.attributes.delete(name); },
        getAttribute(name) { return this.attributes.get(name) ?? null; },
        addEventListener(type, listener) {
            this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener]);
        },
        dispatch(type) {
            for (const listener of this.listeners.get(type) ?? []) listener({ target: this, preventDefault() {} });
        },
        appendChild() {},
        focus() {},
        select() {},
        querySelectorAll() { return []; },
    };
    return element;
}

function runSettingsScript() {
    const html = buildSettingsHtml('csp', 'n');
    const script = /<script nonce="n">([\s\S]*)<\/script>/.exec(html)?.[1];
    assert.ok(script, 'settings script not found');
    const elements = new Map<string, FakeElement>();
    const posted: Array<Record<string, unknown>> = [];
    const messageListeners: Array<(event: { data: unknown }) => void> = [];
    const element = (id: string) => {
        if (!elements.has(id)) elements.set(id, fakeElement(id));
        return elements.get(id)!;
    };
    const context = {
        document: {
            getElementById: element,
            createElement: () => fakeElement(''),
            addEventListener: () => undefined,
            activeElement: null,
        },
        window: {
            addEventListener: (type: string, listener: (event: { data: unknown }) => void) => {
                if (type === 'message') messageListeners.push(listener);
            },
        },
        acquireVsCodeApi: () => ({ postMessage: (message: Record<string, unknown>) => posted.push(message) }),
        URL,
        Number,
        String,
        Date,
    };
    runInNewContext(script, context);
    const deliver = (data: unknown) => { for (const listener of messageListeners) listener({ data }); };
    return { element, posted, deliver };
}

test('an invalid base URL shows an error attached to its field and is never saved', () => {
    const { element, posted } = runSettingsScript();
    const input = element('baseUrl');
    const error = element('baseUrlError');

    input.value = 'http://x';
    input.dispatch('change');

    assert.equal(error.hidden, false);
    assert.equal(error.textContent, GITHUB_BASE_URL_ERROR);
    assert.equal(input.getAttribute('aria-invalid'), 'true');
    assert.equal(input.getAttribute('aria-describedby'), 'baseUrlError');
    assert.notEqual(element('status').textContent, GITHUB_BASE_URL_ERROR);
    assert.equal(posted.some((message) => message.type === 'update'), false);

    input.dispatch('input');
    assert.equal(error.hidden, true);
    assert.equal(error.textContent, '');
    assert.equal(input.getAttribute('aria-invalid'), null);
    assert.equal(input.getAttribute('aria-describedby'), null);
});

test('Check connection validates the base URL field before contacting the host', () => {
    const { element, posted } = runSettingsScript();
    const input = element('baseUrl');

    input.value = 'https://github.example.com/path';
    element('testConnection').dispatch('click');
    assert.equal(element('baseUrlError').hidden, false);
    assert.equal(input.getAttribute('aria-invalid'), 'true');
    assert.equal(posted.some((message) => message.type === 'testConnection'), false);

    input.value = 'https://github.example.com/';
    element('testConnection').dispatch('click');
    assert.equal(element('baseUrlError').hidden, true);
    assert.equal(input.getAttribute('aria-invalid'), null);
    assert.equal(element('testConnection').textContent, 'Checking…');
    assert.equal(JSON.stringify(posted.filter((message) => message.type === 'testConnection')),
        JSON.stringify([{ type: 'testConnection', githubBaseUrl: 'https://github.example.com' }]));
});

test('a valid base URL clears a previous field error and saves the normalized origin', () => {
    const { element, posted } = runSettingsScript();
    const input = element('baseUrl');
    input.value = 'nope';
    input.dispatch('change');

    input.value = 'https://GITHUB.example.com//';
    input.dispatch('change');

    assert.equal(element('baseUrlError').hidden, true);
    assert.equal(input.getAttribute('aria-invalid'), null);
    const update = posted.find((message) => message.type === 'update');
    assert.equal(update?.key, 'githubBaseUrl');
    assert.equal(update?.value, 'https://github.example.com');
});

test('the base URL error sits directly below its input row', () => {
    const html = buildSettingsHtml('csp', 'n');
    assert.match(html, /<input type="text" id="baseUrl"[^>]*>\s*<button id="testConnection"[^>]*>Check connection<\/button>\s*<\/div>\s*<p id="baseUrlError" class="field-error" role="alert" hidden><\/p>/);
});

// ── Remembered repository instructions ───────────────────────────────────────

function initState(repositoryReviewInstructions: Record<string, string>): Record<string, unknown> {
    return {
        provider: 'claude', reviewModel: '', reviewModelCopilot: '', reviewEffort: 'high',
        githubBaseUrl: 'https://github.com', copilotInheritMcp: false, copilotAutoEnableMcpOnReview: false,
        copilotConfigDir: '', reviewFocusAreas: '', reviewCustomInstructions: '', reviewGuidanceGlobs: [],
        reviewGuidanceProfiles: [], activeReviewGuidanceProfileId: '', repositoryReviewInstructions,
        reviewSelfCritique: true, reviewSupervisorEnabled: true, reviewSecondReviewerModel: '',
        reviewSecondReviewerEffort: 'medium', reviewRulesDirectory: '',
        reviewBuiltInRules: true,
        experimentalIntellijAssistedReview: false,
        experimentalVsCodeCodeIntelligence: false,
        notificationsEnabled: false, notifyReviewRequested: true, notifyStarredRepos: false,
        notificationPollMinutes: 5, notificationHealth: EMPTY_NOTIFICATION_HEALTH,
    };
}

function postedUpdates(posted: Array<Record<string, unknown>>) {
    return JSON.parse(JSON.stringify(posted.filter((message) => message.type === 'update'
        && message.key === 'repositoryReviewInstructions').map((message) => message.value))) as unknown[];
}

test('the second reviewer effort row hides while the second reviewer is Off', () => {
    const { element, posted, deliver } = runSettingsScript();
    deliver({ type: 'init', state: { ...initState({}), reviewSecondReviewerModel: 'gemini-4-argon' } });
    const effortRow = element('secondReviewerEffortField');
    assert.equal(effortRow.hiddenClass, false);

    const model = element('reviewSecondReviewerModel');
    model.value = '__cli_default__';
    model.dispatch('change');
    assert.equal(effortRow.hiddenClass, true);
    const saved = posted.filter((message) => message.type === 'update' && message.key === 'reviewSecondReviewerModel');
    assert.equal(JSON.stringify(saved.map((message) => message.value)), JSON.stringify(['']));

    model.value = 'gpt-6.1-sol';
    model.dispatch('change');
    assert.equal(effortRow.hiddenClass, false);
});

test('Copilot-only rows follow the provider', () => {
    const { element, deliver } = runSettingsScript();
    deliver({ type: 'init', state: { ...initState({}), provider: 'claude' } });
    assert.equal(element('effortField').hiddenClass, true);
    assert.equal(element('copilotToolsSection').hiddenClass, true);
    assert.equal(element('claudeModelField').hiddenClass, false);

    deliver({ type: 'init', state: { ...initState({}), provider: 'copilot' } });
    assert.equal(element('effortField').hiddenClass, false);
    assert.equal(element('copilotToolsSection').hiddenClass, false);
    assert.equal(element('claudeModelField').hiddenClass, true);
});

test('the second reviewer effort loads from state, defaults to medium, and saves its setting', () => {
    const { element, posted, deliver } = runSettingsScript();
    deliver({ type: 'init', state: { ...initState({}), reviewSecondReviewerEffort: 'low' } });
    const select = element('reviewSecondReviewerEffort');
    assert.equal(select.value, 'low');

    deliver({ type: 'init', state: { ...initState({}), reviewSecondReviewerEffort: '' } });
    assert.equal(select.value, 'medium');

    select.value = 'high';
    select.dispatch('change');
    const updates = posted.filter((message) => message.type === 'update' && message.key === 'reviewSecondReviewerEffort');
    assert.equal(JSON.stringify(updates.map((message) => message.value)), JSON.stringify(['high']));
});

test('the VS Code code-intelligence toggle loads from state and saves its setting', () => {
    const { element, posted, deliver } = runSettingsScript();
    deliver({ type: 'init', state: { ...initState({}), experimentalVsCodeCodeIntelligence: true } });
    const toggle = element('vscodeCodeIntelligence');
    assert.equal(toggle.checked, true);

    toggle.checked = false;
    toggle.dispatch('change');

    const updates = posted.filter((message) => message.type === 'update' && message.key === 'experimentalVsCodeCodeIntelligence');
    assert.equal(JSON.stringify(updates.map((message) => message.value)), JSON.stringify([false]));
});

test('the guidance files field loads and saves the default glob list one entry per line', () => {
    const { element, posted, deliver } = runSettingsScript();
    deliver({ type: 'init', state: { ...initState({}), reviewGuidanceGlobs: ['.review/ai-agent/*.md', 'docs/review.md'] } });
    assert.equal(element('guidanceGlobs').value, '.review/ai-agent/*.md\ndocs/review.md');

    element('guidanceGlobs').value = '  .review/ai-agent/proto-api-guide.md \n\n docs/review.md\n';
    element('guidanceGlobs').dispatch('change');

    const update = posted.filter((message) => message.type === 'update' && message.key === 'reviewGuidanceGlobs');
    assert.deepEqual(JSON.parse(JSON.stringify(update.map((message) => message.value))),
        [['.review/ai-agent/proto-api-guide.md', 'docs/review.md']]);
});

test('the guidance files field edits the active profile instead of the defaults', () => {
    const { element, posted, deliver } = runSettingsScript();
    const profile = { id: 'p1', name: 'Proto', focusAreas: '', customInstructions: '', guidanceGlobs: ['a.md'] };
    deliver({ type: 'init', state: {
        ...initState({}), reviewGuidanceGlobs: ['default.md'], reviewGuidanceProfiles: [profile],
        activeReviewGuidanceProfileId: 'p1',
    } });
    assert.equal(element('guidanceGlobs').value, 'a.md');

    element('guidanceGlobs').value = 'a.md\nb.md';
    element('guidanceGlobs').dispatch('change');

    assert.equal(posted.some((message) => message.type === 'update' && message.key === 'reviewGuidanceGlobs'), false);
    const saved = posted.filter((message) => message.type === 'updateReviewGuidanceState');
    const profiles = JSON.parse(JSON.stringify(saved[saved.length - 1].profiles)) as Array<{ guidanceGlobs: string[] }>;
    assert.deepEqual(profiles[0].guidanceGlobs, ['a.md', 'b.md']);

    element('guidanceProfile').value = '';
    element('guidanceProfile').dispatch('change');
    assert.equal(element('guidanceGlobs').value, 'default.md');
});

test('buildSettingsHtml renders the remembered repository instructions editor under Review guidance', () => {
    const html = buildSettingsHtml('csp', 'n');
    const guidance = html.slice(html.indexOf('>Review guidance<'), html.indexOf('>Review validation<'));
    assert.match(guidance, /<label for="repositoryInstructionsRepo">Remembered repository instructions<\/label>/);
    assert.match(guidance, /<textarea id="repositoryInstructionsText"[^>]*maxlength="10000"/);
    assert.match(guidance, /<button id="forgetRepositoryInstructions"[^>]*>Forget<\/button>/);
});

test('the repository instructions editor disables itself when nothing is remembered', () => {
    const { element, deliver } = runSettingsScript();
    deliver({ type: 'init', state: initState({}) });
    assert.equal(element('repositoryInstructionsRepo').disabled, true);
    assert.equal(element('repositoryInstructionsText').disabled, true);
    assert.equal(element('forgetRepositoryInstructions').disabled, true);
    assert.equal(element('repositoryInstructionsText').value, '');
});

test('the repository instructions editor edits, clears, and forgets the selected repository', () => {
    const { element, posted, deliver } = runSettingsScript();
    deliver({ type: 'init', state: initState({ 'acme/widget': 'Rule A', 'acme/api': 'Rule B' }) });
    assert.equal(element('repositoryInstructionsRepo').value, 'acme/api');
    assert.equal(element('repositoryInstructionsText').value, 'Rule B');
    assert.equal(element('repositoryInstructionsText').disabled, false);

    element('repositoryInstructionsRepo').value = 'acme/widget';
    element('repositoryInstructionsRepo').dispatch('change');
    assert.equal(element('repositoryInstructionsText').value, 'Rule A');

    element('repositoryInstructionsText').value = '  Rule A2  ';
    element('repositoryInstructionsText').dispatch('change');
    element('forgetRepositoryInstructions').dispatch('click');
    assert.deepEqual(postedUpdates(posted), [
        { 'acme/widget': 'Rule A2', 'acme/api': 'Rule B' },
        { 'acme/api': 'Rule B' },
    ]);
    assert.equal(element('repositoryInstructionsRepo').value, 'acme/api');

    element('repositoryInstructionsText').value = '   ';
    element('repositoryInstructionsText').dispatch('change');
    const updates = postedUpdates(posted);
    assert.deepEqual(updates[updates.length - 1], {});
    assert.equal(element('repositoryInstructionsText').disabled, true);
});
