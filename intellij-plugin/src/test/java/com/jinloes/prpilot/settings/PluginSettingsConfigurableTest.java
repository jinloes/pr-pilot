package com.jinloes.prpilot.settings;

import static com.jinloes.prpilot.settings.PluginSettingsTestSupport.component;
import static org.assertj.core.api.Assertions.assertThat;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.jinloes.prpilot.model.ReviewProvider;
import com.jinloes.prpilot.services.PRNotificationService;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for {@link PluginSettingsConfigurable} reset/apply lifecycle and base-URL loading. */
class PluginSettingsConfigurableTest {

    @Nested
    class ResetAndApply {

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
        void persistsTheExperimentalIntellijAssistedToggleThroughApplyAndReset() throws Exception {
            UiProbeRunner.runAtDefaultScales(
                    PluginSettingsConfigurableTest.class, "verifyIntellijAssistedConfigurable");
        }
    }

    /**
     * Drives the real Configurable Apply/Reset/isModified against in-memory settings. Runs in the
     * probe subprocess and restores global {@code user.home} and {@code Application} afterward.
     */
    static void verifyIntellijAssistedConfigurable() throws Exception {
        String previousHome = System.getProperty("user.home");
        Field appField = ApplicationManager.class.getDeclaredField("ourApplication");
        appField.setAccessible(true);
        Object previousApp = appField.get(null);
        Path home = Files.createTempDirectory("pr-pilot-configurable-");
        try {
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

            assertThat(component.isReviewBuiltInRules()).isTrue();
            component.setReviewBuiltInRules(false);
            assertThat(configurable.isModified()).isTrue();
            configurable.apply();
            assertThat(settings.isReviewBuiltInRules()).isFalse();
            assertThat(configurable.isModified()).isFalse();

            assertThat(component.isReviewSupervisorEnabled()).isTrue();
            component.setReviewSecondReviewerModel("gpt-5.4");
            component.setReviewSecondReviewerModelClaude("claude-haiku-4-5-20251001");
            component.setReviewSecondReviewerModelAntigravity("claude-sonnet-4-6");
            assertThat(configurable.isModified()).isTrue();
            configurable.apply();
            assertThat(settings.getReviewSecondReviewerModel()).isEqualTo("gpt-5.4");
            assertThat(settings.getReviewSecondReviewerModelClaude())
                    .isEqualTo("claude-haiku-4-5-20251001");
            assertThat(settings.getReviewSecondReviewerModelAntigravity())
                    .isEqualTo("claude-sonnet-4-6");
            assertThat(configurable.isModified()).isFalse();
            component.setReviewSecondReviewerModel("");
            component.setReviewSecondReviewerModelClaude("");
            component.setReviewSecondReviewerModelAntigravity("");
            configurable.reset();
            assertThat(component.getReviewSecondReviewerModel()).isEqualTo("gpt-5.4");
            assertThat(component.getReviewSecondReviewerModelClaude())
                    .isEqualTo("claude-haiku-4-5-20251001");
            assertThat(component.getReviewSecondReviewerModelAntigravity())
                    .isEqualTo("claude-sonnet-4-6");

            component.setReviewGuidanceGlobs(".review/ai-agent/*.md\ndocs/rules/*.md");
            assertThat(configurable.isModified()).isTrue();
            configurable.apply();
            assertThat(settings.getReviewGuidanceGlobs())
                    .containsExactly(".review/ai-agent/*.md", "docs/rules/*.md");
            assertThat(configurable.isModified()).isFalse();
            component.setReviewGuidanceGlobs("");
            assertThat(configurable.isModified()).isTrue();
            configurable.reset();
            assertThat(component.getReviewGuidanceGlobs())
                    .isEqualTo(".review/ai-agent/*.md\ndocs/rules/*.md");
            assertThat(configurable.isModified()).isFalse();

            component.setReviewProvider(ReviewProvider.ANTIGRAVITY);
            component.setReviewModelAntigravity("gemini-3.8-flash-high");
            assertThat(configurable.isModified()).isTrue();
            configurable.apply();
            assertThat(settings.getReviewProvider()).isEqualTo(ReviewProvider.ANTIGRAVITY);
            assertThat(settings.getReviewModelAntigravity()).isEqualTo("gemini-3.8-flash-high");
            assertThat(configurable.isModified()).isFalse();
            component.setReviewModelAntigravity("");
            assertThat(configurable.isModified()).isTrue();
            configurable.reset();
            assertThat(component.getReviewModelAntigravity()).isEqualTo("gemini-3.8-flash-high");
            assertThat(configurable.isModified()).isFalse();

            PluginSettings.ReviewGuidanceProfile guidanceProfile =
                    new PluginSettings.ReviewGuidanceProfile(
                            "sec", "Security", "auth", "Check tokens", ".security/*.md");
            component.setReviewGuidanceProfiles(List.of(guidanceProfile));
            component.setActiveReviewGuidanceProfileId("sec");
            assertThat(configurable.isModified()).isTrue();
            configurable.apply();
            assertThat(settings.getReviewGuidanceProfiles())
                    .singleElement()
                    .satisfies(p -> assertThat(p.guidanceGlobs).isEqualTo(".security/*.md"));
            assertThat(home.resolve(".pr-pilot")).doesNotExist();
        } finally {
            appField.set(null, previousApp);
            if (previousHome != null) {
                System.setProperty("user.home", previousHome);
            }
            FileUtils.deleteDirectory(home.toFile());
        }
    }
}
