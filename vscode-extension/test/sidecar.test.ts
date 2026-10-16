import test from 'node:test';
import assert from 'node:assert/strict';
import * as path from 'path';
import { PassThrough } from 'node:stream';

import {
  SidecarClient,
  encodeFrame,
  resolveSidecarJarPath,
  type SidecarSpawn,
} from '../src/sidecar';
import { createSidecarHarness, fakeJar, initializeResult, NO_RESPONSE, tempRoot } from './sidecarHarness';

const extensionRoot = path.resolve('/workspace/pr-pilot/vscode-extension');

test('resolveSidecarJarPath prefers the packaged jar staged alongside the extension', () => {
  const resolved = resolveSidecarJarPath(
    extensionRoot,
    (candidate) => candidate === path.join(extensionRoot, 'sidecar', 'pr-pilot-sidecar.jar'),
    () => [],
  );
  assert.equal(resolved, path.join(extensionRoot, 'sidecar', 'pr-pilot-sidecar.jar'));
});

test('resolveSidecarJarPath falls back to the sibling Gradle module build output during development', () => {
  const devLibsDir = path.resolve(extensionRoot, '..', 'sidecar', 'build', 'libs');
  const resolved = resolveSidecarJarPath(
    extensionRoot,
    (candidate) => candidate === devLibsDir,
    (dir) => (dir === devLibsDir ? ['pr-pilot-sidecar.jar'] : []),
  );
  assert.equal(resolved, path.join(devLibsDir, 'pr-pilot-sidecar.jar'));
});

test('resolveSidecarJarPath returns null when neither packaged nor dev jar exists', () => {
  const resolved = resolveSidecarJarPath(extensionRoot, () => false, () => []);
  assert.equal(resolved, null);
});

test('SidecarClient lazily starts once, initializes, and returns a validated result', async () => {
  const harness = createSidecarHarness((method) => method === 'initialize'
    ? initializeResult
    : { status: 'authenticated', username: 'octocat', message: 'ok' });
  const client = new SidecarClient(fakeJar, '/opt/java', harness.spawnSidecar);

  assert.equal(harness.commands.length, 0);
  const result = await client.checkGitHubAuth('https://github.com');

  assert.equal(result.username, 'octocat');
  assert.deepEqual(harness.commands, [{ command: '/opt/java', args: ['-jar', fakeJar] }]);
  assert.deepEqual(harness.methods, ['initialize', 'github/checkAuth']);
  await client.checkGitHubAuth('https://github.com');
  assert.equal(harness.commands.length, 1);
  client.dispose();
});

test('SidecarClient reports a missing packaged jar before spawning', async () => {
  const harness = createSidecarHarness(() => initializeResult);
  const client = new SidecarClient(path.join(tempRoot, 'missing.jar'), 'java', harness.spawnSidecar);

  await assert.rejects(client.initialize(), /was not found.*Reinstall the extension/);
  await assert.rejects(client.initialize(), /was not found.*Reinstall the extension/);
  assert.equal(harness.commands.length, 0);
});

test('deep lifecycle client sends exact identities and rejects malformed preparation and cleanup results', async () => {
  const head = 'a'.repeat(40);
  const retainedId = '11111111-1111-4111-8111-111111111111';
  const preparation = { retainedId, head, worktree: path.join(tempRoot, 'retained'), servers: ['private'] };
  let malformed = false;
  const harness = createSidecarHarness((method) => {
    if (method === 'initialize') return initializeResult;
    if (method === 'reviews/prepareDeepReview') return malformed ? { ...preparation, head: 'b'.repeat(40) } : preparation;
    if (method === 'reviews/listDeepReviews') return [{ id: retainedId, repository: tempRoot,
      worktree: preparation.worktree, head, createdAt: 1 }];
    if (method === 'reviews/cleanupDeepReview') return { removed: malformed ? 'true' : true };
    throw new Error(`Unexpected method ${method}`);
  });
  const client = new SidecarClient(fakeJar, 'java', harness.spawnSidecar);
  const input = { operationId: 'deep-1', gitRoot: tempRoot, prNumber: 1, branch: 'main',
    headSha: head, forkCloneUrl: '', prIdentity: 'a/b#1', diffDigest: 'b'.repeat(64) };
  try {
    assert.deepEqual(await client.prepareDeepReview(input), preparation);
    assert.deepEqual(harness.requests.find(r => r.method === 'reviews/prepareDeepReview')?.params, input);
    assert.equal((await client.listDeepReviews())[0].id, retainedId);
    assert.equal(await client.cleanupDeepReview(retainedId, true), true);
    assert.deepEqual(harness.requests.find(r => r.method === 'reviews/cleanupDeepReview')?.params,
      { retainedId, projectClosed: true });
    malformed = true;
    await assert.rejects(client.prepareDeepReview({ ...input, operationId: 'deep-2' }), /Invalid deep preparation/);
    await assert.rejects(client.cleanupDeepReview(retainedId, true), /Invalid retained cleanup/);
  } finally {
    client.dispose();
  }
});

