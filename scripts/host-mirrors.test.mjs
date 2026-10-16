import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { MIRRORS, mirrorNotes } from './host-mirrors.mjs'

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const JAVA = 'src/main/java/com/jinloes/prpilot'

function patternExists(pattern) {
  if (!pattern.includes('*')) return fs.existsSync(path.join(repoRoot, pattern))
  const dir = path.posix.dirname(pattern)
  const name = new RegExp(`^${path.posix.basename(pattern).replace(/[.]/g, '\\.').replaceAll('*', '.*')}$`)
  return fs.readdirSync(path.join(repoRoot, dir)).some((entry) => name.test(entry))
}

test('every mirrored path still exists', () => {
  const missing = MIRRORS.flatMap((mirror) => mirror.sides.flat()).filter((p) => !patternExists(p))
  assert.deepEqual(missing, [])
})

test('every entry names two non-empty sides', () => {
  for (const mirror of MIRRORS) {
    assert.equal(mirror.sides.length, 2, mirror.what)
    assert.ok(mirror.sides.every((side) => side.length > 0), mirror.what)
  }
})

test('a JVM-side change points at the TypeScript copy', () => {
  const notes = mirrorNotes([`review-engine/${JAVA}/review/BinaryLocator.java`])
  assert.equal(notes.length, 1)
  assert.match(notes[0], /^Hand-mirrored CLI binary-probing candidates: you changed .*BinaryLocator\.java;/)
  assert.match(notes[0], /check vscode-extension\/src\/claude\.ts, vscode-extension\/src\/copilot\.ts\.$/)
})

test('a TypeScript-side change points back at the JVM copy', () => {
  const notes = mirrorNotes(['vscode-extension/src/repositoryInstructions.ts'])
  assert.equal(notes.length, 1)
  assert.match(notes[0], /check intellij-plugin\/.*\/RepositoryReviewInstructions\.java\. This is/)
})

test('one file can be in several entries', () => {
  const notes = mirrorNotes(['vscode-extension/src/copilot.ts'])
  assert.equal(notes.length, 3)
})

test('one-way entries ignore changes to their second side', () => {
  assert.deepEqual(mirrorNotes(['vscode-extension/src/extension.ts', 'vscode-extension/package.json']), [])
  const notes = mirrorNotes([`intellij-plugin/${JAVA}/settings/PluginSettings.java`])
  assert.equal(notes.length, 1)
  assert.match(notes[0], /package\.json.*A new setting needs a package\.json contribution/)
})

test('changing both sides asks to confirm they agree', () => {
  const notes = mirrorNotes([
    `core/${JAVA}/model/DiffCoverage.java`,
    'webview/src/lib/diffCoverage.ts',
  ])
  assert.equal(notes.length, 1)
  assert.match(notes[0], /both sides changed \(.*DiffCoverage\.java, webview\/src\/lib\/diffCoverage\.ts\)/)
  assert.match(notes[0], /trailer\.golden\.txt/)
})

test('wildcards match within one path segment only', () => {
  assert.equal(mirrorNotes(['vscode-extension/src/settingsViewHtml.ts']).length, 1)
  assert.deepEqual(mirrorNotes(['vscode-extension/src/settingsView/nested.ts']), [])
})

test('windows-style paths are normalized and unrelated files are ignored', () => {
  assert.equal(mirrorNotes(['vscode-extension\\src\\worktree.ts']).length, 1)
  assert.deepEqual(mirrorNotes(['README.md', 'webview/src/App.tsx', '']), [])
})
