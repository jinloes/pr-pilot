import test from 'node:test';
import assert from 'node:assert/strict';

import { toUserFacingError } from '../src/userFacingError';

test('maps GitHub auth failures to gh auth guidance', () => {
  const msg = toUserFacingError(new Error('401 Unauthorized: bad credentials'), 'save draft review');
  assert.match(msg, /gh auth login/i);
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
