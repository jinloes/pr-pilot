import assert from 'node:assert/strict'
import test from 'node:test'
import { BRIDGE_PROTOCOL_VERSION, parseIncomingMessage } from './validation'

const version = { protocolVersion: BRIDGE_PROTOCOL_VERSION }

const review = {
  summary: 'Summary',
  verdict: 'COMMENT',
  lineComments: [{
    file: 'src/a.ts',
    line: 1,
    type: 'note',
    body: 'Body',
    severity: 'minor',
    category: 'compatibility',
    confidence: 'high',
    rationale: 'Evidence',
  }],
}

void test('accepts complete rich review result messages', () => {
  const parsed = parseIncomingMessage({
    ...version,
    type: 'reviewResult',
    prKey: 'acme/platform#42',
    result: review,
    diff: 'diff',
    validationDiff: 'diff',
  })

  assert.notEqual(parsed, null)
})

void test('keeps a well-formed review scope on a result', () => {
  const sha = '0123456789abcdef0123456789abcdef01234567'
  const incremental = parseIncomingMessage({
    ...version,
    type: 'reviewResult',
    prKey: 'acme/platform#42',
    result: review,
    diff: 'diff',
    reviewScope: { kind: 'incremental', baselineSha: sha },
  })
  const full = parseIncomingMessage({
    ...version,
    type: 'reviewResult',
    prKey: 'acme/platform#42',
    result: review,
    diff: 'diff',
    reviewScope: { kind: 'full', fallbackReason: 'baseline_not_in_history' },
  })

  assert.deepEqual(incremental?.type === 'reviewResult' && incremental.reviewScope, { kind: 'incremental', baselineSha: sha })
  assert.deepEqual(full?.type === 'reviewResult' && full.reviewScope, { kind: 'full', fallbackReason: 'baseline_not_in_history' })
})

void test('drops a malformed review scope but keeps the result', () => {
  for (const reviewScope of [
    { kind: 'incremental', baselineSha: 'HEAD' },
    { kind: 'incremental', baselineSha: 'A'.repeat(40) },
    { kind: 'full', fallbackReason: 'surprise' },
    { kind: 'full', fallbackReason: 'up_to_date', extra: true },
    { kind: 'partial' },
    'incremental',
    null,
  ]) {
    const parsed = parseIncomingMessage({ ...version, type: 'reviewResult', prKey: 'acme/platform#42', result: review, diff: 'diff', reviewScope })

    assert.equal(parsed?.type, 'reviewResult', JSON.stringify(reviewScope))
    assert.equal(parsed !== null && 'reviewScope' in parsed, false, JSON.stringify(reviewScope))
  }
})

void test('rejects unversioned and unknown messages', () => {
  assert.equal(parseIncomingMessage({ type: 'prLoading' }), null)
  assert.equal(parseIncomingMessage({ ...version, type: 'surprise' }), null)
})

void test('rejects malformed nested review values', () => {
  assert.equal(parseIncomingMessage({
    ...version,
    type: 'reviewResult',
    result: { ...review, verdict: 'INVALID' },
    diff: 'diff',
  }), null)
  assert.equal(parseIncomingMessage({
    ...version,
    type: 'reviewResult',
    result: { ...review, lineComments: [{ ...review.lineComments[0], line: 0 }] },
    diff: 'diff',
  }), null)
  assert.equal(parseIncomingMessage({
    ...version,
    type: 'reviewResult',
    result: { ...review, lineComments: [{ ...review.lineComments[0], category: 'boundary' }] },
    diff: 'diff',
  }), null)
})

void test('rejects malformed PR correlation keys', () => {
  assert.equal(parseIncomingMessage({ ...version, type: 'draftLoading', prKey: '42' }), null)
  assert.equal(parseIncomingMessage({ ...version, type: 'draftLoading' }), null)
})

