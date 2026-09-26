package com.jinloes.prpilot.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import com.jinloes.prpilot.model.ReviewProvider;
import com.jinloes.prpilot.review.CopilotModelDiscovery;
import com.jinloes.prpilot.services.PRNotificationService;
import com.jinloes.prpilot.sidecar.github.CheckAuthResult;
import java.awt.Component;
import java.awt.Container;
import java.awt.ContainerOrderFocusTraversalPolicy;
import java.awt.FocusTraversalPolicy;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
    class CopilotModelRefresh {

        @Test
        void showsTheCachedListImmediatelyThenRefreshesOnOpen() throws Exception {
            runUiProbe("models-cached-then-refresh", "1.0");
        }

        @Test
        void withoutACacheShowsLoadingUntilTheProbeAnswers() throws Exception {
            runUiProbe("models-loading", "1.0");
        }

        @Test
        void refreshButtonReprobesAndKeepsTheTypedModel() throws Exception {
            runUiProbe("models-refresh-button", "1.0");
        }

        @Test
        void failedRefreshKeepsTheCurrentListAndSaysWhy() throws Exception {
            runUiProbe("models-failed-refresh", "1.0");
        }

        @Test
        void refreshButtonIsDisabledWhileAProbeIsRunning() throws Exception {
            runUiProbe("models-no-overlap", "1.0");
        }
    }

    @Nested
    class CopilotModelStatus {

        @Test
        void accountResultCountsModels() {
            assertThat(PluginSettingsComponent.copilotModelStatus(account("a", "b", "c"), false))
                    .startsWith("3 models available to your Copilot account.");
        }

        @Test
        void helpFallbackWarnsTheListMayBeStale() {
            CopilotModelDiscovery.Result result =
                    new CopilotModelDiscovery.Result(
                            List.of("a"), CopilotModelDiscovery.Source.CLI_HELP, "timed out");

            assertThat(PluginSettingsComponent.copilotModelStatus(result, false))
                    .contains("(timed out)")
                    .contains("may miss newer models");
        }

        @Test
        void failureWithoutAPreviousListPointsAtSuggestions() {
            assertThat(
                            PluginSettingsComponent.copilotModelStatus(
                                    CopilotModelDiscovery.Result.none(""), false))
                    .isEqualTo(
                            "Couldn't load models. Showing suggestions; type any model ID to"
                                    + " override.");
        }

        @Test
        void failureReasonIsHtmlEscaped() {
            assertThat(
                            PluginSettingsComponent.copilotModelStatus(
                                    CopilotModelDiscovery.Result.none("<b>bad</b>"), true))
                    .contains("(&lt;b&gt;bad&lt;/b&gt;)")
                    .doesNotContain("<b>");
        }
    }

    private static CopilotModelDiscovery.Result account(String... models) {
        return new CopilotModelDiscovery.Result(
                List.of(models), CopilotModelDiscovery.Source.ACCOUNT, "");
    }

    /** Mimics the real cache: successful refreshes become the cached result. */
    private static final class FakeCatalog implements PluginSettingsComponent.CopilotModelCatalog {
        private CopilotModelDiscovery.Result cached;
        private CopilotModelDiscovery.Result next;
        private final AtomicInteger refreshes = new AtomicInteger();

        FakeCatalog(CopilotModelDiscovery.Result cached, CopilotModelDiscovery.Result next) {
            this.cached = cached;
            this.next = next;
        }

        @Override
        public CopilotModelDiscovery.Result cached() {
            return cached;
        }

        @Override
        public CopilotModelDiscovery.Result refresh() {
            refreshes.incrementAndGet();
            CopilotModelDiscovery.Result result = next;
            if (!result.models().isEmpty()) cached = result;
            return result;
        }
    }

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
        void resetLoadsThePersistedEnterpriseHostBeforeRefreshingExactlyOnce() {
            List<String> events = new ArrayList<>();

            PluginSettingsConfigurable.loadGithubBaseUrlAndRefresh(
                    "https://github.example.test",
                    url -> events.add("set:" + url),
                    () -> events.add("refresh"));

            assertThat(events).containsExactly("set:https://github.example.test", "refresh");
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
            runUiProbe("shared-width");
        }

        @Test
        void boundsTheCustomInstructionsViewportAndKeepsVerticalScrolling() throws Exception {
            runUiProbe("custom-instructions");
        }

        @Test
        void stacksProfileSelectionAboveActionsAndPreservesProfileState() throws Exception {
            runUiProbe("profile-layout");
        }

        @Test
        void usesTaskBasedSectionsLabelsAndAttachedHints() throws Exception {
            runUiProbe("sections-and-hints");
        }

        @Test
        void usesTheSharedCrossHostVocabularyAndSectionOrder() throws Exception {
            runUiProbe("shared-vocabulary");
        }

        @Test
        void keepsProfileNameCorrectionContextOpenUntilValidOrCancelled() throws Exception {
            runUiProbe("profile-validation");
        }

        @Test
        void offersTheExperimentalIntellijAssistedToggleOffByDefault() throws Exception {
            runUiProbe("intellij-assisted-toggle");
        }

        @Test
        void persistsTheExperimentalIntellijAssistedToggleThroughApplyAndReset() throws Exception {
            runUiProbe("intellij-assisted-configurable");
        }
    }

    public static final class UiProbe {
        public static void main(String[] args) {
            try {
                switch (args[0]) {
                    case "shared-width" -> verifySharedWidth();
                    case "custom-instructions" -> verifyCustomInstructions();
                    case "profile-layout" -> verifyProfileLayout();
                    case "sections-and-hints" -> verifySectionsAndHints();
                    case "shared-vocabulary" -> verifySharedVocabulary();
                    case "profile-validation" -> verifyProfileNameValidation();
                    case "intellij-assisted-toggle" -> verifyIntellijAssistedToggle();
                    case "intellij-assisted-configurable" -> verifyIntellijAssistedConfigurable();
                    case "models-cached-then-refresh" -> verifyModelsCachedThenRefresh();
                    case "models-loading" -> verifyModelsLoading();
                    case "models-refresh-button" -> verifyModelsRefreshButton();
                    case "models-failed-refresh" -> verifyModelsFailedRefresh();
                    case "models-no-overlap" -> verifyModelsNoOverlap();
                    default -> throw new IllegalArgumentException("Unknown probe: " + args[0]);
                }
            } catch (Throwable failure) {
                failure.printStackTrace();
                System.exit(1);
            }
            System.exit(0);
        }
    }

    private static void runUiProbe(String probe) throws Exception {
        for (String uiScale : List.of("1.0", "2.0")) {
            runUiProbe(probe, uiScale);
        }
    }

    private static void runUiProbe(String probe, String uiScale) throws Exception {
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
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(finished)
                .withFailMessage("UI probe timed out at %s scale:%n%s", uiScale, output)
                .isTrue();
        assertThat(process.exitValue())
                .withFailMessage("UI probe failed at %s scale:%n%s", uiScale, output)
                .isZero();
    }

    /** A settings component whose background work is queued until {@link #runBackground}. */
    private record ModelHarness(PluginSettingsComponent component, List<Runnable> background) {
        static ModelHarness create(FakeCatalog catalog) {
            List<Runnable> background = new ArrayList<>();
            PluginSettingsComponent component =
                    new PluginSettingsComponent(
                            ignored -> authenticated("octocat"),
                            background::add,
                            Runnable::run,
                            () -> null,
                            (parent, title, initial, validation) -> null,
                            catalog);
            return new ModelHarness(component, background);
        }

        void runBackground() {
            List<Runnable> tasks = new ArrayList<>(background);
            background.clear();
            tasks.forEach(Runnable::run);
        }
    }

    private static void verifyModelsCachedThenRefresh() {
        FakeCatalog catalog =
                new FakeCatalog(account("claude-opus-5"), account("claude-opus-5", "new-one"));
        ModelHarness harness = ModelHarness.create(catalog);
        PluginSettingsComponent component = harness.component();

        assertThat(component.getCopilotModelOptions()).containsExactly("", "claude-opus-5");
        assertThat(component.getRefreshModelsButton().isEnabled()).isFalse();
        assertThat(component.getRefreshModelsButton().getText()).isEqualTo("Refreshing…");

        harness.runBackground();

        assertThat(catalog.refreshes).hasValue(1);
        assertThat(component.getCopilotModelOptions())
                .containsExactly("", "claude-opus-5", "new-one");
        assertThat(component.getCopilotModelHintText())
                .contains("2 models available to your Copilot account");
        assertThat(component.getRefreshModelsButton().isEnabled()).isTrue();
        assertThat(component.getRefreshModelsButton().getText()).isEqualTo("Refresh");
    }

    private static void verifyModelsLoading() {
        ModelHarness harness = ModelHarness.create(new FakeCatalog(null, account("m1")));

        assertThat(harness.component().getCopilotModelHintText())
                .contains(PluginSettingsComponent.MODELS_LOADING_HINT);
        harness.runBackground();
        assertThat(harness.component().getCopilotModelOptions()).contains("m1");
    }

    private static void verifyModelsRefreshButton() {
        FakeCatalog catalog = new FakeCatalog(null, account("m1"));
        ModelHarness harness = ModelHarness.create(catalog);
        PluginSettingsComponent component = harness.component();
        harness.runBackground();
        component.setReviewModelCopilot("custom-model");
        catalog.next = account("m1", "m2");

        component.getRefreshModelsButton().doClick();
        harness.runBackground();

        assertThat(catalog.refreshes).hasValue(2);
        assertThat(component.getCopilotModelOptions())
                .containsExactly("", "m1", "m2", "custom-model");
        assertThat(component.getReviewModelCopilot()).isEqualTo("custom-model");
    }

    private static void verifyModelsFailedRefresh() {
        ModelHarness harness =
                ModelHarness.create(
                        new FakeCatalog(
                                account("m1"), CopilotModelDiscovery.Result.none("not signed in")));

        harness.runBackground();

        assertThat(harness.component().getCopilotModelOptions()).containsExactly("", "m1");
        assertThat(harness.component().getCopilotModelHintText())
                .contains("Couldn't refresh models (not signed in)")
                .contains("last loaded list");
    }

    private static void verifyModelsNoOverlap() {
        FakeCatalog catalog = new FakeCatalog(null, account("m1"));
        ModelHarness harness = ModelHarness.create(catalog);

        harness.component().getRefreshModelsButton().doClick();
        harness.runBackground();

        assertThat(catalog.refreshes).hasValue(1);
        assertThat(harness.component().getRefreshModelsButton().isEnabled()).isTrue();
    }

    private static void verifySharedWidth() {
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

    private static void verifyCustomInstructions() {
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

    private static void verifyProfileLayout() {
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

    private static void verifyProfileNameValidation() {
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

    private static void verifySectionsAndHints() {
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
                                "Review provider",
                                "Review guidance",
                                "Review validation",
                                "Notifications")
                        .stream()
                        .map(section -> indexContaining(texts, section))
                        .toList();
        assertThat(sectionOrder).isSorted();

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

        AbstractButton advanced = button(panel, "Show advanced Copilot options");
        assertFieldWithHint(advanced, hintContaining(panel, "Optional controls"));
        assertFieldWithHint(validation, validationHint);

        component.setReviewProvider(ReviewProvider.COPILOT);
        JComponent copilotModel = (JComponent) label(panel, "Model:").getLabelFor();
        assertFieldWithHint(
                (JComponent) copilotModel.getParent(),
                hintContaining(panel, PluginSettingsComponent.MODELS_LOADING_HINT));
        assertThat(copilotModel.getParent().getComponents()).contains(button(panel, "Refreshing…"));

        JComponent effort = (JComponent) label(panel, "Reasoning effort:").getLabelFor();
        assertFieldWithHint(effort, hintContaining(panel, "Higher effort"));
        AbstractButton inheritMcp =
                button(panel, "Allow Copilot to use MCP tools from your trusted Copilot config");
        assertFieldWithHint(
                inheritMcp,
                hintContaining(panel, "Applies while reviewing untrusted pull request content."));
    }

    private static void verifySharedVocabulary() {
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
                                        "Review provider",
                                        "Review guidance",
                                        "Review validation",
                                        "Advanced review options",
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
        button(panel, "Enable background PR notifications (experimental)");
        List<String> buttons =
                descendants(panel).stream()
                        .filter(AbstractButton.class::isInstance)
                        .map(AbstractButton.class::cast)
                        .map(AbstractButton::getText)
                        .toList();
        assertThat(buttons)
                .doesNotContain(
                        "Allow MCP tools for untrusted PR content",
                        "Notify when a new PR is opened on a starred repo",
                        "Check Status",
                        "Save current as…",
                        "Test");
    }

    private static void verifyIntellijAssistedToggle() {
        PluginSettingsComponent component = component();
        JPanel panel = component.getPanel();
        List<String> texts =
                descendants(panel).stream()
                        .filter(JLabel.class::isInstance)
                        .map(JLabel.class::cast)
                        .map(JLabel::getText)
                        .toList();

        assertThat(
                        List.of("Review validation", "Advanced review options", "Notifications")
                                .stream()
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

    /**
     * Drives the real Configurable Apply/Reset/isModified against in-memory settings. Runs in the
     * probe subprocess because it installs a global Application and a temporary user.home.
     */
    private static void verifyIntellijAssistedConfigurable() throws Exception {
        Path home = Files.createTempDirectory("pr-pilot-configurable-");
        System.setProperty("user.home", home.toString());
        PluginSettings settings = new PluginSettings();
        settings.setNotificationsEnabled(false);
        AtomicReference<PRNotificationService> notifications = new AtomicReference<>();
        PluginSettingsComponent component = component();
        ApplicationManager.setApplication(
                (Application)
                        Proxy.newProxyInstance(
                                Application.class.getClassLoader(),
                                new Class<?>[] {Application.class},
                                (proxy, method, args) -> {
                                    if (method.getDeclaringClass() == Object.class) {
                                        return switch (method.getName()) {
                                            case "hashCode" -> System.identityHashCode(proxy);
                                            case "equals" -> proxy == args[0];
                                            default -> "configurable-fixture";
                                        };
                                    }
                                    if (method.getName().equals("isUnitTestMode")) return true;
                                    if (method.getName().equals("getService")) {
                                        if (args[0] == PluginSettings.class) return settings;
                                        if (args[0] == PRNotificationService.class) {
                                            notifications.compareAndSet(
                                                    null, new PRNotificationService());
                                            return notifications.get();
                                        }
                                        return null;
                                    }
                                    throw new AssertionError(
                                            "Unexpected application call " + method);
                                }));
        PluginSettingsConfigurable configurable = new PluginSettingsConfigurable();
        Field field = PluginSettingsConfigurable.class.getDeclaredField("component");
        field.setAccessible(true);
        field.set(configurable, component);

        configurable.reset();
        assertThat(component.isExperimentalIntellijAssistedReview()).isFalse();
        assertThat(configurable.isModified()).isFalse();

        component.setExperimentalIntellijAssistedReview(true);
        assertThat(configurable.isModified()).isTrue();
        configurable.apply();
        assertThat(settings.isExperimentalIntellijAssistedReview()).isTrue();
        assertThat(configurable.isModified()).isFalse();

        component.setExperimentalIntellijAssistedReview(false);
        assertThat(configurable.isModified()).isTrue();
        configurable.reset();
        assertThat(component.isExperimentalIntellijAssistedReview()).isTrue();
        assertThat(configurable.isModified()).isFalse();

        component.setExperimentalIntellijAssistedReview(false);
        configurable.apply();
        assertThat(settings.isExperimentalIntellijAssistedReview()).isFalse();
        assertThat(configurable.isModified()).isFalse();
        assertThat(home.resolve(".pr-pilot")).doesNotExist();
    }

    private static PluginSettingsComponent component() {
        return new PluginSettingsComponent(
                ignored -> authenticated("octocat"), ignored -> {}, Runnable::run, () -> null);
    }

    private static PluginSettingsComponent component(
            PluginSettingsComponent.ProfileNamePrompt profileNamePrompt) {
        return new PluginSettingsComponent(
                ignored -> authenticated("octocat"),
                ignored -> {},
                Runnable::run,
                () -> null,
                profileNamePrompt);
    }

    @SuppressWarnings("unchecked")
    private static <T> JComboBox<T> comboLabeled(Component root, String labelText) {
        return (JComboBox<T>) label(root, labelText).getLabelFor();
    }

    private static JLabel label(Component root, String text) {
        List<JLabel> matches =
                descendants(root).stream()
                        .filter(JLabel.class::isInstance)
                        .map(JLabel.class::cast)
                        .filter(candidate -> text.equals(candidate.getText()))
                        .toList();
        assertThat(matches).hasSize(1);
        return matches.get(0);
    }

    private static JLabel hintContaining(Component root, String text) {
        List<JLabel> matches =
                descendants(root).stream()
                        .filter(JLabel.class::isInstance)
                        .map(JLabel.class::cast)
                        .filter(candidate -> candidate.getText().contains(text))
                        .toList();
        assertThat(matches).hasSize(1);
        return matches.get(0);
    }

    private static AbstractButton button(Component root, String text) {
        List<AbstractButton> matches =
                descendants(root).stream()
                        .filter(AbstractButton.class::isInstance)
                        .map(AbstractButton.class::cast)
                        .filter(candidate -> text.equals(candidate.getText()))
                        .toList();
        assertThat(matches).hasSize(1);
        return matches.get(0);
    }

    private static void assertContentWidth(JComponent component) {
        assertThat(component.getPreferredSize().width).isEqualTo(JBUI.scale(320));
        assertThat(component.getMinimumSize().width).isEqualTo(JBUI.scale(320));
        assertThat(component.getMaximumSize().width).isEqualTo(JBUI.scale(320));
    }

    private static void assertFieldWithHint(Component control, JLabel hint) {
        assertThat(control.getParent()).isSameAs(hint.getParent());
        assertThat(control.getParent()).isInstanceOf(JPanel.class);
        JPanel field = (JPanel) control.getParent();
        assertThat(field.getLayout()).isInstanceOf(BoxLayout.class);
        assertThat(field.getComponents()).containsSubsequence(control, hint);
        assertThat(field.isFocusable()).isFalse();
        assertThat(hint.isFocusable()).isFalse();
    }

    private static int indexContaining(List<String> texts, String expected) {
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
