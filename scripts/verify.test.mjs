import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import {
  SOURCE_FILE_MAX_BYTES,
  TEST_FILE_MAX_BYTES,
  changedFiles,
  executeChecks,
  largeFileNotes,
  nodeVersionSupported,
  parseVerifyArgs,
  planChecks,
  resolveNvmrcNode,
} from './verify.mjs'

const names = (plan) => plan.checks.map((check) => check.name)
const gradleTasks = (plan) => plan.checks.find((check) => check.gradle)?.gradle.slice(2) ?? []

test('agent-doc changes run only the agent-docs check', () => {
  const plan = planChecks(['AGENTS.md', 'docs/architecture/webview.md', 'core/README.md'])
  assert.deepEqual(names(plan), ['agent docs'])
  assert.deepEqual(plan.notes, [])
})

test('other docs need no checks', () => {
  const plan = planChecks(['core/README.md', 'docs/intellij-assisted-review.md', 'diagrams/architecture.md'])
  assert.deepEqual(plan.checks, [])
})

test('hand-mirrored files add a reminder note', () => {
  const plan = planChecks(['review-engine/src/main/java/com/jinloes/prpilot/review/CopilotService.java'])
  assert.equal(plan.notes.length, 1)
  assert.match(plan.notes[0], /^Hand-mirrored .*check vscode-extension\/src\/copilot\.ts/)
})

test('a core change checks file budgets and every downstream JVM module in build order', () => {
  const plan = planChecks(['core/src/main/java/com/jinloes/prpilot/model/PullRequest.java'])
  assert.deepEqual(gradleTasks(plan), [
    ':core:check',
    ':github-engine:check',
    ':review-engine:check',
    ':sidecar:check',
    ':review-benchmark:check',
    ':intellij-plugin:check',
  ])
  assert.deepEqual(names(plan), [
    'file budgets',
    'gradle :core:check :github-engine:check :review-engine:check :sidecar:check :review-benchmark:check :intellij-plugin:check',
  ])
})

test('a leaf module change checks only that module', () => {
  const plan = planChecks(['sidecar/src/main/java/com/jinloes/prpilot/sidecar/Foo.java'])
  assert.deepEqual(gradleTasks(plan), [':sidecar:check'])
})

test('engine interface changes also run the VS Code wire catalog tests', () => {
  const plan = planChecks([
    'review-engine/src/main/java/com/jinloes/prpilot/engine/ReviewEngineApi.java',
  ])
  assert.ok(names(plan).includes('vscode-extension unit tests'))
  assert.ok(!names(plan).includes('webview unit tests'))
})

test('diff-coverage golden changes run webview unit tests without Playwright a11y', () => {
  const plan = planChecks(['core/src/test/resources/diff-coverage/trailer.golden.txt'])
  assert.ok(names(plan).includes('webview unit tests'))
  assert.ok(!names(plan).includes('webview a11y tests'))
})

test('webview source changes run a11y and add a visual-check note while unit-test-only changes skip both', () => {
  const appChange = planChecks(['webview/src/App.tsx'])
  assert.equal(appChange.notes.length, 1)
  assert.ok(names(appChange).includes('webview a11y tests'))
  const testOnly = planChecks(['webview/src/lib/diffCoverage.test.ts'])
  assert.equal(testOnly.notes.length, 0)
  assert.ok(names(testOnly).includes('webview lint'))
  assert.ok(names(testOnly).includes('webview unit tests'))
  assert.ok(!names(testOnly).includes('webview a11y tests'))
  assert.ok(!names(testOnly).some((name) => name.startsWith('gradle')))
})

test('script changes run the extension test runner that executes script tests', () => {
  assert.ok(names(planChecks(['scripts/verify.mjs'])).includes('vscode-extension unit tests'))
})

