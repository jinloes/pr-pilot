import * as vscode from 'vscode';
import { execFile } from 'node:child_process';
import * as path from 'path';
import * as fs from 'fs';
import { randomUUID } from 'crypto';
import { DeepReviewFlow } from './deepReview';
import * as claude from './claude';
import * as copilot from './copilot';
import * as settings from './settings';
import * as workspace from './workspace';
import { BRIDGE_PROTOCOL_VERSION, isValidBridgeRequest } from './bridgeValidation';
import { providerNotInstalledMessage } from './userFacingError';
import { resolveWebviewDistPath } from './webviewAssets';
import { buildErrorHtml, buildMainWebviewHtml } from './webviewHtml';
import { classifyHostTheme, type HostTheme } from './hostTheme';
import { SidecarClient, resolveSidecarJarPath } from './sidecar';
import { DraftRecoveryStore } from './draftRecovery';
import { probeClaudeAuthentication } from './providerSetup';
import type { PR, ReviewResult } from './models';
import { cancelThenCleanup, invalidateChatAndCancel, invalidateGenerationAndCancel } from './operationCorrelation';
import { normalizeRepositoryInstructions, repositoryKey } from './repositoryInstructions';
import { normalizeReviewGuidanceGlobs, resolveReviewGuidance } from './reviewGuidanceProfiles';
import { PRNotificationPoller } from './notificationPoller';
import { createWorktreeOperations } from './worktree';
import { createPrHandlers, type PrHandlerApi } from './prHandlers';
import { createReviewHandlers, type ReviewHandlerApi } from './reviewHandlers';
import { selectCopilotModel } from './copilotModel';
import type { ActivePR, HandlerDependencies, Provider, ProviderReadiness, ReviewGenerationSettings, ViewState } from './extensionTypes';

// Process-wide, lazily-started Java engine host. GitHub operations are never performed in the
// Node extension process, so missing Java/jar or transport failures surface as setup errors.
let sidecarClient: SidecarClient;

function provider(): Provider {
    const value = config().get<string>('reviewProvider', 'claude');
    return value === 'copilot' ? 'copilot' : 'claude';
}

