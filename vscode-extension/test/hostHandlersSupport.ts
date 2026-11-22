import { existsSync, readFileSync } from 'node:fs';
import * as path from 'node:path';
import { runInNewContext } from 'node:vm';

export type Handler = (state: Record<string, unknown>, msg: Record<string, unknown>) => Promise<void>;

export const BASELINE = 'b'.repeat(40);

/** Values created inside the vm context have foreign prototypes; compare their JSON shape. */
export function plain(value: unknown): unknown {
    return JSON.parse(JSON.stringify(value));
}

/** Loads the compiled host with only VS Code, the providers, and the sidecar replaced. */
export function host(settings: Record<string, unknown> = {}) {
    const cancelled: string[] = [];
    const messages: Record<string, unknown>[] = [];
    const updates: { key: string; value: unknown; target: unknown }[] = [];
    const client = {
        detectRepo: async () => 'acme/widget',
        listPullRequests: async () => ({
            status: 'ok', prs: [], resultLimit: 50, limited: false, reviewStatusAvailable: true,
        }),
        probeProviderSetup: async () => ({
            available: true,
            binaryStatus: 'ready',
            authenticationStatus: 'ready',
            authCommand: 'claude auth login',
        }),
        cancelReview: async (operationId: string) => { cancelled.push(operationId); },
    };
    const openedDocs: Array<{ uri: Record<string, unknown>; options: Record<string, unknown> }> = [];
    const infoMessages: string[] = [];
    const vscode = {
        ConfigurationTarget: { Global: 1 },
        ViewColumn: { Beside: 2 },
        Range: class Range {
            constructor(public startLine: number, public startChar: number, public endLine: number, public endChar: number) {}
        },
        Uri: {
            file: (fsPath: string) => ({ scheme: 'file', fsPath }),
            from: (parts: Record<string, unknown>) => ({ ...parts }),
        },
        window: {
            showTextDocument: async (uri: Record<string, unknown>, options: Record<string, unknown>) => {
                openedDocs.push({ uri, options });
            },
            showInformationMessage: async (message: string) => {
                infoMessages.push(message);
                return undefined;
            },
        },
        workspace: {
            workspaceFolders: [{ uri: { fsPath: (settings.workspaceRoot as string) ?? '/fixture' } }],
            getConfiguration: () => ({
                get: (key: string, fallback: unknown) => (key in settings ? settings[key] : fallback),
                update: async (key: string, value: unknown, target: unknown) => {
                    if (settings.failUpdates === true) throw new Error('settings.json is read-only');
                    updates.push({ key, value, target });
                    settings[key] = value;
                },
            }),
        },
    };
    const cache = new Map<string, { exports: Record<string, unknown> }>();
    function load(file: string): Record<string, unknown> {
        const cached = cache.get(file);
        if (cached) return cached.exports;
        const module = { exports: {} as Record<string, unknown> };
        cache.set(file, module);
        const requireLocal = (name: string): unknown => {
            if (name === 'vscode') return vscode;
            if (name === './claude') return { claudeBinaryAvailable: () => true };
            if (name === './copilot') return { copilotBinaryAvailable: () => true, resolveReviewInheritMcp: () => true };
            if (name.startsWith('.')) {
                const target = path.resolve(path.dirname(file), `${name}.js`);
                if (existsSync(target)) return load(target);
            }
            return require(name);
        };
        const expose = path.basename(file) === 'extension.js'
            ? '\nexports.testHost = {refresh: handleRefreshPRs, select: handleSelectPR, cancelChat: handleCancelChat, generate: handleGenerateReview, '
                + 'openFile: handleOpenFile, saveRepositoryInstructions: handleSaveRepositoryInstructions, setClient: c => {sidecarClient = Object.assign({}, sidecarClient, c)}};'
            : '';
        runInNewContext(readFileSync(file, 'utf8') + expose, { module, exports: module.exports,
            require: requireLocal, __dirname: path.dirname(file), __filename: file,
            console, process, Buffer, setTimeout, clearTimeout, setInterval, clearInterval }, { filename: file });
        return module.exports;
    }
    const api = load(require.resolve('../src/extension')).testHost as {
        refresh: Handler; select: Handler; cancelChat: Handler; generate: Handler; openFile: Handler; setClient: (value: object) => void;
        saveRepositoryInstructions: (state: Record<string, unknown>, msg: Record<string, unknown>,
            push: (message: Record<string, unknown>) => void) => Promise<void>;
    };
    api.setClient(client);
    const state: Record<string, unknown> = {
        webview: { postMessage: (message: Record<string, unknown>) => messages.push(message) },
        prStateFilter: 'open', searchScope: 'currentRepo', activePR: null, pendingReviewId: null,
        refreshRevision: 0, selectionRevision: 1, generationRevision: 1, chatRevision: 1,
        activeProviderOperation: null, chatHistory: new Map(), disposed: false,
        worktreeDir: null, gitRoot: null, worktreeKey: null, worktreeEpoch: 0, worktreeCreation: null,
        prefetchedPrContext: null,
    };
    return { api, state, messages, cancelled, updates, settings, openedDocs, infoMessages };
}