test('Playwright image inputs run the pin check and unrelated changes do not', () => {
  for (const file of [
    '.github/workflows/release.yml',
    'webview/AGENTS.md',
    'webview/package.json',
    'webview/package-lock.json',
    'scripts/playwright-image.mjs',
    'scripts/playwright-image.test.mjs',
  ]) {
    assert.ok(names(planChecks([file])).includes('playwright image pins'), file)
  }
  for (const file of ['webview/src/App.tsx', 'docs/architecture/ci.md', 'vscode-extension/package-lock.json']) {
    assert.ok(!names(planChecks([file])).includes('playwright image pins'), file)
  }
})

test('CI config changes outside workflows add only a lint note', () => {
  const plan = planChecks(['.github/dependabot.yml', '.github/scripts/trusted-jdks.sh'])
  assert.deepEqual(plan.checks, [])
  assert.equal(plan.notes.length, 1)
  assert.match(plan.notes[0], /^CI config changed: lint with actionlint, shellcheck, and zizmor /)
})

test('workflow changes run everything and add the lint note', () => {
  const plan = planChecks(['.github/workflows/ci.yml'])
  assert.deepEqual(names(plan).slice(0, 3), ['agent docs', 'playwright image pins', 'file budgets'])
  assert.equal(gradleTasks(plan).length, 6)
  assert.equal(plan.notes.length, 2)
  assert.match(plan.notes[0], /^CI config changed: /)
  assert.match(plan.notes[1], /^Webview UI changed: /)
})

test('root build files and --all run everything', () => {
  for (const plan of [planChecks(['settings.gradle']), planChecks([], { all: true })]) {
    assert.deepEqual(names(plan).slice(0, 3), ['agent docs', 'playwright image pins', 'file budgets'])
    assert.equal(gradleTasks(plan).length, 6)
    assert.ok(names(plan).includes('webview a11y tests'))
    assert.ok(names(plan).includes('vscode-extension unit tests'))
    assert.equal(plan.notes.length, 1)
  }
})

test('windows-style paths are normalized', () => {
  assert.deepEqual(gradleTasks(planChecks(['sidecar\\src\\main\\java\\Foo.java'])), [':sidecar:check'])
})

test('node version guard matches the webview engines floor', () => {
  assert.equal(nodeVersionSupported('v20.19.0'), false)
  assert.equal(nodeVersionSupported('v24.22.2'), false)
  assert.equal(nodeVersionSupported('v26.0.0'), true)
  assert.equal(nodeVersionSupported('v26.13.0'), true)
  assert.equal(nodeVersionSupported('v27.1.0'), true)
})

test('resolveNvmrcNode prefers exact .nvmrc install then newest installed >= 26', () => {
  const pinnedBin = path.posix.join('/home/dev', '.nvm', 'versions', 'node', 'v26.13.0', 'bin', 'node')
  const newerBin = path.posix.join('/home/dev', '.nvm', 'versions', 'node', 'v26.15.0', 'bin', 'node')
  const versionsDir = path.posix.join('/home/dev', '.nvm', 'versions', 'node')

  assert.equal(
    resolveNvmrcNode('/repo', {
      platform: 'darwin',
      homedir: '/home/dev',
      env: {},
      existsSync: (target) => target === path.join('/repo', '.nvmrc') || target === pinnedBin,
      readFileSync: () => '26.13.0\n',
      readdirSync: () => ['v22.10.0', 'v26.13.0', 'v26.15.0'],
    }),
    pinnedBin,
  )

  assert.equal(
    resolveNvmrcNode('/repo', {
      platform: 'linux',
      homedir: '/home/dev',
      env: {},
      existsSync: (target) => target === versionsDir || target === newerBin,
      readFileSync: () => '',
      readdirSync: () => ['v22.10.0', 'v26.4.0', 'v26.15.0'],
    }),
    newerBin,
  )

  const winVoltaPinned = 'C:\\Users\\dev\\AppData\\Local\\Volta\\tools\\image\\node\\26.13.0\\node.exe'
  assert.equal(
    resolveNvmrcNode('C:\\repo', {
      platform: 'win32',
      homedir: 'C:\\Users\\dev',
      env: { LOCALAPPDATA: 'C:\\Users\\dev\\AppData\\Local' },
      existsSync: (target) => target === path.join('C:\\repo', '.nvmrc') || target === winVoltaPinned,
      readFileSync: () => '26.13.0\r\n',
      readdirSync: () => [],
    }),
    winVoltaPinned,
  )

  const winNvmLatest = 'C:\\Users\\dev\\AppData\\Roaming\\nvm\\v26.14.0\\node.exe'
  const winNvmDir = 'C:\\Users\\dev\\AppData\\Roaming\\nvm'
  assert.equal(
    resolveNvmrcNode('C:\\repo', {
      platform: 'win32',
      homedir: 'C:\\Users\\dev',
      env: { APPDATA: 'C:\\Users\\dev\\AppData\\Roaming' },
      existsSync: (target) => target === winNvmDir || target === winNvmLatest,
      readFileSync: () => '',
      readdirSync: (dir) => (dir === winNvmDir ? ['v20.20.2', 'v26.14.0'] : []),
    }),
    winNvmLatest,
  )

  assert.equal(
    resolveNvmrcNode('/repo', {
      platform: 'linux',
      homedir: '/home/dev',
      env: {},
      existsSync: () => false,
      readFileSync: () => '',
      readdirSync: () => [],
    }),
    null,
  )
})

