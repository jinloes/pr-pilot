import test from 'node:test';
import assert from 'node:assert/strict';

import { hasStaleCommits } from '../src/draftState';

test('hasStaleCommits returns false when saved commit id is empty', () => {
  assert.equal(hasStaleCommits('', 'abc123'), false);
  assert.equal(hasStaleCommits(undefined, 'abc123'), false);
});

test('hasStaleCommits returns false when current head sha is empty', () => {
  assert.equal(hasStaleCommits('abc123', ''), false);
  assert.equal(hasStaleCommits('abc123', null), false);
});

test('hasStaleCommits returns false when commits match', () => {
  assert.equal(hasStaleCommits('abc123', 'abc123'), false);
});

test('hasStaleCommits returns true when commits differ', () => {
  assert.equal(hasStaleCommits('abc123', 'def456'), true);
});

