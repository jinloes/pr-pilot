import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import * as path from 'path';
import { PassThrough } from 'node:stream';
import type { ChildProcessWithoutNullStreams } from 'node:child_process';

import {
  SidecarClient,
  encodeFrame,
  extractFrames,
  parseCommitContext,
  parseDraftReviewMutationResult,
  parseDraftReviewResult,
  parseExistingReviewsResult,
  parseGitHubAuthResult,
  parseInitializeResult,
  parsePrDetailResult,
  parsePrDiffResult,
  parsePrListResult,
  parsePrSearchResult,
  parseStarredReposResult,
  REQUIRED_CAPABILITIES,
  resolveSidecarJarPath,
  type SidecarSpawn,
} from '../src/sidecar';

const tempRoot = mkdtempSync(path.join(tmpdir(), 'pr-pilot-sidecar-test-'));
const fakeJar = path.join(tempRoot, 'pr-pilot-sidecar.jar');
writeFileSync(fakeJar, 'test');
after(() => rmSync(tempRoot, { recursive: true, force: true }));

const initializeResult = {
  serviceName: 'pr-pilot-sidecar',
  serviceVersion: '0.1.0',
  protocolVersion: 1,
  // Derived rather than listed so adding a capability cannot silently leave this fixture behind.
  // When it was a hand-written list, it fell four capabilities short of what the client requires
  // and every test below transparently exercised a *failed* handshake instead of a healthy one.
  capabilities: Object.fromEntries(REQUIRED_CAPABILITIES.map((name) => [name, true])) as Record<string, boolean>,
};

const NO_RESPONSE = Symbol('NO_RESPONSE');

function createSidecarHarness(
  responder: (method: string, params: unknown) => unknown | typeof NO_RESPONSE,
): {
  spawnSidecar: SidecarSpawn;
  children: ChildProcessWithoutNullStreams[];
  commands: Array<{ command: string; args: string[] }>;
  methods: string[];
  requests: Array<{ id: number; method: string; params: unknown }>;
  killCount: { value: number };
} {
  const children: ChildProcessWithoutNullStreams[] = [];
  const commands: Array<{ command: string; args: string[] }> = [];
  const methods: string[] = [];
  const requests: Array<{ id: number; method: string; params: unknown }> = [];
  const killCount = { value: 0 };
  const spawnSidecar: SidecarSpawn = (command, args) => {
    commands.push({ command, args });
    const stdin = new PassThrough();
    const stdout = new PassThrough();
    const stderr = new PassThrough();
    const child = Object.assign(new EventEmitter(), {
      stdin,
      stdout,
      stderr,
      kill: () => {
        killCount.value++;
        return true;
      },
    }) as unknown as ChildProcessWithoutNullStreams;
    children.push(child);
    let requestBuffer: Buffer<ArrayBufferLike> = Buffer.alloc(0);
    stdin.on('data', (chunk: Buffer) => {
      requestBuffer = extractFrames(Buffer.concat([requestBuffer, chunk]), (body) => {
        const request = JSON.parse(body) as { id: number; method: string; params: unknown };
        methods.push(request.method);
        requests.push({ id: request.id, method: request.method, params: request.params });
        const result = responder(request.method, request.params);
        if (result === NO_RESPONSE) return;
        queueMicrotask(() => stdout.write(encodeFrame(JSON.stringify({
          jsonrpc: '2.0',
          id: request.id,
          result,
        }))));
      });
    });
    return child;
  };
  return { spawnSidecar, children, commands, methods, requests, killCount };
}

test('supplemental GitHub parsers accept valid token-free results', () => {
  const pr = { number: 1, title: 'Fix', owner: 'acme', repo: 'widgets', author: 'octo', createdAt: '', htmlUrl: 'https://example/pr/1', isDraft: false, reviewStatus: 'UNAVAILABLE' };
  assert.deepEqual(parsePrSearchResult({ status: 'ok', message: 'ok', resultLimit: 50, limited: false, prs: [pr] })?.prs, [pr]);
  assert.deepEqual(parseStarredReposResult({ status: 'ok', message: 'ok', resultLimit: 200, limited: false, repositories: ['acme/widgets'] })?.repositories, ['acme/widgets']);
  assert.equal(parseExistingReviewsResult({ status: 'ok', message: 'ok', summary: 'Review by @octo' })?.summary, 'Review by @octo');
});

