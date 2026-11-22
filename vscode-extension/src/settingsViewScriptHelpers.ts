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
  let activeSaveError = '';
  let profileDialogMode = 'add';
  let profileDialogTargetId = '';
  let profileDialogReturnFocus = null;
  let acknowledgedRepoInstructions = {};
  const pendingInputTimers = new Map();
  const lastSavedPayloads = new Map();
  const acknowledgedPayloads = new Map();
  const inFlightSaves = new Map();

  function cancelFieldSave(fieldId) {
    const entry = pendingInputTimers.get(fieldId);
    if (!entry) return;
    clearTimeout(entry.timer);
    pendingInputTimers.delete(fieldId);
  }

  function scheduleFieldSave(fieldId, delayMs, run) {
    cancelFieldSave(fieldId);
    const timer = setTimeout(() => {
      pendingInputTimers.delete(fieldId);
      run();
    }, delayMs);
    pendingInputTimers.set(fieldId, { timer, flush: run });
  }

  function flushFieldSave(fieldId, fallbackRun) {
    const entry = pendingInputTimers.get(fieldId);
    if (entry) {
      clearTimeout(entry.timer);
      pendingInputTimers.delete(fieldId);
      entry.flush();
      return;
    }
    if (fallbackRun) fallbackRun();
  }

  function flushAllPendingSaves() {
    const entries = [...pendingInputTimers.values()];
    for (const key of [...pendingInputTimers.keys()]) {
      const item = pendingInputTimers.get(key);
      if (item) clearTimeout(item.timer);
      pendingInputTimers.delete(key);
    }
    for (const entry of entries) entry.flush();
  }

  function cancelAllPendingSaves() {
    for (const key of [...pendingInputTimers.keys()]) cancelFieldSave(key);
  }

  function normalizeValidBaseUrlOrNull(raw) {
    try {
      let candidate = String(raw || '').trim();
      while (candidate.endsWith('/')) candidate = candidate.slice(0, -1);
      if (candidate.endsWith(':')) return null;
      const url = candidate ? new URL(candidate) : new URL('https://github.com');
      if (url.protocol !== 'https:' || url.username || url.password || url.port || url.pathname !== '/' || url.search || url.hash) return null;
      return url.origin;
    } catch {
      return null;
    }
  }

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
    const isAntigravity = provider === 'antigravity';
    $('claudeModelField').classList.toggle('hidden', isCopilot || isAntigravity);
    $('copilotModelField').classList.toggle('hidden', !isCopilot);
    $('antigravityModelField').classList.toggle('hidden', !isAntigravity);
    $('secondReviewerClaudeModelField').classList.toggle('hidden', isCopilot || isAntigravity);
    $('secondReviewerCopilotModelField').classList.toggle('hidden', !isCopilot);
    $('secondReviewerAntigravityModelField').classList.toggle('hidden', !isAntigravity);
    $('effortField').classList.toggle('hidden', !isCopilot);
    $('copilotToolsSection').classList.toggle('hidden', !isCopilot);
    applySecondReviewerVisibility();
  }

  function applySecondReviewerVisibility() {
    $('secondReviewerEffortField').classList.toggle(
      'hidden',
      $('provider').value !== 'copilot' || secondReviewerValue() === '',
    );
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
    applySecondReviewerVisibility();
  }

  function secondReviewerValue() {
    const sel = $('reviewSecondReviewerModel').value;
    return sel === CLI_DEFAULT ? '' : sel;
  }

  function copilotModelValue() {
    const sel = $('copilotModel').value;
    return sel === CLI_DEFAULT ? '' : sel;
  }

  function renderAntigravityModels(models, current, secondReviewer) {
    renderModelSelect($('antigravityModel'), models, current, 'CLI default (unset)');
    renderModelSelect($('reviewSecondReviewerModelAntigravity'), models, secondReviewer, 'Off');
  }

  function antigravityModelValue() {
    const sel = $('antigravityModel').value;
    return sel === CLI_DEFAULT ? '' : sel;
  }

  function secondReviewerAntigravityValue() {
    const sel = $('reviewSecondReviewerModelAntigravity').value;
    return sel === CLI_DEFAULT ? '' : sel;
  }

  const DEDUPE_TEXT_KEYS = new Set([
    'copilotConfigDir', 'reviewRulesDirectory', 'githubBaseUrl',
    'reviewFocusAreas', 'reviewCustomInstructions', 'reviewGuidanceGlobs',
    'repositoryReviewInstructions',
  ]);

  function save(key, value) {
    if (key === 'githubBaseUrl') {
      const normalized = normalizeValidBaseUrlOrNull(value);
      if (!normalized) {
        showBaseUrlError();
        return;
      }
      value = normalized;
      clearBaseUrlError();
    }
    const cacheKey = 'setting:' + key;
    const serialized = JSON.stringify(value);
    const current = lastSavedPayloads.get(cacheKey);
    if (DEDUPE_TEXT_KEYS.has(key) && current && current.serialized === serialized) return;
    activeSaveError = '';
    setStatus('Saving…');
    const requestId = ++nextSaveRequestId;
    latestSaveRequestId = requestId;
    lastSavedPayloads.set(cacheKey, { requestId, serialized });
    inFlightSaves.set(requestId, {
      cacheKey,
      serialized,
      repoSnapshot: key === 'repositoryReviewInstructions' ? { ...value } : null,
    });
    vscode.postMessage({ type: 'update', requestId, key, value });
  }

  function saveGuidanceState() {
    const serialized = JSON.stringify({ profiles: guidanceProfiles, activeProfileId: activeGuidanceProfileId });
    activeSaveError = '';
    setStatus('Saving…');
    const requestId = ++nextSaveRequestId;
    latestSaveRequestId = requestId;
    lastSavedPayloads.set('guidanceState', { requestId, serialized });
    inFlightSaves.set(requestId, { cacheKey: 'guidanceState', serialized, repoSnapshot: null });
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

  function guidanceGlobLines() {
    return $('guidanceGlobs').value
      .split('\\n')
      .map((line) => line.trim())
      .filter((line) => line !== '');
  }

  function guidanceValues() {
    return {
      focusAreas: $('focusAreas').value.trim(),
      customInstructions: $('customInstructions').value.trim(),
      guidanceGlobs: guidanceGlobLines(),
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
    $('guidanceGlobs').value = (profile ? profile.guidanceGlobs : (state.reviewGuidanceGlobs || [])).join('\\n');
  }

  function saveGuidanceField(defaultKey) {
    const values = guidanceValues();
    const profile = activeGuidanceProfile();
    if (profile) {
      const nextProfiles = guidanceProfiles.map((candidate) =>
        candidate.id === profile.id ? { ...candidate, ...values } : candidate);
      const serialized = JSON.stringify({ profiles: nextProfiles, activeProfileId: activeGuidanceProfileId });
      const currentGuidance = lastSavedPayloads.get('guidanceState');
      if (currentGuidance && currentGuidance.serialized === serialized) return;
      Object.assign(profile, values);
      saveGuidanceState();
      return;
    }
    const nextValue = defaultKey === 'reviewFocusAreas'
      ? values.focusAreas
      : defaultKey === 'reviewCustomInstructions'
        ? values.customInstructions
        : values.guidanceGlobs;
    const currentSetting = lastSavedPayloads.get('setting:' + defaultKey);
    if (currentSetting && currentSetting.serialized === JSON.stringify(nextValue)) return;
    if (state) state[defaultKey] = nextValue;
    save(defaultKey, nextValue);
  }

  function seedSavedPayloads() {
    if (!state) return;
    lastSavedPayloads.clear();
    acknowledgedPayloads.clear();
    inFlightSaves.clear();
    activeSaveError = '';
    acknowledgedRepoInstructions = { ...repositoryInstructions };
    const entries = [
      ['setting:githubBaseUrl', JSON.stringify(normalizeValidBaseUrlOrNull(state.githubBaseUrl) || state.githubBaseUrl)],
      ['setting:copilotConfigDir', JSON.stringify((state.copilotConfigDir || '').trim())],
      ['setting:reviewRulesDirectory', JSON.stringify((state.reviewRulesDirectory || '').trim())],
      ['setting:reviewFocusAreas', JSON.stringify((state.reviewFocusAreas || '').trim())],
      ['setting:reviewCustomInstructions', JSON.stringify((state.reviewCustomInstructions || '').trim())],
      ['setting:reviewGuidanceGlobs', JSON.stringify(state.reviewGuidanceGlobs || [])],
      ['setting:repositoryReviewInstructions', JSON.stringify(repositoryInstructions)],
      ['guidanceState', JSON.stringify({ profiles: guidanceProfiles, activeProfileId: activeGuidanceProfileId })],
    ];
    for (const [k, v] of entries) {
      acknowledgedPayloads.set(k, v);
      lastSavedPayloads.set(k, { requestId: 0, serialized: v });
    }
  }

  function handleSaveResult(msg) {
    const reqId = typeof msg.requestId === 'number' ? msg.requestId : null;
    const inFlight = reqId !== null ? inFlightSaves.get(reqId) : null;
    if (reqId !== null) inFlightSaves.delete(reqId);
    const fallbackKey = msg.key === 'reviewGuidanceState'
      ? 'guidanceState'
      : typeof msg.key === 'string' ? 'setting:' + msg.key : null;
    const cacheKey = inFlight ? inFlight.cacheKey : fallbackKey;
    if (msg.ok) {
      if (inFlight) {
        acknowledgedPayloads.set(inFlight.cacheKey, inFlight.serialized);
        if (inFlight.repoSnapshot) acknowledgedRepoInstructions = { ...inFlight.repoSnapshot };
      }
      if (typeof msg.requestId === 'number' && msg.requestId !== latestSaveRequestId) return;
      if (!activeSaveError) setStatus(msg.message || 'Saved.', 'ok');
      return;
    }
    if (cacheKey) {
      const current = lastSavedPayloads.get(cacheKey);
      if (!current || reqId === null || current.requestId === reqId) {
        const ack = acknowledgedPayloads.get(cacheKey);
        if (ack !== undefined) lastSavedPayloads.set(cacheKey, { requestId: 0, serialized: ack });
        else lastSavedPayloads.delete(cacheKey);
        if (cacheKey === 'setting:repositoryReviewInstructions') {
          repositoryInstructions = { ...acknowledgedRepoInstructions };
        }
      }
    }
    activeSaveError = msg.message || 'Could not save setting.';
    setStatus(activeSaveError, 'error');
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
