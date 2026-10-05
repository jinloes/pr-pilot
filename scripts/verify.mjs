// Runs only the checks affected by the current change and prints failures, not full logs.
//
//   node scripts/verify.mjs                 # files changed vs origin/main plus uncommitted/untracked
//   node scripts/verify.mjs path/to/File.java webview/src/App.tsx
//   node scripts/verify.mjs --all           # every required check
//   node scripts/verify.mjs --dry-run       # print the plan without running it
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { gradleWrapperInvocation } from './portable-process.mjs'

const JVM_DOWNSTREAM = {
  core: ['core', 'github-engine', 'review-engine', 'sidecar', 'intellij-plugin', 'review-benchmark'],
  'github-engine': ['github-engine', 'sidecar', 'intellij-plugin', 'review-benchmark'],
  'review-engine': ['review-engine', 'sidecar', 'intellij-plugin', 'review-benchmark'],
  sidecar: ['sidecar'],
  'review-benchmark': ['review-benchmark'],
  'intellij-plugin': ['intellij-plugin'],
}
const JVM_ORDER = Object.keys(JVM_DOWNSTREAM)

// Files whose contents another toolchain's tests read as a source of truth.
const WIRE_CATALOG_SOURCES = [
  'github-engine/src/main/java/com/jinloes/prpilot/engine/GitHubEngineApi.java',
  'review-engine/src/main/java/com/jinloes/prpilot/engine/ReviewEngineApi.java',
  'sidecar/src/main/java/com/jinloes/prpilot/sidecar/SidecarBootstrapService.java',
]
const DIFF_COVERAGE_SOURCES = [
  'core/src/main/java/com/jinloes/prpilot/model/DiffCoverage.java',
  'core/src/test/resources/diff-coverage/',
]

const ROOT_BUILD_FILES = new Set([
  'build.gradle',
  'settings.gradle',
  'gradle.properties',
  'gradlew',
  'gradlew.bat',
])

const DOC_ONLY = /\.(md|mmd)$/i

function webviewChecks() {
  return [
    { name: 'webview lint', cwd: 'webview', npm: ['run', 'lint'] },
    { name: 'webview typecheck', cwd: 'webview', npm: ['exec', '--', 'tsc', '--noEmit'] },
    { name: 'webview unit tests', cwd: 'webview', npm: ['run', 'test:unit'] },
    { name: 'webview a11y tests', cwd: 'webview', npm: ['run', 'test:a11y'] },
  ]
}

function extensionChecks() {
  return [
    { name: 'vscode-extension lint', cwd: 'vscode-extension', npm: ['run', 'lint'] },
    {
      name: 'vscode-extension typecheck',
      cwd: 'vscode-extension',
      npm: ['exec', '--', 'tsc', '--noEmit'],
    },
    { name: 'vscode-extension unit tests', cwd: 'vscode-extension', npm: ['run', 'test:unit'] },
  ]
}

