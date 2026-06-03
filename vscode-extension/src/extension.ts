import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import * as github from './github';
import * as claude from './claude';
import * as copilot from './copilot';
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
    context.subscriptions.push(
        vscode.window.registerWebviewViewProvider('pr-pilot.main', provider, {
            webviewOptions: { retainContextWhenHidden: true },
        })
    );
}

export function deactivate() {}

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
            assignedToMe: false,
            reviewRequested: false,
            activePR: null,
            activeDiff: '',
            activeReviewResult: null,
            pendingReviewId: null,
            chatHistory: new Map(),
        };

        this.setupMessageBridge(state);

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
                        vscode.env.openExternal(vscode.Uri.parse(msg.url));
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
    assignedToMe: boolean;
    reviewRequested: boolean;
    activePR: ActivePR | null;
    activeDiff: string;
    activeReviewResult: github.ReviewResult | null;
    pendingReviewId: string | null;
    chatHistory: Map<string, claude.ChatMessage[]>;
}

function prKey(pr: ActivePR | null): string | null {
    return pr ? `${pr.owner}/${pr.repo}#${pr.number}` : null;
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
        if (typeof msg.assignedToMe === 'boolean') state.assignedToMe = msg.assignedToMe;
        if (typeof msg.reviewRequested === 'boolean') state.reviewRequested = msg.reviewRequested;

        const currentRepo = github.detectCurrentRepo(workingDir() || process.cwd());
        const prs = await github.searchPRs(
            token,
            githubBaseUrl(),
            state.prStateFilter,
            state.assignedToMe,
            state.reviewRequested,
            currentRepo ?? undefined,
        );
        prs.sort((a, b) => b.createdAt.localeCompare(a.createdAt));
        push(state, { type: 'prListLoaded', prs, defaultRepo: currentRepo ?? undefined });
    } catch (err) {
        state.cachedToken = null;
        const errMsg = (err instanceof Error ? err.message : String(err)).toLowerCase();
        const notInstalled = errMsg.includes('enoent') || errMsg.includes('no such file') || errMsg.includes('error=2');
        const isAuthError = notInstalled || errMsg.includes('gh auth') || errMsg.includes('auth token');
        if (isAuthError) {
            const reason = notInstalled ? 'gh_not_installed' : 'gh_not_authenticated';
            const detail = notInstalled
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

async function handleSelectPR(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    const number = msg.number as number;
    const owner = msg.owner as string;
    const repo = msg.repo as string;
    if (!number || !owner || !repo) return;

    push(state, { type: 'draftLoading' });
    try {
        const token = await getToken(state);
        const base = githubBaseUrl();

        const [diff, detail, draft] = await Promise.all([
            github.getPRDiff(token, base, owner, repo, number),
            github.getPRDetail(token, base, owner, repo, number),
            github.loadDraftReview(token, base, owner, repo, number),
        ]);

        const title = typeof msg.title === 'string' ? msg.title : '';
        const body = typeof msg.body === 'string' ? msg.body : '';
        state.activePR = { number, owner, repo, title, body };
        state.activeDiff = diff;
        state.activeReviewResult = draft?.result ?? null;
        state.pendingReviewId = draft?.id ?? null;

        if (detail.merged) {
            push(state, { type: 'draftLoaded', prState: 'MERGED', diff });
        } else if (draft) {
            push(state, {
                type: 'draftLoaded',
                prState: 'DRAFT_PRESENT',
                reviewId: draft.id,
                result: draft.result,
                diff,
                staleCommits: false,
            });
        } else {
            push(state, { type: 'draftLoaded', prState: 'NO_DRAFT', diff });
        }
    } catch (err) {
        state.cachedToken = null;
        push(state, {
            type: 'draftLoaded',
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

    push(state, { type: 'reviewGenerating', message: 'Fetching PR data…' });
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

        const result = isCopilot
            ? await copilot.reviewPR({
                prompt,
                model: reviewModel(),
                effort: reviewEffort(),
                workingDir: workingDir(),
                onStatus: (status) => push(state, { type: 'reviewGenerating', message: status }),
                onChunk: (kind, chunk) => push(state, { type: 'reviewChunk', kind, chunk }),
            })
            : await claude.reviewPR({
                prompt,
                model: reviewModel(),
                workingDir: workingDir(),
                onStatus: (status) => push(state, { type: 'reviewGenerating', message: status }),
                onChunk: (kind, chunk) => push(state, { type: 'reviewChunk', kind, chunk }),
            });

        state.activeReviewResult = result;
        push(state, { type: 'reviewResult', result, diff });
    } catch (err) {
        if (isCancellationError(err)) return;
        push(state, { type: 'reviewError', message: toUserFacingError(err, 'generate review') });
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

    try {
        const token = await getToken(state);
        const { reviewId, commentsDropped } = await github.saveDraftReview(
            token, githubBaseUrl(), owner, repo, number, review, orphansFromMsg,
        );
        state.pendingReviewId = reviewId;
        push(state, { type: 'draftSaved', reviewId, commentsDropped });
    } catch (err) {
        state.cachedToken = null;
        push(state, {
            type: 'draftSaveError',
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

    try {
        const token = await getToken(state);
        await github.submitReview(
            token, githubBaseUrl(), owner, repo, number,
            state.pendingReviewId, verdict, comment,
        );
        state.pendingReviewId = null;
        push(state, { type: 'reviewSubmitted' });
    } catch (err) {
        state.cachedToken = null;
        push(state, {
            type: 'reviewSubmitError',
            message: toUserFacingError(err, 'submit draft review'),
        });
    }
}

async function handleDeleteDraft(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    const number = msg.number as number;
    const owner = msg.owner as string;
    const repo = msg.repo as string;
    if (!number || !owner || !repo || !state.pendingReviewId) return;

    try {
        const token = await getToken(state);
        await github.deleteDraftReview(
            token, githubBaseUrl(), owner, repo, number, state.pendingReviewId,
        );
        state.pendingReviewId = null;
        push(state, { type: 'draftDeleted' });
    } catch (err) {
        state.cachedToken = null;
        push(state, {
            type: 'draftDeleteError',
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
        const response = isCopilot
            ? await copilot.chat({
                prompt,
                effort: reviewEffort(),
                workingDir: workingDir(),
                onChunk: (chunk) => push(state, { type: 'chatChunk', chunk }),
            })
            : await claude.chat({
                prompt,
                workingDir: workingDir(),
                onChunk: (chunk) => push(state, { type: 'chatChunk', chunk }),
            });
        history.push({ role: 'ASSISTANT', content: response });
        push(state, { type: 'chatResponse', response });
    } catch (err) {
        if (isCancellationError(err)) return;
        history.pop(); // remove the user turn we added on error
        push(state, { type: 'chatError', message: toUserFacingError(err, 'answer chat question') });
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
