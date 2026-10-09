import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import * as path from 'node:path';
import { runInNewContext } from 'node:vm';

type Handler = (state: Record<string, unknown>, msg: Record<string, unknown>) => Promise<void>;

/** Loads the compiled host with only VS Code, the providers, and the sidecar replaced. */
function host(settings: Record<string, unknown> = {}) {
    const cancelled: string[] = [];
    const messages: Record<string, unknown>[] = [];
    const updates: { key: string; value: unknown; target: unknown }[] = [];
    const client = {
        detectRepo: async () => 'acme/widget',
        listPullRequests: async () => ({
            status: 'ok', prs: [], resultLimit: 50, limited: false, reviewStatusAvailable: true,
        }),
        cancelReview: async (operationId: string) => { cancelled.push(operationId); },
    };
    const vscode = {
        ConfigurationTarget: { Global: 1 },
        workspace: {
            workspaceFolders: [{ uri: { fsPath: '/fixture' } }],
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
            ? '\nexports.testHost = {refresh: handleRefreshPRs, cancelChat: handleCancelChat, generate: handleGenerateReview, '
                + 'saveRepositoryInstructions: handleSaveRepositoryInstructions, setClient: c => {sidecarClient=c}};'
            : '';
        runInNewContext(readFileSync(file, 'utf8') + expose, { module, exports: module.exports,
            require: requireLocal, __dirname: path.dirname(file), __filename: file,
            console, process, Buffer, setTimeout, clearTimeout, setInterval, clearInterval }, { filename: file });
        return module.exports;
    }
    const api = load(require.resolve('../src/extension')).testHost as {
        refresh: Handler; cancelChat: Handler; generate: Handler; setClient: (value: object) => void;
        saveRepositoryInstructions: (state: Record<string, unknown>, msg: Record<string, unknown>,
            push: (message: Record<string, unknown>) => void) => Promise<void>;
    };
    api.setClient(client);
    const state: Record<string, unknown> = {
        webview: { postMessage: (message: Record<string, unknown>) => messages.push(message) },
        prStateFilter: 'open', searchScope: 'currentRepo', activePR: null, pendingReviewId: null,
        refreshRevision: 0, selectionRevision: 1, generationRevision: 1, chatRevision: 1,
        activeProviderOperation: null, chatHistory: new Map(), disposed: false,
    };
    return { api, state, messages, cancelled, updates, settings };
}

async function listFlag(settings: Record<string, unknown>): Promise<unknown> {
    const h = host({ reviewProvider: 'copilot', ...settings });
    await h.api.refresh(h.state, {});
    const loaded = h.messages.find((message) => message.type === 'prListLoaded');
    assert.ok(loaded, `expected a prListLoaded message, got ${JSON.stringify(h.messages)}`);
    return loaded.intellijAssistedEnabled;
}

test('PR list messages carry the IntelliJ-assisted setting so PR-agnostic UI can honor it', async () => {
    assert.equal(await listFlag({}), false);
    assert.equal(await listFlag({ experimentalIntellijAssistedReview: false }), false);
    assert.equal(await listFlag({ experimentalIntellijAssistedReview: true }), true);
});

test('cancelChat stops the owning chat operation and keeps the conversation history', async () => {
    const h = host();
    const history = [{ role: 'USER', content: 'Q' }, { role: 'ASSISTANT', content: 'A' }];
    (h.state.chatHistory as Map<string, unknown>).set('acme/widget#42', history);
    h.state.activeProviderOperation = { kind: 'chat', revision: 1, operationId: 'chat-1' };

    await h.api.cancelChat(h.state, { type: 'cancelChat', operationId: 'chat-1' });

    assert.deepEqual(h.cancelled, ['chat-1']);
    assert.equal(h.state.chatRevision, 2, 'late output from the stopped answer must be dropped');
    assert.equal((h.state.chatHistory as Map<string, unknown>).get('acme/widget#42'), history);
    assert.deepEqual(h.messages, [], 'a user stop is not reported as a chat error');
});

test('cancelChat ignores operations it does not own', async () => {
    for (const operation of [null, { kind: 'chat', revision: 1, operationId: 'other' },
        { kind: 'review', revision: 1, operationId: 'chat-1' }]) {
        const h = host();
        h.state.activeProviderOperation = operation;

        await h.api.cancelChat(h.state, { type: 'cancelChat', operationId: 'chat-1' });

        assert.deepEqual(h.cancelled, []);
        assert.equal(h.state.chatRevision, 1);
    }
});

/** Values created inside the vm context have foreign prototypes; compare their JSON shape. */
function plain(value: unknown): unknown {
    return JSON.parse(JSON.stringify(value));
}

test('saveRepositoryInstructions persists trimmed text under the lowercase repository key', async () => {
    const h = host({ repositoryReviewInstructions: { 'other/repo': 'Keep me' } });
    await h.api.saveRepositoryInstructions(h.state, { number: 7, owner: 'Acme', repo: 'Widget',
        instructions: '  API PRs precede service PRs.  ' }, (message) => h.messages.push(message));
    assert.deepEqual(plain(h.updates), [{ key: 'repositoryReviewInstructions', target: 1,
        value: { 'other/repo': 'Keep me', 'acme/widget': 'API PRs precede service PRs.' } }]);
    assert.deepEqual(plain(h.messages), [{ type: 'repositoryInstructionsSaved', prKey: 'Acme/Widget#7',
        instructions: 'API PRs precede service PRs.' }]);
});

test('saveRepositoryInstructions forgets a repository when the text is blank', async () => {
    const h = host({ repositoryReviewInstructions: { 'acme/widget': 'Old', 'other/repo': 'Keep me' } });
    await h.api.saveRepositoryInstructions(h.state, { number: 7, owner: 'acme', repo: 'widget', instructions: '   ' },
        (message) => h.messages.push(message));
    assert.deepEqual(plain(h.updates[0]?.value), { 'other/repo': 'Keep me' });
    assert.deepEqual(plain(h.messages), [{ type: 'repositoryInstructionsSaved', prKey: 'acme/widget#7', instructions: '' }]);
});

test('saveRepositoryInstructions reports invalid names, oversize text, and settings failures without saving', async () => {
    const invalid = host();
    await invalid.api.saveRepositoryInstructions(invalid.state, { number: 1, owner: 'a b', repo: 'widget',
        instructions: 'Rule' }, (message) => invalid.messages.push(message));
    assert.equal(invalid.updates.length, 0);
    assert.equal(invalid.messages[0]?.type, 'repositoryInstructionsSaveError');

    const oversize = host();
    await oversize.api.saveRepositoryInstructions(oversize.state, { number: 1, owner: 'acme', repo: 'widget',
        instructions: 'x'.repeat(10_001) }, (message) => oversize.messages.push(message));
    assert.equal(oversize.updates.length, 0);
    assert.match(String(oversize.messages[0]?.message), /10,000 characters/);

    const failing = host({ failUpdates: true });
    await failing.api.saveRepositoryInstructions(failing.state, { number: 1, owner: 'acme', repo: 'widget',
        instructions: 'Rule' }, (message) => failing.messages.push(message));
    assert.deepEqual(plain(failing.messages), [{ type: 'repositoryInstructionsSaveError', prKey: 'acme/widget#1',
        message: 'Could not save PR Pilot settings. Try again.' }]);
});

const BASELINE = 'b'.repeat(40);

/** Runs one generateReview through the real host handler with a scripted sidecar. */
async function generate(options: {
    incremental?: boolean;
    incrementalResult?: Record<string, unknown>;
    validationFails?: boolean;
}) {
    const h = host();
    const incrementalCalls: unknown[][] = [];
    const generateCalls: Record<string, unknown>[] = [];
    h.api.setClient({
        cancelReview: async () => {},
        getPullRequestDiff: async (_base: string, _owner: string, _repo: string, _number: number, mode: string) => {
            if (mode === 'validation' && options.validationFails) throw new Error('network down');
            return { status: 'ok', message: 'ok', diff: mode === 'validation' ? 'pr-validation-diff' : 'pr-diff',
                truncated: false, limitBytes: 250000 };
        },
        getIncrementalDiff: async (...args: unknown[]) => {
            incrementalCalls.push(args);
            return options.incrementalResult;
        },
        getExistingReviews: async () => ({ status: 'ok', summary: '' }),
        getPullRequestDetail: async () => ({ status: 'ok', detail: { head: { sha: 'c'.repeat(40) }, baseSha: 'd'.repeat(40) } }),
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
    });
    await h.api.generate(h.state, { type: 'generateReview', operationId: 'op-1', number: 42, owner: 'acme',
        repo: 'widget', ...(options.incremental ? { incremental: true } : {}) });
    const find = (type: string) => {
        const message = h.messages.find((m) => m.type === type);
        return message === undefined ? undefined : plain(message) as Record<string, unknown>;
    };
    const result = find('reviewResult');
    const error = find('reviewError');
    return { state: h.state, incrementalCalls, generateCalls, result, error };
}

test('incremental generation reviews only new changes but publishes PR diffs', async () => {
    const run = await generate({ incremental: true, incrementalResult: { status: 'ok', message: 'ok',
        scope: 'incremental', baselineSha: BASELINE, headSha: 'c'.repeat(40), diff: 'incremental-diff',
        truncated: false, limitBytes: 250000 } });

    assert.equal(run.incrementalCalls.length, 1);
    assert.equal(run.generateCalls[0].diff, 'incremental-diff');
    assert.equal(run.generateCalls[0].incrementalBaselineSha, BASELINE);
    assert.equal(run.state.activeDiff, 'pr-diff');
    assert.equal(run.result?.diff, 'pr-diff');
    assert.equal(run.result?.validationDiff, 'pr-validation-diff');
    assert.deepEqual(run.result?.reviewScope, { kind: 'incremental', baselineSha: BASELINE });
});

test('incremental generation falls back to the PR diff when the validation fetch fails', async () => {
    const run = await generate({ incremental: true, validationFails: true, incrementalResult: { status: 'ok',
        message: 'ok', scope: 'incremental', baselineSha: BASELINE, headSha: 'c'.repeat(40),
        diff: 'incremental-diff', truncated: false, limitBytes: 250000 } });

    assert.equal(run.generateCalls[0].diff, 'incremental-diff');
    assert.equal(run.result?.diff, 'pr-diff');
    assert.equal(run.result?.validationDiff, 'pr-diff');
});

test('incremental generation runs a full review when the engine falls back', async () => {
    const run = await generate({ incremental: true, incrementalResult: { status: 'ok', message: 'Falling back.',
        scope: 'full', fallbackReason: 'baseline_not_in_history', limitBytes: 250000 } });

    assert.equal(run.generateCalls[0].diff, 'pr-diff');
    assert.equal(run.generateCalls[0].incrementalBaselineSha, undefined);
    assert.equal(run.result?.diff, 'pr-diff');
    assert.deepEqual(run.result?.reviewScope, { kind: 'full', fallbackReason: 'baseline_not_in_history' });
});

test('incremental generation reports a non-ok status as a review error', async () => {
    const run = await generate({ incremental: true, incrementalResult: { status: 'rate_limited',
        message: 'GitHub rate limit exceeded. Try again shortly.', limitBytes: 250000 } });

    assert.equal(run.generateCalls.length, 0);
    assert.equal(run.result, undefined);
    assert.ok(run.error, 'expected a reviewError');
});

test('ordinary generation never asks for the incremental diff or sends a scope', async () => {
    const run = await generate({});

    assert.equal(run.incrementalCalls.length, 0);
    assert.equal(run.generateCalls[0].diff, 'pr-diff');
    assert.equal(run.generateCalls[0].incrementalBaselineSha, undefined);
    assert.equal(run.result !== undefined && 'reviewScope' in run.result, false);
});
