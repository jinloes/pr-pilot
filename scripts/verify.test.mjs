import assert from 'node:assert/strict'
import test from 'node:test'
import { nodeVersionSupported, planChecks } from './verify.mjs'

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
  const plan = planChecks(['review-engine/src/main/java/com/jinloes/prpilot/review/BinaryLocator.java'])
  assert.equal(plan.notes.length, 1)
  assert.match(plan.notes[0], /^Hand-mirrored .*check vscode-extension\/src\/claude\.ts/)
})

test('a core change checks every downstream JVM module in build order', () => {
  const plan = planChecks(['core/src/main/java/com/jinloes/prpilot/model/PullRequest.java'])
  assert.deepEqual(gradleTasks(plan), [
    ':core:check',
    ':github-engine:check',
    ':review-engine:check',
    ':sidecar:check',
    ':review-benchmark:check',
    ':intellij-plugin:check',
  ])
  assert.equal(plan.checks.length, 1)
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

test('diff-coverage golden changes also run webview tests', () => {
  const plan = planChecks(['core/src/test/resources/diff-coverage/trailer.golden.txt'])
  assert.ok(names(plan).includes('webview unit tests'))
})

test('webview source changes add a visual-check note but test-only changes do not', () => {
  assert.equal(planChecks(['webview/src/App.tsx']).notes.length, 1)
  const testOnly = planChecks(['webview/src/lib/diffCoverage.test.ts'])
  assert.equal(testOnly.notes.length, 0)
  assert.ok(names(testOnly).includes('webview lint'))
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
  assert.deepEqual(names(plan).slice(0, 2), ['agent docs', 'playwright image pins'])
  assert.equal(gradleTasks(plan).length, 6)
  assert.equal(plan.notes.length, 2)
  assert.match(plan.notes[0], /^CI config changed: /)
  assert.match(plan.notes[1], /^Webview UI changed: /)
})

test('root build files and --all run everything', () => {
  for (const plan of [planChecks(['settings.gradle']), planChecks([], { all: true })]) {
    assert.deepEqual(names(plan).slice(0, 2), ['agent docs', 'playwright image pins'])
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
  assert.equal(nodeVersionSupported('v16.19.0'), false)
  assert.equal(nodeVersionSupported('v20.18.3'), false)
  assert.equal(nodeVersionSupported('v20.19.0'), true)
  assert.equal(nodeVersionSupported('v22.1.0'), true)
})
