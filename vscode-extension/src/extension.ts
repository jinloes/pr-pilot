import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import * as github from './github';
import * as claude from './claude';
import * as copilot from './copilot';
import * as worktree from './worktree';
import * as settings from './settings';
import { COPILOT_MODEL_SUGGESTIONS } from './settingsView';
import { classifySetupAuthError } from './authError';
import { toUserFacingError } from './userFacingError';

type Provider = 'claude' | 'copilot';

function provider(): Provider {
    const value = config().get<string>('reviewProvider', 'claude');
    return value === 'copilot' ? 'copilot' : 'claude';
}

function cancelActiveProvider(): void {
    // Cancel both — only one has an active process at any time, but reading the provider
    // setting here can race with a config change so we just send the signal to both.
    claude.cancelCurrentRequest();
    copilot.cancelCurrentRequest();
}

export function activate(context: vscode.ExtensionContext) {
    const provider = new ClaudeReviewsViewProvider(context.extensionUri);
    const notificationPoller = new PRNotificationPoller();
    context.subscriptions.push(
        vscode.window.registerWebviewViewProvider('pr-pilot.main', provider, {
            webviewOptions: { retainContextWhenHidden: true },
        }),
        vscode.commands.registerCommand('pr-pilot.selectCopilotModel', selectCopilotModel),
        vscode.commands.registerCommand('pr-pilot.openSettings', () => settings.openSettings(context)),
        notificationPoller,
    );
    notificationPoller.syncFromSettings();
    context.subscriptions.push(vscode.workspace.onDidChangeConfiguration((event) => {
        if (event.affectsConfiguration('pr-pilot.notificationsEnabled')
            || event.affectsConfiguration('pr-pilot.notifyReviewRequested')
            || event.affectsConfiguration('pr-pilot.notifyStarredRepos')
            || event.affectsConfiguration('pr-pilot.notificationPollMinutes')
            || event.affectsConfiguration('pr-pilot.githubBaseUrl')) {
            notificationPoller.syncFromSettings();
        }
    }));
}

/**
 * Recent Copilot CLI model IDs offered when discovery is unavailable. Intentionally short; the
 * SDK probe is the source of truth. Shared with the settings webview (see settingsView.ts).
 */

/**
 * Quick-pick command to set `pr-pilot.reviewModelCopilot`. VS Code settings are declarative JSON
 * and cannot self-populate an enum from a runtime probe, so model discovery is surfaced as a
 * command instead. Probes the SDK for available models, falls back to hardcoded suggestions, and
 * always offers a freeform entry plus a "CLI default" option. Mirrors the IntelliJ settings combo.
 */
async function selectCopilotModel(): Promise<void> {
    const current = config().get<string>('reviewModelCopilot', '');

    const discovered = await vscode.window.withProgress(
        { location: vscode.ProgressLocation.Notification, title: 'PR Pilot: discovering Copilot models…' },
        () => copilot.listModels(true),
    );
    const modelIds = discovered.length > 0 ? discovered : COPILOT_MODEL_SUGGESTIONS;

    interface ModelPick extends vscode.QuickPickItem { value?: string; custom?: boolean }
    const items: ModelPick[] = [
        { label: 'CLI default', description: "Empty — use the Copilot CLI's own routing", value: '' },
        ...modelIds.map((id): ModelPick => ({
            label: id,
            description: id === current ? '$(check) current' : undefined,
            value: id,
        })),
        { label: '$(pencil) Enter a model ID…', custom: true },
    ];

    const picked = await vscode.window.showQuickPick(items, {
        title: discovered.length > 0
            ? 'Select Copilot review model'
            : 'Select Copilot review model (discovery unavailable — showing suggestions)',
        placeHolder: current ? `Current: ${current}` : 'Current: CLI default',
    });
    if (!picked) return;

    let value: string;
    if (picked.custom) {
        const entered = await vscode.window.showInputBox({
            title: 'Copilot model ID',
            value: current,
            prompt: 'Run `copilot help config` to list supported IDs. Leave empty for the CLI default.',
        });
        if (entered === undefined) return;
        value = entered.trim();
    } else {
        value = picked.value ?? '';
    }

    await config().update('reviewModelCopilot', value, vscode.ConfigurationTarget.Global);
    vscode.window.showInformationMessage(
        value ? `PR Pilot: Copilot review model set to ${value}` : 'PR Pilot: Copilot review model set to CLI default',
    );
}

export function deactivate() {}

