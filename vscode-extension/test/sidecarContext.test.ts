import test from 'node:test';
import assert from 'node:assert/strict';
import * as path from 'path';

import { SidecarClient } from '../src/sidecar';
import { createSidecarHarness, fakeJar, initializeResult, tempRoot } from './sidecarHarness';

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
