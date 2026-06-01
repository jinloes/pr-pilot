import test from 'node:test';
import assert from 'node:assert/strict';

import { DEFAULT_REASONING_EFFORT, normalizeReasoningEffort } from '../src/copilot';

test('normalizeReasoningEffort keeps supported values', () => {
  assert.equal(normalizeReasoningEffort('low'), 'low');
  assert.equal(normalizeReasoningEffort('medium'), 'medium');
  assert.equal(normalizeReasoningEffort('high'), 'high');
  assert.equal(normalizeReasoningEffort('xhigh'), 'xhigh');
});

test('normalizeReasoningEffort trims and lowercases supported values', () => {
  assert.equal(normalizeReasoningEffort('  HIGH  '), 'high');
});

test('normalizeReasoningEffort maps none to low', () => {
  assert.equal(normalizeReasoningEffort('none'), 'low');
});

test('normalizeReasoningEffort maps max to xhigh', () => {
  assert.equal(normalizeReasoningEffort('max'), 'xhigh');
});

test('normalizeReasoningEffort falls back to default on unknown values', () => {
  assert.equal(normalizeReasoningEffort('ultra'), DEFAULT_REASONING_EFFORT);
});

test('normalizeReasoningEffort falls back to default on blank input', () => {
  assert.equal(normalizeReasoningEffort('   '), DEFAULT_REASONING_EFFORT);
});
