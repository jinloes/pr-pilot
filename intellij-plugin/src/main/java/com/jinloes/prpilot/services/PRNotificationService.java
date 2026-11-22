package com.jinloes.prpilot.services;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.settings.PluginSettings;
import com.jinloes.prpilot.ui.PRToolWindowFactory;
import com.jinloes.prpilot.util.LogRedactor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Background service that polls GitHub at a configurable interval and fires IDE balloon
 * notifications for:
 *
 * <ul>
 *   <li>New pull requests where a review has been requested from the current user.
 *   <li>New pull requests opened on the user's starred repositories (optional).
 * </ul>
 *
 * <p>The first poll after startup silently seeds the seen-PR set so the user doesn't receive a
 * flood of notifications for existing PRs.
 */
@Service
public final class PRNotificationService implements Disposable {

    private static final Logger log = LoggerFactory.getLogger(PRNotificationService.class);

    static final String NOTIFICATION_GROUP = "PR Pilot";
    static final String AUTH_MISSING_ERROR = "Not signed in — run 'gh auth login'.";
    static final String REVIEW_REQUESTED_QUERY = "is:open is:pr draft:false review-requested:@me";
    static final int MAX_INDIVIDUAL_NOTIFICATIONS_PER_POLL = 3;

    private record PollStatus(long epochMs, String error) {}

    private volatile ScheduledFuture<?> scheduledTask;
    private volatile PollStatus lastPollStatus = new PollStatus(0, null);
    private final Object pollGuard = new Object();
    private volatile long scheduleGeneration;
    private boolean pollRunning;
    private long pendingPollGeneration = -1;
    private long completedPollSequence;
    private final Supplier<NotificationSettings> settingsSupplier;
    private final NotificationSourceClient sourceClient;
    private final SeenPRSet seenSet;
    private final PendingReviewIndex pendingIndex;
    private final BiConsumer<PullRequest, NotificationSource> notificationSink;
    private final Consumer<List<Candidate>> summaryNotificationSink;
    private final BiConsumer<PendingReviewIndex, PendingReviewIndex.LoadResult>
            pendingIndexHealthObserver;

    public PRNotificationService() {
        this(
                PRNotificationService::currentSettings,
                new IntellijNotificationSourceClient(),
                new SeenPRSet(),
                new PendingReviewIndex(),
                null,
                PendingReviewIndexNotifications::observe,
                null);
    }

    PRNotificationService(
            Supplier<NotificationSettings> settingsSupplier,
            NotificationSourceClient sourceClient,
            SeenPRSet seenSet,
            PendingReviewIndex pendingIndex,
            BiConsumer<PullRequest, NotificationSource> notificationSink) {
        this(
                settingsSupplier,
                sourceClient,
                seenSet,
                pendingIndex,
                notificationSink,
                PendingReviewIndexNotifications::observe,
                null);
    }

    PRNotificationService(
            Supplier<NotificationSettings> settingsSupplier,
            NotificationSourceClient sourceClient,
            SeenPRSet seenSet,
            PendingReviewIndex pendingIndex,
            BiConsumer<PullRequest, NotificationSource> notificationSink,
            BiConsumer<PendingReviewIndex, PendingReviewIndex.LoadResult>
                    pendingIndexHealthObserver) {
        this(
                settingsSupplier,
                sourceClient,
                seenSet,
                pendingIndex,
                notificationSink,
                pendingIndexHealthObserver,
                null);
    }

    PRNotificationService(
            Supplier<NotificationSettings> settingsSupplier,
            NotificationSourceClient sourceClient,
            SeenPRSet seenSet,
            PendingReviewIndex pendingIndex,
            BiConsumer<PullRequest, NotificationSource> notificationSink,
            BiConsumer<PendingReviewIndex, PendingReviewIndex.LoadResult>
                    pendingIndexHealthObserver,
            Consumer<List<Candidate>> summaryNotificationSink) {
        this.settingsSupplier = settingsSupplier;
        this.sourceClient = sourceClient;
        this.seenSet = seenSet;
        this.pendingIndex = pendingIndex;
        this.notificationSink =
                notificationSink != null ? notificationSink : this::fireNotification;
        this.pendingIndexHealthObserver = pendingIndexHealthObserver;
        this.summaryNotificationSink =
                summaryNotificationSink != null
                        ? summaryNotificationSink
                        : this::fireSummaryNotification;
    }

