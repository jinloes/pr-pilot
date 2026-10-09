import test from 'node:test';
import assert from 'node:assert/strict';

import { BRIDGE_PROTOCOL_VERSION, isValidBridgeRequest } from '../src/bridgeValidation';

const version = { protocolVersion: BRIDGE_PROTOCOL_VERSION };

test('accepts valid PR-scoped request', () => {
  assert.equal(
    isValidBridgeRequest({
      ...version,
      type: 'generateReview',
      operationId: 'review-1',
      number: 42,
      owner: 'acme',
      repo: 'platform',
      diff: 'diff --git a/a.ts b/a.ts',
    }),
    true,
  );
});

test('rejects invalid or oversized batch diffs', () => {
  const base = { ...version, type: 'generateReview', operationId: 'review-1', number: 42, owner: 'acme', repo: 'platform' };
  assert.equal(isValidBridgeRequest({ ...base, diff: 42 }), false);
  assert.equal(isValidBridgeRequest({ ...base, diff: 'x'.repeat(1_100_001) }), false);
});

test('accepts only a boolean incremental flag on generateReview', () => {
  const base = { ...version, type: 'generateReview', operationId: 'review-1', number: 42, owner: 'acme', repo: 'platform' };
  assert.equal(isValidBridgeRequest({ ...base, incremental: true }), true);
  assert.equal(isValidBridgeRequest({ ...base, incremental: false }), true);
  assert.equal(isValidBridgeRequest({ ...base, incremental: 'yes' }), false);
  assert.equal(isValidBridgeRequest({ ...base, incremental: 1 }), false);
});

test('rejects unknown type', () => {
  assert.equal(isValidBridgeRequest({ ...version, type: 'surprise' }), false);
});

test('rejects PR-scoped request without valid identity', () => {
  assert.equal(
    isValidBridgeRequest({ ...version, type: 'selectPR', number: 0, owner: '', repo: '' }),
    false,
  );
});

test('accepts openUrl only when url is a string', () => {
  assert.equal(isValidBridgeRequest({ ...version, type: 'openUrl', url: 'https://example.com' }), true);
  assert.equal(isValidBridgeRequest({ ...version, type: 'openUrl', url: 42 }), false);
});

test('accepts setup runAuthLogin action', () => {
  assert.equal(isValidBridgeRequest({ ...version, type: 'runAuthLogin' }), true);
});

test('validates webview layout change reasons', () => {
  assert.equal(isValidBridgeRequest({ ...version, type: 'webviewLayoutChanged', reason: 'chat-panel' }), true);
  assert.equal(isValidBridgeRequest({ ...version, type: 'webviewLayoutChanged' }), false);
  assert.equal(isValidBridgeRequest({ ...version, type: 'webviewLayoutChanged', reason: 42 }), false);
  assert.equal(
    isValidBridgeRequest({ ...version, type: 'webviewLayoutChanged', reason: 'x'.repeat(4_097) }),
    false,
  );
});

test('rejects unversioned messages', () => {
  assert.equal(isValidBridgeRequest({ type: 'runAuthLogin' }), false);
});

test('validates nested review fields', () => {
  const base = { ...version, type: 'saveDraft', number: 42, owner: 'acme', repo: 'platform', saveId: 1 };
  assert.equal(isValidBridgeRequest({
    ...base,
    result: {
      summary: 'Summary',
      verdict: 'COMMENT',
      lineComments: [{ file: 'src/a.ts', line: 1, type: 'note', body: 'Body', category: 'compatibility', confidence: 'high' }],
    },
    generatedResult: { summary: 'Generated', verdict: 'COMMENT', lineComments: [] },
  }), true);
  assert.equal(isValidBridgeRequest({
    ...base,
    result: { summary: 'Summary', verdict: 'INVALID', lineComments: [] },
  }), false);
  assert.equal(isValidBridgeRequest({
    ...base,
    generatedResult: { summary: 'Generated', verdict: 'INVALID', lineComments: [] },
  }), false);
});

