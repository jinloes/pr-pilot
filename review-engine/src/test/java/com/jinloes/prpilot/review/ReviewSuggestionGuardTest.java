package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.ReviewResult;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReviewSuggestionGuardTest {

    private static final InspectionManifest MANIFEST =
            InspectionManifest.fromDiff(
                    """
                    diff --git a/src/A.java b/src/A.java
                    --- a/src/A.java
                    +++ b/src/A.java
                    @@ -10,3 +10,3 @@
                         int context = 1;
                    -    int removedOnly = 2;
                    +    int added = 2;
                         return context;
                    """);

    @Nested
    class Apply {

        @Test
        void keepsAValidSuggestionOnAnAddedLine() {
            LineComment comment = suggested(11, "    int added = 3;");

            ReviewResult result = apply(comment);

            assertThat(result.getLineComments()).singleElement().isSameAs(comment);
            assertThat(comment.getSuggestedChange()).isEqualTo("    int added = 3;");
        }

        @Test
        void acceptsAContextLine() {
            LineComment comment = suggested(10, "    int context = 2;");

            apply(comment);

            assertThat(comment.getSuggestedChange()).isEqualTo("    int context = 2;");
        }

        @Test
        void keepsUpToSixLines() {
            LineComment comment =
                    suggested(
                            11, "    int a = 1;\n    b();\n    c();\n    d();\n    e();\n    f();");

            apply(comment);

            assertThat(comment.getSuggestedChange()).contains("f();");
        }

        @Test
        void normalizesCrlfAndStripsTrailingNewlines() {
            LineComment comment = suggested(11, "    int added = 3;\r\n    use(added);\r\n\n\n");

            apply(comment);

            assertThat(comment.getSuggestedChange())
                    .isEqualTo("    int added = 3;\n    use(added);");
        }

        @Test
        void leavesCommentsWithoutASuggestionUntouched() {
            LineComment comment = suggested(11, "");
            comment.setSuggestedChange(null);

            apply(comment);

            assertThat(comment.getSuggestedChange()).isEmpty();
        }

        @Test
        void clearsSevenLines() {
            assertCleared(
                    suggested(
                            11,
                            "    a();\n    b();\n    c();\n    d();\n    e();\n    f();\n    g();"));
        }

        @Test
        void clearsMoreThanOneThousandChars() {
            assertCleared(suggested(11, "    " + "x".repeat(997)));
        }

        @Test
        void keepsExactlyOneThousandChars() {
            LineComment comment = suggested(11, "    " + "x".repeat(996));

            apply(comment);

            assertThat(comment.getSuggestedChange()).hasSize(1_000);
        }

        @Test
        void clearsACodeFence() {
            assertCleared(suggested(11, "    int added = 3; // ```"));
        }

        @Test
        void clearsANoteType() {
            LineComment comment = suggested(11, "    int added = 3;");
            comment.setType("note");

            assertCleared(comment);
        }

        @Test
        void clearsMediumConfidence() {
            LineComment comment = suggested(11, "    int added = 3;");
            comment.setConfidence("medium");

            assertCleared(comment);
        }

        @Test
        void clearsALineAbsentFromTheDiff() {
            assertCleared(suggested(40, "    int added = 3;"));
        }

        @Test
        void clearsAnUnknownFile() {
            LineComment comment = suggested(11, "    int added = 3;");
            comment.setFile("src/B.java");

            assertCleared(comment);
        }

        @Test
        void clearsARemovedOnlyLine() {
            InspectionManifest removalOnly =
                    InspectionManifest.fromDiff(
                            """
                            diff --git a/src/A.java b/src/A.java
                            --- a/src/A.java
                            +++ b/src/A.java
                            @@ -10,2 +10,1 @@
                                 return context;
                            -    int removedOnly = 2;
                            """);
            // Old line 11 was removed; the new side has no line 11.
            LineComment comment = suggested(11, "    int removedOnly = 3;");

            ReviewSuggestionGuard.apply(
                    new ReviewResult("s", "COMMENT", List.of(comment)), removalOnly);

            assertThat(comment.getSuggestedChange()).isEmpty();
        }

        @Test
        void clearsTextIdenticalToTheAnchoredLine() {
            assertCleared(suggested(11, "    int added = 2;"));
        }

        @Test
        void clearsABlankValue() {
            assertCleared(suggested(11, "   \n\t"));
        }

        @Test
        void clearsALineNumberPrefix() {
            assertCleared(suggested(11, "    int added = 3;\n42|     use(added);"));
        }

        @Test
        void clearsABareDividerPrefix() {
            assertCleared(suggested(11, "| int added = 3;"));
        }

        @Test
        void clearsChangedLeadingWhitespace() {
            assertCleared(suggested(11, "  int added = 3;"));
        }

        @Test
        void keepsTheCommentWhenClearingItsSuggestion() {
            LineComment comment = suggested(11, "    int added = 2;");

            ReviewResult result = apply(comment);

            assertThat(result.getLineComments()).singleElement().isSameAs(comment);
            assertThat(comment.getBody()).isEqualTo("Use 3.");
            assertThat(comment.getRationale()).isEqualTo("Line 11 sets 2.");
        }
    }

    @Nested
    class HunkBodyDoubleMarkers {

        // "---counter;" removes "--counter;" and "+++counter;" adds "++counter;"; neither is a
        // file header, so they must not shift new-side line numbers.
        private static final InspectionManifest DECREMENT =
                InspectionManifest.fromDiff(
                        """
                        diff --git a/src/A.java b/src/A.java
                        --- a/src/A.java
                        +++ b/src/A.java
                        @@ -1,3 +1,3 @@
                        ---counter;
                         keep();
                        +++counter;
                         last();
                        """);

        @Test
        void removedDecrementLineIsNotNewSideText() {
            assertThat(DECREMENT.newSideLineText("src/A.java", 1)).contains("keep();");
            assertThat(DECREMENT.newSideLineText("src/A.java", 2)).contains("++counter;");
            assertThat(DECREMENT.newSideLineText("src/A.java", 3)).contains("last();");
            assertThat(DECREMENT.newSideLineText("src/A.java", 4)).isEmpty();
        }

        @Test
        void addedIncrementLineIsAChangedLine() {
            assertThat(DECREMENT.files().get(0).hunks().get(0).changedNewLines())
                    .containsExactly(2);
        }

        @Test
        void clearsTextIdenticalToTheLineAfterARemovedDecrement() {
            LineComment comment = suggested(1, "keep();");

            applyTo(comment, DECREMENT);

            assertThat(comment.getSuggestedChange()).isEmpty();
        }

        @Test
        void clearsALinePastTheShortenedNewSide() {
            LineComment comment = suggested(4, "fixed();");

            applyTo(comment, DECREMENT);

            assertThat(comment.getSuggestedChange()).isEmpty();
        }

        @Test
        void keepsAValidSuggestionOnTheAddedIncrementLine() {
            LineComment comment = suggested(2, "counter += 2;");

            applyTo(comment, DECREMENT);

            assertThat(comment.getSuggestedChange()).isEqualTo("counter += 2;");
        }
    }

    @Nested
    class UnicodeSeparatorsInSource {

        // A literal U+2028 inside a string is source text, not a diff record boundary.
        private static final String FIRST = "const s = \"a\u2028b\";";
        private static final InspectionManifest SEPARATOR =
                InspectionManifest.fromDiff(
                        "diff --git a/src/A.java b/src/A.java\n"
                                + "--- a/src/A.java\n"
                                + "+++ b/src/A.java\n"
                                + "@@ -1,2 +1,2 @@\n"
                                + "-const s = \"old\";\n"
                                + "+"
                                + FIRST
                                + "\n"
                                + " const next = 1;\n");

        @Test
        void keepsTheWholeLineAndItsNumbering() {
            assertThat(SEPARATOR.newSideLineText("src/A.java", 1)).contains(FIRST);
            assertThat(SEPARATOR.newSideLineText("src/A.java", 2)).contains("const next = 1;");
            assertThat(SEPARATOR.newSideLineText("src/A.java", 3)).isEmpty();
        }

        @Test
        void clearsTextIdenticalToTheLineAfterTheSeparator() {
            LineComment comment = suggested(2, "const next = 1;");

            applyTo(comment, SEPARATOR);

            assertThat(comment.getSuggestedChange()).isEmpty();
        }

        @Test
        void clearsALinePastTheRealNewSide() {
            LineComment comment = suggested(3, "const next = 2;");

            applyTo(comment, SEPARATOR);

            assertThat(comment.getSuggestedChange()).isEmpty();
        }

        @Test
        void doesNotStartAFileAtASeparatorFollowedByADiffHeader() {
            InspectionManifest manifest =
                    InspectionManifest.fromDiff(
                            "diff --git a/src/A.java b/src/A.java\n"
                                    + "--- a/src/A.java\n"
                                    + "+++ b/src/A.java\n"
                                    + "@@ -0,0 +1,2 @@\n"
                                    + "+String s = \"\u2028diff --git a/x b/x\";\n"
                                    + "+int next = 1;\n");

            assertThat(manifest.files()).hasSize(1);
            assertThat(manifest.newSideLineText("src/A.java", 2)).contains("int next = 1;");
        }

        @Test
        void splitsCrlfRecordsWithoutKeepingTheCarriageReturn() {
            InspectionManifest manifest =
                    InspectionManifest.fromDiff(
                            "diff --git a/src/A.java b/src/A.java\r\n"
                                    + "--- a/src/A.java\r\n"
                                    + "+++ b/src/A.java\r\n"
                                    + "@@ -0,0 +1,2 @@\r\n"
                                    + "+int a = 1;\r\n"
                                    + "+int b = 2;\r\n");

            assertThat(manifest.newSideLineText("src/A.java", 1)).contains("int a = 1;");
            assertThat(manifest.newSideLineText("src/A.java", 2)).contains("int b = 2;");
        }
    }

    private static void applyTo(LineComment comment, InspectionManifest manifest) {
        ReviewSuggestionGuard.apply(new ReviewResult("s", "COMMENT", List.of(comment)), manifest);
    }

    private static ReviewResult apply(LineComment comment) {
        return ReviewSuggestionGuard.apply(
                new ReviewResult("summary", "COMMENT", List.of(comment)), MANIFEST);
    }

    private static void assertCleared(LineComment comment) {
        ReviewResult result = apply(comment);

        assertThat(result.getLineComments()).singleElement().isSameAs(comment);
        assertThat(comment.getSuggestedChange()).isEmpty();
    }

    private static LineComment suggested(int line, String suggestion) {
        LineComment comment = new LineComment("src/A.java", line, "issue", "Use 3.");
        comment.setSeverity("major");
        comment.setCategory("correctness");
        comment.setConfidence("high");
        comment.setRationale("Line 11 sets 2.");
        comment.setSuggestedChange(suggestion);
        return comment;
    }
}
