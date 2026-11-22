import assert from 'node:assert/strict'
import test from 'node:test'
import compactReporter, { buildNodeTestInvocation } from './compact-test-reporter.mjs'

async function collect(events) {
  async function* source() {
    for (const event of events) yield event
  }
  const chunks = []
  for await (const chunk of compactReporter(source())) {
    chunks.push(chunk)
  }
  return chunks.join('')
}

test('compactReporter emits a single summary line when all tests pass', async () => {
  const output = await collect([
    { type: 'test:start', data: { testId: 1, parentId: 0, name: 'suite' } },
    { type: 'test:start', data: { testId: 2, parentId: 1, name: 'works' } },
    { type: 'test:pass', data: { testId: 2, name: 'works', details: { type: 'test' } } },
    { type: 'test:pass', data: { testId: 1, name: 'suite', details: { type: 'suite' } } },
    {
      type: 'test:summary',
      data: {
        success: true,
        counts: { passed: 12, failed: 0, skipped: 1, todo: 0, cancelled: 0 },
        duration_ms: 1420,
      },
    },
  ])
  assert.equal(output, 'ok   12 passed, 1 skipped (1.4s)\n')
})

test('compactReporter prints failing leaf breadcrumb, location, and error without suite duplicate', async () => {
  const err = new AssertionErrorStub('expected 1 to equal 2')
  const output = await collect([
    { type: 'test:start', data: { testId: 1, parentId: 0, name: 'Parser' } },
    { type: 'test:start', data: { testId: 2, parentId: 1, name: 'rejects blanks' } },
    {
      type: 'test:fail',
      data: {
        testId: 2,
        name: 'rejects blanks',
        file: 'test/parser.test.js',
        line: 42,
        column: 7,
        details: { type: 'test', error: { cause: err } },
      },
    },
    {
      type: 'test:fail',
      data: {
        testId: 1,
        name: 'Parser',
        file: 'test/parser.test.js',
        line: 10,
        column: 1,
        details: { type: 'suite', error: { failureType: 'subtestsFailed' } },
      },
    },
    {
      type: 'test:summary',
      data: {
        success: false,
        counts: { passed: 5, failed: 1, skipped: 0, todo: 0, cancelled: 0 },
        duration_ms: 310,
      },
    },
  ])
  assert.match(output, /^FAIL Parser > rejects blanks \(test\/parser\.test\.js:42:7\)\n/)
  assert.match(output, /expected 1 to equal 2/)
  assert.match(output, /FAIL 1 failed, 5 passed \(0\.3s\)\n$/)
  assert.equal((output.match(/^FAIL Parser/gm) ?? []).length, 1)
})

test('compactReporter surfaces buffered child stderr when a test file crashes at load time', async () => {
  const output = await collect([
    {
      type: 'test:stderr',
      data: {
        file: 'test/broken.test.js',
        entryFile: 'test/broken.test.js',
        message: 'SyntaxError: Unexpected token\n    at test/broken.test.js:3:1\n',
      },
    },
    {
      type: 'test:fail',
      data: {
        testId: 1,
        name: 'test/broken.test.js',
        file: 'test/broken.test.js',
        line: 1,
        column: 1,
        details: {
          type: 'test',
          error: { failureType: 'testCodeFailure', cause: 'test failed' },
        },
      },
    },
    {
      type: 'test:summary',
      data: { success: false, counts: { passed: 0, failed: 1 }, duration_ms: 50 },
    },
  ])
  assert.match(output, /SyntaxError: Unexpected token/)
})

test('buildNodeTestInvocation filters matching files or falls back to --test-name-pattern', () => {
  const files = ['dist-test/test/wireCatalog.test.js', '../scripts/verify.test.mjs']

  const defaultRun = buildNodeTestInvocation({
    files,
    argv: [],
    env: {},
    reporterPath: 'file:///rep.mjs',
  })
  assert.equal(defaultRun.verbose, false)
  assert.deepEqual(defaultRun.nodeArgs, ['--test', '--test-reporter=file:///rep.mjs', ...files])

  const fsPathRun = buildNodeTestInvocation({
    files,
    argv: [],
    env: {},
    reporterPath: process.platform === 'win32' ? 'C:\\repo\\rep.mjs' : '/repo/rep.mjs',
  })
  assert.match(fsPathRun.nodeArgs[1], /^--test-reporter=file:\/\/\/.*\/rep\.mjs$/)

  const byFile = buildNodeTestInvocation({
    files,
    argv: ['wireCatalog'],
    env: {},
    reporterPath: '/rep.mjs',
  })
  assert.deepEqual(byFile.selectedFiles, ['dist-test/test/wireCatalog.test.js'])
  assert.ok(!byFile.nodeArgs.some((arg) => arg.startsWith('--test-name-pattern=')))

  const byTestName = buildNodeTestInvocation({
    files,
    argv: ['resolveNvmrcNode', '--verbose'],
    env: {},
    reporterPath: '/rep.mjs',
  })
  assert.equal(byTestName.verbose, true)
  assert.deepEqual(byTestName.selectedFiles, files)
  assert.ok(byTestName.nodeArgs.includes('--test-reporter=spec'))
  assert.ok(byTestName.nodeArgs.includes('--test-name-pattern=resolveNvmrcNode'))
})

class AssertionErrorStub extends Error {
  constructor(message) {
    super(message)
    this.name = 'AssertionError'
  }
}
