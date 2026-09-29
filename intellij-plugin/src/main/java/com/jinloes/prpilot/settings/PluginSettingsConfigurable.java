package com.jinloes.prpilot.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.jinloes.prpilot.services.PRNotificationService;
import java.util.function.Consumer;
import javax.swing.*;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

public class PluginSettingsConfigurable implements Configurable {

    private PluginSettingsComponent component;

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "PR Pilot";
    }

    @Override
    public @Nullable JComponent createComponent() {
        component = new PluginSettingsComponent();
        return component.getPanel();
    }

    @Override
    public boolean isModified() {
        PluginSettings s = PluginSettings.getInstance();
        return !component.getGithubBaseUrl().equals(s.getGithubBaseUrl())
                || component.isNotificationsEnabled() != s.isNotificationsEnabled()
                || component.isNotifyReviewRequested() != s.isNotifyReviewRequested()
                || component.isNotifyStarredRepos() != s.isNotifyStarredRepos()
                || component.getNotificationPollMinutes() != s.getNotificationPollMinutes()
                || !component.getReviewModel().equals(s.getReviewModel())
                || !component.getReviewModelCopilot().equals(s.getReviewModelCopilot())
                || component.getReviewProvider() != s.getReviewProvider()
                || !component.getReviewEffort().equals(s.getReviewEffort())
                || component.isCopilotInheritMcp() != s.isCopilotInheritMcp()
                || component.isCopilotAutoEnableMcpOnReview() != s.isCopilotAutoEnableMcpOnReview()
                || !component.getCopilotConfigDir().equals(s.getCopilotConfigDir())
                || !component.getReviewFocusAreas().equals(s.getReviewFocusAreas())
                || !component.getReviewCustomInstructions().equals(s.getReviewCustomInstructions())
                || !component.getReviewGuidanceGlobs().equals(s.getReviewGuidanceGlobsRaw())
                || !component.getReviewGuidanceProfiles().equals(s.getReviewGuidanceProfiles())
                || !component
                        .getActiveReviewGuidanceProfileId()
                        .equals(s.getActiveReviewGuidanceProfileId())
                || !component
                        .getRepositoryReviewInstructions()
                        .equals(s.getRepositoryReviewInstructions())
                || component.isReviewSelfCritique() != s.isReviewSelfCritique()
                || component.isReviewSupervisorEnabled() != s.isReviewSupervisorEnabled()
                || !component
                        .getReviewSecondReviewerModel()
                        .equals(s.getReviewSecondReviewerModel())
                || component.isExperimentalIntellijAssistedReview()
                        != s.isExperimentalIntellijAssistedReview();
    }

    @Override
    public void apply() throws ConfigurationException {
        PluginSettings s = PluginSettings.getInstance();
        String githubBaseUrl;
        try {
            githubBaseUrl = GithubBaseUrlValidator.normalize(component.getGithubBaseUrl());
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException(e.getMessage(), "Invalid GitHub base URL");
        }
        String oversizedRepository = component.repositoryWithOversizedInstructions();
        if (oversizedRepository != null) {
            throw new ConfigurationException(
                    "Instructions for "
                            + oversizedRepository
                            + " are limited to "
                            + RepositoryReviewInstructions.MAX_INSTRUCTIONS_LENGTH
                            + " characters.",
                    "Remembered repository instructions");
        }
        boolean notificationScopeChanged =
                !githubBaseUrl.equals(s.getGithubBaseUrl())
                        || component.isNotificationsEnabled() != s.isNotificationsEnabled()
                        || component.isNotifyReviewRequested() != s.isNotifyReviewRequested()
                        || component.isNotifyStarredRepos() != s.isNotifyStarredRepos();
        s.setGithubBaseUrl(githubBaseUrl);
        s.setNotificationsEnabled(component.isNotificationsEnabled());
        s.setNotifyReviewRequested(component.isNotifyReviewRequested());
        s.setNotifyStarredRepos(component.isNotifyStarredRepos());
        s.setNotificationPollMinutes(component.getNotificationPollMinutes());
        s.setReviewModel(component.getReviewModel());
        s.setReviewModelCopilot(component.getReviewModelCopilot());
        s.setReviewProvider(component.getReviewProvider());
        s.setReviewEffort(component.getReviewEffort());
        s.setCopilotInheritMcp(component.isCopilotInheritMcp());
        s.setCopilotAutoEnableMcpOnReview(component.isCopilotAutoEnableMcpOnReview());
        s.setCopilotConfigDir(component.getCopilotConfigDir());
        s.setReviewFocusAreas(component.getReviewFocusAreas());
        s.setReviewCustomInstructions(component.getReviewCustomInstructions());
        s.setReviewGuidanceGlobs(component.getReviewGuidanceGlobs());
        s.setReviewGuidanceProfiles(component.getReviewGuidanceProfiles());
        s.setActiveReviewGuidanceProfileId(component.getActiveReviewGuidanceProfileId());
        s.setRepositoryReviewInstructions(component.getRepositoryReviewInstructions());
        s.setReviewSelfCritique(component.isReviewSelfCritique());
        s.setReviewSupervisorEnabled(component.isReviewSupervisorEnabled());
        s.setReviewSecondReviewerModel(component.getReviewSecondReviewerModel());
        s.setExperimentalIntellijAssistedReview(component.isExperimentalIntellijAssistedReview());

        // Restart/stop polling to reflect the new settings immediately
        PRNotificationService svc = PRNotificationService.getInstance();
        if (notificationScopeChanged) svc.resetSeenState();
        if (s.isNotificationsEnabled()) {
            svc.startPolling(s.getNotificationPollMinutes());
        } else {
            svc.stopPolling();
        }
    }

    @Override
    public void reset() {
        PluginSettings s = PluginSettings.getInstance();
        component.setNotificationsEnabled(s.isNotificationsEnabled());
        component.setNotifyReviewRequested(s.isNotifyReviewRequested());
        component.setNotifyStarredRepos(s.isNotifyStarredRepos());
        component.setNotificationPollMinutes(s.getNotificationPollMinutes());
        component.setReviewModel(s.getReviewModel());
        component.setReviewModelCopilot(s.getReviewModelCopilot());
        component.setReviewProvider(s.getReviewProvider());
        component.setReviewEffort(s.getReviewEffort());
        component.setCopilotInheritMcp(s.isCopilotInheritMcp());
        component.setCopilotAutoEnableMcpOnReview(s.isCopilotAutoEnableMcpOnReview());
        component.setCopilotConfigDir(s.getCopilotConfigDir());
        component.setReviewFocusAreas(s.getReviewFocusAreas());
        component.setReviewCustomInstructions(s.getReviewCustomInstructions());
        component.setReviewGuidanceGlobs(s.getReviewGuidanceGlobsRaw());
        component.setReviewGuidanceProfiles(s.getReviewGuidanceProfiles());
        component.setActiveReviewGuidanceProfileId(s.getActiveReviewGuidanceProfileId());
        component.setRepositoryReviewInstructions(s.getRepositoryReviewInstructions());
        component.setReviewSelfCritique(s.isReviewSelfCritique());
        component.setReviewSupervisorEnabled(s.isReviewSupervisorEnabled());
        component.setReviewSecondReviewerModel(s.getReviewSecondReviewerModel());
        component.setExperimentalIntellijAssistedReview(s.isExperimentalIntellijAssistedReview());
        loadGithubBaseUrlAndRefresh(
                s.getGithubBaseUrl(), component::setGithubBaseUrl, component::refreshAuthStatus);
    }

    static void loadGithubBaseUrlAndRefresh(
            String persistedBaseUrl, Consumer<String> baseUrlSetter, Runnable refresh) {
        baseUrlSetter.accept(persistedBaseUrl);
        refresh.run();
    }

    @Override
    public void disposeUIResources() {
        component = null;
    }
}
