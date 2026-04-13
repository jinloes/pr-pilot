package com.jinloes.claudereviews.highlighting;

import java.awt.Color;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;

/**
 * Delegates syntax highlighting to {@link TreeSitterHighlighter}, so every language with a
 * tree-sitter grammar is highlighted structurally (method names, type names, annotations, etc.).
 * Languages without a grammar receive plain {@link #FG_COLOR} spans.
 *
 * <p>Token colours use GitHub's dark mode palette so the diff panel matches how PRs look on
 * github.com.
 */
public class DiffHighlighter {

    /** Fallback foreground colour when no specific highlight applies. */
    public static final Color FG_COLOR = new Color(0xe6edf3);

    /** A contiguous run of characters with a single foreground colour. */
    public record Span(int start, int end, Color color) {}

    /**
     * Highlights all lines in a hunk using tree-sitter. Always returns a non-null result: languages
     * without a grammar or parse failures produce a list of single {@link #FG_COLOR} spans.
     *
     * @param lines code content lines (diff prefix stripped)
     * @param extension file extension
     * @return one {@link Span} list per input line
     */
    public static List<List<Span>> colorHunk(List<String> lines, String extension) {
        return TreeSitterHighlighter.colorHunk(lines, extension);
    }

    /**
     * Escapes HTML special characters via Apache Commons Text and converts spaces to {@code
     * &nbsp;}. Intended for rendering individual tokens inside an HTML label or pane.
     */
    public static String htmlEscape(String s) {
        return StringUtils.isEmpty(s)
                ? ""
                : StringEscapeUtils.escapeHtml4(s).replace(" ", "&nbsp;");
    }
}
