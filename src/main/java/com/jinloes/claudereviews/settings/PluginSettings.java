package com.jinloes.claudereviews.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.jinloes.claudereviews.services.GitHubAuthService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@State(name = "ClaudeReviewSettings", storages = @Storage("claudeReviews.xml"))
public class PluginSettings implements PersistentStateComponent<PluginSettings.State> {

    public static class State {
        /** Base URL of GitHub instance, e.g. https://github.com or https://github.mycompany.com */
        public String githubBaseUrl = "https://github.com";

        /** Cached display name of the authenticated user. */
        public String githubUsername = "";

        /** Whether background PR notifications are enabled. */
        public boolean notificationsEnabled = false;

        /** Notify when a review is requested from the current user. */
        public boolean notifyReviewRequested = true;

        /** Notify when a new PR is opened on a starred repository. */
        public boolean notifyStarredRepos = false;

        /** Poll interval in minutes. */
        public int notificationPollMinutes = 5;
    }

    private State myState = new State();
    private final GitHubAuthService authService = new GitHubAuthService();

    public static PluginSettings getInstance() {
        return ApplicationManager.getApplication().getService(PluginSettings.class);
    }

    @Override
    public @Nullable State getState() {
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        myState = state;
    }

    public String getGithubBaseUrl() {
        return myState.githubBaseUrl != null ? myState.githubBaseUrl : "https://github.com";
    }

    public void setGithubBaseUrl(String url) {
        myState.githubBaseUrl =
                url != null ? url.stripTrailing().replaceAll("/$", "") : "https://github.com";
    }

    /**
     * Returns the REST API base URL derived from the GitHub base URL. github.com →
     * https://api.github.com github.company.com → https://github.company.com/api/v3
     */
    public String getApiBaseUrl() {
        String base = getGithubBaseUrl();
        return base.equals("https://github.com") ? "https://api.github.com" : base + "/api/v3";
    }

    public String getGithubUsername() {
        return myState.githubUsername != null ? myState.githubUsername : "";
    }

    public void setGithubUsername(String username) {
        myState.githubUsername = username;
    }

    public boolean isNotificationsEnabled() {
        return myState.notificationsEnabled;
    }

    public void setNotificationsEnabled(boolean v) {
        myState.notificationsEnabled = v;
    }

    public boolean isNotifyReviewRequested() {
        return myState.notifyReviewRequested;
    }

    public void setNotifyReviewRequested(boolean v) {
        myState.notifyReviewRequested = v;
    }

    public boolean isNotifyStarredRepos() {
        return myState.notifyStarredRepos;
    }

    public void setNotifyStarredRepos(boolean v) {
        myState.notifyStarredRepos = v;
    }

    public int getNotificationPollMinutes() {
        return myState.notificationPollMinutes > 0 ? myState.notificationPollMinutes : 5;
    }

    public void setNotificationPollMinutes(int v) {
        myState.notificationPollMinutes = Math.max(1, v);
    }

    /**
     * Resolves the GitHub token via the local {@code gh} CLI. Returns {@code null} if the CLI is
     * not installed or not authenticated.
     */
    public @Nullable String getGithubToken() {
        try {
            return authService.resolveToken(getGithubBaseUrl());
        } catch (Exception e) {
            return null;
        }
    }

    /** Returns true if {@code gh auth token} succeeds (i.e. the user is authenticated). */
    public boolean isSignedIn() {
        String token = getGithubToken();
        return token != null && !token.isBlank();
    }
}
