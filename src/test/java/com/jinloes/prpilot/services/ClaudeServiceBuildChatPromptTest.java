package com.jinloes.prpilot.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.ChatMessage;
import com.jinloes.prpilot.model.ChatMessage.Role;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ClaudeServiceBuildChatPromptTest {

    @Nested
    class HistoryTurns {

        @Test
        void userTurnWrappedInUserTag() {
            List<ChatMessage> history = List.of(new ChatMessage(Role.USER, "hello"));
            String prompt = ClaudeService.buildChatPrompt("", history, "follow up");
            assertThat(prompt).contains("<turn role=\"user\">\nhello\n</turn>");
        }

        @Test
        void assistantTurnWrappedInAssistantTag() {
            List<ChatMessage> history = List.of(new ChatMessage(Role.ASSISTANT, "hi there"));
            String prompt = ClaudeService.buildChatPrompt("", history, "follow up");
            assertThat(prompt).contains("<turn role=\"assistant\">\nhi there\n</turn>");
        }

        @Test
        void multiTurnHistory_allTurnsPresent() {
            List<ChatMessage> history =
                    List.of(
                            new ChatMessage(Role.USER, "first question"),
                            new ChatMessage(Role.ASSISTANT, "first answer"),
                            new ChatMessage(Role.USER, "second question"));
            String prompt = ClaudeService.buildChatPrompt("", history, "third question");
            assertThat(prompt).contains("<turn role=\"user\">\nfirst question\n</turn>");
            assertThat(prompt).contains("<turn role=\"assistant\">\nfirst answer\n</turn>");
            assertThat(prompt).contains("<turn role=\"user\">\nsecond question\n</turn>");
        }

        @Test
        void emptyHistory_noTurnTags() {
            String prompt = ClaudeService.buildChatPrompt("", List.of(), "a question");
            assertThat(prompt).doesNotContain("<turn");
        }
    }

    @Nested
    class UserMessage {

        @Test
        void userMessageWrappedInUserMessageTag() {
            String prompt = ClaudeService.buildChatPrompt("", List.of(), "my message");
            assertThat(prompt).contains("<user_message>\nmy message\n</user_message>");
        }

        @Test
        void userMessageAppearsAfterHistory() {
            List<ChatMessage> history = List.of(new ChatMessage(Role.USER, "prior turn"));
            String prompt = ClaudeService.buildChatPrompt("", history, "new message");
            assertThat(prompt.indexOf("<turn role=\"user\">\nprior turn"))
                    .isLessThan(prompt.indexOf("<user_message>"));
        }
    }

    @Nested
    class PrContext {

        @Test
        void nonBlankPrContext_appearsBeforeHistorySeparator() {
            String prompt =
                    ClaudeService.buildChatPrompt("## PR context here", List.of(), "question");
            assertThat(prompt).contains("## PR context here");
            assertThat(prompt.indexOf("## PR context here"))
                    .isLessThan(prompt.indexOf("<user_message>"));
        }

        @Test
        void nonBlankPrContext_separatorPresent() {
            String prompt = ClaudeService.buildChatPrompt("some context", List.of(), "question");
            assertThat(prompt).contains("some context\n\n---\n\n");
        }

        @Test
        void blankPrContext_separatorAbsent() {
            String prompt = ClaudeService.buildChatPrompt("", List.of(), "question");
            assertThat(prompt).doesNotContain("---");
        }
    }
}
