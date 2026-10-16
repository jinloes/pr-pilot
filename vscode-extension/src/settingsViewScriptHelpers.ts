export function buildSettingsScriptHelpers(baseUrlError: string, profileNameErrorSource: string): string {
    return `  ${profileNameErrorSource}
  const vscode = acquireVsCodeApi();
  const $ = (id) => document.getElementById(id);
  const CLI_DEFAULT = '__cli_default__';
  let state = null;
  let guidanceProfiles = [];
  let activeGuidanceProfileId = '';
  let repositoryInstructions = {};
  let selectedRepository = '';

  function renderRepositoryInstructions() {
    const select = $('repositoryInstructionsRepo');
    const text = $('repositoryInstructionsText');
    const repositories = Object.keys(repositoryInstructions).sort();
    if (!repositories.includes(selectedRepository)) selectedRepository = repositories[0] || '';
    select.innerHTML = '';
    if (repositories.length === 0) {
      const empty = document.createElement('option');
      empty.value = '';
      empty.textContent = 'No remembered repositories';
      select.appendChild(empty);
    }
    for (const repository of repositories) {
      const option = document.createElement('option');
      option.value = repository;
      option.textContent = repository;
      select.appendChild(option);
    }
    select.value = selectedRepository;
    select.disabled = repositories.length === 0;
    text.disabled = repositories.length === 0;
    $('forgetRepositoryInstructions').disabled = repositories.length === 0;
    text.value = selectedRepository ? repositoryInstructions[selectedRepository] || '' : '';
  }

  function saveRepositoryInstructions(next) {
    repositoryInstructions = next;
    renderRepositoryInstructions();
    save('repositoryReviewInstructions', repositoryInstructions);
  }
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

  function showBaseUrlError() {
    const input = $('baseUrl');
    const error = $('baseUrlError');
    error.textContent = '${baseUrlError}';
    error.hidden = false;
    input.setAttribute('aria-invalid', 'true');
    input.setAttribute('aria-describedby', 'baseUrlError');
    setStatus('');
  }

  function clearBaseUrlError() {
    const input = $('baseUrl');
    const error = $('baseUrlError');
    error.textContent = '';
    error.hidden = true;
    input.removeAttribute('aria-invalid');
    input.removeAttribute('aria-describedby');
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

  function renderModelSelect(sel, models, current, blankLabel) {
    sel.innerHTML = '';
    const def = document.createElement('option');
    def.value = CLI_DEFAULT;
    def.textContent = blankLabel;
    sel.appendChild(def);
    const ids = current && !models.includes(current) ? [...models, current] : models;
    for (const id of ids) {
      const o = document.createElement('option');
      o.value = id;
      o.textContent = id;
      sel.appendChild(o);
    }
    sel.value = current || CLI_DEFAULT;
  }

  function renderCopilotModels(models, current, secondReviewer) {
    renderModelSelect($('copilotModel'), models, current, 'CLI default (unset)');
    renderModelSelect($('reviewSecondReviewerModel'), models, secondReviewer, 'Off');
  }

  function secondReviewerValue() {
    const sel = $('reviewSecondReviewerModel').value;
    return sel === CLI_DEFAULT ? '' : sel;
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
        showBaseUrlError();
        return;
      }
      clearBaseUrlError();
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
`;
}
