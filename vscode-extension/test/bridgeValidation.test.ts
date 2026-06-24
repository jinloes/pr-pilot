import test from 'node:test';
import assert from 'node:assert/strict';

import { isValidBridgeRequest } from '../src/bridgeValidation';

test('accepts valid PR-scoped request', () => {
  assert.equal(
    isValidBridgeRequest({ type: 'generateReview', number: 42, owner: 'acme', repo: 'platform' }),
    true,
  );
});

test('rejects unknown type', () => {
  assert.equal(isValidBridgeRequest({ type: 'surprise' }), false);
});

test('rejects PR-scoped request without valid identity', () => {
  assert.equal(
    isValidBridgeRequest({ type: 'selectPR', number: 0, owner: '', repo: '' }),
    false,
  );
});

test('accepts openUrl only when url is a string', () => {
  assert.equal(isValidBridgeRequest({ type: 'openUrl', url: 'https://example.com' }), true);
  assert.equal(isValidBridgeRequest({ type: 'openUrl', url: 42 }), false);
});

test('accepts setup runAuthLogin action', () => {
  assert.equal(isValidBridgeRequest({ type: 'runAuthLogin' }), true);
});

