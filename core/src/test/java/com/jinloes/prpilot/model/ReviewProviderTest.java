package com.jinloes.prpilot.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Java port of the former core/jvmTest Kotest suite for ReviewProvider. */
class ReviewProviderTest {

    @Nested
    class FromId {

        @Test
        void resolvesClaudeById() {
            assertThat(ReviewProvider.fromId("claude")).isEqualTo(ReviewProvider.CLAUDE);
        }

        @Test
        void resolvesCopilotById() {
            assertThat(ReviewProvider.fromId("copilot")).isEqualTo(ReviewProvider.COPILOT);
        }

        @Test
        void resolvesAntigravityById() {
            assertThat(ReviewProvider.fromId("antigravity")).isEqualTo(ReviewProvider.ANTIGRAVITY);
        }

        @Test
        void isCaseInsensitive() {
            assertThat(ReviewProvider.fromId("CoPiLoT")).isEqualTo(ReviewProvider.COPILOT);
            assertThat(ReviewProvider.fromId("AnTiGrAvItY")).isEqualTo(ReviewProvider.ANTIGRAVITY);
        }

        @Test
        void fallsBackToClaudeForNull() {
            assertThat(ReviewProvider.fromId(null)).isEqualTo(ReviewProvider.CLAUDE);
        }

        @Test
        void fallsBackToClaudeForBlank() {
            assertThat(ReviewProvider.fromId("   ")).isEqualTo(ReviewProvider.CLAUDE);
        }

        @Test
        void fallsBackToClaudeForAnUnrecognizedId() {
            assertThat(ReviewProvider.fromId("gemini")).isEqualTo(ReviewProvider.CLAUDE);
        }
    }

    @Nested
    class Accessors {

        @Test
        void exposeIdDisplayNameAndBinary() {
            assertThat(ReviewProvider.CLAUDE.getId()).isEqualTo("claude");
            assertThat(ReviewProvider.CLAUDE.getDisplayName()).isEqualTo("Claude Code");
            assertThat(ReviewProvider.CLAUDE.getBinary()).isEqualTo("claude");

            assertThat(ReviewProvider.COPILOT.getId()).isEqualTo("copilot");
            assertThat(ReviewProvider.COPILOT.getDisplayName()).isEqualTo("GitHub Copilot");
            assertThat(ReviewProvider.COPILOT.getBinary()).isEqualTo("copilot");

            assertThat(ReviewProvider.ANTIGRAVITY.getId()).isEqualTo("antigravity");
            assertThat(ReviewProvider.ANTIGRAVITY.getDisplayName()).isEqualTo("Antigravity");
            assertThat(ReviewProvider.ANTIGRAVITY.getBinary()).isEqualTo("agy");
        }
    }
}
