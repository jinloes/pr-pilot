package com.jinloes.prpilot.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.model.ReviewStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PrPilotUiCoordinatorTest {

    private static PullRequest pr(int number, String title, ReviewStatus status) {
        return new PullRequest(
                title,
                "https://github.com/acme/widgets/pull/" + number,
                "acme",
                "widgets",
                number,
                "",
                "alice",
                "2026-01-01T00:00:00Z",
                false,
                status);
    }

    private static final class FakeReviewSurface implements PrPilotUiCoordinator.ReviewSurface {
        record Activation(PullRequest pr, String source) {}

        final List<Activation> activations = new ArrayList<>();
        int reloadCount;
        PullRequest active;

        @Override
        public PullRequest activePr() {
            return active;
        }

        @Override
        public void activatePr(PullRequest pr, String source) {
            activations.add(new Activation(pr, source));
        }

        @Override
        public void reload() {
            reloadCount++;
        }
    }

    private static final class FakeEditorHost implements PrPilotUiCoordinator.EditorHost {
        final AtomicInteger openCalls = new AtomicInteger();
        final AtomicBoolean lastFocusFlag = new AtomicBoolean();
        final AtomicInteger refreshTitleCalls = new AtomicInteger();
        Supplier<PrPilotUiCoordinator.ReviewRegistration> onFirstOpen;
        PrPilotUiCoordinator.ReviewRegistration openedRegistration;

        @Override
        public void openReviewEditor(boolean focusEditor) {
            openCalls.incrementAndGet();
            lastFocusFlag.set(focusEditor);
            if (openedRegistration == null && onFirstOpen != null) {
                openedRegistration = onFirstOpen.get();
            }
        }

        @Override
        public void refreshTabPresentation() {
            refreshTitleCalls.incrementAndGet();
        }

        @Override
        public void runOnEdt(Runnable action) {
            action.run();
        }
    }

    @Nested
    class OpenOrFocusEditor {

        @Test
        void opensFocusedEditorWithoutChangingSelectionAndIgnoresDisposedCoordinator() {
            FakeEditorHost host = new FakeEditorHost();
            PrPilotUiCoordinator coordinator = new PrPilotUiCoordinator(host);

            coordinator.openOrFocusEditor();
            assertThat(host.openCalls).hasValue(1);
            assertThat(host.lastFocusFlag.get()).isTrue();
            assertThat(coordinator.activePr()).isNull();
            assertThat(coordinator.tabTitlePr()).isNull();

            coordinator.dispose();
            coordinator.openOrFocusEditor();
            assertThat(host.openCalls).hasValue(1);
        }
    }

    @Nested
    class RequestSelectPr {

        @Test
        void opensEditorQueuesNotificationUntilReadyAndUpdatesTabTitle() {
            FakeEditorHost host = new FakeEditorHost();
            PrPilotUiCoordinator coordinator = new PrPilotUiCoordinator(host);
            PullRequest pr42 = pr(42, "Open PR Pilot in main editor", ReviewStatus.UNREVIEWED);

            FakeReviewSurface reviewSurface = new FakeReviewSurface();
            host.onFirstOpen = () -> coordinator.registerReviewSurfaceForTest(reviewSurface, false);

            coordinator.requestSelectPr(pr42, "notification");

            assertThat(host.openCalls).hasValue(1);
            assertThat(host.lastFocusFlag.get()).isTrue();
            assertThat(coordinator.tabTitlePr()).isEqualTo(pr42);
            assertThat(reviewSurface.activations).isEmpty();

            coordinator.markSurfaceReadyCallbackForTest(host.openedRegistration).run();
            assertThat(reviewSurface.activations)
                    .containsExactly(new FakeReviewSurface.Activation(pr42, "notification"));

            reviewSurface.active = pr42;
            coordinator.notifyPrSelectedForTest(host.openedRegistration, pr42);
            assertThat(coordinator.activePr()).isEqualTo(pr42);
            assertThat(coordinator.tabTitlePr()).isEqualTo(pr42);
        }

        @Test
        void keepsOriginalTabTitleOnCancelledSwitchAndRefocusesWithoutReActivatingSamePr() {
            FakeEditorHost host = new FakeEditorHost();
            PrPilotUiCoordinator coordinator = new PrPilotUiCoordinator(host);
            PullRequest pr42 = pr(42, "First PR", ReviewStatus.UNREVIEWED);
            PullRequest pr43 = pr(43, "Second PR", ReviewStatus.UNREVIEWED);

            FakeReviewSurface reviewSurface = new FakeReviewSurface();
            PrPilotUiCoordinator.ReviewRegistration reg =
                    coordinator.registerReviewSurfaceForTest(reviewSurface, true);
            host.openedRegistration = reg;

            coordinator.requestSelectPr(pr42, null);
            reviewSurface.active = pr42;
            coordinator.notifyPrSelectedForTest(reg, pr42);
            reviewSurface.activations.clear();

            // Notification requests PR #43 while PR #42 has unsaved review edits; user cancels
            // the switch in the webview, so notifyPrSelectedForTest is not called for #43.
            coordinator.requestSelectPr(pr43, "notification");
            assertThat(reviewSurface.activations)
                    .containsExactly(new FakeReviewSurface.Activation(pr43, "notification"));
            assertThat(coordinator.activePr()).isEqualTo(pr42);
            assertThat(coordinator.tabTitlePr()).isEqualTo(pr42);

            // Re-requesting the already-selected PR #42 focuses the editor tab without re-sending
            // activatePR.
            reviewSurface.activations.clear();
            int opensBefore = host.openCalls.get();
            coordinator.requestSelectPr(pr42, "notification");

            assertThat(host.openCalls.get()).isEqualTo(opensBefore + 1);
            assertThat(reviewSurface.activations).isEmpty();
        }
    }

    @Nested
    class SplitAndCloseLifecycle {

        @Test
        void seedsSplitEditorTracksFocusReloadsAndResetsStateWhenLastTabCloses() {
            FakeEditorHost host = new FakeEditorHost();
            PrPilotUiCoordinator coordinator = new PrPilotUiCoordinator(host);

            PullRequest pr42 = pr(42, "Primary PR", ReviewStatus.UNREVIEWED);
            PullRequest pr77 = pr(77, "Secondary PR", ReviewStatus.UNREVIEWED);

            FakeReviewSurface firstSurface = new FakeReviewSurface();
            PrPilotUiCoordinator.ReviewRegistration firstReg =
                    coordinator.registerReviewSurfaceForTest(firstSurface, true);
            host.openedRegistration = firstReg;
            coordinator.requestSelectPr(pr42, null);
            firstSurface.active = pr42;
            coordinator.notifyPrSelectedForTest(firstReg, pr42);

            // Splitting the editor opens a second surface seeded with the active PR (#42).
            FakeReviewSurface splitSurface = new FakeReviewSurface();
            PrPilotUiCoordinator.ReviewRegistration splitReg =
                    coordinator.registerReviewSurfaceForTest(splitSurface, true);
            assertThat(splitSurface.activations)
                    .containsExactly(new FakeReviewSurface.Activation(pr42, null));

            // Split tab switches to PR #77, then focusing the first tab restores #42.
            splitSurface.active = pr77;
            coordinator.notifyPrSelectedForTest(splitReg, pr77);
            assertThat(coordinator.activePr()).isEqualTo(pr77);

            firstReg.markFocused();
            assertThat(coordinator.activePr()).isEqualTo(pr42);
            assertThat(coordinator.tabTitlePr()).isEqualTo(pr42);

            // Reloading editor surfaces reloads both open tabs.
            coordinator.reloadEditorSurfaces();
            assertThat(firstSurface.reloadCount).isEqualTo(1);
            assertThat(splitSurface.reloadCount).isEqualTo(1);

            // Closing the first tab falls back to the remaining split tab (#77); closing the last
            // tab clears activePr and tabTitlePr.
            firstReg.close();
            assertThat(coordinator.activePr()).isEqualTo(pr77);

            splitReg.close();
            assertThat(coordinator.activePr()).isNull();
            assertThat(coordinator.tabTitlePr()).isNull();
        }
    }
}
