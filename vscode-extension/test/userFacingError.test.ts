import test from 'node:test';
import assert from 'node:assert/strict';

import { GitHubOperationError, OUTPUT_PANEL_HINT, toUserFacingError } from '../src/userFacingError';
import { setLogSink } from '../src/log';

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
  assert.equal(msg, "Couldn't load PR details. Please retry." + OUTPUT_PANEL_HINT);
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
  assert.equal(msg, "Couldn't load PR details. Please retry." + OUTPUT_PANEL_HINT);
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

test('maps certificate validation failures to truststore guidance', () => {
  const msg = toUserFacingError(
    new Error("Java could not verify GitHub's TLS certificate. The Java runtime's truststore (lib/security/cacerts) may be missing public root CAs."),
    'load pull requests',
  );
  assert.match(msg, /^Couldn't load pull requests: Java could not verify GitHub's TLS certificate/);
  assert.match(msg, /cacerts/);
});

test('maps an unreachable GitHub to network guidance', () => {
  const msg = toUserFacingError(new Error('Unable to reach GitHub. Check your connection.'), 'load pull requests');
  assert.match(msg, /^Network error while trying to load pull requests/);
});

test('falls back to context-specific generic message', () => {
  const msg = toUserFacingError(new Error('some weird low-level failure'), 'delete draft review');
  assert.equal(msg, "Couldn't delete draft review. Please retry." + OUTPUT_PANEL_HINT);
});

test('names a git command timeout and drops the diff-size advice', () => {
  const msg = toUserFacingError(new Error('git fetch timed out after 60s'), 'generate review');
  assert.equal(msg, "'git fetch' timed out while trying to generate review. Check your VPN and your git access to"
    + ' the repository (SSH key or credentials), then retry.' + OUTPUT_PANEL_HINT);
});

test('names Copilot startup timeouts, including MCP server startup', () => {
  for (const cause of ['copilot runtime startup timed out after 60s', 'copilot session creation timed out after 60s']) {
    const msg = toUserFacingError(new Error(cause), 'generate review');
    assert.match(msg, /^GitHub Copilot did not start in time while trying to generate review\./);
    assert.match(msg, /MCP servers/);
    assert.doesNotMatch(msg, /smaller diff/);
  }
});

test('names the sidecar request that hit its timeout', () => {
  const msg = toUserFacingError(
    new Error('PR Pilot Java sidecar request "prs/getCheckStatus" timed out. Try the request again.'),
    'generate review',
  );
  assert.match(msg, /^The 'prs\/getCheckStatus' request timed out while trying to generate review\. Check your connection to GitHub/);
});

test('keeps the diff and effort advice for a provider run timeout and points at the output panel', () => {
  const msg = toUserFacingError(new Error('Review timed out — provider pass exceeded its time limit.'), 'generate review');
  assert.match(msg, /smaller diff \/ lower reasoning effort/);
  assert.ok(msg.endsWith(OUTPUT_PANEL_HINT));
});

test('does not point at the output panel when the message already explains the fix', () => {
  const msg = toUserFacingError(new Error('401 Unauthorized: bad credentials'), 'save draft review');
  assert.ok(!msg.includes(OUTPUT_PANEL_HINT));
});

test('logs the raw cause behind every user-facing error', () => {
  const lines: string[] = [];
  const previous = setLogSink((line) => lines.push(line));
  try {
    toUserFacingError(new Error('git fetch timed out after 60s'), 'generate review');
  } finally {
    setLogSink(previous);
  }
  assert.deepEqual(lines, ["[error] Couldn't generate review: git fetch timed out after 60s"]);
});
