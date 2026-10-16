import { buildSettingsScript } from './settingsViewScript';
import { SETTINGS_VIEW_STYLES } from './settingsViewStyles';
import {
    CLAUDE_MODEL_OPTIONS,
    COPILOT_EFFORTS,
    GITHUB_BASE_URL_ERROR,
    escapeHtml,
    profileNameError,
} from './settingsView';

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
${SETTINGS_VIEW_STYLES}
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
    <div class="section-title">GitHub connection</div>

    <div class="field">
      <label for="baseUrl">GitHub base URL</label>
      <div class="row">
        <input type="text" id="baseUrl" placeholder="https://github.com">
        <button id="testConnection" class="secondary" title="Verify gh authentication for this host">Check connection</button>
      </div>
      <p id="baseUrlError" class="field-error" role="alert" hidden></p>
      <div class="hint">Change for GitHub Enterprise (for example https://github.mycompany.com).</div>
    </div>
  </div>

  <div class="section">
    <div class="section-title">Review provider</div>

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

    <div class="field">
      <label for="reviewSecondReviewerModel">Second reviewer</label>
      <select id="reviewSecondReviewerModel" aria-describedby="reviewSecondReviewerModelHint"></select>
      <div class="hint" id="reviewSecondReviewerModelHint">Optional. Runs a second Copilot model in parallel with either provider; findings are cross-validated. Choose Off to disable.</div>
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
          <label><input type="checkbox" id="inheritMcp" style="width:auto;margin-right:6px;">Allow Copilot to use MCP tools from your trusted Copilot config</label>
          <div class="hint">Applies while reviewing untrusted pull request content. Servers load only from your own Copilot config (<code>~/.copilot/mcp-config.json</code>); a pull request's <code>.mcp.json</code> is never loaded.</div>
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
    <div class="section-title">Review guidance</div>

    <div class="field">
      <label for="guidanceProfile">Guidance profile</label>
      <div class="row wrap">
        <select id="guidanceProfile"></select>
        <button id="addGuidanceProfile" class="secondary">Save as…</button>
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
      <label for="repositoryInstructionsRepo">Remembered repository instructions</label>
      <div class="row wrap">
        <select id="repositoryInstructionsRepo"></select>
        <button id="forgetRepositoryInstructions" class="secondary" type="button">Forget</button>
      </div>
      <textarea id="repositoryInstructionsText" rows="3" maxlength="10000" aria-label="Instructions for the selected repository" aria-describedby="repositoryInstructionsHint"></textarea>
      <div class="hint" id="repositoryInstructionsHint">Added to every review of that repository, together with the instructions above. Add a repository from a review's instructions with “Remember for this repository”.</div>
    </div>

    <div class="field">
      <label for="reviewRulesDirectory">Rules folder</label>
      <input type="text" id="reviewRulesDirectory" aria-describedby="reviewRulesDirectoryHint" placeholder="/absolute/path/to/review-rules">
      <div class="hint" id="reviewRulesDirectoryHint">Optional local folder of review rules (.md, .yaml, .yml), such as a checkout of your team's central review rules. Structured rules run when their trigger matches; each rule is applied by its own agent.</div>
    </div>
  </div>

  <div class="section">
    <div class="section-title">Review validation</div>

    <div class="field">
      <label><input type="checkbox" id="reviewSelfCritique" style="width:auto;margin-right:6px;">Validate findings with a second pass</label>
      <div class="hint">Higher precision, but roughly doubles review time.</div>
    </div>

    <div class="field">
      <label><input type="checkbox" id="reviewSupervisorEnabled" style="width:auto;margin-right:6px;">Re-inspect coverage gaps</label>
      <div class="hint">Runs a bounded coverage check, then targeted follow-ups that re-review changed files the first pass never mentioned and its uninspected high-risk hunks. On by default; turn off to save latency.</div>
    </div>
  </div>

  <div class="section">
    <div class="section-title">Advanced review options</div>

    <div class="field">
      <label><input type="checkbox" id="experimentalIntellijAssistedReview" style="width:auto;margin-right:6px;">Enable IntelliJ-assisted review (experimental)</label>
      <div class="hint">Shows the IntelliJ-assisted option in a review's Advanced review options. Requires a separate IntelliJ IDEA 262+ installation, a hand-written <code>~/.pr-pilot/semantic-review.json</code>, and manually importing a retained worktree per review. Off by default; ordinary reviews are unaffected. Turning this off also hides retained-worktree maintenance; turn it back on to remove retained worktrees.</div>
    </div>
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
${buildSettingsScript(GITHUB_BASE_URL_ERROR, profileNameError.toString())}
</script>
</body>
</html>`;
}
