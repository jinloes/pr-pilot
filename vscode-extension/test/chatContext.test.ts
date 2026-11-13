import test from 'node:test';
import assert from 'node:assert/strict';

import { buildChatContext, chatAttachment } from '../src/reviewHandlers';
import type { ViewState } from '../src/extensionTypes';

function state(overrides: Partial<ViewState>): ViewState {
    return {
        activePR: null,
        activeDiff: '',
        activeReviewResult: null,
        ...overrides,
    } as ViewState;
}

test('buildChatContext is undefined without an active PR', () => {
    assert.equal(buildChatContext(state({})), undefined);
});

test('buildChatContext carries PR metadata, diff, review, and attachment', () => {
    const context = buildChatContext(state({
        activePR: { number: 7, owner: 'acme', repo: 'widgets', title: 'Add cache', body: 'Body', author: 'octo' },
        activeDiff: 'diff --git a/A b/A\n',
        activeReviewResult: {
            summary: 'One race.',
            verdict: 'REQUEST_CHANGES',
            lineComments: [{ file: 'src/A.ts', line: 3, type: 'issue', severity: 'major', body: 'Null deref.' }],
        },
    }), { text: 'x', file: 'src/A.ts', startLine: 3, endLine: 3 });

    assert.deepEqual(context, {
        owner: 'acme',
        repo: 'widgets',
        number: 7,
        title: 'Add cache',
        author: 'octo',
        body: 'Body',
        diff: 'diff --git a/A b/A\n',
        reviewVerdict: 'REQUEST_CHANGES',
        reviewSummary: 'One race.',
        reviewComments: [{ file: 'src/A.ts', line: 3, type: 'issue', severity: 'major', body: 'Null deref.' }],
        attachment: { text: 'x', file: 'src/A.ts', startLine: 3, endLine: 3 },
    });
});

test('buildChatContext omits an empty diff and a missing review', () => {
    const context = buildChatContext(state({
        activePR: { number: 7, owner: 'acme', repo: 'widgets', title: 't', body: '' },
    }));

    assert.equal(context?.diff, undefined);
    assert.equal(context?.reviewVerdict, undefined);
    assert.deepEqual(context?.reviewComments, []);
});

test('chatAttachment is undefined without attached text', () => {
    assert.equal(chatAttachment({}), undefined);
    assert.equal(chatAttachment({ context: '   ' }), undefined);
});

test('chatAttachment keeps text without a location', () => {
    assert.deepEqual(chatAttachment({ context: 'int x;' }), { text: 'int x;' });
});

test('chatAttachment reads the location and drops invalid lines', () => {
    assert.deepEqual(
        chatAttachment({ context: 'x', contextLocation: { file: 'src/A.ts', startLine: 3, endLine: 5, oldSide: true } }),
        { text: 'x', file: 'src/A.ts', startLine: 3, endLine: 5, oldSide: true },
    );
    assert.deepEqual(
        chatAttachment({ context: 'x', contextLocation: { file: ' ', startLine: 0, endLine: 1.5 } }),
        { text: 'x', file: undefined, startLine: undefined, endLine: undefined, oldSide: false },
    );
});
