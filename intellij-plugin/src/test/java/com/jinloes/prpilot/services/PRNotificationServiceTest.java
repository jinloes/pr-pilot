package com.jinloes.prpilot.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PRNotificationServiceTest {

    @Nested
    class FormatPollStatus {

        @Test
        void noPollYetReturnsNull() {
            String result = PRNotificationService.formatPollStatus(0L, null, 10_000L);
            assertThat(result).isNull();
        }

        @Test
        void successStatusUsesLastPolledPrefix() {
            String result = PRNotificationService.formatPollStatus(9_000L, null, 10_000L);
            assertThat(result).isEqualTo("Last polled: 1s ago");
        }

        @Test
        void errorStatusIncludesErrorText() {
            String result =
                    PRNotificationService.formatPollStatus(
                            120_000L, PRNotificationService.AUTH_MISSING_ERROR, 180_000L);
            assertThat(result)
                    .isEqualTo(
                            "Last poll: 1 min ago — Error: "
                                    + PRNotificationService.AUTH_MISSING_ERROR);
        }
    }

    @Nested
    class SanitizeError {

        @Test
        void plainMessageIsReturnedAsIs() {
            String result =
                    PRNotificationService.sanitizeError(new IOException("connection refused"));
            assertThat(result).isEqualTo("connection refused");
        }

        @Test
        void bearerTokenIsRedacted() {
            String result =
                    PRNotificationService.sanitizeError(
                            new IOException(
                                    "401 Unauthorized: Bearer ghp_abc123XYZ token rejected"));
            assertThat(result).doesNotContain("ghp_abc123XYZ");
            assertThat(result).contains("[redacted]");
        }

        @Test
        void tokenKeywordIsRedacted() {
            String result =
                    PRNotificationService.sanitizeError(
                            new IOException("invalid token: ghs_secretvalue"));
            assertThat(result).doesNotContain("ghs_secretvalue");
            assertThat(result).contains("[redacted]");
        }

        @Test
        void caseInsensitiveRedaction() {
            String result =
                    PRNotificationService.sanitizeError(
                            new IOException("TOKEN=abc123secret rejected"));
            assertThat(result).doesNotContain("abc123secret");
            assertThat(result).contains("[redacted]");
        }

        @Test
        void nullMessageFallsBackToUnknownError() {
            String result = PRNotificationService.sanitizeError(new IOException((String) null));
            assertThat(result).isEqualTo("unknown error");
        }

        @Test
        void blankMessageFallsBackToUnknownError() {
            String result = PRNotificationService.sanitizeError(new IOException("   "));
            assertThat(result).isEqualTo("unknown error");
        }
    }
}
