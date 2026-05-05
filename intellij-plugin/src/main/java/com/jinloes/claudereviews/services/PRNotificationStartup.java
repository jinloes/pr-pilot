package com.jinloes.claudereviews.services;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.jinloes.claudereviews.settings.PluginSettings;
import org.jetbrains.annotations.NotNull;

/**
 * Starts the PR notification polling when any project is opened, but only if the user has enabled
 * notifications in settings.
 */
@SuppressWarnings("deprecation")
public class PRNotificationStartup implements StartupActivity, DumbAware {

    @Override
    public void runActivity(@NotNull Project project) {
        PluginSettings settings = PluginSettings.getInstance();
        PRNotificationService svc = PRNotificationService.getInstance();
        if (settings.isNotificationsEnabled() && !svc.isPolling()) {
            svc.startPolling(settings.getNotificationPollMinutes());
        }
    }
}
