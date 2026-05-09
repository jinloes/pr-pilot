package com.jinloes.claudereviews.parser

/** Parses unified diff text into structured [DiffFile] / [DiffLine] objects. */
object DiffParser {

    /**
     * Holds the parsed lines for a single file in a unified diff. [name] is mutable because the
     * `+++ b/<path>` header may refine the name after the `diff --git` header is processed.
     */
    class DiffFile(var name: String) {
        val lines: MutableList<DiffLine> = mutableListOf()
    }

    data class DiffLine(val newLineNum: Int, val type: Char, val content: String, val hunkStart: Boolean)

    private val HUNK_NEW_START = Regex("""@@ -\d+(?:,\d+)? \+(\d+)""")

    fun parseDiff(rawDiff: String): List<DiffFile> {
        val files = mutableListOf<DiffFile>()
        var current: DiffFile? = null
        var newLineNum = 0
        var nextIsHunkStart = false

        // limit = 0 means no limit (unlimited splits, trailing empty strings dropped).
        // Java's split(regex, -1) kept trailing empty strings, but they are empty and skipped
        // by the parser anyway, so the behaviour is identical.
        for (line in rawDiff.split(Regex("""\r?\n"""), limit = 0)) {
            when {
                line.startsWith("diff --git ") -> {
                    val bIdx = line.lastIndexOf(" b/")
                    current = DiffFile(if (bIdx >= 0) line.substring(bIdx + 3) else line)
                    files.add(current)
                    newLineNum = 0
                    nextIsHunkStart = false
                }
                line.startsWith("+++ b/") && current != null -> {
                    current.name = line.substring(6)
                }
                line.startsWith("---")
                    || line.startsWith("+++")
                    || line.startsWith("index ")
                    || line.startsWith("new file")
                    || line.startsWith("deleted file")
                    || line.startsWith("old mode")
                    || line.startsWith("new mode")
                    || line.startsWith("Binary files")
                    || line.startsWith("similarity")
                    || line.startsWith("rename") -> {
                    // skip metadata
                }
                line.startsWith("@@ ") -> {
                    val m = HUNK_NEW_START.find(line)
                    if (m != null) newLineNum = m.groupValues[1].toInt() - 1
                    nextIsHunkStart = true
                }
                current != null && line.isNotEmpty() && line[0] != '\\' -> {
                    val first = line[0]
                    val content = if (line.length > 1) line.substring(1) else ""
                    val isHunkStart = nextIsHunkStart
                    nextIsHunkStart = false
                    when (first) {
                        '+' -> current.lines.add(DiffLine(++newLineNum, '+', content, isHunkStart))
                        '-' -> current.lines.add(DiffLine(-1, '-', content, isHunkStart))
                        else -> current.lines.add(DiffLine(++newLineNum, ' ', content, isHunkStart))
                    }
                }
            }
        }
        return files
    }

    /**
     * Returns the column count matching the longest content line across all diff files, clamped to
     * [40, 120].
     */
    fun computeMaxColumns(files: List<DiffFile>): Int {
        var max = 40
        for (f in files) {
            for (l in f.lines) {
                if (l.content.length > max) max = l.content.length
            }
        }
        return minOf(max, 120)
    }
}