test('supplemental GitHub parsers reject malformed results', () => {
  assert.equal(parsePrSearchResult({ status: 'ok', message: 'ok', resultLimit: 50, limited: false, prs: [{}] }), null);
  assert.equal(parseStarredReposResult({ status: 'ok', message: 'ok', resultLimit: 200, limited: false, repositories: [42] }), null);
  assert.equal(parseExistingReviewsResult({ status: 'unknown', message: 'bad', summary: '' }), null);
});

const extensionRoot = path.resolve('/workspace/pr-pilot/vscode-extension');

test('encodeFrame writes a bounded Content-Length header followed by the UTF-8 body', () => {
  const frame = encodeFrame('{"message":"héllo"}');
  const text = frame.toString('utf8');
  const body = Buffer.from('{"message":"héllo"}', 'utf8');
  assert.equal(text, `Content-Length: ${body.length}\r\n\r\n{"message":"héllo"}`);
});

test('encodeFrame rejects payloads larger than the protocol limit', () => {
  assert.throws(
    () => encodeFrame('x'.repeat(8 * 1024 * 1024 + 1)),
    /exceeds the maximum size/,
  );
});

test('extractFrames decodes a single complete frame and leaves no remainder', () => {
  const frame = encodeFrame('{"a":1}');
  const seen: string[] = [];
  const remaining = extractFrames(frame, (body) => seen.push(body));
  assert.deepEqual(seen, ['{"a":1}']);
  assert.equal(remaining.length, 0);
});

test('extractFrames decodes multiple concatenated frames in order', () => {
  const combined = Buffer.concat([encodeFrame('{"a":1}'), encodeFrame('{"b":2}')]);
  const seen: string[] = [];
  const remaining = extractFrames(combined, (body) => seen.push(body));
  assert.deepEqual(seen, ['{"a":1}', '{"b":2}']);
  assert.equal(remaining.length, 0);
});

test('extractFrames holds back a partial trailing frame for the next chunk', () => {
  const complete = encodeFrame('{"a":1}');
  const partialNext = encodeFrame('{"b":2}');
  const combined = Buffer.concat([complete, partialNext.subarray(0, partialNext.length - 3)]);
  const seen: string[] = [];
  const remaining = extractFrames(combined, (body) => seen.push(body));
  assert.deepEqual(seen, ['{"a":1}']);
  assert.equal(remaining.length, partialNext.length - 3);
});

test('extractFrames rejects an unparseable header', () => {
  const garbage = Buffer.from('Content-Type: application/json\r\n\r\n{}', 'ascii');
  assert.throws(() => extractFrames(garbage, () => undefined), /invalid Content-Length/);
});

test('extractFrames rejects frames larger than the protocol limit', () => {
  const oversizedHeader = Buffer.from(`Content-Length: ${8 * 1024 * 1024 + 1}\r\n\r\n`, 'ascii');
  assert.throws(() => extractFrames(oversizedHeader, () => undefined), /exceeds the maximum size/);
});

test('parseGitHubAuthResult accepts the token-free authenticated result shape', () => {
  assert.deepEqual(
    parseGitHubAuthResult({
      status: 'authenticated',
      username: 'octocat',
      message: 'GitHub authentication is available.',
    }),
    {
      status: 'authenticated',
      username: 'octocat',
      message: 'GitHub authentication is available.',
    },
  );
});

test('parseGitHubAuthResult rejects malformed or unknown authentication results', () => {
  assert.equal(parseGitHubAuthResult({ status: 'unknown', username: null, message: 'x' }), null);
  assert.equal(parseGitHubAuthResult({ status: 'authenticated', username: 1, message: 'x' }), null);
  assert.equal(parseGitHubAuthResult({ status: 'authenticated', username: null }), null);
});

