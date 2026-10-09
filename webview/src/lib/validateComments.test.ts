import test from 'node:test'
import assert from 'node:assert/strict'

import { validateComments } from './validateComments.js'
import type { LineComment } from '../bridge/types'

function comment(file: string, line: number, body = 'note'): LineComment {
  return { file, line, type: 'issue', body }
}

void test('validateComments keeps general notes when diff parsing fails', () => {
  const result = validateComments('not a diff', [comment('src/Foo.ts', 12), comment('', 0, 'general note')])

  assert.equal(result.adjusted.length, 1)
  assert.equal(result.adjusted[0].file, '')
  assert.equal(result.adjusted[0].body, 'general note')
  assert.equal(result.orphans.length, 1)
  assert.equal(result.orphans[0].file, 'src/Foo.ts')
})

void test('validateComments matches a unique suffix but rejects ambiguous suffixes', () => {
  const uniqueDiff = [
    'diff --git a/src/one/Foo.ts b/src/one/Foo.ts',
    '--- a/src/one/Foo.ts',
    '+++ b/src/one/Foo.ts',
    '@@ -1,1 +1,1 @@',
    '-old',
    '+new',
    '',
  ].join('\n')

  const unique = validateComments(uniqueDiff, [comment('Foo.ts', 1)])
  assert.equal(unique.adjusted.length, 1)
  assert.equal(unique.adjusted[0].line, 1)
  assert.equal(unique.orphans.length, 0)

  const ambiguousDiff = [
    'diff --git a/src/a/Foo.ts b/src/a/Foo.ts',
    '--- a/src/a/Foo.ts',
    '+++ b/src/a/Foo.ts',
    '@@ -1,1 +1,1 @@',
    '-old',
    '+new',
    '',
    'diff --git a/src/b/Foo.ts b/src/b/Foo.ts',
    '--- a/src/b/Foo.ts',
    '+++ b/src/b/Foo.ts',
    '@@ -1,1 +1,1 @@',
    '-old',
    '+new',
    '',
  ].join('\n')

  const ambiguous = validateComments(ambiguousDiff, [comment('Foo.ts', 1)])
  assert.equal(ambiguous.adjusted.length, 0)
  assert.equal(ambiguous.orphans.length, 1)
  assert.equal(ambiguous.orphans[0].file, 'Foo.ts')
})

void test('validateComments does not match file against substring-without-boundary (UserAction vs Action)', () => {
  // Both files exist in the diff. A comment on "Action.java" must NOT match "UserAction.java"
  // because "Action.java" is not preceded by "/" in "UserAction.java".
  const diff = [
    'diff --git a/src/Action.java b/src/Action.java',
    '--- a/src/Action.java',
    '+++ b/src/Action.java',
    '@@ -1,1 +1,1 @@',
    '-old',
    '+new',
    '',
    'diff --git a/src/UserAction.java b/src/UserAction.java',
    '--- a/src/UserAction.java',
    '+++ b/src/UserAction.java',
    '@@ -1,1 +1,1 @@',
    '-old',
    '+new',
    '',
  ].join('\n')

  // Exact match on "src/Action.java" should still work
  const exact = validateComments(diff, [comment('src/Action.java', 1)])
  assert.equal(exact.adjusted.length, 1)
  assert.equal(exact.adjusted[0].file, 'src/Action.java')
  assert.equal(exact.orphans.length, 0)

  // Short name "Action.java" is ambiguous (matches both src/Action.java and src/UserAction.java
  // under the old broken rule) — with the fix, key.endsWith('/Action.java') matches only
  // src/Action.java (unique), so the comment is correctly anchored.
  const short = validateComments(diff, [comment('Action.java', 1)])
  assert.equal(short.adjusted.length, 1)
  assert.equal(short.adjusted[0].file, 'Action.java')
  assert.equal(short.orphans.length, 0)

  // A name that is a true non-boundary substring ("Action") should become an orphan.
  const sub = validateComments(diff, [comment('Action', 1)])
  assert.equal(sub.orphans.length, 1)
})

// Two hunks far apart in the same file: lines 10-12 and lines 200-202.
const twoHunkDiff = [
  'diff --git a/src/Far.ts b/src/Far.ts',
  '--- a/src/Far.ts',
  '+++ b/src/Far.ts',
  '@@ -10,3 +10,3 @@',
  ' ten',
  '+eleven',
  ' twelve',
  '@@ -200,3 +200,3 @@',
  ' two hundred',
  '+two hundred one',
  ' two hundred two',
  '',
].join('\n')

void test('validateComments passes through a line that is already inside a hunk', () => {
  const result = validateComments(twoHunkDiff, [comment('src/Far.ts', 201)])

  assert.equal(result.orphans.length, 0)
  assert.equal(result.adjusted[0].line, 201)
  assert.equal(result.snappedCount, 0)
})

