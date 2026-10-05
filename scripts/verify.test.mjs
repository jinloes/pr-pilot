import assert from 'node:assert/strict'
import test from 'node:test'
import { nodeVersionSupported, planChecks } from './verify.mjs'

const names = (plan) => plan.checks.map((check) => check.name)
const gradleTasks = (plan) => plan.checks.find((check) => check.gradle)?.gradle.slice(2) ?? []

test('docs-only changes need no checks', () => {
  const plan = planChecks(['AGENTS.md', 'docs/architecture/webview.md', 'core/README.md'])
  assert.deepEqual(plan.checks, [])
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

test('root build files and --all run everything', () => {
  for (const plan of [planChecks(['settings.gradle']), planChecks([], { all: true })]) {
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