// ── Background PR notifications ───────────────────────────────────────────────

class PRNotificationPoller implements vscode.Disposable {
    private timer: NodeJS.Timeout | null = null;
    private seeded = false;
    private readonly seen = new Set<string>();
    private running = false;

    syncFromSettings(): void {
        this.stop();
        if (!config().get<boolean>('notificationsEnabled', false)) return;
        const minutes = Math.max(1, config().get<number>('notificationPollMinutes', 5));
        void this.poll();
        this.timer = setInterval(() => void this.poll(), minutes * 60_000);
    }

    dispose(): void {
        this.stop();
    }

    private stop(): void {
        if (this.timer) clearInterval(this.timer);
        this.timer = null;
    }

    private async poll(): Promise<void> {
        if (this.running) return;
        this.running = true;
        try {
            const token = await github.resolveToken(githubBaseUrl());
            const found: github.PR[] = [];

            if (config().get<boolean>('notifyReviewRequested', true)) {
                found.push(...await github.searchPRsByQuery(
                    token,
                    githubBaseUrl(),
                    'is:open is:pr draft:false review-requested:@me',
                ));
            }

            if (config().get<boolean>('notifyStarredRepos', false)) {
                const starred = (await github.getStarredRepos(token, githubBaseUrl())).slice(0, 25);
                if (starred.length > 0) {
                    const repoQ = starred.map((repo) => `repo:${repo}`).join(' ');
                    found.push(...await github.searchPRsByQuery(
                        token,
                        githubBaseUrl(),
                        `is:open is:pr draft:false ${repoQ}`,
                    ));
                }
            }

            const unique = dedupePRs(found);
            if (!this.seeded) {
                for (const pr of unique) this.seen.add(prNotificationKey(pr));
                this.seeded = true;
                return;
            }

            for (const pr of unique) {
                const key = prNotificationKey(pr);
                if (this.seen.has(key)) continue;
                this.seen.add(key);
                void vscode.window.showInformationMessage(
                    `${pr.owner}/${pr.repo} #${pr.number}: ${pr.title}`,
                    'Open PR',
                ).then((choice) => {
                    if (choice === 'Open PR') void vscode.env.openExternal(vscode.Uri.parse(pr.htmlUrl));
                });
            }

            const liveKeys = new Set(unique.map(prNotificationKey));
            for (const key of Array.from(this.seen)) {
                if (!liveKeys.has(key)) this.seen.delete(key);
            }
        } catch (err) {
            console.warn('[pr-pilot] PR notification poll failed:', err instanceof Error ? err.message : String(err));
        } finally {
            this.running = false;
        }
    }
}

function prNotificationKey(pr: github.PR): string {
    return `${pr.owner}/${pr.repo}#${pr.number}`;
}

function dedupePRs(prs: github.PR[]): github.PR[] {
    const seen = new Set<string>();
    const result: github.PR[] = [];
    for (const pr of prs) {
        const key = prNotificationKey(pr);
        if (seen.has(key)) continue;
        seen.add(key);
        result.push(pr);
    }
    return result;
}

// ── State per webview view ─────────────────────────────────────────────────────

interface ActivePR {
    number: number;
    owner: string;
    repo: string;
    title: string;
    body: string;
}

/**
 * Provides the Claude Reviews webview view in the activity bar.
 * Serves the pre-built webview/dist/ React app and bridges all messages.
 */
class ClaudeReviewsViewProvider implements vscode.WebviewViewProvider {

    constructor(private readonly extensionUri: vscode.Uri) {}

    resolveWebviewView(
        webviewView: vscode.WebviewView,
        _context: vscode.WebviewViewResolveContext,
        _token: vscode.CancellationToken,
    ): void {
        const distUri = vscode.Uri.joinPath(this.extensionUri, '..', 'webview', 'dist');

        webviewView.webview.options = {
            enableScripts: true,
            localResourceRoots: [distUri],
        };

        webviewView.webview.html = this.getHtmlContent(webviewView.webview, distUri);

        // Per-view state — each panel gets its own instance of these fields
        const state: ViewState = {
            webview: webviewView.webview,
            cachedToken: null,
            prStateFilter: 'open',
            searchScope: 'currentRepo',
            activePR: null,
            activeDiff: '',
            activeReviewResult: null,
            pendingReviewId: null,
            chatHistory: new Map(),
            worktreeDir: null,
            gitRoot: null,
            worktreeKey: null,
        };

        this.setupMessageBridge(state);

        // Tear down any PR-branch worktree when the view is disposed so we don't leak
        // temp directories or detached worktrees registered against the user's repo.
        webviewView.onDidDispose(() => clearWorktree(state));

        // Trigger initial PR load so the user sees results (or setup guidance) immediately
        // rather than an indefinite loading spinner.
        handleRefreshPRs(state, {}).catch(console.error);
    }

