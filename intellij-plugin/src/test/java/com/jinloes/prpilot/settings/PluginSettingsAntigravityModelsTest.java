package com.jinloes.prpilot.settings;

import static com.jinloes.prpilot.settings.PluginSettingsTestSupport.authenticated;
import static com.jinloes.prpilot.settings.PluginSettingsTestSupport.button;
import static com.jinloes.prpilot.settings.PluginSettingsTestSupport.label;
import static com.jinloes.prpilot.settings.PluginSettingsTestSupport.visibleWithin;
import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.ReviewProvider;
import com.jinloes.prpilot.review.AntigravityModelDiscovery;
import com.jinloes.prpilot.review.CopilotModelDiscovery;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for Antigravity settings persistence, lazy model discovery, and UI card visibility. */
class PluginSettingsAntigravityModelsTest {

    @Nested
    class PersistedSettings {

        @Test
        void reviewModelAntigravityDefaultsToEmptyAndTrimsValues() {
            PluginSettings settings = new PluginSettings();

            assertThat(settings.getReviewModelAntigravity()).isEmpty();
            settings.setReviewModelAntigravity("  gemini-3.8-flash-high  ");
            assertThat(settings.getReviewModelAntigravity()).isEqualTo("gemini-3.8-flash-high");
            settings.setReviewModelAntigravity(null);
            assertThat(settings.getReviewModelAntigravity()).isEmpty();
        }

        @Test
        void activeReviewModelSelectsAntigravityModelWhenProviderIsAntigravity() {
            PluginSettings settings = new PluginSettings();
            settings.setReviewModel("claude-sonnet-4-6");
            settings.setReviewModelCopilot("gpt-5.4");
            settings.setReviewModelAntigravity("claude-opus-4-6-thinking");

            settings.setReviewProvider(ReviewProvider.ANTIGRAVITY);
            assertThat(settings.getActiveReviewModel()).isEqualTo("claude-opus-4-6-thinking");

            PluginSettings.State loaded = new PluginSettings.State();
            loaded.reviewProvider = "antigravity";
            loaded.reviewModelAntigravity = "gemini-3.8-flash-medium";
            settings.loadState(loaded);
            assertThat(settings.getReviewProvider()).isEqualTo(ReviewProvider.ANTIGRAVITY);
            assertThat(settings.getActiveReviewModel()).isEqualTo("gemini-3.8-flash-medium");
        }
    }

    @Nested
    class AntigravityModelRefresh {

        @Test
        void refreshesLazilyWhenSwitchedToAntigravityAndHidesCopilotOnlyControls()
                throws Exception {
            UiProbeRunner.run(
                    PluginSettingsAntigravityModelsTest.class,
                    "verifyLazyRefreshAndVisibility",
                    "1.0");
        }

        @Test
        void refreshButtonReprobesAndPreservesTypedSlug() throws Exception {
            UiProbeRunner.run(
                    PluginSettingsAntigravityModelsTest.class, "verifyManualRefresh", "1.0");
        }
    }

    @Nested
    class AntigravityModelStatus {

        @Test
        void cliResultCountsModelsAndMentionsDefault() {
            assertThat(
                            PluginSettingsComponent.antigravityModelStatus(
                                    cli("gemini-3.8-flash-high", "claude-sonnet-4-6"), false))
                    .startsWith("2 models available from Antigravity CLI.")
                    .contains("Leave blank for CLI default");
        }

        @Test
        void failureWithoutCacheMentionsSuggestionsAndDefault() {
            assertThat(
                            PluginSettingsComponent.antigravityModelStatus(
                                    AntigravityModelDiscovery.Result.none(""), false))
                    .contains("Couldn't load models")
                    .contains("Showing suggestions");
        }

        @Test
        void failureReasonIsHtmlEscaped() {
            assertThat(
                            PluginSettingsComponent.antigravityModelStatus(
                                    AntigravityModelDiscovery.Result.none("<script>x</script>"),
                                    true))
                    .contains("(&lt;script&gt;x&lt;/script&gt;)")
                    .doesNotContain("<script>");
        }
    }

    private static AntigravityModelDiscovery.Result cli(String... models) {
        return new AntigravityModelDiscovery.Result(
                List.of(models), AntigravityModelDiscovery.Source.ACCOUNT, "");
    }

