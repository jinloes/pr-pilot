package com.jinloes.prpilot.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.ReviewProvider;
import com.jinloes.prpilot.settings.PluginSettings;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.helpers.MessageFormatter;

class IntellijClaudeServiceTest {

    @Nested
    class SnapshotReviewRuntimeSettings {

        @Test
        void remainsStableWhenSettingsChange() {
            PluginSettings settings = new PluginSettings();
            PluginSettings.State initial = new PluginSettings.State();
            initial.reviewProvider = "copilot";
            initial.reviewModelCopilot = "generation-model";
            initial.reviewSupervisorEnabled = true;
            settings.loadState(initial);

            IntellijClaudeService.ReviewRuntimeSettings snapshot =
                    IntellijClaudeService.snapshotReviewRuntimeSettings(settings);

            settings.setReviewProvider(ReviewProvider.CLAUDE);
            settings.setReviewModel("later-model");
            assertThat(snapshot.provider()).isEqualTo(ReviewProvider.COPILOT);
            assertThat(snapshot.model()).isEqualTo("generation-model");
            assertThat(snapshot.supervisorEnabled()).isTrue();
        }

        @Test
        void identityChangesWithTheSecondReviewerModel() {
            PluginSettings settings = new PluginSettings();
            Object before =
                    IntellijClaudeService.snapshotReviewRuntimeSettings(settings).identity();

            settings.setReviewSecondReviewerModel("gpt-5.4");
            IntellijClaudeService.ReviewRuntimeSettings after =
                    IntellijClaudeService.snapshotReviewRuntimeSettings(settings);

            assertThat(after.secondReviewerModel()).isEqualTo("gpt-5.4");
            assertThat(after.identity()).isNotEqualTo(before);
        }

        @Test
        void secondReviewerEffortDefaultsToMediumForEitherProvider() {
            PluginSettings settings = new PluginSettings();
            settings.setReviewProvider(ReviewProvider.CLAUDE);
            assertThat(
                            IntellijClaudeService.snapshotReviewRuntimeSettings(settings)
                                    .secondReviewerEffort())
                    .isEqualTo(PluginSettings.DEFAULT_SECOND_REVIEWER_EFFORT);

            PluginSettings.State copilot = new PluginSettings.State();
            copilot.reviewProvider = "copilot";
            copilot.reviewEffort = "xhigh";
            settings.loadState(copilot);
            assertThat(
                            IntellijClaudeService.snapshotReviewRuntimeSettings(settings)
                                    .secondReviewerEffort())
                    .isEqualTo("medium");
        }

        @Test
        void aConfiguredSecondReviewerEffortIsUsedAndChangesTheIdentity() {
            PluginSettings settings = new PluginSettings();
            Object before =
                    IntellijClaudeService.snapshotReviewRuntimeSettings(settings).identity();

            settings.setReviewSecondReviewerEffort("low");
            IntellijClaudeService.ReviewRuntimeSettings after =
                    IntellijClaudeService.snapshotReviewRuntimeSettings(settings);

            assertThat(after.secondReviewerEffort()).isEqualTo("low");
            assertThat(after.identity()).isNotEqualTo(before);
        }

        @Test
        void aStoredBlankSecondReviewerEffortFallsBackToMedium() {
            PluginSettings.State stored = new PluginSettings.State();
            stored.reviewSecondReviewerEffort = null;
            PluginSettings settings = new PluginSettings();
            settings.loadState(stored);

            assertThat(
                            IntellijClaudeService.snapshotReviewRuntimeSettings(settings)
                                    .secondReviewerEffort())
                    .isEqualTo("medium");
        }
    }

    @Nested
    class ResolveReviewInheritMcp {

        @Test
        void staysDisabledByDefault() {
            assertThat(IntellijClaudeService.resolveReviewInheritMcp(false, false)).isFalse();
        }

        @Test
        void keepsExplicitInheritanceEnabled() {
            assertThat(IntellijClaudeService.resolveReviewInheritMcp(true, false)).isTrue();
        }

