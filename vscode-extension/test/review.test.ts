import test from 'node:test';
import assert from 'node:assert/strict';

import { parseReview } from '../src/review';

const valid = JSON.stringify({
  summary: '## Overview\nDoes a thing.',
  verdict: 'COMMENT',
  lineComments: [{ file: 'src/a.ts', line: 5, type: 'issue', body: 'fix' }],
});

test('parseReview parses a well-formed object', () => {
  const r = parseReview(valid);
  assert.equal(r.verdict, 'COMMENT');
  assert.equal(r.lineComments.length, 1);
  assert.equal(r.lineComments[0].file, 'src/a.ts');
});

test('parseReview strips markdown fences', () => {
  const r = parseReview('```json\n' + valid + '\n```');
  assert.equal(r.verdict, 'COMMENT');
});

test('parseReview strips surrounding prose', () => {
  const r = parseReview('Here is the review:\n' + valid + '\nThanks!');
  assert.equal(r.lineComments.length, 1);
});

test('parseReview accepts empty lineComments', () => {
  const r = parseReview(JSON.stringify({ summary: 's', verdict: 'APPROVE', lineComments: [] }));
  assert.equal(r.lineComments.length, 0);
});

test('parseReview rejects non-object JSON', () => {
  assert.throws(() => parseReview('[1,2,3]'), /not an object/);
});

test('parseReview rejects missing summary', () => {
  assert.throws(
    () => parseReview(JSON.stringify({ verdict: 'APPROVE', lineComments: [] })),
    /summary/,
  );
});

test('parseReview rejects invalid verdict', () => {
  assert.throws(
    () => parseReview(JSON.stringify({ summary: 's', verdict: 'LGTM', lineComments: [] })),
    /verdict/,
  );
});

test('parseReview rejects non-array lineComments', () => {
  assert.throws(
    () => parseReview(JSON.stringify({ summary: 's', verdict: 'APPROVE', lineComments: {} })),
    /lineComments/,
  );
});

test('parseReview rejects lineComment with wrong field types', () => {
  assert.throws(
    () => parseReview(JSON.stringify({
      summary: 's',
      verdict: 'APPROVE',
      lineComments: [{ file: 'a', line: '5', type: 'issue', body: 'b' }],
    })),
    /lineComments/,
  );
});

test('parseReview rejects lineComment with invalid type', () => {
  assert.throws(
    () => parseReview(JSON.stringify({
      summary: 's',
      verdict: 'APPROVE',
      lineComments: [{ file: 'a', line: 5, type: 'nit', body: 'b' }],
    })),
    /lineComments/,
  );
});

test('parseReview throws on non-JSON input', () => {
  assert.throws(() => parseReview('not json at all'));
});

test('parseReview keeps valid severity, category, confidence, and rationale', () => {
  const r = parseReview(JSON.stringify({
    summary: 's',
    verdict: 'REQUEST_CHANGES',
    lineComments: [{
      file: 'a.ts', line: 5, type: 'issue', body: 'b',
      severity: 'major', category: 'security', confidence: 'high', rationale: 'read the schema',
    }],
  }));
  const c = r.lineComments[0];
  assert.equal(c.severity, 'major');
  assert.equal(c.category, 'security');
  assert.equal(c.confidence, 'high');
  assert.equal(c.rationale, 'read the schema');
});

test('parseReview drops invalid enum values for the richer fields', () => {
  const r = parseReview(JSON.stringify({
    summary: 's',
    verdict: 'COMMENT',
    lineComments: [{
      file: 'a.ts', line: 5, type: 'note', body: 'b',
      severity: 'catastrophic', category: 'vibes', confidence: 'certain',
    }],
  }));
  const c = r.lineComments[0];
  assert.equal(c.severity, undefined);
  assert.equal(c.category, undefined);
  assert.equal(c.confidence, undefined);
});

test('parseReview normalizes mixed-case enums and omits absent rich fields', () => {
  const r = parseReview(JSON.stringify({
    summary: 's',
    verdict: 'COMMENT',
    lineComments: [{ file: 'a.ts', line: 5, type: 'note', body: 'b', severity: 'NIT' }],
  }));
  const c = r.lineComments[0];
  assert.equal(c.severity, 'nit');
  assert.equal(c.category, undefined);
  assert.equal(c.rationale, undefined);
});
