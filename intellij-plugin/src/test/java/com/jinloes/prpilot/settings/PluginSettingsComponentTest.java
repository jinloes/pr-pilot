package com.jinloes.prpilot.settings;

import static com.jinloes.prpilot.settings.PluginSettingsTestSupport.assertContentWidth;
import static com.jinloes.prpilot.settings.PluginSettingsTestSupport.assertFieldWithHint;
import static com.jinloes.prpilot.settings.PluginSettingsTestSupport.authenticated;
import static com.jinloes.prpilot.settings.PluginSettingsTestSupport.button;
import static com.jinloes.prpilot.settings.PluginSettingsTestSupport.comboLabeled;
import static com.jinloes.prpilot.settings.PluginSettingsTestSupport.component;
import static com.jinloes.prpilot.settings.PluginSettingsTestSupport.descendants;
import static com.jinloes.prpilot.settings.PluginSettingsTestSupport.hintContaining;
import static com.jinloes.prpilot.settings.PluginSettingsTestSupport.indexContaining;
import static com.jinloes.prpilot.settings.PluginSettingsTestSupport.label;
import static org.assertj.core.api.Assertions.assertThat;

import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import com.jinloes.prpilot.model.ReviewProvider;
import java.awt.Component;
import java.awt.Container;
import java.awt.ContainerOrderFocusTraversalPolicy;
import java.awt.FocusTraversalPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.AbstractButton;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ScrollPaneConstants;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PluginSettingsComponentTest {

    @Nested
    class AuthStatus {

        @Test
        void coordinatorConstructionDoesNotCheckTheDefaultHost() {
            List<String> checkedUrls = new ArrayList<>();

            new PluginSettingsComponent.AuthCheckCoordinator(
                    url -> {
                        checkedUrls.add(url);
                        return authenticated("octocat");
                    },
                    Runnable::run,
                    Runnable::run);

            assertThat(checkedUrls).isEmpty();
        }

        @Test
        void anOlderCheckCannotOverwriteANewerResult() {
            List<Runnable> checks = new ArrayList<>();
            AtomicReference<String> result = new AtomicReference<>();
            PluginSettingsComponent.AuthCheckCoordinator coordinator =
                    new PluginSettingsComponent.AuthCheckCoordinator(
                            url ->
                                    authenticated(
                                            url.contains("example")
                                                    ? "enterprise-user"
                                                    : "default-user"),
                            checks::add,
                            Runnable::run);

            long first = coordinator.begin();
            coordinator.execute(first, "https://github.com", value -> result.set(value.username()));
            long second = coordinator.begin();
            coordinator.execute(
                    second, "https://github.example.test", value -> result.set(value.username()));
            checks.get(1).run();
            checks.get(0).run();

            assertThat(result).hasValue("enterprise-user");
        }
    }

    @Nested
    class GetPanel {

        @Test
        void givesPrimaryControlsAReadableSharedWidthForBothProviders() throws Exception {
            UiProbeRunner.runAtDefaultScales(
                    PluginSettingsComponentTest.class, "verifySharedWidth");
        }

        @Test
        void boundsTheCustomInstructionsViewportAndKeepsVerticalScrolling() throws Exception {
            UiProbeRunner.runAtDefaultScales(
                    PluginSettingsComponentTest.class, "verifyCustomInstructions");
        }

        @Test
        void stacksProfileSelectionAboveActionsAndPreservesProfileState() throws Exception {
            UiProbeRunner.runAtDefaultScales(
                    PluginSettingsComponentTest.class, "verifyProfileLayout");
        }

        @Test
        void keepsProfileNameCorrectionContextOpenUntilValidOrCancelled() throws Exception {
            UiProbeRunner.runAtDefaultScales(
                    PluginSettingsComponentTest.class, "verifyProfileNameValidation");
        }

        @Test
        void offersTheExperimentalIntellijAssistedToggleOffByDefault() throws Exception {
            UiProbeRunner.runAtDefaultScales(
                    PluginSettingsComponentTest.class, "verifyIntellijAssistedToggle");
        }
    }

    static void verifySharedWidth() {
        PluginSettingsComponent component = component();
        JPanel panel = component.getPanel();

        JComboBox<ReviewProvider> providerCombo = comboLabeled(panel, "Provider:");
        assertThat(providerCombo.getPrototypeDisplayValue()).isEqualTo(ReviewProvider.COPILOT);
        assertContentWidth(providerCombo);
        assertThat(providerCombo.getParent().getLayout()).isInstanceOf(BoxLayout.class);
        assertThat(providerCombo.getParent().isFocusable()).isFalse();

        Component providerRenderer =
                providerCombo
                        .getRenderer()
                        .getListCellRendererComponent(
                                new JList<>(), ReviewProvider.COPILOT, -1, false, false);
        assertThat(providerCombo.getPreferredSize().width)
                .isGreaterThanOrEqualTo(providerRenderer.getPreferredSize().width);

        for (ReviewProvider provider : ReviewProvider.values()) {
            component.setReviewProvider(provider);
            assertContentWidth((JComponent) label(panel, "Model:").getLabelFor());
        }

        assertContentWidth((JComponent) label(panel, "Profile:").getLabelFor());
        assertContentWidth((JComponent) label(panel, "Focus areas:").getLabelFor());
    }

    static void verifyCustomInstructions() {
        JPanel panel = component().getPanel();
        JBTextArea textArea = (JBTextArea) label(panel, "Custom instructions:").getLabelFor();
        JBScrollPane scrollPane =
                descendants(panel).stream()
                        .filter(JBScrollPane.class::isInstance)
                        .map(JBScrollPane.class::cast)
                        .filter(candidate -> candidate.getViewport().getView() == textArea)
                        .findFirst()
                        .orElseThrow();

        assertThat(textArea.getRows()).isBetween(4, 5);
        assertThat(scrollPane.getVerticalScrollBarPolicy())
                .isEqualTo(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        assertThat(scrollPane.getPreferredSize().width).isEqualTo(JBUI.scale(320));
        assertThat(scrollPane.getPreferredSize().height)
                .isGreaterThanOrEqualTo(textArea.getPreferredScrollableViewportSize().height);
        assertThat(scrollPane.getMaximumSize()).isEqualTo(scrollPane.getPreferredSize());
        assertThat(scrollPane.getMinimumSize()).isEqualTo(scrollPane.getPreferredSize());
        assertFieldWithHint(scrollPane, hintContaining(panel, "Extra instructions appended"));
    }

    static void verifyProfileLayout() {
        PluginSettingsComponent component = component();
        JPanel panel = component.getPanel();
        JComboBox<PluginSettings.ReviewGuidanceProfile> profileCombo =
                comboLabeled(panel, "Profile:");
        Container profilePanel = profileCombo.getParent();

        assertThat(profilePanel.getLayout()).isInstanceOf(BoxLayout.class);
        assertThat(profilePanel.getComponents()).hasSize(2);
        assertThat(profilePanel.getComponent(0)).isSameAs(profileCombo);

        JPanel actionRow = (JPanel) profilePanel.getComponent(1);
        assertThat(
                        Arrays.stream(actionRow.getComponents())
                                .map(AbstractButton.class::cast)
                                .map(AbstractButton::getText))
                .containsExactly("Save as…", "Rename", "Delete");

        AbstractButton save = button(panel, "Save as…");
        AbstractButton rename = button(panel, "Rename");
        AbstractButton delete = button(panel, "Delete");
        assertThat(save.isEnabled()).isTrue();
        assertThat(rename.isEnabled()).isFalse();
        assertThat(delete.isEnabled()).isFalse();

        PluginSettings.ReviewGuidanceProfile profile =
                new PluginSettings.ReviewGuidanceProfile(
                        "quality", "Security, performance, and test coverage", "", "", "");
        component.setReviewGuidanceProfiles(List.of(profile));
        component.setActiveReviewGuidanceProfileId(profile.id);

        assertThat(rename.isEnabled()).isTrue();
        assertThat(delete.isEnabled()).isTrue();
        panel.setFocusCycleRoot(true);
        panel.setFocusTraversalPolicy(new ContainerOrderFocusTraversalPolicy());
        panel.addNotify();
        FocusTraversalPolicy focusTraversal = panel.getFocusTraversalPolicy();
        assertThat(focusTraversal.getComponentAfter(panel, profileCombo)).isSameAs(save);
        assertThat(focusTraversal.getComponentAfter(panel, save)).isSameAs(rename);
        assertThat(focusTraversal.getComponentAfter(panel, rename)).isSameAs(delete);

        Component renderedProfile =
                profileCombo
                        .getRenderer()
                        .getListCellRendererComponent(new JList<>(), profile, -1, false, false);
        assertThat(renderedProfile.getPreferredSize().width)
                .isLessThanOrEqualTo(profileCombo.getPreferredSize().width);
    }

    static void verifyProfileNameValidation() {
        List<String> addInitialValues = new ArrayList<>();
        List<String> addValidationMessages = new ArrayList<>();
        List<String> addResponses = List.of("   ", " Security ");
        AtomicInteger addResponse = new AtomicInteger();
        PluginSettingsComponent addComponent =
                component(
                        (parent, title, initialValue, validationMessage) -> {
                            addInitialValues.add(initialValue);
                            addValidationMessages.add(validationMessage);
                            return addResponses.get(addResponse.getAndIncrement());
                        });

        button(addComponent.getPanel(), "Save as…").doClick();

        assertThat(addInitialValues).containsExactly("", "   ");
        assertThat(addValidationMessages)
                .containsExactly(null, PluginSettingsComponent.PROFILE_NAME_REQUIRED);
        assertThat(addComponent.getReviewGuidanceProfiles())
                .extracting(profile -> profile.name)
                .containsExactly("Security");

        PluginSettingsComponent cancelComponent =
                component((parent, title, initialValue, validationMessage) -> null);
        button(cancelComponent.getPanel(), "Save as…").doClick();
        assertThat(cancelComponent.getReviewGuidanceProfiles()).isEmpty();

        PluginSettings.ReviewGuidanceProfile profile =
                new PluginSettings.ReviewGuidanceProfile(
                        "quality", "Quality", "security", "Check boundaries", "");
        List<String> renameInitialValues = new ArrayList<>();
        List<String> renameValidationMessages = new ArrayList<>();
        List<String> renameResponses = List.of("\t", "Deep quality");
        AtomicInteger renameResponse = new AtomicInteger();
        PluginSettingsComponent renameComponent =
                component(
                        (parent, title, initialValue, validationMessage) -> {
                            renameInitialValues.add(initialValue);
                            renameValidationMessages.add(validationMessage);
                            return renameResponses.get(renameResponse.getAndIncrement());
                        });
        renameComponent.setReviewGuidanceProfiles(List.of(profile));
        renameComponent.setActiveReviewGuidanceProfileId(profile.id);

        button(renameComponent.getPanel(), "Rename").doClick();

        assertThat(renameInitialValues).containsExactly("Quality", "\t");
        assertThat(renameValidationMessages)
                .containsExactly(null, PluginSettingsComponent.PROFILE_NAME_REQUIRED);
        assertThat(renameComponent.getReviewGuidanceProfiles())
                .extracting(candidate -> candidate.name)
                .containsExactly("Deep quality");
    }

    static void verifyIntellijAssistedToggle() {
        PluginSettingsComponent component = component();
        JPanel panel = component.getPanel();
        List<String> texts =
                descendants(panel).stream()
                        .filter(JLabel.class::isInstance)
                        .map(JLabel.class::cast)
                        .map(JLabel::getText)
                        .toList();

        assertThat(
                        List.of("Review validation", "Experimental", "Notifications").stream()
                                .map(section -> indexContaining(texts, section))
                                .toList())
                .isSorted();
        AbstractButton toggle = button(panel, "Enable IntelliJ-assisted review (experimental)");
        assertFieldWithHint(toggle, hintContaining(panel, "IntelliJ IDEA 262+"));
        assertThat(hintContaining(panel, "IntelliJ IDEA 262+").getText())
                .contains("semantic-review.json")
                .contains("Off by default")
                .contains(
                        "Turning this off also hides retained-worktree maintenance; turn it back"
                                + " on to remove retained worktrees.");

        assertThat(toggle.isSelected()).isFalse();
        assertThat(component.isExperimentalIntellijAssistedReview()).isFalse();
        component.setExperimentalIntellijAssistedReview(true);
        assertThat(toggle.isSelected()).isTrue();
        assertThat(component.isExperimentalIntellijAssistedReview()).isTrue();
        toggle.doClick();
        assertThat(component.isExperimentalIntellijAssistedReview()).isFalse();
    }
}
