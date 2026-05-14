package com.jinloes.prpilot.parser

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotStartWith

private val TWO_FILE_DIFF = """
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
""".trimIndent()

class DiffParserTest : FunSpec({

    context("parseDiff") {

        test("two files — correct file count") {
            DiffParser.parseDiff(TWO_FILE_DIFF) shouldHaveSize 2
        }

        test("two files — correct file names") {
            val files = DiffParser.parseDiff(TWO_FILE_DIFF)
            files[0].name shouldBe "src/Foo.java"
            files[1].name shouldBe "src/Bar.java"
        }

        test("added lines get incrementing line numbers") {
            val files = DiffParser.parseDiff(TWO_FILE_DIFF)
            val added = files[0].lines.first { it.type == '+' }
            added.newLineNum shouldBe 2
            added.content shouldBe "added line"
        }

        test("deleted lines get -1 line number") {
            val files = DiffParser.parseDiff(TWO_FILE_DIFF)
            val deleted = files[0].lines.first { it.type == '-' }
            deleted.newLineNum shouldBe -1
            deleted.content shouldBe "deleted line"
        }

        test("context lines get incrementing line numbers and deleted lines do not advance counter") {
            val files = DiffParser.parseDiff(TWO_FILE_DIFF)
            val ctx = files[0].lines.filter { it.type == ' ' }
            ctx shouldHaveSize 2
            ctx[0].newLineNum shouldBe 1
            ctx[1].newLineNum shouldBe 3
        }

        test("first line after hunk header has hunkStart = true") {
            val files = DiffParser.parseDiff(TWO_FILE_DIFF)
            files[0].lines[0].hunkStart.shouldBeTrue()
        }

        test("subsequent lines in hunk have hunkStart = false") {
            val files = DiffParser.parseDiff(TWO_FILE_DIFF)
            val lines = files[0].lines
            lines.drop(1).forEach { it.hunkStart.shouldBeFalse() }
        }

        test("empty diff returns empty list") {
            DiffParser.parseDiff("").shouldBeEmpty()
        }

        test("CRLF line endings parsed same as LF") {
            val crlfDiff =
                "diff --git a/src/Foo.java b/src/Foo.java\r\n" +
                "--- a/src/Foo.java\r\n" +
                "+++ b/src/Foo.java\r\n" +
                "@@ -1 +1 @@\r\n" +
                "+added line\r\n"
            val files = DiffParser.parseDiff(crlfDiff)
            files shouldHaveSize 1
            files[0].name shouldBe "src/Foo.java"
            files[0].lines[0].content shouldBe "added line"
        }

        test("deletion-only hunk — all deleted lines have newLineNum = -1") {
            val diff =
                "diff --git a/src/Foo.java b/src/Foo.java\n" +
                "--- a/src/Foo.java\n" +
                "+++ b/src/Foo.java\n" +
                "@@ -5,3 +5,0 @@\n" +
                "-deleted one\n" +
                "-deleted two\n" +
                "-deleted three\n"
            val files = DiffParser.parseDiff(diff)
            files shouldHaveSize 1
            files[0].lines.forEach { it.newLineNum shouldBe -1 }
        }

        test("diff --git header with no b/ suffix uses the whole line as name") {
            val diff = "diff --git a/src/Foo.java src/Foo.java\n"
            val files = DiffParser.parseDiff(diff)
            files shouldHaveSize 1
            // name comes from the fallback branch (no " b/" found)
            files[0].name shouldNotStartWith " "
        }

        test("+++ b/ header refines the file name") {
            val diff =
                "diff --git a/orig.java b/renamed.java\n" +
                "+++ b/renamed.java\n" +
                "@@ -1 +1 @@\n" +
                "+line\n"
            val files = DiffParser.parseDiff(diff)
            files[0].name shouldBe "renamed.java"
        }

        test("+++ without b/ prefix is ignored for name") {
            val diff =
                "diff --git a/src/Foo.java b/src/Foo.java\n" +
                "+++ a/src/Foo.java\n" +
                "@@ -1 +1 @@\n" +
                "+line\n"
            val files = DiffParser.parseDiff(diff)
            // +++ a/... does not start with "+++ b/" so name keeps the diff --git value
            files[0].name shouldBe "src/Foo.java"
        }

        test("line starting with backslash (no newline at end) is skipped") {
            val diff =
                "diff --git a/f.txt b/f.txt\n" +
                "@@ -1 +1 @@\n" +
                "+content\n" +
                "\\ No newline at end of file\n"
            val files = DiffParser.parseDiff(diff)
            // the backslash line should be skipped, not added as a DiffLine
            files[0].lines shouldHaveSize 1
            files[0].lines[0].content shouldBe "content"
        }

        test("metadata header lines are skipped") {
            val diff =
                "diff --git a/f.txt b/f.txt\n" +
                "index abc..def 100644\n" +
                "new file mode 100644\n" +
                "deleted file mode 100644\n" +
                "old mode 100644\n" +
                "new mode 100755\n" +
                "Binary files a/img.png and b/img.png differ\n" +
                "similarity index 90%\n" +
                "rename from foo.txt\n" +
                "rename to bar.txt\n" +
                "--- a/f.txt\n" +
                "+++ b/f.txt\n" +
                "@@ -1 +1 @@\n" +
                "+line\n"
            val files = DiffParser.parseDiff(diff)
            files shouldHaveSize 1
            files[0].lines shouldHaveSize 1
        }
    }

    context("computeMaxColumns") {

        test("short lines return minimum of 40") {
            val file = DiffParser.DiffFile("f.java")
            file.lines.add(DiffParser.DiffLine(1, '+', "short", false))
            DiffParser.computeMaxColumns(listOf(file)) shouldBe 40
        }

        test("lines of length 80 return 80") {
            val file = DiffParser.DiffFile("f.java")
            file.lines.add(DiffParser.DiffLine(1, '+', "x".repeat(80), false))
            DiffParser.computeMaxColumns(listOf(file)) shouldBe 80
        }

        test("lines longer than 120 are capped at 120") {
            val file = DiffParser.DiffFile("f.java")
            file.lines.add(DiffParser.DiffLine(1, '+', "x".repeat(200), false))
            DiffParser.computeMaxColumns(listOf(file)) shouldBe 120
        }

        test("empty file list returns minimum of 40") {
            DiffParser.computeMaxColumns(emptyList()) shouldBe 40
        }

        test("multiple files — uses maximum across all") {
            val f1 = DiffParser.DiffFile("a.java")
            f1.lines.add(DiffParser.DiffLine(1, '+', "x".repeat(60), false))
            val f2 = DiffParser.DiffFile("b.java")
            f2.lines.add(DiffParser.DiffLine(1, '+', "x".repeat(90), false))
            DiffParser.computeMaxColumns(listOf(f1, f2)) shouldBe 90
        }
    }
})
