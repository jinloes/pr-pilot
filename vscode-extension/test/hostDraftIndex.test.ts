import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import * as path from 'node:path';
import { runInNewContext } from 'node:vm';

import { DraftRecoveryStore, type RecoveryMemento } from '../src/draftRecovery';
import { PendingDraftIndex } from '../src/pendingDraftIndex';
import { createReviewHandlers } from '../src/reviewHandlers';
import type { HandlerDependencies, ViewState } from '../src/extensionTypes';
import type { PrHandlerApi } from '../src/prHandlers';

function memoryMemento(): RecoveryMemento {
    const data = new Map<string, unknown>();
    return {
        get<T>(key: string): T | undefined {
            return data.get(key) as T | undefined;
        },
        update(key: string, value: unknown): PromiseLike<void> {
            data.set(key, value);
            return Promise.resolve();
        },
    };
}

function loadCreatePrHandlers(): (deps: HandlerDependencies) => PrHandlerApi {
    const vscodeStub = { ConfigurationTarget: { Global: 1 } };
    const cache = new Map<string, { exports: Record<string, unknown> }>();
    function load(file: string): Record<string, unknown> {
        const cached = cache.get(file);
        if (cached) return cached.exports;
        const mod = { exports: {} as Record<string, unknown> };
        cache.set(file, mod);
        const requireLocal = (name: string): unknown => {
            if (name === 'vscode') return vscodeStub;
            if (name.startsWith('.')) {
                const target = path.resolve(path.dirname(file), `${name}.js`);
                if (existsSync(target)) return load(target);
            }
            return require(name);
        };
        runInNewContext(readFileSync(file, 'utf8'), {
            module: mod, exports: mod.exports, require: requireLocal,
            __dirname: path.dirname(file), __filename: file,
            console, process, Buffer, URL, setTimeout, clearTimeout,
        }, { filename: file });
        return mod.exports;
    }
    const mod = load(require.resolve('../src/prHandlers'));
    return mod.createPrHandlers as (deps: HandlerDependencies) => PrHandlerApi;
}

