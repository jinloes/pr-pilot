package com.jinloes.prpilot.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.intellij.ui.components.JBTextArea;
import com.jinloes.prpilot.sidecar.github.CheckAuthResult;
import java.awt.Component;
import java.awt.Container;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RepositoryInstructionsEditorTest {

    @Nested
    class RepositoryInstructionsEditor {
        @Test
        void disablesItselfWhenNothingIsRemembered() throws Exception {
            runUiProbe("repository-instructions-empty");
        }

        @Test
        void editsTheSelectedRepositoryAndKeepsEditsAcrossSelectionChanges() throws Exception {
            runUiProbe("repository-instructions-edit");
        }

        @Test
        void forgetRemovesTheSelectedRepositoryAndClearingTextForgetsOnApply() throws Exception {
            runUiProbe("repository-instructions-forget");
        }

        @Test
        void reportsOversizedEditsInsteadOfDroppingThem() throws Exception {
            runUiProbe("repository-instructions-oversized");
        }
    }

    public static final class UiProbe {
        public static void main(String[] args) {
            try {
                switch (args[0]) {
                    case "repository-instructions-empty" -> verifyEmpty();
                    case "repository-instructions-edit" -> verifyEdit();
                    case "repository-instructions-forget" -> verifyForget();
                    case "repository-instructions-oversized" -> verifyOversized();
                    default -> throw new IllegalArgumentException("Unknown probe: " + args[0]);
                }
            } catch (Throwable failure) {
                failure.printStackTrace();
                System.exit(1);
            }
        }
    }

    private static void runUiProbe(String probe) throws Exception {
        for (String uiScale : List.of("1.0", "2.0")) {
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            Process process =
                    new ProcessBuilder(
                                    java,
                                    "--add-opens=java.desktop/javax.swing=ALL-UNNAMED",
                                    "-Djava.awt.headless=true",
                                    "-Dide.ui.scale=" + uiScale,
                                    "-Dos.name=Linux",
                                    "-cp",
                                    System.getProperty("java.class.path"),
                                    UiProbe.class.getName(),
                                    probe)
                            .redirectErrorStream(true)
                            .start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly().waitFor();
            }
            String output =
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(finished)
                    .withFailMessage("UI probe timed out at %s scale:%n%s", uiScale, output)
                    .isTrue();
            assertThat(process.exitValue())
                    .withFailMessage("UI probe failed at %s scale:%n%s", uiScale, output)
                    .isZero();
        }
    }

    private static void verifyEmpty() {
        PluginSettingsComponent component = component();
        component.setRepositoryReviewInstructions(Map.of());

        assertThat(repositoryCombo(component).isEnabled()).isFalse();
        assertThat(repositoryCombo(component).getSelectedItem())
                .isEqualTo("No remembered repositories");
        assertThat(repositoryArea(component).isEnabled()).isFalse();
        assertThat(forgetButton(component).isEnabled()).isFalse();
        assertThat(component.getRepositoryReviewInstructions()).isEmpty();
    }

    private static void verifyEdit() {
        PluginSettingsComponent component = component();
        component.setRepositoryReviewInstructions(
                Map.of("acme/widget", "Rule A", "acme/api", "Rule B"));

        assertThat(repositoryCombo(component).getSelectedItem()).isEqualTo("acme/api");
        assertThat(repositoryArea(component).getText()).isEqualTo("Rule B");
        repositoryArea(component).setText("  Rule B2  ");
        repositoryCombo(component).setSelectedItem("acme/widget");
        assertThat(repositoryArea(component).getText()).isEqualTo("Rule A");
        assertThat(component.getRepositoryReviewInstructions())
                .containsOnly(Map.entry("acme/widget", "Rule A"), Map.entry("acme/api", "Rule B2"));
    }

    private static void verifyForget() {
        PluginSettingsComponent component = component();
        component.setRepositoryReviewInstructions(
                Map.of("acme/widget", "Rule A", "acme/api", "Rule B"));

        forgetButton(component).doClick();
        assertThat(repositoryCombo(component).getSelectedItem()).isEqualTo("acme/widget");
        assertThat(repositoryArea(component).getText()).isEqualTo("Rule A");
        repositoryArea(component).setText("   ");
        assertThat(component.getRepositoryReviewInstructions()).isEmpty();
    }

    private static void verifyOversized() {
        PluginSettingsComponent component = component();
        component.setRepositoryReviewInstructions(Map.of("acme/widget", "Rule"));
        assertThat(component.repositoryWithOversizedInstructions()).isNull();
        repositoryArea(component).setText("x".repeat(10_001));
        assertThat(component.repositoryWithOversizedInstructions()).isEqualTo("acme/widget");
    }

    @SuppressWarnings("unchecked")
    private static JComboBox<String> repositoryCombo(PluginSettingsComponent component) {
        return (JComboBox<String>)
                label(component.getPanel(), "Remembered repository instructions:").getLabelFor();
    }

    private static JBTextArea repositoryArea(PluginSettingsComponent component) {
        return descendants(component.getPanel()).stream()
                .filter(JBTextArea.class::isInstance)
                .map(JBTextArea.class::cast)
                .filter(
                        candidate ->
                                "Instructions for the selected repository"
                                        .equals(
                                                candidate
                                                        .getAccessibleContext()
                                                        .getAccessibleName()))
                .findFirst()
                .orElseThrow();
    }

    private static AbstractButton forgetButton(PluginSettingsComponent component) {
        return descendants(component.getPanel()).stream()
                .filter(AbstractButton.class::isInstance)
                .map(AbstractButton.class::cast)
                .filter(button -> "Forget".equals(button.getText()))
                .findFirst()
                .orElseThrow();
    }

    private static PluginSettingsComponent component() {
        return new PluginSettingsComponent(
                ignored -> authenticated("octocat"), ignored -> {}, Runnable::run, () -> null);
    }

    private static javax.swing.JLabel label(Component root, String text) {
        return descendants(root).stream()
                .filter(javax.swing.JLabel.class::isInstance)
                .map(javax.swing.JLabel.class::cast)
                .filter(candidate -> text.equals(candidate.getText()))
                .findFirst()
                .orElseThrow();
    }

    private static List<Component> descendants(Component root) {
        List<Component> components = new ArrayList<>();
        collect(root, components);
        return components;
    }

    private static void collect(Component component, List<Component> components) {
        components.add(component);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collect(child, components);
            }
        }
    }

    private static CheckAuthResult authenticated(String username) {
        return new CheckAuthResult(
                "authenticated", username, "GitHub authentication is available.");
    }
}
