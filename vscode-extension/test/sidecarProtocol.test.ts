import test from 'node:test';
import assert from 'node:assert/strict';

import {
  parseCommitContext,
  parseDraftReviewMutationResult,
  parseDraftReviewResult,
  parseExistingReviewsResult,
  parseGitHubAuthResult,
  parseInitializeResult,
  parsePrDetailResult,
  parseIncrementalDiffResult,
  parsePrDiffResult,
  parsePrListResult,
  parsePrSearchResult,
  parseStarredReposResult,
} from '../src/sidecar';
import { parseReviewResult } from '../src/sidecarProtocol';
import { initializeResult } from './sidecarHarness';

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
        baseSha: null,
      },
    },
  );
});

test('parsePrDetailResult carries a string baseSha and rejects a non-string one', () => {
  const detail = {
    merged: false,
    title: 'Example',
    body: '',
    head: null,
    baseRepoFullName: 'acme/widgets',
  };
  const base = 'c'.repeat(40);
  assert.equal(
    parsePrDetailResult({ status: 'ok', message: 'x', detail: { ...detail, baseSha: base } })?.detail?.baseSha,
    base,
  );
  assert.equal(
    parsePrDetailResult({ status: 'ok', message: 'x', detail: { ...detail, baseSha: null } })?.detail?.baseSha,
    null,
  );
  assert.equal(parsePrDetailResult({ status: 'ok', message: 'x', detail: { ...detail, baseSha: 42 } }), null);
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

test('parseReviewResult carries reviewer sources only when they are an array of strings', () => {
  const comment = { file: 'a.ts', line: 1, type: 'issue', body: 'Body.' };
  const parse = (extra: Record<string, unknown>) =>
    parseReviewResult({ summary: 's', verdict: 'COMMENT', lineComments: [{ ...comment, ...extra }] })
      ?.lineComments[0];

  assert.deepEqual(parse({ sources: ['claude-opus', 'gpt-5.5'] })?.sources, ['claude-opus', 'gpt-5.5']);
  assert.deepEqual(parse({ sources: [] })?.sources, []);
  for (const sources of [undefined, null, 'claude-opus', ['claude-opus', 1], { 0: 'x' }]) {
    const parsed = parse({ sources });
    assert.ok(parsed, `comment with sources ${JSON.stringify(sources)} still parses`);
    assert.equal('sources' in parsed, false);
  }
});

test('parseReviewResult keeps a non-blank suggestedChange and treats blank or non-string as absent', () => {
  const comment = { file: 'a.ts', line: 1, type: 'issue', body: 'Body.' };
  const parse = (extra: Record<string, unknown>) =>
    parseReviewResult({ summary: 's', verdict: 'COMMENT', lineComments: [{ ...comment, ...extra }] })
      ?.lineComments[0];

  assert.equal(parse({ suggestedChange: '  return a;' })?.suggestedChange, '  return a;');
  for (const suggestedChange of [undefined, null, '', '  ', 3]) {
    const parsed = parse({ suggestedChange });
    assert.ok(parsed, `comment with suggestedChange ${JSON.stringify(suggestedChange)} still parses`);
    assert.equal('suggestedChange' in parsed, false);
  }
});

test('parseDraftReviewResult maps a suggestedChange and treats empty or null as absent', () => {
  const draft = (suggestedChange: unknown) => parseDraftReviewResult({
    status: 'ok',
    message: 'm',
    id: '7',
    commitId: 'sha',
    review: {
      summary: 's',
      verdict: 'COMMENT',
      importedFromGitHub: false,
      lineComments: [{
        file: 'a.ts', line: 10, type: 'issue', body: 'b',
        severity: null, category: null, confidence: 'high', rationale: null, suggestedChange,
      }],
    },
  })?.review?.lineComments[0];

  assert.equal(draft('return a;')?.suggestedChange, 'return a;');
  for (const absent of [null, '', undefined]) {
    const parsed = draft(absent);
    assert.ok(parsed);
    assert.equal('suggestedChange' in parsed, false);
  }
  assert.equal(draft(3), undefined);
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

test('parseCommitContext rejects malformed or unbounded issue-number arrays', () => {
  assert.equal(parseCommitContext({ summary: 'x', closingIssueNumbers: '7' }), null);
  assert.equal(parseCommitContext({ summary: 'x', closingIssueNumbers: [7, '8'] }), null);
  assert.equal(parseCommitContext({ summary: 'x', closingIssueNumbers: [0] }), null);
  assert.equal(parseCommitContext({ summary: 'x', closingIssueNumbers: [7, 7] }), null);
  assert.equal(parseCommitContext({ summary: 'x', closingIssueNumbers: [1, 2, 3, 4] }), null);
});

test('parseIncrementalDiffResult accepts incremental, fallback and failure results', () => {
  const baselineSha = 'a'.repeat(40);
  const headSha = 'b'.repeat(40);
  assert.deepEqual(parseIncrementalDiffResult({ status: 'ok', message: 'ok', scope: 'incremental', fallbackReason: null,
    baselineSha, headSha, diff: 'diff', truncated: false, limitBytes: 250000 }),
  { status: 'ok', message: 'ok', scope: 'incremental', baselineSha, headSha, diff: 'diff', truncated: false, limitBytes: 250000 });
  assert.deepEqual(parseIncrementalDiffResult({ status: 'ok', message: 'Falling back.', scope: 'full',
    fallbackReason: 'up_to_date', baselineSha, headSha, diff: null, truncated: false, limitBytes: 250000 }),
  { status: 'ok', message: 'Falling back.', scope: 'full', fallbackReason: 'up_to_date', limitBytes: 250000 });
  assert.deepEqual(parseIncrementalDiffResult({ status: 'not_authenticated', message: 'Log in.', scope: null,
    fallbackReason: null, baselineSha: null, headSha: null, diff: null, truncated: false, limitBytes: 250000 }),
  { status: 'not_authenticated', message: 'Log in.', limitBytes: 250000 });
});

test('parseIncrementalDiffResult rejects malformed results', () => {
  const baselineSha = 'a'.repeat(40);
  const ok = { status: 'ok', message: 'ok', scope: 'incremental', baselineSha, headSha: baselineSha,
    diff: 'diff', truncated: false, limitBytes: 250000 };
  assert.equal(parseIncrementalDiffResult({ ...ok, baselineSha: 'HEAD' }), null);
  assert.equal(parseIncrementalDiffResult({ ...ok, diff: '  ' }), null);
  assert.equal(parseIncrementalDiffResult({ ...ok, scope: 'partial' }), null);
  assert.equal(parseIncrementalDiffResult({ ...ok, scope: 'full', fallbackReason: 'surprise' }), null);
  assert.equal(parseIncrementalDiffResult({ status: 'api_failed', message: 'x', scope: 'full', limitBytes: 1 }), null);
  assert.equal(parseIncrementalDiffResult({ status: 'diff_too_large', message: 'x', limitBytes: 1 }), null);
  assert.equal(parseIncrementalDiffResult({ status: 'unknown', message: 'x', limitBytes: 1 }), null);
  assert.equal(parseIncrementalDiffResult(null), null);
});