/** Runs one generateReview through the real host handler with a scripted sidecar. */
export async function generate(options: {
    incremental?: boolean;
    incrementalResult?: Record<string, unknown>;
    validationFails?: boolean;
    oversized?: boolean;
    cachedLocal?: boolean;
    currentHead?: string;
    currentBase?: string;
    metadataUnavailable?: boolean;
    chunked?: boolean;
    settings?: Record<string, unknown>;
}) {
    const h = host(options.settings ?? {});
    const incrementalCalls: unknown[][] = [];
    const generateCalls: Record<string, unknown>[] = [];
    const localSources: unknown[] = [];
    h.api.setClient({
        cancelReview: async () => {},
        getPullRequestDiff: async (_base: string, _owner: string, _repo: string, _number: number, mode: string, localSource?: unknown) => {
            if (mode === 'validation' && options.validationFails) throw new Error('network down');
            if (options.oversized && !localSource) return { status: 'diff_too_large', message: 'Too large.', diff: null };
            if (localSource) localSources.push(localSource);
            return { status: 'ok', message: 'ok', diff: mode === 'validation' ? 'pr-validation-diff' : 'pr-diff',
                truncated: false, limitBytes: 250000 };
        },
        getIncrementalDiff: async (...args: unknown[]) => {
            incrementalCalls.push(args);
            return options.incrementalResult;
        },
        getExistingReviews: async () => ({ status: 'ok', summary: '' }),
        getPullRequestDetail: async () => options.metadataUnavailable
            ? { status: 'api_failed' }
            : { status: 'ok', detail: {
                head: { sha: options.currentHead ?? 'c'.repeat(40) },
                baseSha: options.currentBase ?? 'd'.repeat(40),
            } },
        getCommits: async () => ({ summary: '', closingIssueNumbers: [] }),
        getCheckStatus: async () => ({ summary: '', annotations: [] }),
        getLinkedIssues: async () => '',
        getRepoProfile: async () => '',
        generateReview: async (params: Record<string, unknown>) => {
            generateCalls.push(params);
            return { summary: 'Done.', verdict: 'COMMENT', lineComments: [] };
        },
    });
    Object.assign(h.state, {
        activePR: { number: 42, owner: 'acme', repo: 'widget', title: 'Test', body: '' },
        activeDiff: '', activeValidationDiff: '', activeReviewResult: null, generatedReviews: new Map(),
        worktreeDir: '/fixture/wt', worktreeKey: 'acme/widget#42',
        ...(options.cachedLocal ? {
            activeDiff: 'cached-local', activeValidationDiff: 'cached-validation',
            localDiffRevisions: { headSha: 'c'.repeat(40), baseSha: 'd'.repeat(40) },
        } : {}),
    });
    await h.api.generate(h.state, { type: 'generateReview', operationId: 'op-1', number: 42, owner: 'acme',
        repo: 'widget', ...(options.incremental ? { incremental: true } : {}),
        ...(options.chunked ? { chunkedReview: true, diff: 'cached-validation' } : {}) });
    const find = (type: string) => {
        const message = h.messages.find((m) => m.type === type);
        return message === undefined ? undefined : plain(message) as Record<string, unknown>;
    };
    const result = find('reviewResult');
    const error = find('reviewError');
    return { state: h.state, incrementalCalls, generateCalls, localSources, result, error };
}
