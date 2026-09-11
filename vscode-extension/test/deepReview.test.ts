import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import * as path from 'node:path';
import { runInNewContext } from 'node:vm';
import { DeepReviewFlow } from '../src/deepReview';

type Call = (state: object, input: Record<string, unknown>) => Promise<void>;

/** Execute the compiled host callbacks, with only the external host/provider/sidecar replaced. */
function host() {
    let head = 'a'.repeat(40);
    let provider = 'claude';
    let providerCalls = 0;
    let detailHook = async () => {};
    let generateHook = async () => {};
    let prepareHook = async () => {};
    const removals: string[] = [];
    const messages: Record<string, unknown>[] = [];
    const preparation = { retainedId: '11111111-1111-4111-8111-111111111111',
        worktree: '/fixture/deep', head, servers: ['private'] };
    const client = {
        findGitRoot: async () => '/fixture',
        detectRepo: async () => 'acme/widget',
        getPullRequestDetail: async () => { await detailHook(); return { status: 'ok', detail: {
            baseRepoFullName: 'acme/widget', head: { sha: head, ref: 'fix', repoFullName: 'acme/widget' } } }; },
        getPullRequestDiff: async () => ({ status: 'ok', diff: 'fresh-diff' }),
        prepareDeepReview: async () => { await prepareHook(); return preparation; },
        listDeepReviews: async () => [{ id: preparation.retainedId, worktree: preparation.worktree }],
        cleanupDeepReview: async (id: string, closed: boolean) => {
            if (!closed) throw new Error('Close project first');
            removals.push(id);
        },
        getExistingReviews: async () => '',
        getCommits: async () => ({ summary: '', closingIssueNumbers: [] }),
        getCheckStatus: async () => ({ summary: '', annotations: [] }),
        getLinkedIssues: async () => '',
        getRepoProfile: async () => '',
        generateReview: async (params: Record<string, unknown>) => {
            providerCalls++;
            await generateHook();
            assert.equal(params.projectDir, preparation.worktree);
            assert.equal(params.diff, 'fresh-diff');
            assert.equal(JSON.stringify(params.deepReview), JSON.stringify({ retainedId: preparation.retainedId, server: 'private' }));
            return { summary: 'finished', verdict: 'COMMENT', lineComments: [] };
        },
        cancelReview: async () => {},
    };
    const vscode = { workspace: { workspaceFolders: [{ uri: { fsPath: '/fixture' } }],
        getConfiguration: () => ({ get: (key: string, fallback: unknown) => key === 'reviewProvider' ? provider : fallback }) } };
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
            ? '\nexports.testHost = {prepare: handlePrepareDeepReview, resume: handleContinueDeepReview, maintenance: handleDeepMaintenance, setClient: c => {sidecarClient=c}};'
            : '';
        runInNewContext(readFileSync(file, 'utf8') + expose, { module, exports: module.exports,
            require: requireLocal, __dirname: path.dirname(file), __filename: file,
            console, process, Buffer, setTimeout, clearTimeout, setInterval, clearInterval }, { filename: file });
        return module.exports;
    }
    const api = load(require.resolve('../src/extension')).testHost as {
        prepare: Call; resume: Call; maintenance: Call; setClient: (client: object) => void;
    };
    api.setClient(client);
    const state = { activePR: { number: 42, owner: 'acme', repo: 'widget', title: 'Test', body: '' },
        webview: { postMessage: (message: Record<string, unknown>) => messages.push(message) },
        selectionRevision: 1, generationRevision: 1, activeProviderOperation: null,
        activeReviewResult: null, activeDiff: 'stale-client-diff', activeValidationDiff: '',
        generatedReviews: new Map(), disposed: false, deepReview: new DeepReviewFlow() };
    const options = { number: 42, owner: 'acme', repo: 'widget', operationId: 'prepare-1', intellijAssisted: true };
    const resume = { ...options, operationId: 'continue-1', retainedId: preparation.retainedId, server: 'private' };
    return { api, state, options, resume, messages, removals, calls: () => providerCalls,
        onDetail: (hook: () => Promise<void>) => { detailHook = hook; },
        onPrepare: (hook: () => Promise<void>) => { prepareHook = hook; },
        onGenerate: (hook: () => Promise<void>) => { generateHook = hook; },
        setHead: () => { head = 'b'.repeat(40); }, setProvider: (value: string) => { provider = value; } };
}

test('actual host pauses without provider and resumes both configured provider paths once', async () => {
    for (const provider of ['claude', 'copilot']) {
        const h = host();
        h.setProvider(provider);
        await h.api.prepare(h.state, h.options);
        assert.equal(h.calls(), 0);
        assert.equal(h.messages[h.messages.length - 1]?.type, 'deepReviewPrepared');
        await h.api.resume(h.state, h.resume);
        assert.equal(h.calls(), 1);
        assert.equal(h.messages[h.messages.length - 1]?.type, 'reviewResult');
        const count = h.messages.length;
        await h.api.resume(h.state, h.resume);
        assert.equal(h.calls(), 1);
        assert.equal(h.messages.length, count, 'duplicate replies must not replace completed output');
    }
});

