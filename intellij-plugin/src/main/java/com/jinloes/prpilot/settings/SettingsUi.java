package com.jinloes.prpilot.settings;

import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import java.awt.Component;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.ScrollPaneConstants;

/** Shared sizing and hint helpers used by the settings sections. */
final class SettingsUi {

    static final int CONTENT_WIDTH = 320;

    private SettingsUi() {}

    static void boundContentWidth(JComponent component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        java.awt.Dimension size =
                new java.awt.Dimension(
                        JBUI.scale(CONTENT_WIDTH), component.getPreferredSize().height);
        component.setPreferredSize(size);
        component.setMinimumSize(size);
        component.setMaximumSize(size);
    }

    static JBScrollPane boundedTextArea(JBTextArea textArea) {
        JBScrollPane scrollPane =
                new JBScrollPane(
                        textArea,
                        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        java.awt.Dimension viewportSize = textArea.getPreferredScrollableViewportSize();
        scrollPane
                .getViewport()
                .setPreferredSize(
                        new java.awt.Dimension(JBUI.scale(CONTENT_WIDTH), viewportSize.height));
        boundContentWidth(scrollPane);
        return scrollPane;
    }

    static JPanel fieldWithHint(JComponent control, JLabel hint) {
        JPanel field = contentField(control);
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setFocusable(false);
        field.add(hint);
        return field;
    }

    static JPanel contentField(JComponent control) {
        control.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel field = new JPanel();
        field.setLayout(new BoxLayout(field, BoxLayout.Y_AXIS));
        field.setFocusable(false);
        field.setAlignmentX(Component.LEFT_ALIGNMENT);
        field.add(control);
        return field;
    }

    static JBLabel fieldLabel(String text, JComponent control) {
        JBLabel label = new JBLabel(text);
        label.setLabelFor(control);
        return label;
    }

    static JBLabel hintLabel(String html) {
        JBLabel label = new JBLabel(boundedHintHtml(html));
        label.setBorder(JBUI.Borders.emptyTop(2));
        label.setFocusable(false);
        return label;
    }

    static String boundedHintHtml(String html) {
        String inner = html;
        if (inner.startsWith("<html>")) {
            inner = inner.substring("<html>".length());
        }
        if (inner.endsWith("</html>")) {
            inner = inner.substring(0, inner.length() - "</html>".length());
        }
        return "<html><div style='width:" + JBUI.scale(480) + "px'>" + inner + "</div></html>";
    }
}
