package com.jinloes.prpilot.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LogRedactorTest {

    @Nested
    class Redact {

        @Test
        void preservesNullEmptyAndSecretFreeText() {
            assertThat(LogRedactor.redact(null)).isNull();
            assertThat(LogRedactor.redact("")).isEmpty();
            assertThat(LogRedactor.redact("Failed to parse review JSON: unexpected token }"))
                    .isEqualTo("Failed to parse review JSON: unexpected token }");
        }

        @Test
        void masksGitHubTokensBearerHeadersApiKeysAndUrlCredentials() {
            String classic = "ghp_" + "a".repeat(36);
            String fineGrained = "github_pat_" + "b".repeat(40);
            String bearer = "Bearer " + "c".repeat(24);
            String anthropic = "sk-ant-" + "d".repeat(24);
            String urlSecret = "ghs_" + "e".repeat(28);
            String raw =
                    "auth "
                            + classic
                            + " / "
                            + fineGrained
                            + "; "
                            + bearer
                            + "; apiKey="
                            + anthropic
                            + "; https://x-access-token:"
                            + urlSecret
                            + "@github.com/acme/widgets.git";

            String redacted = LogRedactor.redact(raw);

            assertThat(redacted)
                    .doesNotContain(classic)
                    .doesNotContain(fineGrained)
                    .doesNotContain("c".repeat(24))
                    .doesNotContain(anthropic)
                    .doesNotContain(urlSecret)
                    .contains("https://<redacted>@github.com/acme/widgets.git");
        }
    }

    @Nested
    class ErrorType {

        @Test
        void returnsSimpleClassNameAndWalksAnonymousSubclasses() {
            assertThat(LogRedactor.errorType(null)).isEqualTo("Exception");
            assertThat(LogRedactor.errorType(new NullPointerException()))
                    .isEqualTo("NullPointerException");
            IOException anonymous = new IOException("") {};
            assertThat(LogRedactor.errorType(anonymous)).isEqualTo("IOException");
        }
    }

    @Nested
    class Sanitize {

        @Test
        void returnsNullAndSecretFreeThrowablesUnchanged() {
            assertThat(LogRedactor.sanitize(null)).isNull();
            IOException plain = new IOException("git fetch timed out after 60s");
            assertThat(LogRedactor.sanitize(plain)).isSameAs(plain);
        }

        @Test
        void redactsMessagesCausesAndSuppressedWhilePreservingClassNamesAndStackTraces() {
            String classic = "ghp_" + "x".repeat(36);
            String bearerVal = "y".repeat(24);
            String keyVal = "z".repeat(20);
            IllegalStateException cause = new IllegalStateException("root token=" + keyVal);
            IOException top = new IOException("401 with Bearer " + bearerVal, cause);
            top.addSuppressed(new RuntimeException("suppressed " + classic));

            Throwable sanitized = LogRedactor.sanitize(top);

            String printed = stackTraceOf(sanitized);
            assertThat(printed)
                    .contains("java.io.IOException: 401 with Bearer <redacted>")
                    .contains("Caused by: java.lang.IllegalStateException: root token=<redacted>")
                    .contains("Suppressed: java.lang.RuntimeException: suppressed <redacted>")
                    .contains(LogRedactorTest.class.getName())
                    .doesNotContain(classic)
                    .doesNotContain(bearerVal)
                    .doesNotContain(keyVal);
        }

        @Test
        void handlesCyclicSuppressedGraphsWithoutInfiniteRecursion() {
            String secret = "ghp_" + "m".repeat(36);
            IOException first = new IOException("first " + secret);
            IOException second = new IOException("second", first);
            first.addSuppressed(second);

            Throwable sanitized = LogRedactor.sanitize(first);

            assertThat(stackTraceOf(sanitized))
                    .contains("java.io.IOException: first <redacted>")
                    .doesNotContain(secret);
        }

        private static String stackTraceOf(Throwable throwable) {
            StringWriter writer = new StringWriter();
            throwable.printStackTrace(new PrintWriter(writer));
            return writer.toString();
        }
    }
}
