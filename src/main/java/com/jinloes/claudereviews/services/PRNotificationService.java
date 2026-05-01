package com.jinloes.claudereviews.services;

import com.intellij.ide.BrowserUtil;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.jinloes.claudereviews.model.PullRequest;
import com.jinloes.claudereviews.settings.PluginSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

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
@Slf4j
@Service
public final class PRNotificationService implements Disposable {

    static final String NOTIFICATION_GROUP = "Claude PR Reviews";

    private volatile ScheduledFuture<?> scheduledTask;
    private volatile long lastPollEpochMs = 0;
    private volatile String lastPollError = null;
    private final SeenPRSet seenSet = new SeenPRSet();
    private final GitHubService githubService = GitHubService.getInstance();

    public static PRNotificationService getInstance() {
        return ApplicationManager.getApplication().getService(PRNotificationService.class);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    public synchronized void startPolling(int intervalMinutes) {
        stopPolling();
        long delay = Math.max(1, intervalMinutes);
        scheduledTask =
                AppExecutorUtil.getAppScheduledExecutorService()
                        .scheduleWithFixedDelay(this::poll, 0, delay, TimeUnit.MINUTES);
    }

    public synchronized void stopPolling() {
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
            scheduledTask = null;
        }
    }

    public boolean isPolling() {
        return scheduledTask != null && !scheduledTask.isCancelled();
    }

    /**
     * Returns a human-readable description of the last poll attempt — e.g. "Last polled: 3 min ago"
     * or "Last poll: 45s ago — Error: connection refused". Returns {@code null} if no poll has run
     * yet.
     */
    public String getLastPollStatus() {
        long ts = lastPollEpochMs;
        if (ts == 0) return null;
        long agoSec = (System.currentTimeMillis() - ts) / 1000;
        String when = agoSec < 60 ? agoSec + "s ago" : (agoSec / 60) + " min ago";
        String err = lastPollError;
        return err != null ? "Last poll: " + when + " — Error: " + err : "Last polled: " + when;
    }

    @Override
    public void dispose() {
        stopPolling();
    }

    // -------------------------------------------------------------------------
    // Poll logic
    // -------------------------------------------------------------------------

    private void poll() {
        PluginSettings settings = PluginSettings.getInstance();
        if (!settings.isNotificationsEnabled()) return;

        String token = settings.getGithubToken();
        if (token == null || token.isBlank()) return;

        lastPollEpochMs = System.currentTimeMillis();
        String pollError = null;
        List<PullRequest> found = new ArrayList<>();

        if (settings.isNotifyReviewRequested()) {
            try {
                found.addAll(githubService.searchPRs(token, "is:open is:pr review-requested:@me"));
            } catch (Exception e) {
                log.warn("PR notification poll failed", e);
                pollError = e.getMessage();
            }
        }

        if (settings.isNotifyStarredRepos()) {
            try {
                List<String> starred = githubService.getStarredRepos(token);
                // Cap at 25 repos to avoid a huge search query
                List<String> slice = starred.subList(0, Math.min(starred.size(), 25));
                if (!slice.isEmpty()) {
                    String repoQ =
                            slice.stream().map(r -> "repo:" + r).collect(Collectors.joining(" "));
                    found.addAll(githubService.searchPRs(token, "is:open is:pr " + repoQ));
                }
            } catch (Exception e) {
                log.warn("PR notification poll failed", e);
                if (pollError == null) pollError = e.getMessage();
            }
        }

        lastPollError = pollError;

        if (!seenSet.isSeeded()) {
            // First run: populate the seen set without showing any notifications
            for (PullRequest pr : found) seenSet.add(pr);
            seenSet.markSeeded();
            seenSet.save();
            return;
        }

        // Notify about PRs that weren't seen before
        for (PullRequest pr : found) {
            if (!seenSet.contains(pr)) {
                seenSet.add(pr);
                fireNotification(pr);
            }
        }
        seenSet.save();
    }

    private void fireNotification(PullRequest pr) {
        String title = pr.getOwner() + "/" + pr.getRepo() + " #" + pr.getNumber();
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
                                            "Open PR", () -> BrowserUtil.browse(pr.getHtmlUrl())));
                            notification.notify(null);
                        });
    }
}
