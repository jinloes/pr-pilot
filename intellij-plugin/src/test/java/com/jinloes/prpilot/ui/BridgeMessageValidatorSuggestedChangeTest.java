package com.jinloes.prpilot.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class BridgeMessageValidatorSuggestedChangeTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode saveDraftWith(ObjectNode comment) {
        ObjectNode message =
                MAPPER.createObjectNode()
                        .put("protocolVersion", 1)
                        .put("type", "saveDraft")
                        .put("number", 7)
                        .put("owner", "acme")
                        .put("repo", "platform")
                        .put("saveId", 1);
        ObjectNode result = message.putObject("result");
        result.put("summary", "Summary").put("verdict", "COMMENT");
        result.putArray("lineComments").add(comment);
        return message;
    }

    private static ObjectNode comment() {
        return MAPPER.createObjectNode()
                .put("file", "src/A.java")
                .put("line", 3)
                .put("type", "issue")
                .put("body", "Fix it.");
    }

    @Nested
    class IsValid {
        @Test
        void acceptsACommentWithoutASuggestedChange() {
            assertThat(BridgeMessageValidator.isValid(saveDraftWith(comment()))).isTrue();
        }

        @Test
        void acceptsASuggestedChangeUpToTheCodecLimit() {
            assertThat(
                            BridgeMessageValidator.isValid(
                                    saveDraftWith(comment().put("suggestedChange", "  return a;"))))
                    .isTrue();
            assertThat(
                            BridgeMessageValidator.isValid(
                                    saveDraftWith(
                                            comment().put("suggestedChange", "x".repeat(1_000)))))
                    .isTrue();
        }

        @Test
        void rejectsAnOversizedOrNonStringSuggestedChange() {
            assertThat(
                            BridgeMessageValidator.isValid(
                                    saveDraftWith(
                                            comment().put("suggestedChange", "x".repeat(1_001)))))
                    .isFalse();
            assertThat(
                            BridgeMessageValidator.isValid(
                                    saveDraftWith(comment().put("suggestedChange", 3))))
                    .isFalse();
            assertThat(
                            BridgeMessageValidator.isValid(
                                    saveDraftWith(comment().putNull("suggestedChange"))))
                    .isFalse();
        }
    }
}