test('parseInitializeResult accepts only the expected service shape', () => {
  assert.deepEqual(parseInitializeResult(initializeResult), initializeResult);
  assert.equal(parseInitializeResult({ ...initializeResult, serviceName: 'other' }), null);
  assert.equal(parseInitializeResult({ ...initializeResult, protocolVersion: '1' }), null);
  assert.equal(parseInitializeResult({ ...initializeResult, capabilities: { githubAuth: 'yes' } }), null);
});

test('parsePrListResult accepts token-free pull request list results', () => {
  assert.deepEqual(
    parsePrListResult({
      status: 'ok',
      message: 'Pull requests loaded.',
      query: 'is:pr is:open author:@me',
      resultLimit: 50,
      limited: false,
      reviewStatusAvailable: true,
      prs: [{
        number: 42,
        title: 'Example',
        owner: 'acme',
        repo: 'widgets',
        author: 'octocat',
        createdAt: '2026-01-01T00:00:00Z',
        htmlUrl: 'https://github.com/acme/widgets/pull/42',
        isDraft: false,
        reviewStatus: 'REVIEWED',
      }],
    }),
    {
      status: 'ok',
      message: 'Pull requests loaded.',
      query: 'is:pr is:open author:@me',
      resultLimit: 50,
      limited: false,
      reviewStatusAvailable: true,
      prs: [{
        number: 42,
        title: 'Example',
        owner: 'acme',
        repo: 'widgets',
        author: 'octocat',
        createdAt: '2026-01-01T00:00:00Z',
        htmlUrl: 'https://github.com/acme/widgets/pull/42',
        isDraft: false,
        reviewStatus: 'REVIEWED',
      }],
    },
  );
});

test('parsePrListResult rejects malformed fields and unknown statuses', () => {
  const base = { status: 'ok', message: 'x', query: null, resultLimit: 50, limited: false, reviewStatusAvailable: true };
  assert.equal(parsePrListResult({ ...base, status: 'unknown', prs: [] }), null);
  assert.equal(parsePrListResult({ ...base, prs: [{ number: '42' }] }), null);
  assert.equal(parsePrListResult({ ...base }), null);
  assert.equal(parsePrListResult({ ...base, reviewStatusAvailable: undefined, prs: [] }), null);
  assert.equal(parsePrListResult({
    ...base,
    prs: [{
      number: 42, title: 'x', owner: 'a', repo: 'b', author: 'c', createdAt: '',
      htmlUrl: 'https://example.test', isDraft: false, reviewStatus: 'UNKNOWN',
    }],
  }), null);
});

test('parsePrDetailResult accepts nullable repository metadata', () => {
  assert.deepEqual(
    parsePrDetailResult({
      status: 'ok',
      message: 'Pull request details loaded.',
      detail: {
        merged: false,
        title: 'Example',
        body: '',
        head: { sha: 'abc', ref: 'feature', repoFullName: null, cloneUrl: null },
        baseRepoFullName: null,
      },
    }),
    {
      status: 'ok',
      message: 'Pull request details loaded.',
      detail: {
        merged: false,
        title: 'Example',
        body: '',
        head: { sha: 'abc', ref: 'feature', repoFullName: null, cloneUrl: null },
        baseRepoFullName: null,
      },
    },
  );
});

test('parsePrDetailResult rejects malformed successful and unknown results', () => {
  assert.equal(parsePrDetailResult({ status: 'unknown', message: 'x', detail: null }), null);
  assert.equal(parsePrDetailResult({ status: 'ok', message: 'x', detail: null }), null);
  assert.equal(parsePrDetailResult({ status: 'ok', message: 'x', detail: { merged: false } }), null);
});