        @Test
        void enablesMcpForReviewWhenOptInIsSet() {
            assertThat(IntellijClaudeService.resolveReviewInheritMcp(false, true)).isTrue();
        }
    }

    @Nested
    class FriendlyMessage {

        @Test
        void claudeBinaryMissingMentionsClaudeCode() {
            String msg =
                    IntellijClaudeService.friendlyMessage(
                            ReviewProvider.CLAUDE,
                            new IOException("Cannot run program \"claude\": error=2, No such file"),
                            "generate review");
            assertThat(msg).contains("'claude'").contains("Claude Code");
        }

        @Test
        void copilotBinaryMissingMentionsCopilot() {
            String msg =
                    IntellijClaudeService.friendlyMessage(
                            ReviewProvider.COPILOT,
                            new IOException("Cannot run program \"copilot\": error=2"),
                            "generate review");
            assertThat(msg).contains("'copilot'").contains("GitHub Copilot");
        }

        @Test
        void blankMessageShowsExceptionClassAndIdeaLogHint() {
            String msg =
                    IntellijClaudeService.friendlyMessage(
                            ReviewProvider.CLAUDE, new IOException(""), "generate review");
            assertThat(msg).isEqualTo("Couldn't generate review (IOException). See idea.log.");
        }

        @Test
        void nullMessageShowsExceptionClassAndIdeaLogHint() {
            String msg =
                    IntellijClaudeService.friendlyMessage(
                            ReviewProvider.COPILOT, new NullPointerException(), "generate review");
            assertThat(msg)
                    .isEqualTo("Couldn't generate review (NullPointerException). See idea.log.");
        }

        @Test
        void parseFailuresAreMappedToActionableMessage() {
            String msg =
                    IntellijClaudeService.friendlyMessage(
                            ReviewProvider.COPILOT,
                            new IOException("Failed to parse review JSON: unexpected token"),
                            "generate review");
            assertThat(msg).contains("invalid review format").contains("Retry");
        }

        @Test
        void chatFailureUsesChatOperation() {
            String msg =
                    IntellijClaudeService.friendlyMessage(
                            ReviewProvider.CLAUDE,
                            new IOException("opaque failure"),
                            "answer chat question");

            assertThat(msg).isEqualTo("Couldn't answer chat question. Please retry.");
        }
    }

    @Nested
    class RunWithErrorHandling {

        @Test
        void succeedsWithoutLoggingWarningsOrReportingErrors() {
            List<LoggedWarn> warnings = new ArrayList<>();
            AtomicBoolean ran = new AtomicBoolean();
            AtomicReference<String> error = new AtomicReference<>();

            IntellijClaudeService.runWithErrorHandling(
                    ReviewProvider.COPILOT,
                    "generate review",
                    "Review interrupted.",
                    error::set,
                    Runnable::run,
                    capturingLogger(warnings),
                    () -> ran.set(true));

            assertThat(ran.get()).isTrue();
            assertThat(error.get()).isNull();
            assertThat(warnings).isEmpty();
        }

        @Test
        void logsWarnWithProviderOperationAndStackTraceWhileRedactingSecrets() {
            List<LoggedWarn> warnings = new ArrayList<>();
            AtomicReference<String> error = new AtomicReference<>();
            String token = "ghp_" + "a".repeat(36);
            String bearer = "Bearer " + "b".repeat(24);
            IllegalStateException cause = new IllegalStateException("root cause " + token);
            IOException failure = new IOException("upstream failed with " + bearer, cause);

            IntellijClaudeService.runWithErrorHandling(
                    ReviewProvider.COPILOT,
                    "generate review",
                    "Review interrupted.",
                    error::set,
                    Runnable::run,
                    capturingLogger(warnings),
                    () -> {
                        throw failure;
                    });

            assertThat(error.get()).isEqualTo("Couldn't generate review. Please retry.");
            assertThat(warnings).hasSize(1);
            LoggedWarn warn = warnings.get(0);
            assertThat(warn.message()).contains("copilot").contains("generate review");
            assertThat(warn.throwable()).isNotNull();
            String renderedStack = stackTraceOf(warn.throwable());
            assertThat(renderedStack)
                    .contains("java.io.IOException: upstream failed with Bearer <redacted>")
                    .contains("Caused by: java.lang.IllegalStateException: root cause <redacted>")
                    .contains(IntellijClaudeServiceTest.class.getName())
                    .doesNotContain(token)
                    .doesNotContain("b".repeat(24));
        }