test('executeChecks runs distinct lanes concurrently while keeping same-lane steps sequential', async () => {
  const events = []
  let active = 0
  let maxActive = 0
  const checks = [
    { name: 'ext-lint', lane: 'extension' },
    { name: 'ext-unit', lane: 'extension' },
    { name: 'webview-lint', lane: 'webview-lint' },
    { name: 'gradle', lane: 'gradle' },
  ]
  await executeChecks(
    checks,
    async (check) => {
      events.push(`start:${check.name}`)
      active++
      maxActive = Math.max(maxActive, active)
      await new Promise((resolve) => setTimeout(resolve, 15))
      active--
      events.push(`end:${check.name}`)
      return { ok: true, output: '', warnings: [] }
    },
    () => {},
  )

  assert.ok(maxActive >= 3, `expected at least 3 lanes active concurrently, saw ${maxActive}`)
  assert.ok(
    events.indexOf('end:ext-lint') < events.indexOf('start:ext-unit'),
    `same-lane checks must stay sequential: ${events.join(', ')}`,
  )
})

test('executeChecks honors maxParallelLanes cap', async () => {
  let active = 0
  let maxActive = 0
  const checks = [
    { name: 'lane-1', lane: 'l1' },
    { name: 'lane-2', lane: 'l2' },
    { name: 'lane-3', lane: 'l3' },
  ]
  await executeChecks(
    checks,
    async () => {
      active++
      maxActive = Math.max(maxActive, active)
      await new Promise((resolve) => setTimeout(resolve, 15))
      active--
      return { ok: true, output: '', warnings: [] }
    },
    () => {},
    { maxParallelLanes: 2 },
  )
  assert.equal(maxActive, 2)
})

test('executeChecks waits for present afterLane dependencies and ignores absent ones', async () => {
  const events = []
  const withGradle = [
    { name: 'gradle', lane: 'gradle' },
    { name: 'unit', lane: 'webview-tests' },
    { name: 'a11y', lane: 'webview-a11y', afterLane: ['gradle', 'webview-tests'] },
  ]
  await executeChecks(
    withGradle,
    async (check) => {
      events.push(`start:${check.name}`)
      await new Promise((resolve) => setTimeout(resolve, check.name === 'gradle' ? 20 : 5))
      events.push(`end:${check.name}`)
      return { ok: true, output: '', warnings: [] }
    },
    () => {},
  )
  assert.ok(events.indexOf('end:gradle') < events.indexOf('start:a11y'))
  assert.ok(events.indexOf('end:unit') < events.indexOf('start:a11y'))

  const withoutGradleEvents = []
  await executeChecks(
    [
      { name: 'unit', lane: 'webview-tests' },
      { name: 'a11y', lane: 'webview-a11y', afterLane: ['gradle', 'webview-tests'] },
    ],
    async (check) => {
      withoutGradleEvents.push(`start:${check.name}`)
      return { ok: true, output: '', warnings: [] }
    },
    () => {},
  )
  assert.deepEqual(withoutGradleEvents, ['start:unit', 'start:a11y'])
})

