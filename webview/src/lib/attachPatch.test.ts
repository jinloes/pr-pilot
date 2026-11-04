import test from 'node:test'
import assert from 'node:assert/strict'

import {
  attachPatchBlocker,
  MAX_ATTACHED_PATCH_CHARS,
  splitAttachedPatch,
  stripAttachedPatch,
  withAttachedPatch,
} from './attachPatch.js'

const DIFF_PATCH = '```diff\n--- a/x.java\n+++ b/x.java\n@@\n-old\n+new\n```'

void test('withAttachedPatch appends the patch as a collapsed diff block after the comment', () => {
  const body = withAttachedPatch('Fall back to any actor.', DIFF_PATCH)

  assert.equal(
    body,
    'Fall back to any actor.\n\n<details>\n<summary>Suggested patch</summary>\n\n'
      + '```diff\n--- a/x.java\n+++ b/x.java\n@@\n-old\n+new\n```\n\n</details>',
  )
})

void test('withAttachedPatch replaces a previously attached patch instead of stacking a second', () => {
  const first = withAttachedPatch('Comment.', DIFF_PATCH)!
  const second = withAttachedPatch(first, '```java\nreturn b;\n```')!

  assert.equal(second.split('<summary>Suggested patch</summary>').length, 2)
  assert.ok(second.startsWith('Comment.\n\n<details>'))
  assert.ok(second.includes('```java\nreturn b;\n```'))
  assert.ok(!second.includes('+new'))
})

void test('withAttachedPatch labels an unfenced diff and keeps an unfenced snippet untagged', () => {
  assert.ok(withAttachedPatch('c', '--- a/x\n+++ b/x\n@@\n-a\n+b')!.includes('```diff\n--- a/x'))
  assert.ok(withAttachedPatch('c', 'return b;')!.includes('```\nreturn b;\n```'))
})

void test('withAttachedPatch lengthens the fence past backtick runs inside the patch', () => {
  const body = withAttachedPatch('c', '````md\nuse ```code``` here\n````')!

  assert.ok(body.includes('\n````md\nuse ```code``` here\n````\n'))
})

void test('withAttachedPatch rejects empty and oversized patches', () => {
  assert.equal(withAttachedPatch('c', '```diff\n\n```'), null)
  assert.equal(withAttachedPatch('c', 'x'.repeat(MAX_ATTACHED_PATCH_CHARS + 1)), null)
  assert.notEqual(withAttachedPatch('c', 'x'.repeat(MAX_ATTACHED_PATCH_CHARS)), null)
})

void test('attachPatchBlocker explains why a patch cannot be attached', () => {
  assert.equal(attachPatchBlocker(null), 'empty')
  assert.equal(attachPatchBlocker('   '), 'empty')
  assert.equal(attachPatchBlocker('x'.repeat(MAX_ATTACHED_PATCH_CHARS + 1)), 'tooLarge')
  assert.equal(attachPatchBlocker(DIFF_PATCH), null)
})

void test('splitAttachedPatch separates the comment text from the attached code block', () => {
  const body = withAttachedPatch('Fall back to any actor.', DIFF_PATCH)!

  assert.deepEqual(splitAttachedPatch(body), {
    text: 'Fall back to any actor.',
    patch: '```diff\n--- a/x.java\n+++ b/x.java\n@@\n-old\n+new\n```',
  })
  assert.equal(stripAttachedPatch(body), 'Fall back to any actor.')
})

void test('splitAttachedPatch leaves a body without an attached patch unchanged', () => {
  assert.deepEqual(splitAttachedPatch('Plain <details> text'), { text: 'Plain <details> text', patch: null })
})