    private static final class FakeAntigravityCatalog
            implements PluginSettingsComponent.AntigravityModelCatalog {
        private AntigravityModelDiscovery.Result cached;
        private AntigravityModelDiscovery.Result next;
        private final AtomicInteger refreshes = new AtomicInteger();

        FakeAntigravityCatalog(
                AntigravityModelDiscovery.Result cached, AntigravityModelDiscovery.Result next) {
            this.cached = cached;
            this.next = next;
        }

        @Override
        public AntigravityModelDiscovery.Result cached() {
            return cached;
        }

        @Override
        public AntigravityModelDiscovery.Result refresh() {
            refreshes.incrementAndGet();
            AntigravityModelDiscovery.Result result = next;
            if (!result.models().isEmpty()) cached = result;
            return result;
        }
    }

    private static final class NoopCopilotCatalog
            implements PluginSettingsComponent.CopilotModelCatalog {
        @Override
        public CopilotModelDiscovery.Result cached() {
            return CopilotModelDiscovery.Result.none("");
        }

        @Override
        public CopilotModelDiscovery.Result refresh() {
            return CopilotModelDiscovery.Result.none("");
        }
    }

    private record Harness(PluginSettingsComponent component, List<Runnable> background) {
        static Harness create(FakeAntigravityCatalog catalog) {
            List<Runnable> background = new ArrayList<>();
            PluginSettingsComponent component =
                    new PluginSettingsComponent(
                            ignored -> authenticated("octocat"),
                            background::add,
                            Runnable::run,
                            () -> null,
                            (parent, title, initial, validation) -> null,
                            new NoopCopilotCatalog(),
                            catalog,
                            null,
                            null);
            return new Harness(component, background);
        }

        void runBackground() {
            List<Runnable> tasks = new ArrayList<>(background);
            background.clear();
            tasks.forEach(Runnable::run);
        }
    }

    static void verifyLazyRefreshAndVisibility() {
        FakeAntigravityCatalog catalog =
                new FakeAntigravityCatalog(
                        cli("gemini-3.8-flash-high"),
                        cli("gemini-3.8-flash-high", "claude-sonnet-4-6"));
        Harness harness = Harness.create(catalog);
        PluginSettingsComponent component = harness.component();
        JPanel panel = component.getPanel();

        harness.runBackground();
        assertThat(catalog.refreshes).hasValue(0);
        assertThat(component.getAntigravityModelOptions())
                .containsExactly("", "gemini-3.8-flash-high");

        component.setReviewProvider(ReviewProvider.ANTIGRAVITY);
        JLabel effortLabel = label(panel, "Reasoning effort:");
        JLabel secondEffortLabel = label(panel, "Second reviewer effort:");
        AbstractButton inheritMcpBox =
                button(panel, "Allow Copilot to use MCP tools from your trusted Copilot config");
        assertThat(visibleWithin(effortLabel, panel)).isFalse();
        assertThat(visibleWithin(secondEffortLabel, panel)).isFalse();
        assertThat(visibleWithin(inheritMcpBox, panel)).isFalse();
        assertThat(component.getRefreshAntigravityModelsButton().isEnabled()).isFalse();

        harness.runBackground();
        assertThat(catalog.refreshes).hasValue(1);
        assertThat(component.getAntigravityModelOptions())
                .containsExactly("", "gemini-3.8-flash-high", "claude-sonnet-4-6");
        assertThat(component.getSecondReviewerAntigravityModelOptions())
                .containsExactly("", "gemini-3.8-flash-high", "claude-sonnet-4-6");
        assertThat(component.getAntigravityModelHintText())
                .contains("2 models available from Antigravity CLI.");
        assertThat(component.getRefreshAntigravityModelsButton().isEnabled()).isTrue();
    }

    static void verifyManualRefresh() {
        FakeAntigravityCatalog catalog =
                new FakeAntigravityCatalog(null, cli("gemini-3.8-flash-high"));
        Harness harness = Harness.create(catalog);
        PluginSettingsComponent component = harness.component();

        component.setReviewProvider(ReviewProvider.ANTIGRAVITY);
        assertThat(component.getAntigravityModelHintText())
                .contains(PluginSettingsComponent.ANTIGRAVITY_MODELS_LOADING_HINT);
        harness.runBackground();

        component.setReviewModelAntigravity("custom-agy-model");
        catalog.next = cli("gemini-3.8-flash-high", "claude-opus-4-6-thinking");
        component.getRefreshAntigravityModelsButton().doClick();
        harness.runBackground();

        assertThat(catalog.refreshes).hasValue(2);
        assertThat(component.getAntigravityModelOptions())
                .containsExactly(
                        "",
                        "gemini-3.8-flash-high",
                        "claude-opus-4-6-thinking",
                        "custom-agy-model");
        assertThat(component.getReviewModelAntigravity()).isEqualTo("custom-agy-model");
    }
}