    public static PRNotificationService getInstance() {
        return ApplicationManager.getApplication().getService(PRNotificationService.class);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    public synchronized void startPolling(int intervalMinutes) {
        stopPolling();
        long delay = Math.max(1, intervalMinutes);
        final long generation;
        synchronized (pollGuard) {
            generation = ++scheduleGeneration;
        }
        scheduledTask =
                AppExecutorUtil.getAppScheduledExecutorService()
                        .scheduleWithFixedDelay(
                                () -> requestPoll(generation), 0, delay, TimeUnit.MINUTES);
    }

    public synchronized void stopPolling() {
        synchronized (pollGuard) {
            scheduleGeneration++;
            pendingPollGeneration = -1;
            pollGuard.notifyAll();
        }
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
            scheduledTask = null;
        }
    }

    public void resetSeenState() {
        seenSet.reset();
    }

    public boolean isPolling() {
        ScheduledFuture<?> task = scheduledTask;
        return task != null && !task.isCancelled();
    }

    /**
     * Runs a single notification poll immediately using persisted settings, waiting if a poll is
     * already in flight so callers observe the completed coalesced poll status.
     */
    public void pollNow() {
        long generation;
        long targetSequence = -1;
        synchronized (pollGuard) {
            generation = scheduleGeneration;
            if (pollRunning) {
                pendingPollGeneration = generation;
                targetSequence = completedPollSequence + 2;
            } else {
                pollRunning = true;
            }
            if (targetSequence >= 0) {
                while (pollRunning
                        && scheduleGeneration == generation
                        && completedPollSequence < targetSequence) {
                    try {
                        pollGuard.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                return;
            }
        }
        runPollLoop();
    }

    /**
     * Returns a human-readable description of the last poll attempt — e.g. "Last polled: 3 min ago"
     * or "Last poll: 45s ago — Error: connection refused". Returns {@code null} if no poll has run
     * yet.
     */
    public String getLastPollStatus() {
        PollStatus status = lastPollStatus;
        return formatPollStatus(status.epochMs(), status.error(), System.currentTimeMillis());
    }

    static String formatPollStatus(long pollEpochMs, String error, long nowMs) {
        if (pollEpochMs == 0) return null;
        long agoSec = (nowMs - pollEpochMs) / 1000;
        String when = agoSec < 60 ? agoSec + "s ago" : (agoSec / 60) + " min ago";
        return error != null ? "Last poll: " + when + " — Error: " + error : "Last polled: " + when;
    }

    void recordPollStatus(String error) {
        lastPollStatus = new PollStatus(System.currentTimeMillis(), error);
    }

    @Override
    public void dispose() {
        stopPolling();
    }

    // -------------------------------------------------------------------------
    // Poll logic
    // -------------------------------------------------------------------------

    void poll() {
        requestPoll(scheduleGeneration);
    }

    private void requestPoll(long generation) {
        synchronized (pollGuard) {
            if (generation != scheduleGeneration) {
                return;
            }
            if (pollRunning) {
                pendingPollGeneration = generation;
                return;
            }
            pollRunning = true;
        }
        runPollLoop();
    }

    private void runPollLoop() {
        while (true) {
            RuntimeException failure = null;
            try {
                pollBody();
            } catch (RuntimeException e) {
                failure = e;
            }

            boolean runAgain;
            synchronized (pollGuard) {
                completedPollSequence++;
                runAgain =
                        failure == null
                                && pendingPollGeneration == scheduleGeneration
                                && pendingPollGeneration >= 0;
                pendingPollGeneration = -1;
                if (!runAgain) {
                    pollRunning = false;
                }
                pollGuard.notifyAll();
            }
            if (failure != null) {
                throw failure;
            }
            if (!runAgain) {
                return;
            }
        }
    }

    private void pollBody() {
        NotificationSettings settings = settingsSupplier.get();
        if (!settings.notificationsEnabled()) return;

        String pollError = null;
        List<PullRequest> reviewRequested = new ArrayList<>();
        List<PullRequest> starredPrs = new ArrayList<>();

        if (settings.notifyReviewRequested()) {
            try {
                reviewRequested.addAll(sourceClient.searchPRs(REVIEW_REQUESTED_QUERY));
            } catch (Exception e) {
                log.warn("PR notification poll failed", LogRedactor.sanitize(e));
                pollError = sanitizeError(e);
            }
        }

        if (settings.notifyStarredRepos()) {
            try {
                List<String> starred = sourceClient.getStarredRepos();
                // Cap at 25 repos to avoid a huge search query
                List<String> slice = starred.subList(0, Math.min(starred.size(), 25));
                if (!slice.isEmpty()) {
                    String repoQ =
                            slice.stream().map(r -> "repo:" + r).collect(Collectors.joining(" "));
                    starredPrs.addAll(sourceClient.searchPRs(buildStarredReposQuery(repoQ)));
                }
            } catch (Exception e) {
                log.warn("PR notification poll failed", LogRedactor.sanitize(e));
                if (pollError == null) pollError = sanitizeError(e);
            }
        }

        recordPollStatus(pollError);
        if (!seenSet.isSeeded() && pollError != null) {
            return;
        }

        List<Candidate> candidates = mergeCandidates(reviewRequested, starredPrs);
        PendingReviewIndex.LoadResult pendingEntries = pendingIndex.listResult();
        pendingIndexHealthObserver.accept(pendingIndex, pendingEntries);
        if (!pendingEntries.healthy()) {
            return;
        }

        if (!seenSet.isSeeded()) {
            // First run: populate the seen set without showing any notifications
            for (Candidate c : candidates) seenSet.add(c.pr());
            seenSet.markSeeded();
            seenSet.save();
            return;
        }

        Set<String> draftKeys =
                pendingEntries.entries().stream()
                        .map(entry -> entry.owner() + "/" + entry.repo() + "#" + entry.number())
                        .collect(Collectors.toSet());

        // Notify about PRs that weren't seen before and have no in-progress draft, capping
        // individual notifications per poll so post-offline bursts do not flood the IDE.
        List<Candidate> eligible = new ArrayList<>();
        for (Candidate c : candidates) {
            PullRequest pr = c.pr();
            if (!seenSet.contains(pr)) {
                seenSet.add(pr);
                if (!draftKeys.contains(prKey(pr))) {
                    eligible.add(c);
                }
            }
        }
        int individualCount = Math.min(eligible.size(), MAX_INDIVIDUAL_NOTIFICATIONS_PER_POLL);
        for (int i = 0; i < individualCount; i++) {
            Candidate c = eligible.get(i);
            notificationSink.accept(c.pr(), c.source());
        }
        if (eligible.size() > MAX_INDIVIDUAL_NOTIFICATIONS_PER_POLL) {
            summaryNotificationSink.accept(
                    List.copyOf(
                            eligible.subList(
                                    MAX_INDIVIDUAL_NOTIFICATIONS_PER_POLL, eligible.size())));
        }
        // Keep previously seen keys so limited result windows don't cause duplicate notifications
        // when older open PRs fall in/out of the top-N search page.
        seenSet.trim();
        seenSet.save();
    }

    /** Provenance of a notified PR, surfaced in the balloon so the source is unambiguous. */
    enum NotificationSource {
        REVIEW_REQUESTED("Review requested"),
        STARRED_REPO("\u2605 Starred repo");

        private final String label;

        NotificationSource(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    record Candidate(PullRequest pr, NotificationSource source) {}

    record NotificationSettings(
            boolean notificationsEnabled,
            boolean notifyReviewRequested,
            boolean notifyStarredRepos) {}

    interface NotificationSourceClient {
        List<PullRequest> searchPRs(String query) throws Exception;

        List<String> getStarredRepos() throws Exception;
    }

    static String prKey(PullRequest pr) {
        return pr.getOwner() + "/" + pr.getRepo() + "#" + pr.getNumber();
    }

    /**
     * Merges review-requested and starred-repo results, deduped by PR. Review-requested wins when a
     * PR appears in both sources so its more actionable label is shown.
     */
    static List<Candidate> mergeCandidates(
            List<PullRequest> reviewRequested, List<PullRequest> starred) {
        LinkedHashMap<String, Candidate> merged = new LinkedHashMap<>();
        for (PullRequest pr : reviewRequested) {
            merged.putIfAbsent(prKey(pr), new Candidate(pr, NotificationSource.REVIEW_REQUESTED));
        }
        for (PullRequest pr : starred) {
            merged.putIfAbsent(prKey(pr), new Candidate(pr, NotificationSource.STARRED_REPO));
        }
        return new ArrayList<>(merged.values());
    }

    static String notificationTitle(PullRequest pr, NotificationSource source) {
        return source.label() + " · " + pr.getOwner() + "/" + pr.getRepo() + " #" + pr.getNumber();
    }

    static String formatOverflowSummary(List<Candidate> overflow) {
        int reviewRequestedCount = 0;
        int starredCount = 0;
        for (Candidate item : overflow) {
            if (item.source() == NotificationSource.STARRED_REPO) {
                starredCount++;
            } else {
                reviewRequestedCount++;
            }
        }
        List<String> clauses = new ArrayList<>();
        if (reviewRequestedCount > 0) {
            clauses.add(reviewRequestedCount + " review requested");
        }
        if (starredCount > 0) {
            clauses.add(
                    "\u2605 "
                            + starredCount
                            + " starred "
                            + (starredCount == 1 ? "repo" : "repos"));
        }
        return "+"
                + overflow.size()
                + " more new pull requests ("
                + String.join(", ", clauses)
                + ")";
    }

    static String buildStarredReposQuery(String repoQ) {
        return "is:open is:pr draft:false " + repoQ;
    }

    private static NotificationSettings currentSettings() {
        PluginSettings settings = PluginSettings.getInstance();
        return new NotificationSettings(
                settings.isNotificationsEnabled(),
                settings.isNotifyReviewRequested(),
                settings.isNotifyStarredRepos());
    }

    private static final class IntellijNotificationSourceClient
            implements NotificationSourceClient {
        private final IntellijGitHubService githubService = IntellijGitHubService.getInstance();

        @Override
        public List<PullRequest> searchPRs(String query) throws Exception {
            return githubService.searchPRs(query);
        }

        @Override
        public List<String> getStarredRepos() throws Exception {
            return githubService.getStarredRepos();
        }
    }

    private void fireNotification(PullRequest pr, NotificationSource source) {
        String title = notificationTitle(pr, source);
        String content = pr.getTitle();

        ApplicationManager.getApplication()
                .invokeLater(
                        () -> {
                            Notification notification =
                                    NotificationGroupManager.getInstance()
                                            .getNotificationGroup(NOTIFICATION_GROUP)
                                            .createNotification(
                                                    title, content, NotificationType.INFORMATION);
                            notification.addAction(
                                    NotificationAction.createSimple(
                                            "Open in PR Pilot",
                                            () ->
                                                    PRToolWindowFactory.activatePrFromNotification(
                                                            pr)));
                            notification.notify(null);
                        });
    }

    private void fireSummaryNotification(List<Candidate> overflow) {
        if (overflow.isEmpty()) return;
        String content = formatOverflowSummary(overflow);

        ApplicationManager.getApplication()
                .invokeLater(
                        () -> {
                            Notification notification =
                                    NotificationGroupManager.getInstance()
                                            .getNotificationGroup(NOTIFICATION_GROUP)
                                            .createNotification(
                                                    NOTIFICATION_GROUP,
                                                    content,
                                                    NotificationType.INFORMATION);
                            notification.addAction(
                                    NotificationAction.createSimple(
                                            "Open PR Pilot",
                                            PRToolWindowFactory::activateToolWindow));
                            notification.notify(null);
                        });
    }

    /**
     * Returns a display-safe error summary from the given exception, stripping anything that looks
     * like a Bearer token or other secret that GitHub HTTP errors may echo back in the message
     * body.
     */
    static String sanitizeError(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) return "unknown error";
        String sanitized = msg;
        sanitized = sanitized.replaceAll("(?i)bearer\\s+[^\\s\"'&,;)]+", "Bearer [redacted]");
        sanitized = sanitized.replaceAll("(?i)(token\\s*[:=]\\s*)[^\\s\"'&,;)]+", "$1[redacted]");
        sanitized = sanitized.replaceAll("(?i)(token\\s+)[^\\s\"'&,;)]+", "$1[redacted]");
        sanitized = sanitized.replaceAll("\\bgh[pousr]_[A-Za-z0-9_]+\\b", "[redacted]");
        return sanitized;
    }
}
