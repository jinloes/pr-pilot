import test from 'node:test';
import assert from 'node:assert/strict';

import { classifySetupAuthError } from '../src/authError';

test('classifies missing gh binary as gh_not_installed', () => {
  const reason = classifySetupAuthError(new Error('spawn gh ENOENT'));
  assert.equal(reason, 'gh_not_installed');
});

test('classifies GitHub API 401 as gh_not_authenticated', () => {
  const reason = classifySetupAuthError(
    new Error('GitHub API GET 401: {"message":"Bad credentials"}')
  );
  assert.equal(reason, 'gh_not_authenticated');
});

test('classifies GitHub API 403 as gh_not_authenticated', () => {
  const reason = classifySetupAuthError(
    new Error('GitHub API GET 403: {"message":"forbidden"}')
  );
  assert.equal(reason, 'gh_not_authenticated');
});

test('returns null for non-auth failures', () => {
  const reason = classifySetupAuthError(new Error('request timed out while loading pull requests'));
  assert.equal(reason, null);
});
