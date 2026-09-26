import test from 'node:test';
import assert from 'node:assert/strict';
import { runInNewContext } from 'node:vm';

import {
    COPILOT_MODEL_SUGGESTIONS,
    GITHUB_BASE_URL_ERROR,
    buildModelsMessage,
    buildSettingsHtml,
    escapeHtml,
    mergeCopilotModelOptions,
    normalizeGithubBaseUrl,
    normalizeProvider,
    profileNameError,
} from '../src/settingsView';

// ── normalizeProvider ─────────────────────────────────────────────────────────

test('normalizeProvider returns copilot only for the exact value', () => {
    assert.equal(normalizeProvider('copilot'), 'copilot');
    assert.equal(normalizeProvider('claude'), 'claude');
    assert.equal(normalizeProvider('anything-else'), 'claude');
    assert.equal(normalizeProvider(undefined), 'claude');
    assert.equal(normalizeProvider(42), 'claude');
});

test('normalizeGithubBaseUrl defaults blanks and canonicalizes HTTPS origins', () => {
    assert.equal(normalizeGithubBaseUrl(' '), 'https://github.com');
    assert.equal(normalizeGithubBaseUrl(' https://GITHUB.EXAMPLE.COM/// '), 'https://github.example.com');
});

test('normalizeGithubBaseUrl rejects non-origin and unsafe values', () => {
    for (const value of [
        'http://github.example.com',
        'https://user@github.example.com',
        'https://github.example.com/path',
        'https://github.example.com?query=1',
        'https://github.example.com#fragment',
        'https://github.example.com:',
        'https://github.example.com:8443',
        'https://github.example.com:65536',
        'not a url',
    ]) {
        assert.throws(() => normalizeGithubBaseUrl(value), /must be an HTTPS origin/);
    }
});

test('profileNameError rejects blank names and accepts a visible name', () => {
    assert.equal(profileNameError(' \t '), 'Enter a profile name.');
    assert.equal(profileNameError('Security review'), null);
});

// ── mergeCopilotModelOptions ──────────────────────────────────────────────────

test('mergeCopilotModelOptions uses discovered models when present', () => {
    const merged = mergeCopilotModelOptions(['gpt-5.5', 'claude-opus-4.7'], '');
    assert.deepEqual(merged, ['gpt-5.5', 'claude-opus-4.7']);
});

test('mergeCopilotModelOptions falls back to suggestions when discovery is empty', () => {
    const merged = mergeCopilotModelOptions([], '');
    assert.deepEqual(merged, COPILOT_MODEL_SUGGESTIONS);
});

test('mergeCopilotModelOptions appends a current value not already present', () => {
    const merged = mergeCopilotModelOptions(['gpt-5.5'], 'custom-model-x');
    assert.deepEqual(merged, ['gpt-5.5', 'custom-model-x']);
});

test('mergeCopilotModelOptions does not duplicate a current value already listed', () => {
    const merged = mergeCopilotModelOptions(['gpt-5.5', 'gpt-5.4'], 'gpt-5.4');
    assert.deepEqual(merged, ['gpt-5.5', 'gpt-5.4']);
});

test('mergeCopilotModelOptions excludes blank ids and de-dupes', () => {
    const merged = mergeCopilotModelOptions(['a', '  ', 'a', 'b'], '');
    assert.deepEqual(merged, ['a', 'b']);
});

// ── buildModelsMessage ────────────────────────────────────────────────────────

test('buildModelsMessage reports a successful refresh with the fresh list', () => {
    const msg = buildModelsMessage(['claude-opus-5.5', 'gpt-5.5'], ['old'], 'gpt-5.5', true);
    assert.deepEqual(msg, {
        type: 'models',
        ok: true,
        quiet: true,
        message: '2 models available to your Copilot account.',
        copilotModels: ['claude-opus-5.5', 'gpt-5.5'],
    });
});

test('buildModelsMessage keeps the last good list when a refresh fails', () => {
    const msg = buildModelsMessage([], ['m1', 'm2'], 'custom', false);
    assert.equal(msg.ok, false);
    assert.equal(msg.quiet, false);
    assert.match(msg.message, /last loaded list/);
    assert.deepEqual(msg.copilotModels, ['m1', 'm2', 'custom']);
});

test('buildModelsMessage falls back to suggestions when nothing has ever loaded', () => {
    const msg = buildModelsMessage([], null, '', true);
    assert.equal(msg.ok, false);
    assert.match(msg.message, /Showing suggestions/);
    assert.deepEqual(msg.copilotModels, COPILOT_MODEL_SUGGESTIONS);
});

// ── escapeHtml ────────────────────────────────────────────────────────────────

