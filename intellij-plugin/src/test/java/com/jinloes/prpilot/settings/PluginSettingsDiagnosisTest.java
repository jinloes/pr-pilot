package com.jinloes.prpilot.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PluginSettingsDiagnosisTest {

    @Nested
    class ClassifyAuthError {

        @Test
        void noSuchFileClassifiesAsNotInstalled() {
            var diagnosis =
                    PluginSettings.classifyAuthError(
                            new IOException("error=2, No such file or directory"));
            assertThat(diagnosis).isEqualTo(PluginSettings.AuthDiagnosis.NOT_INSTALLED);
        }

        @Test
        void errorCode2ClassifiesAsNotInstalled() {
            var diagnosis =
                    PluginSettings.classifyAuthError(
                            new IOException("Cannot run program \"gh\": error=2"));
            assertThat(diagnosis).isEqualTo(PluginSettings.AuthDiagnosis.NOT_INSTALLED);
        }

        @Test
        void authLoginFailureClassifiesAsNotAuthenticated() {
            var diagnosis =
                    PluginSettings.classifyAuthError(
                            new IOException(
                                    "gh auth token failed — run 'gh auth login' in a terminal"
                                            + " first."));
            assertThat(diagnosis).isEqualTo(PluginSettings.AuthDiagnosis.NOT_AUTHENTICATED);
        }

        @Test
        void timeoutClassifiesAsNotAuthenticated() {
            var diagnosis =
                    PluginSettings.classifyAuthError(
                            new IOException(
                                    "gh auth token timed out — check your GitHub"
                                            + " authentication in a terminal."));
            assertThat(diagnosis).isEqualTo(PluginSettings.AuthDiagnosis.NOT_AUTHENTICATED);
        }
    }
}
