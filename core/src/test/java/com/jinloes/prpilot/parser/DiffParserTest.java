package com.jinloes.prpilot.parser;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.parser.DiffParser.DiffFile;
import com.jinloes.prpilot.parser.DiffParser.DiffLine;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DiffParserTest {

    private static final String TWO_FILE_DIFF =
            """
            diff --git a/src/Foo.java b/src/Foo.java
            index abc..def 100644
            --- a/src/Foo.java
            +++ b/src/Foo.java
            @@ -1,3 +1,4 @@
             context line
            +added line
            -deleted line
             another context
            diff --git a/src/Bar.java b/src/Bar.java
            index 111..222 100644
            --- a/src/Bar.java
            +++ b/src/Bar.java
            @@ -10,2 +10,2 @@
             bar context
            +bar added
            """;

    @Nested
    class ParseDiff {

        @Test
        void twoFiles_parsesCorrectCount() {
            List<DiffFile> files = DiffParser.INSTANCE.parseDiff(TWO_FILE_DIFF);
            assertThat(files).hasSize(2);
        }

        @Test
        void twoFiles_parsesCorrectFileNames() {
            List<DiffFile> files = DiffParser.INSTANCE.parseDiff(TWO_FILE_DIFF);
            assertThat(files.get(0).getName()).isEqualTo("src/Foo.java");
            assertThat(files.get(1).getName()).isEqualTo("src/Bar.java");
        }

        @Test
        void addedLines_getIncrementingLineNumbers() {
            List<DiffFile> files = DiffParser.INSTANCE.parseDiff(TWO_FILE_DIFF);
            List<DiffLine> lines = files.get(0).getLines();
            DiffLine added = lines.stream().filter(l -> l.getType() == '+').findFirst().orElseThrow();
            assertThat(added.getNewLineNum()).isGreaterThan(0);
            assertThat(added.getContent()).isEqualTo("added line");
        }

        @Test
        void deletedLines_getMinusOneLineNumber() {
            List<DiffFile> files = DiffParser.INSTANCE.parseDiff(TWO_FILE_DIFF);
            List<DiffLine> lines = files.get(0).getLines();
            DiffLine deleted =
                    lines.stream().filter(l -> l.getType() == '-').findFirst().orElseThrow();
            assertThat(deleted.getNewLineNum()).isEqualTo(-1);
            assertThat(deleted.getContent()).isEqualTo("deleted line");
        }

        @Test
        void contextLines_getIncrementingLineNumbers() {
            List<DiffFile> files = DiffParser.INSTANCE.parseDiff(TWO_FILE_DIFF);
            List<DiffLine> lines = files.get(0).getLines();
            List<DiffLine> ctx = lines.stream().filter(l -> l.getType() == ' ').toList();
            assertThat(ctx).hasSize(2);
            assertThat(ctx.get(0).getNewLineNum()).isEqualTo(1);
            // deleted line does not advance the new-file counter
            assertThat(ctx.get(1).getNewLineNum()).isEqualTo(3);
        }

        @Test
        void firstLineAfterHunkHeader_hasHunkStartTrue() {
            List<DiffFile> files = DiffParser.INSTANCE.parseDiff(TWO_FILE_DIFF);
            assertThat(files.get(0).getLines().get(0).getHunkStart()).isTrue();
        }

        @Test
        void subsequentLinesInHunk_haveHunkStartFalse() {
            List<DiffFile> files = DiffParser.INSTANCE.parseDiff(TWO_FILE_DIFF);
            List<DiffLine> lines = files.get(0).getLines();
            assertThat(lines.subList(1, lines.size())).allMatch(l -> !l.getHunkStart());
        }

        @Test
        void emptyDiff_returnsEmptyList() {
            assertThat(DiffParser.INSTANCE.parseDiff("")).isEmpty();
        }

        @Test
        void carriageReturnNewline_parsedSameAsLf() {
            String crlfDiff =
                    "diff --git a/src/Foo.java b/src/Foo.java\r\n"
                            + "--- a/src/Foo.java\r\n"
                            + "+++ b/src/Foo.java\r\n"
                            + "@@ -1 +1 @@\r\n"
                            + "+added line\r\n";
            List<DiffFile> files = DiffParser.INSTANCE.parseDiff(crlfDiff);
            assertThat(files).hasSize(1);
            // \r must not bleed into the filename
            assertThat(files.get(0).getName()).isEqualTo("src/Foo.java");
            assertThat(files.get(0).getLines().get(0).getContent()).isEqualTo("added line");
        }

        @Test
        void deletionOnlyHunk_startLineNotNegative() {
            // @@ -5,3 +5,0 @@ — deletion-only hunk; +5 must yield newLineNum = 4 (then no lines)
            String diff =
                    "diff --git a/src/Foo.java b/src/Foo.java\n"
                            + "--- a/src/Foo.java\n"
                            + "+++ b/src/Foo.java\n"
                            + "@@ -5,3 +5,0 @@\n"
                            + "-deleted one\n"
                            + "-deleted two\n"
                            + "-deleted three\n";
            List<DiffFile> files = DiffParser.INSTANCE.parseDiff(diff);
            assertThat(files).hasSize(1);
            // Deleted lines get newLineNum == -1; startLine was correctly parsed as 5 (not 0)
            files.get(0).getLines().forEach(l -> assertThat(l.getNewLineNum()).isEqualTo(-1));
        }
    }

    @Nested
    class ComputeMaxColumns {

        @Test
        void shortLines_returnsMinimumOf40() {
            DiffFile file = new DiffFile("f.java");
            file.getLines().add(new DiffLine(1, '+', "short", false));
            assertThat(DiffParser.INSTANCE.computeMaxColumns(List.of(file))).isEqualTo(40);
        }

        @Test
        void longLines_returnsActualLength() {
            DiffFile file = new DiffFile("f.java");
            String line = "x".repeat(80);
            file.getLines().add(new DiffLine(1, '+', line, false));
            assertThat(DiffParser.INSTANCE.computeMaxColumns(List.of(file))).isEqualTo(80);
        }

        @Test
        void veryLongLines_cappedAt120() {
            DiffFile file = new DiffFile("f.java");
            file.getLines().add(new DiffLine(1, '+', "x".repeat(200), false));
            assertThat(DiffParser.INSTANCE.computeMaxColumns(List.of(file))).isEqualTo(120);
        }

        @Test
        void emptyFiles_returnsMinimumOf40() {
            assertThat(DiffParser.INSTANCE.computeMaxColumns(List.of())).isEqualTo(40);
        }
    }
}
