package com.jinloes.claudereviews.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PRToolWindowFriendlyErrorTest {

    @Nested
    class FriendlyError {

        @Test
        void nullMessage() {
            Exception ex = new RuntimeException((String) null);
            assertThat(PRToolWindow.friendlyError(ex))
                    .isEqualTo("Unexpected error — check IDE logs.");
        }

        @Test
        void unauthorizedMessage() {
            Exception ex = new RuntimeException("HTTP 401 Unauthorized");
            assertThat(PRToolWindow.friendlyError(ex))
                    .isEqualTo("GitHub authentication failed — run `gh auth login` in a terminal.");
        }

        @Test
        void unauthorizedLowercase() {
            Exception ex = new RuntimeException("unauthorized access denied");
            assertThat(PRToolWindow.friendlyError(ex))
                    .isEqualTo("GitHub authentication failed — run `gh auth login` in a terminal.");
        }

        @Test
        void connectionRefused() {
            Exception ex = new RuntimeException("Connection refused: localhost:443");
            assertThat(PRToolWindow.friendlyError(ex))
                    .isEqualTo("Could not reach GitHub — check your connection.");
        }

        @Test
        void failedToConnect() {
            Exception ex = new RuntimeException("Failed to connect to api.github.com");
            assertThat(PRToolWindow.friendlyError(ex))
                    .isEqualTo("Could not reach GitHub — check your connection.");
        }

        @Test
        void unknownHost() {
            Exception ex = new RuntimeException("Unknown host: github.example.com");
            assertThat(PRToolWindow.friendlyError(ex))
                    .isEqualTo("Could not reach GitHub — check your connection.");
        }

        @Test
        void notFound() {
            Exception ex = new RuntimeException("HTTP 404 Not Found");
            assertThat(PRToolWindow.friendlyError(ex))
                    .isEqualTo("Not found on GitHub — the PR or repo may have been deleted.");
        }

        @Test
        void genericMessage() {
            Exception ex = new RuntimeException("Something unexpected happened");
            assertThat(PRToolWindow.friendlyError(ex)).isEqualTo("Something unexpected happened");
        }
    }
}
