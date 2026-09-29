package com.jinloes.prpilot.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.ReviewProvider;
import com.jinloes.prpilot.review.CopilotService;
import com.jinloes.prpilot.settings.PluginSettings;
import java.io.IOException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class IntellijClaudeServiceTest {

    @Nested
    class SnapshotReviewRuntimeSettings {

        @Test
        void remainsStableWhenSettingsChange() {
            PluginSettings settings = new PluginSettings();
            PluginSettings.State initial = new PluginSettings.State();
            initial.reviewProvider = "copilot";
            initial.reviewModelCopilot = "generation-model";
            initial.reviewSupervisorEnabled = true;
            settings.loadState(initial);

            IntellijClaudeService.ReviewRuntimeSettings snapshot =
                    IntellijClaudeService.snapshotReviewRuntimeSettings(settings);

            settings.setReviewProvider(ReviewProvider.CLAUDE);
            settings.setReviewModel("later-model");
            assertThat(snapshot.provider()).isEqualTo(ReviewProvider.COPILOT);
            assertThat(snapshot.model()).isEqualTo("generation-model");
            assertThat(snapshot.supervisorEnabled()).isTrue();
        }

        @Test
        void identityChangesWithTheSecondReviewerModel() {
            PluginSettings settings = new PluginSettings();
            Object before =
                    IntellijClaudeService.snapshotReviewRuntimeSettings(settings).identity();

            settings.setReviewSecondReviewerModel("gpt-5.4");
            IntellijClaudeService.ReviewRuntimeSettings after =
                    IntellijClaudeService.snapshotReviewRuntimeSettings(settings);

            assertThat(after.secondReviewerModel()).isEqualTo("gpt-5.4");
            assertThat(after.identity()).isNotEqualTo(before);
        }

        @Test
        void secondReviewerEffortOnlyFollowsACopilotPrimary() {
            PluginSettings settings = new PluginSettings();
            settings.setReviewProvider(ReviewProvider.CLAUDE);
            assertThat(
                            IntellijClaudeService.snapshotReviewRuntimeSettings(settings)
                                    .secondReviewerEffort())
                    .isEqualTo(CopilotService.DEFAULT_REASONING_EFFORT);

            PluginSettings.State copilot = new PluginSettings.State();
            copilot.reviewProvider = "copilot";
            settings.loadState(copilot);
            IntellijClaudeService.ReviewRuntimeSettings snapshot =
                    IntellijClaudeService.snapshotReviewRuntimeSettings(settings);
            assertThat(snapshot.secondReviewerEffort()).isEqualTo(settings.getReviewEffort());
        }
    }

    @Nested
    class ResolveReviewInheritMcp {

        @Test
        void staysDisabledByDefault() {
            assertThat(IntellijClaudeService.resolveReviewInheritMcp(false, false)).isFalse();
        }

        @Test
        void keepsExplicitInheritanceEnabled() {
            assertThat(IntellijClaudeService.resolveReviewInheritMcp(true, false)).isTrue();
        }

        @Test
        void enablesMcpForReviewWhenOptInIsSet() {
            assertThat(IntellijClaudeService.resolveReviewInheritMcp(false, true)).isTrue();
        }
    }

    @Nested
    class FriendlyMessage {

        @Test
        void claudeBinaryMissingMentionsClaudeCode() {
            String msg =
                    IntellijClaudeService.friendlyMessage(
                            ReviewProvider.CLAUDE,
                            new IOException("Cannot run program \"claude\": error=2, No such file"),
                            "generate review");
            assertThat(msg).contains("'claude'").contains("Claude Code");
        }

        @Test
        void copilotBinaryMissingMentionsCopilot() {
            String msg =
                    IntellijClaudeService.friendlyMessage(
                            ReviewProvider.COPILOT,
                            new IOException("Cannot run program \"copilot\": error=2"),
                            "generate review");
            assertThat(msg).contains("'copilot'").contains("GitHub Copilot");
        }

        @Test
        void blankMessageFallsBackToGeneric() {
            String msg =
                    IntellijClaudeService.friendlyMessage(
                            ReviewProvider.CLAUDE, new IOException(""), "generate review");
            assertThat(msg).isEqualTo("Couldn't generate review. Please retry.");
        }

        @Test
        void parseFailuresAreMappedToActionableMessage() {
            String msg =
                    IntellijClaudeService.friendlyMessage(
                            ReviewProvider.COPILOT,
                            new IOException("Failed to parse review JSON: unexpected token"),
                            "generate review");
            assertThat(msg).contains("invalid review format").contains("Retry");
        }

        @Test
        void chatFailureUsesChatOperation() {
            String msg =
                    IntellijClaudeService.friendlyMessage(
                            ReviewProvider.CLAUDE,
                            new IOException("opaque failure"),
                            "answer chat question");

            assertThat(msg).isEqualTo("Couldn't answer chat question. Please retry.");
        }
    }
}
