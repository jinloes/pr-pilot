import test from 'node:test';
import assert from 'node:assert/strict';

import { DEFAULT_REASONING_EFFORT, filterModelIds, normalizeReasoningEffort, withTimeout } from '../src/copilot';

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

test('withTimeout returns resolved value before timeout', async () => {
  const value = await withTimeout(Promise.resolve('ok'), 50, 'runtime startup');
  assert.equal(value, 'ok');
});

test('withTimeout rejects when operation exceeds timeout', async () => {
  const never = new Promise<string>(() => undefined);
  await assert.rejects(
    withTimeout(never, 20, 'session creation'),
    /copilot session creation timed out after 1s/,
  );
});

test('filterModelIds returns enabled model IDs in order', () => {
  const ids = filterModelIds([
    { id: 'claude-sonnet-4.6', policy: { state: 'enabled' } },
    { id: 'gpt-5.5', policy: { state: 'unconfigured' } },
    { id: 'gpt-5.4' },
  ]);
  assert.deepEqual(ids, ['claude-sonnet-4.6', 'gpt-5.5', 'gpt-5.4']);
});

test('filterModelIds drops policy-disabled models', () => {
  const ids = filterModelIds([
    { id: 'enabled-model', policy: { state: 'enabled' } },
    { id: 'blocked-model', policy: { state: 'disabled' } },
  ]);
  assert.deepEqual(ids, ['enabled-model']);
});

test('filterModelIds drops blank and non-string ids', () => {
  const ids = filterModelIds([
    { id: '   ', policy: { state: 'enabled' } },
    { id: 42 as unknown as string },
    { id: 'real', policy: { state: 'enabled' } },
  ]);
  assert.deepEqual(ids, ['real']);
});

test('filterModelIds de-dupes while preserving order', () => {
  const ids = filterModelIds([
    { id: 'a' },
    { id: 'b' },
    { id: 'a' },
  ]);
  assert.deepEqual(ids, ['a', 'b']);
});

test('filterModelIds returns empty for empty input', () => {
  assert.deepEqual(filterModelIds([]), []);
});
