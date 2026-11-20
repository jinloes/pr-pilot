package com.jinloes.prpilot.settings;

import static com.jinloes.prpilot.settings.PluginSettingsTestSupport.authenticated;
import static com.jinloes.prpilot.settings.PluginSettingsTestSupport.button;
import static com.jinloes.prpilot.settings.PluginSettingsTestSupport.descendants;
import static org.assertj.core.api.Assertions.assertThat;

import com.intellij.util.ui.JBUI;
import com.jinloes.prpilot.review.CopilotModelDiscovery;
import com.jinloes.prpilot.sidecar.github.CheckAuthResult;
import com.jinloes.prpilot.ui.PRToolWindowFactory;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PluginSettingsNotificationsTest {

    @Nested
    class StatusLabelsAndRetry {

        @Test
        void escapesHtmlAndUsesPlatformThemeForegroundsInConnectionAndPollStatusLabels()
                throws Exception {
            UiProbeRunner.runAtDefaultScales(
                    PluginSettingsNotificationsTest.class, "verifyHtmlEscapingAndThemeColors");
        }

        @Test
        void guardsRetryUntilNotificationSettingsAreAppliedAndRefreshesAfterPoll()
                throws Exception {
            UiProbeRunner.runAtDefaultScales(
                    PluginSettingsNotificationsTest.class, "verifyRetryNotificationPoll");
        }

        @Test
        void handlesUnnormalizedPersistedBaseUrlAndClassifiesIntelliJSetupFailures() {
            assertThat(
                            PluginSettingsComponent.isNotificationConfigAppliedAndEnabled(
                                    true,
                                    true,
                                    true,
                                    true,
                                    false,
                                    false,
                                    "https://ghe.example.com:8443",
                                    "https://ghe.example.com"))
                    .isFalse();
            assertThat(
                            PluginSettingsComponent.isNotificationConfigAppliedAndEnabled(
                                    true,
                                    true,
                                    true,
                                    true,
                                    false,
                                    false,
                                    "https://ghe.example.com/",
                                    "https://GHE.EXAMPLE.COM"))
                    .isTrue();

            PRToolWindowFactory.SetupFailure unauthGhe =
                    PRToolWindowFactory.classifySetupFailure(
                            new IOException(
                                    "Run 'gh auth login' in a terminal for this GitHub host."),
                            "https://github.example.com/");
            assertThat(unauthGhe.reason()).isEqualTo("gh_not_authenticated");
            assertThat(unauthGhe.ghAuthCommand())
                    .isEqualTo("gh auth login --hostname github.example.com");
            assertThat(unauthGhe.detail()).contains("gh auth login --hostname github.example.com");

            PRToolWindowFactory.SetupFailure missingGh =
                    PRToolWindowFactory.classifySetupFailure(
                            new IOException("GitHub CLI is not installed."),
                            "https://github.example.com");
            assertThat(missingGh.reason()).isEqualTo("gh_not_installed");

            PRToolWindowFactory.SetupFailure networkError =
                    PRToolWindowFactory.classifySetupFailure(
                            new IOException("Unable to reach GitHub. Check your connection."),
                            "https://github.com");
            assertThat(networkError.reason()).isEqualTo("load_failed");

            String overlongHost = ("a".repeat(63) + ".").repeat(8) + "example.com";
            PRToolWindowFactory.SetupFailure overlongHostFailure =
                    PRToolWindowFactory.classifySetupFailure(
                            new IOException(
                                    "Run 'gh auth login' in a terminal for this GitHub host."),
                            "https://" + overlongHost);
            assertThat(overlongHostFailure.reason()).isEqualTo("gh_not_authenticated");
            assertThat(overlongHostFailure.ghAuthCommand()).isEqualTo("gh auth login");
        }
    }

    static void verifyHtmlEscapingAndThemeColors() {
        AtomicReference<CheckAuthResult> authResult =
                new AtomicReference<>(
                        new CheckAuthResult(
                                "unauthenticated", null, "Missing <token> & query=true"));
        AtomicReference<String> pollStatus =
                new AtomicReference<>("Last poll: 5s ago — Error: <bad>&status");
        PluginSettingsComponent component =
                new PluginSettingsComponent(
                        ignored -> authResult.get(),
                        Runnable::run,
                        Runnable::run,
                        pollStatus::get,
                        (parent, title, initialValue, validationMessage) -> null,
                        new PluginSettingsComponent.CopilotModelCatalog() {
                            @Override
                            public CopilotModelDiscovery.Result cached() {
                                return CopilotModelDiscovery.Result.none("offline");
                            }

                            @Override
                            public CopilotModelDiscovery.Result refresh() {
                                return cached();
                            }
                        },
                        () -> false,
                        () -> {});
        JPanel panel = component.getPanel();

        button(panel, "Check connection").doClick();
        JLabel connectionError =
                descendants(panel).stream()
                        .filter(JLabel.class::isInstance)
                        .map(JLabel.class::cast)
                        .filter(label -> label.getText().contains("Missing &lt;token&gt;"))
                        .findFirst()
                        .orElseThrow();
        assertThat(connectionError.getText())
                .contains("Missing &lt;token&gt; &amp; query=true")
                .doesNotContain("<font");
        assertThat(connectionError.getForeground())
                .isEqualTo(JBUI.CurrentTheme.Label.errorForeground());

        panel.setSize(new Dimension(JBUI.scale(560), 1200));
        layoutRecursively(panel);
        Container statusParent = connectionError.getParent();
        Rectangle visibleBounds =
                new Rectangle(0, 0, statusParent.getWidth(), statusParent.getHeight())
                        .intersection(connectionError.getBounds());
        assertThat(visibleBounds.isEmpty()).isFalse();
        assertThat(visibleBounds.height).isGreaterThan(0);

        JLabel pollError =
                descendants(panel).stream()
                        .filter(JLabel.class::isInstance)
                        .map(JLabel.class::cast)
                        .filter(label -> label.getText().contains("&lt;bad&gt;&amp;status"))
                        .findFirst()
                        .orElseThrow();
        assertThat(pollError.getText()).doesNotContain("<font");
        assertThat(pollError.getForeground()).isEqualTo(JBUI.CurrentTheme.Label.errorForeground());

        authResult.set(authenticated("octocat"));
        button(panel, "Check connection").doClick();
        JLabel signedIn =
                descendants(panel).stream()
                        .filter(JLabel.class::isInstance)
                        .map(JLabel.class::cast)
                        .filter(label -> "Signed in as @octocat".equals(label.getText()))
                        .findFirst()
                        .orElseThrow();
        assertThat(signedIn.getForeground()).isEqualTo(JBUI.CurrentTheme.Label.foreground());
    }

    static void verifyRetryNotificationPoll() {
        AtomicBoolean applied = new AtomicBoolean(false);
        AtomicInteger retries = new AtomicInteger();
        AtomicReference<String> pollStatus = new AtomicReference<>(null);
        PluginSettingsComponent component =
                new PluginSettingsComponent(
                        ignored -> authenticated("octocat"),
                        Runnable::run,
                        Runnable::run,
                        pollStatus::get,
                        (parent, title, initialValue, validationMessage) -> null,
                        new PluginSettingsComponent.CopilotModelCatalog() {
                            @Override
                            public CopilotModelDiscovery.Result cached() {
                                return CopilotModelDiscovery.Result.none("offline");
                            }

                            @Override
                            public CopilotModelDiscovery.Result refresh() {
                                return cached();
                            }
                        },
                        applied::get,
                        () -> {
                            retries.incrementAndGet();
                            pollStatus.set("Last polled: 0s ago");
                        });
        JPanel panel = component.getPanel();
        component.setNotificationsEnabled(true);

        button(panel, "Retry notification poll").doClick();
        assertThat(retries).hasValue(0);
        JLabel applyFirst =
                descendants(panel).stream()
                        .filter(JLabel.class::isInstance)
                        .map(JLabel.class::cast)
                        .filter(
                                label ->
                                        label.getText()
                                                .contains(
                                                        "Apply notification settings first to run a poll."))
                        .findFirst()
                        .orElseThrow();
        assertThat(applyFirst.getForeground()).isEqualTo(JBUI.CurrentTheme.ContextHelp.FOREGROUND);

        applied.set(true);
        button(panel, "Retry notification poll").doClick();
        assertThat(retries).hasValue(1);
        assertThat(
                        descendants(panel).stream()
                                .filter(JLabel.class::isInstance)
                                .map(JLabel.class::cast)
                                .anyMatch(label -> label.getText().contains("Last polled: 0s ago")))
                .isTrue();
    }

    private static void layoutRecursively(Component component) {
        if (component instanceof Container container) {
            container.doLayout();
            for (Component child : container.getComponents()) {
                layoutRecursively(child);
            }
        }
    }
}
