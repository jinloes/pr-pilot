package com.jinloes.prpilot.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ChatContextTest {

    private static ChatContext context(int number, List<ChatContext.ReviewComment> comments) {
        return new ChatContext(
                "acme", "widgets", number, "t", "a", "b", "d", null, null, comments, null);
    }

    @Nested
    class Construction {

        @Test
        void treatsNullReviewCommentsAsEmpty() {
            assertThat(context(1, null).reviewComments()).isEmpty();
        }

        @Test
        void copiesReviewCommentsDefensively() {
            List<ChatContext.ReviewComment> comments = new ArrayList<>();
            comments.add(new ChatContext.ReviewComment("A", 1, "note", null, "x"));
            ChatContext context = context(1, comments);

            comments.clear();

            assertThat(context.reviewComments()).hasSize(1);
        }
    }

    @Nested
    class HasPullRequest {

        @Test
        void isTrueForAPositiveNumber() {
            assertThat(context(5, List.of()).hasPullRequest()).isTrue();
        }

        @Test
        void isFalseWithoutANumber() {
            assertThat(context(0, List.of()).hasPullRequest()).isFalse();
        }
    }

    @Nested
    class WithAttachment {

        @Test
        void replacesOnlyTheAttachment() {
            ChatContext.Attachment attachment = new ChatContext.Attachment("x", "A", 1, 2, false);
            ChatContext original = context(3, List.of());

            ChatContext updated = original.withAttachment(attachment);

            assertThat(updated.attachment()).isEqualTo(attachment);
            assertThat(updated.withAttachment(null)).isEqualTo(original);
        }
    }

    @Nested
    class ReviewCommentOf {

        @Test
        void copiesTheFieldsChatUses() {
            LineComment comment = new LineComment();
            comment.setFile("src/A.java");
            comment.setLine(9);
            comment.setType("issue");
            comment.setSeverity("major");
            comment.setBody("Null deref.");

            assertThat(ChatContext.ReviewComment.of(comment))
                    .isEqualTo(
                            new ChatContext.ReviewComment(
                                    "src/A.java", 9, "issue", "major", "Null deref."));
        }
    }

    @Nested
    class AttachmentLocation {

        @Test
        void isEmptyWithoutAFile() {
            assertThat(new ChatContext.Attachment("x", null, 3, 4, false).location()).isEmpty();
            assertThat(new ChatContext.Attachment("x", " ", 3, 4, false).location()).isEmpty();
        }

        @Test
        void isTheFileWithoutLines() {
            assertThat(new ChatContext.Attachment("x", "A", 0, 0, false).location()).isEqualTo("A");
        }

        @Test
        void isASingleLineWhenTheRangeHasOneLine() {
            assertThat(new ChatContext.Attachment("x", "A", 7, 7, false).location())
                    .isEqualTo("A:7");
            assertThat(new ChatContext.Attachment("x", "A", 7, 0, false).location())
                    .isEqualTo("A:7");
        }

        @Test
        void isARangeAndMarksPreChangeLines() {
            assertThat(new ChatContext.Attachment("x", "A", 7, 9, false).location())
                    .isEqualTo("A:7-9");
            assertThat(new ChatContext.Attachment("x", "A", 7, 9, true).location())
                    .isEqualTo("A:7-9 (pre-change lines)");
        }
    }
}
