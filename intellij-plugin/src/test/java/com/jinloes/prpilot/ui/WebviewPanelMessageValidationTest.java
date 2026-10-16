package com.jinloes.prpilot.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class WebviewPanelMessageValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Nested
    class IncomingMessageValidation {

        @Test
        void acceptsKnownMessageWithValidPrIdentity() throws Exception {
            var node =
                    MAPPER.readTree(
                            "{\"protocolVersion\":1,\"type\":\"generateReview\",\"operationId\":\"review-1\",\"number\":7,\"owner\":\"acme\",\"repo\":\"platform\",\"diff\":\"diff --git a/a b/a\"}");

            assertThat(WebviewPanel.isValidIncomingMessage(node)).isTrue();
        }

        @Test
        void rejectsInvalidOrOversizedReviewDiff() throws Exception {
            var nonText =
                    MAPPER.readTree(
                            "{\"protocolVersion\":1,\"type\":\"generateReview\",\"operationId\":\"review-1\",\"number\":7,\"owner\":\"acme\",\"repo\":\"platform\",\"diff\":42}");
            var oversized =
                    MAPPER.createObjectNode()
                            .put("protocolVersion", 1)
                            .put("type", "generateReview")
                            .put("operationId", "review-1")
                            .put("number", 7)
                            .put("owner", "acme")
                            .put("repo", "platform")
                            .put("diff", "x".repeat(1_100_001));

            assertThat(WebviewPanel.isValidIncomingMessage(nonText)).isFalse();
            assertThat(WebviewPanel.isValidIncomingMessage(oversized)).isFalse();
        }

        @Test
        void rejectsPrMessageWithoutOwnerRepoOrNumber() throws Exception {
            var node =
                    MAPPER.readTree(
                            "{\"protocolVersion\":1,\"type\":\"selectPR\",\"number\":0,\"owner\":\"\",\"repo\":\"\"}");

            assertThat(WebviewPanel.isValidIncomingMessage(node)).isFalse();
        }

        @Test
        void rejectsUnknownMessageType() throws Exception {
            var node = MAPPER.readTree("{\"protocolVersion\":1,\"type\":\"surprise\"}");

            assertThat(WebviewPanel.isValidIncomingMessage(node)).isFalse();
        }

        @Test
        void acceptsRunAuthLoginMessage() throws Exception {
            var node = MAPPER.readTree("{\"protocolVersion\":1,\"type\":\"runAuthLogin\"}");

            assertThat(WebviewPanel.isValidIncomingMessage(node)).isTrue();
        }

        @Test
        void validatesWebviewLayoutChangedReason() throws Exception {
            var valid =
                    MAPPER.readTree(
                            "{\"protocolVersion\":1,\"type\":\"webviewLayoutChanged\",\"reason\":\"chat-panel\"}");
            var missing =
                    MAPPER.readTree("{\"protocolVersion\":1,\"type\":\"webviewLayoutChanged\"}");
            var nonText =
                    MAPPER.readTree(
                            "{\"protocolVersion\":1,\"type\":\"webviewLayoutChanged\",\"reason\":42}");
            var oversized =
                    MAPPER.createObjectNode()
                            .put("protocolVersion", 1)
                            .put("type", "webviewLayoutChanged")
                            .put("reason", "x".repeat(4_097));

            assertThat(WebviewPanel.isValidIncomingMessage(valid)).isTrue();
            assertThat(WebviewPanel.isValidIncomingMessage(missing)).isFalse();
            assertThat(WebviewPanel.isValidIncomingMessage(nonText)).isFalse();
            assertThat(WebviewPanel.isValidIncomingMessage(oversized)).isFalse();
        }

        @Test
        void rejectsMalformedNestedReview() throws Exception {
            var node =
                    MAPPER.readTree(
                            "{\"protocolVersion\":1,\"type\":\"saveDraft\",\"number\":7,\"saveId\":1,"
                                    + "\"owner\":\"acme\",\"repo\":\"platform\",\"result\":{"
                                    + "\"summary\":\"s\",\"verdict\":\"INVALID\",\"lineComments\":[]}}");

            assertThat(WebviewPanel.isValidIncomingMessage(node)).isFalse();
        }

        @Test
        void validatesGeneratedReviewBaseline() throws Exception {
            var valid =
                    MAPPER.readTree(
                            "{\"protocolVersion\":1,\"type\":\"saveDraft\",\"number\":7,\"saveId\":1,"
                                    + "\"owner\":\"acme\",\"repo\":\"platform\",\"generatedResult\":{"
                                    + "\"summary\":\"generated\",\"verdict\":\"COMMENT\",\"lineComments\":[]}}");
            var invalid = (ObjectNode) valid.deepCopy();
            ((ObjectNode) invalid.path("generatedResult")).put("verdict", "INVALID");

            assertThat(WebviewPanel.isValidIncomingMessage(valid)).isTrue();
            assertThat(WebviewPanel.isValidIncomingMessage(invalid)).isFalse();
        }

        @Test
        void rejectsDraftSaveWithoutPositiveCorrelationId() throws Exception {
            var missing =
                    MAPPER.readTree(
                            "{\"protocolVersion\":1,\"type\":\"saveDraft\",\"number\":7,"
                                    + "\"owner\":\"acme\",\"repo\":\"widget\"}");
            var zero = ((ObjectNode) missing.deepCopy()).put("saveId", 0);
            var valid = ((ObjectNode) missing.deepCopy()).put("saveId", 1);

            assertThat(WebviewPanel.isValidIncomingMessage(missing)).isFalse();
            assertThat(WebviewPanel.isValidIncomingMessage(zero)).isFalse();
            assertThat(WebviewPanel.isValidIncomingMessage(valid)).isTrue();
        }

        @Test
        void validatesRefreshCompatibilityBooleans() throws Exception {
            var valid =
                    MAPPER.readTree(
                            "{\"protocolVersion\":1,\"type\":\"refreshPRs\",\"assignedToMe\":true,\"reviewRequested\":false}");
            var invalid =
                    MAPPER.readTree(
                            "{\"protocolVersion\":1,\"type\":\"refreshPRs\",\"assignedToMe\":\"yes\"}");

            assertThat(WebviewPanel.isValidIncomingMessage(valid)).isTrue();
            assertThat(WebviewPanel.isValidIncomingMessage(invalid)).isFalse();
        }

        @Test
        void rejectsOversizedPrIdentity() {
            var node =
                    MAPPER.createObjectNode()
                            .put("protocolVersion", 1)
                            .put("type", "selectPR")
                            .put("number", 7)
                            .put("owner", "x".repeat(257))
                            .put("repo", "platform");

            assertThat(WebviewPanel.isValidIncomingMessage(node)).isFalse();
        }

        @Test
        void rejectsInvalidRichCommentMetadata() throws Exception {
            var node =
                    MAPPER.readTree(
                            "{\"protocolVersion\":1,\"type\":\"saveDraft\",\"number\":7,\"saveId\":1,"
                                    + "\"owner\":\"acme\",\"repo\":\"platform\",\"result\":{"
                                    + "\"summary\":\"s\",\"verdict\":\"COMMENT\",\"lineComments\":[{"
                                    + "\"file\":\"a.java\",\"line\":1,\"type\":\"note\",\"body\":\"b\","
                                    + "\"severity\":\"urgent\"}]}}");

            assertThat(WebviewPanel.isValidIncomingMessage(node)).isFalse();
        }
    }
}