void test('validates late diff updates and correlated save acknowledgements', () => {
  assert.notEqual(parseIncomingMessage({
    ...version,
    type: 'validationDiffUpdated',
    prKey: 'acme/platform#42',
    validationDiff: 'full diff',
  }), null)
  assert.notEqual(parseIncomingMessage({
    ...version,
    type: 'draftSaved',
    prKey: 'acme/platform#42',
    reviewId: '123',
    commentsDropped: false,
    saveId: 7,
  }), null)
  assert.equal(parseIncomingMessage({
    ...version,
    type: 'draftSaveError',
    prKey: 'acme/platform#42',
    message: 'failed',
  }), null)
})

void test('validates PR list metadata', () => {
  const pr = {
    number: 42,
    title: 'Improve validation',
    owner: 'acme',
    repo: 'platform',
    author: 'octocat',
    createdAt: '2026-07-13T00:00:00Z',
    htmlUrl: 'https://github.com/acme/platform/pull/42',
    isDraft: false,
    hasReviewDraft: true,
    reviewStatus: 'UPDATED_SINCE_REVIEW',
  }
  assert.notEqual(parseIncomingMessage({
    ...version,
    type: 'prListLoaded',
    prs: [pr],
    defaultRepo: 'acme/platform',
    listStatus: {
      searchScope: 'currentRepo',
      currentRepo: 'acme/platform',
      resultLimit: 50,
      limited: false,
      reviewStatusAvailable: true,
    },
  }), null)
  assert.equal(parseIncomingMessage({
    ...version,
    type: 'prListLoaded',
    prs: [pr],
    listStatus: {
      searchScope: 'invalid',
      resultLimit: 50,
      limited: false,
      reviewStatusAvailable: true,
    },
  }), null)
  assert.equal(parseIncomingMessage({
    ...version,
    type: 'prListLoaded',
    prs: [{ ...pr, reviewStatus: 'UNKNOWN' }],
  }), null)
  assert.equal(parseIncomingMessage({
    ...version,
    type: 'prListLoaded',
    prs: [pr],
    listStatus: {
      searchScope: 'currentRepo',
      resultLimit: 50,
      limited: false,
    },
  }), null)
})

void test('accepts an optional boolean IntelliJ-assisted flag on PR list messages', () => {
  const base = { ...version, type: 'prListLoaded', prs: [] }
  assert.notEqual(parseIncomingMessage(base), null)
  assert.notEqual(parseIncomingMessage({ ...base, intellijAssistedEnabled: true }), null)
  assert.notEqual(parseIncomingMessage({ ...base, intellijAssistedEnabled: false }), null)
  assert.equal(parseIncomingMessage({ ...base, intellijAssistedEnabled: 'true' }), null)
  assert.equal(parseIncomingMessage({ ...base, intellijAssistedEnabled: 1 }), null)
})

void test('validates provider setup readiness states', () => {
  assert.notEqual(parseIncomingMessage({
    ...version,
    type: 'setupRequired',
    reason: 'provider_not_authenticated',
    detail: 'Sign in.',
    providerReadiness: {
      provider: 'claude',
      available: true,
      detail: 'Sign in.',
      binaryStatus: 'ready',
      authenticationStatus: 'unavailable',
      authCommand: 'claude auth login',
    },
  }), null)
  assert.equal(parseIncomingMessage({
    ...version,
    type: 'setupRequired',
    reason: 'provider_not_installed',
    detail: 'Missing.',
    providerReadiness: {
      provider: 'claude',
      available: false,
      detail: 'Missing.',
      binaryStatus: 'ready',
      authenticationStatus: 'unknown',
    },
  }), null)
})

void test('accepts the IntelliJ draft-index recovery reason', () => {
  assert.notEqual(parseIncomingMessage({
    ...version,
    type: 'setupRequired',
    reason: 'draft_index_unavailable',
    detail: 'Repair the preserved index, then refresh.',
  }), null)
})

