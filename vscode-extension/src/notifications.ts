import type { PR } from './models';

/** Provenance of a background PR notification, surfaced so the source is unambiguous. */
export type NotifySource = 'reviewRequested' | 'starredRepo';
const NOTIFICATION_SOURCES: readonly NotifySource[] = ['reviewRequested', 'starredRepo'];

export const MAX_INDIVIDUAL_NOTIFICATIONS_PER_POLL = 3;

export interface NotificationHealth {
    status: 'never' | 'healthy' | 'degraded' | 'error';
    lastAttemptAt?: number;
    lastSuccessAt?: number;
    message?: string;
    consecutiveFailures: number;
    lastWarningAt?: number;
}

export const EMPTY_NOTIFICATION_HEALTH: NotificationHealth = {
    status: 'never',
    consecutiveFailures: 0,
};

export function recordNotificationSuccess(
    previous: NotificationHealth,
    now = Date.now(),
): NotificationHealth {
    return {
        ...previous,
        status: 'healthy',
        lastAttemptAt: now,
        lastSuccessAt: now,
        message: undefined,
        consecutiveFailures: 0,
    };
}

export function recordNotificationFailure(
    previous: NotificationHealth,
    message: string,
    now = Date.now(),
): NotificationHealth {
    return {
        ...previous,
        status: 'error',
        lastAttemptAt: now,
        message,
        consecutiveFailures: previous.consecutiveFailures + 1,
    };
}

export function recordNotificationDegraded(
    previous: NotificationHealth,
    message: string,
    now = Date.now(),
): NotificationHealth {
    return {
        ...previous,
        status: 'degraded',
        lastAttemptAt: now,
        lastSuccessAt: now,
        message,
        consecutiveFailures: previous.consecutiveFailures + 1,
    };
}

export function shouldWarnAboutNotificationFailure(
    health: NotificationHealth,
    now = Date.now(),
    cooldownMs = 30 * 60_000,
): boolean {
    return (health.status === 'degraded' || health.status === 'error')
        && health.consecutiveFailures >= 2
        && (health.lastWarningAt === undefined || now - health.lastWarningAt >= cooldownMs);
}

export function markNotificationWarningShown(
    health: NotificationHealth,
    now = Date.now(),
): NotificationHealth {
    return { ...health, lastWarningAt: now };
}

export function notificationHealthLabel(health: NotificationHealth): string {
    if (health.status === 'never') return 'No notification poll has run yet.';
    const when = health.lastAttemptAt === undefined ? '' : ` Last checked ${new Date(health.lastAttemptAt).toLocaleString()}.`;
    if (health.status === 'healthy') return `Notifications are working.${when}`;
    if (health.status === 'degraded') return `Notifications are partially working: ${health.message || 'One source failed.'}${when}`;
    return `Notification polling failed: ${health.message || 'Unknown error.'}${when}`;
}

export function notificationWarningMessage(health: NotificationHealth): string {
    return health.status === 'degraded'
        ? 'PR Pilot notifications are partially working. Open settings for details or retry now.'
        : 'PR Pilot notifications are not working. Open settings for details or retry now.';
}

export function normalizeNotificationSeedSources(
    value: unknown,
    legacySeeded = false,
): NotifySource[] {
    if (!Array.isArray(value)) {
        return legacySeeded ? [...NOTIFICATION_SOURCES] : [];
    }
    const normalized = new Set<NotifySource>();
    for (const source of value) {
        if (source === 'reviewRequested' || source === 'starredRepo') normalized.add(source);
    }
    return [...normalized];
}

export function notifySourceLabel(source: NotifySource): string {
    return source === 'starredRepo' ? '★ Starred repo' : 'Review requested';
}

export function prNotificationKey(pr: PR): string {
    return `${pr.owner}/${pr.repo}#${pr.number}`;
}

export function notificationMessage(pr: PR, source: NotifySource): string {
    return `${notifySourceLabel(source)} · ${pr.owner}/${pr.repo} #${pr.number}: ${pr.title}`;
}

export function formatOverflowSummary(
    overflow: ReadonlyArray<{ pr: PR; source: NotifySource }>,
): string {
    let reviewRequestedCount = 0;
    let starredCount = 0;
    for (const item of overflow) {
        if (item.source === 'starredRepo') starredCount++;
        else reviewRequestedCount++;
    }
    const clauses: string[] = [];
    if (reviewRequestedCount > 0) clauses.push(`${reviewRequestedCount} review requested`);
    if (starredCount > 0) {
        clauses.push(`★ ${starredCount} starred ${starredCount === 1 ? 'repo' : 'repos'}`);
    }
    return `+${overflow.length} more new pull requests (${clauses.join(', ')})`;
}

export function summaryNotificationMessage(
    overflow: ReadonlyArray<{ pr: PR; source: NotifySource }>,
): string {
    return `PR Pilot · ${formatOverflowSummary(overflow)}`;
}

