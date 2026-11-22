import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import {
  SOURCE_FILE_MAX_BYTES,
  TEST_FILE_MAX_BYTES,
  checkFileBudgets,
  isSizedCodeFile,
  isTestCodeFile,
} from './file-budgets.mjs'

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

function withFiles(files, run) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'file-budgets-test-'))
  try {
    for (const [rel, content] of Object.entries(files)) {
      const target = path.join(root, rel)
      fs.mkdirSync(path.dirname(target), { recursive: true })
      fs.writeFileSync(target, content)
    }
    return run(root)
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
}

test('isSizedCodeFile and isTestCodeFile classify source, test, and ignored paths', () => {
  assert.equal(isSizedCodeFile('core/src/main/java/Foo.java'), true)
  assert.equal(isSizedCodeFile('webview\\src\\App.tsx'), true)
  assert.equal(isSizedCodeFile('AGENTS.md'), false)
  assert.equal(isSizedCodeFile('webview/fixtures/sample.json'), false)

  assert.equal(isTestCodeFile('core/src/test/java/FooTest.java'), true)
  assert.equal(isTestCodeFile('webview/visual/app.visual.spec.ts'), true)
  assert.equal(isTestCodeFile('scripts/verify.test.mjs'), true)
  assert.equal(isTestCodeFile('core/src/main/java/Foo.java'), false)
})

test('checkFileBudgets passes files at or under target and emits non-blocking warnings over target', () => {
  withFiles(
    {
      'core/src/main/java/Ok.java': 'x'.repeat(SOURCE_FILE_MAX_BYTES),
      'core/src/test/java/OkTest.java': 'x'.repeat(TEST_FILE_MAX_BYTES),
      'webview/webview-dist/bundle.js': 'x'.repeat(SOURCE_FILE_MAX_BYTES * 4),
    },
    (root) => assert.deepEqual(checkFileBudgets(root, {}), { errors: [], warnings: [] }),
  )

  withFiles(
    {
      'core/src/main/java/TooBig.java': 'x'.repeat(SOURCE_FILE_MAX_BYTES + 1),
      'core/src/test/java/TooBigTest.java': 'x'.repeat(TEST_FILE_MAX_BYTES + 1),
    },
    (root) => {
      const { errors, warnings } = checkFileBudgets(root, {})
      assert.deepEqual(errors, [])
      assert.equal(warnings.length, 2)
      assert.match(
        warnings[0],
        /^core\/src\/main\/java\/TooBig\.java is 32001 LF bytes \(advisory source target 32000\)/,
      )
      assert.match(
        warnings[1],
        /^core\/src\/test\/java\/TooBigTest\.java is 20001 LF bytes \(advisory test target 20000\)/,
      )
    },
  )
})

test('checkFileBudgets uses LEGACY_CEILINGS as non-blocking advisory baselines', () => {
  withFiles(
    {
      'core/src/main/java/Legacy.java': 'x'.repeat(35_000),
    },
    (root) => {
      assert.deepEqual(checkFileBudgets(root, { 'core/src/main/java/Legacy.java': 35_500 }), {
        errors: [],
        warnings: [],
      })
      const grown = checkFileBudgets(root, { 'core/src/main/java/Legacy.java': 34_500 })
      assert.deepEqual(grown.errors, [])
      assert.equal(grown.warnings.length, 1)
      assert.match(grown.warnings[0], /grew to 35000 LF bytes \(baseline 34500/)
    },
  )

  withFiles(
    {
      'core/src/main/java/SplitNow.java': 'x'.repeat(SOURCE_FILE_MAX_BYTES),
    },
    (root) => {
      const { errors, warnings } = checkFileBudgets(root, {
        'core/src/main/java/SplitNow.java': 35_000,
        'core/src/main/java/Deleted.java': 40_000,
      })
      assert.deepEqual(errors, [])
      assert.equal(warnings.length, 2)
      assert.match(warnings[0], /remove its LEGACY_CEILINGS entry/)
      assert.match(warnings[1], /Deleted\.java in LEGACY_CEILINGS no longer exists/)
    },
  )
})

test('checkFileBudgets never produces hard errors against the repository', () => {
  assert.deepEqual(checkFileBudgets(repoRoot).errors, [])
})
