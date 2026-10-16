package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for {@link DiffLineAnnotator}. */
class DiffLineAnnotatorTest {
    @Nested
    class AnnotateDiffWithLineNumbers {

        @Test
        void numbersAddedAndContextLinesFromHunkHeader() {
            String diff =
                    "diff --git a/f.txt b/f.txt\n"
                            + "@@ -10,3 +20,4 @@ void f()\n"
                            + " ctx\n"
                            + "+added1\n"
                            + "+added2\n"
                            + " ctx2\n";
            String annotated = DiffLineAnnotator.annotateDiffWithLineNumbers(diff);
            assertThat(annotated)
                    .contains("diff --git a/f.txt b/f.txt")
                    .contains("@@ -10,3 +20,4 @@ void f()")
                    .contains("20|  ctx")
                    .contains("21| +added1")
                    .contains("22| +added2")
                    .contains("23|  ctx2");
        }

        @Test
        void deletedLinesGetNoNumberAndDoNotAdvanceCounter() {
            String diff = "@@ -1,2 +1,1 @@\n" + "-removed\n" + "+kept\n";
            String annotated = DiffLineAnnotator.annotateDiffWithLineNumbers(diff);
            assertThat(annotated).contains("| -removed").contains("1| +kept");
            assertThat(annotated).doesNotContain("1| -removed");
        }

        @Test
        void resetsNumberingAtEachNewHunk() {
            String diff = "@@ -1,1 +1,1 @@\n" + "+first\n" + "@@ -50,1 +80,1 @@\n" + "+second\n";
            String annotated = DiffLineAnnotator.annotateDiffWithLineNumbers(diff);
            assertThat(annotated).contains("1| +first").contains("80| +second");
        }

        @Test
        void treatsTriplePlusAsSourceAfterAHunkButAsAHeaderBeforeOne() {
            String diff =
                    "diff --git a/f.txt b/f.txt\n"
                            + "--- a/f.txt\n"
                            + "+++ b/f.txt\n"
                            + "@@ -1,1 +1,3 @@\n"
                            + " context\n"
                            + "+++operator\n"
                            + "+after\n";

            String annotated = DiffLineAnnotator.annotateDiffWithLineNumbers(diff);

            assertThat(annotated)
                    .contains("+++ b/f.txt")
                    .contains("2| +++operator")
                    .contains("3| +after");
        }

        @Test
        void resetsToHeaderModeAtTheNextFile() {
            String diff =
                    "diff --git a/a.txt b/a.txt\n"
                            + "--- a/a.txt\n"
                            + "+++ b/a.txt\n"
                            + "@@ -1 +1 @@\n"
                            + "+first\n"
                            + "diff --git a/b.txt b/b.txt\n"
                            + "--- a/b.txt\n"
                            + "+++ b/b.txt\n"
                            + "@@ -9 +10 @@\n"
                            + "+second\n";

            String annotated = DiffLineAnnotator.annotateDiffWithLineNumbers(diff);

            assertThat(annotated).contains("+++ b/b.txt").contains("10| +second");
            assertThat(annotated).doesNotContain("| +++ b/b.txt");
        }

        @Test
        void preHunkAndBlankInputPassThroughUnchanged() {
            assertThat(DiffLineAnnotator.annotateDiffWithLineNumbers("")).isEmpty();
            assertThat(DiffLineAnnotator.annotateDiffWithLineNumbers("diff --git a/a b/a"))
                    .isEqualTo("diff --git a/a b/a");
        }
    }
}
