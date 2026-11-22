import test from 'node:test';
import assert from 'node:assert/strict';

import { buildGhAuthLoginCommand, toProviderReadiness } from '../src/providerSetup';
import { parseProviderSetupResult } from '../src/sidecarProtocol';
import { providerNotInstalledMessage } from '../src/userFacingError';

test('providerNotInstalledMessage names the Copilot CLI and binary', () => {
  const msg = providerNotInstalledMessage('copilot');
  assert.match(msg, /copilot/i);
  assert.match(msg, /GitHub Copilot CLI/);
  assert.match(msg, /try again/i);
});

test('providerNotInstalledMessage names the Claude CLI and binary', () => {
  const msg = providerNotInstalledMessage('claude');
  assert.match(msg, /claude/i);
  assert.match(msg, /Claude Code CLI/);
  assert.match(msg, /try again/i);
});

test('parseProviderSetupResult validates engine setup probe payloads and rejects invalid shapes', () => {
  assert.deepEqual(
    parseProviderSetupResult({
      available: true,
      binaryStatus: 'ready',
      authenticationStatus: 'ready',
      authCommand: 'claude auth login',
    }),
    {
      available: true,
      binaryStatus: 'ready',
      authenticationStatus: 'ready',
      authCommand: 'claude auth login',
    },
  );
  assert.equal(parseProviderSetupResult({ available: true, binaryStatus: 'unknown', authenticationStatus: 'ready', authCommand: 'x' }), null);
  assert.equal(parseProviderSetupResult(null), null);
});

test('toProviderReadiness maps missing, ready, unavailable, and unverified sidecar probe results', () => {
  const missing = toProviderReadiness('claude', {
    available: false,
    binaryStatus: 'missing',
    authenticationStatus: 'unavailable',
    authCommand: 'claude auth login',
  });
  assert.equal(missing.available, false);
  assert.equal(missing.binaryStatus, 'missing');
  assert.match(missing.detail, /Claude Code CLI/);

  const ready = toProviderReadiness('claude', {
    available: true,
    binaryStatus: 'ready',
    authenticationStatus: 'ready',
    authCommand: 'claude auth login',
  });
  assert.equal(ready.available, true);
  assert.equal(ready.authenticationStatus, 'ready');
  assert.equal(ready.detail, 'Provider CLI and authentication are ready.');

  const signedOut = toProviderReadiness('claude', {
    available: true,
    binaryStatus: 'ready',
    authenticationStatus: 'unavailable',
    authCommand: 'claude auth login',
  });
  assert.equal(signedOut.authenticationStatus, 'unavailable');
  assert.match(signedOut.detail, /claude auth login/);

  const copilotReady = toProviderReadiness('copilot', {
    available: true,
    binaryStatus: 'ready',
    authenticationStatus: 'unverified',
    authCommand: 'copilot login',
  });
  assert.equal(copilotReady.available, true);
  assert.equal(copilotReady.authenticationStatus, 'unverified');
});

test('buildGhAuthLoginCommand scopes enterprise hosts and defaults github.com', () => {
  assert.equal(buildGhAuthLoginCommand('https://github.com'), 'gh auth login');
  assert.equal(buildGhAuthLoginCommand('https://github.example.test'), 'gh auth login --hostname github.example.test');
});
