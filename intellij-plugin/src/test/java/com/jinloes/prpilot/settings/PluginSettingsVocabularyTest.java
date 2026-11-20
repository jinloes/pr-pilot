package com.jinloes.prpilot.settings;

import static com.jinloes.prpilot.settings.PluginSettingsTestSupport.assertFieldWithHint;
import static com.jinloes.prpilot.settings.PluginSettingsTestSupport.button;
import static com.jinloes.prpilot.settings.PluginSettingsTestSupport.comboLabeled;
import static com.jinloes.prpilot.settings.PluginSettingsTestSupport.component;
import static com.jinloes.prpilot.settings.PluginSettingsTestSupport.descendants;
import static com.jinloes.prpilot.settings.PluginSettingsTestSupport.hintContaining;
import static com.jinloes.prpilot.settings.PluginSettingsTestSupport.indexContaining;
import static com.jinloes.prpilot.settings.PluginSettingsTestSupport.label;
import static com.jinloes.prpilot.settings.PluginSettingsTestSupport.visibleWithin;
import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.ReviewProvider;
import java.util.List;
import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for task-based settings sections, hint attachments, and shared cross-host vocabulary. */
class PluginSettingsVocabularyTest {

    @Nested
    class SectionsAndVocabulary {

        @Test
        void usesTaskBasedSectionsLabelsAndAttachedHints() throws Exception {
            UiProbeRunner.runAtDefaultScales(
                    PluginSettingsVocabularyTest.class, "verifySectionsAndHints");
        }

        @Test
        void usesTheSharedCrossHostVocabularyAndSectionOrder() throws Exception {
            UiProbeRunner.runAtDefaultScales(
                    PluginSettingsVocabularyTest.class, "verifySharedVocabulary");
        }
    }

    static void verifySectionsAndHints() {
        PluginSettingsComponent component = component();
        JPanel panel = component.getPanel();
        List<String> texts =
                descendants(panel).stream()
                        .filter(JLabel.class::isInstance)
                        .map(JLabel.class::cast)
                        .map(JLabel::getText)
                        .toList();

        assertThat(texts)
                .contains(
                        "Base URL:",
                        "Provider:",
                        "Model:",
                        "Profile:",
                        "Focus areas:",
                        "Custom instructions:")
                .doesNotContain(
                        "Review provider:",
                        "Review model:",
                        "Guidance profile:",
                        "Review focus areas:",
                        "Custom review instructions:");
        assertThat(texts.stream().anyMatch(text -> text.contains("Review settings"))).isFalse();
        assertThat(texts.stream().anyMatch(text -> text.contains("Review defaults"))).isFalse();

        List<Integer> sectionOrder =
                List.of(
                                "GitHub connection",
                                "Reviewer",
                                "Review guidance",
                                "Review validation",
                                "Notifications")
                        .stream()
                        .map(section -> indexContaining(texts, section))
                        .toList();
        assertThat(sectionOrder).isSorted();

        AbstractButton supervisor = button(panel, "Re-inspect coverage gaps");
        assertFieldWithHint(supervisor, hintContaining(panel, "bounded coverage check"));
        JComponent secondReviewer = (JComponent) label(panel, "Second reviewer:").getLabelFor();
        assertThat(secondReviewer).isInstanceOf(JComboBox.class);
        assertThat(indexContaining(texts, "Second reviewer:"))
                .isGreaterThan(indexContaining(texts, "Reviewer"))
                .isLessThan(indexContaining(texts, "Review guidance"));
        JLabel secondReviewerHint = hintContaining(panel, "Runs a second Copilot model");
        assertFieldWithHint(secondReviewer, secondReviewerHint);
        assertThat(secondReviewerHint.getText())
                .contains("in parallel with either provider")
                .contains("Choose Off to disable.");
        assertThat(component.getReviewSecondReviewerModel()).isEmpty();
        component.setReviewSecondReviewerModel(" gpt-5.4 ");
        assertThat(((JComboBox<?>) secondReviewer).getEditor().getItem()).isEqualTo("gpt-5.4");
        assertThat(component.getReviewSecondReviewerModel()).isEqualTo("gpt-5.4");
        component.setReviewSecondReviewerModel(null);
        assertThat(component.getReviewSecondReviewerModel()).isEmpty();

        JComponent secondReviewerEffort =
                (JComponent) label(panel, "Second reviewer effort:").getLabelFor();
        assertThat(secondReviewerEffort).isInstanceOf(JComboBox.class);
        assertFieldWithHint(
                secondReviewerEffort, hintContaining(panel, "Separate from the primary's"));
        assertThat(component.getReviewSecondReviewerEffort()).isEqualTo("medium");
        component.setReviewSecondReviewerEffort("low");
        assertThat(((JComboBox<?>) secondReviewerEffort).getSelectedItem()).isEqualTo("low");
        assertThat(component.getReviewSecondReviewerEffort()).isEqualTo("low");
        component.setReviewSecondReviewerEffort(" ");
        assertThat(component.getReviewSecondReviewerEffort()).isEqualTo("medium");
        component.setReviewSecondReviewerModel("");
        assertThat(secondReviewerEffort.isEnabled()).isFalse();
        component.setReviewSecondReviewerModel("gemini-4-argon");
        assertThat(secondReviewerEffort.isEnabled()).isTrue();
        component.setReviewSecondReviewerModel(null);
        assertThat(secondReviewerEffort.isEnabled()).isFalse();

        AbstractButton inheritMcpBox =
                button(panel, "Allow Copilot to use MCP tools from your trusted Copilot config");
        JLabel effortLabel = label(panel, "Reasoning effort:");
        component.setReviewProvider(ReviewProvider.CLAUDE);
        assertThat(visibleWithin(effortLabel, panel)).isFalse();
        assertThat(visibleWithin(inheritMcpBox, panel)).isFalse();
        component.setReviewProvider(ReviewProvider.COPILOT);
        assertThat(visibleWithin(effortLabel, panel)).isTrue();
        assertThat(visibleWithin(inheritMcpBox, panel)).isTrue();
        assertThat(indexContaining(texts, "Reasoning effort:"))
                .isGreaterThan(indexContaining(texts, "Reviewer"))
                .isLessThan(indexContaining(texts, "Second reviewer:"));
        component.setReviewProvider(ReviewProvider.CLAUDE);

        AbstractButton validation = button(panel, "Validate findings with a second pass");
        JLabel validationHint = hintContaining(panel, "roughly doubles review time");
        assertThat(validationHint.getText()).contains("improve precision");

        JComponent baseUrl = (JComponent) label(panel, "Base URL:").getLabelFor();
        assertFieldWithHint(baseUrl, hintContaining(panel, "Authentication uses"));

        JComboBox<PluginSettings.ReviewGuidanceProfile> profileCombo =
                comboLabeled(panel, "Profile:");
        assertFieldWithHint(
                (JComponent) profileCombo.getParent(),
                hintContaining(panel, "Save and reuse focus areas"));

        JComponent focusAreas = (JComponent) label(panel, "Focus areas:").getLabelFor();
        assertFieldWithHint(focusAreas, hintContaining(panel, "Comma-separated areas"));

        assertFieldWithHint(validation, validationHint);

        component.setReviewProvider(ReviewProvider.COPILOT);
        JComponent copilotModel = (JComponent) label(panel, "Model:").getLabelFor();
        assertFieldWithHint(
                (JComponent) copilotModel.getParent(),
                hintContaining(panel, PluginSettingsComponent.MODELS_LOADING_HINT));
        assertThat(copilotModel.getParent().getComponents()).contains(button(panel, "Refreshing…"));

        JComponent effort = (JComponent) label(panel, "Reasoning effort:").getLabelFor();
        assertFieldWithHint(effort, hintContaining(panel, "Higher is deeper but slower."));
        AbstractButton inheritMcp =
                button(panel, "Allow Copilot to use MCP tools from your trusted Copilot config");
        assertFieldWithHint(
                inheritMcp,
                hintContaining(panel, "Applies while reviewing untrusted pull request content."));
    }