test('actual host retries readiness failure with fresh identity and preserves its previous draft', async () => {
    const h = host();
    await h.api.prepare(h.state, h.options);
    h.onGenerate(async () => { throw new Error('Manual sync required'); });
    await h.api.resume(h.state, h.resume);
    assert.equal(h.calls(), 1);
    assert.equal(h.state.activeReviewResult, null);
    assert.equal(h.messages[h.messages.length - 1]?.type, 'deepReviewPrepared');
    const count = h.messages.length;
    await h.api.resume(h.state, h.resume);
    assert.equal(h.messages.length, count);
    h.onGenerate(async () => {});
    await h.api.resume(h.state, { ...h.resume, operationId: 'retry-2' });
    assert.equal(h.calls(), 2);
    assert.equal(h.messages[h.messages.length - 1]?.type, 'reviewResult');
});

test('actual host suppresses delayed preparation and changed selection during Continue', async () => {
    const h = host();
    h.onPrepare(async () => { h.state.deepReview.invalidate(); });
    await h.api.prepare(h.state, h.options);
    assert.equal(h.messages.length, 0);
    h.onPrepare(async () => {});
    await h.api.prepare(h.state, h.options);
    h.onDetail(async () => { h.state.selectionRevision++; });
    const count = h.messages.length;
    await h.api.resume(h.state, h.resume);
    assert.equal(h.calls(), 0);
    assert.equal(h.messages.length, count);
});

test('actual host retries a transient preflight failure with a new operation only', async () => {
    const h = host();
    await h.api.prepare(h.state, h.options);
    h.onDetail(async () => { throw new Error('Temporary GitHub refresh failure'); });
    await h.api.resume(h.state, h.resume);
    assert.equal(h.calls(), 0);
    assert.equal(h.messages[h.messages.length - 1]?.type, 'reviewError');
    h.onDetail(async () => {});
    await h.api.resume(h.state, h.resume);
    assert.equal(h.calls(), 0);
    await h.api.resume(h.state, { ...h.resume, operationId: 'retry-2' });
    assert.equal(h.calls(), 1);
    assert.equal(h.messages[h.messages.length - 1]?.type, 'reviewResult');
});

test('failed preflight never restores cancelled, superseded or changed preparation', async () => {
    for (const change of ['cancel', 'prepare', 'selection', 'settings', 'disposal']) {
        const h = host();
        await h.api.prepare(h.state, h.options);
        h.onDetail(async () => {
            if (change === 'cancel') h.state.deepReview.cancel(h.resume.operationId);
            if (change === 'prepare') h.state.deepReview.start('prepare-2');
            if (change === 'selection') h.state.selectionRevision++;
            if (change === 'settings') h.setProvider('copilot');
            if (change === 'disposal') h.state.disposed = true;
            throw new Error('Temporary GitHub refresh failure');
        });
        await h.api.resume(h.state, h.resume);
        assert.equal(h.state.deepReview.peek(), undefined, change);
        assert.equal(h.calls(), 0, change);
    }
});

test('confirmed head change does not restore the old preparation', async () => {
    const h = host();
    await h.api.prepare(h.state, h.options);
    h.setHead();
    await h.api.resume(h.state, h.resume);
    assert.equal(h.state.deepReview.peek(), undefined);
    assert.equal(h.calls(), 0);
});

test('actual host never publishes a candidate after remote-head change during provider execution', async () => {
    const h = host();
    await h.api.prepare(h.state, h.options);
    h.onGenerate(async () => { h.setHead(); });
    await h.api.resume(h.state, h.resume);
    assert.equal(h.calls(), 1);
    assert.equal(h.state.activeReviewResult, null);
    assert.equal(h.messages.some(m => m.type === 'reviewResult'), false);
});

test('actual host exposes retained maintenance without a selected PR and requires confirmation', async () => {
    const h = host();
    const state = { ...h.state, activePR: null };
    await h.api.maintenance(state, { type: 'listDeepReviews', operationId: 'list-1' });
    assert.equal(h.messages[h.messages.length - 1]?.type, 'retainedDeepReviews');
    await h.api.maintenance(state, { type: 'cleanupDeepReview', operationId: 'remove-1',
        retainedId: h.resume.retainedId, projectClosed: false });
    assert.equal(h.messages[h.messages.length - 1]?.type, 'deepReviewMaintenanceError');
    assert.equal(h.removals.length, 0);
    await h.api.maintenance(state, { type: 'cleanupDeepReview', operationId: 'remove-2',
        retainedId: h.resume.retainedId, projectClosed: true });
    assert.deepEqual(h.removals, [h.resume.retainedId]);
    assert.equal(h.calls(), 0);
});

test('actual host rejects changed head, settings, selection and disposal before provider', async () => {
    for (const change of ['head', 'settings', 'selection', 'disposal']) {
        const h = host();
        await h.api.prepare(h.state, h.options);
        if (change === 'head') h.setHead();
        if (change === 'settings') h.setProvider('copilot');
        if (change === 'selection') h.state.selectionRevision++;
        if (change === 'disposal') h.state.disposed = true;
        await h.api.resume(h.state, h.resume);
        assert.equal(h.calls(), 0, change);
    }
});