function normalize(file) {
  return file.replaceAll('\\', '/').replace(/^\.\//, '')
}

/**
 * Maps changed repository-relative paths to the smallest set of checks that covers them.
 * Returns `{ checks, notes }`; checks are ordered JVM first, then webview, then extension.
 */
export function planChecks(changedPaths, options = {}) {
  const files = changedPaths.map(normalize).filter(Boolean)
  const jvmModules = new Set()
  let webview = false
  let extension = false
  let visual = false
  const notes = []

  const everything = options.all || files.some((file) =>
    ROOT_BUILD_FILES.has(file) || file.startsWith('gradle/') || file.startsWith('.github/workflows/'))

  if (everything) {
    JVM_ORDER.forEach((module) => jvmModules.add(module))
    webview = true
    extension = true
    visual = true
  }

  for (const file of files) {
    const top = file.split('/')[0]
    if (JVM_DOWNSTREAM[top]) {
      if (DOC_ONLY.test(file)) continue
      JVM_DOWNSTREAM[top].forEach((module) => jvmModules.add(module))
      if (WIRE_CATALOG_SOURCES.includes(file)) extension = true
      if (DIFF_COVERAGE_SOURCES.some((source) => file.startsWith(source))) webview = true
    } else if (top === 'webview') {
      webview = true
      if (/^webview\/(src|visual)\//.test(file) && !/\.test\.tsx?$/.test(file)) visual = true
    } else if (top === 'vscode-extension') {
      extension = true
    } else if (top === 'scripts') {
      // vscode-extension's unit-test runner also executes scripts/*.test.mjs.
      extension = true
    }
  }

  const checks = []
  if (jvmModules.size > 0) {
    const tasks = JVM_ORDER.filter((module) => jvmModules.has(module)).map((m) => `:${m}:check`)
    checks.push({ name: `gradle ${tasks.join(' ')}`, gradle: ['--console=plain', '--continue', ...tasks] })
  }
  if (webview) checks.push(...webviewChecks())
  if (extension) checks.push(...extensionChecks())
  if (visual) {
    notes.push(
      'Webview UI changed: run the Docker visual check in webview/AGENTS.md.',
    )
  }
  return { checks, notes }
}

function git(args, cwd) {
  const result = spawnSync('git', args, { cwd, encoding: 'utf8', shell: false })
  return result.status === 0 ? result.stdout : ''
}

export function changedFiles(repoRoot, base = 'origin/main') {
  const mergeBase = git(['merge-base', base, 'HEAD'], repoRoot).trim()
  const lines = [
    mergeBase ? git(['diff', '--name-only', mergeBase, 'HEAD'], repoRoot) : '',
    git(['diff', '--name-only', 'HEAD'], repoRoot),
    git(['ls-files', '--others', '--exclude-standard'], repoRoot),
  ].join('\n')
  return [...new Set(lines.split('\n').map((line) => line.trim()).filter(Boolean))]
}

function invocationFor(check, repoRoot) {
  if (check.gradle) {
    return { ...gradleWrapperInvocation(check.gradle, { repoRoot }), cwd: repoRoot, shell: false }
  }
  const cwd = path.join(repoRoot, check.cwd)
  if (process.env.npm_execpath) {
    return {
      command: process.execPath,
      args: [process.env.npm_execpath, ...check.npm],
      cwd,
      shell: false,
    }
  }
  const win = process.platform === 'win32'
  return { command: win ? 'npm.cmd' : 'npm', args: check.npm, cwd, shell: win }
}

function describe(check) {
  return check.gradle ? `./gradlew ${check.gradle.join(' ')}` : `(cd ${check.cwd} && npm ${check.npm.join(' ')})`
}

export const MIN_NODE = [20, 19]

export function nodeVersionSupported(version) {
  const [major, minor] = version.replace(/^v/, '').split('.').map(Number)
  return major > MIN_NODE[0] || (major === MIN_NODE[0] && minor >= MIN_NODE[1])
}

function main(argv) {
  if (!nodeVersionSupported(process.version)) {
    console.error(
      `Node ${process.version} is too old; webview tooling needs >= ${MIN_NODE.join('.')} (see .nvmrc).`,
    )
    return 1
  }
  const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
  const flags = new Set(argv.filter((arg) => arg.startsWith('--')))
  const explicit = argv.filter((arg) => !arg.startsWith('--'))
  const files = explicit.length > 0 ? explicit : changedFiles(repoRoot)
  const { checks, notes } = planChecks(files, { all: flags.has('--all') })

  if (checks.length === 0) {
    console.log(files.length === 0 ? 'No changes detected.' : 'No checks needed (docs only).')
    notes.forEach((note) => console.log(`note: ${note}`))
    return 0
  }
  if (flags.has('--dry-run')) {
    checks.forEach((check) => console.log(describe(check)))
    notes.forEach((note) => console.log(`note: ${note}`))
    return 0
  }

  const logDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pr-pilot-verify-'))
  let failed = 0
  for (const [index, check] of checks.entries()) {
    const started = Date.now()
    const inv = invocationFor(check, repoRoot)
    const result = spawnSync(inv.command, inv.args, {
      cwd: inv.cwd,
      shell: inv.shell,
      encoding: 'utf8',
      maxBuffer: 256 * 1024 * 1024,
      env: { ...process.env, CI: process.env.CI ?? '1', FORCE_COLOR: '0' },
    })
    const seconds = ((Date.now() - started) / 1000).toFixed(0)
    const output = `${result.stdout ?? ''}${result.stderr ?? ''}${result.error?.message ?? ''}`
    if (result.status === 0) {
      console.log(`ok   ${check.name} (${seconds}s)`)
      continue
    }
    failed++
    const logFile = path.join(logDir, `${index}-${check.name.replace(/[^\w.-]+/g, '_').slice(0, 60)}.log`)
    fs.writeFileSync(logFile, output)
    const tail = output.trimEnd().split('\n').slice(-60).join('\n')
    console.log(`FAIL ${check.name} (${seconds}s) - ${describe(check)}\n${tail}\nfull log: ${logFile}\n`)
  }
  notes.forEach((note) => console.log(`note: ${note}`))
  if (failed === 0) fs.rmSync(logDir, { recursive: true, force: true })
  return failed === 0 ? 0 : 1
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.exit(main(process.argv.slice(2)))
}
