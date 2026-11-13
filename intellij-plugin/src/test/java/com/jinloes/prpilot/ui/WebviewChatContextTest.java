package com.jinloes.prpilot.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinloes.prpilot.model.ChatContext;
import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.model.ReviewResult;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Chat context the IntelliJ host builds from webview messages and panel state. */
class WebviewChatContextTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode askClaude() {
        return MAPPER.createObjectNode()
                .put("protocolVersion", 1)
                .put("type", "askClaude")
                .put("operationId", "chat-1")
                .put("question", "Why?")
                .put("context", "int x = 1;");
    }

    @Nested
    class ValidatorContextLocation {

        @Test
        void acceptsAMessageWithoutALocation() {
            assertThat(BridgeMessageValidator.isValid(askClaude())).isTrue();
        }

        @Test
        void acceptsAFileWithALineRange() {
            ObjectNode message = askClaude();
            message.putObject("contextLocation")
                    .put("file", "src/A.java")
                    .put("startLine", 3)
                    .put("endLine", 5)
                    .put("oldSide", true);

            assertThat(BridgeMessageValidator.isValid(message)).isTrue();
        }

        @Test
        void rejectsALocationWithoutAFile() {
            ObjectNode message = askClaude();
            message.putObject("contextLocation").put("startLine", 3);

            assertThat(BridgeMessageValidator.isValid(message)).isFalse();
        }

        @Test
        void rejectsANegativeOrNonIntegerLine() {
            ObjectNode negative = askClaude();
            negative.putObject("contextLocation").put("file", "A").put("startLine", -1);
            ObjectNode fractional = askClaude();
            fractional.putObject("contextLocation").put("file", "A").put("endLine", 1.5);

            assertThat(BridgeMessageValidator.isValid(negative)).isFalse();
            assertThat(BridgeMessageValidator.isValid(fractional)).isFalse();
        }

        @Test
        void rejectsANonObjectLocationOrNonBooleanSide() {
            ObjectNode text = askClaude().put("contextLocation", "src/A.java");
            ObjectNode side = askClaude();
            side.putObject("contextLocation").put("file", "A").put("oldSide", "yes");

            assertThat(BridgeMessageValidator.isValid(text)).isFalse();
            assertThat(BridgeMessageValidator.isValid(side)).isFalse();
        }
    }

    @Nested
    class ChatAttachment {

        @Test
        void isNullWithoutAttachedText() {
            assertThat(WebviewBridgeHandler.chatAttachment(askClaude().put("context", "  ")))
                    .isNull();
        }

        @Test
        void keepsTextWithoutALocation() {
            assertThat(WebviewBridgeHandler.chatAttachment(askClaude()))
                    .isEqualTo(new ChatContext.Attachment("int x = 1;", null, 0, 0, false));
        }

        @Test
        void readsTheLocation() {
            ObjectNode message = askClaude();
            message.putObject("contextLocation")
                    .put("file", "src/A.java")
                    .put("startLine", 3)
                    .put("endLine", 5)
                    .put("oldSide", true);

            assertThat(WebviewBridgeHandler.chatAttachment(message))
                    .isEqualTo(new ChatContext.Attachment("int x = 1;", "src/A.java", 3, 5, true));
        }

        @Test
        void dropsABlankFile() {
            ObjectNode message = askClaude();
            message.putObject("contextLocation").put("file", " ").put("startLine", 3);

            assertThat(WebviewBridgeHandler.chatAttachment(message).file()).isNull();
        }
    }

    @Nested
    class BuildChatContext {
        private final PullRequest pr =
                new PullRequest("Add cache", "", "acme", "widget", 42, "Body", "octo", "");

        @Test
        void carriesPrMetadataAndDiffWithoutAReview() throws Exception {
            WebviewPanelFixture f = new WebviewPanelFixture();
            f.field("prefetchedDiff", "diff --git a/A b/A\n");

            ChatContext context = f.panel.buildChatContext(pr);

            assertThat(context)
                    .isEqualTo(
                            new ChatContext(
                                    "acme",
                                    "widget",
                                    42,
                                    "Add cache",
                                    "octo",
                                    "Body",
                                    "diff --git a/A b/A\n",
                                    null,
                                    null,
                                    List.of(),
                                    null));
        }

        @Test
        void carriesTheGeneratedReviewAndItsComments() throws Exception {
            WebviewPanelFixture f = new WebviewPanelFixture();
            LineComment comment = new LineComment("src/A.java", 7, "issue", "Null deref.");
            comment.setSeverity("major");
            f.field(
                    "lastResult",
                    new ReviewResult("One race.", "REQUEST_CHANGES", List.of(comment)));

            ChatContext context = f.panel.buildChatContext(pr);

            assertThat(context.reviewVerdict()).isEqualTo("REQUEST_CHANGES");
            assertThat(context.reviewSummary()).isEqualTo("One race.");
            assertThat(context.reviewComments())
                    .containsExactly(
                            new ChatContext.ReviewComment(
                                    "src/A.java", 7, "issue", "major", "Null deref."));
        }
    }
}
