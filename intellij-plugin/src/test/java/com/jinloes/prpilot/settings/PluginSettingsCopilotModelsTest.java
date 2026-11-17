package com.jinloes.prpilot.settings;

import static com.jinloes.prpilot.settings.PluginSettingsTestSupport.authenticated;
import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.review.CopilotModelDiscovery;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for Copilot model list caching, refresh status copy, and selector behavior. */
class PluginSettingsCopilotModelsTest {

    @Nested
    class CopilotModelRefresh {

        @Test
        void showsTheCachedListImmediatelyThenRefreshesOnOpen() throws Exception {
            UiProbeRunner.run(
                    PluginSettingsCopilotModelsTest.class, "verifyModelsCachedThenRefresh", "1.0");
        }

        @Test
        void withoutACacheShowsLoadingUntilTheProbeAnswers() throws Exception {
            UiProbeRunner.run(PluginSettingsCopilotModelsTest.class, "verifyModelsLoading", "1.0");
        }

        @Test
        void refreshButtonReprobesAndKeepsTheTypedModel() throws Exception {
            UiProbeRunner.run(
                    PluginSettingsCopilotModelsTest.class, "verifyModelsRefreshButton", "1.0");
        }

        @Test
        void failedRefreshKeepsTheCurrentListAndSaysWhy() throws Exception {
            UiProbeRunner.run(
                    PluginSettingsCopilotModelsTest.class, "verifyModelsFailedRefresh", "1.0");
        }

        @Test
        void refreshButtonIsDisabledWhileAProbeIsRunning() throws Exception {
            UiProbeRunner.run(
                    PluginSettingsCopilotModelsTest.class, "verifyModelsNoOverlap", "1.0");
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

    static void verifyModelsCachedThenRefresh() {
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

    static void verifyModelsLoading() {
        ModelHarness harness = ModelHarness.create(new FakeCatalog(null, account("m1")));

        assertThat(harness.component().getCopilotModelHintText())
                .contains(PluginSettingsComponent.MODELS_LOADING_HINT);
        harness.runBackground();
        assertThat(harness.component().getCopilotModelOptions()).contains("m1");
    }

    static void verifyModelsRefreshButton() {
        FakeCatalog catalog = new FakeCatalog(null, account("m1"));
        ModelHarness harness = ModelHarness.create(catalog);
        PluginSettingsComponent component = harness.component();
        harness.runBackground();
        component.setReviewModelCopilot("custom-model");
        component.setReviewSecondReviewerModel("m2");
        catalog.next = account("m1", "m2");

        component.getRefreshModelsButton().doClick();
        harness.runBackground();

        assertThat(catalog.refreshes).hasValue(2);
        assertThat(component.getCopilotModelOptions())
                .containsExactly("", "m1", "m2", "custom-model");
        assertThat(component.getReviewModelCopilot()).isEqualTo("custom-model");
        assertThat(component.getSecondReviewerModelOptions()).containsExactly("", "m1", "m2");
        assertThat(component.getReviewSecondReviewerModel()).isEqualTo("m2");
    }

    static void verifyModelsFailedRefresh() {
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

    static void verifyModelsNoOverlap() {
        FakeCatalog catalog = new FakeCatalog(null, account("m1"));
        ModelHarness harness = ModelHarness.create(catalog);

        harness.component().getRefreshModelsButton().doClick();
        harness.runBackground();

        assertThat(catalog.refreshes).hasValue(1);
        assertThat(harness.component().getRefreshModelsButton().isEnabled()).isTrue();
    }
}