async function cancelActiveProvider(operationId: string): Promise<void> {
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
    const viewProvider = new ClaudeReviewsViewProvider(context.extensionUri, new DraftRecoveryStore(context.globalState));
    const notificationPoller = new PRNotificationPoller(
        context,
        sidecarClient,
        config,
        githubBaseUrl,
        (pr) => viewProvider.openPullRequest(pr),
    );
    context.subscriptions.push(
        vscode.window.createTreeView('pr-pilot.main', { treeDataProvider: new EmptyLauncherTree() }),
        vscode.commands.registerCommand('pr-pilot.open', () => viewProvider.openPanel()),
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

export function deactivate() {
    sidecarClient?.dispose();
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

/** Provides the PR Pilot editor tab and bridges all messages in the editor tab. */
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
        if (hadLiveState) this.flushPendingActivation();
    }

    private initializeWebview(
        webview: vscode.Webview,
        onDidDispose: vscode.Event<void>,
        onDispose?: () => void,
    ): void {
        webview.html = this.getHtmlContent(webview);
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

        onDidDispose(() => {
            themeSubscription.dispose();
            state.disposed = true;
            state.deepReview.invalidate();
            state.generationRevision++;
            state.chatRevision++;
            const operationId = state.activeProviderOperation?.operationId;
            void cancelThenCleanup(
                operationId ? () => cancelActiveProvider(operationId) : () => Promise.resolve(),
                () => worktreeOperations.clearWorktree(state),
            );
            onDispose?.();
        });

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
        push(this.state, { type: 'activatePR', pr: activation.pr, source: activation.source });
    }

    private getHtmlContent(webview: vscode.Webview): string {
        const indexPath = path.join(this.distUri.fsPath, 'index.html');
        if (!fs.existsSync(indexPath)) {
            return buildErrorHtml('webview/dist/index.html not found. Run "npm run build" inside webview/.');
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
                case 'refreshPRs': await handleRefreshPRs(state, msg); break;
                case 'selectPR': await handleSelectPR(state, msg); break;
                case 'generateReview':
                    if (msg.intellijAssisted === true) await handlePrepareDeepReview(state, msg);
                    else { state.deepReview.invalidate(); await handleGenerateReview(state, msg); }
                    break;
                case 'saveRepositoryInstructions':
                    await handleSaveRepositoryInstructions(state, msg, (message) => push(state, message));
                    break;
                case 'continueDeepReview': await handleContinueDeepReview(state, msg); break;
                case 'listDeepReviews':
                case 'cleanupDeepReview': await handleDeepMaintenance(state, msg); break;
                case 'cancelReview':
                    state.deepReview.cancel(msg.operationId as string);
                    if (state.activeProviderOperation?.kind === 'review'
                        && state.activeProviderOperation.operationId === msg.operationId) {
                        await invalidateGenerationAndCancel(state, () => cancelActiveProvider(msg.operationId as string));
                    }
                    break;
                case 'saveDraft': await enqueueMutation(state, () => handleSaveDraft(state, msg)); break;
                case 'submitReview': await enqueueMutation(state, () => handleSubmitReview(state, msg)); break;
                case 'deleteDraft': await enqueueMutation(state, () => handleDeleteDraft(state, msg)); break;
                case 'askClaude': await handleAskClaude(state, msg); break;
                case 'clearChat': {
                    const ownsProvider = state.activeProviderOperation?.kind === 'chat'
                        && state.activeProviderOperation.operationId === msg.operationId;
                    await invalidateChatAndCancel(state, () => cancelActiveProvider(msg.operationId as string), ownsProvider);
                    const key = prKey(state.activePR);
                    if (key) state.chatHistory.delete(key);
                    break;
                }
                case 'cancelChat': await handleCancelChat(state, msg); break;
                case 'openUrl':
                    if (typeof msg.url === 'string' && msg.url.startsWith('https://')) {
                        void vscode.env.openExternal(vscode.Uri.parse(msg.url));
                    }
                    break;
                case 'openSettings': await vscode.commands.executeCommand('pr-pilot.openSettings'); break;
                case 'runAuthLogin': runAuthLoginInTerminal(); break;
                case 'webviewLayoutChanged': break;
                default: console.warn('[pr-pilot] unknown message type:', msg.type);
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

function providerReadiness(): ProviderReadiness {
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

async function providerSetupReadiness(): Promise<ProviderReadiness> {
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
        rulesDirectory: c.get<string>('reviewRulesDirectory', '').trim(),
        githubBaseUrl: c.get<string>('githubBaseUrl', 'https://github.com'),
        guidance,
    };
}

/** Formats a prior generated review as compact context for a re-generation prompt. */
function formatPriorReview(result: ReviewResult | null): string {
    if (!result) return '';
    const lines = [`Verdict: ${result.verdict}`];
    if (result.summary) lines.push(`Summary: ${result.summary}`);
    for (const c of result.lineComments) lines.push(`- ${c.file}:${c.line} [${c.type}] ${c.body}`);
    return lines.join('\n');
}

function workingDir(): string {
    return workspace.resolveWorkspaceDir(
        vscode.workspace.workspaceFolders?.map(folder => folder.uri.fsPath) ?? [],
    );
}

function rememberedRepositoryInstructions(owner: string, repo: string): string {
    const key = repositoryKey(owner, repo);
    if (!key) return '';
    return normalizeRepositoryInstructions(config().get<unknown>('repositoryReviewInstructions', {}))[key] ?? '';
}

const worktreeOperations = createWorktreeOperations({
    client: () => sidecarClient,
    workingDir,
    githubBaseUrl,
    push,
});

const handlerDependencies: HandlerDependencies = {
    client: () => sidecarClient,
    config,
    githubBaseUrl,
    provider,
    workingDir,
    providerReadiness,
    providerSetupReadiness,
    intellijAssistedEnabled: () => config().get<boolean>('experimentalIntellijAssistedReview', false) === true,
    rememberedRepositoryInstructions,
    snapshotReviewGenerationSettings,
    formatPriorReview,
    prKey,
    prKeyFromParts,
    push,
    enqueueMutation,
    clearWorktree: worktreeOperations.clearWorktree,
    resolveWorkingDir: worktreeOperations.resolveWorkingDir,
    cancelActiveProvider,
    freshDeepPr: (...args) => prHandlers.freshDeepPr(...args),
    handleGenerateReview: (...args) => reviewHandlers.handleGenerateReview(...args),
};
const prHandlers: PrHandlerApi = createPrHandlers(handlerDependencies);
const reviewHandlers: ReviewHandlerApi = createReviewHandlers(handlerDependencies);

// Compatibility wrappers keep the bridge handler names stable for existing host tests and callers.
function handleRefreshPRs(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    return prHandlers.handleRefreshPRs(state, msg);
}
function handleSelectPR(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    return prHandlers.handleSelectPR(state, msg);
}
function handlePrepareDeepReview(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    return prHandlers.handlePrepareDeepReview(state, msg);
}
function handleContinueDeepReview(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    return prHandlers.handleContinueDeepReview(state, msg);
}
function handleDeepMaintenance(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    return prHandlers.handleDeepMaintenance(state, msg);
}
function handleSaveRepositoryInstructions(
    state: Pick<ViewState, 'disposed'>,
    msg: Record<string, unknown>,
    pushMessage: (message: Record<string, unknown>) => void,
): Promise<void> {
    return prHandlers.handleSaveRepositoryInstructions(state, msg, pushMessage);
}
function handleGenerateReview(
    state: ViewState,
    msg: Record<string, unknown>,
    deep?: { pending: import('./deepReview').PreparedDeepReview; server: string },
): Promise<void> {
    return reviewHandlers.handleGenerateReview(state, msg, deep);
}
function handleSaveDraft(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    return reviewHandlers.handleSaveDraft(state, msg);
}
function handleSubmitReview(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    return reviewHandlers.handleSubmitReview(state, msg);
}
function handleDeleteDraft(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    return reviewHandlers.handleDeleteDraft(state, msg);
}
function handleCancelChat(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    return reviewHandlers.handleCancelChat(state, msg);
}
function handleAskClaude(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    return reviewHandlers.handleAskClaude(state, msg);
}
