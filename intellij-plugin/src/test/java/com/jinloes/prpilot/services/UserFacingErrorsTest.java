package com.jinloes.prpilot.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.ReviewProvider;
import java.io.IOException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class UserFacingErrorsTest {

    @Nested
    class ForGitHub {

        @Test
        void authErrorsMapToGhLoginGuidance() {
            String msg =
                    UserFacingErrors.forGitHub(
                            new IOException("401 Unauthorized: bad credentials"), "save draft");
            assertThat(msg).contains("gh auth login");
        }

        @Test
        void timeoutErrorsMapToRetryMessage() {
            String msg =
                    UserFacingErrors.forGitHub(
                            new IOException("request timed out after 30s"), "submit review");
            assertThat(msg).contains("timed out").contains("Retry");
        }
    }

    @Nested
    class ForProvider {

        @Test
        void missingBinaryMentionsInstallAction() {
            String msg =
                    UserFacingErrors.forProvider(
                            ReviewProvider.COPILOT,
                            new IOException("Cannot run program \"copilot\": error=2"),
                            "generate a review");
            assertThat(msg).contains("copilot").contains("Install");
        }

        @Test
        void parseErrorsMapToStructuredOutputGuidance() {
            String msg =
                    UserFacingErrors.forProvider(
                            ReviewProvider.CLAUDE,
                            new IOException("Failed to parse review JSON: unexpected token"),
                            "generate a review");
            assertThat(msg).contains("invalid review format").contains("Retry");
        }
    }
}
