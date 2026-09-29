import * as vscode from 'vscode';
import { execFile } from 'node:child_process';
import * as path from 'path';
import * as fs from 'fs';
import { randomUUID, createHash } from 'crypto';
import { DeepReviewFlow, type PreparedDeepReview } from './deepReview';
import * as claude from './claude';
import * as copilot from './copilot';
import * as settings from './settings';
import * as workspace from './workspace';
import { hasStaleCommits } from './draftState';
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
import { BRIDGE_PROTOCOL_VERSION, isValidBridgeRequest } from './bridgeValidation';
import {
    composeCustomInstructions,
    normalizeRepositoryInstructions,
    repositoryKey,
    withRepositoryInstructions,
} from './repositoryInstructions';
import { classifySetupAuthError } from './authError';
import { GitHubOperationError, toUserFacingError, providerNotInstalledMessage } from './userFacingError';
import { resolveWebviewDistPath } from './webviewAssets';
import { buildErrorHtml, buildMainWebviewHtml } from './webviewHtml';
import { classifyHostTheme, type HostTheme } from './hostTheme';
import { SidecarClient, resolveSidecarJarPath, type OutcomeComment } from './sidecar';
import { DraftRecoveryStore } from './draftRecovery';
import { probeClaudeAuthentication } from './providerSetup';
import type { LineComment, PR, PRSearchScope, ReviewResult } from './models';
import {
    canPersistDraft,
    cancelThenCleanup,
    cancelForSelection,
    invalidateChatAndCancel,
    invalidateGenerationAndCancel,
} from './operationCorrelation';
import {
    normalizeReviewGuidanceGlobs,
    resolveReviewGuidance,
    type ResolvedReviewGuidance,
} from './reviewGuidanceProfiles';

// Process-wide, lazily-started Java engine host. GitHub operations are never performed in the
// Node extension process, so missing Java/jar or transport failures surface as setup errors.
let sidecarClient: SidecarClient;

type Provider = 'claude' | 'copilot';

function provider(): Provider {
    const value = config().get<string>('reviewProvider', 'claude');
    return value === 'copilot' ? 'copilot' : 'claude';
}

async function cancelActiveProvider(operationId: string): Promise<void> {
    // Cancellation now happens on the sidecar side (it owns the active provider process).
    await sidecarClient.cancelReview(operationId).catch(() => undefined);
}

function newOperationId(): string {
    return randomUUID();
}

export function activate(context: vscode.ExtensionContext) {
    let retryPromptVisible = false;
    const showSidecarRetry = (failure: Error): void => {
        if (retryPromptVisible) return;
        retryPromptVisible = true;
        void vscode.window.showErrorMessage(failure.message, 'Retry').then((action) => {
            retryPromptVisible = false;
            if (action === 'Retry') {
                void sidecarClient.restart().catch((err: unknown) => {
                    showSidecarRetry(
                        err instanceof Error
                            ? err
                            : new Error('PR Pilot Java sidecar failed to start.'),
                    );
                });
            }
        }, () => {
            retryPromptVisible = false;
        });
    };
    sidecarClient = new SidecarClient(
        resolveSidecarJarPath(context.extensionUri.fsPath),
        undefined,
        undefined,
        undefined,
        showSidecarRetry,
    );
    context.subscriptions.push({ dispose: () => sidecarClient?.dispose() });
    void sidecarClient.initialize().catch((err: unknown) => {
        showSidecarRetry(
            err instanceof Error
                ? err
                : new Error('PR Pilot Java sidecar failed to start.'),
        );
    });
    const provider = new ClaudeReviewsViewProvider(context.extensionUri, new DraftRecoveryStore(context.globalState));
    const notificationPoller = new PRNotificationPoller(context, (pr) => provider.openPullRequest(pr));
    context.subscriptions.push(
        // An empty tree lets VS Code render the native welcome content contributed in package.json.
        vscode.window.createTreeView('pr-pilot.main', { treeDataProvider: new EmptyLauncherTree() }),
        vscode.commands.registerCommand('pr-pilot.open', () => provider.openPanel()),
        vscode.commands.registerCommand('pr-pilot.selectCopilotModel', selectCopilotModel),
        vscode.commands.registerCommand('pr-pilot.openSettings', () =>
            settings.openSettings(
                context,
                sidecarClient,
                () => notificationPoller.getHealth(),
                () => notificationPoller.retry(),
            )),
        notificationPoller,
    );
    notificationPoller.syncFromSettings();
    context.subscriptions.push(vscode.workspace.onDidChangeConfiguration((event) => {
        // A change to the notification scope (which PRs match) must re-seed silently so existing
        // PRs are not announced retroactively; an interval-only change keeps the current seed.
        if (event.affectsConfiguration('pr-pilot.notificationsEnabled')
            || event.affectsConfiguration('pr-pilot.notifyReviewRequested')
            || event.affectsConfiguration('pr-pilot.notifyStarredRepos')
            || event.affectsConfiguration('pr-pilot.githubBaseUrl')) {
            notificationPoller.resetAndSync();
        } else if (event.affectsConfiguration('pr-pilot.notificationPollMinutes')) {
            notificationPoller.syncFromSettings();
        }
    }));
}

/** Kept for backwards compatibility; model selection now lives in PR Pilot Settings. */
async function selectCopilotModel(): Promise<void> {
    await vscode.commands.executeCommand('pr-pilot.openSettings');
}

export function deactivate() {
    sidecarClient?.dispose();
}

// ── Background PR notifications ───────────────────────────────────────────────

/** globalState key for the persisted seen-PR set so notifications survive reloads/restarts. */
const NOTIFY_STATE_KEY = 'pr-pilot.notifications.seenState';
const NOTIFY_HEALTH_KEY = 'pr-pilot.notifications.health';
const MAX_SEEN_NOTIFICATION_PRS = 500;

interface SeenState {
    /** Legacy all-sources seed written before per-source seeding was introduced. */
    seeded?: boolean;
    seededSources?: NotifySource[];
    seen: string[];
}

class PRNotificationPoller implements vscode.Disposable {
    private timer: NodeJS.Timeout | null = null;
    private readonly seededSources: Set<NotifySource>;
    private readonly seen: Set<string>;
    private running = false;
    private health: NotificationHealth;

