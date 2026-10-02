package com.jinloes.prpilot.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class WorktreeCoordinatorTest {

    @Nested
    class WorktreeCoordinator {

        @Test
        void concurrentSameKeyAcquiresShareOneCreation() {
            com.jinloes.prpilot.ui.WorktreeCoordinator<String> coordinator =
                    new com.jinloes.prpilot.ui.WorktreeCoordinator<>();

            com.jinloes.prpilot.ui.WorktreeCoordinator.WorktreeLease<String> owner =
                    coordinator.acquire("acme/repo#7");
            com.jinloes.prpilot.ui.WorktreeCoordinator.WorktreeLease<String> waiter =
                    coordinator.acquire("acme/repo#7");

            assertThat(owner.owner()).isTrue();
            assertThat(waiter.owner()).isFalse();
            assertThat(waiter.future()).isSameAs(owner.future());
            assertThat(coordinator.install(owner, "worktree")).isTrue();
            assertThat(waiter.future()).isCompletedWithValue("worktree");
            assertThat(coordinator.activeValue()).isEqualTo("worktree");
        }

        @Test
        void clearDuringCreationRejectsLateInstallAndFailsWaiters() {
            com.jinloes.prpilot.ui.WorktreeCoordinator<String> coordinator =
                    new com.jinloes.prpilot.ui.WorktreeCoordinator<>();
            com.jinloes.prpilot.ui.WorktreeCoordinator.WorktreeLease<String> owner =
                    coordinator.acquire("acme/repo#7");
            com.jinloes.prpilot.ui.WorktreeCoordinator.WorktreeLease<String> waiter =
                    coordinator.acquire("acme/repo#7");

            assertThat(coordinator.clear()).isNull();
            assertThat(waiter.future()).isCompletedExceptionally();

            assertThat(coordinator.install(owner, "stale-worktree")).isFalse();
            assertThat(waiter.future()).isCompletedExceptionally();
            assertThat(coordinator.activeValue()).isNull();
        }

        @Test
        void clearReturnsInstalledValueAndStartsNewEpoch() {
            com.jinloes.prpilot.ui.WorktreeCoordinator<String> coordinator =
                    new com.jinloes.prpilot.ui.WorktreeCoordinator<>();
            com.jinloes.prpilot.ui.WorktreeCoordinator.WorktreeLease<String> first =
                    coordinator.acquire("acme/repo#7");
            coordinator.install(first, "worktree");

            assertThat(coordinator.clear()).isEqualTo("worktree");
            com.jinloes.prpilot.ui.WorktreeCoordinator.WorktreeLease<String> next =
                    coordinator.acquire("acme/repo#7");
            assertThat(next.owner()).isTrue();
            assertThat(next.future()).isNotSameAs(first.future());
        }

        @Test
        void failedCreationReleasesKeyForRetry() {
            com.jinloes.prpilot.ui.WorktreeCoordinator<String> coordinator =
                    new com.jinloes.prpilot.ui.WorktreeCoordinator<>();
            com.jinloes.prpilot.ui.WorktreeCoordinator.WorktreeLease<String> failed =
                    coordinator.acquire("acme/repo#7");

            coordinator.fail(failed);

            assertThat(failed.future()).isCompletedExceptionally();
            assertThat(coordinator.acquire("acme/repo#7").owner()).isTrue();
        }
    }
}