test('rejects draft saves without a positive correlation ID', () => {
  const base = { ...version, type: 'saveDraft', number: 42, owner: 'acme', repo: 'platform' };
  assert.equal(isValidBridgeRequest(base), false);
  assert.equal(isValidBridgeRequest({ ...base, saveId: 0 }), false);
  assert.equal(isValidBridgeRequest({ ...base, saveId: 1 }), true);
});

test('validates refresh compatibility booleans', () => {
  assert.equal(isValidBridgeRequest({
    ...version, type: 'refreshPRs', assignedToMe: true, reviewRequested: false,
  }), true);
  assert.equal(isValidBridgeRequest({
    ...version, type: 'refreshPRs', assignedToMe: 'yes',
  }), false);
});

test('rejects oversized identities and payloads', () => {
  assert.equal(isValidBridgeRequest({
    ...version, type: 'selectPR', number: 42, owner: 'x'.repeat(257), repo: 'platform',
  }), false);
  assert.equal(isValidBridgeRequest({
    ...version, type: 'askClaude', operationId: 'chat-1', question: 'x'.repeat(100_001), context: '',
  }), false);
});

test('requires bounded operation IDs for cancellable requests', () => {
  assert.equal(isValidBridgeRequest({ ...version, type: 'cancelReview' }), false);
  assert.equal(isValidBridgeRequest({ ...version, type: 'cancelReview', operationId: '  ' }), false);
  assert.equal(isValidBridgeRequest({ ...version, type: 'cancelReview', operationId: 'review-1' }), true);
  assert.equal(isValidBridgeRequest({ ...version, type: 'askClaude', operationId: 'chat-1', question: 'x' }), true);
});

test('rejects invalid rich comment metadata', () => {
  const base = { ...version, type: 'saveDraft', number: 42, owner: 'acme', repo: 'platform', saveId: 1 };
  assert.equal(isValidBridgeRequest({
    ...base,
    result: {
      summary: 'Summary',
      verdict: 'COMMENT',
      lineComments: [{ file: 'src/a.ts', line: 1, type: 'note', body: 'Body', severity: 'urgent' }],
    },
  }), false);
  assert.equal(isValidBridgeRequest({
    ...base,
    result: {
      summary: 'Summary',
      verdict: 'COMMENT',
      lineComments: [{ file: 'src/a.ts', line: 1, type: 'note', body: 'Body', category: 'boundary' }],
    },
  }), false);
});

test('accepts an optional bounded suggestedChange and rejects null or non-strings', () => {
  const base = { ...version, type: 'saveDraft', number: 42, owner: 'acme', repo: 'platform', saveId: 1 };
  const save = (suggestedChange: unknown) => isValidBridgeRequest({
    ...base,
    result: {
      summary: 'Summary',
      verdict: 'COMMENT',
      lineComments: [{ file: 'src/a.ts', line: 1, type: 'issue', body: 'Body', suggestedChange }],
    },
  });

  assert.equal(save(undefined), true);
  assert.equal(save('  return a;'), true);
  assert.equal(save('x'.repeat(1_000)), true);
  assert.equal(save('x'.repeat(1_001)), false);
  assert.equal(save(null), false);
  assert.equal(save(3), false);
});

test('accepts cancelChat only with a bounded operation ID', () => {
  assert.equal(isValidBridgeRequest({ ...version, type: 'cancelChat', operationId: 'chat-1' }), true);
  assert.equal(isValidBridgeRequest({ ...version, type: 'cancelChat' }), false);
  assert.equal(isValidBridgeRequest({ ...version, type: 'cancelChat', operationId: '' }), false);
  assert.equal(isValidBridgeRequest({ ...version, type: 'cancelChat', operationId: 'x'.repeat(129) }), false);
  assert.equal(isValidBridgeRequest({ ...version, type: 'cancelChat', operationId: 'bad\nid' }), false);
});
