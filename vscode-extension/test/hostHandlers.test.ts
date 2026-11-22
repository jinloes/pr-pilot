import test from 'node:test';
import assert from 'node:assert/strict';
import * as path from 'node:path';
import { BASELINE, generate, host, plain } from './hostHandlersSupport';

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

test('cached local diff requires matching fresh revisions before starting a review', async () => {
    const unchanged = await generate({ cachedLocal: true });
    assert.equal(unchanged.generateCalls[0]?.diff, 'cached-local');
    for (const options of [
        { currentHead: 'e'.repeat(40) },
        { currentBase: 'e'.repeat(40), chunked: true },
        { metadataUnavailable: true },
    ]) {
        const run = await generate({ cachedLocal: true, ...options });
        assert.equal(run.generateCalls.length, 0);
        assert.match(String(run.error?.message), /reopen the pull request/i);
    }
});

test('ordinary review uses the exact local PR comparison when GitHub refuses the diff', async () => {
    const run = await generate({ oversized: true });

    assert.deepEqual(plain(run.localSources), Array(2).fill({
        worktreeDir: '/fixture/wt', baseSha: 'd'.repeat(40), headSha: 'c'.repeat(40),
    }));
    assert.equal(run.generateCalls[0].diff, 'pr-diff');
    assert.equal(run.result?.validationDiff, 'pr-validation-diff');
    assert.equal(run.error, undefined);
});

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

