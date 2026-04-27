package com.jinloes.claudereviews.ui;

import com.jinloes.claudereviews.highlighting.DiffHighlighter;
import com.jinloes.claudereviews.model.LineComment;
import com.jinloes.claudereviews.model.ReviewResult;
import com.jinloes.claudereviews.ui.DiffParser.DiffFile;
import com.jinloes.claudereviews.ui.DiffParser.DiffLine;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.border.*;
import javax.swing.text.*;

/**
 * Scrollable panel that renders a {@link ReviewResult} in GitHub dark-mode style.
 *
 * <p>The diff is broken into per-file sections. Each section shows colour-coded and
 * syntax-highlighted lines with {@link CommentCard}s injected inline after the lines they
 * reference. Comments are editable in-place and can be dismissed individually. Call {@link
 * #nextComment()} / {@link #prevComment()} to scroll through all inline comments sequentially.
 */
public class ReviewPanel extends JPanel implements Scrollable {

    static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 12);
    static final Font UI = new Font(Font.SANS_SERIF, Font.PLAIN, 12);

    // ---------------------------------------------------------------------

    /** All CommentCards rendered in the current review, in document order. */
    private final List<CommentCard> commentCards = new ArrayList<>();

    private int currentCommentIndex = -1;

    /** Called whenever a comment card is dismissed. Used to update the nav count. */
    private Runnable onCommentRemoved;

    /** Called when the user triggers "Ask Claude" from the right-click menu. */
    private java.util.function.BiConsumer<String, String> onAskClaude;

    /** Called when the user triggers "Verify pattern in repo" on a CommentCard. */
    private java.util.function.Consumer<CommentCard> onVerifyComment;

    /** Last non-empty selection made inside this panel; survives focus loss. */
    private String lastKnownSelection = "";

    /** Columns matching the diff's max line length; used to size inline comment text areas. */
    private int diffMaxCols = 80;

    /** Pixel width of the 5-char gutter ("%4d ") in MONO font. */
    private int diffGutterPx = 36;

    /** Indent for the card wrapper: gutter (5 chars) + prefix char (1 char) = content column. */
    private int diffIndentPx = 43;

    /** Max pixel width for a CommentCard — content chars in MONO font + right slack. */
    private int diffCardMaxPx = 600;

    /** Non-null while the panel is showing a placeholder (set by {@link #showPlaceholder}). */
    private JLabel placeholderLabel;

    /** Non-null while the panel is in terminal log mode (set by {@link #showStatusLog}). */
    private JTextArea statusLog;

    /** Last message appended to {@link #statusLog}; used to suppress consecutive duplicates. */
    private String lastStatusMessage;

    /** Animated "thinking" spinner shown below the log while Claude is running. */
    private JLabel thinkingLabel;

    private Timer thinkingTimer;
    private int thinkingFrame;
    private long thinkingStartMs;
    private static final String[] SPINNER = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    public ReviewPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(ThemeColors.BG);
        showPlaceholder("Select a PR, then click \u201cGenerate Review\u201d.");
    }

    // ---------------------------------------------------------------
    // State transitions
    // ---------------------------------------------------------------

    public void showPlaceholder(String message) {
        rebuild(
                () -> {
                    placeholderLabel = muted(message);
                    add(placeholderLabel);
                    add(vgap(8));
                });
    }

    /**
     * Switches the panel to a terminal-style scrollable log. Each subsequent {@link
     * #updateStatus(String)} call appends a new line. The outer {@link JScrollPane} provided by the
     * tool window supplies the scrollbar — no nested scroll pane is used.
     */
    public void showStatusLog(String initialMessage) {
        rebuild(
                () -> {
                    statusLog = new JTextArea("> " + initialMessage + "\n");
                    statusLog.setFont(MONO);
                    statusLog.setForeground(new Color(0x3fb950));
                    statusLog.setBackground(ThemeColors.BG);
                    statusLog.setEditable(false);
                    statusLog.setLineWrap(true);
                    statusLog.setWrapStyleWord(true);
                    statusLog.setBorder(new EmptyBorder(12, 16, 12, 16));
                    statusLog.setAlignmentX(LEFT_ALIGNMENT);
                    statusLog.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
                    add(statusLog);

                    thinkingFrame = 0;
                    thinkingStartMs = System.currentTimeMillis();
                    thinkingLabel = new JLabel(SPINNER[0] + " Claude is thinking… (0s)");
                    thinkingLabel.setFont(MONO);
                    thinkingLabel.setForeground(ThemeColors.FG_MUTED);
                    thinkingLabel.setBorder(new EmptyBorder(0, 16, 10, 16));
                    thinkingLabel.setAlignmentX(LEFT_ALIGNMENT);
                    add(thinkingLabel);

                    thinkingTimer =
                            new Timer(
                                    100,
                                    e -> {
                                        thinkingFrame = (thinkingFrame + 1) % SPINNER.length;
                                        long elapsed =
                                                (System.currentTimeMillis() - thinkingStartMs)
                                                        / 1000;
                                        thinkingLabel.setText(
                                                SPINNER[thinkingFrame]
                                                        + " Claude is thinking… ("
                                                        + elapsed
                                                        + "s)");
                                    });
                    thinkingTimer.start();
                });
    }

    /**
     * Appends a new line to the terminal log (if in log mode), or updates the placeholder label
     * text in place. No-op if a review is already displayed.
     */
    public void updateStatus(String message) {
        if (statusLog != null) {
            if (message.equals(lastStatusMessage)) return;
            lastStatusMessage = message;
            statusLog.append("> " + message + "\n");
            statusLog.setCaretPosition(statusLog.getDocument().getLength());
        } else if (placeholderLabel != null) {
            placeholderLabel.setText(message);
        }
    }

    public void showError(String message) {
        rebuild(
                () -> {
                    add(errorCard(message));
                    add(vgap(8));
                });
    }

    public void showReview(ReviewResult result, String rawDiff) {
        List<DiffFile> files = DiffParser.parseDiff(rawDiff);
        diffMaxCols = DiffParser.computeMaxColumns(files);
        FontMetrics monoFm = new JLabel().getFontMetrics(MONO);
        int charPx = monoFm.charWidth('0');
        diffGutterPx = charPx * 5; // 5-char gutter: "%4d "
        diffIndentPx = charPx * 6; // gutter + prefix char = content column
        diffCardMaxPx = charPx * diffMaxCols + 16; // content width + right slack
        Map<String, Map<Integer, List<LineComment>>> commentMap =
                buildCommentMap(result.getLineComments());
        List<LineComment> generalComments =
                result.getLineComments().stream()
                        .filter(c -> c.getFile().isBlank() || c.getLine() <= 0)
                        .toList();

        rebuild(
                () -> {
                    add(vgap(6));

                    for (DiffFile file : files) {
                        Map<Integer, List<LineComment>> fc =
                                commentMap.getOrDefault(file.name, Map.of());
                        add(fileSection(file, fc, result, diffCardMaxPx, diffIndentPx));
                        add(vgap(8));
                    }

                    if (!generalComments.isEmpty()) {
                        add(sectionHeader("General Comments"));
                        for (LineComment c : generalComments) {
                            CommentCard card =
                                    new CommentCard(
                                            c,
                                            dismissed -> onCardDismissed(dismissed, c, result),
                                            onVerifyComment,
                                            diffCardMaxPx);
                            commentCards.add(card);
                            add(card);
                        }
                        add(vgap(8));
                    }

                    add(verdictPanel(result.getVerdict()));
                    add(vgap(16));
                });
    }

    // ---------------------------------------------------------------
    // Section builders
    // ---------------------------------------------------------------

    private JPanel summaryCard(String summary) {
        JPanel card = filled(new Color(0x0c1c2e));
        card.setLayout(new BorderLayout(0, 4));
        card.setBorder(
                new CompoundBorder(
                        new MatteBorder(0, 3, 0, 0, new Color(0x1f6feb)),
                        new EmptyBorder(10, 12, 10, 12)));

        JEditorPane body = new JEditorPane();
        body.setContentType("text/html");
        body.setText(ChatPanel.buildHtml(summary));
        body.setEditable(false);
        body.setOpaque(false);
        body.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        body.setFont(UI);
        body.setBorder(null);
        card.add(body, BorderLayout.CENTER);

        return card;
    }

    private JPanel fileSection(
            DiffFile file,
            Map<Integer, List<LineComment>> comments,
            ReviewResult result,
            int cardMaxPx,
            int indentPx) {
        JPanel section = new JPanel();
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.setBackground(ThemeColors.BG);
        section.setBorder(new LineBorder(ThemeColors.BORDER_COL));
        section.setAlignmentX(LEFT_ALIGNMENT);
        section.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        // File header
        JPanel header = filled(ThemeColors.BG_SUBTLE);
        header.setLayout(new BorderLayout());
        header.setBorder(new EmptyBorder(5, 10, 5, 10));
        JLabel name = new JLabel(file.name);
        name.setFont(MONO.deriveFont(Font.BOLD));
        name.setForeground(ThemeColors.FG);
        header.add(name, BorderLayout.WEST);
        section.add(header);
        section.add(hline());

        String ext = fileExtension(file.name);

        // Pre-compute per-line spans for the whole file section via tree-sitter.
        List<List<DiffHighlighter.Span>> precomputedSpans = precomputeSpans(file.lines, ext);

        // Hover state: which new-line-number the mouse is currently over (-1 = none).
        final int[] hoverLineNum = {-1};
        // Maps new-line-number → start doc-offset (used to position the "+" button).
        final Map<Integer, Integer> lineStartByNum = new HashMap<>();
        // Maps new-line-number → sticky Position just after all cards for that line.
        final Map<Integer, Position> insertPositions = new HashMap<>();

        // Single JTextPane for all diff lines — enables cross-line text selection.
        // Overrides paintComponent to draw the "+" add-comment button on hover (filled)
        // and a faint outline "+" for all hoverable lines.
        JTextPane pane =
                new JTextPane() {
                    @Override
                    protected void paintComponent(Graphics g) {
                        super.paintComponent(g);
                        Graphics2D g2 = (Graphics2D) g.create();
                        g2.setRenderingHint(
                                RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        FontMetrics fm = getFontMetrics(getFont());
                        int lineH = fm.getHeight();
                        int gutterPx = fm.charWidth('0') * 5;
                        int btnSize = 14;

                        for (Map.Entry<Integer, Integer> entry : lineStartByNum.entrySet()) {
                            int lineNum = entry.getKey();
                            int startOffset = entry.getValue();
                            try {
                                Rectangle2D rect = modelToView2D(startOffset);
                                if (rect == null) continue;
                                int btnX = gutterPx + 1;
                                int btnY = (int) rect.getY() + (lineH - btnSize) / 2;
                                boolean hovered = lineNum == hoverLineNum[0];
                                if (hovered) {
                                    g2.setColor(new Color(0x238636));
                                    g2.fillRoundRect(btnX, btnY, btnSize, btnSize, 4, 4);
                                    g2.setColor(Color.WHITE);
                                } else {
                                    // Faint indicator: subtle outline "+" in muted color
                                    g2.setColor(
                                            new Color(
                                                    ThemeColors.FG_MUTED.getRed(),
                                                    ThemeColors.FG_MUTED.getGreen(),
                                                    ThemeColors.FG_MUTED.getBlue(),
                                                    60));
                                    g2.drawRoundRect(btnX, btnY, btnSize, btnSize, 4, 4);
                                    g2.setColor(
                                            new Color(
                                                    ThemeColors.FG_MUTED.getRed(),
                                                    ThemeColors.FG_MUTED.getGreen(),
                                                    ThemeColors.FG_MUTED.getBlue(),
                                                    80));
                                }
                                g2.setFont(getFont().deriveFont(Font.BOLD, 11f));
                                FontMetrics btnFm = g2.getFontMetrics();
                                int plusX = btnX + (btnSize - btnFm.stringWidth("+")) / 2;
                                int plusY =
                                        btnY
                                                + (btnSize + btnFm.getAscent() - btnFm.getDescent())
                                                        / 2;
                                g2.drawString("+", plusX, plusY);
                            } catch (BadLocationException ignored) {
                            }
                        }
                        g2.dispose();
                    }
                };
        pane.setEditable(false);
        pane.setBackground(ThemeColors.BG);
        pane.setFont(MONO);
        pane.setForeground(ThemeColors.FG);
        pane.setBorder(new EmptyBorder(0, 0, 0, 0));
        pane.setAlignmentX(LEFT_ALIGNMENT);
        pane.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        // Disable the default caret colour flash on focus — not needed for a read-only pane
        pane.setCaretColor(ThemeColors.BG);
        pane.putClientProperty("diffFileName", file.name);
        pane.setToolTipText("Click + in gutter to add an inline comment");

        StyledDocument doc = pane.getStyledDocument();

        // Record (startOffset, endOffset) for lines that need a non-default background
        record BgRange(int start, int end, Color color) {}
        List<BgRange> bgRanges = new ArrayList<>();
        List<LineInfo> lineInfos = new ArrayList<>();

        try {
            for (int lineIndex = 0; lineIndex < file.lines.size(); lineIndex++) {
                DiffLine line = file.lines.get(lineIndex);
                Color lineBg =
                        switch (line.type()) {
                            case '+' -> ThemeColors.ADD_BG;
                            case '-' -> ThemeColors.DEL_BG;
                            default -> ThemeColors.BG;
                        };
                Color pfxColor =
                        switch (line.type()) {
                            case '+' -> ThemeColors.ADD_FG;
                            case '-' -> ThemeColors.DEL_FG;
                            default -> ThemeColors.FG_MUTED;
                        };

                int lineStart = doc.getLength();
                if (line.newLineNum() > 0) {
                    lineInfos.add(new LineInfo(lineStart, line.newLineNum(), line.content()));
                    lineStartByNum.put(line.newLineNum(), lineStart);
                }

                // Gutter: 4-char line number + separator space
                String numStr =
                        line.newLineNum() > 0 ? String.format("%4d ", line.newLineNum()) : "     ";
                doc.insertString(doc.getLength(), numStr, styledAttr(ThemeColors.FG_MUTED, lineBg));

                // Prefix (+/-/space)
                String pfxStr =
                        switch (line.type()) {
                            case '+' -> "+";
                            case '-' -> "-";
                            default -> " ";
                        };
                doc.insertString(doc.getLength(), pfxStr, styledAttr(pfxColor, lineBg));

                // Syntax-highlighted content via tree-sitter spans.
                List<DiffHighlighter.Span> spans = precomputedSpans.get(lineIndex);
                for (DiffHighlighter.Span span : spans) {
                    String tok = line.content().substring(span.start(), span.end());
                    doc.insertString(doc.getLength(), tok, styledAttr(span.color(), lineBg));
                }

                int lineEnd = doc.getLength();

                // Newline (must carry the line background so the painter covers the full row)
                doc.insertString(doc.getLength(), "\n", styledAttr(lineBg, lineBg));

                if (lineBg != ThemeColors.BG) {
                    bgRanges.add(new BgRange(lineStart, lineEnd, lineBg));
                }

                // Inline comment cards — embedded directly into the document flow
                if (line.newLineNum() > 0 && comments.containsKey(line.newLineNum())) {
                    for (LineComment c : comments.get(line.newLineNum())) {
                        CommentCard card =
                                new CommentCard(
                                        c,
                                        dismissed -> onCardDismissed(dismissed, c, result),
                                        onVerifyComment,
                                        cardMaxPx);
                        commentCards.add(card);
                        JPanel wrapper = cardWrapper(card, indentPx);
                        Style compStyle = doc.addStyle(null, null);
                        StyleConstants.setComponent(compStyle, wrapper);
                        doc.insertString(doc.getLength(), " ", compStyle);
                        doc.insertString(doc.getLength(), "\n", null);
                    }
                }

                // Sticky insert-position at the final '\n' of this line's block (including
                // any pre-existing cards).  Pointing to the '\n' char itself (not one past
                // it) keeps the position stable when subsequent lines are appended at
                // doc.getLength(): those appends happen at offsets > this position, so the
                // mark is never bumped.  addNewComment inserts at pos.getOffset()+1.
                if (line.newLineNum() > 0) {
                    insertPositions.put(line.newLineNum(), doc.createPosition(doc.getLength() - 1));
                }
            }
        } catch (BadLocationException ignored) {
        }

        // Paint full-width ADD/DEL backgrounds (StyledDocument bg only covers text width)
        Highlighter highlighter = pane.getHighlighter();
        for (BgRange r : bgRanges) {
            try {
                highlighter.addHighlight(r.start(), r.end(), new FullWidthLinePainter(r.color()));
            } catch (BadLocationException ignored) {
            }
        }

        // Mouse handling: gutter left-click sets line context; "+" click adds a comment;
        // right-click shows popup menu.
        String filename = file.name;

        pane.addMouseMotionListener(
                new java.awt.event.MouseMotionAdapter() {
                    @Override
                    public void mouseMoved(java.awt.event.MouseEvent e) {
                        int offset = pane.viewToModel2D(e.getPoint());
                        LineInfo hit = lineInfoAt(lineInfos, offset);
                        int newHover = (hit != null) ? hit.lineNum() : -1;
                        if (newHover != hoverLineNum[0]) {
                            hoverLineNum[0] = newHover;
                            pane.repaint();
                        }
                    }
                });

        pane.addMouseListener(
                new java.awt.event.MouseAdapter() {
                    @Override
                    public void mouseExited(java.awt.event.MouseEvent e) {
                        if (hoverLineNum[0] != -1) {
                            hoverLineNum[0] = -1;
                            pane.repaint();
                        }
                    }

                    @Override
                    public void mouseClicked(java.awt.event.MouseEvent e) {
                        if (e.isPopupTrigger()
                                || e.getButton() != java.awt.event.MouseEvent.BUTTON1) {
                            return;
                        }
                        FontMetrics fm = pane.getFontMetrics(pane.getFont());
                        int gutterPx = fm.charWidth('0') * 5;
                        int offset = pane.viewToModel2D(e.getPoint());
                        LineInfo hit = lineInfoAt(lineInfos, offset);

                        // "+" button area: just to the right of the gutter numerals
                        if (e.getX() >= gutterPx && e.getX() <= gutterPx + 16 && hit != null) {
                            addNewComment(
                                    hit.lineNum(), filename, doc, insertPositions, result, pane);
                            return;
                        }
                        if (e.getX() > gutterPx) {
                            return;
                        }
                        if (hit == null) {
                            return;
                        }
                        lastKnownSelection = filename + ":" + hit.lineNum() + "\n" + hit.content();
                    }

                    @Override
                    public void mousePressed(java.awt.event.MouseEvent e) {
                        maybeShowPopup(e);
                    }

                    @Override
                    public void mouseReleased(java.awt.event.MouseEvent e) {
                        maybeShowPopup(e);
                    }

                    private void maybeShowPopup(java.awt.event.MouseEvent e) {
                        if (!e.isPopupTrigger()) {
                            return;
                        }
                        int offset = pane.viewToModel2D(e.getPoint());
                        LineInfo hit = lineInfoAt(lineInfos, offset);

                        JPopupMenu menu = new JPopupMenu();

                        // "Explain this line" — uses the line under the cursor
                        JMenuItem explainLine = new JMenuItem("Ask Claude: explain this line");
                        explainLine.setEnabled(hit != null && onAskClaude != null);
                        if (hit != null) {
                            final LineInfo li = hit;
                            explainLine.addActionListener(
                                    ev -> {
                                        String ctx =
                                                "In "
                                                        + filename
                                                        + ", line "
                                                        + li.lineNum()
                                                        + ":\n"
                                                        + li.content();
                                        onAskClaude.accept(ctx, "What does this line do?");
                                    });
                        }
                        menu.add(explainLine);

                        // "Explain selection" — uses whatever text is selected in the pane
                        String sel = pane.getSelectedText();
                        JMenuItem explainSel = new JMenuItem("Ask Claude: explain selection");
                        explainSel.setEnabled(sel != null && !sel.isBlank() && onAskClaude != null);
                        if (sel != null && !sel.isBlank()) {
                            final String selectedText = sel;
                            explainSel.addActionListener(
                                    ev -> {
                                        String ctx = "In " + filename + ":\n" + selectedText;
                                        onAskClaude.accept(ctx, "What does this code do?");
                                    });
                        }
                        menu.add(explainSel);

                        // "Summarize file" — reconstructs the new version of the file from the diff
                        JMenuItem summarizeFile = new JMenuItem("Ask Claude: summarize this file");
                        summarizeFile.setEnabled(onAskClaude != null);
                        summarizeFile.addActionListener(
                                ev -> {
                                    StringBuilder fileContent = new StringBuilder();
                                    for (DiffLine dl : file.lines) {
                                        if (dl.type() != '-') {
                                            fileContent.append(dl.content()).append("\n");
                                        }
                                    }
                                    String ctx =
                                            "File: "
                                                    + filename
                                                    + "\n\n"
                                                    + fileContent.toString().stripTrailing();
                                    onAskClaude.accept(ctx, "Summarize what this file does.");
                                });
                        menu.add(summarizeFile);

                        menu.show(pane, e.getX(), e.getY());
                    }
                });

        section.add(pane);
        return section;
    }

    /**
     * Inserts a new blank {@link CommentCard} into {@code doc} at the sticky position recorded for
     * {@code lineNum}, adds the matching {@link LineComment} to the review result, and focuses the
     * card's text area so the user can start typing immediately.
     */
    private void addNewComment(
            int lineNum,
            String filename,
            StyledDocument doc,
            Map<Integer, Position> insertPositions,
            ReviewResult result,
            JTextPane pane) {
        Position pos = insertPositions.get(lineNum);
        if (pos == null) {
            return;
        }
        LineComment newComment = new LineComment(filename, lineNum, "note", "");
        result.getLineComments().add(newComment);
        CommentCard card =
                new CommentCard(
                        newComment,
                        dismissed -> onCardDismissed(dismissed, newComment, result),
                        onVerifyComment,
                        diffCardMaxPx);
        commentCards.add(card);
        try {
            // Insert one past the stored '\n' so the card appears after the line's block.
            int insertOffset = pos.getOffset() + 1;
            JPanel wrapper = cardWrapper(card, diffIndentPx);
            Style compStyle = doc.addStyle(null, null);
            StyleConstants.setComponent(compStyle, wrapper);
            doc.insertString(insertOffset, " ", compStyle);
            doc.insertString(insertOffset + 1, "\n", null);
            // Advance the stored position to the new card's '\n' so subsequent clicks
            // on the same line append after this card rather than before it.
            insertPositions.put(lineNum, doc.createPosition(insertOffset + 1));
            pane.revalidate();
            pane.repaint();
        } catch (BadLocationException ignored) {
        }
        if (onCommentRemoved != null) {
            onCommentRemoved.run();
        }
        SwingUtilities.invokeLater(card::focusBody);
    }

    private static LineInfo lineInfoAt(List<LineInfo> lineInfos, int docOffset) {
        LineInfo hit = null;
        for (LineInfo li : lineInfos) {
            if (li.docOffset() <= docOffset) hit = li;
            else break;
        }
        return hit;
    }

    /**
     * Builds a {@link SimpleAttributeSet} with monospaced font, given foreground and background.
     */
    private static SimpleAttributeSet styledAttr(Color fg, Color bg) {
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setFontFamily(a, Font.MONOSPACED);
        StyleConstants.setFontSize(a, 12);
        StyleConstants.setForeground(a, fg);
        StyleConstants.setBackground(a, bg);
        return a;
    }

    /**
     * Paints a solid, full-viewport-width rectangle at the y-position of each highlighted line,
     * regardless of how wide the text content is.
     */
    private static final class FullWidthLinePainter implements Highlighter.HighlightPainter {
        private final Color color;

        FullWidthLinePainter(Color color) {
            this.color = color;
        }

        @Override
        public void paint(Graphics g, int p0, int p1, Shape bounds, JTextComponent c) {
            try {
                Rectangle alloc = bounds.getBounds();
                Rectangle2D r0 = c.modelToView2D(p0);
                Rectangle2D r1 = c.modelToView2D(Math.max(p0, p1 - 1));
                if (r0 == null || r1 == null) return;
                g.setColor(color);
                int lineH = (int) r0.getHeight();
                int y = (int) r0.getY();
                int yEnd = (int) r1.getY();
                while (y <= yEnd) {
                    g.fillRect(alloc.x, y, alloc.width, lineH);
                    y += lineH;
                }
            } catch (BadLocationException ignored) {
            }
        }
    }

    private JPanel sectionHeader(String title) {
        JPanel header = filled(ThemeColors.BG_SUBTLE);
        header.setLayout(new BorderLayout());
        header.setBorder(
                new CompoundBorder(
                        new LineBorder(ThemeColors.BORDER_COL), new EmptyBorder(5, 10, 5, 10)));
        JLabel label = new JLabel(title);
        label.setFont(MONO.deriveFont(Font.BOLD));
        label.setForeground(ThemeColors.FG);
        header.add(label, BorderLayout.WEST);
        return header;
    }

    private JPanel verdictPanel(String verdict) {
        String[] style =
                switch (verdict.toUpperCase()) {
                    case "APPROVE" -> new String[] {"238636", "\u2714\u2002Approved"};
                    case "REQUEST_CHANGES" ->
                            new String[] {"da3633", "\u2718\u2002Changes Requested"};
                    default -> new String[] {"6e7681", "\u25ce\u2002Needs Discussion"};
                };
        Color bg = new Color(Integer.parseInt(style[0], 16));

        JPanel panel = filled(bg);
        panel.setLayout(new FlowLayout(FlowLayout.LEFT, 14, 8));
        JLabel label = new JLabel(style[1]);
        label.setFont(UI.deriveFont(Font.BOLD, 14f));
        label.setForeground(Color.WHITE);
        panel.add(label);
        return panel;
    }

    private JLabel muted(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(ThemeColors.FG_MUTED);
        label.setFont(UI);
        label.setAlignmentX(LEFT_ALIGNMENT);
        label.setBorder(new EmptyBorder(20, 20, 20, 20));
        return label;
    }

    private JPanel errorCard(String message) {
        JPanel card = filled(new Color(0x2a0f0e));
        card.setLayout(new BorderLayout());
        card.setBorder(
                new CompoundBorder(
                        new MatteBorder(0, 3, 0, 0, new Color(0xda3633)),
                        new EmptyBorder(10, 12, 10, 12)));
        JTextArea text = new JTextArea("Error: " + message);
        text.setFont(UI);
        text.setForeground(new Color(0xffa198));
        text.setBackground(new Color(0x2a0f0e));
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setBorder(null);
        card.add(text, BorderLayout.CENTER);
        return card;
    }

    // ---------------------------------------------------------------
    // Layout helpers
    // ---------------------------------------------------------------

    /**
     * Wraps a {@link CommentCard} in a transparent panel with a fixed left strut so the card's left
     * edge aligns with the content column of the diff (past gutter + prefix). The wrapper is what
     * gets embedded as a component in the {@link javax.swing.text.StyledDocument}.
     */
    private static JPanel cardWrapper(CommentCard card, int indentPx) {
        JPanel wrapper = new JPanel();
        wrapper.setLayout(new BoxLayout(wrapper, BoxLayout.X_AXIS));
        wrapper.setOpaque(false);
        wrapper.setAlignmentX(LEFT_ALIGNMENT);
        wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        wrapper.add(Box.createRigidArea(new Dimension(indentPx, 0)));
        wrapper.add(card);
        wrapper.add(Box.createHorizontalGlue());
        return wrapper;
    }

    /** Creates a panel pre-configured with the standard fill settings. */
    private JPanel filled(Color bg) {
        JPanel p = new JPanel();
        p.setBackground(bg);
        p.setAlignmentX(LEFT_ALIGNMENT);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return p;
    }

    private JSeparator hline() {
        JSeparator s = new JSeparator(SwingConstants.HORIZONTAL);
        s.setForeground(ThemeColors.BORDER_COL);
        s.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        return s;
    }

    private Component vgap(int h) {
        return Box.createRigidArea(new Dimension(0, h));
    }

    private void rebuild(Runnable builder) {
        if (thinkingTimer != null) {
            thinkingTimer.stop();
            thinkingTimer = null;
        }
        removeAll();
        commentCards.clear();
        currentCommentIndex = -1;
        placeholderLabel = null;
        statusLog = null;
        lastStatusMessage = null;
        thinkingLabel = null;
        builder.run();
        revalidate();
        repaint();
    }

    // ---------------------------------------------------------------
    // Scrollable implementation (ensures panel fills viewport width)
    // ---------------------------------------------------------------

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle r, int o, int d) {
        return 20;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle r, int o, int d) {
        return 200;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }

    // ---------------------------------------------------------------
    // Comment navigation
    // ---------------------------------------------------------------

    /**
     * Returns the currently selected text from any focused {@link javax.swing.text.JTextComponent}
     * inside this panel (e.g. an editable comment body), or an empty string if nothing is selected.
     */
    public String getSelectedText() {
        java.awt.Component focused =
                java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (focused instanceof javax.swing.text.JTextComponent tc
                && javax.swing.SwingUtilities.isDescendingFrom(focused, this)) {
            String sel = tc.getSelectedText();
            // Only update when the user has actively dragged a text selection.
            // Clicking without dragging gives null/empty — must NOT erase a gutter-click context.
            if (sel != null && !sel.isBlank()) {
                String fileName =
                        (tc instanceof JTextPane jp)
                                ? (String) jp.getClientProperty("diffFileName")
                                : null;
                lastKnownSelection = (fileName != null) ? fileName + "\n" + sel : sel;
            }
        }
        return lastKnownSelection;
    }

    /** Call when a new review is loaded to discard stale selection context. */
    public void clearSelection() {
        lastKnownSelection = "";
    }

    public void setOnCommentRemoved(Runnable callback) {
        this.onCommentRemoved = callback;
    }

    public void setOnAskClaude(java.util.function.BiConsumer<String, String> callback) {
        this.onAskClaude = callback;
    }

    public void setOnVerifyComment(java.util.function.Consumer<CommentCard> callback) {
        this.onVerifyComment = callback;
    }

    private void onCardDismissed(CommentCard card, LineComment comment, ReviewResult result) {
        commentCards.remove(card);
        result.getLineComments().remove(comment);
        if (currentCommentIndex >= commentCards.size()) {
            currentCommentIndex = commentCards.size() - 1;
        }
        if (onCommentRemoved != null) onCommentRemoved.run();
    }

    /** Number of inline/general comments in the current review. */
    public int getCommentCount() {
        return commentCards.size();
    }

    /** Scroll to the next comment, wrapping around. Returns {@code false} if none. */
    public boolean nextComment() {
        if (commentCards.isEmpty()) return false;
        currentCommentIndex = (currentCommentIndex + 1) % commentCards.size();
        scrollToCard(commentCards.get(currentCommentIndex));
        return true;
    }

    /** Scroll to the previous comment, wrapping around. Returns {@code false} if none. */
    public boolean prevComment() {
        if (commentCards.isEmpty()) return false;
        currentCommentIndex = (currentCommentIndex - 1 + commentCards.size()) % commentCards.size();
        scrollToCard(commentCards.get(currentCommentIndex));
        return true;
    }

    /** Current comment index (0-based), or {@code -1} if no navigation has occurred. */
    public int getCurrentCommentIndex() {
        return currentCommentIndex;
    }

    private void scrollToCard(CommentCard card) {
        JScrollPane sp =
                (JScrollPane)
                        javax.swing.SwingUtilities.getAncestorOfClass(JScrollPane.class, this);
        if (sp == null) {
            card.scrollRectToVisible(
                    new Rectangle(0, 0, card.getWidth(), Math.max(card.getHeight(), 1)));
            return;
        }
        // Convert the card's top-left corner into ReviewPanel coordinates and
        // set the viewport position directly so the card appears at the top.
        java.awt.Point pt =
                javax.swing.SwingUtilities.convertPoint(card, new java.awt.Point(0, 0), this);
        sp.getViewport().setViewPosition(new java.awt.Point(0, Math.max(0, pt.y - 8)));
    }

    private record LineInfo(int docOffset, int lineNum, String content) {}

    /**
     * Pre-computes per-line syntax highlight spans for all lines in a file section.
     *
     * <p>Groups consecutive lines into hunks (split at {@link DiffLine#hunkStart()} boundaries) and
     * calls {@link DiffHighlighter#colorHunk} for each hunk. Always returns a non-null result:
     * languages without a tree-sitter grammar produce plain {@link DiffHighlighter#FG_COLOR} spans.
     */
    private static List<List<DiffHighlighter.Span>> precomputeSpans(
            List<DiffLine> lines, String ext) {
        if (lines.isEmpty()) {
            return new ArrayList<>();
        }
        List<List<DiffHighlighter.Span>> result = new ArrayList<>(lines.size());
        // Process hunk by hunk (each hunk starts at a line where hunkStart() == true).
        int hunkStart = 0;
        while (hunkStart < lines.size()) {
            int hunkEnd = hunkStart + 1;
            while (hunkEnd < lines.size() && !lines.get(hunkEnd).hunkStart()) {
                hunkEnd++;
            }
            List<String> contents = new ArrayList<>(hunkEnd - hunkStart);
            for (int i = hunkStart; i < hunkEnd; i++) {
                contents.add(lines.get(i).content());
            }
            result.addAll(DiffHighlighter.colorHunk(contents, ext));
            hunkStart = hunkEnd;
        }
        return result;
    }

    private static Map<String, Map<Integer, List<LineComment>>> buildCommentMap(
            List<LineComment> comments) {
        Map<String, Map<Integer, List<LineComment>>> map = new HashMap<>();
        for (LineComment c : comments) {
            if (c.getFile().isBlank() || c.getLine() <= 0) continue;
            String file = c.getFile();
            if (file.startsWith("a/") || file.startsWith("b/")) file = file.substring(2);
            map.computeIfAbsent(file, k -> new HashMap<>())
                    .computeIfAbsent(c.getLine(), k -> new ArrayList<>())
                    .add(c);
        }
        return map;
    }

    // ---------------------------------------------------------------
    // Utilities
    // ---------------------------------------------------------------

    static String htmlEscape(String s) {
        return DiffHighlighter.htmlEscape(s);
    }

    private static String fileExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return (dot >= 0 && dot < filename.length() - 1) ? filename.substring(dot + 1) : "";
    }
}