test('parsePrDiffResult accepts complete success and ambiguous 404 results', () => {
  assert.deepEqual(parsePrDiffResult({ status: 'ok', message: 'Pull request diff loaded.', diff: 'diff', truncated: false, limitBytes: 250000 }),
    { status: 'ok', message: 'Pull request diff loaded.', diff: 'diff', truncated: false, limitBytes: 250000 });
  assert.deepEqual(parsePrDiffResult({ status: 'not_found_or_inaccessible', message: 'Not found or inaccessible.', diff: null, truncated: false, limitBytes: 250000 }),
    { status: 'not_found_or_inaccessible', message: 'Not found or inaccessible.', diff: null, truncated: false, limitBytes: 250000 });
  assert.equal(parsePrDiffResult({ status: 'ok', message: 'x', diff: null, truncated: false, limitBytes: 250000 }), null);
  assert.equal(parsePrDiffResult({ status: 'unknown', message: 'x', diff: null, truncated: false, limitBytes: 250000 }), null);
});

test('parsePrDiffResult accepts a diff_too_large result and still rejects unknown statuses', () => {
  assert.deepEqual(parsePrDiffResult({ status: 'diff_too_large', message: 'Too large.', diff: null, truncated: false, limitBytes: 250000 }),
    { status: 'diff_too_large', message: 'Too large.', diff: null, truncated: false, limitBytes: 250000 });
  assert.equal(parsePrDiffResult({ status: 'diff_too_large', message: 'Too large.', diff: 'diff', truncated: false, limitBytes: 250000 }), null);
  assert.equal(parsePrDiffResult({ status: 'too_large', message: 'x', diff: null, truncated: false, limitBytes: 250000 }), null);
});

test('parseDraftReviewResult accepts a decoded pending review', () => {
  assert.deepEqual(
    parseDraftReviewResult({
      status: 'ok',
      message: 'Pending review draft loaded.',
      id: '7',
      commitId: 'sha',
      review: {
        summary: 'Looks good',
        verdict: 'APPROVE',
        lineComments: [{
          file: 'a.ts',
          line: 10,
          type: 'note',
          body: 'nit',
          severity: null,
          category: null,
          confidence: null,
          rationale: null,
        }],
        importedFromGitHub: false,
      },
    }),
    {
      status: 'ok',
      message: 'Pending review draft loaded.',
      id: '7',
      commitId: 'sha',
      review: {
        summary: 'Looks good',
        verdict: 'APPROVE',
        lineComments: [{
          file: 'a.ts',
          line: 10,
          type: 'note',
          body: 'nit',
          severity: null,
          category: null,
          confidence: null,
          rationale: null,
        }],
        importedFromGitHub: false,
      },
    },
  );
});

test('parseDraftReviewResult accepts a token-free none result without a review', () => {
  assert.deepEqual(
    parseDraftReviewResult({ status: 'none', message: 'No pending review draft.', id: null, commitId: null, review: null }),
    { status: 'none', message: 'No pending review draft.', id: null, commitId: null, review: null },
  );
});

test('parseDraftReviewResult rejects malformed successful and unknown results', () => {
  assert.equal(parseDraftReviewResult({ status: 'unknown', message: 'x', id: null, commitId: null, review: null }), null);
  assert.equal(parseDraftReviewResult({ status: 'ok', message: 'x', id: null, commitId: null, review: null }), null);
  assert.equal(parseDraftReviewResult({ status: 'ok', message: 'x', id: '1', commitId: null, review: { summary: 's' } }), null);
});

test('parseDraftReviewMutationResult accepts a successful save result', () => {
  assert.deepEqual(
    parseDraftReviewMutationResult({ status: 'ok', message: 'Draft review saved.', reviewId: '42', commentsDropped: true, recoveryRequired: false }),
    { status: 'ok', message: 'Draft review saved.', reviewId: '42', commentsDropped: true, recoveryRequired: false },
  );
});

test('parseDraftReviewMutationResult accepts a successful result with a null reviewId', () => {
  assert.deepEqual(
    parseDraftReviewMutationResult({ status: 'ok', message: 'Review submitted.', reviewId: null, commentsDropped: false, recoveryRequired: false }),
    { status: 'ok', message: 'Review submitted.', reviewId: null, commentsDropped: false, recoveryRequired: false },
  );
});

test('parseDraftReviewMutationResult accepts a token-free domain failure', () => {
  assert.deepEqual(
    parseDraftReviewMutationResult({ status: 'not_authenticated', message: 'x', reviewId: null, commentsDropped: false, recoveryRequired: false }),
    { status: 'not_authenticated', message: 'x', reviewId: null, commentsDropped: false, recoveryRequired: false },
  );
});