test('escapeHtml escapes all HTML-significant characters', () => {
    assert.equal(
        escapeHtml(`<script>"x" & 'y'</script>`),
        '&lt;script&gt;&quot;x&quot; &amp; &#39;y&#39;&lt;/script&gt;',
    );
});

// ── buildSettingsHtml ─────────────────────────────────────────────────────────

test('buildSettingsHtml embeds the nonce in the CSP and the inline script', () => {
    const html = buildSettingsHtml('vscode-resource:', 'NONCE123');
    assert.match(html, /script-src 'nonce-NONCE123'/);
    assert.match(html, /<script nonce="NONCE123">/);
});

test('buildSettingsHtml restricts default-src and includes the cspSource for styles', () => {
    const html = buildSettingsHtml('vscode-resource:', 'n');
    assert.match(html, /default-src 'none'/);
    assert.match(html, /style-src vscode-resource: 'nonce-n'/);
});

test('buildSettingsHtml renders both provider-specific model fields and the effort field', () => {
    const html = buildSettingsHtml('csp', 'n');
    assert.match(html, /Review provider/);
    assert.match(html, /GitHub connection/);
    assert.match(html, /Review guidance/);
    assert.match(html, /Notifications/);
    assert.match(html, /id="claudeModelField"/);
    assert.match(html, /id="copilotModelField"/);
    assert.match(html, /Advanced Copilot options/);
    assert.match(html, /id="effortField"/);
    assert.match(html, /id="status"/);
    assert.match(html, /id="testConnection"/);
});

