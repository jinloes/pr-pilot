package com.jinloes.prpilot.services;

import com.intellij.ide.BrowserUtil;
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

    static final String NOTIFICATION_GROUP = "PR Pilot";

    private record PollStatus(long epochMs, String error) {}

    private volatile ScheduledFuture<?> scheduledTask;
    private volatile PollStatus lastPollStatus = new PollStatus(0, null);
    private final SeenPRSet seenSet = new SeenPRSet();
    private final PendingReviewIndex pendingIndex = new PendingReviewIndex();
    private final IntellijGitHubService githubService = IntellijGitHubService.getInstance();

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
        ScheduledFuture<?> task = scheduledTask;
        return task != null && !task.isCancelled();
    }

    /**
     * Returns a human-readable description of the last poll attempt — e.g. "Last polled: 3 min ago"
     * or "Last poll: 45s ago — Error: connection refused". Returns {@code null} if no poll has run
     * yet.
     */
    public String getLastPollStatus() {
        PollStatus status = lastPollStatus;
        if (status.epochMs() == 0) return null;
        long agoSec = (System.currentTimeMillis() - status.epochMs()) / 1000;
        String when = agoSec < 60 ? agoSec + "s ago" : (agoSec / 60) + " min ago";
        return status.error() != null
                ? "Last poll: " + when + " — Error: " + status.error()
                : "Last polled: " + when;
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

        String pollError = null;
        List<PullRequest> found = new ArrayList<>();

        if (settings.isNotifyReviewRequested()) {
            try {
                found.addAll(githubService.searchPRs(token, "is:open is:pr review-requested:@me"));
            } catch (Exception e) {
                log.warn("PR notification poll failed", e);
                pollError = sanitizeError(e);
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
                if (pollError == null) pollError = sanitizeError(e);
            }
        }

        lastPollStatus = new PollStatus(System.currentTimeMillis(), pollError);

        if (!seenSet.isSeeded()) {
            // First run: populate the seen set without showing any notifications
            for (PullRequest pr : found) seenSet.add(pr);
            seenSet.markSeeded();
            seenSet.save();
            return;
        }

        // Notify about PRs that weren't seen before and have no in-progress draft
        for (PullRequest pr : found) {
            if (!seenSet.contains(pr)) {
                seenSet.add(pr);
                if (!pendingIndex.hasDraft(pr.getOwner(), pr.getRepo(), pr.getNumber())) {
                    fireNotification(pr);
                }
            }
        }
        // Drop entries for PRs no longer in live results (closed, merged, review fulfilled),
        // then cap size to guard against unbounded growth.
        seenSet.retain(found);
        seenSet.trim();
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

    /**
     * Returns a display-safe error summary from the given exception, stripping anything that looks
     * like a Bearer token or other secret that GitHub HTTP errors may echo back in the message body.
     */
    static String sanitizeError(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) return "unknown error";
        String sanitized = msg;
        sanitized = sanitized.replaceAll("(?i)bearer\\s+[^\\s\"'&,;)]+", "Bearer [redacted]");
        sanitized =
                sanitized.replaceAll(
                        "(?i)(token\\s*[:=]\\s*)[^\\s\"'&,;)]+", "$1[redacted]");
        sanitized = sanitized.replaceAll("(?i)(token\\s+)[^\\s\"'&,;)]+", "$1[redacted]");
        sanitized = sanitized.replaceAll("\\bgh[pousr]_[A-Za-z0-9_]+\\b", "[redacted]");
        return sanitized;
    }
}
