package com.jinloes.claudereviews.ui;

import com.intellij.ui.components.JBScrollPane;
import com.jinloes.claudereviews.highlighting.DiffHighlighter;
import com.jinloes.claudereviews.model.ChatMessage;
import com.jinloes.claudereviews.model.ChatMessage.Role;
import com.jinloes.claudereviews.model.PullRequest;
import com.jinloes.claudereviews.model.ReviewResult;
import com.jinloes.claudereviews.services.ClaudeService;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.text.*;
import org.apache.commons.lang3.StringUtils;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

/**
 * Conversational chat panel backed by the local {@code claude} CLI.
 *
 * <p>The panel maintains a running conversation history and includes the current PR review as
 * system context. The {@code @} button inserts whatever text is currently selected in the review
 * panel as a quoted block.
 */
public class ChatPanel extends JPanel {

    private static final Font UI = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    private static final Font BOLD = new Font(Font.SANS_SERIF, Font.BOLD, 12);
    private static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 12);

    private static final Parser MARKDOWN_PARSER = Parser.builder().build();
    private static final HtmlRenderer MARKDOWN_RENDERER = HtmlRenderer.builder().build();
    private static final String HTML_HEADER =
            "<html><head><style type='text/css'>"
                    + "body{font-family:Dialog,SansSerif;font-size:11pt;color:#e6edf3;margin:0;padding:2px 0;}"
                    + "p{margin:0 0 3px 0;padding:0;}"
                    + "ol,ul{margin:2px 0 2px 20px;padding:0;}"
                    + "li{margin-bottom:1px;}"
                    + "h1{font-size:14pt;color:#58a6ff;margin:4px 0 2px;}"
                    + "h2{font-size:13pt;color:#58a6ff;margin:3px 0 2px;}"
                    + "h3{font-size:12pt;color:#58a6ff;margin:2px 0 2px;}"
                    + "blockquote{color:#8b949e;margin:2px 0 2px 8px;padding-left:6px;}"
                    + "code{font-family:Monospaced,monospace;color:#f0f6fc;background-color:#21262d;}"
                    + "pre{font-family:Monospaced,monospace;font-size:10pt;color:#e6edf3;"
                    + "background-color:#161b22;border:1px solid #30363d;"
                    + "padding:8px 10px;margin:4px 0;white-space:pre;}"
                    + "</style></head><body>";

    private final ClaudeService claudeService;
    private final Supplier<String> selectionSupplier;

    private final JPanel messagesPanel = new JPanel();
    private final JBScrollPane messagesScroll;
    private final JTextArea inputArea = new JTextArea(3, 0);
    private final JButton sendButton = new JButton("Send ▶");
    private final JLabel statusLabel = new JLabel(" ");
    private final JLabel contextBar = new JLabel(" ");
    private JButton toggleButton;
    private Runnable onToggle;

    private Timer selectionPoller;
    private String lastSelection = "";

    private final List<ChatMessage> history = new ArrayList<>();
    private String prContext = ""; // formatted PR + review, injected as background context
    private String projectConventions = ""; // CLAUDE.md / AGENTS.md from the open project root

    /** Fired once with the complete response text after the next send completes. */
    private java.util.function.Consumer<String> pendingResponseCallback;

    // ---------------------------------------------------------------

    public ChatPanel(ClaudeService claudeService, Supplier<String> selectionSupplier) {
        this.claudeService = claudeService;
        this.selectionSupplier = selectionSupplier;

        setLayout(new BorderLayout());
        setBackground(ThemeColors.BG);

        messagesPanel.setLayout(new BoxLayout(messagesPanel, BoxLayout.Y_AXIS));
        messagesPanel.setBackground(ThemeColors.BG);

        messagesScroll = new JBScrollPane(messagesPanel);
        messagesScroll.setBackground(ThemeColors.BG);
        messagesScroll.getViewport().setBackground(ThemeColors.BG);
        messagesScroll.setBorder(new MatteBorder(1, 0, 0, 0, ThemeColors.BORDER_COL));

        add(buildHeader(), BorderLayout.NORTH);
        add(messagesScroll, BorderLayout.CENTER);
        add(buildInput(), BorderLayout.SOUTH);

        showPlaceholder();
    }

    // ---------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------

    public void setOnToggle(Runnable r) {
        this.onToggle = r;
    }

    public void setCollapsed(boolean collapsed) {
        toggleButton.setText(collapsed ? "▸" : "▾");
    }

    /**
     * Load PR review context so every chat message is grounded in the review. Called after a review
     * is successfully generated.
     */
    public void setContext(PullRequest pr, ReviewResult review) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Pull Request #")
                .append(pr.getNumber())
                .append(" — ")
                .append(pr.getOwner())
                .append("/")
                .append(pr.getRepo())
                .append("\n");
        sb.append("Title: ").append(pr.getTitle()).append("\n");
        if (pr.getBody() != null && !pr.getBody().isBlank())
            sb.append("Description: ").append(pr.getBody()).append("\n");
        sb.append("\n## Review Summary\n").append(review.getSummary()).append("\n");
        List<com.jinloes.claudereviews.model.LineComment> comments = review.getLineComments();
        if (!comments.isEmpty()) {
            sb.append("\n## Review Comments\n");
            for (var c : comments) {
                sb.append("- [").append(c.getType().toUpperCase()).append("] ");
                if (!c.getFile().isBlank())
                    sb.append(c.getFile()).append(":").append(c.getLine()).append(" — ");
                sb.append(c.getBody()).append("\n");
            }
        }
        sb.append("\n## Verdict\n").append(review.getVerdict());
        prContext = sb.toString();
        sendButton.setEnabled(true);
        startSelectionPoller();
        if (history.isEmpty()) showPlaceholder();
    }

    /** Sets the project-level conventions (CLAUDE.md / AGENTS.md) injected into every message. */
    public void setProjectConventions(String conventions) {
        projectConventions = conventions == null ? "" : conventions;
    }

    /**
     * Programmatically ask Claude about a specific piece of code. {@code context} is shown as a
     * quoted block above the question.
     */
    public void askAbout(String context, String question) {
        askAbout(context, question, null);
    }

    /**
     * Same as {@link #askAbout(String, String)} but fires {@code onResponseComplete} with the full
     * response text once Claude finishes. Use this when the caller needs to persist the answer.
     */
    public void askAbout(
            String context,
            String question,
            java.util.function.Consumer<String> onResponseComplete) {
        pendingResponseCallback = onResponseComplete;
        lastSelection = context;
        inputArea.setText(question);
        sendMessage();
    }

    /**
     * Ask a focused question about a specific code snippet. Uses a lightweight prompt that omits
     * the full PR review context and comment list. Still shows the exchange in the chat panel and
     * adds it to history.
     */
    public void askFocused(
            String context,
            String question,
            java.util.function.Consumer<String> onResponseComplete) {
        pendingResponseCallback = onResponseComplete;
        removePlaceholder();

        String displayMessage =
                context.isBlank()
                        ? question
                        : "> " + context.strip().replace("\n", "\n> ") + "\n\n" + question;
        addUserBubble(displayMessage);
        StreamBubble sb = createClaudeBubble();

        sendButton.setEnabled(false);
        statusLabel.setText("Claude is thinking…");

        claudeService.chatFocused(
                context,
                question,
                chunk -> {
                    if ("…".equals(sb.streamArea().getText())) sb.streamArea().setText("");
                    sb.streamArea().append(chunk);
                    messagesPanel.revalidate();
                    scrollToBottom();
                },
                response -> {
                    reformatClaudeBubble(sb.panel(), sb.streamArea(), response);
                    history.add(new ChatMessage(Role.USER, displayMessage));
                    history.add(new ChatMessage(Role.ASSISTANT, response));
                    sendButton.setEnabled(!prContext.isBlank());
                    statusLabel.setText(" ");
                    java.util.function.Consumer<String> cb = pendingResponseCallback;
                    pendingResponseCallback = null;
                    if (cb != null) cb.accept(response);
                },
                err -> {
                    sb.streamArea().setForeground(ThemeColors.ERROR_FG);
                    sb.streamArea().setText("Error: " + err);
                    sendButton.setEnabled(!prContext.isBlank());
                    statusLabel.setText(" ");
                });
    }

    /** Clear context and history when a new PR is selected. */
    public void clearContext() {
        prContext = "";
        history.clear();
        sendButton.setEnabled(false);
        stopSelectionPoller();
        lastSelection = "";
        contextBar.setText(" ");
        messagesPanel.removeAll();
        showPlaceholder();
        messagesPanel.revalidate();
        messagesPanel.repaint();
    }

    // ---------------------------------------------------------------
    // UI construction
    // ---------------------------------------------------------------

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(ThemeColors.BG_SUBTLE);
        header.setBorder(
                new CompoundBorder(
                        new MatteBorder(0, 0, 1, 0, ThemeColors.BORDER_COL),
                        new EmptyBorder(4, 8, 4, 8)));
        JLabel title = new JLabel("Claude Chat");
        title.setFont(BOLD);
        title.setForeground(ThemeColors.FG);
        header.add(title, BorderLayout.WEST);

        JButton clearButton = new JButton("Clear");
        clearButton.setFont(UI);
        clearButton.addActionListener(
                e -> {
                    int choice =
                            JOptionPane.showConfirmDialog(
                                    this,
                                    "Clear all chat history?",
                                    "Clear Chat",
                                    JOptionPane.YES_NO_OPTION);
                    if (choice != JOptionPane.YES_OPTION) return;
                    history.clear();
                    messagesPanel.removeAll();
                    showPlaceholder();
                    messagesPanel.revalidate();
                    messagesPanel.repaint();
                });

        toggleButton = new JButton("▾");
        toggleButton.setFont(UI);
        toggleButton.setForeground(ThemeColors.FG_MUTED);
        toggleButton.setBorderPainted(false);
        toggleButton.setContentAreaFilled(false);
        toggleButton.setFocusPainted(false);
        toggleButton.setToolTipText("Collapse/expand chat");
        toggleButton.addActionListener(
                e -> {
                    if (onToggle != null) onToggle.run();
                });

        JPanel eastPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        eastPanel.setOpaque(false);
        eastPanel.add(clearButton);
        eastPanel.add(toggleButton);
        header.add(eastPanel, BorderLayout.EAST);
        return header;
    }

    private JPanel buildInput() {
        inputArea.setFont(UI);
        inputArea.setForeground(ThemeColors.FG);
        inputArea.setBackground(new Color(0x161b22));
        inputArea.setCaretColor(ThemeColors.FG);
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setBorder(new EmptyBorder(6, 8, 6, 8));
        // Right-click context menu with cut/copy/paste
        JPopupMenu popup = new JPopupMenu();
        JMenuItem cutItem = new JMenuItem("Cut");
        JMenuItem copyItem = new JMenuItem("Copy");
        JMenuItem pasteItem = new JMenuItem("Paste");
        cutItem.addActionListener(e -> inputArea.cut());
        copyItem.addActionListener(e -> inputArea.copy());
        pasteItem.addActionListener(e -> inputArea.paste());
        popup.add(cutItem);
        popup.add(copyItem);
        popup.add(pasteItem);
        inputArea.setComponentPopupMenu(popup);

        // Explicit Ctrl/Cmd+V binding so IntelliJ's global action system does not intercept it
        int mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        inputArea
                .getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_V, mask), "paste-from-clipboard");
        inputArea
                .getActionMap()
                .put(
                        "paste-from-clipboard",
                        new AbstractAction() {
                            @Override
                            public void actionPerformed(ActionEvent e) {
                                inputArea.paste();
                            }
                        });

        // Enter sends; Shift+Enter inserts newline
        inputArea.addKeyListener(
                new KeyAdapter() {
                    @Override
                    public void keyPressed(KeyEvent e) {
                        if (e.getKeyCode() == KeyEvent.VK_ENTER && !e.isShiftDown()) {
                            e.consume();
                            sendMessage();
                        }
                    }
                });

        JBScrollPane inputScroll = new JBScrollPane(inputArea);
        inputScroll.setBorder(new LineBorder(ThemeColors.BORDER_COL));
        inputScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        sendButton.setFont(BOLD);
        sendButton.setEnabled(false);
        sendButton.addActionListener(e -> sendMessage());

        statusLabel.setFont(UI);
        statusLabel.setForeground(ThemeColors.FG_MUTED);

        contextBar.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 11));
        contextBar.setForeground(new Color(0x58a6ff));
        contextBar.setBorder(new EmptyBorder(2, 8, 2, 8));

        JPanel inputRow = new JPanel(new BorderLayout(4, 0));
        inputRow.setBackground(ThemeColors.BG);
        inputRow.setBorder(new EmptyBorder(6, 6, 6, 6));
        inputRow.add(inputScroll, BorderLayout.CENTER);
        inputRow.add(sendButton, BorderLayout.EAST);

        JPanel inputPanel = new JPanel(new BorderLayout());
        inputPanel.setBackground(ThemeColors.BG);
        inputPanel.setBorder(new MatteBorder(1, 0, 0, 0, ThemeColors.BORDER_COL));
        inputPanel.add(contextBar, BorderLayout.NORTH);
        inputPanel.add(inputRow, BorderLayout.CENTER);
        inputPanel.add(statusLabel, BorderLayout.SOUTH);
        return inputPanel;
    }

    // ---------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------

    private void sendMessage() {
        String typed = inputArea.getText();
        if (typed.isBlank()) return;
        inputArea.setText("");
        sendButton.setEnabled(false);
        statusLabel.setText("Claude is thinking…");

        // Auto-include current selection as a quoted block
        String sel = lastSelection;
        String raw =
                sel.isBlank() ? typed : "> " + sel.strip().replace("\n", "\n> ") + "\n\n" + typed;

        addUserBubble(raw);
        StreamBubble sb = createClaudeBubble();

        List<ChatMessage> snapshot = new ArrayList<>(history);
        String effectiveContext =
                StringUtils.isNotBlank(projectConventions)
                        ? "### Project Conventions\n"
                                + projectConventions.strip()
                                + (StringUtils.isNotBlank(prContext)
                                        ? "\n\n---\n\n" + prContext
                                        : "")
                        : prContext;
        claudeService.chat(
                effectiveContext,
                snapshot,
                raw,
                chunk -> {
                    if ("\u2026".equals(sb.streamArea().getText())) sb.streamArea().setText("");
                    sb.streamArea().append(chunk);
                    messagesPanel.revalidate();
                    scrollToBottom();
                },
                response -> {
                    reformatClaudeBubble(sb.panel(), sb.streamArea(), response);
                    history.add(new ChatMessage(Role.USER, raw));
                    history.add(new ChatMessage(Role.ASSISTANT, response));
                    sendButton.setEnabled(true);
                    statusLabel.setText(" ");
                    java.util.function.Consumer<String> cb = pendingResponseCallback;
                    pendingResponseCallback = null;
                    if (cb != null) cb.accept(response);
                },
                err -> {
                    sb.streamArea().setForeground(ThemeColors.ERROR_FG);
                    sb.streamArea().setText("Error: " + err);
                    sendButton.setEnabled(true);
                    statusLabel.setText(" ");
                });
    }

    private void startSelectionPoller() {
        if (selectionPoller != null && selectionPoller.isRunning()) return;
        selectionPoller =
                new Timer(
                        500,
                        e -> {
                            String sel = selectionSupplier.get();
                            if (sel == null) sel = "";
                            lastSelection = sel;
                            if (sel.isBlank()) {
                                contextBar.setText("Enter to send · Shift+Enter for newline");
                            } else {
                                String preview = sel.strip().replace("\n", " ↵ ");
                                if (preview.length() > 80) preview = preview.substring(0, 77) + "…";
                                contextBar.setText("\u29c1 context: \u201c" + preview + "\u201d");
                            }
                        });
        selectionPoller.start();
    }

    private void stopSelectionPoller() {
        if (selectionPoller != null) {
            selectionPoller.stop();
            selectionPoller = null;
        }
    }

    // ---------------------------------------------------------------
    // Markdown segment model
    // ---------------------------------------------------------------

    private record Segment(boolean isCode, String lang, String text) {}

    private static final Pattern CODE_FENCE =
            Pattern.compile("```(\\w*)\n([\\s\\S]*?)```", Pattern.MULTILINE);

    private static List<Segment> parseSegments(String text) {
        List<Segment> out = new ArrayList<>();
        Matcher m = CODE_FENCE.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) out.add(new Segment(false, "", text.substring(last, m.start())));
            out.add(new Segment(true, m.group(1), m.group(2)));
            last = m.end();
        }
        if (last < text.length()) out.add(new Segment(false, "", text.substring(last)));
        return out;
    }

    // ---------------------------------------------------------------
    // Bubble builders
    // ---------------------------------------------------------------

    /** Wrapper returned from {@link #createClaudeBubble()} for streaming + later reformat. */
    private record StreamBubble(JPanel panel, JTextArea streamArea) {}

    private void addUserBubble(String text) {
        removePlaceholder();
        JPanel bubble = makeBubbleShell("You", ThemeColors.BG_USER, ThemeColors.ACCENT_BLUE);
        addFormattedContent(bubble, ThemeColors.BG_USER, text);
        addBubbleToPanel(bubble);
        scrollToBottom();
    }

    private StreamBubble createClaudeBubble() {
        removePlaceholder();
        JPanel bubble = makeBubbleShell("Claude", ThemeColors.BG_SUBTLE, ThemeColors.ACCENT_GREEN);

        JTextArea streamArea = new JTextArea("\u2026");
        streamArea.setFont(MONO);
        streamArea.setForeground(ThemeColors.FG);
        streamArea.setBackground(ThemeColors.BG_SUBTLE);
        streamArea.setEditable(false);
        streamArea.setLineWrap(true);
        streamArea.setWrapStyleWord(true);
        streamArea.setBorder(new EmptyBorder(4, 0, 0, 0));
        bubble.add(streamArea, BorderLayout.CENTER);

        addBubbleToPanel(bubble);
        return new StreamBubble(bubble, streamArea);
    }

    /** Called when streaming is done — replaces the raw stream area with formatted segments. */
    private void reformatClaudeBubble(JPanel bubble, JTextArea streamArea, String fullText) {
        bubble.remove(streamArea);
        addFormattedContent(bubble, ThemeColors.BG_SUBTLE, fullText);
        bubble.revalidate();
        bubble.repaint();
        scrollToBottom();
    }

    // ---------------------------------------------------------------
    // Formatting helpers
    // ---------------------------------------------------------------

    private void addFormattedContent(JPanel bubble, Color bg, String text) {
        List<Segment> segments = parseSegments(text);
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(bg);
        content.setOpaque(false);

        for (Segment seg : segments) {
            if (seg.isCode()) {
                content.add(makeCodeBlock(seg.lang(), seg.text(), bg));
                content.add(Box.createRigidArea(new Dimension(0, 4)));
            } else {
                String t = seg.text();
                // Trim leading/trailing blank lines around code blocks
                t = t.replaceAll("^\n+", "").replaceAll("\n+$", "");
                if (!t.isBlank()) {
                    content.add(makeMarkdownPane(t, bg));
                }
            }
        }
        bubble.add(content, BorderLayout.CENTER);
    }

    private JEditorPane makeMarkdownPane(String text, Color bg) {
        JEditorPane pane = new JEditorPane();
        pane.setEditable(false);
        pane.setOpaque(true);
        pane.setBackground(bg);
        pane.setForeground(ThemeColors.FG);
        pane.setFont(UI);
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        pane.setBorder(new EmptyBorder(2, 0, 2, 0));
        pane.setAlignmentX(Component.LEFT_ALIGNMENT);
        pane.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        pane.setContentType("text/html");
        pane.setText(buildHtml(text));
        return pane;
    }

    // ---------------------------------------------------------------
    // Markdown → HTML
    // ---------------------------------------------------------------

    static String buildHtml(String markdown) {
        return HTML_HEADER
                + MARKDOWN_RENDERER.render(MARKDOWN_PARSER.parse(markdown))
                + "</body></html>";
    }

    private JPanel makeCodeBlock(String lang, String code, Color bubbleBg) {
        Color codeBg = new Color(0x010409);
        JPanel wrapper = new JPanel(new BorderLayout(0, 2));
        wrapper.setBackground(codeBg);
        wrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
        wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        wrapper.setBorder(
                new CompoundBorder(
                        new LineBorder(ThemeColors.BORDER_COL), new EmptyBorder(6, 8, 6, 8)));

        if (!lang.isBlank()) {
            JLabel langLabel = new JLabel(lang);
            langLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
            langLabel.setForeground(ThemeColors.FG_MUTED);
            wrapper.add(langLabel, BorderLayout.NORTH);
        }

        // Syntax-highlighted JTextPane
        JTextPane pane = new JTextPane();
        pane.setEditable(false);
        pane.setBackground(codeBg);
        pane.setFont(MONO);
        pane.setForeground(DiffHighlighter.FG_COLOR);
        pane.setBorder(new EmptyBorder(2, 0, 0, 0));

        String ext = langToExt(lang);
        StyledDocument doc = pane.getStyledDocument();
        try {
            List<String> lines = List.of(code.split("\n", -1));
            List<List<DiffHighlighter.Span>> allSpans = DiffHighlighter.colorHunk(lines, ext);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                for (DiffHighlighter.Span span : allSpans.get(i)) {
                    String tok = line.substring(span.start(), span.end());
                    SimpleAttributeSet attr = new SimpleAttributeSet();
                    StyleConstants.setFontFamily(attr, Font.MONOSPACED);
                    StyleConstants.setFontSize(attr, 12);
                    StyleConstants.setForeground(attr, span.color());
                    StyleConstants.setBackground(attr, codeBg);
                    doc.insertString(doc.getLength(), tok, attr);
                }
                if (i < lines.size() - 1) doc.insertString(doc.getLength(), "\n", null);
            }
        } catch (BadLocationException ignored) {
        }

        wrapper.add(pane, BorderLayout.CENTER);
        return wrapper;
    }

    private static String langToExt(String lang) {
        return switch (lang.toLowerCase()) {
            case "kotlin" -> "kt";
            case "javascript" -> "js";
            case "typescript" -> "ts";
            case "python" -> "py";
            case "rust" -> "rs";
            default -> lang.toLowerCase();
        };
    }

    // ---------------------------------------------------------------
    // Panel plumbing
    // ---------------------------------------------------------------

    private JPanel makeBubbleShell(String who, Color bg, Color accent) {
        JPanel bubble = new JPanel(new BorderLayout(0, 4));
        bubble.setBackground(bg);
        bubble.setAlignmentX(Component.LEFT_ALIGNMENT);
        bubble.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        bubble.setBorder(
                new CompoundBorder(
                        new MatteBorder(0, 3, 0, 0, accent), new EmptyBorder(8, 10, 8, 10)));
        JLabel header = new JLabel(who);
        header.setFont(BOLD);
        header.setForeground(accent);
        bubble.add(header, BorderLayout.NORTH);
        return bubble;
    }

    private void addBubbleToPanel(JPanel bubble) {
        JSeparator sep = new JSeparator();
        sep.setForeground(ThemeColors.BORDER_COL);
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        messagesPanel.add(bubble);
        messagesPanel.add(sep);
        messagesPanel.revalidate();
    }

    private void removePlaceholder() {
        if (messagesPanel.getComponentCount() == 1
                && messagesPanel.getComponent(0) instanceof JLabel) {
            messagesPanel.removeAll();
        }
    }

    private void showPlaceholder() {
        messagesPanel.removeAll();
        String hintText =
                prContext.isBlank()
                        ? "Generate a review first, then ask questions here."
                        : "<html>Ask Claude anything about this PR…<br>"
                                + "<small style='color:gray'>Tip: click or select any diff line"
                                + " to include it as context.</small></html>";
        JLabel hint = new JLabel(hintText);
        hint.setForeground(ThemeColors.FG_MUTED);
        hint.setFont(UI);
        hint.setBorder(new EmptyBorder(16, 12, 16, 12));
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        messagesPanel.add(hint);
    }

    private void scrollToBottom() {
        SwingUtilities.invokeLater(
                () -> {
                    JScrollBar bar = messagesScroll.getVerticalScrollBar();
                    bar.setValue(bar.getMaximum());
                });
    }
}
