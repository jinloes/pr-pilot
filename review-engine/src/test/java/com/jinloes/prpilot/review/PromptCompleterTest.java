package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PromptCompleterTest {

    @Nested
    class ForProvider {
        @Test
        void rejectsAnUnknownProvider() {
            assertThatThrownBy(() -> PromptCompleter.forProvider("gemini", "/tmp", "", "", ""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("gemini");
        }

        @Test
        void acceptsBothSupportedProviders() {
            assertThat(PromptCompleter.forProvider("claude", "/tmp", "", "", "")).isNotNull();
            assertThat(PromptCompleter.forProvider("copilot", "/tmp", "m", "high", "")).isNotNull();
        }
    }

    @Nested
    class Complete {
        @Test
        void returnsTheProviderAnswerAndPassesTheTimeout() throws Exception {
            AtomicLong timeout = new AtomicLong();
            PromptCompleter completer =
                    new PromptCompleter(
                            (prompt, timeoutMillis, onStatus) -> {
                                timeout.set(timeoutMillis);
                                return "answer to " + prompt;
                            });

            assertThat(completer.complete("q", 1234)).isEqualTo("answer to q");
            assertThat(timeout.get()).isEqualTo(1234);
        }

        @Test
        void mapsANullAnswerToEmpty() throws Exception {
            PromptCompleter completer = new PromptCompleter((prompt, timeout, onStatus) -> null);

            assertThat(completer.complete("q", 1)).isEmpty();
        }

        @Test
        void rejectsABlankPromptOrNonPositiveTimeout() {
            PromptCompleter completer = new PromptCompleter((prompt, timeout, onStatus) -> "x");

            assertThatThrownBy(() -> completer.complete(" ", 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> completer.complete("q", 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