test('parseDraftReviewMutationResult rejects malformed or unknown-status results', () => {
  assert.equal(parseDraftReviewMutationResult({ status: 'unknown', message: 'x', reviewId: null, commentsDropped: false, recoveryRequired: false }), null);
  assert.equal(parseDraftReviewMutationResult({ status: 'ok', message: 'x', reviewId: 42, commentsDropped: false, recoveryRequired: false }), null);
  assert.equal(parseDraftReviewMutationResult({ status: 'ok', message: 'x', reviewId: null, commentsDropped: 'no', recoveryRequired: false }), null);
  assert.equal(parseDraftReviewMutationResult({ status: 'ok', message: 'x', reviewId: null, commentsDropped: false }), null);
  assert.equal(parseDraftReviewMutationResult(null), null);
});

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

// ── Phase 1 prompt context (best-effort reads) ────────────────────────────────

test('getCheckStatus returns the rendered summary and structured annotations', async () => {
  const harness = createSidecarHarness((method) => method === 'initialize'
    ? initializeResult
    : {
      status: 'ok',
      state: 'complete',
      summary: 'CI: 1 failing',
      annotations: [
        { path: 'src/A.java', startLine: 12, endLine: 12, level: 'failure', message: 'boom' },
        { path: 'src/B.java', startLine: 3, endLine: 3, level: 'warning', message: 'lint' },
      ],
    });
  const client = new SidecarClient(fakeJar, 'java', harness.spawnSidecar);

  const result = await client.getCheckStatus('https://github.com', 'o', 'r', 'abc123');

  assert.equal(result.summary, 'CI: 1 failing');
  assert.equal(result.annotations.length, 2);
  assert.deepEqual(result.annotations[0], {
    path: 'src/A.java', startLine: 12, endLine: 12, level: 'failure', message: 'boom',
  });
  assert.ok(harness.methods.includes('prs/getCheckStatus'));
  client.dispose();
});

test('getCommits retains validated closing issue numbers with the rendered summary', async () => {
  const harness = createSidecarHarness((method) => method === 'initialize'
    ? initializeResult
    : { summary: '- Fix login', closingIssueNumbers: [7, 8] });
  const client = new SidecarClient(fakeJar, 'java', harness.spawnSidecar);

  assert.deepEqual(
    await client.getCommits('https://github.com', 'acme', 'widgets', 42),
    { summary: '- Fix login', closingIssueNumbers: [7, 8] },
  );
  assert.equal(harness.methods.filter((method) => method === 'prs/getCommits').length, 1);
  client.dispose();
});

test('parseCommitContext rejects malformed or unbounded issue-number arrays', () => {
  assert.equal(parseCommitContext({ summary: 'x', closingIssueNumbers: '7' }), null);
  assert.equal(parseCommitContext({ summary: 'x', closingIssueNumbers: [7, '8'] }), null);
  assert.equal(parseCommitContext({ summary: 'x', closingIssueNumbers: [0] }), null);
  assert.equal(parseCommitContext({ summary: 'x', closingIssueNumbers: [7, 7] }), null);
  assert.equal(parseCommitContext({ summary: 'x', closingIssueNumbers: [1, 2, 3, 4] }), null);
});

// Prompt context is purely additive: a review without it is exactly as good as before it existed,
// so every one of these must degrade to empty rather than reject. A missing jar is used because it
// fails fast and is a real deployment failure — throwing inside the responder would instead leave
// the request unanswered and test the request timeout, which is a different (and very slow) thing.
test('context reads degrade to empty instead of rejecting when the sidecar is unavailable', async () => {
  const harness = createSidecarHarness(() => initializeResult);
  const client = new SidecarClient(path.join(tempRoot, 'missing.jar'), 'java', harness.spawnSidecar);

  assert.deepEqual(await client.getCheckStatus('https://github.com', 'o', 'r', 'sha'), {
    summary: '', annotations: [],
  });
  assert.deepEqual(await client.getCommits('https://github.com', 'o', 'r', 1), {
    summary: '', closingIssueNumbers: [],
  });
  assert.equal(await client.getLinkedIssues('https://github.com', 'o', 'r', 'Closes #1', []), '');
  assert.equal(await client.getRepoProfile('/tmp/x'), '');
  assert.equal(harness.commands.length, 0);
  client.dispose();
});

