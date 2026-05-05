package com.jinloes.claudereviews.ui;

import com.jinloes.claudereviews.model.LineComment;
import java.awt.*;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.border.*;

/**
 * A lightweight inline annotation for a single review comment. Rendered as a plain text area with a
 * thin coloured left bar and a compact type/dismiss header — no heavy card background.
 */
public class CommentCard extends JPanel {

    // ---- Type colours (border + label only — no background fill) -----------

    private static final Color ISSUE_BORDER = new Color(0xd29922);
    private static final Color ISSUE_LABEL = new Color(0xe3b341);
    private static final Color SUG_BORDER = new Color(0x1f6feb);
    private static final Color SUG_LABEL = new Color(0x58a6ff);
    private static final Color PRAISE_BORDER = new Color(0x238636);
    private static final Color PRAISE_LABEL = new Color(0x3fb950);
    private static final Color NOTE_BORDER = new Color(0x8b949e);
    private static final Color NOTE_LABEL = new Color(0x8b949e);

    private static final String[] TYPES = {"issue", "suggestion", "praise", "note"};

    private static final Font UI_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    private static final Font LABEL_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 11);

    // -------------------------------------------------------------------------

    private final LineComment comment;
    private final JTextArea body;
    private final int maxPixelWidth;

    public LineComment getComment() {
        return comment;
    }

    public CommentCard(LineComment comment, Consumer<CommentCard> onDismiss) {
        this(comment, onDismiss, 600);
    }

    public CommentCard(LineComment comment, Consumer<CommentCard> onDismiss, int maxPixelWidth) {
        this.comment = comment;
        this.maxPixelWidth = maxPixelWidth;

        Color[] colors = palette(comment.getType());
        final Color[] currentBorder = {colors[0]};
        final Color[] currentLabel = {colors[1]};

        setLayout(new BorderLayout(0, 2));
        setBackground(ThemeColors.BG_SUBTLE);
        setAlignmentX(LEFT_ALIGNMENT);
        applyBorder(currentBorder[0]);

        // ── Compact header: type label (clickable) + dismiss ──────────
        JLabel typeLabel = new JLabel(icon(comment.getType()) + " " + comment.getType() + " ▾");
        typeLabel.setFont(LABEL_FONT);
        typeLabel.setForeground(currentLabel[0]);
        typeLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        typeLabel.setToolTipText("Click to change type");

        JButton dismiss = new JButton("×");
        dismiss.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        dismiss.setForeground(NOTE_BORDER);
        dismiss.setBorderPainted(false);
        dismiss.setContentAreaFilled(false);
        dismiss.setFocusPainted(false);
        dismiss.setMargin(new Insets(0, 4, 0, 0));
        dismiss.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        dismiss.setToolTipText("Dismiss");
        dismiss.addActionListener(
                e -> {
                    Container parent = getParent();
                    if (parent != null) {
                        parent.remove(this);
                        parent.revalidate();
                        parent.repaint();
                        Container grand = parent.getParent();
                        if (grand != null) {
                            grand.revalidate();
                            grand.repaint();
                        }
                    }
                    onDismiss.accept(this);
                });

        JPanel eastButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        eastButtons.setOpaque(false);
        eastButtons.add(dismiss);

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(typeLabel, BorderLayout.WEST);
        header.add(eastButtons, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        // ── Body: plain monospace text area ───────────────────────────
        body = new JTextArea(comment.getBody());
        body.setFont(UI_FONT);
        body.setForeground(ThemeColors.FG);
        body.setBackground(ThemeColors.BG_SUBTLE);
        body.setCaretColor(ThemeColors.FG);
        body.setLineWrap(true);
        body.setWrapStyleWord(true);
        body.setEditable(true);
        body.setBorder(new EmptyBorder(1, 0, 2, 0));
        body.getDocument()
                .addDocumentListener(
                        new javax.swing.event.DocumentListener() {
                            private void sync() {
                                comment.setBody(body.getText());
                            }

                            @Override
                            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                                sync();
                            }

                            @Override
                            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                                sync();
                            }

                            @Override
                            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                                sync();
                            }
                        });
        add(body, BorderLayout.CENTER);

        // ── Type popup on label click ─────────────────────────────────
        typeLabel.addMouseListener(
                new java.awt.event.MouseAdapter() {
                    @Override
                    public void mouseClicked(java.awt.event.MouseEvent e) {
                        JPopupMenu popup = new JPopupMenu();
                        for (String t : TYPES) {
                            Color[] tc = palette(t);
                            JMenuItem item = new JMenuItem(icon(t) + " " + t);
                            item.setForeground(tc[1]);
                            item.addActionListener(
                                    ae -> {
                                        comment.setType(t);
                                        Color[] nc = palette(t);
                                        currentBorder[0] = nc[0];
                                        currentLabel[0] = nc[1];
                                        typeLabel.setForeground(nc[1]);
                                        typeLabel.setText(icon(t) + " " + t + " ▾");
                                        applyBorder(nc[0]);
                                        repaint();
                                    });
                            popup.add(item);
                        }
                        popup.show(typeLabel, 0, typeLabel.getHeight());
                    }
                });
    }

    /** Focuses the body text area so the user can type immediately after card creation. */
    void focusBody() {
        body.requestFocusInWindow();
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(maxPixelWidth, Integer.MAX_VALUE);
    }

    private void applyBorder(Color accentColor) {
        // Outer box: 1px subtle border on all sides; inner: 2px coloured accent bar + padding
        setBorder(
                new CompoundBorder(
                        new MatteBorder(1, 1, 1, 1, ThemeColors.BORDER_COL),
                        new CompoundBorder(
                                new MatteBorder(0, 2, 0, 0, accentColor),
                                new EmptyBorder(4, 8, 4, 8))));
    }

    private static Color[] palette(String type) {
        return switch (type == null ? "" : type.toLowerCase()) {
            case "issue" -> new Color[] {ISSUE_BORDER, ISSUE_LABEL};
            case "suggestion" -> new Color[] {SUG_BORDER, SUG_LABEL};
            case "praise" -> new Color[] {PRAISE_BORDER, PRAISE_LABEL};
            default -> new Color[] {NOTE_BORDER, NOTE_LABEL};
        };
    }

    private static String icon(String type) {
        return switch (type == null ? "" : type.toLowerCase()) {
            case "issue" -> "⚠";
            case "suggestion" -> "💡";
            case "praise" -> "★";
            default -> "●";
        };
    }
}
