export function buildSettingsScriptEvents(): string {
    return `
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
  $('reviewRulesDirectory').addEventListener('change', () => save('reviewRulesDirectory', $('reviewRulesDirectory').value.trim()));
  $('baseUrl').addEventListener('change', () => save('githubBaseUrl', $('baseUrl').value.trim()));
  $('baseUrl').addEventListener('input', clearBaseUrlError);
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
  $('repositoryInstructionsRepo').addEventListener('change', (event) => {
    selectedRepository = event.target.value;
    renderRepositoryInstructions();
  });
  $('repositoryInstructionsText').addEventListener('change', (event) => {
    if (!selectedRepository) return;
    const next = { ...repositoryInstructions };
    const value = String(event.target.value || '').trim();
    if (value) next[selectedRepository] = value;
    else delete next[selectedRepository];
    saveRepositoryInstructions(next);
  });
  $('forgetRepositoryInstructions').addEventListener('click', () => {
    if (!selectedRepository) return;
    const next = { ...repositoryInstructions };
    delete next[selectedRepository];
    saveRepositoryInstructions(next);
  });
  $('reviewSelfCritique').addEventListener('change', () => save('reviewSelfCritique', $('reviewSelfCritique').checked));
  $('reviewSupervisorEnabled').addEventListener('change', () => save('reviewSupervisorEnabled', $('reviewSupervisorEnabled').checked));
  $('reviewBuiltInRules').addEventListener('change', () => save('reviewBuiltInRules', $('reviewBuiltInRules').checked));
  $('reviewSecondReviewerModel').addEventListener('change', () => save('reviewSecondReviewerModel', secondReviewerValue()));
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
      state = msg.state;
      $('provider').value = state.provider;
      $('claudeModel').value = state.reviewModel;
      $('effort').value = state.reviewEffort;
      $('baseUrl').value = state.githubBaseUrl;
      $('inheritMcp').checked = state.copilotInheritMcp === true;
      $('reviewAutoEnableMcp').checked = state.copilotAutoEnableMcpOnReview === true;
      $('copilotConfigDir').value = state.copilotConfigDir || '';
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
      if (msg.refreshingModels === true) setModelsRefreshing(true);
      applyProviderVisibility(state.provider);
    } else if (msg.type === 'models') {
      setModelsRefreshing(false);
      renderCopilotModels(msg.copilotModels || [], copilotModelValue(), secondReviewerValue());
      // The automatic refresh on open stays silent when it succeeds; failures always explain why
      // the list may be stale.
      if (msg.quiet !== true || msg.ok === false) {
        setStatus(msg.message || (msg.ok === false ? 'Could not refresh models.' : 'Model list refreshed.'), msg.ok === false ? 'error' : 'ok');
      }
    } else if (msg.type === 'saveResult') {
      if (typeof msg.requestId === 'number' && msg.requestId !== latestSaveRequestId) return;
      setStatus(msg.ok ? (msg.message || 'Saved.') : (msg.message || 'Could not save setting.'), msg.ok ? 'ok' : 'error');
    } else if (msg.type === 'testResult') {
      $('testConnection').textContent = 'Check connection';
      setStatus(msg.ok ? (msg.message || 'Connection looks good.') : (msg.message || 'Connection check failed.'), msg.ok ? 'ok' : 'error');
    }
  });

  vscode.postMessage({ type: 'ready' });`;
}