    private getHtmlContent(webview: vscode.Webview, distUri: vscode.Uri): string {
        const indexPath = path.join(distUri.fsPath, 'index.html');
        if (!fs.existsSync(indexPath)) {
            return errorHtml('webview/dist/index.html not found. Run "npm run build" inside webview/.');
        }
        let html = fs.readFileSync(indexPath, 'utf8');
        html = html.replace(/(src|href)="\.\/([^"]+)"/g, (_m, attr, p) =>
            `${attr}="${webview.asWebviewUri(vscode.Uri.joinPath(distUri, p)).toString()}"`);
        html = html.replace(/(src|href)="\/([^"]+)"/g, (_m, attr, p) =>
            `${attr}="${webview.asWebviewUri(vscode.Uri.joinPath(distUri, p)).toString()}"`);
        return html;
    }

    private setupMessageBridge(state: ViewState): void {
        state.webview.onDidReceiveMessage(async (msg: { type?: string } & Record<string, unknown>) => {
            if (!msg || typeof msg.type !== 'string') return;
            switch (msg.type) {
                case 'refreshPRs':
                    await handleRefreshPRs(state, msg);
                    break;
                case 'selectPR':
                    await handleSelectPR(state, msg);
                    break;
                case 'generateReview':
                    await handleGenerateReview(state, msg);
                    break;
                case 'cancelReview':
                    cancelActiveProvider();
                    break;
                case 'saveDraft':
                    await handleSaveDraft(state, msg);
                    break;
                case 'submitReview':
                    await handleSubmitReview(state, msg);
                    break;
                case 'deleteDraft':
                    await handleDeleteDraft(state, msg);
                    break;
                case 'askClaude':
                    await handleAskClaude(state, msg);
                    break;
                case 'clearChat': {
                    const key = prKey(state.activePR);
                    if (key) state.chatHistory.delete(key);
                    break;
                }
                case 'openUrl':
                    if (typeof msg.url === 'string' && msg.url.startsWith('https://')) {
                        void vscode.env.openExternal(vscode.Uri.parse(msg.url));
                    }
                    break;
                default:
                    console.warn('[pr-pilot] unknown message type:', msg.type);
            }
        });
    }
}

// ── Per-view state ─────────────────────────────────────────────────────────────

interface ViewState {
    webview: vscode.Webview;
    cachedToken: string | null;
    prStateFilter: string;
    searchScope: github.PRSearchScope;
    activePR: ActivePR | null;
    activeDiff: string;
    activeReviewResult: github.ReviewResult | null;
    pendingReviewId: string | null;
    chatHistory: Map<string, claude.ChatMessage[]>;
    // PR-branch worktree, lazily created on first review/chat for the active PR and reused until
    // the PR changes or the view is disposed. Mirrors WebviewPanel.java's activePr* fields.
    worktreeDir: string | null;
    gitRoot: string | null;
    worktreeKey: string | null;
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
    if (wt && root) {
        void worktree.removeWorktree(root, wt).catch(() => undefined);
    }
}

/**
 * Resolves the working directory for a review/chat against `pr`. Creates a detached git worktree
 * checked out to the PR's head branch so the CLI can read PR-branch source for type lookups and
 * cross-file references, then caches it for reuse. Falls back to the open workspace folder when a
 * worktree can't be created (no git root, different repo, fork fetch fails, etc.).
 *
 * Mirrors WebviewPanel.resolvePrClaudeService. Only builds a worktree when the open workspace is
 * the same repo as the PR — the worktree shares that repo's local object store.
 */