test('context reads tolerate a malformed payload without throwing', async () => {
  const harness = createSidecarHarness((method) => method === 'initialize'
    ? initializeResult
    : { summary: 42, annotations: [{ path: 'ok.java', message: 'm' }, { nope: true }, 'junk'] });
  const client = new SidecarClient(fakeJar, 'java', harness.spawnSidecar);

  const status = await client.getCheckStatus('https://github.com', 'o', 'r', 'sha');
  assert.equal(status.summary, '');
  // Only the well-formed annotation survives; defaults fill the absent numeric/level fields.
  assert.deepEqual(status.annotations, [
    { path: 'ok.java', startLine: 0, endLine: 0, level: 'warning', message: 'm' },
  ]);
  assert.deepEqual(await client.getCommits('https://github.com', 'o', 'r', 1), {
    summary: '', closingIssueNumbers: [],
  });
  client.dispose();
});

test('getLinkedIssues sends the PR body required by the engine contract', async () => {
  const harness = createSidecarHarness((method) => method === 'initialize'
    ? initializeResult
    : { summary: 'Issue #7' });
  const client = new SidecarClient(fakeJar, 'java', harness.spawnSidecar);

  assert.equal(
    await client.getLinkedIssues(
      'https://github.com',
      'acme',
      'widgets',
      'Fixes #7',
      [8, 9],
    ),
    'Issue #7',
  );
  const request = harness.requests.find(({ method }) => method === 'prs/getLinkedIssues');
  assert.deepEqual(request?.params, {
    githubBaseUrl: 'https://github.com',
    owner: 'acme',
    repo: 'widgets',
    prBody: 'Fixes #7',
    commitIssueNumbers: [8, 9],
  });
  client.dispose();
});

// ── Repo guidance + worktree lifecycle (engine-owned, retired from TypeScript) ─
//
// These replace the deleted guidelines.ts/worktree.ts implementations. The logic itself is now
// tested in review-engine; what remains testable here is the part this host still owns: sending the
// right wire method and degrading correctly, since every one of these calls has a fallback that
// must never surface as a thrown error on the review path.

test('readRepoGuidelines returns the engine-rendered guidance text', async () => {
  const harness = createSidecarHarness((method) => method === 'initialize'
    ? initializeResult
    : { guidelines: '## AGENTS.md\nPrefer Apache Commons helpers.' });
  const client = new SidecarClient(fakeJar, 'java', harness.spawnSidecar);

  const guidelines = await client.readRepoGuidelines('/repo', ['docs/*.md']);

  assert.equal(guidelines, '## AGENTS.md\nPrefer Apache Commons helpers.');
  assert.ok(harness.methods.includes('reviews/readGuidelines'));
  client.dispose();
});

test('findGitRoot returns the resolved repository root', async () => {
  const harness = createSidecarHarness((method) => method === 'initialize'
    ? initializeResult
    : { gitRoot: '/home/octo/widgets' });
  const client = new SidecarClient(fakeJar, 'java', harness.spawnSidecar);

  assert.equal(await client.findGitRoot('/home/octo/widgets/src'), '/home/octo/widgets');
  assert.ok(harness.methods.includes('reviews/findGitRoot'));
  client.dispose();
});

