package com.jinloes.prpilot.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class WebviewPanelDeepReviewTest {

    @Nested
    class DeepBridgeCallbacks {
        @Test
        void actualBridgePreparesWithoutProviderAndConsumesContinueOnlyOnce() throws Exception {
            for (String provider : List.of("claude", "copilot")) {
                var f = new WebviewPanelFixture();
                f.settings = provider;
                f.send("generateReview", "prepare-1", Map.of("intellijAssisted", true));
                assertThat(f.generations).isZero();
                f.drain();
                assertThat(f.generations).isZero();
                assertThat(f.messages.get(0).path("type").asText()).isEqualTo("deepReviewPrepared");
                f.send("continueDeepReview", "continue-1", f.continuation());
                f.drain();
                assertThat(f.generations).isEqualTo(1);
                int messages = f.messages.size();
                f.send("continueDeepReview", "continue-1", f.continuation());
                f.drain();
                assertThat(f.generations).isEqualTo(1);
                assertThat(f.messages).hasSize(messages);
            }
        }

        @Test
        void rejectsAssistedRequestWhileExperimentalSettingIsOff() throws Exception {
            var f = new WebviewPanelFixture();
            f.assistedEnabled = false;
            f.send("generateReview", "prepare-1", Map.of("intellijAssisted", true));
            assertThat(f.jobs).isEmpty();
            f.drain();
            assertThat(f.generations).isZero();
            assertThat(f.messages).hasSize(1);
            assertThat(f.messages.get(0).path("type").asText()).isEqualTo("reviewError");
            assertThat(f.messages.get(0).path("prKey").asText()).isEqualTo("acme/widget#42");
            assertThat(f.messages.get(0).path("message").asText())
                    .isEqualTo(
                            "IntelliJ-assisted review is disabled; enable it in PR Pilot"
                                    + " settings (experimental)");

            f.assistedEnabled = true;
            f.send("generateReview", "prepare-2", Map.of("intellijAssisted", true));
            f.drain();
            assertThat(f.messages.get(f.messages.size() - 1).path("type").asText())
                    .isEqualTo("deepReviewPrepared");
        }

        @Test
        void retriesTransientPreflightFailureWithNewOperationOnly() throws Exception {
            var f = new WebviewPanelFixture();
            f.send("generateReview", "prepare-1", Map.of("intellijAssisted", true));
            f.drain();
            f.failRefresh = true;
            f.send("continueDeepReview", "continue-1", f.continuation());
            f.drain();
            assertThat(f.generations).isZero();
            assertThat(f.messages.get(f.messages.size() - 1).path("type").asText())
                    .isEqualTo("reviewError");
            f.failRefresh = false;
            f.send("continueDeepReview", "continue-1", f.continuation());
            f.drain();
            assertThat(f.generations).isZero();
            f.send("continueDeepReview", "retry-2", f.continuation());
            f.drain();
            assertThat(f.generations).isEqualTo(1);
        }

        @Test
        void failedPreflightDoesNotRestoreInvalidatedPreparation() throws Exception {
            for (String change :
                    List.of("cancel", "settings", "selection", "disposed", "prepare")) {
                var f = new WebviewPanelFixture();
                f.send("generateReview", "prepare-1", Map.of("intellijAssisted", true));
                f.drain();
                f.failRefresh = true;
                f.send("continueDeepReview", "continue-1", f.continuation());
                switch (change) {
                    case "cancel" -> f.send("cancelReview", "continue-1", Map.of());
                    case "settings" -> f.settings = "copilot";
                    case "selection" -> f.field("selectionRevision", 1L);
                    case "disposed" -> f.field("disposed", true);
                    case "prepare" ->
                            f.send("generateReview", "prepare-2", Map.of("intellijAssisted", true));
                    default -> throw new AssertionError(change);
                }
                f.drain();
                f.failRefresh = false;
                f.settings = "claude";
                f.field("selectionRevision", 0L);
                f.field("disposed", false);
                f.send("continueDeepReview", "retry-2", f.continuation());
                f.drain();
                assertThat(f.generations).as(change).isZero();
            }
        }

        @Test
        void confirmedHeadChangeDoesNotRestorePreparation() throws Exception {
            var f = new WebviewPanelFixture();
            f.send("generateReview", "prepare-1", Map.of("intellijAssisted", true));
            f.drain();
            f.head = "b".repeat(40);
            f.send("continueDeepReview", "continue-1", f.continuation());
            f.drain();
            f.head = "a".repeat(40);
            f.send("continueDeepReview", "retry-2", f.continuation());
            f.drain();
            assertThat(f.generations).isZero();
        }

        @Test
        void actualBridgeRejectsHeadSettingsSelectionAndDisposedChanges() throws Exception {
            for (String change : List.of("head", "settings", "selection", "disposed")) {
                var f = new WebviewPanelFixture();
                f.send("generateReview", "prepare-1", Map.of("intellijAssisted", true));
                f.drain();
                f.send("continueDeepReview", "continue-1", f.continuation());
                switch (change) {
                    case "head" -> f.head = "b".repeat(40);
                    case "settings" -> f.settings = "copilot";
                    case "selection" -> f.field("selectionRevision", 1L);
                    case "disposed" -> f.field("disposed", true);
                    default -> throw new AssertionError(change);
                }
                f.drain();
                assertThat(f.generations).as(change).isZero();
                assertThat(f.removals).isEmpty();
            }
        }

        @Test
        void actualBridgeCancellationInvalidatesQueuedPreparationWithoutRemoval() throws Exception {
            var f = new WebviewPanelFixture();
            f.send("generateReview", "prepare-1", Map.of("intellijAssisted", true));
            f.send("cancelReview", "prepare-1", Map.of());
            f.drain();
            assertThat(f.messages).isEmpty();
            assertThat(f.generations).isZero();
            assertThat(f.removals).isEmpty();
        }

        @Test
        void actualBridgeMaintenanceNeedsNoPrAndRejectsUnconfirmedCleanup() throws Exception {
            var f = new WebviewPanelFixture();
            f.field("activePR", null);
            f.send("listDeepReviews", "list-1", Map.of());
            f.drain();
            assertThat(f.messages.get(0).path("type").asText()).isEqualTo("retainedDeepReviews");
            f.send(
                    "cleanupDeepReview",
                    "cleanup-1",
                    Map.of("retainedId", f.id, "projectClosed", false));
            f.drain();
            assertThat(f.removals).isEmpty();
            f.send(
                    "cleanupDeepReview",
                    "cleanup-2",
                    Map.of("retainedId", f.id, "projectClosed", true));
            f.drain();
            assertThat(f.removals).containsExactly(f.id);
            assertThat(f.generations).isZero();
        }
    }
}