async function resolveWorkingDir(
    state: ViewState,
    pr: ActivePR,
    token: string,
    emitStatus: boolean,
    bridgePrKey?: string,
): Promise<string> {
    const fallback = workingDir();
    const key = worktreeKey(pr);
    if (state.worktreeDir && state.worktreeKey === key) return state.worktreeDir;
    if (!token || !fallback) return fallback;

    const gitRoot = worktree.findGitRoot(fallback);
    const currentRepo = github.detectCurrentRepo(fallback);
    const sameRepo = currentRepo !== null
        && currentRepo.toLowerCase() === `${pr.owner}/${pr.repo}`.toLowerCase();
    if (!gitRoot || !sameRepo) return fallback;

    if (emitStatus) {
        push(state, { type: 'reviewGenerating', prKey: bridgePrKey, message: 'Preparing PR branch…' });
    }

    try {
        const headInfo = await github.getPRHeadInfo(token, githubBaseUrl(), pr.owner, pr.repo, pr.number);
        if (!headInfo.ref.trim()) return fallback;

        const wt = worktree.worktreePath(pr.number);
        if (headInfo.isFork) {
            await worktree.createWorktreeFromFork(gitRoot, headInfo.forkCloneUrl, headInfo.ref, wt);
        } else {
            await worktree.createWorktree(gitRoot, headInfo.ref, wt);
        }

        // The PR may have changed while we awaited git; discard the worktree if so.
        if (!isSameActivePR(state, pr)) {
            void worktree.removeWorktree(gitRoot, wt).catch(() => undefined);
            return fallback;
        }

        state.worktreeDir = wt;
        state.gitRoot = gitRoot;
        state.worktreeKey = key;
        return wt;
    } catch (err) {
        console.warn(`[pr-pilot] Worktree creation for PR #${pr.number} failed; using workspace dir:`,
            err instanceof Error ? err.message : String(err));
        return fallback;
    }
}

function push(state: ViewState, msg: object): void {
    state.webview.postMessage(msg);
}

function config(): vscode.WorkspaceConfiguration {
    return vscode.workspace.getConfiguration('pr-pilot');
}

function githubBaseUrl(): string {
    return config().get<string>('githubBaseUrl', 'https://github.com');
}

function reviewModel(): string {
    const key = provider() === 'copilot' ? 'reviewModelCopilot' : 'reviewModel';
    return config().get<string>(key, '');
}

function reviewEffort(): string {
    const value = config().get<string>('reviewEffort', 'medium');
    return value && value.trim().length > 0 ? value : 'medium';
}

function workingDir(): string {
    return vscode.workspace.workspaceFolders?.[0]?.uri.fsPath ?? '';
}

async function getToken(state: ViewState): Promise<string> {
    if (state.cachedToken) return state.cachedToken;
    const token = await github.resolveToken(githubBaseUrl());
    state.cachedToken = token;
    return token;
}

// ── Message handlers ───────────────────────────────────────────────────────────

async function handleRefreshPRs(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    try {
        const token = await getToken(state);
        if (typeof msg.state === 'string') state.prStateFilter = msg.state;
        if (typeof msg.searchScope === 'string') {
            state.searchScope = normalizeSearchScope(msg.searchScope);
        } else if (typeof msg.assignedToMe === 'boolean' && msg.assignedToMe) {
            state.searchScope = 'assigned';
        } else if (typeof msg.reviewRequested === 'boolean' && msg.reviewRequested) {
            state.searchScope = 'reviewRequested';
        }

        const currentRepo = github.detectCurrentRepo(workingDir() || process.cwd());
        const prs = await github.searchPRs(
            token,
            githubBaseUrl(),
            state.prStateFilter,
            state.searchScope,
            currentRepo ?? undefined,
        );
        prs.sort((a, b) => b.createdAt.localeCompare(a.createdAt));
        push(state, {
            type: 'prListLoaded',
            prs,
            defaultRepo: currentRepo ?? undefined,
            listStatus: {
                searchScope: state.searchScope,
                currentRepo: currentRepo ?? undefined,
                resultLimit: 50,
                limited: prs.length >= 50,
            },
        });
    } catch (err) {
        state.cachedToken = null;
        const reason = classifySetupAuthError(err);
        if (reason) {
            const detail = reason === 'gh_not_installed'
                ? "The 'gh' CLI was not found. Install it from https://cli.github.com, then run 'gh auth login' in a terminal and click Refresh."
                : "Run 'gh auth login' in a terminal to authenticate, then click Refresh.";
            push(state, { type: 'setupRequired', reason, detail });
        } else {
            const message = toUserFacingError(err, 'load pull requests');
            vscode.window.showErrorMessage(`PR Pilot: ${message}`);
            push(state, { type: 'prListLoaded', prs: [] });
        }
    }
}

function normalizeSearchScope(value: string): github.PRSearchScope {
    if (value === 'authored' || value === 'assigned' || value === 'reviewRequested') return value;
    return 'currentRepo';
}