function createHarness(memento = memoryMemento(), baseUrl = 'https://github.com') {
    const createPrHandlers = loadCreatePrHandlers();
    const pendingDraftIndex = new PendingDraftIndex(memento);
    const recoveryStore = new DraftRecoveryStore(memento);
    const pushed: Array<Record<string, unknown>> = [];
    let remoteDraftByPr = new Map<string, { id: string; commitId: string; review: object }>();
    let saveGate: Promise<void> | null = null;
    let readinessGate: Promise<void> | null = null;

    const client = {
        detectRepo: async () => 'acme/widgets',
        listPullRequests: async () => ({
            status: 'ok',
            prs: [
                { number: 1, owner: 'acme', repo: 'widgets', title: 'First', author: 'alice', createdAt: '2026-07-02T00:00:00Z', htmlUrl: 'https://github.com/acme/widgets/pull/1', isDraft: false, reviewStatus: 'UNREVIEWED' },
                { number: 2, owner: 'acme', repo: 'widgets', title: 'Second', author: 'bob', createdAt: '2026-07-01T00:00:00Z', htmlUrl: 'https://github.com/acme/widgets/pull/2', isDraft: false, reviewStatus: 'UNREVIEWED' },
            ],
            resultLimit: 50,
            limited: false,
            reviewStatusAvailable: true,
        }),
        getPullRequestDetail: async (_base: string, _owner: string, _repo: string, number: number) => ({
            status: 'ok',
            detail: { title: `PR ${number}`, body: '', merged: false, baseSha: 'b'.repeat(40), head: { sha: 'a'.repeat(40) } },
        }),
        getPullRequestDiff: async () => ({ status: 'ok', diff: 'diff --git a/a.ts b/a.ts\n' }),
        getDraftReview: async (_base: string, owner: string, repo: string, number: number) => {
            const draft = remoteDraftByPr.get(`${owner}/${repo}#${number}`);
            return draft
                ? { status: 'ok', id: draft.id, commitId: draft.commitId, review: draft.review }
                : { status: 'none' };
        },
        getExistingReviews: async () => ({ status: 'ok', summary: '' }),
        getCommits: async () => ({ summary: '', closingIssueNumbers: [] }),
        getLinkedIssues: async () => '',
        saveDraftReview: async (_base: string, owner: string, repo: string, number: number) => {
            if (saveGate) await saveGate;
            remoteDraftByPr.set(`${owner}/${repo}#${number}`, {
                id: `rev-${number}`,
                commitId: 'a'.repeat(40),
                review: { summary: 'Saved', verdict: 'COMMENT', lineComments: [] },
            });
            return { status: 'ok', reviewId: `rev-${number}`, commentsDropped: false };
        },
        submitReview: async (_base: string, owner: string, repo: string, number: number) => {
            remoteDraftByPr.delete(`${owner}/${repo}#${number}`);
            return { status: 'ok' };
        },
        deleteDraftReview: async (_base: string, owner: string, repo: string, number: number) => {
            remoteDraftByPr.delete(`${owner}/${repo}#${number}`);
            return { status: 'ok' };
        },
    };

    const deps = {
        client: () => client as unknown as ReturnType<HandlerDependencies['client']>,
        config: () => ({ get: (_k: string, d: unknown) => d }) as ReturnType<HandlerDependencies['config']>,
        githubBaseUrl: () => baseUrl,
        provider: () => 'claude' as const,
        workingDir: () => '/fixture',
        providerReadiness: () => ({ provider: 'claude', available: true, detail: 'Ready', binaryStatus: 'ready', authenticationStatus: 'ready', authCommand: 'claude auth login' }),
        providerSetupReadiness: async () => {
            if (readinessGate) await readinessGate;
            return { provider: 'claude' as const, available: true, detail: 'Ready', binaryStatus: 'ready' as const, authenticationStatus: 'ready' as const, authCommand: 'claude auth login' };
        },
        intellijAssistedEnabled: () => false,
        rememberedRepositoryInstructions: () => '',
        snapshotReviewGenerationSettings: () => ({}) as ReturnType<HandlerDependencies['snapshotReviewGenerationSettings']>,
        formatPriorReview: () => '',
        prKey: (pr) => (pr ? `${pr.owner}/${pr.repo}#${pr.number}` : null),
        prKeyFromParts: (number, owner, repo) => `${owner}/${repo}#${number}`,
        pendingDraftIndex: () => pendingDraftIndex,
        push: (_state, message) => { pushed.push(message as Record<string, unknown>); },
        enqueueMutation: async (_state, action) => action(),
        clearWorktree: () => undefined,
        resolveWorkingDir: async () => '/fixture',
        cancelActiveProvider: async () => undefined,
        withCodeIntelligence: async (_enabled, _root, run) => run(undefined),
        freshDeepPr: async () => { throw new Error('unused'); },
        handleGenerateReview: async () => undefined,
    } satisfies HandlerDependencies;

    const prHandlers = createPrHandlers(deps);
    const reviewHandlers = createReviewHandlers(deps);
    const state: ViewState = {
        webview: {} as ViewState['webview'],
        prStateFilter: 'open',
        searchScope: 'currentRepo',
        activePR: null,
        activeDiff: '',
        localDiffRevisions: null,
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
        prefetchedPrContext: null,
        disposed: false,
        deepReview: { invalidate: () => undefined, cancel: () => undefined } as unknown as ViewState['deepReview'],
        draftRecoveryStore: recoveryStore,
    };

    return {
        prHandlers,
        reviewHandlers,
        state,
        pushed,
        pendingDraftIndex,
        recoveryStore,
        setSaveGate: (gate: Promise<void> | null) => { saveGate = gate; },
        setReadinessGate: (gate: Promise<void> | null) => { readinessGate = gate; },
        setRemoteDraft: (key: string, id: string) => {
            remoteDraftByPr.set(key, {
                id,
                commitId: 'a'.repeat(40),
                review: { summary: 'Remote', verdict: 'COMMENT', lineComments: [] },
            });
        },
        clearRemoteDrafts: () => { remoteDraftByPr = new Map(); },
    };
}

test('preserves hasReviewDraft across PR switches, in-flight flush saves, and ViewState reloads until deleted or submitted', async () => {
    const sharedMemento = memoryMemento();
    const h1 = createHarness(sharedMemento);

    await h1.prHandlers.handleSelectPR(h1.state, { number: 1, owner: 'acme', repo: 'widgets', author: 'alice' });

    let releaseSave!: () => void;
    h1.setSaveGate(new Promise<void>((resolve) => { releaseSave = resolve; }));
    const inFlightSave = h1.reviewHandlers.handleSaveDraft(h1.state, {
        number: 1,
        owner: 'acme',
        repo: 'widgets',
        saveId: 1,
        result: { summary: 'Draft 1', verdict: 'COMMENT', lineComments: [] },
        orphans: [],
    });

    // Switch to PR #2 while PR #1's flush save is still in flight.
    await h1.prHandlers.handleSelectPR(h1.state, { number: 2, owner: 'acme', repo: 'widgets', author: 'bob' });
    releaseSave();
    await inFlightSave;
    h1.setSaveGate(null);

    assert.equal(h1.pendingDraftIndex.has('acme', 'widgets', 1), true);
    assert.ok(h1.pushed.some((m) => m.type === 'prDraftStatusUpdated' && m.number === 1 && m.hasReviewDraft === true));

    // Reload into a brand-new ViewState backed by the same globalState memento.
    const h2 = createHarness(sharedMemento);
    await h2.prHandlers.handleRefreshPRs(h2.state, {});
    const listLoaded = h2.pushed.find((m) => m.type === 'prListLoaded') as { prs: Array<{ number: number; hasReviewDraft: boolean }> };
    assert.equal(listLoaded.prs.find((p) => p.number === 1)?.hasReviewDraft, true);
    assert.equal(listLoaded.prs.find((p) => p.number === 2)?.hasReviewDraft, false);

    // Selecting PR #1 when GitHub now has no draft clears the stale index entry.
    h2.clearRemoteDrafts();
    await h2.prHandlers.handleSelectPR(h2.state, { number: 1, owner: 'acme', repo: 'widgets', author: 'alice' });
    assert.equal(h2.pendingDraftIndex.has('acme', 'widgets', 1), false);
});