test('createWorktree passes the fork clone URL through and returns the created directory', async () => {
  const seen: Array<Record<string, unknown>> = [];
  const harness = createSidecarHarness((method, params) => {
    if (method === 'initialize') return initializeResult;
    seen.push(params as Record<string, unknown>);
    return { status: 'created', worktreeDir: '/tmp/pr-pilot-wt-7-abc', message: '' };
  });
  const client = new SidecarClient(fakeJar, 'java', harness.spawnSidecar);

  const result = await client.createWorktree('/repo', 7, 'feature', 'a1b2c3d', 'https://github.com/fork/widgets.git');

  assert.deepEqual(result, { status: 'created', worktreeDir: '/tmp/pr-pilot-wt-7-abc', message: '' });
  assert.deepEqual(seen[0], {
    gitRoot: '/repo',
    prNumber: 7,
    branch: 'feature',
    headSha: 'a1b2c3d',
    forkCloneUrl: 'https://github.com/fork/widgets.git',
  });
  client.dispose();
});

test('createWorktree surfaces skipped and failed statuses as domain results, not throws', async () => {
  for (const engineResult of [
    { status: 'skipped', worktreeDir: '', message: 'No branch to check out.' },
    { status: 'failed', worktreeDir: '', message: "couldn't find remote ref" },
  ]) {
    const harness = createSidecarHarness((method) => method === 'initialize' ? initializeResult : engineResult);
    const client = new SidecarClient(fakeJar, 'java', harness.spawnSidecar);
    assert.deepEqual(await client.createWorktree('/repo', 7, 'feature', '', ''), engineResult);
    client.dispose();
  }
});

test('createWorktree treats an unusable success as failure so the caller falls back', async () => {
  // A 'created' status with no directory, or a status this client does not recognise, must not be
  // handed back as a working directory — resolveWorkingDir would pass '' to the provider CLI as its
  // cwd. Falling back to the open workspace folder is the safe degradation.
  for (const engineResult of [
    { status: 'created', worktreeDir: '', message: '' },
    { status: 'something-new', worktreeDir: '/tmp/pr-pilot-wt-7-abc', message: '' },
  ]) {
    const harness = createSidecarHarness((method) => method === 'initialize' ? initializeResult : engineResult);
    const client = new SidecarClient(fakeJar, 'java', harness.spawnSidecar);
    assert.equal((await client.createWorktree('/repo', 7, 'feature', '', '')).status, 'failed');
    client.dispose();
  }
});

test('worktree lifecycle degrades instead of rejecting when the sidecar is unavailable', async () => {
  // A worktree is an optimization, not a precondition: the caller falls back to the user's own
  // checkout. A rejection here would fail the whole review instead.
  const harness = createSidecarHarness(() => initializeResult);
  const client = new SidecarClient(path.join(tempRoot, 'missing.jar'), 'java', harness.spawnSidecar);

  assert.equal(await client.findGitRoot('/repo'), '');
  assert.equal((await client.createWorktree('/repo', 7, 'feature', '', '')).status, 'failed');
  assert.equal(await client.removeWorktree('/repo', '/tmp/pr-pilot-wt-7-abc'), false);
  assert.equal(await client.readRepoGuidelines('/repo', []), '');
  assert.equal(harness.commands.length, 0);
  client.dispose();
});

test('worktree removal preserves the engine cleanup result', async () => {
  let removed = true;
  const harness = createSidecarHarness((method) => {
    if (method === 'initialize') return initializeResult;
    if (method === 'reviews/removeWorktree') return { removed };
    return { nope: true };
  });
  const client = new SidecarClient(fakeJar, 'java', harness.spawnSidecar);

  assert.equal(await client.removeWorktree('/repo', '/tmp/pr-pilot-wt-7-abc'), true);
  removed = false;
  assert.equal(await client.removeWorktree('/repo', '/tmp/pr-pilot-wt-7-abc'), false);
  assert.ok(harness.methods.includes('reviews/removeWorktree'));
  client.dispose();
});

test('worktree and guidance reads tolerate a malformed payload without throwing', async () => {
  const harness = createSidecarHarness((method) => method === 'initialize' ? initializeResult : { nope: true });
  const client = new SidecarClient(fakeJar, 'java', harness.spawnSidecar);

  assert.equal(await client.findGitRoot('/repo'), '');
  assert.equal(await client.readRepoGuidelines('/repo', []), '');
  assert.deepEqual(await client.createWorktree('/repo', 7, 'feature', '', ''), {
    status: 'failed', worktreeDir: '', message: '',
  });
  client.dispose();
});
