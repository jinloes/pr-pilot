package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class UnifiedPatchTest {
    @Nested
    class ParseHunks {
        @Test
        void parsesHeadersWithOmittedCountsAndSkipsNoNewlineMarkers() {
            String diff =
                    """
                    diff --git a/a.proto b/a.proto
                    index 1..2 100644
                    --- a/a.proto
                    +++ b/a.proto
                    @@ -2 +2,2 @@ message User {
                    - string name = 1;
                    \\ No newline at end of file
                    + string full_name = 1;
                    + int32 age = 2;
                    """;

            assertThat(UnifiedPatch.parseHunks(diff))
                    .hasValueSatisfying(
                            hunks ->
                                    assertThat(hunks)
                                            .containsExactly(
                                                    new UnifiedPatch.Hunk(
                                                            2,
                                                            1,
                                                            2,
                                                            2,
                                                            List.of(
                                                                    "- string name = 1;",
                                                                    "+ string full_name = 1;",
                                                                    "+ int32 age = 2;"))));
        }

        @Test
        void returnsEmptyOptionalForMalformedCountsOrBinaryDiffs() {
            assertThat(
                            UnifiedPatch.parseHunks(
                                    """
                                    @@ -1,2 +1,2 @@
                                    -a
                                    +b
                                    """))
                    .isEmpty();
            assertThat(UnifiedPatch.parseHunks("GIT binary patch\nliteral 1\n")).isEmpty();
        }

        @Test
        void returnsPresentEmptyListWhenNoHunksExist() {
            assertThat(
                            UnifiedPatch.parseHunks(
                                    "diff --git a/a.proto b/b.proto\nsimilarity index 100%\n"))
                    .hasValue(List.of());
        }
    }

    @Nested
    class Apply {
        @Test
        void readsAnEmptyLineInsideAHunkAsABlankContextLine() {
            String diff =
                    "--- a/a.proto\n+++ b/a.proto\n@@ -1,3 +1,3 @@\n message A {}\n\n-message B {}\n"
                            + "+message C {}\n";

            assertThat(UnifiedPatch.apply("message A {}\n\nmessage B {}\n", diff))
                    .contains("message A {}\n\nmessage C {}\n");
            assertThat(UnifiedPatch.apply("message A {}\nnot blank\nmessage B {}\n", diff))
                    .isEmpty();
        }

        @Test
        void detectsBinaryDiffsFromTheHeaderOnly() {
            String diff =
                    "--- a/a.proto\n+++ b/a.proto\n@@ -1 +1,2 @@\n message A {}\n"
                            + "+// Binary files differ\n";

            assertThat(UnifiedPatch.apply("message A {}\n", diff))
                    .contains("message A {}\n// Binary files differ\n");
            assertThat(
                            UnifiedPatch.apply(
                                    "message A {}\n",
                                    "diff --git a/a.proto b/a.proto\n"
                                            + "Binary files a/a.proto and b/a.proto differ\n"))
                    .isEmpty();
        }

        @Test
        void appliesStrictlyAndNormalizesOutput() {
            String base = "syntax = \"proto3\";\r\nmessage User {\r\n string name = 1;\r\n}\r\n";
            String diff =
                    """
                    diff --git a/a.proto b/a.proto
                    --- a/a.proto
                    +++ b/a.proto
                    @@ -2,3 +2,4 @@
                     message User {
                    - string name = 1;
                    + string full_name = 1;
                    + int32 age = 2;
                     }
                    """;

            assertThat(UnifiedPatch.apply(base, diff))
                    .contains(
                            """
                            syntax = "proto3";
                            message User {
                             string full_name = 1;
                             int32 age = 2;
                            }
                            """);
        }

        @Test
        void supportsAddedFilesAndPureMetadataDiffs() {
            assertThat(
                            UnifiedPatch.apply(
                                    "",
                                    """
                                    diff --git a/new.proto b/new.proto
                                    new file mode 100644
                                    --- /dev/null
                                    +++ b/new.proto
                                    @@ -0,0 +1,2 @@
                                    +syntax = "proto3";
                                    +message User {}
                                    """))
                    .contains("syntax = \"proto3\";\nmessage User {}\n");

            assertThat(UnifiedPatch.apply("message User {}\n", "diff --git a/a.proto b/b.proto\n"))
                    .contains("message User {}\n");
        }

        @Test
        void rejectsMismatchesOverlapOutOfRangeAndBinaryDiffs() {
            assertThat(
                            UnifiedPatch.apply(
                                    "a\n",
                                    """
                                    @@ -1 +1 @@
                                    -b
                                    +c
                                    """))
                    .isEmpty();
            assertThat(
                            UnifiedPatch.apply(
                                    "a\n",
                                    """
                                    @@ -3 +3 @@
                                    -x
                                    +y
                                    """))
                    .isEmpty();
            assertThat(UnifiedPatch.apply("a\n", "Binary files a and b differ\n")).isEmpty();
        }
    }
}
