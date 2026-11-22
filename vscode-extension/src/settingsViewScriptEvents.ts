export function buildSettingsScriptEvents(): string {
    return `
  $('provider').addEventListener('change', () => {
    const p = $('provider').value;
    applyProviderVisibility(p);
    save('reviewProvider', p);
  });
  $('claudeModel').addEventListener('change', () => save('reviewModel', $('claudeModel').value));
  $('copilotModel').addEventListener('change', () => save('reviewModelCopilot', copilotModelValue()));
  $('antigravityModel').addEventListener('change', () => save('reviewModelAntigravity', antigravityModelValue()));
  $('effort').addEventListener('change', () => save('reviewEffort', $('effort').value));
  $('inheritMcp').addEventListener('change', () => save('copilotInheritMcp', $('inheritMcp').checked));
  $('reviewAutoEnableMcp').addEventListener('change', () => save('copilotAutoEnableMcpOnReview', $('reviewAutoEnableMcp').checked));
  $('copilotConfigDir').addEventListener('input', () => {
    scheduleFieldSave('copilotConfigDir', 350, () => save('copilotConfigDir', $('copilotConfigDir').value.trim()));
  });
  $('copilotConfigDir').addEventListener('change', () => {
    flushFieldSave('copilotConfigDir', () => save('copilotConfigDir', $('copilotConfigDir').value.trim()));
  });
  $('vscodeCodeIntelligence').addEventListener('change', () => save('experimentalVsCodeCodeIntelligence', $('vscodeCodeIntelligence').checked));
  $('reviewRulesDirectory').addEventListener('input', () => {
    scheduleFieldSave('reviewRulesDirectory', 350, () => save('reviewRulesDirectory', $('reviewRulesDirectory').value.trim()));
  });
  $('reviewRulesDirectory').addEventListener('change', () => {
    flushFieldSave('reviewRulesDirectory', () => save('reviewRulesDirectory', $('reviewRulesDirectory').value.trim()));
  });
  $('baseUrl').addEventListener('change', () => {
    cancelFieldSave('baseUrl');
    save('githubBaseUrl', $('baseUrl').value.trim());
  });
  $('baseUrl').addEventListener('input', () => {
    clearBaseUrlError();
    cancelFieldSave('baseUrl');
    const normalized = normalizeValidBaseUrlOrNull($('baseUrl').value);
    if (normalized) {
      scheduleFieldSave('baseUrl', 500, () => save('githubBaseUrl', $('baseUrl').value.trim()));
    }
  });
  $('guidanceProfile').addEventListener('change', () => {
    flushAllPendingSaves();
    activeGuidanceProfileId = $('guidanceProfile').value;
    saveGuidanceState();
    renderGuidanceProfileOptions();
    loadGuidanceFields();
  });
  $('addGuidanceProfile').addEventListener('click', () => {
    flushAllPendingSaves();
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
  $('focusAreas').addEventListener('input', () => {
    scheduleFieldSave('focusAreas', 350, () => saveGuidanceField('reviewFocusAreas'));
  });
  $('focusAreas').addEventListener('change', () => {
    flushFieldSave('focusAreas', () => saveGuidanceField('reviewFocusAreas'));
  });
  $('customInstructions').addEventListener('input', () => {
    scheduleFieldSave('customInstructions', 350, () => saveGuidanceField('reviewCustomInstructions'));
  });
  $('customInstructions').addEventListener('change', () => {
    flushFieldSave('customInstructions', () => saveGuidanceField('reviewCustomInstructions'));
  });
  $('guidanceGlobs').addEventListener('input', () => {
    scheduleFieldSave('guidanceGlobs', 350, () => saveGuidanceField('reviewGuidanceGlobs'));
  });
  $('guidanceGlobs').addEventListener('change', () => {
    flushFieldSave('guidanceGlobs', () => saveGuidanceField('reviewGuidanceGlobs'));
  });
  $('repositoryInstructionsRepo').addEventListener('change', (event) => {
    flushAllPendingSaves();
    selectedRepository = event.target.value;
    renderRepositoryInstructions();
  });
  $('repositoryInstructionsText').addEventListener('input', () => {
    if (!selectedRepository) return;
    const repo = selectedRepository;
    scheduleFieldSave('repositoryInstructionsText', 350, () => {
      if (!repo) return;
      const trimmed = String($('repositoryInstructionsText').value || '').trim();
      if (!trimmed) return;
      const next = { ...repositoryInstructions, [repo]: trimmed };
      repositoryInstructions = next;
      save('repositoryReviewInstructions', next);
    });
  });
  $('repositoryInstructionsText').addEventListener('change', (event) => {
    cancelFieldSave('repositoryInstructionsText');
    if (!selectedRepository) return;
    const next = { ...repositoryInstructions };
    const value = String(event.target.value || '').trim();
    if (value) {
      next[selectedRepository] = value;
      repositoryInstructions = next;
      renderRepositoryInstructions();
      save('repositoryReviewInstructions', repositoryInstructions);
    } else if (selectedRepository in next || selectedRepository in acknowledgedRepoInstructions) {
      delete next[selectedRepository];
      saveRepositoryInstructions(next);
    }
  });
  $('forgetRepositoryInstructions').addEventListener('click', () => {
    cancelFieldSave('repositoryInstructionsText');
    if (!selectedRepository) return;
    const next = { ...repositoryInstructions };
    delete next[selectedRepository];
    saveRepositoryInstructions(next);
  });
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') flushAllPendingSaves();
  });
  window.addEventListener('pagehide', flushAllPendingSaves);
  $('reviewSelfCritique').addEventListener('change', () => save('reviewSelfCritique', $('reviewSelfCritique').checked));
  $('reviewSupervisorEnabled').addEventListener('change', () => save('reviewSupervisorEnabled', $('reviewSupervisorEnabled').checked));
  $('reviewBuiltInRules').addEventListener('change', () => save('reviewBuiltInRules', $('reviewBuiltInRules').checked));
  $('reviewSecondReviewerModelClaude').addEventListener('change', () => {
    save('reviewSecondReviewerModelClaude', $('reviewSecondReviewerModelClaude').value);
  });
  $('reviewSecondReviewerModel').addEventListener('change', () => {
    applySecondReviewerVisibility();
    save('reviewSecondReviewerModel', secondReviewerValue());
  });
  $('reviewSecondReviewerModelAntigravity').addEventListener('change', () => {
    save('reviewSecondReviewerModelAntigravity', secondReviewerAntigravityValue());
  });
  $('reviewSecondReviewerEffort').addEventListener('change', () => save('reviewSecondReviewerEffort', $('reviewSecondReviewerEffort').value));
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
  function setAntigravityModelsRefreshing(refreshing) {
    $('refreshAntigravityModels').textContent = refreshing ? 'Refreshing…' : 'Refresh';
    $('refreshAntigravityModels').disabled = refreshing;
  }
  $('refreshAntigravityModels').addEventListener('click', () => {
    setAntigravityModelsRefreshing(true);
    setStatus('Refreshing Antigravity model list…');
    vscode.postMessage({ type: 'refreshAntigravityModels' });
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
      showBaseUrlError();
      return;
    }
    clearBaseUrlError();
    $('testConnection').textContent = 'Checking…';
    setStatus('Checking gh authentication…');
    vscode.postMessage({ type: 'testConnection', githubBaseUrl: normalized });
  });

  window.addEventListener('message', (event) => {
    const msg = event.data;
    if (msg.type === 'init') {
      cancelAllPendingSaves();
      state = msg.state;
      $('provider').value = state.provider;
      $('claudeModel').value = state.reviewModel;
      $('reviewSecondReviewerModelClaude').value = (state.reviewSecondReviewerModelClaude || '').trim();
      $('effort').value = state.reviewEffort;
      $('reviewSecondReviewerEffort').value = state.reviewSecondReviewerEffort || 'medium';
      $('baseUrl').value = state.githubBaseUrl;
      $('inheritMcp').checked = state.copilotInheritMcp === true;
      $('reviewAutoEnableMcp').checked = state.copilotAutoEnableMcpOnReview === true;
      $('copilotConfigDir').value = state.copilotConfigDir || '';
      $('vscodeCodeIntelligence').checked = state.experimentalVsCodeCodeIntelligence === true;
      $('reviewRulesDirectory').value = state.reviewRulesDirectory || '';
      guidanceProfiles = Array.isArray(state.reviewGuidanceProfiles) ? state.reviewGuidanceProfiles : [];
      activeGuidanceProfileId = state.activeReviewGuidanceProfileId || '';
      renderGuidanceProfileOptions();
      loadGuidanceFields();
      repositoryInstructions = state.repositoryReviewInstructions && typeof state.repositoryReviewInstructions === 'object'
        ? { ...state.repositoryReviewInstructions } : {};
      renderRepositoryInstructions();
      $('reviewSelfCritique').checked = state.reviewSelfCritique !== false;
      $('reviewSupervisorEnabled').checked = state.reviewSupervisorEnabled === true;
      $('reviewBuiltInRules').checked = state.reviewBuiltInRules !== false;
      $('experimentalIntellijAssistedReview').checked = state.experimentalIntellijAssistedReview === true;
      $('notificationsEnabled').checked = state.notificationsEnabled === true;
      $('notifyReviewRequested').checked = state.notifyReviewRequested === true;
      $('notifyStarredRepos').checked = state.notifyStarredRepos === true;
      $('notificationPollMinutes').value = String(state.notificationPollMinutes || 5);
      applyNotificationVisibility(state.notificationsEnabled);
      renderNotificationHealth(state.notificationHealth);
      renderCopilotModels(msg.copilotModels || [], state.reviewModelCopilot, (state.reviewSecondReviewerModel || '').trim());
      renderAntigravityModels(
        msg.antigravityModels || [],
        state.reviewModelAntigravity || '',
        (state.reviewSecondReviewerModelAntigravity || '').trim(),
      );
      if (msg.refreshingModels === true) setModelsRefreshing(true);
      if (msg.refreshingAntigravityModels === true) setAntigravityModelsRefreshing(true);
      applyProviderVisibility(state.provider);
      seedSavedPayloads();
    } else if (msg.type === 'models') {
      setModelsRefreshing(false);
      renderCopilotModels(msg.copilotModels || [], copilotModelValue(), secondReviewerValue());
      // The automatic refresh on open stays silent when it succeeds; failures always explain why
      // the list may be stale.
      if (msg.quiet !== true || msg.ok === false) {
        setStatus(msg.message || (msg.ok === false ? 'Could not refresh models.' : 'Model list refreshed.'), msg.ok === false ? 'error' : 'ok');
      }
    } else if (msg.type === 'antigravityModels') {
      setAntigravityModelsRefreshing(false);
      renderAntigravityModels(msg.antigravityModels || [], antigravityModelValue(), secondReviewerAntigravityValue());
      if (msg.quiet !== true || msg.ok === false) {
        setStatus(msg.message || (msg.ok === false ? 'Could not refresh Antigravity models.' : 'Antigravity model list refreshed.'), msg.ok === false ? 'error' : 'ok');
      }
    } else if (msg.type === 'saveResult') {
      handleSaveResult(msg);
    } else if (msg.type === 'testResult') {
      $('testConnection').textContent = 'Check connection';
      setStatus(msg.ok ? (msg.message || 'Connection looks good.') : (msg.message || 'Connection check failed.'), msg.ok ? 'ok' : 'error');
    }
  });

  vscode.postMessage({ type: 'ready' });`;
}