        @Test
        void logsInterruptedExceptionAtWarnRestoresInterruptFlagAndRedactsSecrets() {
            List<LoggedWarn> warnings = new ArrayList<>();
            AtomicReference<String> error = new AtomicReference<>();
            String secret = "github_pat_" + "c".repeat(40);
            try {
                IntellijClaudeService.runWithErrorHandling(
                        ReviewProvider.CLAUDE,
                        "answer chat question",
                        "Chat interrupted.",
                        error::set,
                        Runnable::run,
                        capturingLogger(warnings),
                        () -> {
                            throw new InterruptedException("cancelled with " + secret);
                        });

                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }

            assertThat(error.get()).isEqualTo("Chat interrupted.");
            assertThat(warnings).hasSize(1);
            LoggedWarn warn = warnings.get(0);
            assertThat(warn.message()).contains("claude").contains("answer chat question");
            assertThat(warn.throwable()).isNotNull();
            assertThat(stackTraceOf(warn.throwable()))
                    .contains("java.lang.InterruptedException: cancelled with <redacted>")
                    .contains(IntellijClaudeServiceTest.class.getName())
                    .doesNotContain(secret);
        }
    }

    @Nested
    class HandleDeepFailure {

        @Test
        void logsBlankExceptionAtWarnAndNamesClassInReturnedMessage() {
            List<LoggedWarn> warnings = new ArrayList<>();

            String msg =
                    IntellijClaudeService.handleDeepFailure(
                            ReviewProvider.COPILOT,
                            new NullPointerException(),
                            capturingLogger(warnings));

            assertThat(msg)
                    .isEqualTo(
                            "Couldn't generate IntelliJ-assisted review (NullPointerException). See idea.log.");
            assertThat(warnings).hasSize(1);
            assertThat(warnings.get(0).message())
                    .contains("copilot")
                    .contains("generate IntelliJ-assisted review");
            assertThat(warnings.get(0).throwable()).isInstanceOf(NullPointerException.class);
        }

        @Test
        void logsInterruptedDeepReviewAtWarnAndRestoresInterruptFlag() {
            List<LoggedWarn> warnings = new ArrayList<>();
            try {
                String msg =
                        IntellijClaudeService.handleDeepFailure(
                                ReviewProvider.CLAUDE,
                                new InterruptedException(),
                                capturingLogger(warnings));

                assertThat(msg).isEqualTo("Review interrupted.");
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
            assertThat(warnings).hasSize(1);
            assertThat(warnings.get(0).message()).contains("claude");
            assertThat(warnings.get(0).throwable()).isInstanceOf(InterruptedException.class);
        }
    }

    private record LoggedWarn(String message, Throwable throwable) {}

    private static Logger capturingLogger(List<LoggedWarn> sink) {
        return (Logger)
                Proxy.newProxyInstance(
                        Logger.class.getClassLoader(),
                        new Class<?>[] {Logger.class},
                        (proxy, method, args) -> {
                            if ("warn".equals(method.getName())
                                    && args != null
                                    && args.length >= 1
                                    && args[0] instanceof String pattern) {
                                Object[] params =
                                        args.length == 2 && args[1] instanceof Object[] varargs
                                                ? varargs
                                                : Arrays.copyOfRange(args, 1, args.length);
                                var formatted = MessageFormatter.arrayFormat(pattern, params);
                                sink.add(
                                        new LoggedWarn(
                                                formatted.getMessage(), formatted.getThrowable()));
                                return null;
                            }
                            Class<?> returnType = method.getReturnType();
                            if (returnType == boolean.class) {
                                return true;
                            }
                            return null;
                        });
    }

    private static String stackTraceOf(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
