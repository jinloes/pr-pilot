package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.review.DiffFiles.DiffFile;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DiffFilesTest {

    @Nested
    class Parse {

        @Test
        void returnsNothingForNullOrEmptyInput() {
            assertThat(DiffFiles.parse(null)).isEmpty();
            assertThat(DiffFiles.parse("")).isEmpty();
            assertThat(DiffFiles.parse("not a diff\n")).isEmpty();
        }

        @Test
        void splitsSectionsAndCountsHunkLines() {
            String first =
                    "diff --git a/A.java b/A.java\n--- a/A.java\n+++ b/A.java\n"
                            + "@@ -1,2 +1,3 @@\n context\n-old\n+new\n+more\n";
            String second =
                    "diff --git a/B.java b/B.java\n--- a/B.java\n+++ b/B.java\n"
                            + "@@ -1 +1 @@\n-x\n+y\n";

            List<DiffFile> files = DiffFiles.parse(first + second);

            assertThat(files)
                    .containsExactly(
                            new DiffFile("A.java", 2, 1, first),
                            new DiffFile("B.java", 1, 1, second));
            assertThat(files.get(0).changedLines()).isEqualTo(3);
        }

        @Test
        void usesTheHeaderPathForADeletedFile() {
            String deleted =
                    "diff --git a/Gone.java b/Gone.java\n--- a/Gone.java\n+++ /dev/null\n"
                            + "@@ -1 +0,0 @@\n-x\n";

            assertThat(DiffFiles.parse(deleted))
                    .containsExactly(new DiffFile("Gone.java", 0, 1, deleted));
        }

        @Test
        void doesNotCountFileHeadersAsChanges() {
            String diff = "diff --git a/A b/A\n--- a/A\n+++ b/A\n@@ -1 +1 @@\n-a\n+b\n";

            assertThat(DiffFiles.parse(diff).get(0))
                    .extracting(DiffFile::added, DiffFile::removed)
                    .containsExactly(1, 1);
        }

        @Test
        void returnsAMutableList() {
            List<DiffFile> files =
                    DiffFiles.parse("diff --git a/A b/A\n+++ b/A\n@@ -1 +1 @@\n+b\n");

            files.add(new DiffFile("B", 0, 0, ""));

            assertThat(files).hasSize(2);
        }
    }
}