async function handleSelectPR(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    const number = msg.number as number;
    const owner = msg.owner as string;
    const repo = msg.repo as string;
    if (!number || !owner || !repo) return;
    const key = prKeyFromParts(number, owner, repo);
    const title = typeof msg.title === 'string' ? msg.title : '';
    const body = typeof msg.body === 'string' ? msg.body : '';

    clearWorktree(state);
    state.activePR = { number, owner, repo, title, body };
    state.activeDiff = '';
    state.activeReviewResult = null;
    state.pendingReviewId = null;
    push(state, { type: 'draftLoading', prKey: key });
    try {
        const token = await getToken(state);
        const base = githubBaseUrl();

        const [diff, detail, draft] = await Promise.all([
            github.getPRDiff(token, base, owner, repo, number),
            github.getPRDetail(token, base, owner, repo, number),
            github.loadDraftReview(token, base, owner, repo, number),
        ]);

        if (prKey(state.activePR) !== key) return;

        state.activePR = { number, owner, repo, title, body };
        state.activeDiff = diff;
        state.activeReviewResult = draft?.result ?? null;
        state.pendingReviewId = draft?.id ?? null;

        if (detail.merged) {
            push(state, { type: 'draftLoaded', prKey: key, prState: 'MERGED', diff });
        } else if (draft) {
            push(state, {
                type: 'draftLoaded',
                prKey: key,
                prState: 'DRAFT_PRESENT',
                reviewId: draft.id,
                result: draft.result,
                diff,
                staleCommits: false,
                importedFromGitHub: draft.importedFromGitHub,
            });
        } else {
            push(state, { type: 'draftLoaded', prKey: key, prState: 'NO_DRAFT', diff });
        }
    } catch (err) {
        state.cachedToken = null;
        if (prKey(state.activePR) !== key) return;
        push(state, {
            type: 'draftLoaded',
            prKey: key,
            prState: 'NO_DRAFT',
            status: toUserFacingError(err, 'load PR details'),
        });
    }
}

async function handleGenerateReview(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    const number = msg.number as number;
    const owner = msg.owner as string;
    const repo = msg.repo as string;
    if (!number || !owner || !repo) return;
    const key = prKeyFromParts(number, owner, repo);

    push(state, { type: 'reviewGenerating', prKey: key, message: 'Fetching PR data…' });
    try {
        const token = await getToken(state);
        const base = githubBaseUrl();

        let diff = state.activeDiff;
        if (!diff || state.activePR?.number !== number) {
            diff = await github.getPRDiff(token, base, owner, repo, number);
            state.activeDiff = diff;
        }

        const existingReviews = await github.getExistingReviewsSummary(token, base, owner, repo, number).catch(() => '');

        const pr: claude.PR = {
            number,
            owner,
            repo,
            title: state.activePR?.title ?? '',
            body: state.activePR?.body ?? '',
        };

        const prompt = claude.buildPrompt({ pr, existingReviews });
        const isCopilot = provider() === 'copilot';

        const reviewDir = await resolveWorkingDir(
            state,
            { number, owner, repo, title: pr.title, body: pr.body ?? '' },
            token,
            true,
            key,
        );

        const result = isCopilot
            ? await copilot.reviewPR({
                prompt,
                model: reviewModel(),
                effort: reviewEffort(),
                workingDir: reviewDir,
                onStatus: (status) => push(state, { type: 'reviewGenerating', prKey: key, message: status }),
                onChunk: (kind, chunk) => push(state, { type: 'reviewChunk', prKey: key, kind, chunk }),
            })
            : await claude.reviewPR({
                prompt,
                model: reviewModel(),
                workingDir: reviewDir,
                onStatus: (status) => push(state, { type: 'reviewGenerating', prKey: key, message: status }),
                onChunk: (kind, chunk) => push(state, { type: 'reviewChunk', prKey: key, kind, chunk }),
            });

        if (prKey(state.activePR) !== key) return;
        state.activeReviewResult = result;
        push(state, { type: 'reviewResult', prKey: key, result, diff });
    } catch (err) {
        if (isCancellationError(err)) return;
        push(state, { type: 'reviewError', prKey: key, message: toUserFacingError(err, 'generate review') });
    }
}