test('incremental generation runs a full review when PR scoping is unavailable', async () => {
    const run = await generate({ incremental: true, incrementalResult: { status: 'ok', message: 'Falling back.',
        scope: 'full', fallbackReason: 'pr_scope_unavailable', limitBytes: 250000 } });

    assert.equal(run.generateCalls[0].diff, 'pr-diff');
    assert.equal(run.generateCalls[0].incrementalBaselineSha, undefined);
    assert.equal(run.result?.diff, 'pr-diff');
    assert.deepEqual(run.result?.reviewScope, { kind: 'full', fallbackReason: 'pr_scope_unavailable' });
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

test('selectPR prefetches commits and linked issues and generateReview reuses them while headSha matches', async () => {
    const h = host({});
    let commitCalls = 0;
    let issueCalls = 0;
    let currentHead = '1'.repeat(40);
    const generateCalls: Record<string, unknown>[] = [];
    h.api.setClient({
        cancelReview: async () => {},
        findGitRoot: async () => '/fixture',
        detectRepo: async () => 'acme/widget',
        createWorktree: async () => ({ status: 'created', worktreeDir: '/fixture/wt', message: '' }),
        removeWorktree: async () => {},
        getDraftReview: async () => ({ status: 'none' }),
        getPullRequestDiff: async () => ({ status: 'ok', message: 'ok', diff: 'pr-diff', truncated: false, limitBytes: 250000 }),
        getExistingReviews: async () => ({ status: 'ok', summary: 'Prior reviewer note' }),
        getPullRequestDetail: async () => ({
            status: 'ok',
            detail: {
                title: 'Fast PR',
                body: 'Closes #9',
                merged: false,
                baseSha: '0'.repeat(40),
                baseRepoFullName: 'acme/widget',
                head: { ref: 'feat', sha: currentHead, repoFullName: 'acme/widget' },
            },
        }),
        getCommits: async () => {
            commitCalls += 1;
            return { summary: `commit-${commitCalls}`, closingIssueNumbers: [9] };
        },
        getLinkedIssues: async () => {
            issueCalls += 1;
            return `issue-${issueCalls}`;
        },
        getCheckStatus: async () => ({ summary: 'checks-ok', annotations: [] }),
        getRepoProfile: async () => 'Java',
        generateReview: async (params: Record<string, unknown>) => {
            generateCalls.push(params);
            return { summary: 'Done.', verdict: 'COMMENT', lineComments: [] };
        },
    });
    Object.assign(h.state, {
        generatedReviews: new Map(),
        draftRecoveryStore: { get: () => null, clear: async () => {} },
    });

    await h.api.select(h.state, { number: 42, owner: 'acme', repo: 'widget', title: 'Fast PR', body: 'Closes #9' });
    await h.api.generate(h.state, { type: 'generateReview', operationId: 'op-1', number: 42, owner: 'acme', repo: 'widget' });

    assert.equal(commitCalls, 1, 'generateReview should reuse selectPR commit prefetch when headSha matches');
    assert.equal(issueCalls, 1, 'generateReview should reuse selectPR linked-issue prefetch when headSha matches');
    assert.equal(generateCalls[0].commits, 'commit-1');
    assert.equal(generateCalls[0].linkedIssue, 'issue-1');

    currentHead = '2'.repeat(40);
    await h.api.generate(h.state, { type: 'generateReview', operationId: 'op-2', number: 42, owner: 'acme', repo: 'widget' });
    assert.equal(commitCalls, 2, 'generateReview should refetch commits when headSha advances');
    assert.equal(generateCalls[1].commits, 'commit-2');
});

test('generation resolves the active provider second reviewer and sends secondReviewerEffort only for Copilot', async () => {
    const copilotDefaulted = await generate({ settings: { reviewProvider: 'copilot', reviewEffort: 'xhigh',
        reviewSecondReviewerModel: 'gemini-4-argon' } });
    const copilotConfigured = await generate({ settings: { reviewProvider: 'copilot',
        reviewSecondReviewerModel: 'gpt-6.1-sol', reviewSecondReviewerEffort: 'low' } });
    const claudeActive = await generate({ settings: { reviewProvider: 'claude',
        reviewSecondReviewerModel: 'gpt-6.1-sol', reviewSecondReviewerModelClaude: 'claude-sonnet-4-6',
        reviewSecondReviewerEffort: 'low' } });
    const antigravityActive = await generate({ settings: { reviewProvider: 'antigravity',
        reviewSecondReviewerModel: 'gpt-6.1-sol', reviewSecondReviewerModelAntigravity: 'gemini-3.1-pro-high',
        reviewSecondReviewerEffort: 'low' } });
    const off = await generate({ settings: { reviewSecondReviewerEffort: 'low' } });

    assert.equal(copilotDefaulted.generateCalls[0].effort, 'xhigh');
    assert.equal(copilotDefaulted.generateCalls[0].secondReviewerModel, 'gemini-4-argon');
    assert.equal(copilotDefaulted.generateCalls[0].secondReviewerEffort, 'medium');
    assert.equal(copilotConfigured.generateCalls[0].secondReviewerEffort, 'low');
    assert.equal(claudeActive.generateCalls[0].secondReviewerModel, 'claude-sonnet-4-6');
    assert.equal(claudeActive.generateCalls[0].secondReviewerEffort, undefined);
    assert.equal(antigravityActive.generateCalls[0].secondReviewerModel, 'gemini-3.1-pro-high');
    assert.equal(antigravityActive.generateCalls[0].secondReviewerEffort, undefined);
    assert.equal(off.generateCalls[0].secondReviewerModel, undefined);
    assert.equal(off.generateCalls[0].secondReviewerEffort, undefined);
});

test('openFile opens editable workspace file first and read-only preview scheme for PR-only worktree file', async (t) => {
    const fsMod = await import('node:fs');
    const osMod = await import('node:os');
    const root = fsMod.mkdtempSync(path.join(osMod.tmpdir(), 'pr-pilot-host-openfile-'));
    t.after(() => fsMod.rmSync(root, { recursive: true, force: true }));
    const ws = path.join(root, 'ws');
    const wt = path.join(root, 'wt');
    fsMod.mkdirSync(path.join(ws, 'src'), { recursive: true });
    fsMod.mkdirSync(path.join(wt, 'src'), { recursive: true });
    fsMod.writeFileSync(path.join(ws, 'src', 'app.ts'), 'export const a = 1;\n');
    fsMod.writeFileSync(path.join(wt, 'src', 'newInPr.ts'), 'export const b = 2;\n');

    const h = host({ workspaceRoot: ws });
    h.api.setClient({
        findGitRoot: async () => ws,
        detectRepo: async () => 'acme/widget',
    });
    h.state.activePR = { number: 42, owner: 'acme', repo: 'widget', title: 'Test', body: '' };
    h.state.worktreeDir = wt;
    h.state.worktreeKey = 'acme/widget#42';

    await h.api.openFile(h.state, { type: 'openFile', number: 42, owner: 'acme', repo: 'widget', file: 'src/app.ts' });
    assert.equal(h.openedDocs.length, 1);
    assert.equal(h.openedDocs[0].uri.scheme, 'file');
    assert.equal((h.openedDocs[0].options.selection as { startLine: number }).startLine, 0);

    await h.api.openFile(h.state, { type: 'openFile', number: 42, owner: 'acme', repo: 'widget', file: 'src/newInPr.ts' });
    assert.equal(h.openedDocs.length, 2);
    assert.equal(h.openedDocs[1].uri.scheme, 'pr-pilot-worktree');
    assert.equal(h.openedDocs[1].uri.path, '/acme/widget/PR-42/src/newInPr.ts');
    assert.match(String(h.openedDocs[1].uri.query ?? ''), /^wt=[0-9a-f]{12}$/);

    await h.api.openFile(h.state, { type: 'openFile', number: 99, owner: 'acme', repo: 'widget', file: 'src/app.ts' });
    assert.equal(h.openedDocs.length, 2, 'stale PR openFile must be ignored');
});