    static void verifySharedVocabulary() {
        PluginSettingsComponent component = component();
        JPanel panel = component.getPanel();
        List<String> texts =
                descendants(panel).stream()
                        .filter(JLabel.class::isInstance)
                        .map(JLabel.class::cast)
                        .map(JLabel::getText)
                        .toList();

        assertThat(
                        List.of(
                                        "GitHub connection",
                                        "Reviewer",
                                        "Copilot tools",
                                        "Review guidance",
                                        "Review validation",
                                        "Experimental",
                                        "Notifications")
                                .stream()
                                .map(section -> indexContaining(texts, section))
                                .toList())
                .doesNotContain(-1)
                .isSorted();
        button(panel, "Allow Copilot to use MCP tools from your trusted Copilot config");
        assertThat(
                        hintContaining(
                                        panel,
                                        "Applies while reviewing untrusted pull request content.")
                                .getText())
                .contains("Servers load only from your own Copilot config")
                .contains("<code>~/.copilot/mcp-config.json</code>")
                .contains("a pull request's <code>.mcp.json</code> is never loaded.");
        button(panel, "Validate findings with a second pass");
        button(panel, "Notify for new PRs in starred repositories");
        button(panel, "Save as…");
        button(panel, "Check connection");
        button(panel, "Enable background PR notifications");
        button(panel, "Retry notification poll");
        List<String> buttons =
                descendants(panel).stream()
                        .filter(AbstractButton.class::isInstance)
                        .map(AbstractButton.class::cast)
                        .map(AbstractButton::getText)
                        .toList();
        assertThat(buttons)
                .doesNotContain(
                        "Enable background PR notifications (experimental)",
                        "Allow MCP tools for untrusted PR content",
                        "Notify when a new PR is opened on a starred repo",
                        "Check Status",
                        "Save current as…",
                        "Test");
    }
}