async function handleSaveDraft(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    const number = msg.number as number;
    const owner = msg.owner as string;
    const repo = msg.repo as string;
    const resultFromMsg = msg.result as github.ReviewResult | undefined;
    const orphansFromMsg = (msg.orphans as github.LineComment[] | undefined) ?? [];
    const review = resultFromMsg ?? state.activeReviewResult;
    if (!number || !owner || !repo || !review) return;
    const key = prKeyFromParts(number, owner, repo);

    try {
        const token = await getToken(state);
        const { reviewId, commentsDropped } = await github.saveDraftReview(
            token, githubBaseUrl(), owner, repo, number, review, orphansFromMsg,
        );
        state.pendingReviewId = reviewId;
        push(state, { type: 'draftSaved', prKey: key, reviewId, commentsDropped });
    } catch (err) {
        state.cachedToken = null;
        push(state, {
            type: 'draftSaveError',
            prKey: key,
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
    if (!number || !owner || !repo || !verdict || !state.pendingReviewId) return;
    const key = prKeyFromParts(number, owner, repo);

    try {
        const token = await getToken(state);
        await github.submitReview(
            token, githubBaseUrl(), owner, repo, number,
            state.pendingReviewId, verdict, comment,
        );
        state.pendingReviewId = null;
        push(state, { type: 'reviewSubmitted', prKey: key });
    } catch (err) {
        state.cachedToken = null;
        push(state, {
            type: 'reviewSubmitError',
            prKey: key,
            message: toUserFacingError(err, 'submit draft review'),
        });
    }
}

async function handleDeleteDraft(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    const number = msg.number as number;
    const owner = msg.owner as string;
    const repo = msg.repo as string;
    if (!number || !owner || !repo || !state.pendingReviewId) return;
    const key = prKeyFromParts(number, owner, repo);

    try {
        const token = await getToken(state);
        await github.deleteDraftReview(
            token, githubBaseUrl(), owner, repo, number, state.pendingReviewId,
        );
        state.pendingReviewId = null;
        push(state, { type: 'draftDeleted', prKey: key });
    } catch (err) {
        state.cachedToken = null;
        push(state, {
            type: 'draftDeleteError',
            prKey: key,
            message: toUserFacingError(err, 'delete draft review'),
        });
    }
}

async function handleAskClaude(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    const context = msg.context as string ?? '';
    const question = msg.question as string ?? '';
    if (!question.trim()) return;

    const key = prKey(state.activePR);
    if (key && !state.chatHistory.has(key)) state.chatHistory.set(key, []);
    const history = key ? (state.chatHistory.get(key) ?? []) : [];

    // Add user turn to history before sending
    history.push({ role: 'USER', content: question });

    const prompt = context.trim()
        ? claude.buildFocusedChatPrompt(context, question)
        : claude.buildChatPrompt(buildPrContext(state), history.slice(0, -1), question);

    const isCopilot = provider() === 'copilot';
    try {
        // Reuse the PR-branch worktree if one was built for the active PR (e.g. during review) so
        // chat sees the same source. Uses the cached token only — never triggers a fresh auth.
        const chatDir = state.activePR
            ? await resolveWorkingDir(state, state.activePR, state.cachedToken ?? '', false)
            : workingDir();
        const response = isCopilot
            ? await copilot.chat({
                prompt,
                effort: reviewEffort(),
                workingDir: chatDir,
                onChunk: (chunk) => push(state, { type: 'chatChunk', prKey: key ?? undefined, chunk }),
            })
            : await claude.chat({
                prompt,
                workingDir: chatDir,
                onChunk: (chunk) => push(state, { type: 'chatChunk', prKey: key ?? undefined, chunk }),
            });
        history.push({ role: 'ASSISTANT', content: response });
        push(state, { type: 'chatResponse', prKey: key ?? undefined, response });
    } catch (err) {
        if (isCancellationError(err)) return;
        history.pop(); // remove the user turn we added on error
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
        if (r.summary) lines.push(`Summary: ${r.summary.substring(0, 500)}`);
    }
    if (state.activeDiff) {
        lines.push('', 'Diff (excerpt):', state.activeDiff.substring(0, 4000));
    }
    return lines.join('\n');
}

function errorMessage(err: unknown): string {
    return err instanceof Error ? err.message : String(err);
}

function isCancellationError(err: unknown): boolean {
    return errorMessage(err).toLowerCase().includes('cancel');
}

function errorHtml(message: string): string {
    return `<!DOCTYPE html>
<html lang="en">
<head><meta charset="UTF-8"><title>Claude Reviews</title></head>
<body style="color:#e8a030;background:#0a0805;font-family:monospace;padding:16px;">
  <p>${message}</p>
</body>
</html>`;
}
