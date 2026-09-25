import test from 'node:test';
import assert from 'node:assert/strict';

import {
    COPILOT_MODEL_SUGGESTIONS,
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
    assert.match(html, /Review backend/);
    assert.match(html, /GitHub connection/);
    assert.match(html, /Review defaults/);
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
    assert.match(html, /<summary>Advanced review options<\/summary>[\s\S]*id="experimentalIntellijAssistedReview"/);
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