test('SidecarClient reports missing Java with installation guidance', async () => {
  const harness = createSidecarHarness(() => NO_RESPONSE);
  const spawnSidecar: SidecarSpawn = (command, args, options) => {
    const child = harness.spawnSidecar(command, args, options);
    queueMicrotask(() => child.emit('error', Object.assign(new Error('spawn java ENOENT'), { code: 'ENOENT' })));
    return child;
  };
  const client = new SidecarClient(fakeJar, 'java', spawnSidecar);

  await assert.rejects(client.initialize(), /Install Java 17 or newer/);
});

test('SidecarClient rejects incompatible protocols and missing capabilities', async () => {
  const incompatible = createSidecarHarness(() => ({ ...initializeResult, protocolVersion: 2 }));
  const incompatibleClient = new SidecarClient(fakeJar, 'java', incompatible.spawnSidecar);
  await assert.rejects(incompatibleClient.initialize(), /protocol mismatch.*expected 1, got 2/);
  await assert.rejects(incompatibleClient.initialize(), /protocol mismatch.*expected 1, got 2/);
  assert.equal(incompatible.commands.length, 1);

  const incomplete = createSidecarHarness(() => ({
    ...initializeResult,
    capabilities: { ...initializeResult.capabilities, prDiff: false },
  }));
  const incompleteClient = new SidecarClient(fakeJar, 'java', incomplete.spawnSidecar);
  await assert.rejects(incompleteClient.initialize(), /missing required capabilities: prDiff/);
});

test('SidecarClient throws when a capability returns a malformed result', async () => {
  const harness = createSidecarHarness((method) => method === 'initialize' ? initializeResult : { status: 'ok' });
  const client = new SidecarClient(fakeJar, 'java', harness.spawnSidecar);

  await assert.rejects(
    client.checkGitHubAuth('https://github.com'),
    /invalid GitHub authentication response/,
  );
  client.dispose();
});

test('SidecarClient keeps the transport available after a request timeout', async () => {
  const harness = createSidecarHarness((method) => method === 'initialize' ? initializeResult : NO_RESPONSE);
  const client = new SidecarClient(fakeJar, 'java', harness.spawnSidecar, 5);

  await assert.rejects(
    client.checkGitHubAuth('https://github.com'),
    /request "github\/checkAuth" timed out.*Try the request again/,
  );
  assert.equal(harness.killCount.value, 0);
  await assert.rejects(client.checkGitHubAuth('https://github.com'), /timed out/);
  assert.equal(harness.commands.length, 1);
  client.dispose();
});

test('SidecarClient accepts another request after a timed-out request', async () => {
  let timedOut = false;
  const harness = createSidecarHarness((method) => {
    if (method === 'initialize') return initializeResult;
    if (!timedOut) {
      timedOut = true;
      return NO_RESPONSE;
    }
    return { status: 'authenticated', username: 'octocat', message: 'ok' };
  });
  const client = new SidecarClient(fakeJar, 'java', harness.spawnSidecar, 5);

  await assert.rejects(client.checkGitHubAuth('https://github.com'), /Try the request again/);
  const result = await client.checkGitHubAuth('https://github.com');

  assert.equal(result.username, 'octocat');
  assert.equal(harness.commands.length, 1);
  assert.equal(harness.killCount.value, 0);
  client.dispose();
});

test('SidecarClient times out one request without aborting a pending review', async () => {
  const harness = createSidecarHarness((method) => method === 'initialize' ? initializeResult : NO_RESPONSE);
  const client = new SidecarClient(fakeJar, 'java', harness.spawnSidecar, 5);
  const chunks: string[] = [];
  const review = client.chatReview({
    operationId: 'review-1',
    provider: 'claude',
    effort: 'medium',
    inheritMcp: false,
    userMessage: 'Review this change',
  }, (chunk) => chunks.push(chunk));

  while (!harness.methods.includes('reviews/chat')) await new Promise((resolve) => setTimeout(resolve, 0));
  await assert.rejects(client.checkGitHubAuth('https://github.com'), /timed out/);

  const reviewRequest = harness.requests.find(({ method }) => method === 'reviews/chat');
  assert.ok(reviewRequest);
  const stdout = harness.children[0].stdout as PassThrough;
  stdout.write(encodeFrame(JSON.stringify({
    jsonrpc: '2.0',
    method: 'reviews/chatChunk',
    params: { requestId: reviewRequest.id, text: 'still running' },
  })));
  stdout.write(encodeFrame(JSON.stringify({
    jsonrpc: '2.0',
    id: reviewRequest.id,
    result: { content: 'completed' },
  })));

  assert.equal(await review, 'completed');
  assert.deepEqual(chunks, ['still running']);
  assert.equal(harness.killCount.value, 0);
  client.dispose();
});

