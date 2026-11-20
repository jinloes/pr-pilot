package com.jinloes.prpilot.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.services.PRNotificationService.Candidate;
import com.jinloes.prpilot.services.PRNotificationService.NotificationSettings;
import com.jinloes.prpilot.services.PRNotificationService.NotificationSource;
import com.jinloes.prpilot.services.PRNotificationService.NotificationSourceClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PRNotificationServiceTest {

    @TempDir Path tempDir;

    private static PullRequest pr(String owner, String repo, int number) {
        return new PullRequest(
                "Title #" + number, "https://github.test", owner, repo, number, "", "octocat", "");
    }

    @Nested
    class NotificationLabeling {

        @Test
        void reviewRequestedTitleNamesTheSourceAndPr() {
            String title =
                    PRNotificationService.notificationTitle(
                            pr("acme", "foo", 7), NotificationSource.REVIEW_REQUESTED);
            assertThat(title).contains("Review requested").contains("acme/foo #7");
        }

        @Test
        void starredRepoTitleIsDistinctFromReviewRequested() {
            String title =
                    PRNotificationService.notificationTitle(
                            pr("acme", "bar", 9), NotificationSource.STARRED_REPO);
            assertThat(title).contains("Starred repo").contains("acme/bar #9");
        }

        @Test
        void reviewRequestedWinsWhenAPrMatchesBothSources() {
            PullRequest shared = pr("acme", "foo", 1);
            PullRequest starredOnly = pr("acme", "bar", 2);
            List<Candidate> merged =
                    PRNotificationService.mergeCandidates(
                            List.of(shared), List.of(shared, starredOnly));

            assertThat(merged).hasSize(2);
            assertThat(merged.get(0).source()).isEqualTo(NotificationSource.REVIEW_REQUESTED);
            assertThat(merged.get(1).pr().getNumber()).isEqualTo(2);
            assertThat(merged.get(1).source()).isEqualTo(NotificationSource.STARRED_REPO);
        }
    }

    @Nested
    class QueryBuilders {

        @Test
        void reviewRequestedQueryExcludesDrafts() {
            assertThat(PRNotificationService.REVIEW_REQUESTED_QUERY)
                    .isEqualTo("is:open is:pr draft:false review-requested:@me");
        }

        @Test
        void starredReposQueryExcludesDrafts() {
            assertThat(PRNotificationService.buildStarredReposQuery("repo:acme/foo repo:acme/bar"))
                    .isEqualTo("is:open is:pr draft:false repo:acme/foo repo:acme/bar");
        }
    }

    @Nested
    class FormatPollStatus {

        @Test
        void noPollYetReturnsNull() {
            String result = PRNotificationService.formatPollStatus(0L, null, 10_000L);
            assertThat(result).isNull();
        }

        @Test
        void successStatusUsesLastPolledPrefix() {
            String result = PRNotificationService.formatPollStatus(9_000L, null, 10_000L);
            assertThat(result).isEqualTo("Last polled: 1s ago");
        }

        @Test
        void errorStatusIncludesErrorText() {
            String result =
                    PRNotificationService.formatPollStatus(
                            120_000L, PRNotificationService.AUTH_MISSING_ERROR, 180_000L);
            assertThat(result)
                    .isEqualTo(
                            "Last poll: 1 min ago — Error: "
                                    + PRNotificationService.AUTH_MISSING_ERROR);
        }
    }

    @Nested
    class SanitizeError {

        @Test
        void plainMessageIsReturnedAsIs() {
            String result =
                    PRNotificationService.sanitizeError(new IOException("connection refused"));
            assertThat(result).isEqualTo("connection refused");
        }

        @Test
        void bearerTokenIsRedacted() {
            String result =
                    PRNotificationService.sanitizeError(
                            new IOException(
                                    "401 Unauthorized: Bearer ghp_abc123XYZ token rejected"));
            assertThat(result).doesNotContain("ghp_abc123XYZ");
            assertThat(result).contains("[redacted]");
        }

        @Test
        void tokenKeywordIsRedacted() {
            String result =
                    PRNotificationService.sanitizeError(
                            new IOException("invalid token: ghs_secretvalue"));
            assertThat(result).doesNotContain("ghs_secretvalue");
            assertThat(result).contains("[redacted]");
        }

        @Test
        void caseInsensitiveRedaction() {
            String result =
                    PRNotificationService.sanitizeError(
                            new IOException("TOKEN=abc123secret rejected"));
            assertThat(result).doesNotContain("abc123secret");
            assertThat(result).contains("[redacted]");
        }

        @Test
        void nullMessageFallsBackToUnknownError() {
            String result = PRNotificationService.sanitizeError(new IOException((String) null));
            assertThat(result).isEqualTo("unknown error");
        }

        @Test
        void blankMessageFallsBackToUnknownError() {
            String result = PRNotificationService.sanitizeError(new IOException("   "));
            assertThat(result).isEqualTo("unknown error");
        }
    }

    @Nested
    class Poll {

        @Test
        void failedOrPartialFirstPollDoesNotSeedAndRecoveryStaysSilent() {
            PullRequest reviewPr = pr("acme", "review", 1);
            PullRequest starredPr = pr("acme", "starred", 2);
            PullRequest newPr = pr("acme", "review", 3);
            FakeSourceClient source = new FakeSourceClient();
            source.reviewRequested = List.of(reviewPr);
            source.starredRepos = List.of("acme/starred");
            source.starred = List.of(starredPr);
            source.reviewFailure = new IOException("network down");
            SeenPRSet seenSet = new SeenPRSet(tempDir.resolve("seen-prs.json"));
            List<PullRequest> notified = new ArrayList<>();
            PRNotificationService service =
                    new PRNotificationService(
                            () -> new NotificationSettings(true, true, true),
                            source,
                            seenSet,
                            new PendingReviewIndex(tempDir.resolve("pending-prs.json")),
                            (pullRequest, ignored) -> notified.add(pullRequest));

            service.poll();

            assertThat(seenSet.isSeeded()).isFalse();
            assertThat(notified).isEmpty();

            source.reviewFailure = null;
            service.poll();

            assertThat(seenSet.isSeeded()).isTrue();
            assertThat(notified).isEmpty();

            source.reviewRequested = List.of(reviewPr, newPr);
            service.poll();

            assertThat(notified).containsExactly(newPr);
        }

        @Test
        void successfulEmptyFirstPollSeedsTheSnapshot() {
            SeenPRSet seenSet = new SeenPRSet(tempDir.resolve("empty-seen-prs.json"));
            PRNotificationService service =
                    new PRNotificationService(
                            () -> new NotificationSettings(true, true, false),
                            new FakeSourceClient(),
                            seenSet,
                            new PendingReviewIndex(tempDir.resolve("empty-pending-prs.json")),
                            (pullRequest, source) -> {});

            service.poll();

            assertThat(seenSet.isSeeded()).isTrue();
        }

        @Test
        void corruptPendingIndexSuppressesNotificationsUntilStateIsRecoverable()
                throws IOException {
            PullRequest candidate = pr("acme", "review", 4);
            FakeSourceClient source = new FakeSourceClient();
            source.reviewRequested = List.of(candidate);
            SeenPRSet seenSet = new SeenPRSet(tempDir.resolve("corrupt-seen-prs.json"));
            seenSet.markSeeded();
            seenSet.save();
            Path pendingFile = tempDir.resolve("corrupt-pending-prs.json");
            Files.writeString(pendingFile, "{broken", StandardCharsets.UTF_8);
            List<PullRequest> notified = new ArrayList<>();
            int[] healthWarnings = {0};
            PRNotificationService service =
                    new PRNotificationService(
                            () -> new NotificationSettings(true, true, false),
                            source,
                            seenSet,
                            new PendingReviewIndex(pendingFile),
                            (pullRequest, ignored) -> notified.add(pullRequest),
                            (index, result) -> {
                                if (!result.healthy()) healthWarnings[0]++;
                            });

            service.poll();

            assertThat(notified).isEmpty();
            assertThat(healthWarnings[0]).isEqualTo(1);
            assertThat(Files.readString(pendingFile, StandardCharsets.UTF_8)).isEqualTo("{broken");

            Files.delete(pendingFile);
            service.poll();

            assertThat(notified).containsExactly(candidate);
        }

        @Test
        void capsIndividualNotificationsAtThreeAndSummarizesOverflowWhileMarkingAllSeen() {
            FakeSourceClient source = new FakeSourceClient();
            source.reviewRequested =
                    List.of(
                            pr("acme", "review", 1),
                            pr("acme", "review", 2),
                            pr("acme", "review", 3),
                            pr("acme", "review", 4));
            source.starredRepos = List.of("acme/starred");
            source.starred = List.of(pr("acme", "starred", 5), pr("acme", "starred", 6));
            SeenPRSet seenSet = new SeenPRSet(tempDir.resolve("burst-seen-prs.json"));
            seenSet.markSeeded();
            seenSet.save();
            List<Integer> individualNumbers = new ArrayList<>();
            List<String> summaries = new ArrayList<>();
            PRNotificationService service =
                    new PRNotificationService(
                            () -> new NotificationSettings(true, true, true),
                            source,
                            seenSet,
                            new PendingReviewIndex(tempDir.resolve("burst-pending-prs.json")),
                            (pullRequest, ignored) ->
                                    individualNumbers.add(pullRequest.getNumber()),
                            (index, result) -> {},
                            overflow ->
                                    summaries.add(
                                            PRNotificationService.formatOverflowSummary(overflow)));

            service.pollNow();

            assertThat(individualNumbers).containsExactly(1, 2, 3);
            assertThat(summaries)
                    .containsExactly(
                            "+3 more new pull requests (1 review requested, \u2605 2 starred repos)");
            for (PullRequest item : source.reviewRequested) {
                assertThat(seenSet.contains(item)).isTrue();
            }
            for (PullRequest item : source.starred) {
                assertThat(seenSet.contains(item)).isTrue();
            }

            // A subsequent poll with <= 3 unseen PRs emits individual notifications with no
            // summary.
            individualNumbers.clear();
            summaries.clear();
            source.reviewRequested =
                    List.of(
                            pr("acme", "review", 1),
                            pr("acme", "review", 2),
                            pr("acme", "review", 3),
                            pr("acme", "review", 4),
                            pr("acme", "review", 7));
            service.pollNow();
            assertThat(individualNumbers).containsExactly(7);
            assertThat(summaries).isEmpty();
        }

        @Test
        void restartDuringRunningPollCoalescesOneSerializedFollowUp() throws Exception {
            CountDownLatch firstPollEntered = new CountDownLatch(1);
            CountDownLatch releaseFirstPoll = new CountDownLatch(1);
            CountDownLatch followUpFinished = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maxActive = new AtomicInteger();
            NotificationSourceClient source =
                    new NotificationSourceClient() {
                        @Override
                        public List<PullRequest> searchPRs(String query) throws Exception {
                            int invocation = calls.incrementAndGet();
                            int nowActive = active.incrementAndGet();
                            maxActive.accumulateAndGet(nowActive, Math::max);
                            try {
                                if (invocation == 1) {
                                    firstPollEntered.countDown();
                                    assertThat(releaseFirstPoll.await(5, TimeUnit.SECONDS))
                                            .isTrue();
                                } else if (invocation == 2) {
                                    followUpFinished.countDown();
                                }
                                return List.of();
                            } finally {
                                active.decrementAndGet();
                            }
                        }

                        @Override
                        public List<String> getStarredRepos() {
                            return List.of();
                        }
                    };
            PRNotificationService service =
                    new PRNotificationService(
                            () -> new NotificationSettings(true, true, false),
                            source,
                            new SeenPRSet(tempDir.resolve("serialized-seen-prs.json")),
                            new PendingReviewIndex(tempDir.resolve("serialized-pending-prs.json")),
                            (pullRequest, notificationSource) -> {});
            Thread initialPoll = new Thread(service::poll);

            try {
                initialPoll.start();
                assertThat(firstPollEntered.await(5, TimeUnit.SECONDS)).isTrue();

                service.startPolling(1);
                releaseFirstPoll.countDown();

                assertThat(followUpFinished.await(5, TimeUnit.SECONDS)).isTrue();
                initialPoll.join(5_000);
                assertThat(initialPoll.isAlive()).isFalse();
                assertThat(calls).hasValue(2);
                assertThat(maxActive).hasValue(1);
            } finally {
                releaseFirstPoll.countDown();
                service.stopPolling();
                initialPoll.join(5_000);
            }
        }

        @Test
        void pollNowWaitsForCoalescedCompletionWhenTriggeredDuringInFlightPoll() throws Exception {
            CountDownLatch firstPollEntered = new CountDownLatch(1);
            CountDownLatch releaseFirstPoll = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            NotificationSourceClient source =
                    new NotificationSourceClient() {
                        @Override
                        public List<PullRequest> searchPRs(String query) throws Exception {
                            int invocation = calls.incrementAndGet();
                            if (invocation == 1) {
                                firstPollEntered.countDown();
                                assertThat(releaseFirstPoll.await(5, TimeUnit.SECONDS)).isTrue();
                            }
                            return List.of();
                        }

                        @Override
                        public List<String> getStarredRepos() {
                            return List.of();
                        }
                    };
            PRNotificationService service =
                    new PRNotificationService(
                            () -> new NotificationSettings(true, true, false),
                            source,
                            new SeenPRSet(tempDir.resolve("pollnow-seen-prs.json")),
                            new PendingReviewIndex(tempDir.resolve("pollnow-pending-prs.json")),
                            (pullRequest, notificationSource) -> {});
            Thread initialPoll = new Thread(service::poll);
            CountDownLatch retryReturned = new CountDownLatch(1);
            Thread manualRetry =
                    new Thread(
                            () -> {
                                service.pollNow();
                                retryReturned.countDown();
                            });

            try {
                initialPoll.start();
                assertThat(firstPollEntered.await(5, TimeUnit.SECONDS)).isTrue();

                manualRetry.start();
                assertThat(retryReturned.await(150, TimeUnit.MILLISECONDS)).isFalse();

                releaseFirstPoll.countDown();
                assertThat(retryReturned.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(calls).hasValue(2);
                assertThat(service.getLastPollStatus()).startsWith("Last polled:");
            } finally {
                releaseFirstPoll.countDown();
                service.stopPolling();
                initialPoll.join(5_000);
                manualRetry.join(5_000);
            }
        }
    }

    private static final class FakeSourceClient implements NotificationSourceClient {
        private List<PullRequest> reviewRequested = List.of();
        private List<PullRequest> starred = List.of();
        private List<String> starredRepos = List.of();
        private Exception reviewFailure;

        @Override
        public List<PullRequest> searchPRs(String query) throws Exception {
            if (PRNotificationService.REVIEW_REQUESTED_QUERY.equals(query)) {
                if (reviewFailure != null) throw reviewFailure;
                return reviewRequested;
            }
            return starred;
        }

        @Override
        public List<String> getStarredRepos() {
            return starredRepos;
        }
    }
}
