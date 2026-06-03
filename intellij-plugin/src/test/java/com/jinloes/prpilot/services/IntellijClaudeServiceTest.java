package com.jinloes.prpilot.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.ReviewProvider;
import java.io.IOException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class IntellijClaudeServiceTest {

    @Nested
    class FriendlyMessage {

        @Test
        void claudeBinaryMissingMentionsClaudeCode() {
            String msg =
                    IntellijClaudeService.friendlyMessage(
                            ReviewProvider.CLAUDE,
                            new IOException(
                                    "Cannot run program \"claude\": error=2, No such file"));
            assertThat(msg).contains("'claude'").contains("Claude Code");
        }

        @Test
        void copilotBinaryMissingMentionsCopilot() {
            String msg =
                    IntellijClaudeService.friendlyMessage(
                            ReviewProvider.COPILOT,
                            new IOException("Cannot run program \"copilot\": error=2"));
            assertThat(msg).contains("'copilot'").contains("GitHub Copilot");
        }

        @Test
        void blankMessageFallsBackToGeneric() {
            String msg =
                    IntellijClaudeService.friendlyMessage(
                            ReviewProvider.CLAUDE, new IOException(""));
            assertThat(msg).isEqualTo("Couldn't generate response. Please retry.");
        }

        @Test
        void parseFailuresAreMappedToActionableMessage() {
            String msg =
                    IntellijClaudeService.friendlyMessage(
                            ReviewProvider.COPILOT,
                            new IOException("Failed to parse review JSON: unexpected token"));
            assertThat(msg).contains("invalid review format").contains("Retry");
        }
    }
}
