package com.jinloes.claudereviews.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AnthropicApiKeyLocatorTest {

    // ── fromKeychain ─────────────────────────────────────────────────────────

    @Nested
    class FromKeychain {

        @Test
        void returnsKeyWhenCommandSucceeds() {
            // Simulate `security` returning a valid key
            AnthropicApiKeyLocator locator = locatorWithKeychainOutput("sk-ant-api03-test123", 0);

            assertThat(locator.fromKeychain()).contains("sk-ant-api03-test123");
        }

        @Test
        void returnsEmptyWhenCommandFails() {
            AnthropicApiKeyLocator locator = locatorWithKeychainOutput("", 1);

            assertThat(locator.fromKeychain()).isEmpty();
        }

        @Test
        void returnsEmptyWhenOutputIsBlank() {
            AnthropicApiKeyLocator locator = locatorWithKeychainOutput("   ", 0);

            assertThat(locator.fromKeychain()).isEmpty();
        }

        @Test
        void returnsEmptyWhenCommandNotFound() {
            // Non-existent binary causes IOException
            AnthropicApiKeyLocator locator =
                    new AnthropicApiKeyLocator(
                            List.of("/nonexistent/binary/that/does/not/exist"), k -> null);

            assertThat(locator.fromKeychain()).isEmpty();
        }

        @Test
        void trimsWhitespaceFromOutput() {
            AnthropicApiKeyLocator locator =
                    locatorWithKeychainOutput("  sk-ant-api03-trimmed  \n", 0);

            assertThat(locator.fromKeychain()).contains("sk-ant-api03-trimmed");
        }
    }

    // ── fromEnv ──────────────────────────────────────────────────────────────

    @Nested
    class FromEnv {

        @Test
        void returnsKeyWhenEnvVarSet() {
            AnthropicApiKeyLocator locator = locatorWithEnv("sk-ant-api03-from-env");

            assertThat(locator.fromEnv()).contains("sk-ant-api03-from-env");
        }

        @Test
        void returnsEmptyWhenEnvVarAbsent() {
            AnthropicApiKeyLocator locator = locatorWithEnv(null);

            assertThat(locator.fromEnv()).isEmpty();
        }

        @Test
        void returnsEmptyWhenEnvVarBlank() {
            AnthropicApiKeyLocator locator = locatorWithEnv("   ");

            assertThat(locator.fromEnv()).isEmpty();
        }
    }

    // ── locate (priority) ────────────────────────────────────────────────────

    @Nested
    class Locate {

        @Test
        void keychainTakesPriorityOverEnv() {
            AnthropicApiKeyLocator locator =
                    new AnthropicApiKeyLocator(echoCommand("sk-ant-keychain"), k -> "sk-ant-env");

            assertThat(locator.locate()).contains("sk-ant-keychain");
        }

        @Test
        void fallsBackToEnvWhenKeychainFails() {
            AnthropicApiKeyLocator locator =
                    new AnthropicApiKeyLocator(failCommand(), k -> "sk-ant-from-env");

            assertThat(locator.locate()).contains("sk-ant-from-env");
        }

        @Test
        void returnsEmptyWhenBothSourcesFail() {
            AnthropicApiKeyLocator locator = new AnthropicApiKeyLocator(failCommand(), k -> null);

            assertThat(locator.locate()).isEmpty();
        }

        @Test
        void returnsEmptyWhenKeychainFailsAndEnvBlank() {
            AnthropicApiKeyLocator locator = new AnthropicApiKeyLocator(failCommand(), k -> "");

            assertThat(locator.locate()).isEmpty();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Builds a locator whose keychain command prints {@code output} and exits {@code code}. */
    private static AnthropicApiKeyLocator locatorWithKeychainOutput(String output, int exitCode) {
        List<String> cmd =
                exitCode == 0
                        ? List.of("sh", "-c", "printf '%s' '" + output.replace("'", "'\\''") + "'")
                        : List.of("sh", "-c", "exit 1");
        return new AnthropicApiKeyLocator(cmd, k -> null);
    }

    private static AnthropicApiKeyLocator locatorWithEnv(String value) {
        return new AnthropicApiKeyLocator(failCommand(), k -> value);
    }

    /** A keychain command that exits successfully with the given value. */
    private static List<String> echoCommand(String value) {
        return List.of("sh", "-c", "printf '%s' '" + value + "'");
    }

    /** A keychain command that always fails with exit code 1. */
    private static List<String> failCommand() {
        return List.of("sh", "-c", "exit 1");
    }

    // Ensure the constants are the documented values
    @Test
    void keychainServiceNameIsClaudeCode() {
        assertThat(AnthropicApiKeyLocator.KEYCHAIN_SERVICE).isEqualTo("Claude Code");
    }

    @Test
    void envVarNameIsAnthropicApiKey() {
        assertThat(AnthropicApiKeyLocator.ENV_VAR).isEqualTo("ANTHROPIC_API_KEY");
    }

    // fromKeychain returns Optional, not raw value with possible whitespace leakage
    @Test
    void returnedKeyDoesNotContainNewline() {
        AnthropicApiKeyLocator locator = locatorWithKeychainOutput("sk-ant-key\n", 0);
        Optional<String> result = locator.fromKeychain();
        assertThat(result).isPresent();
        assertThat(result.get()).doesNotContain("\n");
    }
}