    constructor(
        private readonly context: vscode.ExtensionContext,
        private readonly onOpenPr: (pr: PR) => void,
    ) {
        // Restore prior seen state so a reload/restart does not silently re-seed and swallow PRs
        // that appeared while the window was closed.
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
        if (!config().get<boolean>('notificationsEnabled', false)) return;
        const minutes = Math.max(1, config().get<number>('notificationPollMinutes', 5));
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

            if (config().get<boolean>('notifyReviewRequested', true)) {
                requests.push({
                    source: 'reviewRequested',
                    load: async () => {
                        const result = await sidecarClient.searchPullRequests(
                            githubBaseUrl(), 'is:open is:pr draft:false review-requested:@me', 50);
                        if (result.status !== 'ok') throw new Error(result.message);
                        return result.prs.map((pr) => ({ ...pr, hasReviewDraft: false }));
                    },
                });
            }

            if (config().get<boolean>('notifyStarredRepos', false)) {
                requests.push({
                    source: 'starredRepo',
                    load: async () => {
                        const reposResult = await sidecarClient.listStarredRepositories(githubBaseUrl());
                        if (reposResult.status !== 'ok') throw new Error(reposResult.message);
                        const starredRepos = reposResult.repositories.slice(0, 25);
                        if (starredRepos.length === 0) return [];
                        const repoQ = starredRepos.map((repo) => `repo:${repo}`).join(' ');
                        const result = await sidecarClient.searchPullRequests(
                            githubBaseUrl(), `is:open is:pr draft:false ${repoQ}`, 50);
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

// ── State per webview view ─────────────────────────────────────────────────────

interface ActivePR {
    number: number;
    owner: string;
    repo: string;
    title: string;
    body: string;
}

/** Has no items, so the Activity Bar view shows only its native `viewsWelcome` content. */
class EmptyLauncherTree implements vscode.TreeDataProvider<never> {
    getTreeItem(element: never): vscode.TreeItem {
        return element;
    }

    getChildren(): never[] {
        return [];
    }
}

/**
 * Provides the PR Pilot editor tab.
 * Serves the pre-built webview/dist/ React app and bridges all messages in the editor tab.
 */
class ClaudeReviewsViewProvider {
    private readonly distUri: vscode.Uri;
    private panel: vscode.WebviewPanel | undefined;
    private state: ViewState | undefined;
    private pendingActivation: { pr: PR; source: 'notification' } | null = null;

    constructor(
        extensionUri: vscode.Uri,
        private readonly draftRecoveryStore: DraftRecoveryStore,
    ) {
        this.distUri = vscode.Uri.file(resolveWebviewDistPath(extensionUri.fsPath, fs.existsSync));
    }

    openPanel(): void {
        if (this.panel) {
            this.panel.reveal(vscode.ViewColumn.Active);
            return;
        }

        const panel = vscode.window.createWebviewPanel(
            'pr-pilot.main',
            'PR Pilot',
            vscode.ViewColumn.Active,
            {
                enableScripts: true,
                retainContextWhenHidden: true,
                localResourceRoots: [this.distUri],
            },
        );
        this.panel = panel;
        this.initializeWebview(panel.webview, panel.onDidDispose, () => {
            if (this.state?.webview === panel.webview) this.state = undefined;
            if (this.panel === panel) this.panel = undefined;
        });
    }

    openPullRequest(pr: PR): void {
        this.pendingActivation = { pr, source: 'notification' };
        const hadLiveState = Boolean(this.state);
        this.openPanel();
        if (hadLiveState) {
            this.flushPendingActivation();
        }
    }

    private initializeWebview(
        webview: vscode.Webview,
        onDidDispose: vscode.Event<void>,
        onDispose?: () => void,
    ): void {
        webview.html = this.getHtmlContent(webview);

        // Per-view state — each panel gets its own instance of these fields
        const state: ViewState = {
            webview,
            prStateFilter: 'open',
            searchScope: 'currentRepo',
            activePR: null,
            activeDiff: '',
            activeValidationDiff: '',
            activeReviewResult: null,
            pendingReviewId: null,
            pendingReviewKey: null,
            selectionRevision: 0,
            refreshRevision: 0,
            generationRevision: 0,
            chatRevision: 0,
            activeProviderOperation: null,
            generatedReviews: new Map(),
            mutationQueue: Promise.resolve(),
            chatHistory: new Map(),
            worktreeDir: null,
            gitRoot: null,
            worktreeKey: null,
            worktreeEpoch: 0,
            worktreeCreation: null,
            disposed: false,
            deepReview: new DeepReviewFlow(),
            draftRecoveryStore: this.draftRecoveryStore,
        };
        this.state = state;

        this.setupMessageBridge(state);
        const themeSubscription = vscode.window.onDidChangeActiveColorTheme((theme) => {
            push(state, { type: 'themeChanged', theme: mapHostThemeKind(theme.kind) });
        });
        push(state, { type: 'themeChanged', theme: mapHostThemeKind(vscode.window.activeColorTheme.kind) });

        // Tear down any PR-branch worktree when the view is disposed so we don't leak
        // temp directories or detached worktrees registered against the user's repo.
        onDidDispose(() => {
            themeSubscription.dispose();
            state.disposed = true;
            state.deepReview.invalidate();
            state.generationRevision++;
            state.chatRevision++;
            const operationId = state.activeProviderOperation?.operationId;
            void cancelThenCleanup(
                operationId ? () => cancelActiveProvider(operationId) : () => Promise.resolve(),
                () => clearWorktree(state),
            );
            onDispose?.();
        });

        // Trigger initial PR load so the user sees results (or setup guidance) immediately
        // rather than an indefinite loading spinner.
        handleRefreshPRs(state, {})
            .catch(console.error)
            .finally(() => {
                push(state, { type: 'themeChanged', theme: mapHostThemeKind(vscode.window.activeColorTheme.kind) });
                this.flushPendingActivation();
            });
    }

    private flushPendingActivation(): void {
        if (!this.state || !this.pendingActivation) return;
        const activation = this.pendingActivation;
        this.pendingActivation = null;
        push(this.state, {
            type: 'activatePR',
            pr: activation.pr,
            source: activation.source,
        });
    }

    private getHtmlContent(webview: vscode.Webview): string {
        const indexPath = path.join(this.distUri.fsPath, 'index.html');
        if (!fs.existsSync(indexPath)) {
            return buildErrorHtml(
                'webview/dist/index.html not found. Run "npm run build" inside webview/.',
            );
        }
        const html = fs.readFileSync(indexPath, 'utf8');
        return buildMainWebviewHtml(
            html,
            webview.cspSource,
            (assetPath) => webview.asWebviewUri(vscode.Uri.joinPath(this.distUri, assetPath)).toString(),
        );
    }

    private setupMessageBridge(state: ViewState): void {
        state.webview.onDidReceiveMessage(async (msg: { type?: string } & Record<string, unknown>) => {
            if (!isValidBridgeRequest(msg)) {
                console.warn('[pr-pilot] invalid bridge payload:', msg);
                return;
            }
            switch (msg.type) {
                case 'refreshPRs':
                    await handleRefreshPRs(state, msg);
                    break;
                case 'selectPR':
                    await handleSelectPR(state, msg);
                    break;
                case 'generateReview':
                    if (msg.intellijAssisted === true) await handlePrepareDeepReview(state, msg);
                    else { state.deepReview.invalidate(); await handleGenerateReview(state, msg); }
                    break;
                case 'saveRepositoryInstructions':
                    await handleSaveRepositoryInstructions(state, msg, (message) => push(state, message));
                    break;
                case 'continueDeepReview':
                    await handleContinueDeepReview(state, msg);
                    break;
                case 'listDeepReviews':
                case 'cleanupDeepReview':
                    await handleDeepMaintenance(state, msg);
                    break;
                case 'cancelReview':
                    state.deepReview.cancel(msg.operationId as string);
                    if (state.activeProviderOperation?.kind === 'review'
                        && state.activeProviderOperation.operationId === msg.operationId) {
                        await invalidateGenerationAndCancel(
                            state,
                            () => cancelActiveProvider(msg.operationId as string),
                        );
                    }
                    break;
                case 'saveDraft':
                    await enqueueMutation(state, () => handleSaveDraft(state, msg));
                    break;
                case 'submitReview':
                    await enqueueMutation(state, () => handleSubmitReview(state, msg));
                    break;
                case 'deleteDraft':
                    await enqueueMutation(state, () => handleDeleteDraft(state, msg));
                    break;
                case 'askClaude':
                    await handleAskClaude(state, msg);
                    break;
                case 'clearChat': {
                    const ownsProvider = state.activeProviderOperation?.kind === 'chat'
                        && state.activeProviderOperation.operationId === msg.operationId;
                    await invalidateChatAndCancel(
                        state,
                        () => cancelActiveProvider(msg.operationId as string),
                        ownsProvider,
                    );
                    const key = prKey(state.activePR);
                    if (key) state.chatHistory.delete(key);
                    break;
                }
                case 'cancelChat':
                    await handleCancelChat(state, msg);
                    break;
                case 'openUrl':
                    if (typeof msg.url === 'string' && msg.url.startsWith('https://')) {
                        void vscode.env.openExternal(vscode.Uri.parse(msg.url));
                    }
                    break;
                case 'openSettings':
                    await vscode.commands.executeCommand('pr-pilot.openSettings');
                    break;
                case 'runAuthLogin':
                    runAuthLoginInTerminal();
                    break;
                case 'webviewLayoutChanged':
                    break;
                default:
                    console.warn('[pr-pilot] unknown message type:', msg.type);
            }
        });
    }
}

export function mapHostThemeKind(kind: vscode.ColorThemeKind): HostTheme {
    const highContrast = kind === vscode.ColorThemeKind.HighContrast
        || kind === vscode.ColorThemeKind.HighContrastLight;
    const dark = kind !== vscode.ColorThemeKind.Light
        && kind !== vscode.ColorThemeKind.HighContrastLight;
    return classifyHostTheme(dark, highContrast);
}

function runAuthLoginInTerminal(): void {
    const terminal = vscode.window.createTerminal({ name: 'PR Pilot Setup' });
    terminal.show(true);
    terminal.sendText('gh auth login', true);
}

// ── Per-view state ─────────────────────────────────────────────────────────────

interface ViewState {
    webview: vscode.Webview;
    prStateFilter: string;
    searchScope: PRSearchScope;
    activePR: ActivePR | null;
    activeDiff: string;
    activeValidationDiff: string;
    activeReviewResult: ReviewResult | null;
    pendingReviewId: string | null;
    pendingReviewKey: string | null;
    selectionRevision: number;
    refreshRevision: number;
    generationRevision: number;
    chatRevision: number;
    activeProviderOperation: { kind: 'review' | 'chat'; revision: number; operationId: string } | null;
    generatedReviews: Map<string, {
        result: ReviewResult;
        editedResult: ReviewResult | null;
        attribution: { provider: Provider; model: string; reviewSupervisorEnabled: boolean };
    }>;
    mutationQueue: Promise<void>;
    chatHistory: Map<string, claude.ChatMessage[]>;
    // PR-branch worktree, lazily created on first review/chat for the active PR and reused until
    // the PR changes or the view is disposed. Mirrors WebviewPanel.java's activePr* fields.
    worktreeDir: string | null;
    gitRoot: string | null;
    worktreeKey: string | null;
    worktreeEpoch: number;
    worktreeCreation: { key: string; promise: Promise<string> } | null;
    disposed: boolean;
    deepReview: DeepReviewFlow;
    draftRecoveryStore: DraftRecoveryStore;
}

function providerReadiness(): {
    provider: Provider;
    available: boolean;
    detail: string;
    binaryStatus: 'ready' | 'missing';
    authenticationStatus: 'ready' | 'unavailable' | 'unverified';
    authCommand: string;
} {
    const current = provider();
    const available = current === 'copilot' ? copilot.copilotBinaryAvailable() : claude.claudeBinaryAvailable();
    return {
        provider: current,
        available,
        binaryStatus: available ? 'ready' : 'missing',
        authenticationStatus: available ? 'unverified' : 'unavailable',
        authCommand: current === 'copilot' ? 'copilot login' : 'claude auth login',
        detail: available
            ? 'Provider CLI found. Authentication cannot be verified without starting a provider session.'
            : providerNotInstalledMessage(current),
    };
}

async function providerSetupReadiness(): Promise<ReturnType<typeof providerReadiness>> {
    const readiness = providerReadiness();
    if (!readiness.available || readiness.provider === 'copilot') return readiness;
    const authenticationStatus = await probeClaudeAuthentication((complete) => {
        execFile(
            claude.findClaudeBinary(),
            ['auth', 'status', '--text'],
            { timeout: 5_000, windowsHide: true },
            (error, stdout, stderr) => complete(error, String(stdout), String(stderr)),
        );
    });
    return {
        ...readiness,
        authenticationStatus,
        detail: authenticationStatus === 'ready'
            ? 'Provider CLI and authentication are ready.'
            : authenticationStatus === 'unavailable'
                ? `Claude authentication is unavailable. Run '${readiness.authCommand}' and check again.`
                : `Claude authentication could not be verified non-interactively; run '${readiness.authCommand}' if sign-in is required.`,
    };
}

function prKey(pr: ActivePR | null): string | null {
    return pr ? `${pr.owner}/${pr.repo}#${pr.number}` : null;
}

function prKeyFromParts(number: number, owner: string, repo: string): string {
    return `${owner}/${repo}#${number}`;
}

function worktreeKey(pr: ActivePR): string {
    return `${pr.owner.toLowerCase()}/${pr.repo.toLowerCase()}#${pr.number}`;
}

function isSameActivePR(state: ViewState, pr: ActivePR): boolean {
    const active = state.activePR;
    return !!active
        && active.number === pr.number
        && active.owner.toLowerCase() === pr.owner.toLowerCase()
        && active.repo.toLowerCase() === pr.repo.toLowerCase();
}

/** Removes the active PR worktree (if any) and clears the cached fields. Non-blocking cleanup. */
function clearWorktree(state: ViewState): void {
    const wt = state.worktreeDir;
    const root = state.gitRoot;
    state.worktreeDir = null;
    state.gitRoot = null;
    state.worktreeKey = null;
    state.worktreeEpoch++;
    state.worktreeCreation = null;
    if (wt && root) {
        void sidecarClient.removeWorktree(root, wt);
    }
}

/**
 * Resolves the working directory for a review/chat against `pr`. Asks the engine for a detached git
 * worktree pinned to the PR's head commit so the CLI reads exactly the code under review — not the
 * branch tip, which can move mid-review — then caches it for reuse. The operation fails closed when
 * a worktree cannot be created, preventing an AI provider from reading an unrelated open checkout.
 *
 * The git work lives in `review-engine`'s `GitWorktreeService` behind the `worktrees` capability,
 * so this host holds only the cache and the fallback decision. Mirrors
 * WebviewPanel.resolvePrClaudeService. Only builds a worktree when the open workspace is the same
 * repo as the PR — the worktree shares that repo's local object store.
 */
async function resolveWorkingDir(
    state: ViewState,
    pr: ActivePR,
    emitStatus: boolean,
    bridgePrKey?: string,
): Promise<string> {
    const fallback = workingDir();
    const key = worktreeKey(pr);
    if (state.worktreeDir && state.worktreeKey === key) return state.worktreeDir;
    if (!fallback) throw new Error('Open the pull request repository before starting a review or chat.');

    if (state.worktreeCreation?.key === key) return state.worktreeCreation.promise;
    const epoch = state.worktreeEpoch;
    const promise = createWorkingDir(state, pr, fallback, key, epoch, emitStatus, bridgePrKey);
    state.worktreeCreation = { key, promise };
    try {
        return await promise;
    } finally {
        if (state.worktreeCreation?.promise === promise) state.worktreeCreation = null;
    }
}

async function createWorkingDir(
    state: ViewState,
    pr: ActivePR,
    fallback: string,
    key: string,
    epoch: number,
    emitStatus: boolean,
    bridgePrKey?: string,
): Promise<string> {

    const gitRoot = await sidecarClient.findGitRoot(fallback);
    const currentRepo = await sidecarClient.detectRepo(fallback);
    const sameRepo = currentRepo !== null
        && currentRepo.toLowerCase() === `${pr.owner}/${pr.repo}`.toLowerCase();
    if (!gitRoot || !sameRepo) {
        throw new Error('Open the pull request repository before starting a review or chat.');
    }

    if (emitStatus) {
        push(state, { type: 'reviewGenerating', prKey: bridgePrKey, message: 'Preparing PR branch…' });
    }

    try {
        const detailResult = await sidecarClient.getPullRequestDetail(githubBaseUrl(), pr.owner, pr.repo, pr.number);
        if (detailResult.status !== 'ok' || !detailResult.detail) throw new Error(detailResult.message);
        const head = detailResult.detail.head;
        if (!head?.ref.trim()) throw new Error('Unable to determine the pull request branch.');
        const isFork = !!head.repoFullName && head.repoFullName !== detailResult.detail.baseRepoFullName;

        const created = await sidecarClient.createWorktree(
            gitRoot,
            pr.number,
            head.ref,
            head.sha ?? '',
            isFork ? head.cloneUrl ?? '' : '',
        );
        if (created.status !== 'created') {
            throw new Error(created.message || 'Unable to create an isolated pull request worktree.');
        }

        // The PR may have changed while we awaited git; discard the worktree if so.
        if (state.disposed || state.worktreeEpoch !== epoch || !isSameActivePR(state, pr)) {
            void sidecarClient.removeWorktree(gitRoot, created.worktreeDir);
            throw new Error('The selected pull request changed while its worktree was being prepared.');
        }

        state.worktreeDir = created.worktreeDir;
        state.gitRoot = gitRoot;
        state.worktreeKey = key;
        return created.worktreeDir;
    } catch (err) {
        console.warn(`[pr-pilot] Worktree creation for PR #${pr.number} failed:`,
            err instanceof Error ? err.message : String(err));
        throw err;
    }
}

function push(state: ViewState, msg: object): void {
    state.webview.postMessage({ protocolVersion: BRIDGE_PROTOCOL_VERSION, ...msg });
}

function enqueueMutation(state: ViewState, action: () => Promise<void>): Promise<void> {
    const queued = state.mutationQueue.then(action, action);
    state.mutationQueue = queued.catch(() => undefined);
    return queued;
}

function config(): vscode.WorkspaceConfiguration {
    return vscode.workspace.getConfiguration('pr-pilot');
}

function githubBaseUrl(): string {
    return config().get<string>('githubBaseUrl', 'https://github.com');
}

interface ReviewGenerationSettings {
    provider: Provider;
    model: string;
    effort: string;
    inheritMcp: boolean;
    configDir: string;
    selfCritique: boolean;
    supervisorEnabled: boolean;
    /** Optional parallel Copilot reviewer; blank disables it. */
    secondReviewerModel: string;
    githubBaseUrl: string;
    guidance: ResolvedReviewGuidance;
}

function snapshotReviewGenerationSettings(): ReviewGenerationSettings {
    const c = config();
    const selectedProvider: Provider = c.get<string>('reviewProvider', 'claude') === 'copilot'
        ? 'copilot'
        : 'claude';
    const effort = c.get<string>('reviewEffort', 'high').trim() || 'high';
    const guidanceGlobs = normalizeReviewGuidanceGlobs(c.get<unknown>('reviewGuidanceGlobs', [])) ?? [];
    const guidance = resolveReviewGuidance(
        c.get<unknown>('reviewGuidanceProfiles', []),
        c.get<unknown>('activeReviewGuidanceProfileId', ''),
        {
            focusAreas: c.get<string>('reviewFocusAreas', '').trim(),
            customInstructions: c.get<string>('reviewCustomInstructions', '').trim(),
            guidanceGlobs,
        },
    );
    return {
        provider: selectedProvider,
        model: c.get<string>(selectedProvider === 'copilot' ? 'reviewModelCopilot' : 'reviewModel', ''),
        effort,
        inheritMcp: selectedProvider === 'copilot'
            ? copilot.resolveReviewInheritMcp(
                c.get<boolean>('copilotInheritMcp', false),
                c.get<boolean>('copilotAutoEnableMcpOnReview', false),
            )
            : false,
        configDir: selectedProvider === 'copilot' ? c.get<string>('copilotConfigDir', '').trim() : '',
        selfCritique: c.get<boolean>('reviewSelfCritique', true),
        supervisorEnabled: c.get<boolean>('reviewSupervisorEnabled', true),
        secondReviewerModel: c.get<string>('reviewSecondReviewerModel', '').trim(),
        githubBaseUrl: c.get<string>('githubBaseUrl', 'https://github.com'),
        guidance,
    };
}

/** Formats a prior generated review as compact context for a re-generation prompt. */
function formatPriorReview(result: ReviewResult | null): string {
    if (!result) return '';
    const lines = [`Verdict: ${result.verdict}`];
    if (result.summary) lines.push(`Summary: ${result.summary}`);
    for (const c of result.lineComments) {
        lines.push(`- ${c.file}:${c.line} [${c.type}] ${c.body}`);
    }
    return lines.join('\n');
}

function workingDir(): string {
    return workspace.resolveWorkspaceDir(
        vscode.workspace.workspaceFolders?.map(folder => folder.uri.fsPath) ?? [],
    );
}

// ── Message handlers ───────────────────────────────────────────────────────────

async function handleRefreshPRs(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    const refreshRevision = ++state.refreshRevision;
    try {
        if (typeof msg.state === 'string') state.prStateFilter = msg.state;
        if (typeof msg.searchScope === 'string') {
            state.searchScope = normalizeSearchScope(msg.searchScope);
        } else if (typeof msg.assignedToMe === 'boolean' && msg.assignedToMe) {
            state.searchScope = 'assigned';
        } else if (typeof msg.reviewRequested === 'boolean' && msg.reviewRequested) {
            state.searchScope = 'reviewRequested';
        }

        const prStateFilter = state.prStateFilter;
        const searchScope = state.searchScope;
        const baseUrl = githubBaseUrl();

        const currentRepo = await sidecarClient.detectRepo(workingDir() || process.cwd());
        const found = await sidecarClient.listPullRequests(
            baseUrl, prStateFilter, searchScope, currentRepo ?? undefined);
        if (state.refreshRevision !== refreshRevision || state.disposed) return;
        if (found.status !== 'ok') throw new Error(found.message);
        const prs = found.prs.map((item) => ({ ...item, hasReviewDraft: false })).map((pr) => {
            if (!state.activePR || !state.pendingReviewId) return pr;
            return pr.number === state.activePR.number && pr.owner === state.activePR.owner && pr.repo === state.activePR.repo
                ? { ...pr, hasReviewDraft: true }
                : pr;
        });
        prs.sort((a, b) => b.createdAt.localeCompare(a.createdAt));
        const readiness = await providerSetupReadiness();
        if (!readiness.available) {
            push(state, {
                type: 'setupRequired',
                reason: 'provider_not_installed',
                detail: readiness.detail,
                providerReadiness: readiness,
            });
            return;
        }
        if (readiness.authenticationStatus === 'unavailable') {
            push(state, {
                type: 'setupRequired',
                reason: 'provider_not_authenticated',
                detail: readiness.detail,
                providerReadiness: readiness,
            });
            return;
        }
        push(state, {
            type: 'prListLoaded',
            prs,
            defaultRepo: currentRepo ?? undefined,
            listStatus: {
                searchScope,
                currentRepo: currentRepo ?? undefined,
                resultLimit: found.resultLimit,
                limited: found.limited,
                reviewStatusAvailable: found.reviewStatusAvailable,
            },
            providerReadiness: readiness,
            intellijAssistedEnabled: intellijAssistedEnabled(),
        });
    } catch (err) {
        if (state.refreshRevision !== refreshRevision || state.disposed) return;
        const reason = classifySetupAuthError(err);
        const detail = reason === 'gh_not_installed'
            ? "The 'gh' CLI was not found. Install it from https://cli.github.com, then run 'gh auth login' in a terminal and click Refresh."
            : reason === 'gh_not_authenticated'
                ? "Run 'gh auth login' in a terminal to authenticate, then click Refresh."
                : toUserFacingError(err, 'load pull requests');
        push(state, {
            type: 'setupRequired',
            reason,
            detail,
            providerReadiness: providerReadiness(),
        });
    }
}

function normalizeSearchScope(value: string): PRSearchScope {
    if (value === 'authored' || value === 'assigned' || value === 'reviewRequested') return value;
    return 'currentRepo';
}

async function handleSelectPR(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    const number = msg.number as number;
    const owner = msg.owner as string;
    const repo = msg.repo as string;
    if (!number || !owner || !repo) return;
    const key = prKeyFromParts(number, owner, repo);
    const selectionRevision = ++state.selectionRevision;
    const title = typeof msg.title === 'string' ? msg.title : '';
    const body = typeof msg.body === 'string' ? msg.body : '';

    state.generationRevision++;
    state.chatRevision++;
    const operationId = state.activeProviderOperation?.operationId;
    if (!await cancelForSelection(
        state,
        selectionRevision,
        operationId ? () => cancelActiveProvider(operationId) : () => Promise.resolve(),
    )) return;
    clearWorktree(state);
    state.activePR = { number, owner, repo, title, body };
    state.activeDiff = '';
    state.activeValidationDiff = '';
    state.activeReviewResult = null;
    state.pendingReviewId = null;
    state.pendingReviewKey = null;
    push(state, { type: 'draftLoading', prKey: key });
    try {
        const base = githubBaseUrl();
        const readiness = providerReadiness();

        const [diffResult, detailResult, draftResult] = await Promise.all([
            sidecarClient.getPullRequestDiff(base, owner, repo, number, 'review'),
            sidecarClient.getPullRequestDetail(base, owner, repo, number),
            sidecarClient.getDraftReview(base, owner, repo, number),
        ]);
        if (diffResult.status !== 'ok' || diffResult.diff === null) {
            throw new GitHubOperationError(diffResult.status, diffResult.message);
        }
        if (detailResult.status !== 'ok' || !detailResult.detail) throw new Error(detailResult.message);
        if (draftResult.status !== 'ok' && draftResult.status !== 'none') throw new Error(draftResult.message);
        const diff = diffResult.diff;
        const detail = detailResult.detail;
        const remoteDraft = draftResult.status === 'ok' && draftResult.review ? {
            id: draftResult.id ?? '',
            commitId: draftResult.commitId ?? '',
            result: draftResult.review as ReviewResult,
            importedFromGitHub: draftResult.review.importedFromGitHub,
        } : null;
        const recovery = state.draftRecoveryStore.get(key);
        const draft = recovery ? {
            id: remoteDraft?.id ?? '',
            commitId: remoteDraft?.commitId ?? '',
            result: recovery.result,
            importedFromGitHub: false,
            recoveryPending: true,
        } : remoteDraft ? { ...remoteDraft, recoveryPending: false } : null;
        const validationDiff = diff;

        if (prKey(state.activePR) !== key || state.selectionRevision !== selectionRevision) return;

        state.activePR = {
            number,
            owner,
            repo,
            title: detail.title ?? title,
            body: detail.body ?? body,
        };
        state.activeDiff = diff;
        state.activeValidationDiff = validationDiff;
        state.activeReviewResult = draft?.result ?? null;
        state.pendingReviewId = draft?.id || null;
        state.pendingReviewKey = draft?.id ? key : null;

        if (detail.merged) {
            await state.draftRecoveryStore.clear(key);
            push(state, { type: 'prDraftStatusUpdated', number, owner, repo, hasReviewDraft: false });
            push(state, { type: 'draftLoaded', prKey: key, prState: 'MERGED', diff, validationDiff, providerReadiness: readiness,
                intellijAssistedEnabled: intellijAssistedEnabled(),
                repositoryInstructions: rememberedRepositoryInstructions(owner, repo) });
        } else if (draft) {
            const staleCommits = hasStaleCommits(draft.commitId, detail.head?.sha ?? '');
            push(state, { type: 'prDraftStatusUpdated', number, owner, repo, hasReviewDraft: true });
            push(state, {
                type: 'draftLoaded',
                prKey: key,
                prState: 'DRAFT_PRESENT',
                reviewId: draft.id,
                result: draft.result,
                diff,
                validationDiff,
                staleCommits,
                importedFromGitHub: draft.importedFromGitHub,
                recoveryPending: draft.recoveryPending,
                providerReadiness: readiness,
                intellijAssistedEnabled: intellijAssistedEnabled(),
                repositoryInstructions: rememberedRepositoryInstructions(owner, repo),
            });
        } else {
            push(state, { type: 'prDraftStatusUpdated', number, owner, repo, hasReviewDraft: false });
            push(state, { type: 'draftLoaded', prKey: key, prState: 'NO_DRAFT', diff, validationDiff, providerReadiness: readiness,
                intellijAssistedEnabled: intellijAssistedEnabled(),
                repositoryInstructions: rememberedRepositoryInstructions(owner, repo) });
        }

        // Comment-position validation benefits from an untruncated diff, but it must not block the
        // draft status UI on a large or slow response. The bounded review diff above is sufficient
        // until this optional fetch completes.
        void sidecarClient.getPullRequestDiff(base, owner, repo, number, 'validation')
            .then((fullResult) => {
                if (fullResult.status !== 'ok' || fullResult.diff === null) return;
                if (prKey(state.activePR) === key && state.selectionRevision === selectionRevision) {
                    state.activeValidationDiff = fullResult.diff || diff;
                    push(state, {
                        type: 'validationDiffUpdated',
                        prKey: key,
                        validationDiff: state.activeValidationDiff,
                    });
                }
            })
            .catch((err) => {
                console.warn(`[pr-pilot] Full validation diff for PR #${number} failed; using review diff:`,
                    err instanceof Error ? err.message : String(err));
            });
    } catch (err) {
        if (prKey(state.activePR) !== key || state.selectionRevision !== selectionRevision) return;
        push(state, {
            type: 'draftLoaded',
            prKey: key,
            prState: 'NO_DRAFT',
            status: toUserFacingError(err, 'load PR details'),
            providerReadiness: providerReadiness(),
            intellijAssistedEnabled: intellijAssistedEnabled(),
            repositoryInstructions: rememberedRepositoryInstructions(owner, repo),
        });
    }
}

async function freshDeepPr(number: number, owner: string, repo: string) {
    const first = await sidecarClient.getPullRequestDetail(githubBaseUrl(), owner, repo, number);
    const diff = await sidecarClient.getPullRequestDiff(githubBaseUrl(), owner, repo, number, 'validation');
    const last = await sidecarClient.getPullRequestDetail(githubBaseUrl(), owner, repo, number);
    if (first.status !== 'ok' || last.status !== 'ok' || !first.detail?.head?.sha
        || first.detail.head.sha !== last.detail?.head?.sha
        || diff.status !== 'ok' || !diff.diff) {
        throw new Error('Cannot bind a fresh PR head and diff. Prepare again.');
    }
    return { detail: last.detail, diff: diff.diff };
}

function deepReviewError(error: unknown): string {
    return (error instanceof Error ? error.message : 'IntelliJ-assisted review could not continue.').slice(0, 4096);
}

async function handleDeepMaintenance(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    if (state.disposed) return;
    try {
        if (msg.type === 'cleanupDeepReview') {
            await sidecarClient.cleanupDeepReview(msg.retainedId as string, msg.projectClosed === true);
        }
        push(state, { type: 'retainedDeepReviews', operationId: msg.operationId,
            retained: await sidecarClient.listDeepReviews() });
    } catch (error) {
        push(state, { type: 'deepReviewMaintenanceError', operationId: msg.operationId,
            message: deepReviewError(error) });
    }
}

/** Experimental opt-in; while off, the webview hides the IntelliJ-assisted controls. */
function intellijAssistedEnabled(): boolean {
    return config().get<boolean>('experimentalIntellijAssistedReview', false) === true;
}

/** Instructions the user asked PR Pilot to remember for `owner/repo`; empty when none. */
function rememberedRepositoryInstructions(owner: string, repo: string): string {
    const key = repositoryKey(owner, repo);
    if (!key) return '';
    return normalizeRepositoryInstructions(config().get<unknown>('repositoryReviewInstructions', {}))[key] ?? '';
}

export async function handleSaveRepositoryInstructions(
    state: Pick<ViewState, 'disposed'>,
    msg: Record<string, unknown>,
    pushMessage: (message: Record<string, unknown>) => void,
    configuration: Pick<vscode.WorkspaceConfiguration, 'get' | 'update'> = config(),
): Promise<void> {
    const number = msg.number as number;
    const owner = msg.owner as string;
    const repo = msg.repo as string;
    const key = prKeyFromParts(number, owner, repo);
    const repository = repositoryKey(owner, repo);
    const fail = (message: string) =>
        pushMessage({ type: 'repositoryInstructionsSaveError', prKey: key, message });
    if (!repository) {
        fail('This repository name cannot be remembered.');
        return;
    }
    const current = normalizeRepositoryInstructions(configuration.get<unknown>('repositoryReviewInstructions', {}));
    const next = withRepositoryInstructions(
        current, repository, typeof msg.instructions === 'string' ? msg.instructions : '');
    if (!next) {
        fail('Repository instructions are limited to 10,000 characters and 200 repositories.');
        return;
    }
    try {
        await configuration.update('repositoryReviewInstructions', next, vscode.ConfigurationTarget.Global);
    } catch (err) {
        console.warn('[pr-pilot] saving repository instructions failed:', err instanceof Error ? err.message : String(err));
        if (!state.disposed) fail('Could not save PR Pilot settings. Try again.');
        return;
    }
    if (!state.disposed) {
        pushMessage({ type: 'repositoryInstructionsSaved', prKey: key, instructions: next[repository] ?? '' });
    }
}

const INTELLIJ_ASSISTED_DISABLED_ERROR =
    'IntelliJ-assisted review is disabled; enable it in PR Pilot settings (experimental)';

async function handlePrepareDeepReview(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    const number = msg.number as number;
    const owner = msg.owner as string;
    const repo = msg.repo as string;
    const key = prKeyFromParts(number, owner, repo);
    if (!intellijAssistedEnabled()) {
        // Reject rather than downgrade: an ordinary review must be an explicit user choice.
        push(state, { type: 'reviewError', prKey: key, message: INTELLIJ_ASSISTED_DISABLED_ERROR });
        return;
    }
    const revision = state.deepReview.start(msg.operationId as string);
    const selectionRevision = state.selectionRevision;
    const configuration = JSON.stringify(snapshotReviewGenerationSettings());
    const current = () => !state.disposed && prKey(state.activePR) === key
        && state.selectionRevision === selectionRevision && state.deepReview.isCurrent(revision);
    try {
        const previousOperationId = state.activeProviderOperation?.operationId;
        await invalidateGenerationAndCancel(state, () => previousOperationId
            ? cancelActiveProvider(previousOperationId) : Promise.resolve());
        const root = await sidecarClient.findGitRoot(workingDir());
        const repository = await sidecarClient.detectRepo(workingDir());
        if (!root || repository?.toLowerCase() !== `${owner}/${repo}`.toLowerCase()) {
            throw new Error('Open the pull request repository before preparing a deep review.');
        }
        const fresh = await freshDeepPr(number, owner, repo);
        if (!current()) return;
        const head = fresh.detail.head!;
        const preparation = await sidecarClient.prepareDeepReview({
            operationId: msg.operationId as string, gitRoot: root, prNumber: number,
            branch: head.ref, headSha: head.sha,
            forkCloneUrl: head.repoFullName !== fresh.detail.baseRepoFullName ? head.cloneUrl ?? '' : '',
            prIdentity: `${owner}/${repo}#${number}`,
            diffDigest: createHash('sha256').update(fresh.diff).digest('hex'),
        });
        const pending: PreparedDeepReview = { preparation, diff: fresh.diff, prKey: key,
            settings: configuration, options: { ...msg }, selectionRevision };
        if (!current() || configuration !== JSON.stringify(snapshotReviewGenerationSettings())
            || !state.deepReview.install(revision, pending)) return;
        push(state, { type: 'deepReviewPrepared', prKey: key, operationId: msg.operationId, ...preparation,
            message: 'Open this exact worktree in IntelliJ. Enable MCP, import Gradle, then Continue. The first Continue may arm tracking and require one manual sync.' });
    } catch (error) {
        if (current()) push(state, { type: 'reviewError', prKey: key,
            message: deepReviewError(error) });
    }
}

async function handleContinueDeepReview(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    const key = prKeyFromParts(msg.number as number, msg.owner as string, msg.repo as string);
    const pendingAtStart = state.deepReview.peek();
    if (state.disposed || prKey(state.activePR) !== key || !pendingAtStart
        || pendingAtStart.selectionRevision !== state.selectionRevision
        || pendingAtStart.preparation.retainedId !== msg.retainedId
        || state.deepReview.hasConsumed(msg.operationId as string)) return;
    const generationRevision = state.generationRevision;
    const current = () => !state.disposed && prKey(state.activePR) === key
        && state.selectionRevision === pendingAtStart.selectionRevision
        && state.generationRevision === generationRevision
        && (state.deepReview.peek() === pendingAtStart
            || state.deepReview.isOperationCurrent(msg.operationId as string));
    let refreshing = false;
    try {
        const pending = state.deepReview.consume(msg.retainedId, msg.operationId as string, key,
            JSON.stringify(snapshotReviewGenerationSettings()), state.selectionRevision, msg.server as string);
        refreshing = true;
        const fresh = await freshDeepPr(msg.number as number, msg.owner as string, msg.repo as string);
        refreshing = false;
        if (!current()) return;
        if (fresh.detail.head?.sha !== pending.preparation.head || fresh.diff !== pending.diff
            || pending.settings !== JSON.stringify(snapshotReviewGenerationSettings())) {
            throw new Error('The PR head or diff changed. Prepare a new deep review; the old tree is retained.');
        }
        await handleGenerateReview(state, { ...pending.options, operationId: msg.operationId, diff: pending.diff },
            { pending, server: msg.server as string });
    } catch (error) {
        if (current()) {
            if (refreshing && pendingAtStart.settings === JSON.stringify(snapshotReviewGenerationSettings())) {
                state.deepReview.retry(pendingAtStart);
            }
            push(state, { type: 'reviewError', prKey: key, message: deepReviewError(error) });
        }
    }
}

async function handleGenerateReview(state: ViewState, msg: Record<string, unknown>,
    deep?: { pending: PreparedDeepReview; server: string }): Promise<void> {
    const number = msg.number as number;
    const owner = msg.owner as string;
    const repo = msg.repo as string;
    if (!number || !owner || !repo) return;
    const key = prKeyFromParts(number, owner, repo);
    if (prKey(state.activePR) !== key) {
        push(state, { type: 'reviewError', prKey: key, message: 'The selected pull request changed. Try again.' });
        return;
    }
    const operationId = msg.operationId as string;
    const previousOperationId = state.activeProviderOperation?.operationId;
    const selectionRevision = state.selectionRevision;
    const generationRevision = ++state.generationRevision;
    const activePr = state.activePR;
    const settings = {
        ...snapshotReviewGenerationSettings(),
        rememberedRepositoryInstructions: rememberedRepositoryInstructions(owner, repo),
    };
    const priorReview = formatPriorReview(state.activeReviewResult);
    const isCurrentGeneration = () =>
        !state.disposed
        && prKey(state.activePR) === key
        && state.selectionRevision === selectionRevision
        && state.generationRevision === generationRevision;
    const providerOperation = {
        kind: 'review' as const,
        revision: generationRevision,
        operationId,
    };
    state.activeProviderOperation = providerOperation;
    if (previousOperationId && previousOperationId !== operationId) {
        await cancelActiveProvider(previousOperationId);
    }

    // Provider preflight: fail fast with actionable guidance instead of a raw CLI spawn error
    // when the configured review provider's binary isn't installed/resolvable.
    const isCopilot = settings.provider === 'copilot';
    if (isCopilot ? !copilot.copilotBinaryAvailable() : !claude.claudeBinaryAvailable()) {
        push(state, {
            type: 'reviewError',
            prKey: key,
            message: providerNotInstalledMessage(isCopilot ? 'copilot' : 'claude'),
        });
        if (state.activeProviderOperation === providerOperation) state.activeProviderOperation = null;
        return;
    }

    push(state, { type: 'reviewGenerating', prKey: key, message: 'Fetching PR data…' });
    try {
        const base = settings.githubBaseUrl;

        const requestedDiff = typeof msg.diff === 'string' && msg.diff.trim() ? msg.diff : undefined;
        let diff = requestedDiff ?? state.activeDiff;
        if (!diff) {
            const result = await sidecarClient.getPullRequestDiff(base, owner, repo, number, 'review');
            if (!result || result.status !== 'ok' || result.diff === null) {
                throw new GitHubOperationError(
                    result?.status ?? 'invalid_response',
                    result?.message ?? 'Invalid sidecar diff response.',
                );
            }
            diff = result.diff;
            if (!isCurrentGeneration()) return;
            state.activeDiff = diff;
        }
        let resolvedValidationDiff = state.activeValidationDiff;
        let reviewResultPublished = false;
        const validationDiffPromise = resolvedValidationDiff
            ? Promise.resolve(resolvedValidationDiff)
            : sidecarClient.getPullRequestDiff(base, owner, repo, number, 'validation')
                .then((result) => result?.status === 'ok' && result.diff !== null ? result.diff : diff)
                .catch(() => diff);
        void validationDiffPromise.then((fullDiff) => {
            if (!isCurrentGeneration()) return;
            resolvedValidationDiff = fullDiff;
            state.activeValidationDiff = fullDiff;
            if (reviewResultPublished) {
                push(state, { type: 'validationDiffUpdated', prKey: key, validationDiff: fullDiff });
            }
        });

        const reviewsResult = await sidecarClient.getExistingReviews(base, owner, repo, number).catch(() => null);
        const existingReviews = reviewsResult?.status === 'ok' ? reviewsResult.summary : '';

        const title = activePr?.title ?? '';
        const body = activePr?.body ?? '';

        const reviewDir = deep?.pending.preparation.worktree ?? await resolveWorkingDir(
            state,
            { number, owner, repo, title, body },
            true,
            key,
        );
        if (!isCurrentGeneration()) return;

        // Phase 1 prompt context. Fetched in parallel and best-effort: every one of these degrades
        // to an omitted prompt section rather than failing the review, so none is awaited
        // individually or allowed to reject. Mirrors WebviewPanel's context block.
        const revisions = await sidecarClient
            .getPullRequestDetail(base, owner, repo, number)
            .then((d) => (d.status === 'ok'
                ? { headSha: d.detail?.head?.sha ?? '', baseSha: d.detail?.baseSha ?? '' }
                : { headSha: '', baseSha: '' }))
            .catch(() => ({ headSha: '', baseSha: '' }));
        const headSha = revisions.headSha;
        const commitsPromise = sidecarClient.getCommits(base, owner, repo, number);
        const [checkStatus, commits, linkedIssue, repoProfile] = await Promise.all([
            headSha
                ? sidecarClient.getCheckStatus(base, owner, repo, headSha)
                : Promise.resolve({ summary: '', annotations: [] }),
            commitsPromise,
            commitsPromise.then((commitContext) =>
                sidecarClient.getLinkedIssues(
                    base,
                    owner,
                    repo,
                    body,
                    commitContext.closingIssueNumbers,
                )),
            sidecarClient.getRepoProfile(reviewDir),
        ]);
        if (!isCurrentGeneration()) return;

        // Per-review overrides from the webview take precedence over the saved settings defaults.
        const guidance = settings.guidance;
        const focusAreas = typeof msg.focusAreas === 'string' && msg.focusAreas.trim()
            ? msg.focusAreas.trim()
            : guidance.focusAreas;
        const customInstructions = composeCustomInstructions(
            `${owner}/${repo}`,
            settings.rememberedRepositoryInstructions,
            typeof msg.customInstructions === 'string' && msg.customInstructions.trim()
                ? msg.customInstructions.trim()
                : guidance.customInstructions,
        );

        // Prompt construction happens sidecar-side (shared review-engine ClaudeService/CopilotService);
        // the extension only supplies raw PR/diff/context fields.
        let result: ReviewResult | null;
        try {
            result = await sidecarClient.generateReview(
                {
                operationId,
                deepReview: deep ? { retainedId: deep.pending.preparation.retainedId, server: deep.server } : undefined,
                provider: settings.provider,
                projectDir: reviewDir,
                model: settings.model,
                effort: settings.effort,
                inheritMcp: settings.inheritMcp,
                configDir: isCopilot ? settings.configDir : undefined,
                selfCritique: settings.selfCritique,
                reviewSupervisorEnabled: settings.supervisorEnabled,
                secondReviewerModel: settings.secondReviewerModel || undefined,
                baseSha: revisions.baseSha || undefined,
                chunkedReview: msg.chunkedReview === true,
                pr: {
                    title,
                    // htmlUrl/author/createdAt/isDraft aren't used by ClaudeService/CopilotService's
                    // prompt building (see review-engine ClaudeService.buildPrompt) — ActivePR
                    // doesn't track them, so placeholders are supplied to satisfy the sidecar's
                    // PrParams shape without any loss of behavior.
                    htmlUrl: '',
                    owner,
                    repo,
                    number,
                    body,
                    author: '',
                    createdAt: '',
                    isDraft: false,
                },
                diff,
                priorReview,
                existingReviews,
                // Guidance in the PR worktree is authored by the change under review, so the host
                // never reads it; the engine resolves guidance and file history from baseSha.
                repoGuidelines: '',
                focusAreas,
                customInstructions,
                ciStatus: checkStatus.summary,
                commits: commits.summary,
                linkedIssue,
                repoProfile,
                ciAnnotations: checkStatus.annotations.map((a) => ({
                    file: a.path,
                    line: a.startLine,
                    level: a.level,
                    message: a.message,
                })),
                },
                (status) => {
                    if (isCurrentGeneration()) push(state, { type: 'reviewGenerating', prKey: key, message: status });
                },
                (kind, chunk) => {
                    if (isCurrentGeneration()) push(state, { type: 'reviewChunk', prKey: key, kind, chunk });
                },
            );
        } finally {
            if (state.activeProviderOperation === providerOperation) state.activeProviderOperation = null;
        }

        if (!result) throw new Error('Provider produced no output.');
        if (deep) {
            const fresh = await freshDeepPr(number, owner, repo);
            if (fresh.detail.head?.sha !== deep.pending.preparation.head || fresh.diff !== deep.pending.diff
                || deep.pending.settings !== JSON.stringify(snapshotReviewGenerationSettings())) {
                throw new Error('Deep review identity changed before delivery. Output was not published.');
            }
        }
        if (!isCurrentGeneration()) return;
        state.activeReviewResult = result;
        state.generatedReviews.set(key, {
            result,
            editedResult: null,
            attribution: {
                provider: settings.provider,
                model: settings.model,
                reviewSupervisorEnabled: settings.supervisorEnabled,
            },
        });
        push(state, {
            type: 'reviewResult',
            prKey: key,
            result,
            diff,
            validationDiff: resolvedValidationDiff ?? diff,
        });
        reviewResultPublished = true;
    } catch (err) {
        if (isCancellationError(err) || !isCurrentGeneration()) return;
        if (deep) {
            state.deepReview.retry(deep.pending);
            push(state, { type: 'deepReviewPrepared', operationId, prKey: key, ...deep.pending.preparation,
                message: deepReviewError(err) });
            return;
        }
        push(state, { type: 'reviewError', prKey: key, message: toUserFacingError(err, 'generate review') });
    }
}

async function handleSaveDraft(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    const number = msg.number as number;
    const owner = msg.owner as string;
    const repo = msg.repo as string;
    const saveId = msg.saveId as number;
    const resultFromMsg = msg.result as ReviewResult | undefined;
    const generatedResultFromMsg = msg.generatedResult as ReviewResult | undefined;
    const orphansFromMsg = (msg.orphans as LineComment[] | undefined) ?? [];
    const review = resultFromMsg ?? state.activeReviewResult;
    if (!number || !owner || !repo || !review) return;
    const key = prKeyFromParts(number, owner, repo);
    const activeAtStart = prKey(state.activePR) === key;
    if (!canPersistDraft(activeAtStart, resultFromMsg !== undefined)) {
        push(state, { type: 'draftSaveError', prKey: key, saveId, message: 'The selected pull request changed before the draft could be saved.' });
        return;
    }
    const selectionRevision = state.selectionRevision;

    try {
        await state.draftRecoveryStore.save(key, review, orphansFromMsg);
        const mutation = await sidecarClient.saveDraftReview(
            githubBaseUrl(), owner, repo, number, review.summary, review.verdict,
            review.lineComments, orphansFromMsg,
        );
        if (mutation.status !== 'ok' || !mutation.reviewId) throw new Error(mutation.message);
        const { reviewId, commentsDropped } = mutation;
        await state.draftRecoveryStore.clear(key);
        const tracked = state.generatedReviews.get(key);
        if (tracked) {
            state.generatedReviews.set(key, {
                ...tracked,
                result: generatedResultFromMsg ?? tracked.result,
                editedResult: review,
            });
        }
        if (prKey(state.activePR) !== key || state.selectionRevision !== selectionRevision) return;
        state.activeReviewResult = review;
        state.pendingReviewId = reviewId;
        state.pendingReviewKey = key;
        push(state, { type: 'draftSaved', prKey: key, saveId, reviewId, commentsDropped });
        push(state, { type: 'prDraftStatusUpdated', number, owner, repo, hasReviewDraft: true });
    } catch (err) {
        if (prKey(state.activePR) !== key || state.selectionRevision !== selectionRevision) return;
        push(state, {
            type: 'draftSaveError',
            prKey: key,
            saveId,
            message: toUserFacingError(err, 'save draft review'),
        });
    }
}

async function handleSubmitReview(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    const number = msg.number as number;
    const owner = msg.owner as string;
    const repo = msg.repo as string;
    const verdict = msg.verdict as string;
    const comment = msg.comment as string ?? '';
    if (!number || !owner || !repo || !verdict) return;
    const key = prKeyFromParts(number, owner, repo);
    // Always notify the webview when there is no usable draft to submit — the webview has
    // already flipped into a "submitting" spinner state before sending this message, so a
    // silent return here leaves the UI stuck forever with no way to recover.
    if (prKey(state.activePR) !== key || state.pendingReviewKey !== key || !state.pendingReviewId) {
        push(state, { type: 'reviewSubmitError', prKey: key, message: 'No pending draft review belongs to the selected pull request.' });
        return;
    }
    const reviewId = state.pendingReviewId;
    const selectionRevision = state.selectionRevision;

    try {
        const mutation = await sidecarClient.submitReview(
            githubBaseUrl(), owner, repo, number, reviewId, verdict, comment);
        if (mutation.status !== 'ok') throw new Error(mutation.message);
        await state.draftRecoveryStore.clear(key);
        if (prKey(state.activePR) !== key || state.selectionRevision !== selectionRevision) return;
        void recordReviewOutcome(state, key);
        if (state.pendingReviewId === reviewId && state.pendingReviewKey === key) {
            state.pendingReviewId = null;
            state.pendingReviewKey = null;
        }
        push(state, { type: 'reviewSubmitted', prKey: key });
        push(state, { type: 'prDraftStatusUpdated', number, owner, repo, hasReviewDraft: false });
    } catch (err) {
        if (prKey(state.activePR) !== key || state.selectionRevision !== selectionRevision) return;
        push(state, {
            type: 'reviewSubmitError',
            prKey: key,
            message: toUserFacingError(err, 'submit draft review'),
        });
    }
}

/**
 * Logs what the reviewer did with each generated comment. Deliberately not awaited: the review has
 * already been submitted, so instrumentation must not delay the UI or be able to fail the submit.
 * A no-op when no generated review is held — a draft loaded from GitHub in a later session was
 * never generated locally, so there is nothing to compare it against.
 *
 * Mirrors WebviewPanel.recordReviewOutcome.
 */
async function recordReviewOutcome(state: ViewState, key: string): Promise<void> {
    const tracked = state.generatedReviews.get(key);
    if (!tracked) return;
    state.generatedReviews.delete(key);
    const generated = tracked.result;
    const submitted = tracked.editedResult ?? generated;
    const toOutcome = (comments: LineComment[] | undefined): OutcomeComment[] =>
        (comments ?? []).map((c) => ({
            file: c.file,
            line: c.line,
            type: c.type,
            body: c.body,
            severity: c.severity,
            confidence: c.confidence,
        }));
    await sidecarClient.recordReviewOutcome(
        tracked.attribution.provider,
        tracked.attribution.model,
        tracked.attribution.reviewSupervisorEnabled,
        toOutcome(generated.lineComments),
        toOutcome(submitted.lineComments),
    );
}

async function handleDeleteDraft(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    const number = msg.number as number;
    const owner = msg.owner as string;
    const repo = msg.repo as string;
    if (!number || !owner || !repo) return;
    const key = prKeyFromParts(number, owner, repo);
    // Same reasoning as handleSubmitReview: the webview is already showing a "deleting"
    // spinner, so any early exit here must push an error or the UI hangs forever.
    if (prKey(state.activePR) !== key || state.pendingReviewKey !== key || !state.pendingReviewId) {
        push(state, { type: 'draftDeleteError', prKey: key, message: 'The pending draft does not belong to the selected pull request.' });
        return;
    }
    const reviewId = state.pendingReviewId;
    const selectionRevision = state.selectionRevision;

    try {
        const mutation = await sidecarClient.deleteDraftReview(
            githubBaseUrl(), owner, repo, number, reviewId);
        if (mutation.status !== 'ok') throw new Error(mutation.message);
        await state.draftRecoveryStore.clear(key);
        state.generatedReviews.delete(key);
        if (prKey(state.activePR) !== key || state.selectionRevision !== selectionRevision) return;
        if (state.pendingReviewId === reviewId && state.pendingReviewKey === key) {
            state.pendingReviewId = null;
            state.pendingReviewKey = null;
        }
        push(state, { type: 'draftDeleted', prKey: key });
        push(state, { type: 'prDraftStatusUpdated', number, owner, repo, hasReviewDraft: false });
    } catch (err) {
        if (prKey(state.activePR) !== key || state.selectionRevision !== selectionRevision) return;
        push(state, {
            type: 'draftDeleteError',
            prKey: key,
            message: toUserFacingError(err, 'delete draft review'),
        });
    }
}

/**
 * Stops the chat answer the webview is waiting for without deleting conversation history. Only the
 * operation that owns the provider is cancelled; the revision bump drops its late output silently.
 */
async function handleCancelChat(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    const ownsProvider = state.activeProviderOperation?.kind === 'chat'
        && state.activeProviderOperation.operationId === msg.operationId;
    if (!ownsProvider) return;
    await invalidateChatAndCancel(state, () => cancelActiveProvider(msg.operationId as string));
}

async function handleAskClaude(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    const context = msg.context as string ?? '';
    const question = msg.question as string ?? '';
    if (!question.trim()) return;

    const key = prKey(state.activePR);
    const activePr = state.activePR;
    const selectionRevision = state.selectionRevision;
    const chatRevision = ++state.chatRevision;
    const history = key ? [...(state.chatHistory.get(key) ?? [])] : [];
    const prContext = buildPrContext(state);
    const c = config();
    const selectedProvider: Provider = c.get<string>('reviewProvider', 'claude') === 'copilot'
        ? 'copilot'
        : 'claude';
    const effort = c.get<string>('reviewEffort', 'high').trim() || 'high';
    const inheritMcp = selectedProvider === 'copilot'
        ? c.get<boolean>('copilotInheritMcp', false)
        : false;
    const configDir = selectedProvider === 'copilot'
        ? c.get<string>('copilotConfigDir', '').trim()
        : undefined;
    const isCurrentChat = () =>
        !state.disposed
        && state.chatRevision === chatRevision
        && state.selectionRevision === selectionRevision
        && prKey(state.activePR) === key;
    const operationId = msg.operationId as string;
    const previousOperationId = state.activeProviderOperation?.operationId;
    const providerOperation = {
        kind: 'chat' as const,
        revision: chatRevision,
        operationId,
    };
    state.activeProviderOperation = providerOperation;

    // Focused chat builds its (small) prompt client-side, matching IntelliJ's
    // IntellijClaudeService.chatFocused; regular chat sends raw context/history and lets the
    // shared review-engine service build the full prompt server-side.
    const focused = context.trim().length > 0;
    const rawPrompt = focused ? claude.buildFocusedChatPrompt(context, question) : undefined;

    try {
        if (previousOperationId && previousOperationId !== operationId) {
            await cancelActiveProvider(previousOperationId);
        }
        // Reuse the PR-branch worktree if one was built for the active PR (e.g. during review) so
        // chat sees the same source.
        const chatDir = activePr
            ? await resolveWorkingDir(state, activePr, false)
            : workingDir();
        if (!isCurrentChat()) return;
        let response: string;
        try {
            response = await sidecarClient.chatReview(
                {
                operationId,
                provider: selectedProvider,
                projectDir: chatDir,
                effort,
                inheritMcp,
                configDir,
                ...(focused
                    ? { rawPrompt }
                    : { prContext, history, userMessage: question }),
                },
                (chunk) => {
                    if (isCurrentChat()) push(state, { type: 'chatChunk', prKey: key ?? undefined, chunk });
                },
            );
        } finally {
            if (state.activeProviderOperation === providerOperation) state.activeProviderOperation = null;
        }
        if (!isCurrentChat()) return;
        if (key) {
            state.chatHistory.set(key, [
                ...history,
                { role: 'USER', content: question },
                { role: 'ASSISTANT', content: response },
            ]);
        }
        push(state, { type: 'chatResponse', prKey: key ?? undefined, response });
    } catch (err) {
        if (isCancellationError(err) || !isCurrentChat()) return;
        push(state, { type: 'chatError', prKey: key ?? undefined, message: toUserFacingError(err, 'answer chat question') });
    }
}

// ── Utilities ──────────────────────────────────────────────────────────────────

function buildPrContext(state: ViewState): string {
    if (!state.activePR) return '';
    const pr = state.activePR;
    const lines = [
        `PR #${pr.number}: ${pr.title}`,
        `Repo: ${pr.owner}/${pr.repo}`,
    ];
    if (state.activeReviewResult) {
        const r = state.activeReviewResult;
        lines.push('', `Review verdict: ${r.verdict}`);
        if (r.summary) lines.push(`Summary: ${r.summary}`);
    }
    if (state.activeDiff) {
        // Pass the full diff (already capped at 250 KB by getPRDiff) for parity with the IntelliJ
        // host, which also sends the complete diff as chat context.
        lines.push('', 'Diff:', state.activeDiff);
    }
    return lines.join('\n');
}

function errorMessage(err: unknown): string {
    return err instanceof Error ? err.message : String(err);
}

function isCancellationError(err: unknown): boolean {
    return errorMessage(err).toLowerCase().includes('cancel');
}
