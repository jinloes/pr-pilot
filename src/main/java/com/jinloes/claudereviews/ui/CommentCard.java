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
    private static final Color NOTE_BORDER = new Color(0x8b949e);
    private static final Color NOTE_LABEL = new Color(0x8b949e);

    private static final String[] TYPES = {"issue", "suggestion", "note"};

    private static final Color TEXT_COLOR = new Color(0xe6edf3);
    private static final Color CARD_BG =
            new Color(0x161b22); // elevated surface, distinct from diff BG
    private static final Color CARD_BORDER = new Color(0x30363d); // matches ReviewPanel.BORDER_COL
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
        this(comment, onDismiss, null, 600);
    }

    public CommentCard(
            LineComment comment,
            Consumer<CommentCard> onDismiss,
            Consumer<CommentCard> onVerify,
            int maxPixelWidth) {
        this.comment = comment;
        this.maxPixelWidth = maxPixelWidth;

        Color[] colors = palette(comment.getType());
        final Color[] currentBorder = {colors[0]};
        final Color[] currentLabel = {colors[1]};

        setLayout(new BorderLayout(0, 2));
        setBackground(CARD_BG);
        setAlignmentX(LEFT_ALIGNMENT);
        applyBorder(currentBorder[0]);

        // ── Compact header: type label (clickable) + dismiss ──────────
        JLabel typeLabel = new JLabel(icon(comment.getType()) + " " + comment.getType());
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

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(typeLabel, BorderLayout.WEST);
        header.add(dismiss, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        // ── Body: plain monospace text area ───────────────────────────
        body = new JTextArea(comment.getBody());
        body.setFont(UI_FONT);
        body.setForeground(TEXT_COLOR);
        body.setBackground(CARD_BG);
        body.setCaretColor(TEXT_COLOR);
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

        // ── Right-click: verify ───────────────────────────────────────
        if (onVerify != null) {
            JPopupMenu popup = new JPopupMenu();
            JMenuItem verifyItem = new JMenuItem("Verify pattern in repo");
            verifyItem.addActionListener(e -> onVerify.accept(this));
            popup.add(verifyItem);
            body.setComponentPopupMenu(popup);
        }

        // ── Type cycling on label click ───────────────────────────────
        typeLabel.addMouseListener(
                new java.awt.event.MouseAdapter() {
                    @Override
                    public void mouseClicked(java.awt.event.MouseEvent e) {
                        int idx = 0;
                        for (int i = 0; i < TYPES.length; i++) {
                            if (TYPES[i].equals(comment.getType())) {
                                idx = i;
                                break;
                            }
                        }
                        String next = TYPES[(idx + 1) % TYPES.length];
                        comment.setType(next);
                        Color[] newColors = palette(next);
                        currentBorder[0] = newColors[0];
                        currentLabel[0] = newColors[1];
                        typeLabel.setForeground(currentLabel[0]);
                        typeLabel.setText(icon(next) + " " + next);
                        applyBorder(currentBorder[0]);
                        repaint();
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
                        new MatteBorder(1, 1, 1, 1, CARD_BORDER),
                        new CompoundBorder(
                                new MatteBorder(0, 2, 0, 0, accentColor),
                                new EmptyBorder(4, 8, 4, 8))));
    }

    private static Color[] palette(String type) {
        return switch (type == null ? "" : type.toLowerCase()) {
            case "issue" -> new Color[] {ISSUE_BORDER, ISSUE_LABEL};
            case "suggestion" -> new Color[] {SUG_BORDER, SUG_LABEL};
            default -> new Color[] {NOTE_BORDER, NOTE_LABEL};
        };
    }

    private static String icon(String type) {
        return switch (type == null ? "" : type.toLowerCase()) {
            case "issue" -> "⚠";
            case "suggestion" -> "💡";
            default -> "●";
        };
    }
}