void test('validates all optional draft metadata', () => {
  assert.notEqual(parseIncomingMessage({
    ...version,
    type: 'draftLoaded',
    prKey: 'acme/platform#42',
    prState: 'DRAFT_PRESENT',
    reviewId: '123',
    result: review,
    diff: 'diff',
    validationDiff: 'diff',
    staleCommits: false,
    importedFromGitHub: true,
    status: 'Loaded',
    providerReadiness: { provider: 'claude', available: true, detail: 'Ready' },
  }), null)
  assert.equal(parseIncomingMessage({
    ...version,
    type: 'draftLoaded',
    prState: 'NO_DRAFT',
    staleCommits: 'false',
  }), null)
  assert.equal(parseIncomingMessage({
    ...version,
    type: 'draftLoaded',
    prState: 'NO_DRAFT',
    providerReadiness: { provider: 'unknown', available: true, detail: 'Ready' },
  }), null)
})

void test('accepts IntelliJ no-draft payloads with absent optional fields', () => {
  assert.notEqual(parseIncomingMessage({
    ...version,
    type: 'draftLoaded',
    prKey: 'acme/platform#42',
    prState: 'NO_DRAFT',
    validationDiff: 'diff',
    staleCommits: false,
    importedFromGitHub: false,
    status: '',
    providerReadiness: { provider: 'claude', available: true, detail: 'Ready' },
  }), null)
  assert.equal(parseIncomingMessage({
    ...version,
    type: 'draftLoaded',
    prKey: 'acme/platform#42',
    prState: 'NO_DRAFT',
    reviewId: null,
  }), null)
})

void test('rejects oversized message fields and comment collections', () => {
  assert.equal(parseIncomingMessage({
    ...version,
    type: 'reviewResult',
    result: { ...review, lineComments: Array.from({ length: 1_001 }, () => review.lineComments[0]) },
    diff: 'diff',
  }), null)
  assert.equal(parseIncomingMessage({
    ...version,
    type: 'chatChunk',
    chunk: 'x'.repeat(100_001),
  }), null)
})

void test('validates host theme messages', () => {
  assert.equal(parseIncomingMessage({ ...version, type: 'themeChanged', theme: 'highContrastDark' })?.type, 'themeChanged')
  assert.equal(parseIncomingMessage({ ...version, type: 'themeChanged', theme: 'sepia' }), null)
})

void test('bounds remembered repository instructions on draft loads', () => {
  const base = { ...version, type: 'draftLoaded', prKey: 'acme/widget#1', prState: 'NO_DRAFT' }
  assert.notEqual(parseIncomingMessage(base), null)
  assert.notEqual(parseIncomingMessage({ ...base, repositoryInstructions: 'Keep API PRs API-only.' }), null)
  assert.notEqual(parseIncomingMessage({ ...base, repositoryInstructions: 'x'.repeat(10_000) }), null)
  assert.equal(parseIncomingMessage({ ...base, repositoryInstructions: 'x'.repeat(10_001) }), null)
  assert.equal(parseIncomingMessage({ ...base, repositoryInstructions: 7 }), null)
})

void test('validates PR-scoped repository instruction save replies', () => {
  const saved = { ...version, type: 'repositoryInstructionsSaved', prKey: 'acme/widget#1', instructions: 'Rule' }
  assert.equal(parseIncomingMessage(saved)?.type, 'repositoryInstructionsSaved')
  assert.notEqual(parseIncomingMessage({ ...saved, instructions: '' }), null)
  assert.equal(parseIncomingMessage({ ...saved, prKey: undefined }), null)
  assert.equal(parseIncomingMessage({ ...saved, instructions: undefined }), null)
  assert.equal(parseIncomingMessage({ ...saved, instructions: 'x'.repeat(10_001) }), null)
  const failed = { ...version, type: 'repositoryInstructionsSaveError', prKey: 'acme/widget#1', message: 'No' }
  assert.equal(parseIncomingMessage(failed)?.type, 'repositoryInstructionsSaveError')
  assert.equal(parseIncomingMessage({ ...failed, message: undefined }), null)
  assert.equal(parseIncomingMessage({ ...failed, prKey: undefined }), null)
})
