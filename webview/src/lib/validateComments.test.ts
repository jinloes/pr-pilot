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
