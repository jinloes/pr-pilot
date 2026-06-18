/**
 * Pure view logic for the PR Pilot settings webview. This module intentionally imports nothing
 * from `vscode` so it can be unit-tested under node:test. The `vscode`-coupled controller lives in
 * settings.ts.
 *
 * This webview is the VS Code equivalent of the IntelliJ PluginSettingsComponent dialog: a
 * provider-aware settings surface that shows only the relevant model selector and offers a live
 * dropdown of discovered Copilot models. See AGENTS.md cross-host parity rules.
 */

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
    'claude-sonnet-4.6', 'claude-opus-4.7', 'gpt-5.5', 'gpt-5.4',
];

/** Reasoning-effort levels accepted by `copilot --reasoning-effort`. Mirrors COPILOT_EFFORTS. */
export const COPILOT_EFFORTS: string[] = ['none', 'low', 'medium', 'high', 'xhigh', 'max'];

export interface SettingsState {
    provider: Provider;
    reviewModel: string;
    reviewModelCopilot: string;
    reviewEffort: string;
    githubBaseUrl: string;
    copilotInheritMcp: boolean;
    copilotConfigDir: string;
    reviewFocusAreas: string;
    reviewCustomInstructions: string;
}

export function normalizeProvider(value: unknown): Provider {
    return value === 'copilot' ? 'copilot' : 'claude';
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

/** Escapes a string for safe interpolation into HTML text/attribute contexts. */
export function escapeHtml(value: string): string {
    return value
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

/**
 * Renders the settings webview HTML. The page is data-free at build time — current values and the
 * discovered model list are delivered via a postMessage `init` so the HTML carries no user data
 * (no injection surface) and the dropdown can refresh without reloading. `cspSource` is the
 * webview's `cspSource`; `nonce` gates the single inline script.
 */
export function buildSettingsHtml(cspSource: string, nonce: string): string {
    const claudeOptions = CLAUDE_MODEL_OPTIONS
        .map((o) => `<option value="${escapeHtml(o.id)}">${escapeHtml(o.label)}</option>`)
        .join('');
    const effortOptions = COPILOT_EFFORTS
        .map((e) => `<option value="${escapeHtml(e)}">${escapeHtml(e)}</option>`)
        .join('');

    return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src ${cspSource} 'nonce-${nonce}'; script-src 'nonce-${nonce}';">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>PR Pilot Settings</title>
<style nonce="${nonce}">
  body { font-family: var(--vscode-font-family); color: var(--vscode-foreground); padding: 16px 20px; font-size: 13px; }
  h1 { font-size: 16px; font-weight: 600; margin: 0 0 4px; }
  p.sub { color: var(--vscode-descriptionForeground); margin: 0 0 20px; }
  .field { margin-bottom: 18px; max-width: 560px; }
  label { display: block; font-weight: 600; margin-bottom: 4px; }
  .hint { color: var(--vscode-descriptionForeground); font-size: 12px; margin-top: 4px; }
  .status { min-height: 18px; margin: 0 0 14px; font-size: 12px; color: var(--vscode-descriptionForeground); }
  .status.ok { color: var(--vscode-testing-iconPassed); }
  .status.error { color: var(--vscode-errorForeground); }
  select, input[type=text], textarea {
    width: 100%; box-sizing: border-box; padding: 5px 8px; font-size: 13px;
    color: var(--vscode-input-foreground); background: var(--vscode-input-background);
    border: 1px solid var(--vscode-input-border, transparent); border-radius: 2px;
  }
  .row { display: flex; gap: 8px; align-items: center; }
  .row select, .row input { flex: 1; }
  button {
    padding: 5px 12px; font-size: 13px; cursor: pointer; border: none; border-radius: 2px;
    color: var(--vscode-button-foreground); background: var(--vscode-button-background);
  }
  button:hover { background: var(--vscode-button-hoverBackground); }
  button.secondary {
    color: var(--vscode-button-secondaryForeground); background: var(--vscode-button-secondaryBackground);
  }
  button.secondary:hover { background: var(--vscode-button-secondaryHoverBackground); }
  .hidden { display: none; }
  details.advanced {
    margin: 0 0 18px;
    max-width: 560px;
    border: 1px solid var(--vscode-panel-border, var(--vscode-input-border, transparent));
    border-radius: 4px;
    padding: 8px 10px;
  }
  details.advanced > summary {
    cursor: pointer;
    font-weight: 600;
    margin-bottom: 8px;
  }
  details.advanced > summary::marker {
    color: var(--vscode-descriptionForeground);
  }
  .advanced-fields { margin-top: 8px; }
</style>
</head>
<body>
  <h1>PR Pilot Settings</h1>
  <p class="sub">Changes are saved immediately to your User settings.</p>
  <div id="status" class="status" role="status" aria-live="polite"></div>

  <div class="field">
    <label for="provider">Review provider</label>
    <select id="provider">
      <option value="claude">Claude Code (claude CLI)</option>
      <option value="copilot">GitHub Copilot (copilot CLI)</option>
    </select>
    <div class="hint">Backend CLI used to generate reviews and chat replies.</div>
  </div>

  <div class="field" id="claudeModelField">
    <label for="claudeModel">Claude review model</label>
    <select id="claudeModel">${claudeOptions}</select>
    <div class="hint">Model ID for the Claude CLI. "CLI default" leaves it unset.</div>
  </div>

  <div class="field hidden" id="copilotModelField">
    <label for="copilotModel">Copilot review model</label>
    <div class="row">
      <select id="copilotModel"></select>
      <button id="refreshModels" class="secondary" title="Re-probe available models">Refresh</button>
    </div>
    <div class="hint">Pick a discovered model. Choose "CLI default" to use the Copilot CLI's own routing.</div>
  </div>

  <details id="advancedCopilot" class="advanced hidden">
    <summary>Advanced Copilot settings</summary>
    <div class="advanced-fields">
      <div class="field" id="effortField">
        <label for="effort">Reasoning effort (Copilot)</label>
        <select id="effort">${effortOptions}</select>
        <div class="hint">Higher = deeper review, slower. Applies only to GitHub Copilot.</div>
      </div>

      <div class="field" id="mcpField">
        <label><input type="checkbox" id="inheritMcp" style="width:auto;margin-right:6px;">Inherit MCP servers from the Copilot CLI config</label>
        <div class="hint">Gives the reviewer the same MCP tools as the <code>copilot</code> CLI — discovered from <code>~/.copilot/mcp-config.json</code> and any repo-local <code>.mcp.json</code>.</div>
        <input type="text" id="copilotConfigDir" placeholder="Config dir override (empty = ~/.copilot)" style="margin-top:8px;">
        <div class="hint">Optional override of the Copilot config directory used to discover MCP servers.</div>
      </div>
    </div>
  </details>

  <div class="field">
    <label for="baseUrl">GitHub base URL</label>
    <div class="row">
      <input type="text" id="baseUrl" placeholder="https://github.com">
      <button id="testConnection" class="secondary" title="Verify gh authentication for this host">Test</button>
    </div>
    <div class="hint">Change for GitHub Enterprise (e.g. https://github.mycompany.com).</div>
  </div>

  <div class="field">
    <label for="focusAreas">Review focus areas</label>
    <input type="text" id="focusAreas" placeholder="e.g. security, performance, test coverage">
    <div class="hint">Comma-separated areas the reviewer should prioritize. Sent to the model as steering context.</div>
  </div>

  <div class="field">
    <label for="customInstructions">Custom review instructions</label>
    <textarea id="customInstructions" rows="3" placeholder="Extra instructions appended to every review prompt (e.g. team conventions to enforce)."></textarea>
    <div class="hint">Plain text. Follow team conventions or emphasize specific concerns.</div>
  </div>

<script nonce="${nonce}">
  const vscode = acquireVsCodeApi();
  const $ = (id) => document.getElementById(id);
  const CLI_DEFAULT = '__cli_default__';
  let state = null;

  function setStatus(message, kind = '') {
    const el = $('status');
    el.textContent = message;
    el.className = kind ? 'status ' + kind : 'status';
  }

  function applyProviderVisibility(provider) {
    const isCopilot = provider === 'copilot';
    $('claudeModelField').classList.toggle('hidden', isCopilot);
    $('copilotModelField').classList.toggle('hidden', !isCopilot);
    $('advancedCopilot').classList.toggle('hidden', !isCopilot);
  }

  function renderCopilotModels(models, current) {
    const sel = $('copilotModel');
    sel.innerHTML = '';
    const def = document.createElement('option');
    def.value = CLI_DEFAULT;
    def.textContent = 'CLI default (unset)';
    sel.appendChild(def);
    for (const id of models) {
      const o = document.createElement('option');
      o.value = id;
      o.textContent = id;
      sel.appendChild(o);
    }
    // The host appends the currently saved ID to the option list when needed.
    sel.value = current || CLI_DEFAULT;
  }

  function copilotModelValue() {
    const sel = $('copilotModel').value;
    return sel === CLI_DEFAULT ? '' : sel;
  }

  function save(key, value) {
    if (key === 'githubBaseUrl' && value && !value.startsWith('https://')) {
      setStatus('GitHub base URL must start with https://', 'error');
      return;
    }
    setStatus('Saving…');
    vscode.postMessage({ type: 'update', key, value });
  }

  $('provider').addEventListener('change', () => {
    const p = $('provider').value;
    applyProviderVisibility(p);
    save('reviewProvider', p);
  });
  $('claudeModel').addEventListener('change', () => save('reviewModel', $('claudeModel').value));
  $('copilotModel').addEventListener('change', () => save('reviewModelCopilot', copilotModelValue()));
  $('effort').addEventListener('change', () => save('reviewEffort', $('effort').value));
  $('inheritMcp').addEventListener('change', () => save('copilotInheritMcp', $('inheritMcp').checked));
  $('copilotConfigDir').addEventListener('change', () => save('copilotConfigDir', $('copilotConfigDir').value.trim()));
  $('baseUrl').addEventListener('change', () => save('githubBaseUrl', $('baseUrl').value.trim()));
  $('focusAreas').addEventListener('change', () => save('reviewFocusAreas', $('focusAreas').value.trim()));
  $('customInstructions').addEventListener('change', () => save('reviewCustomInstructions', $('customInstructions').value.trim()));
  $('refreshModels').addEventListener('click', () => {
    $('refreshModels').textContent = 'Refreshing…';
    setStatus('Refreshing model list…');
    vscode.postMessage({ type: 'refreshModels' });
  });
  $('testConnection').addEventListener('click', () => {
    const value = $('baseUrl').value.trim();
    if (value && !value.startsWith('https://')) {
      setStatus('GitHub base URL must start with https://', 'error');
      return;
    }
    $('testConnection').textContent = 'Testing…';
    setStatus('Checking gh authentication…');
    vscode.postMessage({ type: 'testConnection', githubBaseUrl: value });
  });

  window.addEventListener('message', (event) => {
    const msg = event.data;
    if (msg.type === 'init') {
      state = msg.state;
      $('provider').value = state.provider;
      $('claudeModel').value = state.reviewModel;
      $('effort').value = state.reviewEffort;
      $('baseUrl').value = state.githubBaseUrl;
      $('inheritMcp').checked = state.copilotInheritMcp !== false;
      $('copilotConfigDir').value = state.copilotConfigDir || '';
      $('focusAreas').value = state.reviewFocusAreas || '';
      $('customInstructions').value = state.reviewCustomInstructions || '';
      renderCopilotModels(msg.copilotModels || [], state.reviewModelCopilot);
      applyProviderVisibility(state.provider);
    } else if (msg.type === 'models') {
      $('refreshModels').textContent = 'Refresh';
      renderCopilotModels(msg.copilotModels || [], copilotModelValue());
      setStatus(msg.ok === false ? (msg.message || 'Could not refresh models.') : 'Model list refreshed.', msg.ok === false ? 'error' : 'ok');
    } else if (msg.type === 'saveResult') {
      setStatus(msg.ok ? (msg.message || 'Saved.') : (msg.message || 'Could not save setting.'), msg.ok ? 'ok' : 'error');
    } else if (msg.type === 'testResult') {
      $('testConnection').textContent = 'Test';
      setStatus(msg.ok ? (msg.message || 'Connection looks good.') : (msg.message || 'Connection check failed.'), msg.ok ? 'ok' : 'error');
    }
  });

  vscode.postMessage({ type: 'ready' });
</script>
</body>
</html>`;
}