test('buildSettingsHtml renders the Copilot MCP inheritance controls', () => {
    const html = buildSettingsHtml('csp', 'n');
    assert.match(html, /id="mcpField"/);
    assert.match(html, /id="inheritMcp"/);
    assert.match(html, /id="reviewAutoEnableMcp"/);
    assert.match(html, /id="copilotConfigDir"/);
    assert.match(html, /save\('copilotInheritMcp', \$\('inheritMcp'\)\.checked\)/);
    assert.match(html, /save\('copilotAutoEnableMcpOnReview', \$\('reviewAutoEnableMcp'\)\.checked\)/);
    assert.match(html, /save\('copilotConfigDir'/);
});

test('buildSettingsHtml renders the experimental IntelliJ-assisted toggle under Advanced review options', () => {
    const html = buildSettingsHtml('csp', 'n');
    assert.match(html, /section-title">Advanced review options<\/div>[\s\S]*id="experimentalIntellijAssistedReview"/);
    assert.match(html, /Turning this off also hides retained-worktree maintenance; turn it back on to remove retained worktrees\./);
    assert.match(html, /Enable IntelliJ-assisted review \(experimental\)/);
    assert.match(html, /save\('experimentalIntellijAssistedReview', \$\('experimentalIntellijAssistedReview'\)\.checked\)/);
    assert.match(html, /\$\('experimentalIntellijAssistedReview'\)\.checked = state\.experimentalIntellijAssistedReview === true/);
});

test('buildSettingsHtml renders reusable review-guidance profile controls', () => {
    const html = buildSettingsHtml('csp', 'n');
    assert.match(html, /id="guidanceProfile"/);
    assert.match(html, /id="addGuidanceProfile"/);
    assert.match(html, /id="renameGuidanceProfile"/);
    assert.match(html, /id="deleteGuidanceProfile"/);
    assert.match(html, /id="reviewSelfCritique"/);
    assert.match(html, /id="reviewSupervisorEnabled"/);
    assert.match(html, /type: 'updateReviewGuidanceState'/);
    assert.match(html, /profiles: guidanceProfiles/);
    assert.match(html, /activeProfileId: activeGuidanceProfileId/);
    assert.match(html, /msg\.requestId !== latestSaveRequestId/);
    assert.doesNotMatch(html, /save\('activeReviewGuidanceProfileId'/);
    assert.doesNotMatch(html, /id="guidanceGlobs"/);
    assert.doesNotMatch(html, /guidance-file selections remain inactive/);
    assert.match(html, /profile \? profile\.guidanceGlobs/);
    assert.match(html, /id="profileNameDialog".*role="dialog"/);
    assert.match(html, /id="deleteProfileDialog".*role="alertdialog"/);
    assert.match(html, /aria-modal="true"/);
    assert.match(html, /profileNameInput'\)\.focus\(\)/);
    assert.match(html, /id="profileNameError".*role="alert"/);
    assert.match(html, /aria-describedby="profileNameError"/);
    assert.match(html, /setAttribute\('aria-invalid', 'true'\)/);
    assert.match(html, /Enter a profile name\./);
    assert.match(html, /function profileNameError\(value\)\s*\{/);
    assert.match(html, /const nameError = profileNameError\(input\.value\)/);
    assert.match(html, /addEventListener\('input', clearProfileNameError\)/);
    assert.match(html, /showProfileNameError\(nameError\);\s+return;/);
    assert.match(html, /event\.key === 'Escape'/);
    assert.match(html, /event\.key !== 'Tab'/);
    assert.match(html, /profileDialogReturnFocus\.focus\(\)/);
    assert.doesNotMatch(html, /window\.(?:prompt|confirm|alert)\(/);
});

test('buildSettingsHtml exposes notification health, retry, and dependent controls', () => {
    const html = buildSettingsHtml('csp', 'n');

    assert.match(html, /id="notificationHealth"/);
    assert.match(html, /id="retryNotifications"/);
    assert.match(html, /type: 'retryNotifications'/);
    assert.match(html, /applyNotificationVisibility\(state\.notificationsEnabled\)/);
    assert.match(html, /Notifications are partially working:/);
    assert.match(html, /Notification polling failed:/);
    assert.match(html, /input\[type=number\]/);
    assert.match(html, /id="notificationPollMinutes" class="short-field" min="1" max="60" step="1"/);
    assert.match(html, /\.row input\.short-field, input\.short-field \{ width: 88px; flex: 0 0 88px; \}/);
});

test('buildSettingsHtml gives disabled settings actions a distinct non-interactive state', () => {
    const html = buildSettingsHtml('csp', 'n');

    assert.match(html, /button:disabled \{\s+cursor: not-allowed; opacity: \.6;/);
    assert.match(html, /button\.secondary:disabled:hover \{ background: var\(--vscode-button-secondaryBackground\); \}/);
    assert.match(html, /\$\('renameGuidanceProfile'\)\.disabled = !named/);
    assert.match(html, /\$\('deleteGuidanceProfile'\)\.disabled = !named/);
});

test('buildSettingsHtml lists the Claude model presets and effort levels', () => {
    const html = buildSettingsHtml('csp', 'n');
    assert.match(html, /claude-sonnet-4-6/);
    assert.match(html, /value="xhigh"/);
});

test('buildSettingsHtml validates GitHub base URLs before posting updates', () => {
    const html = buildSettingsHtml('csp', 'n');
    assert.match(html, /GitHub base URL must be an HTTPS origin/);
    assert.match(html, /type: 'testConnection'/);
});

// ── Cross-host vocabulary and order ───────────────────────────────────────────

test('buildSettingsHtml uses the shared section order', () => {
    const html = buildSettingsHtml('csp', 'n');
    const titles = [...html.matchAll(/<div class="section-title">([^<]+)<\/div>/g)].map((match) => match[1]);
    assert.deepEqual(titles, [
        'GitHub connection',
        'Review provider',
        'Review guidance',
        'Review validation',
        'Advanced review options',
        'Notifications',
    ]);
});

test('buildSettingsHtml uses the shared labels for settings both hosts expose', () => {
    const html = buildSettingsHtml('csp', 'n');
    assert.match(html, /id="inheritMcp"[^>]*>Allow Copilot to use MCP tools from your trusted Copilot config<\/label>/);
    assert.match(html, /Applies while reviewing untrusted pull request content\. Servers load only from your own Copilot config \(<code>~\/\.copilot\/mcp-config\.json<\/code>\); a pull request's <code>\.mcp\.json<\/code> is never loaded\./);
    assert.match(html, /id="reviewSelfCritique"[^>]*>Validate findings with a second pass<\/label>/);
    assert.match(html, /id="notifyStarredRepos"[^>]*>Notify for new PRs in starred repositories<\/label>/);
    assert.match(html, /<button id="addGuidanceProfile" class="secondary">Save as…<\/button>/);
    assert.match(html, /<button id="testConnection"[^>]*>Check connection<\/button>/);
    assert.match(html, /\$\('testConnection'\)\.textContent = 'Check connection'/);
    assert.match(html, /Enable background PR notifications<\/label>/);
    for (const old of ['Review backend', 'Review defaults', 'Save current as…', 'Run a self-critique validation pass',
        'Allow MCP tools from your trusted Copilot config', '>Test<']) {
        assert.equal(html.includes(old), false, `unexpected legacy label ${old}`);
    }
});

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
    classList: { toggle: () => void; add: () => void; remove: () => void };
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
        style: {}, attributes: new Map(), listeners: new Map(),
        classList: { toggle: () => undefined, add: () => undefined, remove: () => undefined },
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
        window: { addEventListener: () => undefined },
        acquireVsCodeApi: () => ({ postMessage: (message: Record<string, unknown>) => posted.push(message) }),
        URL,
        Number,
        String,
        Date,
    };
    runInNewContext(script, context);
    return { element, posted };
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
