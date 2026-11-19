import test from 'node:test';
import assert from 'node:assert/strict';

import {
  cachedModels,
  discoverModels,
  invalidateModelCache,
  listModels,
  type CopilotModelClient,
} from '../src/copilot';

test('listModels delegates to sidecar and caches non-empty model lists', async () => {
  invalidateModelCache();
  let calls = 0;
  let lastForceRefresh: boolean | undefined;
  const sidecar: CopilotModelClient = {
    async listCopilotModels(forceRefresh) {
      calls++;
      lastForceRefresh = forceRefresh;
      return {
        models: [' claude-sonnet-4.6 ', 'gpt-5.4', 'claude-sonnet-4.6', ''],
        source: 'ACCOUNT',
        failureReason: '',
      };
    },
  };

  const first = await listModels(sidecar);
  assert.deepEqual(first, ['claude-sonnet-4.6', 'gpt-5.4']);
  assert.deepEqual(cachedModels(), ['claude-sonnet-4.6', 'gpt-5.4']);
  assert.equal(calls, 1);
  assert.equal(lastForceRefresh, false);

  const cached = await listModels(sidecar, false);
  assert.deepEqual(cached, ['claude-sonnet-4.6', 'gpt-5.4']);
  assert.equal(calls, 1);

  const refreshed = await listModels(sidecar, true);
  assert.deepEqual(refreshed, ['claude-sonnet-4.6', 'gpt-5.4']);
  assert.equal(calls, 2);
  assert.equal(lastForceRefresh, true);
});

test('listModels preserves previous cached models when refresh fails or returns empty', async () => {
  invalidateModelCache();
  const warmSidecar: CopilotModelClient = {
    async listCopilotModels() {
      return { models: ['gpt-5.4'], source: 'ACCOUNT', failureReason: '' };
    },
  };
  await listModels(warmSidecar, true);
  assert.deepEqual(cachedModels(), ['gpt-5.4']);

  const emptySidecar: CopilotModelClient = {
    async listCopilotModels() {
      return { models: [], source: 'NONE', failureReason: 'not signed in' };
    },
  };
  const onEmpty = await listModels(emptySidecar, true);
  assert.deepEqual(onEmpty, []);
  assert.deepEqual(cachedModels(), ['gpt-5.4']);

  const throwingSidecar: CopilotModelClient = {
    async listCopilotModels() {
      throw new Error('RPC timeout');
    },
  };
  const onError = await listModels(throwingSidecar, true);
  assert.deepEqual(onError, []);
  assert.deepEqual(cachedModels(), ['gpt-5.4']);
  invalidateModelCache();
});

test('discoverModels does not overwrite cached ACCOUNT models with CLI_HELP fallback', async () => {
  invalidateModelCache();
  const accountSidecar: CopilotModelClient = {
    async listCopilotModels() {
      return { models: ['claude-opus-5.5', 'gpt-5.5'], source: 'ACCOUNT', failureReason: '' };
    },
  };
  await discoverModels(accountSidecar, true);
  assert.deepEqual(cachedModels(), ['claude-opus-5.5', 'gpt-5.5']);

  const helpFallbackSidecar: CopilotModelClient = {
    async listCopilotModels() {
      return { models: ['stale-cli-model'], source: 'CLI_HELP', failureReason: 'not signed in' };
    },
  };
  const fallback = await discoverModels(helpFallbackSidecar, true);
  assert.equal(fallback.source, 'CLI_HELP');
  assert.equal(fallback.failureReason, 'not signed in');
  assert.deepEqual(fallback.models, ['stale-cli-model']);
  assert.deepEqual(cachedModels(), ['claude-opus-5.5', 'gpt-5.5']);
  invalidateModelCache();
});
