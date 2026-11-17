package com.jinloes.prpilot.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.intellij.util.ui.JBUI;
import com.jinloes.prpilot.sidecar.github.CheckAuthResult;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractButton;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/** Shared Swing traversal and fixture helpers for {@code PluginSettings*} UI tests. */
final class PluginSettingsTestSupport {

    private PluginSettingsTestSupport() {}

    static PluginSettingsComponent component() {
        return new PluginSettingsComponent(
                ignored -> authenticated("octocat"), ignored -> {}, Runnable::run, () -> null);
    }

    static PluginSettingsComponent component(
            PluginSettingsComponent.ProfileNamePrompt profileNamePrompt) {
        return new PluginSettingsComponent(
                ignored -> authenticated("octocat"),
                ignored -> {},
                Runnable::run,
                () -> null,
                profileNamePrompt);
    }

    static CheckAuthResult authenticated(String username) {
        return new CheckAuthResult(
                "authenticated", username, "GitHub authentication is available.");
    }

    @SuppressWarnings("unchecked")
    static <T> JComboBox<T> comboLabeled(Component root, String labelText) {
        return (JComboBox<T>) label(root, labelText).getLabelFor();
    }

    static JLabel label(Component root, String text) {
        List<JLabel> matches =
                descendants(root).stream()
                        .filter(JLabel.class::isInstance)
                        .map(JLabel.class::cast)
                        .filter(candidate -> text.equals(candidate.getText()))
                        .toList();
        assertThat(matches).hasSize(1);
        return matches.get(0);
    }

    static JLabel hintContaining(Component root, String text) {
        List<JLabel> matches =
                descendants(root).stream()
                        .filter(JLabel.class::isInstance)
                        .map(JLabel.class::cast)
                        .filter(candidate -> candidate.getText().contains(text))
                        .toList();
        assertThat(matches).hasSize(1);
        return matches.get(0);
    }

    static AbstractButton button(Component root, String text) {
        List<AbstractButton> matches =
                descendants(root).stream()
                        .filter(AbstractButton.class::isInstance)
                        .map(AbstractButton.class::cast)
                        .filter(candidate -> text.equals(candidate.getText()))
                        .toList();
        assertThat(matches).hasSize(1);
        return matches.get(0);
    }

    static void assertContentWidth(JComponent component) {
        assertThat(component.getPreferredSize().width).isEqualTo(JBUI.scale(320));
        assertThat(component.getMinimumSize().width).isEqualTo(JBUI.scale(320));
        assertThat(component.getMaximumSize().width).isEqualTo(JBUI.scale(320));
    }

    static void assertFieldWithHint(Component control, JLabel hint) {
        assertThat(control.getParent()).isSameAs(hint.getParent());
        assertThat(control.getParent()).isInstanceOf(JPanel.class);
        JPanel field = (JPanel) control.getParent();
        assertThat(field.getLayout()).isInstanceOf(BoxLayout.class);
        assertThat(field.getComponents()).containsSubsequence(control, hint);
        assertThat(field.isFocusable()).isFalse();
        assertThat(hint.isFocusable()).isFalse();
    }

    static int indexContaining(List<String> texts, String expected) {
        int index = -1;
        for (int i = 0; i < texts.size(); i++) {
            if (texts.get(i).contains(expected)) {
                assertThat(index).isEqualTo(-1);
                index = i;
            }
        }
        assertThat(index).isGreaterThanOrEqualTo(0);
        return index;
    }

    static List<Component> descendants(Component root) {
        List<Component> components = new ArrayList<>();
        collect(root, components);
        return components;
    }

    /** Whether {@code component} and every ancestor up to {@code root} are visible. */
    static boolean visibleWithin(Component component, Component root) {
        for (Component c = component; c != null; c = c.getParent()) {
            if (!c.isVisible()) return false;
            if (c == root) return true;
        }
        return false;
    }

    private static void collect(Component component, List<Component> components) {
        components.add(component);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collect(child, components);
            }
        }
    }
}
