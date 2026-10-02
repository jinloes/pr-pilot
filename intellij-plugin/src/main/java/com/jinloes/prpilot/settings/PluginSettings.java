package com.jinloes.prpilot.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.jinloes.prpilot.model.ReviewProvider;
import com.jinloes.prpilot.review.RepoGuidelinesReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@State(name = "ClaudeReviewSettings", storages = @Storage("claudeReviews.xml"))
public class PluginSettings implements PersistentStateComponent<PluginSettings.State> {

    public static class ReviewGuidanceProfile {
        public String id = "";
        public String name = "";
        public String focusAreas = "";
        public String customInstructions = "";
        public String guidanceGlobs = "";

        public ReviewGuidanceProfile() {}

        public ReviewGuidanceProfile(
                String id,
                String name,
                String focusAreas,
                String customInstructions,
                String guidanceGlobs) {
            this.id = trim(id);
            this.name = trim(name);
            this.focusAreas = trim(focusAreas);
            this.customInstructions = trim(customInstructions);
            this.guidanceGlobs = trim(guidanceGlobs);
        }

        public ReviewGuidanceProfile copy() {
            return new ReviewGuidanceProfile(
                    id, name, focusAreas, customInstructions, guidanceGlobs);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ReviewGuidanceProfile profile)) {
                return false;
            }
            return Objects.equals(id, profile.id)
                    && Objects.equals(name, profile.name)
                    && Objects.equals(focusAreas, profile.focusAreas)
                    && Objects.equals(customInstructions, profile.customInstructions)
                    && Objects.equals(guidanceGlobs, profile.guidanceGlobs);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, name, focusAreas, customInstructions, guidanceGlobs);
        }

        private static String trim(String value) {
            return value != null ? value.trim() : "";
        }
    }

    public static class State {
        /** Base URL of GitHub instance, e.g. https://github.com or https://github.mycompany.com */
        public String githubBaseUrl = "https://github.com";

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
         * "medium", "high", "xhigh", "max". Defaults to "high" for deeper review reasoning. Only
         * applied when {@code reviewProvider} is COPILOT.
         */
        public String reviewEffort = "high";

        /**
         * When true, the Copilot review/chat session inherits MCP servers from the user's trusted
         * Copilot config ({@code ~/.copilot/mcp-config.json}, or {@code copilotConfigDir}). The
         * SDK's on-disk config discovery is never enabled, so a PR's repo-local {@code .mcp.json}
         * is deliberately ignored. Only applied when {@code reviewProvider} is COPILOT.
         */
        public boolean copilotInheritMcp = false;

        /**
         * When true, Copilot review generation always enables MCP even if the general inheritance
         * toggle is off. This is review-only; chat still follows {@code copilotInheritMcp}.
         */
        public boolean copilotAutoEnableMcpOnReview = false;

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

        /**
         * Newline-separated list of guidance files (literal relative paths or globs like {@code
         * **}{@code /style.md}) scanned from the review working directory and folded into {@code
         * <repo_guidelines>}. Blank falls back to {@link
         * RepoGuidelinesReader#DEFAULT_GUIDANCE_GLOBS}.
         */
        public String reviewGuidanceGlobs = "";

        /**
         * Absolute path of a local folder of extra review rules ({@code .md}, {@code .yaml}, {@code
         * .yml}) appended to the repository guidance of every review. Blank disables it.
         */
        public String reviewRulesDirectory = "";

        /**
         * Saved named review-guidance configurations. The legacy fields form the built-in default.
         */
        public List<ReviewGuidanceProfile> reviewGuidanceProfiles = new ArrayList<>();

        /** ID of the active named review-guidance profile; blank selects the built-in default. */
        public String activeReviewGuidanceProfileId = "";

        /**
         * Review instructions remembered per repository, keyed by lowercase {@code owner/repo}.
         * Applied to every review of that repository ahead of the per-review or default custom
         * instructions.
         */
        public Map<String, String> repositoryReviewInstructions = new LinkedHashMap<>();

        /**
         * When true, review generation runs a second self-critique pass that re-validates each
         * finding against the diff and the same context the first pass saw, dropping misattributed,
         * unsupported, and CI-duplicated ones. On by default: a misattributed comment costs a
         * reviewer more than the extra latency does, and precision is what makes the review worth
         * reading. Turn it off to roughly halve review latency.
         */
        public boolean reviewSelfCritique = true;

        /**
         * Enables bounded coverage supervision and at most one targeted follow-up review pass over
         * uninspected high-risk hunks and changed files. On by default: the follow-up only runs
         * when the primary pass left a coverage gap.
         */
        public boolean reviewSupervisorEnabled = true;

        /**
         * Optional Copilot model that reviews the same diff in parallel with the primary provider.
         * Its candidates are merged and cross-validated with the primary's. Blank disables it.
         */
        public String reviewSecondReviewerModel = "";

        /**
         * Experimental: shows the IntelliJ-assisted review controls in the webview and lets the
         * host accept {@code intellijAssisted} generate requests. Off by default because the mode
         * needs IntelliJ IDEA 262+, a hand-written {@code ~/.pr-pilot/semantic-review.json}, and a
         * manual worktree import per review.
         */
        public boolean experimentalIntellijAssistedReview = false;
    }

    private State myState = new State();

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
        myState.githubBaseUrl = GithubBaseUrlValidator.normalize(url);
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
                : "high";
    }

    public void setReviewEffort(String effort) {
        myState.reviewEffort = effort != null ? effort : "high";
    }

    public boolean isCopilotInheritMcp() {
        return myState.copilotInheritMcp;
    }

    public void setCopilotInheritMcp(boolean inherit) {
        myState.copilotInheritMcp = inherit;
    }

    public boolean isCopilotAutoEnableMcpOnReview() {
        return myState.copilotAutoEnableMcpOnReview;
    }

    public void setCopilotAutoEnableMcpOnReview(boolean enabled) {
        myState.copilotAutoEnableMcpOnReview = enabled;
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

    public String getReviewRulesDirectory() {
        return myState.reviewRulesDirectory != null ? myState.reviewRulesDirectory : "";
    }

    public void setReviewRulesDirectory(String value) {
        myState.reviewRulesDirectory = value != null ? value.trim() : "";
    }

    /** Raw newline-separated guidance-globs text, as edited in settings (may be blank). */
    public String getReviewGuidanceGlobsRaw() {
        return myState.reviewGuidanceGlobs != null ? myState.reviewGuidanceGlobs : "";
    }

    public void setReviewGuidanceGlobs(String value) {
        myState.reviewGuidanceGlobs = value != null ? value.trim() : "";
    }

    /**
     * Parsed guidance globs (one per non-blank line), falling back to {@link
     * RepoGuidelinesReader#DEFAULT_GUIDANCE_GLOBS} when nothing is configured.
     */
    public List<String> getReviewGuidanceGlobs() {
        String raw = getReviewGuidanceGlobsRaw();
        if (raw.isBlank()) {
            return RepoGuidelinesReader.DEFAULT_GUIDANCE_GLOBS;
        }
        List<String> globs =
                raw.lines()
                        .map(String::strip)
                        .filter(s -> !s.isBlank())
                        .collect(java.util.stream.Collectors.toList());
        return globs.isEmpty() ? RepoGuidelinesReader.DEFAULT_GUIDANCE_GLOBS : globs;
    }

    public List<ReviewGuidanceProfile> getReviewGuidanceProfiles() {
        return normalizeProfiles(myState.reviewGuidanceProfiles);
    }

    public void setReviewGuidanceProfiles(List<ReviewGuidanceProfile> profiles) {
        myState.reviewGuidanceProfiles = normalizeProfiles(profiles);
        myState.activeReviewGuidanceProfileId = getActiveReviewGuidanceProfileId();
    }

    public String getActiveReviewGuidanceProfileId() {
        String activeId = trim(myState.activeReviewGuidanceProfileId);
        return getReviewGuidanceProfiles().stream().anyMatch(profile -> profile.id.equals(activeId))
                ? activeId
                : "";
    }

    public void setActiveReviewGuidanceProfileId(String profileId) {
        String candidate = trim(profileId);
        myState.activeReviewGuidanceProfileId =
                getReviewGuidanceProfiles().stream()
                                .anyMatch(profile -> profile.id.equals(candidate))
                        ? candidate
                        : "";
    }

    public String getResolvedReviewFocusAreas() {
        ReviewGuidanceProfile active = getActiveReviewGuidanceProfile();
        return active != null ? active.focusAreas : getReviewFocusAreas();
    }

    public String getResolvedReviewCustomInstructions() {
        ReviewGuidanceProfile active = getActiveReviewGuidanceProfile();
        return active != null ? active.customInstructions : getReviewCustomInstructions();
    }

    public List<String> getResolvedReviewGuidanceGlobs() {
        ReviewGuidanceProfile active = getActiveReviewGuidanceProfile();
        return parseGuidanceGlobs(
                active != null ? active.guidanceGlobs : getReviewGuidanceGlobsRaw());
    }

    private ReviewGuidanceProfile getActiveReviewGuidanceProfile() {
        String activeId = getActiveReviewGuidanceProfileId();
        return getReviewGuidanceProfiles().stream()
                .filter(profile -> profile.id.equals(activeId))
                .findFirst()
                .orElse(null);
    }

    private static List<String> parseGuidanceGlobs(String raw) {
        if (trim(raw).isBlank()) {
            return RepoGuidelinesReader.DEFAULT_GUIDANCE_GLOBS;
        }
        List<String> globs = raw.lines().map(String::strip).filter(s -> !s.isBlank()).toList();
        return globs.isEmpty() ? RepoGuidelinesReader.DEFAULT_GUIDANCE_GLOBS : globs;
    }

    private static List<ReviewGuidanceProfile> normalizeProfiles(
            List<ReviewGuidanceProfile> profiles) {
        if (profiles == null) {
            return new ArrayList<>();
        }
        List<ReviewGuidanceProfile> normalized = new ArrayList<>();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (ReviewGuidanceProfile profile : profiles) {
            if (profile == null) {
                continue;
            }
            ReviewGuidanceProfile copy = profile.copy();
            if (copy.id.isBlank() || copy.name.isBlank() || !ids.add(copy.id)) {
                continue;
            }
            normalized.add(copy);
        }
        return normalized;
    }

    private static String trim(String value) {
        return value != null ? value.trim() : "";
    }

    /** Normalized copy of every remembered repository's instructions. */
    public Map<String, String> getRepositoryReviewInstructions() {
        return RepositoryReviewInstructions.normalize(myState.repositoryReviewInstructions);
    }

    /** Replaces all remembered repository instructions (normalized; blanks are forgotten). */
    public void setRepositoryReviewInstructions(Map<String, String> instructions) {
        myState.repositoryReviewInstructions = RepositoryReviewInstructions.normalize(instructions);
    }

    /** Instructions remembered for {@code owner/repo}; empty when none or the name is invalid. */
    public String getRepositoryReviewInstructions(String owner, String repo) {
        String key = RepositoryReviewInstructions.repositoryKey(owner, repo);
        return key == null ? "" : getRepositoryReviewInstructions().getOrDefault(key, "");
    }

    /**
     * Remembers (or, when blank, forgets) instructions for {@code owner/repo}.
     *
     * @return the stored, trimmed instructions ({@code ""} when forgotten), or {@code null} when
     *     the name is invalid, the text is over the limit, or the repository cap is reached
     */
    public String rememberRepositoryReviewInstructions(
            String owner, String repo, String instructions) {
        String key = RepositoryReviewInstructions.repositoryKey(owner, repo);
        if (key == null) {
            return null;
        }
        Map<String, String> next =
                RepositoryReviewInstructions.with(
                        getRepositoryReviewInstructions(), key, instructions);
        if (next == null) {
            return null;
        }
        myState.repositoryReviewInstructions = next;
        return next.getOrDefault(key, "");
    }

    public boolean isReviewSelfCritique() {
        return myState.reviewSelfCritique;
    }

    public void setReviewSelfCritique(boolean value) {
        myState.reviewSelfCritique = value;
    }

    public boolean isReviewSupervisorEnabled() {
        return myState.reviewSupervisorEnabled;
    }

    public void setReviewSupervisorEnabled(boolean value) {
        myState.reviewSupervisorEnabled = value;
    }

    public String getReviewSecondReviewerModel() {
        return StringUtils.defaultString(myState.reviewSecondReviewerModel).trim();
    }

    public void setReviewSecondReviewerModel(String value) {
        myState.reviewSecondReviewerModel = StringUtils.defaultString(value).trim();
    }

    public boolean isExperimentalIntellijAssistedReview() {
        return myState.experimentalIntellijAssistedReview;
    }

    public void setExperimentalIntellijAssistedReview(boolean value) {
        myState.experimentalIntellijAssistedReview = value;
    }
}
