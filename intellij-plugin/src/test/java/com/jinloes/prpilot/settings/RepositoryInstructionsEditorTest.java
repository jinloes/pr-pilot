package com.jinloes.prpilot.settings;

import static com.jinloes.prpilot.settings.PluginSettingsTestSupport.component;
import static com.jinloes.prpilot.settings.PluginSettingsTestSupport.descendants;
import static com.jinloes.prpilot.settings.PluginSettingsTestSupport.label;
import static org.assertj.core.api.Assertions.assertThat;

import com.intellij.ui.components.JBTextArea;
import java.util.Map;
import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RepositoryInstructionsEditorTest {

    @Nested
    class RepositoryInstructionsEditor {
        @Test
        void disablesItselfWhenNothingIsRemembered() throws Exception {
            UiProbeRunner.runAtDefaultScales(RepositoryInstructionsEditorTest.class, "verifyEmpty");
        }

        @Test
        void editsTheSelectedRepositoryAndKeepsEditsAcrossSelectionChanges() throws Exception {
            UiProbeRunner.runAtDefaultScales(RepositoryInstructionsEditorTest.class, "verifyEdit");
        }

        @Test
        void forgetRemovesTheSelectedRepositoryAndClearingTextForgetsOnApply() throws Exception {
            UiProbeRunner.runAtDefaultScales(
                    RepositoryInstructionsEditorTest.class, "verifyForget");
        }

        @Test
        void reportsOversizedEditsInsteadOfDroppingThem() throws Exception {
            UiProbeRunner.runAtDefaultScales(
                    RepositoryInstructionsEditorTest.class, "verifyOversized");
        }
    }

    static void verifyEmpty() {
        PluginSettingsComponent component = component();
        component.setRepositoryReviewInstructions(Map.of());

        assertThat(repositoryCombo(component).isEnabled()).isFalse();
        assertThat(repositoryCombo(component).getSelectedItem())
                .isEqualTo("No remembered repositories");
        assertThat(repositoryArea(component).isEnabled()).isFalse();
        assertThat(forgetButton(component).isEnabled()).isFalse();
        assertThat(component.getRepositoryReviewInstructions()).isEmpty();
    }

    static void verifyEdit() {
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

    static void verifyForget() {
        PluginSettingsComponent component = component();
        component.setRepositoryReviewInstructions(
                Map.of("acme/widget", "Rule A", "acme/api", "Rule B"));

        forgetButton(component).doClick();
        assertThat(repositoryCombo(component).getSelectedItem()).isEqualTo("acme/widget");
        assertThat(repositoryArea(component).getText()).isEqualTo("Rule A");
        repositoryArea(component).setText("   ");
        assertThat(component.getRepositoryReviewInstructions()).isEmpty();
    }

    static void verifyOversized() {
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
}
