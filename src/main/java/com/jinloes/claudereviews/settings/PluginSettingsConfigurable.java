package com.jinloes.claudereviews.settings;

import com.intellij.openapi.options.Configurable;
import com.jinloes.claudereviews.services.PRNotificationService;
import javax.swing.*;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

public class PluginSettingsConfigurable implements Configurable {

    private PluginSettingsComponent component;

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "Claude PR Reviews";
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
                || component.getNotificationPollMinutes() != s.getNotificationPollMinutes();
    }

    @Override
    public void apply() {
        PluginSettings s = PluginSettings.getInstance();
        s.setGithubBaseUrl(component.getGithubBaseUrl());
        s.setNotificationsEnabled(component.isNotificationsEnabled());
        s.setNotifyReviewRequested(component.isNotifyReviewRequested());
        s.setNotifyStarredRepos(component.isNotifyStarredRepos());
        s.setNotificationPollMinutes(component.getNotificationPollMinutes());

        // Restart/stop polling to reflect the new settings immediately
        PRNotificationService svc = PRNotificationService.getInstance();
        if (s.isNotificationsEnabled()) {
            svc.startPolling(s.getNotificationPollMinutes());
        } else {
            svc.stopPolling();
        }
    }

    @Override
    public void reset() {
        PluginSettings s = PluginSettings.getInstance();
        component.setGithubBaseUrl(s.getGithubBaseUrl());
        component.setNotificationsEnabled(s.isNotificationsEnabled());
        component.setNotifyReviewRequested(s.isNotifyReviewRequested());
        component.setNotifyStarredRepos(s.isNotifyStarredRepos());
        component.setNotificationPollMinutes(s.getNotificationPollMinutes());
    }

    @Override
    public void disposeUIResources() {
        component = null;
    }
}
