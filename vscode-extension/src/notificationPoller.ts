import * as vscode from 'vscode';
import type { PR } from './models';
import type { SidecarClient } from './sidecar';
import {
    EMPTY_NOTIFICATION_HEALTH,
    markNotificationWarningShown,
    normalizeNotificationSeedSources,
    notificationMessage,
    notificationWarningMessage,
    planNotificationPoll,
    recordNotificationDegraded,
    recordNotificationFailure,
    recordNotificationSuccess,
    settleNotificationSources,
    type NotifySource,
    type NotificationSourceRequest,
    shouldWarnAboutNotificationFailure,
    type NotificationHealth,
} from './notifications';

const NOTIFY_STATE_KEY = 'pr-pilot.notifications.seenState';
const NOTIFY_HEALTH_KEY = 'pr-pilot.notifications.health';
const MAX_SEEN_NOTIFICATION_PRS = 500;

interface SeenState {
    /** Legacy all-sources seed written before per-source seeding was introduced. */
    seeded?: boolean;
    seededSources?: NotifySource[];
    seen: string[];
}

export class PRNotificationPoller implements vscode.Disposable {
    private timer: NodeJS.Timeout | null = null;
    private readonly seededSources: Set<NotifySource>;
    private readonly seen: Set<string>;
    private running = false;
    private health: NotificationHealth;

    constructor(
        private readonly context: vscode.ExtensionContext,
        private readonly sidecarClient: SidecarClient,
        private readonly getConfig: () => vscode.WorkspaceConfiguration,
        private readonly getGithubBaseUrl: () => string,
        private readonly onOpenPr: (pr: PR) => void,
    ) {
        const saved = context.globalState.get<SeenState>(NOTIFY_STATE_KEY);
        this.seededSources = new Set(
            normalizeNotificationSeedSources(saved?.seededSources, saved?.seeded === true),
        );
        this.seen = new Set(saved?.seen ?? []);
        this.health = {
            ...EMPTY_NOTIFICATION_HEALTH,
            ...context.globalState.get<NotificationHealth>(NOTIFY_HEALTH_KEY),
        };
    }

    /** Starts/restarts the timer for the current interval, preserving the existing seed. */
    syncFromSettings(): void {
        this.stop();
        if (!this.getConfig().get<boolean>('notificationsEnabled', false)) return;
        const minutes = Math.max(1, this.getConfig().get<number>('notificationPollMinutes', 5));
        void this.poll();
        this.timer = setInterval(() => void this.poll(), minutes * 60_000);
    }

    /** Clears the seed and restarts so a scope/host change re-seeds silently instead of flooding. */
    resetAndSync(): void {
        this.seededSources.clear();
        this.seen.clear();
        void this.persist();
        this.syncFromSettings();
    }

    dispose(): void {
        this.stop();
    }

    getHealth(): NotificationHealth {
        return { ...this.health };
    }

    retry(): Promise<void> {
        return this.poll();
    }

    private stop(): void {
        if (this.timer) clearInterval(this.timer);
        this.timer = null;
    }

    private persist(): Thenable<void> {
        return this.context.globalState.update(NOTIFY_STATE_KEY, {
            seededSources: [...this.seededSources],
            seen: [...this.seen],
        } satisfies SeenState);
    }

    private persistHealth(): Thenable<void> {
        return this.context.globalState.update(NOTIFY_HEALTH_KEY, this.health);
    }

    private async recordSuccess(): Promise<void> {
        this.health = recordNotificationSuccess(this.health);
        await this.persistHealth();
    }

    private async recordDegraded(message: string): Promise<void> {
        this.health = recordNotificationDegraded(this.health, message);
        this.warnIfNeeded();
        await this.persistHealth();
    }

    private warnIfNeeded(): void {
        if (!shouldWarnAboutNotificationFailure(this.health)) return;
        this.health = markNotificationWarningShown(this.health);
        void vscode.window.showWarningMessage(
            notificationWarningMessage(this.health),
            'Retry',
            'Open Settings',
        ).then((choice) => {
            if (choice === 'Retry') void this.retry();
            if (choice === 'Open Settings') {
                void vscode.commands.executeCommand('pr-pilot.openSettings');
            }
        });
    }

    private async poll(): Promise<void> {
        if (this.running) return;
        this.running = true;
        try {
            const requests: NotificationSourceRequest[] = [];
            const baseUrl = this.getGithubBaseUrl();

            if (this.getConfig().get<boolean>('notifyReviewRequested', true)) {
                requests.push({
                    source: 'reviewRequested',
                    load: async () => {
                        const result = await this.sidecarClient.searchPullRequests(
                            baseUrl, 'is:open is:pr draft:false review-requested:@me', 50);
                        if (result.status !== 'ok') throw new Error(result.message);
                        return result.prs.map((pr) => ({ ...pr, hasReviewDraft: false }));
                    },
                });
            }

            if (this.getConfig().get<boolean>('notifyStarredRepos', false)) {
                requests.push({
                    source: 'starredRepo',
                    load: async () => {
                        const reposResult = await this.sidecarClient.listStarredRepositories(baseUrl);
                        if (reposResult.status !== 'ok') throw new Error(reposResult.message);
                        const starredRepos = reposResult.repositories.slice(0, 25);
                        if (starredRepos.length === 0) return [];
                        const repoQ = starredRepos.map((repo) => `repo:${repo}`).join(' ');
                        const result = await this.sidecarClient.searchPullRequests(
                            baseUrl, `is:open is:pr draft:false ${repoQ}`, 50);
                        if (result.status !== 'ok') throw new Error(result.message);
                        return result.prs.map((pr) => ({ ...pr, hasReviewDraft: false }));
                    },
                });
            }

            const sourceResults = await settleNotificationSources(requests);
            const plan = planNotificationPoll(this.seededSources, this.seen, sourceResults);
            if (plan.status === 'failed') {
                throw new Error(plan.message || 'All notification sources failed');
            }
            this.seededSources.clear();
            for (const source of plan.seededSources) this.seededSources.add(source);
            this.seen.clear();
            for (const key of plan.seen) this.seen.add(key);

            for (const { pr, source } of plan.notifications) {
                void vscode.window.showInformationMessage(
                    notificationMessage(pr, source),
                    'Open in PR Pilot',
                ).then((choice) => {
                    if (choice === 'Open in PR Pilot') this.onOpenPr(pr);
                });
            }
            trimSeenSet(this.seen, MAX_SEEN_NOTIFICATION_PRS);
            await this.persist();
            if (plan.status === 'degraded') {
                await this.recordDegraded(plan.message || 'One notification source failed');
            } else {
                await this.recordSuccess();
            }
        } catch (err) {
            const message = err instanceof Error ? err.message : String(err);
            console.warn('[pr-pilot] PR notification poll failed:', message);
            this.health = recordNotificationFailure(this.health, message);
            this.warnIfNeeded();
            await Promise.resolve(this.persistHealth()).catch(() => undefined);
        } finally {
            this.running = false;
        }
    }
}

function trimSeenSet(seen: Set<string>, maxSize: number): void {
    if (seen.size <= maxSize) return;
    while (seen.size > maxSize) {
        const first = seen.values().next().value;
        if (!first) break;
        seen.delete(first);
    }
}