test('SidecarClient cannot restart after disposal', async () => {
  const harness = createSidecarHarness(() => initializeResult);
  const client = new SidecarClient(fakeJar, 'java', harness.spawnSidecar);
  await client.initialize();
  client.dispose();

  await assert.rejects(client.initialize(), /has been disposed/);
  await assert.rejects(client.restart(), /has been disposed/);
  assert.equal(harness.commands.length, 1);
});

test('SidecarClient respawns on the next request after an unexpected exit', async () => {
  let answerAuth = false;
  const harness = createSidecarHarness((method) => {
    if (method === 'initialize') return initializeResult;
    return answerAuth
      ? { status: 'authenticated', username: 'octocat', message: 'ok' }
      : NO_RESPONSE;
  });
  const client = new SidecarClient(fakeJar, 'java', harness.spawnSidecar);
  await client.initialize();

  const interrupted = client.checkGitHubAuth('https://github.com');
  while (!harness.methods.includes('github/checkAuth')) {
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
  harness.children[0].emit('exit', 1, null);

  await assert.rejects(interrupted, /exited unexpectedly/);
  answerAuth = true;
  const recovered = await client.checkGitHubAuth('https://github.com');

  assert.equal(recovered.username, 'octocat');
  assert.equal(harness.commands.length, 2);
  client.dispose();
});

test('SidecarClient recovers after an invalid frame header', async () => {
  const harness = createSidecarHarness((method) => method === 'initialize'
    ? initializeResult
    : { status: 'authenticated', username: 'octocat', message: 'ok' });
  const client = new SidecarClient(fakeJar, 'java', harness.spawnSidecar);
  await client.initialize();

  (harness.children[0].stdout as PassThrough).write(
    Buffer.from('Broken-Header\r\n\r\n', 'ascii'),
  );
  await new Promise<void>((resolve) => setImmediate(resolve));

  assert.equal((await client.checkGitHubAuth('https://github.com')).username, 'octocat');
  assert.equal(harness.commands.length, 2);
  client.dispose();
});

test('SidecarClient recovers after malformed JSON from the sidecar', async () => {
  const harness = createSidecarHarness((method) => method === 'initialize'
    ? initializeResult
    : { status: 'authenticated', username: 'octocat', message: 'ok' });
  const client = new SidecarClient(fakeJar, 'java', harness.spawnSidecar);
  await client.initialize();

  (harness.children[0].stdout as PassThrough).write(encodeFrame('{not-json'));
  await new Promise<void>((resolve) => setImmediate(resolve));

  assert.equal((await client.checkGitHubAuth('https://github.com')).username, 'octocat');
  assert.equal(harness.commands.length, 2);
  client.dispose();
});

test('SidecarClient recovers after a stdin write failure', async () => {
  const harness = createSidecarHarness((method) => method === 'initialize'
    ? initializeResult
    : { status: 'authenticated', username: 'octocat', message: 'ok' });
  const client = new SidecarClient(fakeJar, 'java', harness.spawnSidecar);
  await client.initialize();

  const failingStdin = harness.children[0].stdin as unknown as {
    write: (chunk: Uint8Array, callback: (error?: Error | null) => void) => boolean;
  };
  failingStdin.write = (_chunk, callback) => {
    queueMicrotask(() => callback(new Error('broken pipe')));
    return false;
  };

  await assert.rejects(client.checkGitHubAuth('https://github.com'), /broken pipe/);
  assert.equal((await client.checkGitHubAuth('https://github.com')).username, 'octocat');
  assert.equal(harness.commands.length, 2);
  client.dispose();
});

test('SidecarClient bounds consecutive automatic recovery attempts', async () => {
  const harness = createSidecarHarness((method) => method === 'initialize'
    ? initializeResult
    : { status: 'authenticated', username: 'octocat', message: 'ok' });
  let failSpawnedChildren = false;
  const spawnSidecar: SidecarSpawn = (command, args, options) => {
    const child = harness.spawnSidecar(command, args, options);
    if (failSpawnedChildren) {
      queueMicrotask(() => child.emit('exit', 1, null));
    }
    return child;
  };
  const exhausted: Error[] = [];
  const client = new SidecarClient(
    fakeJar,
    'java',
    spawnSidecar,
    undefined,
    (failure) => exhausted.push(failure),
  );
  await client.checkGitHubAuth('https://github.com');
  failSpawnedChildren = true;
  harness.children[0].emit('exit', 1, null);

  await assert.rejects(
    client.checkGitHubAuth('https://github.com'),
    /Automatic recovery failed after 3 attempts.*Use Retry/,
  );
  await assert.rejects(
    client.checkGitHubAuth('https://github.com'),
    /Automatic recovery failed after 3 attempts.*Use Retry/,
  );

  assert.equal(harness.commands.length, 4);
  assert.equal(exhausted.length, 1);
  client.dispose();
});
