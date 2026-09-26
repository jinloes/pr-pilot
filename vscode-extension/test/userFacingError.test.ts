import test from 'node:test';
import assert from 'node:assert/strict';

import { GitHubOperationError, toUserFacingError } from '../src/userFacingError';

test('maps GitHub auth failures to gh auth guidance', () => {
  const msg = toUserFacingError(new Error('401 Unauthorized: bad credentials'), 'save draft review');
  assert.match(msg, /gh auth login/i);
});

test('preserves both possible causes for ambiguous GitHub 404 results', () => {
  const msg = toUserFacingError(
    new GitHubOperationError('not_found_or_inaccessible', 'Pull request not found or inaccessible.'),
    'load PR details',
  );
  assert.match(msg, /may not exist/i);
  assert.match(msg, /may not have access/i);
  assert.match(msg, /verify the PR URL/i);
  assert.match(msg, /active gh account/i);
  assert.match(msg, /gh auth status/i);
});

test('does not classify an ambiguous GitHub 404 from untyped prose', () => {
  const msg = toUserFacingError(
    new Error('Pull request not found or inaccessible to the active gh account.'),
    'load PR details',
  );
  assert.equal(msg, "Couldn't load PR details. Please retry.");
});

test('maps an oversized GitHub diff to the size-limit copy instead of retry guidance', () => {
  const msg = toUserFacingError(
    new GitHubOperationError(
      'diff_too_large',
      "GitHub declined to return this pull request's diff (HTTP 406); it likely exceeds GitHub's diff size limits.",
    ),
    'generate review',
  );
  assert.equal(
    msg,
    "Pull request diff is too large. GitHub will not return this pull request's diff because it exceeds GitHub's size limits. Review smaller pull requests or split this one.",
  );
  assert.doesNotMatch(msg, /retry/i);
});

test('keeps generic retry copy for an api_failed GitHub result', () => {
  const msg = toUserFacingError(new GitHubOperationError('api_failed', 'GitHub API request failed.'), 'load PR details');
  assert.equal(msg, "Couldn't load PR details. Please retry.");
});

test('maps provider binary missing errors to install guidance', () => {
  const msg = toUserFacingError(new Error('Cannot run program "copilot": error=2'), 'generate review');
  assert.match(msg, /copilot/i);
  assert.match(msg, /install/i);
});

test('maps timeout to retry guidance with context', () => {
  const msg = toUserFacingError(new Error('request timed out after 60s'), 'generate review');
  assert.match(msg, /timed out/i);
  assert.match(msg, /generate review/i);
});

test('maps parse errors to structured-output guidance', () => {
  const msg = toUserFacingError(new Error('Failed to parse review JSON: unexpected token'), 'generate review');
  assert.match(msg, /invalid review format/i);
});

test('falls back to context-specific generic message', () => {
  const msg = toUserFacingError(new Error('some weird low-level failure'), 'delete draft review');
  assert.equal(msg, "Couldn't delete draft review. Please retry.");
});