test('does not index a crash-recovery snapshot until it is saved to GitHub', async () => {
    const memento = memoryMemento();
    const h = createHarness(memento);
    await h.recoveryStore.save('acme/widgets#1', { summary: 'Unsaved crash recovery', verdict: 'COMMENT', lineComments: [] }, []);

    await h.prHandlers.handleSelectPR(h.state, { number: 1, owner: 'acme', repo: 'widgets', author: 'alice' });
    assert.equal(h.pendingDraftIndex.has('acme', 'widgets', 1), false);
    const statusUpdate = [...h.pushed].reverse().find((m) => m.type === 'prDraftStatusUpdated' && m.number === 1);
    assert.equal(statusUpdate?.hasReviewDraft, false);

    // Re-selecting the same PR without author (e.g. reload/cancel fallback) preserves activePR.author.
    await h.prHandlers.handleSelectPR(h.state, { number: 1, owner: 'acme', repo: 'widgets' });
    assert.equal(h.state.activePR?.author, 'alice');
});

test('reads PendingDraftIndex after providerSetupReadiness resolves and clears stale index when only crash recovery remains', async () => {
    const memento = memoryMemento();
    const h = createHarness(memento);

    // Start refresh while providerSetupReadiness is suspended, then save a draft on PR #1 before readiness resolves.
    let releaseReadiness!: () => void;
    h.setReadinessGate(new Promise<void>((resolve) => { releaseReadiness = resolve; }));
    const inFlightRefresh = h.prHandlers.handleRefreshPRs(h.state, {});
    await new Promise((resolve) => setImmediate(resolve));

    await h.prHandlers.handleSelectPR(h.state, { number: 1, owner: 'acme', repo: 'widgets', author: 'alice' });
    await h.reviewHandlers.handleSaveDraft(h.state, {
        number: 1,
        owner: 'acme',
        repo: 'widgets',
        saveId: 2,
        result: { summary: 'Saved during readiness probe', verdict: 'COMMENT', lineComments: [] },
        orphans: [],
    });
    assert.equal(h.pendingDraftIndex.has('acme', 'widgets', 1), true);

    releaseReadiness();
    await inFlightRefresh;
    h.setReadinessGate(null);

    const listLoaded = [...h.pushed].reverse().find((m) => m.type === 'prListLoaded') as { prs: Array<{ number: number; hasReviewDraft: boolean }> };
    assert.equal(listLoaded.prs.find((p) => p.number === 1)?.hasReviewDraft, true);

    // If the remote GitHub draft was deleted externally while a local crash recovery snapshot exists,
    // selecting PR #1 removes the stale PendingDraftIndex entry.
    h.clearRemoteDrafts();
    await h.recoveryStore.save('acme/widgets#1', { summary: 'Local crash recovery', verdict: 'COMMENT', lineComments: [] }, []);
    await h.prHandlers.handleSelectPR(h.state, { number: 1, owner: 'acme', repo: 'widgets', author: 'alice' });
    assert.equal(h.pendingDraftIndex.has('acme', 'widgets', 1), false);
});

test('aborts stale handleSelectPR publication if selectionRevision advances while memento.update is pending', async () => {
    let releaseUpdate!: () => void;
    let blockNextUpdate = false;
    const data = new Map<string, unknown>();
    const slowMemento: RecoveryMemento = {
        get<T>(key: string): T | undefined {
            return data.get(key) as T | undefined;
        },
        async update(key: string, value: unknown): Promise<void> {
            if (blockNextUpdate) {
                blockNextUpdate = false;
                await new Promise<void>((resolve) => { releaseUpdate = resolve; });
            }
            data.set(key, value);
        },
    };

    const h = createHarness(slowMemento);
    h.setRemoteDraft('acme/widgets#1', 'rev-1');
    blockNextUpdate = true;

    const staleSelect = h.prHandlers.handleSelectPR(h.state, { number: 1, owner: 'acme', repo: 'widgets', author: 'alice' });
    await new Promise((resolve) => setImmediate(resolve));

    // Switch to PR #2 while PR #1's pendingDraftIndex.add is suspended.
    await h.prHandlers.handleSelectPR(h.state, { number: 2, owner: 'acme', repo: 'widgets', author: 'bob' });
    releaseUpdate();
    await staleSelect;

    assert.equal(h.state.activePR?.number, 2);
    assert.equal(h.pushed.some((m) => m.type === 'draftLoaded' && m.prKey === 'acme/widgets#1'), false);
});
