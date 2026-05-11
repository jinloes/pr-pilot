package com.jinloes.prpilot.services;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ClaudeServiceBuildFocusedChatPromptTest {

    @Nested
    class WithContext {

        @Test
        void nonBlankContext_wrappedInCodeContextTags() {
            String prompt =
                    ClaudeService.buildFocusedChatPrompt("int x = 1;", "What does this do?");
            assertThat(prompt).contains("<code_context>\nint x = 1;\n</code_context>");
        }

        @Test
        void nonBlankContext_appearsBeforeUserMessage() {
            String prompt =
                    ClaudeService.buildFocusedChatPrompt("int x = 1;", "What does this do?");
            assertThat(prompt.indexOf("<code_context>"))
                    .isLessThan(prompt.indexOf("<user_message>"));
        }
    }

    @Nested
    class WithBlankContext {

        @Test
        void blankContext_codeContextBlockAbsent() {
            String prompt = ClaudeService.buildFocusedChatPrompt("", "Explain this");
            assertThat(prompt).doesNotContain("<code_context>");
        }

        @Test
        void whitespaceOnlyContext_codeContextBlockAbsent() {
            String prompt = ClaudeService.buildFocusedChatPrompt("   ", "Explain this");
            assertThat(prompt).doesNotContain("<code_context>");
        }
    }

    @Nested
    class UserMessage {

        @Test
        void questionWrappedInUserMessageTags() {
            String prompt = ClaudeService.buildFocusedChatPrompt("", "Is this safe?");
            assertThat(prompt).contains("<user_message>\nIs this safe?\n</user_message>");
        }

        @Test
        void questionAlwaysPresentEvenWithoutContext() {
            String prompt = ClaudeService.buildFocusedChatPrompt("", "My question");
            assertThat(prompt).contains("My question");
        }
    }

    @Nested
    class Persona {

        @Test
        void chatPersonaAppearsAtStart() {
            String prompt = ClaudeService.buildFocusedChatPrompt("code", "question");
            assertThat(prompt).startsWith("You are a senior security engineer.");
        }

        @Test
        void personaAppearsBeforeCodeContext() {
            String prompt = ClaudeService.buildFocusedChatPrompt("code", "question");
            assertThat(prompt.indexOf("senior security engineer"))
                    .isLessThan(prompt.indexOf("<code_context>"));
        }
    }
}
