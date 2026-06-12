import test from 'node:test';
import assert from 'node:assert/strict';

import {
    COPILOT_MODEL_SUGGESTIONS,
    buildSettingsHtml,
    escapeHtml,
    mergeCopilotModelOptions,
    normalizeProvider,
} from '../src/settingsView';

// ── normalizeProvider ─────────────────────────────────────────────────────────

test('normalizeProvider returns copilot only for the exact value', () => {
    assert.equal(normalizeProvider('copilot'), 'copilot');
    assert.equal(normalizeProvider('claude'), 'claude');
    assert.equal(normalizeProvider('anything-else'), 'claude');
    assert.equal(normalizeProvider(undefined), 'claude');
    assert.equal(normalizeProvider(42), 'claude');
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
    assert.match(html, /id="claudeModelField"/);
    assert.match(html, /id="copilotModelField"/);
    assert.match(html, /id="effortField"/);
    assert.match(html, /id="status"/);
    assert.match(html, /id="testConnection"/);
});

test('buildSettingsHtml lists the Claude model presets and effort levels', () => {
    const html = buildSettingsHtml('csp', 'n');
    assert.match(html, /claude-sonnet-4-6/);
    assert.match(html, /value="xhigh"/);
});

test('buildSettingsHtml validates GitHub base URLs before posting updates', () => {
    const html = buildSettingsHtml('csp', 'n');
    assert.match(html, /GitHub base URL must start with https:\/\//);
    assert.match(html, /type: 'testConnection'/);
});