test('parseVerifyArgs supports --since, --uncommitted, --jobs, and rejects invalid flags', () => {
  assert.deepEqual(parseVerifyArgs(['--since', 'HEAD~1', '--jobs=2', 'core/Foo.java'], {}), {
    all: false,
    dryRun: false,
    uncommittedOnly: false,
    since: 'HEAD~1',
    jobs: 2,
    files: ['core/Foo.java'],
  })
  assert.deepEqual(parseVerifyArgs(['--uncommitted', '--dry-run'], {}), {
    all: false,
    dryRun: true,
    uncommittedOnly: true,
    since: 'HEAD',
    jobs: 3,
    files: [],
  })
  assert.equal(parseVerifyArgs([], { PR_PILOT_VERIFY_JOBS: '1' }).jobs, 1)
  assert.throws(() => parseVerifyArgs(['--since'], {}), /Missing git ref/)
  assert.throws(() => parseVerifyArgs(['--jobs', '0'], {}), /Invalid --jobs/)
  assert.throws(() => parseVerifyArgs(['--bogus'], {}), /Unknown option/)
})

test('changedFiles skips merge-base committed diff when uncommittedOnly or --since HEAD is used', () => {
  const calls = []
  const fakeGit = (args) => {
    calls.push(args.join(' '))
    if (args[0] === 'merge-base') return 'abc123\n'
    if (args[0] === 'diff' && args[2] === 'abc123') return 'committed/A.java\n'
    if (args[0] === 'diff' && args[2] === 'HEAD') return 'dirty/B.java\n'
    if (args[0] === 'ls-files') return 'untracked/C.java\n'
    return ''
  }

  assert.deepEqual(changedFiles('/repo', 'origin/main', fakeGit), [
    'committed/A.java',
    'dirty/B.java',
    'untracked/C.java',
  ])
  calls.length = 0
  assert.deepEqual(changedFiles('/repo', { base: 'HEAD', uncommittedOnly: true }, fakeGit), [
    'dirty/B.java',
    'untracked/C.java',
  ])
  assert.ok(!calls.some((call) => call.startsWith('merge-base')))
})

test('largeFileNotes flags touched source and test files exceeding LF byte budgets', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pr-pilot-verify-size-'))
  try {
    const write = (rel, content) => {
      const target = path.join(dir, rel)
      fs.mkdirSync(path.dirname(target), { recursive: true })
      fs.writeFileSync(target, content)
    }
    write('core/src/test/java/FooTest.java', 'x'.repeat(TEST_FILE_MAX_BYTES + 1))
    write('core/src/main/java/Foo.java', 'x'.repeat(SOURCE_FILE_MAX_BYTES + 1))
    write('webview/src/App.test.tsx', 'x'.repeat(TEST_FILE_MAX_BYTES))
    write('webview/src/App.tsx', 'x'.repeat(SOURCE_FILE_MAX_BYTES))
    write('webview/fixtures/big.json', 'x'.repeat(SOURCE_FILE_MAX_BYTES * 2))

    const notes = largeFileNotes(dir, [
      'core/src/test/java/FooTest.java',
      'core/src/main/java/Foo.java',
      'webview/src/App.test.tsx',
      'webview/src/App.tsx',
      'webview/fixtures/big.json',
      'core/src/main/java/Deleted.java',
    ])
    assert.equal(notes.length, 2)
    assert.match(notes[0], /^Large test file: core\/src\/test\/java\/FooTest\.java/)
    assert.match(notes[1], /^Large source file: core\/src\/main\/java\/Foo\.java/)
  } finally {
    fs.rmSync(dir, { recursive: true, force: true })
  }
})


