package com.jinloes.claudereviews.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DiffTypeContextExtractorTest {

    @Nested
    class ParseDiffFilePaths {

        @Test
        void extractsNewFilePath() {
            String diff =
                    "diff --git a/src/Foo.java b/src/Foo.java\n"
                            + "+++ b/src/Foo.java\n"
                            + "@@ -1,3 +1,4 @@\n";
            assertThat(DiffTypeContextExtractor.parseDiffFilePaths(diff))
                    .containsExactly("src/Foo.java");
        }

        @Test
        void skipsDevNull() {
            String diff = "+++ /dev/null\n";
            assertThat(DiffTypeContextExtractor.parseDiffFilePaths(diff)).isEmpty();
        }

        @Test
        void deduplicatesMultipleHunksInSameFile() {
            String diff = "+++ b/src/Foo.java\n+++ b/src/Foo.java\n";
            assertThat(DiffTypeContextExtractor.parseDiffFilePaths(diff)).hasSize(1);
        }

        @Test
        void extractsMultipleFiles() {
            String diff = "+++ b/src/Foo.java\n" + "@@ -1,1 +1,2 @@\n" + "+++ b/src/Bar.java\n";
            Set<String> paths = DiffTypeContextExtractor.parseDiffFilePaths(diff);
            assertThat(paths).containsExactly("src/Foo.java", "src/Bar.java");
        }

        @Test
        void emptyDiff_returnsEmpty() {
            assertThat(DiffTypeContextExtractor.parseDiffFilePaths("")).isEmpty();
        }

        @Test
        void preservesInsertionOrder() {
            String diff = "+++ b/z/Z.java\n+++ b/a/A.java\n";
            assertThat(DiffTypeContextExtractor.parseDiffFilePaths(diff))
                    .containsExactly("z/Z.java", "a/A.java");
        }
    }
}
