package com.jinloes.prpilot.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.ReviewProvider;
import org.junit.jupiter.api.Test;

class PluginSettingsTest {

    @Test
    void reviewProviderDefaultsToClaude() {
        PluginSettings s = new PluginSettings();
        assertThat(s.getReviewProvider()).isEqualTo(ReviewProvider.CLAUDE);
    }

    @Test
    void setReviewProviderRoundTrips() {
        PluginSettings s = new PluginSettings();
        s.setReviewProvider(ReviewProvider.COPILOT);
        assertThat(s.getReviewProvider()).isEqualTo(ReviewProvider.COPILOT);
        s.setReviewProvider(ReviewProvider.CLAUDE);
        assertThat(s.getReviewProvider()).isEqualTo(ReviewProvider.CLAUDE);
    }

    @Test
    void setReviewProviderNullDefaultsToClaude() {
        PluginSettings s = new PluginSettings();
        s.setReviewProvider(ReviewProvider.COPILOT);
        s.setReviewProvider(null);
        assertThat(s.getReviewProvider()).isEqualTo(ReviewProvider.CLAUDE);
    }

    @Test
    void loadStateWithUnknownProviderIdFallsBackToClaude() {
        PluginSettings s = new PluginSettings();
        PluginSettings.State state = new PluginSettings.State();
        state.reviewProvider = "gemini-cli";
        s.loadState(state);
        assertThat(s.getReviewProvider()).isEqualTo(ReviewProvider.CLAUDE);
    }

    @Test
    void loadStateWithCopilotIdResolvesCopilot() {
        PluginSettings s = new PluginSettings();
        PluginSettings.State state = new PluginSettings.State();
        state.reviewProvider = "copilot";
        s.loadState(state);
        assertThat(s.getReviewProvider()).isEqualTo(ReviewProvider.COPILOT);
    }

    @Test
    void reviewModelCopilotDefaultsToSonnet() {
        PluginSettings s = new PluginSettings();
        assertThat(s.getReviewModelCopilot()).isEqualTo("claude-sonnet-4.6");
    }

    @Test
    void reviewModelCopilotCanBeBlankedToFallBackToCliDefault() {
        PluginSettings s = new PluginSettings();
        s.setReviewModelCopilot("");
        assertThat(s.getReviewModelCopilot()).isEmpty();
    }

    @Test
    void reviewModelCopilotRoundTrips() {
        PluginSettings s = new PluginSettings();
        s.setReviewModelCopilot("gpt-5.4");
        assertThat(s.getReviewModelCopilot()).isEqualTo("gpt-5.4");
        s.setReviewModelCopilot(null);
        assertThat(s.getReviewModelCopilot()).isEmpty();
    }

    @Test
    void reviewEffortDefaultsToMedium() {
        PluginSettings s = new PluginSettings();
        assertThat(s.getReviewEffort()).isEqualTo("medium");
    }

    @Test
    void reviewEffortRoundTrips() {
        PluginSettings s = new PluginSettings();
        s.setReviewEffort("high");
        assertThat(s.getReviewEffort()).isEqualTo("high");
    }

    @Test
    void reviewEffortBlankFallsBackToMedium() {
        PluginSettings s = new PluginSettings();
        s.setReviewEffort("");
        assertThat(s.getReviewEffort()).isEqualTo("medium");
    }

    @Test
    void reviewEffortNullFallsBackToMedium() {
        PluginSettings s = new PluginSettings();
        s.setReviewEffort(null);
        assertThat(s.getReviewEffort()).isEqualTo("medium");
    }

    @Test
    void copilotInheritMcpDefaultsToTrue() {
        PluginSettings s = new PluginSettings();
        assertThat(s.isCopilotInheritMcp()).isTrue();
    }

    @Test
    void copilotInheritMcpRoundTrips() {
        PluginSettings s = new PluginSettings();
        s.setCopilotInheritMcp(false);
        assertThat(s.isCopilotInheritMcp()).isFalse();
        s.setCopilotInheritMcp(true);
        assertThat(s.isCopilotInheritMcp()).isTrue();
    }

    @Test
    void copilotConfigDirDefaultsToEmpty() {
        PluginSettings s = new PluginSettings();
        assertThat(s.getCopilotConfigDir()).isEmpty();
    }

    @Test
    void copilotConfigDirTrimsAndRoundTrips() {
        PluginSettings s = new PluginSettings();
        s.setCopilotConfigDir("  /custom/.copilot  ");
        assertThat(s.getCopilotConfigDir()).isEqualTo("/custom/.copilot");
        s.setCopilotConfigDir(null);
        assertThat(s.getCopilotConfigDir()).isEmpty();
    }

    @Test
    void activeModelReflectsSelectedProvider() {
        PluginSettings s = new PluginSettings();
        s.setReviewModel("claude-opus-4-7");
        s.setReviewModelCopilot("gpt-5.4");

        s.setReviewProvider(ReviewProvider.CLAUDE);
        assertThat(s.getActiveReviewModel()).isEqualTo("claude-opus-4-7");

        s.setReviewProvider(ReviewProvider.COPILOT);
        assertThat(s.getActiveReviewModel()).isEqualTo("gpt-5.4");
    }
}
