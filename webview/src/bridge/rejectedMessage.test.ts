import assert from 'node:assert/strict'
import test from 'node:test'
import {
  REJECTED_REVIEW_MESSAGE,
  describeRejectedHostMessage,
  fallbackForRejectedHostMessage,
} from './rejectedMessage'
import { BRIDGE_PROTOCOL_VERSION } from './validation'

const prKey = 'acme/platform#42'

function reviewResult(comment: Record<string, unknown>) {
  return {
    protocolVersion: BRIDGE_PROTOCOL_VERSION,
    type: 'reviewResult',
    prKey,
    result: {
      summary: 'Summary',
      verdict: 'COMMENT',
      lineComments: [
        { file: 'src/a.ts', line: 1, type: 'note', body: 'Fine' },
        { file: 'src/secret.ts', line: 2, type: 'note', body: 'Private body', ...comment },
      ],
    },
    diff: 'diff',
  }
}

void test('describeRejectedHostMessage names the first invalid comment and its enum fields', () => {
  const description = describeRejectedHostMessage(reviewResult({ category: 'futuristic', severity: 'minor' }))

  assert.equal(
    description,
    '"reviewResult": comment 1 (type="note", severity="minor", category="futuristic", confidence=undefined)',
  )
})

void test('describeRejectedHostMessage never echoes comment bodies or paths', () => {
  const description = describeRejectedHostMessage(reviewResult({ category: 'futuristic' }))

  assert.ok(!description.includes('Private body'))
  assert.ok(!description.includes('src/secret.ts'))
})

void test('describeRejectedHostMessage reports review shape problems', () => {
  assert.equal(
    describeRejectedHostMessage({ ...reviewResult({}), result: 'nope' }),
    '"reviewResult": result is not an object',
  )
  assert.equal(
    describeRejectedHostMessage({ ...reviewResult({}), result: { summary: 1, lineComments: [] } }),
    '"reviewResult": summary is not a string',
  )
  assert.equal(
    describeRejectedHostMessage({ ...reviewResult({}), result: { summary: 's', lineComments: {} } }),
    '"reviewResult": lineComments is not an array',
  )
})

void test('describeRejectedHostMessage falls back to verdict, count, or diff when every comment is valid', () => {
  const message = reviewResult({})
  message.result.verdict = 'MAYBE'

  assert.equal(describeRejectedHostMessage(message), '"reviewResult": verdict "MAYBE", 2 comments, or diff size')
})

void test('describeRejectedHostMessage reports protocol mismatches and non-objects', () => {
  assert.equal(
    describeRejectedHostMessage({ protocolVersion: 2, type: 'reviewResult' }),
    `"reviewResult": protocol version 2, expected ${BRIDGE_PROTOCOL_VERSION}`,
  )
  assert.equal(describeRejectedHostMessage({ protocolVersion: 2 }), `"(missing type)": protocol version 2, expected ${BRIDGE_PROTOCOL_VERSION}`)
  assert.equal(describeRejectedHostMessage('text'), 'payload is not an object')
  assert.equal(describeRejectedHostMessage(null), 'payload is not an object')
})

void test('describeRejectedHostMessage gives other types a generic reason', () => {
  assert.equal(
    describeRejectedHostMessage({ protocolVersion: BRIDGE_PROTOCOL_VERSION, type: 'draftSaved', prKey }),
    '"draftSaved": failed validation',
  )
})

void test('fallbackForRejectedHostMessage ends the run for rejected terminal review messages', () => {
  const expected = { type: 'reviewError', prKey, message: REJECTED_REVIEW_MESSAGE }

  assert.deepEqual(stripVersion(fallbackForRejectedHostMessage(reviewResult({ category: 'futuristic' }))), expected)
  assert.deepEqual(
    stripVersion(fallbackForRejectedHostMessage({ protocolVersion: 2, type: 'reviewError', prKey, message: 7 })),
    expected,
  )
})

void test('fallbackForRejectedHostMessage ignores non-terminal types and untargetable messages', () => {
  assert.equal(fallbackForRejectedHostMessage({ protocolVersion: 1, type: 'draftSaved', prKey }), null)
  assert.equal(fallbackForRejectedHostMessage({ ...reviewResult({}), prKey: 'not a key' }), null)
  assert.equal(fallbackForRejectedHostMessage({ ...reviewResult({}), prKey: undefined }), null)
  assert.equal(fallbackForRejectedHostMessage('reviewResult'), null)
})

function stripVersion(message: unknown) {
  if (message === null || typeof message !== 'object') return message
  const { protocolVersion: _ignored, ...rest } = message as Record<string, unknown>
  return rest
}