void test('validateComments snaps a near-miss line to its own hunk', () => {
  // 14 is two lines past the first hunk's last line (12), so it snaps within that hunk.
  const result = validateComments(twoHunkDiff, [comment('src/Far.ts', 14)])

  assert.equal(result.orphans.length, 0)
  assert.equal(result.adjusted[0].line, 12)
  assert.equal(result.snappedCount, 1)
})

void test('validateComments orphans a line that is far from every hunk', () => {
  const result = validateComments(twoHunkDiff, [comment('src/Far.ts', 100)])

  assert.equal(result.adjusted.length, 0)
  assert.equal(result.orphans.length, 1)
})

void test('validateComments orphans comments on a deleted file rather than mis-anchoring them', () => {
  // A pure-deletion file has only `delete` changes, which carry no new-file line number, so it
  // yields an empty line set and every comment on it orphans into the review body's "Comments not
  // attached inline" section. That degradation is deliberate: anchoring these inline would need a
  // `side: LEFT` field threaded through the bridge schema and both hosts, for a case the review
  // prompt already forbids ("only comment on changed ('+') lines"). The comment still reaches the
  // reviewer, just not inline.
  const deletedFileDiff = [
    'diff --git a/src/Gone.ts b/src/Gone.ts',
    'deleted file mode 100644',
    '--- a/src/Gone.ts',
    '+++ /dev/null',
    '@@ -1,3 +0,0 @@',
    '-const a = 1',
    '-const b = 2',
    '-export { a, b }',
    '',
  ].join('\n')

  const result = validateComments(deletedFileDiff, [comment('src/Gone.ts', 2)])

  assert.equal(result.adjusted.length, 0)
  assert.equal(result.orphans.length, 1)
  assert.equal(result.orphans[0].file, 'src/Gone.ts')
  assert.equal(result.snappedCount, 0)
})

void test('validateComments still anchors surviving files when the diff also deletes one', () => {
  const mixedDiff = [
    'diff --git a/src/Gone.ts b/src/Gone.ts',
    'deleted file mode 100644',
    '--- a/src/Gone.ts',
    '+++ /dev/null',
    '@@ -1,1 +0,0 @@',
    '-const a = 1',
    '',
    'diff --git a/src/Kept.ts b/src/Kept.ts',
    '--- a/src/Kept.ts',
    '+++ b/src/Kept.ts',
    '@@ -1,1 +1,1 @@',
    '-old',
    '+new',
    '',
  ].join('\n')

  const result = validateComments(mixedDiff, [comment('src/Kept.ts', 1), comment('src/Gone.ts', 1)])

  assert.equal(result.adjusted.length, 1)
  assert.equal(result.adjusted[0].file, 'src/Kept.ts')
  assert.equal(result.orphans.length, 1)
  assert.equal(result.orphans[0].file, 'src/Gone.ts')
})

void test('validateComments does not snap across a hunk boundary', () => {
  // Adjacent hunks separated by a two-line gap: 20-21 and 24-25. Line 22 and 23 sit in the gap
  // and are within SNAP_RADIUS of both, so neither hunk owns them.
  const adjacentHunkDiff = [
    'diff --git a/src/Near.ts b/src/Near.ts',
    '--- a/src/Near.ts',
    '+++ b/src/Near.ts',
    '@@ -20,2 +20,2 @@',
    ' twenty',
    '+twenty one',
    '@@ -24,2 +24,2 @@',
    ' twenty four',
    '+twenty five',
    '',
  ].join('\n')

  const result = validateComments(adjacentHunkDiff, [comment('src/Near.ts', 22), comment('src/Near.ts', 23)])

  // A flat per-file line set would have snapped 22 → 21 and 23 → 24, silently attaching each
  // comment to a hunk it does not belong to.
  assert.equal(result.adjusted.length, 0)
  assert.equal(result.orphans.length, 2)
  assert.equal(result.snappedCount, 0)
})

void test('validateComments keeps a suggested change on an exact anchor', () => {
  const result = validateComments(twoHunkDiff, [{ ...comment('src/Far.ts', 201), suggestedChange: 'two hundred and one' }])

  assert.equal(result.adjusted[0].suggestedChange, 'two hundred and one')
})

void test('validateComments clears a suggested change when snapping moves the line', () => {
  const result = validateComments(twoHunkDiff, [{ ...comment('src/Far.ts', 14), suggestedChange: 'fourteen' }])

  assert.equal(result.adjusted[0].line, 12)
  assert.equal('suggestedChange' in result.adjusted[0], false)
  assert.equal(result.adjusted[0].body, 'note')
})
