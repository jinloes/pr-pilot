package com.jinloes.prpilot.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.jinloes.prpilot.model.ReviewProvider;
import com.jinloes.prpilot.services.GitHubAuthService;
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

        /** Model ID passed to the claude CLI for reviews. Empty string uses the CLI default. */
        public String reviewModel = "";

        /**
         * Model ID passed to the copilot CLI for reviews. Defaults to {@code claude-sonnet-4.6}:
         * strong at structured JSON output and code reasoning at sub-Opus latency. Empty string
         * uses the CLI's default routing.
         */
        public String reviewModelCopilot = "claude-sonnet-4.6";

        /** Backend CLI used to generate reviews and chat replies. */
        public String reviewProvider = ReviewProvider.CLAUDE.getId();

        /**
         * Reasoning effort passed to {@code copilot --reasoning-effort}. One of "none", "low",
         * "medium", "high", "xhigh", "max". Defaults to "medium" — enough depth for real bugs
         * without Opus-tier wall-clock. Only applied when {@code reviewProvider} is COPILOT.
         */
        public String reviewEffort = "medium";

        /**
         * When true, the Copilot review/chat session inherits MCP servers from the Copilot CLI
         * config ({@code ~/.copilot/mcp-config.json}) and any repo-local {@code .mcp.json} via the
         * SDK's config discovery. Only applied when {@code reviewProvider} is COPILOT.
         */
        public boolean copilotInheritMcp = true;

        /**
         * Optional override of the Copilot config directory used to discover MCP servers. Empty
         * uses the CLI default ({@code ~/.copilot}). Only applied when {@code reviewProvider} is
         * COPILOT.
         */
        public String copilotConfigDir = "";

        /** Default focus areas the reviewer should prioritize. Sent as prompt steering context. */
        public String reviewFocusAreas = "";

        /** Default extra instructions appended to every review prompt. */
        public String reviewCustomInstructions = "";
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
        String normalized =
                url != null ? url.stripTrailing().replaceAll("/$", "") : "https://github.com";
        // Reject non-HTTPS to prevent SSRF to local services or plain-HTTP interception
        if (!normalized.startsWith("https://")) {
            normalized = "https://github.com";
        }
        myState.githubBaseUrl = normalized;
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

    public String getReviewModel() {
        return myState.reviewModel != null ? myState.reviewModel : "";
    }

    public void setReviewModel(String model) {
        myState.reviewModel = model != null ? model : "";
    }

    public String getReviewModelCopilot() {
        return myState.reviewModelCopilot != null ? myState.reviewModelCopilot : "";
    }

    public void setReviewModelCopilot(String model) {
        myState.reviewModelCopilot = model != null ? model : "";
    }

    /** Returns the model ID for the currently selected provider. */
    public String getActiveReviewModel() {
        return getReviewProvider() == ReviewProvider.COPILOT
                ? getReviewModelCopilot()
                : getReviewModel();
    }

    public ReviewProvider getReviewProvider() {
        return ReviewProvider.fromId(myState.reviewProvider);
    }

    public void setReviewProvider(ReviewProvider provider) {
        myState.reviewProvider =
                provider != null ? provider.getId() : ReviewProvider.CLAUDE.getId();
    }

    public String getReviewEffort() {
        return myState.reviewEffort != null && !myState.reviewEffort.isBlank()
                ? myState.reviewEffort
                : "medium";
    }

    public void setReviewEffort(String effort) {
        myState.reviewEffort = effort != null ? effort : "medium";
    }

    public boolean isCopilotInheritMcp() {
        return myState.copilotInheritMcp;
    }

    public void setCopilotInheritMcp(boolean inherit) {
        myState.copilotInheritMcp = inherit;
    }

    public String getCopilotConfigDir() {
        return myState.copilotConfigDir != null ? myState.copilotConfigDir : "";
    }

    public void setCopilotConfigDir(String dir) {
        myState.copilotConfigDir = dir != null ? dir.trim() : "";
    }

    public String getReviewFocusAreas() {
        return myState.reviewFocusAreas != null ? myState.reviewFocusAreas : "";
    }

    public void setReviewFocusAreas(String value) {
        myState.reviewFocusAreas = value != null ? value.trim() : "";
    }

    public String getReviewCustomInstructions() {
        return myState.reviewCustomInstructions != null ? myState.reviewCustomInstructions : "";
    }

    public void setReviewCustomInstructions(String value) {
        myState.reviewCustomInstructions = value != null ? value.trim() : "";
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

    /** Diagnosis result returned by {@link #diagnoseAuth()}. */
    public enum AuthDiagnosis {
        OK,
        NOT_AUTHENTICATED,
        NOT_INSTALLED,
    }

    /**
     * Probes GitHub authentication and returns a fine-grained diagnosis so callers can surface
     * actionable guidance instead of silently failing.
     */
    public AuthDiagnosis diagnoseAuth() {
        try {
            String token = authService.resolveToken(getGithubBaseUrl());
            return org.apache.commons.lang3.StringUtils.isNotBlank(token)
                    ? AuthDiagnosis.OK
                    : AuthDiagnosis.NOT_AUTHENTICATED;
        } catch (Exception e) {
            return classifyAuthError(e);
        }
    }

    /** Classifies an exception thrown by the auth service into a diagnosis reason. */
    static AuthDiagnosis classifyAuthError(Exception e) {
        String msg =
                org.apache.commons.lang3.StringUtils.defaultString(e.getMessage()).toLowerCase();
        return (msg.contains("no such file") || msg.contains("error=2"))
                ? AuthDiagnosis.NOT_INSTALLED
                : AuthDiagnosis.NOT_AUTHENTICATED;
    }
}