export function partitionNotificationBurst(
    notifications: ReadonlyArray<{ pr: PR; source: NotifySource }>,
    limit = MAX_INDIVIDUAL_NOTIFICATIONS_PER_POLL,
): {
    individual: Array<{ pr: PR; source: NotifySource }>;
    overflow: Array<{ pr: PR; source: NotifySource }>;
} {
    const cap = Math.max(0, limit);
    return {
        individual: notifications.slice(0, cap),
        overflow: notifications.slice(cap),
    };
}

/**
 * Merges review-requested and starred-repo results, deduped by PR. Review-requested wins when a PR
 * appears in both sources so its more actionable label is shown. Order is preserved
 * (review-requested first, then starred-only PRs).
 */
export function mergeBySource(
    reviewRequested: PR[],
    starred: PR[],
): Array<{ pr: PR; source: NotifySource }> {
    const merged = new Map<string, { pr: PR; source: NotifySource }>();
    for (const pr of reviewRequested) {
        const key = prNotificationKey(pr);
        if (!merged.has(key)) merged.set(key, { pr, source: 'reviewRequested' });
    }
    for (const pr of starred) {
        const key = prNotificationKey(pr);
        if (!merged.has(key)) merged.set(key, { pr, source: 'starredRepo' });
    }
    return [...merged.values()];
}

export interface NotificationSourceRequest {
    source: NotifySource;
    load: () => Promise<PR[]>;
}

export interface NotificationSourceResults {
    reviewRequested: PR[];
    starred: PR[];
    successfulSources: NotifySource[];
    failures: Array<{ source: NotifySource; message: string }>;
}

/** Settles independently configured sources so one failure cannot discard another's results. */
export async function settleNotificationSources(
    requests: NotificationSourceRequest[],
): Promise<NotificationSourceResults> {
    const settled = await Promise.allSettled(requests.map((request) => request.load()));
    const result: NotificationSourceResults = {
        reviewRequested: [],
        starred: [],
        successfulSources: [],
        failures: [],
    };
    settled.forEach((outcome, index) => {
        const source = requests[index].source;
        if (outcome.status === 'fulfilled') {
            result.successfulSources.push(source);
            if (source === 'reviewRequested') result.reviewRequested.push(...outcome.value);
            else result.starred.push(...outcome.value);
        } else {
            result.failures.push({
                source,
                message: outcome.reason instanceof Error ? outcome.reason.message : String(outcome.reason),
            });
        }
    });
    return result;
}

export interface NotificationPollPlan {
    status: 'healthy' | 'degraded' | 'failed';
    message?: string;
    seededSources: NotifySource[];
    seen: string[];
    notifications: Array<{ pr: PR; source: NotifySource }>;
}

/**
 * Applies one independently settled poll to persisted notification state. A source's first
 * successful result seeds only that source; already-seeded successful sources can keep delivering
 * while another source remains unavailable.
 */
export function planNotificationPoll(
    seededSources: ReadonlySet<NotifySource>,
    seen: ReadonlySet<string>,
    results: NotificationSourceResults,
    hasPendingDraft: (pr: PR) => boolean = (pr) => pr.hasReviewDraft === true,
): NotificationPollPlan {
    const message = results.failures
        .map(({ source, message: failure }) => `${source}: ${failure}`)
        .join('; ');
    if (results.successfulSources.length === 0 && results.failures.length > 0) {
        return {
            status: 'failed',
            message,
            seededSources: [...seededSources],
            seen: [...seen],
            notifications: [],
        };
    }

    const successfulSources = new Set(results.successfulSources);
    const previouslySeeded = new Set(seededSources);
    const nextSeeded = new Set(seededSources);
    for (const source of successfulSources) nextSeeded.add(source);

    const processable = mergeBySource(
        successfulSources.has('reviewRequested') && previouslySeeded.has('reviewRequested')
            ? results.reviewRequested
            : [],
        successfulSources.has('starredRepo') && previouslySeeded.has('starredRepo')
            ? results.starred
            : [],
    );
    const seedOnly = mergeBySource(
        successfulSources.has('reviewRequested') && !previouslySeeded.has('reviewRequested')
            ? results.reviewRequested
            : [],
        successfulSources.has('starredRepo') && !previouslySeeded.has('starredRepo')
            ? results.starred
            : [],
    );

    const nextSeen = new Set(seen);
    const notifications: Array<{ pr: PR; source: NotifySource }> = [];
    for (const candidate of processable) {
        const key = prNotificationKey(candidate.pr);
        if (nextSeen.has(key)) continue;
        nextSeen.add(key);
        if (!hasPendingDraft(candidate.pr)) {
            notifications.push(candidate);
        }
    }
    for (const { pr } of seedOnly) nextSeen.add(prNotificationKey(pr));

    return {
        status: results.failures.length > 0 ? 'degraded' : 'healthy',
        message: message || undefined,
        seededSources: [...nextSeeded],
        seen: [...nextSeen],
        notifications,
    };
}
