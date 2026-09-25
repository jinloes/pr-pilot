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
    reviewSelfCritique: boolean;
    reviewSupervisorEnabled: boolean;
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
  p.sub { color: var(--vscode-descriptionForeground); margin: 0 0 18px; }
  .section { margin: 0 0 18px; max-width: 560px; }
  .section-title { font-size: 12px; font-weight: 700; text-transform: uppercase; letter-spacing: .03em; color: var(--vscode-descriptionForeground); margin: 0 0 10px; }
  .field { margin-bottom: 14px; }
  label { display: block; font-weight: 600; margin-bottom: 4px; }
  .hint { color: var(--vscode-descriptionForeground); font-size: 12px; margin-top: 4px; }
  .status { min-height: 18px; margin: 0 0 14px; font-size: 12px; color: var(--vscode-descriptionForeground); }
  .status.ok { color: var(--vscode-testing-iconPassed); }
  .status.error { color: var(--vscode-errorForeground); }
  select, input[type=text], input[type=number], textarea {
    width: 100%; box-sizing: border-box; padding: 5px 8px; font-size: 13px;
    color: var(--vscode-input-foreground); background: var(--vscode-input-background);
    border: 1px solid var(--vscode-input-border, transparent); border-radius: 2px;
  }
  select:focus-visible, input:focus-visible, textarea:focus-visible {
    outline: 1px solid var(--vscode-focusBorder); outline-offset: -1px;
  }
  .row { display: flex; gap: 8px; align-items: center; }
  .row select, .row input { flex: 1; }
  .row.wrap { flex-wrap: wrap; }
  .row input.short-field, input.short-field { width: 88px; flex: 0 0 88px; }
  button {
    padding: 5px 12px; font-size: 13px; cursor: pointer; border: none; border-radius: 2px;
    color: var(--vscode-button-foreground); background: var(--vscode-button-background);
  }
  button:hover { background: var(--vscode-button-hoverBackground); }
  button.secondary {
    color: var(--vscode-button-secondaryForeground); background: var(--vscode-button-secondaryBackground);
  }
  button.secondary:hover { background: var(--vscode-button-secondaryHoverBackground); }
  button:disabled {
    cursor: not-allowed; opacity: .6;
  }
  button:disabled:hover { background: var(--vscode-button-background); }
  button.secondary:disabled:hover { background: var(--vscode-button-secondaryBackground); }
  .field-error { margin: 4px 0 0; color: var(--vscode-errorForeground); font-size: 12px; }
  input[aria-invalid=true] { border-color: var(--vscode-inputValidation-errorBorder, var(--vscode-errorForeground)); }
  .hidden { display: none; }
  .modal[hidden] { display: none; }
  .modal {
    position: fixed; inset: 0; z-index: 10; display: grid; place-items: center;
    padding: 20px; background: rgba(0, 0, 0, .45);
  }
  .modal-content {
    width: min(420px, 100%); box-sizing: border-box; padding: 18px;
    color: var(--vscode-foreground); background: var(--vscode-editor-background);
    border: 1px solid var(--vscode-panel-border, var(--vscode-input-border, transparent));
    border-radius: 4px; box-shadow: 0 8px 24px rgba(0, 0, 0, .3);
  }
  .modal-content h2 { margin: 0 0 10px; font-size: 15px; }
  .modal-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px; }
  details.advanced {
    margin: 0 0 8px;
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
  <p class="sub">Changes are saved immediately to your User settings. Configure review provider, model, MCP access, and notifications from one page.</p>
  <div id="status" class="status" role="status" aria-live="polite"></div>

  <div id="profileNameDialog" class="modal" role="dialog" aria-modal="true" aria-labelledby="profileNameDialogTitle" hidden>
    <div class="modal-content">
      <h2 id="profileNameDialogTitle">Save guidance profile</h2>
      <label for="profileNameInput">Profile name</label>
      <input type="text" id="profileNameInput" autocomplete="off" aria-describedby="profileNameError">
      <p id="profileNameError" class="field-error" role="alert" aria-live="assertive" hidden></p>
      <div class="modal-actions">
        <button id="cancelProfileName" class="secondary" type="button">Cancel</button>
        <button id="confirmProfileName" type="button">Save</button>
      </div>
    </div>
  </div>

  <div id="deleteProfileDialog" class="modal" role="alertdialog" aria-modal="true" aria-labelledby="deleteProfileDialogTitle" aria-describedby="deleteProfileDialogDescription" hidden>
    <div class="modal-content">
      <h2 id="deleteProfileDialogTitle">Delete guidance profile?</h2>
      <p id="deleteProfileDialogDescription"></p>
      <div class="modal-actions">
        <button id="cancelDeleteProfile" class="secondary" type="button">Cancel</button>
        <button id="confirmDeleteProfile" type="button">Delete</button>
      </div>
    </div>
  </div>

  <div class="section">
    <div class="section-title">Review backend</div>

    <div class="field">
      <label for="provider">Provider</label>
      <select id="provider">
        <option value="claude">Claude Code (claude CLI)</option>
        <option value="copilot">GitHub Copilot (copilot CLI)</option>
      </select>
      <div class="hint">Backend CLI used to generate reviews and chat replies.</div>
    </div>

    <div class="field" id="claudeModelField">
      <label for="claudeModel">Claude model</label>
      <select id="claudeModel">${claudeOptions}</select>
      <div class="hint">Model ID for the Claude CLI. "CLI default" leaves it unset.</div>
    </div>

    <div class="field hidden" id="copilotModelField">
      <label for="copilotModel">Copilot model</label>
      <div class="row">
        <select id="copilotModel"></select>
        <button id="refreshModels" class="secondary" title="Reload the models available to your Copilot account">Refresh</button>
      </div>
      <div class="hint">Pick a discovered model. Choose "CLI default" to use the Copilot CLI's own routing.</div>
    </div>

    <details id="advancedCopilot" class="advanced hidden">
      <summary>Advanced Copilot options</summary>
      <div class="hint">Optional controls for reasoning depth, MCP access, and Copilot config discovery.</div>
      <div class="advanced-fields">
        <div class="field" id="effortField">
          <label for="effort">Reasoning effort</label>
          <select id="effort">${effortOptions}</select>
          <div class="hint">Higher = deeper review, slower. Applies only to GitHub Copilot.</div>
        </div>

        <div class="field" id="mcpField">
          <label><input type="checkbox" id="inheritMcp" style="width:auto;margin-right:6px;">Allow MCP tools from your trusted Copilot config</label>
          <div class="hint">Capability elevation: lets Copilot call MCP servers defined in your own Copilot config (<code>~/.copilot/mcp-config.json</code>). A pull request's repo-local <code>.mcp.json</code> is never loaded, since PR content is untrusted.</div>
          <label style="margin-top:8px;"><input type="checkbox" id="reviewAutoEnableMcp" style="width:auto;margin-right:6px;">Always enable MCP for Copilot reviews</label>
          <div class="hint">Review-only override. Chat still follows the general MCP toggle above.</div>
          <label for="copilotConfigDir" style="margin-top:8px;">Copilot config directory</label>
          <input type="text" id="copilotConfigDir" aria-describedby="copilotConfigDirHint" placeholder="Empty uses ~/.copilot">
          <div class="hint" id="copilotConfigDirHint">Optional override of the Copilot config directory the trusted MCP servers are loaded from.</div>
        </div>
      </div>
    </details>
  </div>

  <div class="section">
    <div class="section-title">GitHub connection</div>

    <div class="field">
      <label for="baseUrl">GitHub base URL</label>
      <div class="row">
        <input type="text" id="baseUrl" placeholder="https://github.com">
        <button id="testConnection" class="secondary" title="Verify gh authentication for this host">Test</button>
      </div>
      <div class="hint">Change for GitHub Enterprise (for example https://github.mycompany.com).</div>
    </div>
  </div>

  <div class="section">
    <div class="section-title">Review defaults</div>

    <div class="field">
      <label for="guidanceProfile">Guidance profile</label>
      <div class="row wrap">
        <select id="guidanceProfile"></select>
        <button id="addGuidanceProfile" class="secondary">Save current as…</button>
        <button id="renameGuidanceProfile" class="secondary">Rename</button>
        <button id="deleteGuidanceProfile" class="secondary">Delete</button>
      </div>
      <div class="hint">Save and reuse focus areas and custom instructions.</div>
    </div>

    <div class="field">
      <label for="focusAreas">Review focus areas</label>
      <input type="text" id="focusAreas" placeholder="e.g. security, performance, test coverage">
      <div class="hint">Comma-separated areas the reviewer should prioritize.</div>
    </div>

    <div class="field">
      <label for="customInstructions">Custom review instructions</label>
      <textarea id="customInstructions" rows="3" placeholder="Extra instructions appended to every review prompt (for example team conventions to enforce)."></textarea>
      <div class="hint">Plain text. Use this for conventions or repeated review guidance.</div>
    </div>

    <div class="field">
      <label><input type="checkbox" id="reviewSelfCritique" style="width:auto;margin-right:6px;">Run a self-critique validation pass</label>
      <div class="hint">Higher precision, but roughly doubles review time.</div>
    </div>

    <div class="field">
      <label><input type="checkbox" id="reviewSupervisorEnabled" style="width:auto;margin-right:6px;">Inspect high-risk coverage gaps</label>
      <div class="hint">Runs a bounded coverage check and at most one targeted follow-up. Off by default because it adds latency.</div>
    </div>

    <details id="advancedReview" class="advanced">
      <summary>Advanced review options</summary>
      <div class="advanced-fields">
        <div class="field">
          <label><input type="checkbox" id="experimentalIntellijAssistedReview" style="width:auto;margin-right:6px;">Enable IntelliJ-assisted review (experimental)</label>
          <div class="hint">Shows the IntelliJ-assisted option in a review's Advanced review options. Requires a separate IntelliJ IDEA 262+ installation, a hand-written <code>~/.pr-pilot/semantic-review.json</code>, and manually importing a retained worktree per review. Off by default; ordinary reviews are unaffected.</div>
        </div>
      </div>
    </details>
  </div>

  <div class="section">
    <div class="section-title">Notifications</div>

    <div class="field">
      <label><input type="checkbox" id="notificationsEnabled" style="width:auto;margin-right:6px;">Enable background PR notifications</label>
      <div id="notificationOptions">
        <label><input type="checkbox" id="notifyReviewRequested" style="width:auto;margin-right:6px;">Notify when a review is requested from me</label>
        <label><input type="checkbox" id="notifyStarredRepos" style="width:auto;margin-right:6px;">Notify for new PRs in starred repositories</label>
        <label for="notificationPollMinutes">Notification polling interval (minutes)</label>
        <input type="number" id="notificationPollMinutes" class="short-field" min="1" max="60" step="1">
      </div>
      <div id="notificationHealth" class="hint" role="status" aria-live="polite"></div>
      <button id="retryNotifications" class="secondary" type="button">Retry notification poll</button>
      <div class="hint">Notification changes apply to the next polling cycle.</div>
    </div>
  </div>

<script nonce="${nonce}">
  ${profileNameError.toString()}
  const vscode = acquireVsCodeApi();
  const $ = (id) => document.getElementById(id);
  const CLI_DEFAULT = '__cli_default__';
  let state = null;
  let guidanceProfiles = [];
  let activeGuidanceProfileId = '';
  let nextSaveRequestId = 0;
  let latestSaveRequestId = 0;
  let profileDialogMode = 'add';
  let profileDialogTargetId = '';
  let profileDialogReturnFocus = null;

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

  function applyNotificationVisibility(enabled) {
    for (const id of ['notifyReviewRequested', 'notifyStarredRepos', 'notificationPollMinutes', 'retryNotifications']) {
      $(id).disabled = !enabled;
    }
    $('notificationOptions').style.opacity = enabled ? '1' : '0.6';
  }

  function renderNotificationHealth(health) {
    const target = $('notificationHealth');
    if (!state.notificationsEnabled) {
      target.textContent = 'Notifications are disabled.';
      return;
    }
    if (!health || health.status === 'never') {
      target.textContent = 'No notification poll has run yet.';
      return;
    }
    const when = health.lastAttemptAt ? ' Last checked ' + new Date(health.lastAttemptAt).toLocaleString() + '.' : '';
    target.textContent = health.status === 'healthy'
      ? 'Notifications are working.' + when
      : health.status === 'degraded'
        ? 'Notifications are partially working: ' + (health.message || 'One source failed.') + when
        : 'Notification polling failed: ' + (health.message || 'Unknown error.') + when;
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
    if (key === 'githubBaseUrl') {
      try {
        let candidate = String(value || '').trim();
        while (candidate.endsWith('/')) candidate = candidate.slice(0, -1);
        if (candidate.endsWith(':')) throw new Error();
        const url = candidate ? new URL(candidate) : new URL('https://github.com');
        if (url.protocol !== 'https:' || url.username || url.password || url.pathname !== '/' || url.search || url.hash) throw new Error();
        value = url.origin;
      } catch {
        setStatus('${GITHUB_BASE_URL_ERROR}', 'error');
        return;
      }
    }
    setStatus('Saving…');
    const requestId = ++nextSaveRequestId;
    latestSaveRequestId = requestId;
    vscode.postMessage({ type: 'update', requestId, key, value });
  }

  function saveGuidanceState() {
    setStatus('Saving…');
    const requestId = ++nextSaveRequestId;
    latestSaveRequestId = requestId;
    vscode.postMessage({
      type: 'updateReviewGuidanceState',
      requestId,
      profiles: guidanceProfiles,
      activeProfileId: activeGuidanceProfileId,
    });
  }

  function activeGuidanceProfile() {
    return guidanceProfiles.find((profile) => profile.id === activeGuidanceProfileId) || null;
  }

  function guidanceValues() {
    const profile = activeGuidanceProfile();
    return {
      focusAreas: $('focusAreas').value.trim(),
      customInstructions: $('customInstructions').value.trim(),
      guidanceGlobs: [...(profile ? profile.guidanceGlobs : (state.reviewGuidanceGlobs || []))],
    };
  }

  function renderGuidanceProfileOptions() {
    const select = $('guidanceProfile');
    select.innerHTML = '';
    const defaultOption = document.createElement('option');
    defaultOption.value = '';
    defaultOption.textContent = 'Default settings';
    select.appendChild(defaultOption);
    for (const profile of guidanceProfiles) {
      const option = document.createElement('option');
      option.value = profile.id;
      option.textContent = profile.name;
      select.appendChild(option);
    }
    if (!guidanceProfiles.some((profile) => profile.id === activeGuidanceProfileId)) activeGuidanceProfileId = '';
    select.value = activeGuidanceProfileId;
    const named = activeGuidanceProfileId !== '';
    $('renameGuidanceProfile').disabled = !named;
    $('deleteGuidanceProfile').disabled = !named;
  }

  function loadGuidanceFields() {
    const profile = activeGuidanceProfile();
    $('focusAreas').value = profile ? profile.focusAreas : (state.reviewFocusAreas || '');
    $('customInstructions').value = profile ? profile.customInstructions : (state.reviewCustomInstructions || '');
  }

  function saveGuidanceField(defaultKey) {
    const values = guidanceValues();
    const profile = activeGuidanceProfile();
    if (profile) {
      Object.assign(profile, values);
      saveGuidanceState();
      return;
    }
    if (defaultKey === 'reviewFocusAreas') state.reviewFocusAreas = values.focusAreas;
    if (defaultKey === 'reviewCustomInstructions') state.reviewCustomInstructions = values.customInstructions;
    save(defaultKey, state[defaultKey]);
  }

  function closeProfileDialog(id) {
    $(id).hidden = true;
    if (id === 'profileNameDialog') clearProfileNameError();
    if (profileDialogReturnFocus) profileDialogReturnFocus.focus();
    profileDialogReturnFocus = null;
  }

  function clearProfileNameError() {
    const input = $('profileNameInput');
    const error = $('profileNameError');
    input.removeAttribute('aria-invalid');
    error.textContent = '';
    error.hidden = true;
  }

  function showProfileNameError(message) {
    const input = $('profileNameInput');
    const error = $('profileNameError');
    input.setAttribute('aria-invalid', 'true');
    error.textContent = message;
    error.hidden = false;
    input.focus();
    input.select();
  }

  function keepFocusInDialog(dialog, event) {
    if (event.key !== 'Tab') return;
    const focusable = [...dialog.querySelectorAll('button:not([disabled]), input:not([disabled])')];
    if (focusable.length === 0) return;
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  function openProfileNameDialog(mode) {
    const profile = activeGuidanceProfile();
    if (mode === 'rename' && !profile) return;
    profileDialogMode = mode;
    profileDialogTargetId = profile ? profile.id : '';
    profileDialogReturnFocus = document.activeElement;
    $('profileNameDialogTitle').textContent = mode === 'rename' ? 'Rename guidance profile' : 'Save guidance profile';
    $('confirmProfileName').textContent = mode === 'rename' ? 'Rename' : 'Save';
    $('profileNameInput').value = profile ? profile.name : '';
    clearProfileNameError();
    $('profileNameDialog').hidden = false;
    $('profileNameInput').focus();
    $('profileNameInput').select();
  }

  function openDeleteProfileDialog() {
    const profile = activeGuidanceProfile();
    if (!profile) return;
    profileDialogTargetId = profile.id;
    profileDialogReturnFocus = document.activeElement;
    $('deleteProfileDialogDescription').textContent = 'Delete guidance profile "' + profile.name + '"?';
    $('deleteProfileDialog').hidden = false;
    $('cancelDeleteProfile').focus();
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
  $('reviewAutoEnableMcp').addEventListener('change', () => save('copilotAutoEnableMcpOnReview', $('reviewAutoEnableMcp').checked));
  $('copilotConfigDir').addEventListener('change', () => save('copilotConfigDir', $('copilotConfigDir').value.trim()));
  $('baseUrl').addEventListener('change', () => save('githubBaseUrl', $('baseUrl').value.trim()));
  $('guidanceProfile').addEventListener('change', () => {
    activeGuidanceProfileId = $('guidanceProfile').value;
    saveGuidanceState();
    renderGuidanceProfileOptions();
    loadGuidanceFields();
  });
  $('addGuidanceProfile').addEventListener('click', () => {
    openProfileNameDialog('add');
  });
  $('renameGuidanceProfile').addEventListener('click', () => {
    openProfileNameDialog('rename');
  });
  $('cancelProfileName').addEventListener('click', () => closeProfileDialog('profileNameDialog'));
  $('confirmProfileName').addEventListener('click', () => {
    const input = $('profileNameInput');
    const nameError = profileNameError(input.value);
    if (nameError) {
      showProfileNameError(nameError);
      return;
    }
    const name = input.value.trim();
    if (profileDialogMode === 'rename') {
      const profile = guidanceProfiles.find((candidate) => candidate.id === profileDialogTargetId);
      if (!profile) return;
      profile.name = name;
    } else {
      const values = guidanceValues();
      const id = 'profile-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);
      guidanceProfiles.push({ id, name, ...values });
      activeGuidanceProfileId = id;
    }
    saveGuidanceState();
    renderGuidanceProfileOptions();
    closeProfileDialog('profileNameDialog');
  });
  $('profileNameInput').addEventListener('keydown', (event) => {
    if (event.key === 'Enter') $('confirmProfileName').click();
    if (event.key === 'Escape') closeProfileDialog('profileNameDialog');
  });
  $('profileNameInput').addEventListener('input', clearProfileNameError);
  $('deleteGuidanceProfile').addEventListener('click', () => {
    openDeleteProfileDialog();
  });
  $('cancelDeleteProfile').addEventListener('click', () => closeProfileDialog('deleteProfileDialog'));
  $('confirmDeleteProfile').addEventListener('click', () => {
    guidanceProfiles = guidanceProfiles.filter((candidate) => candidate.id !== profileDialogTargetId);
    activeGuidanceProfileId = '';
    saveGuidanceState();
    renderGuidanceProfileOptions();
    loadGuidanceFields();
    closeProfileDialog('deleteProfileDialog');
  });
  for (const id of ['profileNameDialog', 'deleteProfileDialog']) {
    $(id).addEventListener('keydown', (event) => {
      if (event.key === 'Escape') closeProfileDialog(id);
      keepFocusInDialog($(id), event);
    });
  }
  $('focusAreas').addEventListener('change', () => saveGuidanceField('reviewFocusAreas'));
  $('customInstructions').addEventListener('change', () => saveGuidanceField('reviewCustomInstructions'));
  $('reviewSelfCritique').addEventListener('change', () => save('reviewSelfCritique', $('reviewSelfCritique').checked));
  $('reviewSupervisorEnabled').addEventListener('change', () => save('reviewSupervisorEnabled', $('reviewSupervisorEnabled').checked));
  $('experimentalIntellijAssistedReview').addEventListener('change', () => save('experimentalIntellijAssistedReview', $('experimentalIntellijAssistedReview').checked));
  $('notificationsEnabled').addEventListener('change', () => {
    state.notificationsEnabled = $('notificationsEnabled').checked;
    applyNotificationVisibility(state.notificationsEnabled);
    renderNotificationHealth(state.notificationHealth);
    save('notificationsEnabled', state.notificationsEnabled);
  });
  $('notifyReviewRequested').addEventListener('change', () => save('notifyReviewRequested', $('notifyReviewRequested').checked));
  $('notifyStarredRepos').addEventListener('change', () => save('notifyStarredRepos', $('notifyStarredRepos').checked));
  $('notificationPollMinutes').addEventListener('change', () => save('notificationPollMinutes', Number($('notificationPollMinutes').value)));
  $('retryNotifications').addEventListener('click', () => {
    setStatus('Retrying notification poll…');
    vscode.postMessage({ type: 'retryNotifications' });
  });
  function setModelsRefreshing(refreshing) {
    $('refreshModels').textContent = refreshing ? 'Refreshing…' : 'Refresh';
    $('refreshModels').disabled = refreshing;
  }
  $('refreshModels').addEventListener('click', () => {
    setModelsRefreshing(true);
    setStatus('Refreshing model list…');
    vscode.postMessage({ type: 'refreshModels' });
  });
  $('testConnection').addEventListener('click', () => {
    const value = $('baseUrl').value.trim();
    let normalized;
    try {
      let candidate = value;
      while (candidate.endsWith('/')) candidate = candidate.slice(0, -1);
      if (candidate.endsWith(':')) throw new Error();
      const url = candidate ? new URL(candidate) : new URL('https://github.com');
      if (url.protocol !== 'https:' || url.username || url.password || url.pathname !== '/' || url.search || url.hash) throw new Error();
      normalized = url.origin;
    } catch {
      setStatus('${GITHUB_BASE_URL_ERROR}', 'error');
      return;
    }
    $('testConnection').textContent = 'Testing…';
    setStatus('Checking gh authentication…');
    vscode.postMessage({ type: 'testConnection', githubBaseUrl: normalized });
  });

  window.addEventListener('message', (event) => {
    const msg = event.data;
    if (msg.type === 'init') {
      state = msg.state;
      $('provider').value = state.provider;
      $('claudeModel').value = state.reviewModel;
      $('effort').value = state.reviewEffort;
      $('baseUrl').value = state.githubBaseUrl;
      $('inheritMcp').checked = state.copilotInheritMcp === true;
      $('reviewAutoEnableMcp').checked = state.copilotAutoEnableMcpOnReview === true;
      $('copilotConfigDir').value = state.copilotConfigDir || '';
      guidanceProfiles = Array.isArray(state.reviewGuidanceProfiles) ? state.reviewGuidanceProfiles : [];
      activeGuidanceProfileId = state.activeReviewGuidanceProfileId || '';
      renderGuidanceProfileOptions();
      loadGuidanceFields();
      $('reviewSelfCritique').checked = state.reviewSelfCritique !== false;
      $('reviewSupervisorEnabled').checked = state.reviewSupervisorEnabled === true;
      $('experimentalIntellijAssistedReview').checked = state.experimentalIntellijAssistedReview === true;
      $('notificationsEnabled').checked = state.notificationsEnabled === true;
      $('notifyReviewRequested').checked = state.notifyReviewRequested === true;
      $('notifyStarredRepos').checked = state.notifyStarredRepos === true;
      $('notificationPollMinutes').value = String(state.notificationPollMinutes || 5);
      applyNotificationVisibility(state.notificationsEnabled);
      renderNotificationHealth(state.notificationHealth);
      renderCopilotModels(msg.copilotModels || [], state.reviewModelCopilot);
      if (msg.refreshingModels === true) setModelsRefreshing(true);
      applyProviderVisibility(state.provider);
    } else if (msg.type === 'models') {
      setModelsRefreshing(false);
      renderCopilotModels(msg.copilotModels || [], copilotModelValue());
      // The automatic refresh on open stays silent when it succeeds; failures always explain why
      // the list may be stale.
      if (msg.quiet !== true || msg.ok === false) {
        setStatus(msg.message || (msg.ok === false ? 'Could not refresh models.' : 'Model list refreshed.'), msg.ok === false ? 'error' : 'ok');
      }
    } else if (msg.type === 'saveResult') {
      if (typeof msg.requestId === 'number' && msg.requestId !== latestSaveRequestId) return;
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
