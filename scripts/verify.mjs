// Runs only the checks affected by the current change and prints failures, not full logs.
//
//   node scripts/verify.mjs                 # files changed vs origin/main plus uncommitted/untracked
//   node scripts/verify.mjs path/to/File.java webview/src/App.tsx
//   node scripts/verify.mjs --all           # every required check
//   node scripts/verify.mjs --dry-run       # print the plan without running it
import { spawn, spawnSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { checkAgentDocs, isAgentDoc } from './agent-docs.mjs'
import { mirrorNotes } from './host-mirrors.mjs'
import { checkPlaywrightImages, IMAGE_SOURCES, LOCKFILE } from './playwright-image.mjs'
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

// Checks that run inside this process rather than as a child command.
const IN_PROCESS = {
  docs: {
    describe: 'agent-doc links, decision index, and sizes (scripts/agent-docs.mjs)',
    run: (repoRoot) => checkAgentDocs(repoRoot),
  },
  playwrightImage: {
    describe: `Playwright image tags and digests match ${LOCKFILE} (scripts/playwright-image.mjs)`,
    run: (repoRoot) => ({ errors: checkPlaywrightImages(repoRoot), warnings: [] }),
  },
}

const PLAYWRIGHT_IMAGE_INPUTS = [...IMAGE_SOURCES, LOCKFILE, 'webview/package.json']

function isPlaywrightImageInput(file) {
  return PLAYWRIGHT_IMAGE_INPUTS.includes(file) || file.startsWith('scripts/playwright-image')
}

function webviewChecks() {
  return [
    { name: 'webview lint', cwd: 'webview', npm: ['run', 'lint'], lane: 'webview-lint' },
    {
      name: 'webview typecheck',
      cwd: 'webview',
      npm: ['exec', '--', 'tsc', '--noEmit'],
      lane: 'webview-typecheck',
    },
    { name: 'webview unit tests', cwd: 'webview', npm: ['run', 'test:unit'], lane: 'webview-unit' },
    { name: 'webview a11y tests', cwd: 'webview', npm: ['run', 'test:a11y'], lane: 'webview-a11y' },
  ]
}

function extensionChecks() {
  // run-unit-tests.mjs writes and cleans dist-test/, so keep extension steps ordered in one lane.
  return [
    { name: 'vscode-extension lint', cwd: 'vscode-extension', npm: ['run', 'lint'], lane: 'extension' },
    {
      name: 'vscode-extension typecheck',
      cwd: 'vscode-extension',
      npm: ['exec', '--', 'tsc', '--noEmit'],
      lane: 'extension',
    },
    {
      name: 'vscode-extension unit tests',
      cwd: 'vscode-extension',
      npm: ['run', 'test:unit'],
      lane: 'extension',
    },
  ]
}

function normalize(file) {
  return file.replaceAll('\\', '/').replace(/^\.\//, '')
}

/**
 * Maps changed repository-relative paths to the smallest set of checks that covers them.
 * Returns `{ checks, notes }`; checks are ordered in-process consistency checks first, then JVM,
 * then webview, then extension. Notes include reminders for hand-mirrored logic that no test keeps
 * in sync and for CI files that only CI can run.
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
  if (everything || files.some(isAgentDoc)) checks.push({ name: 'agent docs', inProcess: 'docs' })
  if (everything || files.some(isPlaywrightImageInput)) {
    checks.push({ name: 'playwright image pins', inProcess: 'playwrightImage' })
  }
  if (jvmModules.size > 0) {
    const tasks = JVM_ORDER.filter((module) => jvmModules.has(module)).map((m) => `:${m}:check`)
    checks.push({
      name: `gradle ${tasks.join(' ')}`,
      gradle: ['--console=plain', '--continue', ...tasks],
      lane: 'gradle',
    })
  }
  if (webview) checks.push(...webviewChecks())
  if (extension) checks.push(...extensionChecks())
  notes.push(...mirrorNotes(files))
  if (files.some((file) => file.startsWith('.github/'))) {
    notes.push(
      'CI config changed: lint with actionlint, shellcheck, and zizmor (see "Workflow lint" in docs/architecture/ci.md); CI runs all three.',
    )
  }
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
  if (check.inProcess) return IN_PROCESS[check.inProcess].describe
  return check.gradle ? `./gradlew ${check.gradle.join(' ')}` : `(cd ${check.cwd} && npm ${check.npm.join(' ')})`
}

function runInProcessCheck(check, repoRoot) {
  try {
    const { errors, warnings } = IN_PROCESS[check.inProcess].run(repoRoot)
    return { ok: errors.length === 0, output: errors.join('\n'), warnings }
  } catch (error) {
    return { ok: false, output: String(error?.stack ?? error), warnings: [] }
  }
}

function runProcessCheck(check, repoRoot) {
  const inv = invocationFor(check, repoRoot)
  return new Promise((resolve) => {
    const chunks = []
    let child
    try {
      child = spawn(inv.command, inv.args, {
        cwd: inv.cwd,
        shell: inv.shell,
        stdio: ['ignore', 'pipe', 'pipe'],
        env: { ...process.env, CI: process.env.CI ?? '1', FORCE_COLOR: '0' },
      })
    } catch (error) {
      resolve({ ok: false, output: String(error?.message ?? error), warnings: [] })
      return
    }
    child.stdout?.on('data', (chunk) => chunks.push(chunk))
    child.stderr?.on('data', (chunk) => chunks.push(chunk))
    child.on('error', (error) => {
      chunks.push(Buffer.from(String(error?.message ?? error)))
      resolve({ ok: false, output: Buffer.concat(chunks).toString('utf8'), warnings: [] })
    })
    child.on('close', (code) => {
      resolve({ ok: code === 0, output: Buffer.concat(chunks).toString('utf8'), warnings: [] })
    })
  })
}

export const MIN_NODE = [26, 0]
const MAX_PARALLEL_LANES = 4

export function nodeVersionSupported(version) {
  const [major, minor] = version.replace(/^v/, '').split('.').map(Number)
  return major > MIN_NODE[0] || (major === MIN_NODE[0] && minor >= MIN_NODE[1])
}

/**
 * Resolves a Node >= 26 binary from `.nvmrc` / nvm / volta when a non-interactive shell starts
 * on an older default Node, so agents and IDE tasks need no manual PATH setup.
 */
export function resolveNvmrcNode(repoRoot, options = {}) {
  const platform = options.platform ?? process.platform
  if (platform === 'win32') return null
  const home = options.homedir ?? os.homedir()
  const env = options.env ?? process.env
  const exists = options.existsSync ?? fs.existsSync
  const readdir = options.readdirSync ?? fs.readdirSync
  const readFile = options.readFileSync ?? fs.readFileSync

  const nvmDir = env.NVM_DIR || path.join(home, '.nvm')
  const versionsDir = path.join(nvmDir, 'versions', 'node')
  const candidates = []

  const nvmrcPath = path.join(repoRoot, '.nvmrc')
  if (exists(nvmrcPath)) {
    const pinned = readFile(nvmrcPath, 'utf8').trim().replace(/^v/, '')
    if (pinned && nodeVersionSupported(pinned)) {
      candidates.push(path.join(versionsDir, `v${pinned}`, 'bin', 'node'))
    }
  }
  if (exists(versionsDir)) {
    const installed = readdir(versionsDir)
      .filter((entry) => /^v\d+\.\d+\.\d+$/.test(entry) && nodeVersionSupported(entry))
      .sort((a, b) => b.localeCompare(a, undefined, { numeric: true }))
    for (const version of installed) {
      candidates.push(path.join(versionsDir, version, 'bin', 'node'))
    }
  }
  for (const candidate of candidates) {
    if (exists(candidate)) return candidate
  }
  return null
}

function maybeReexecWithSupportedNode(repoRoot, argv) {
  if (process.env.PR_PILOT_VERIFY_REEXEC === '1') return null
  const candidate = resolveNvmrcNode(repoRoot)
  if (!candidate) return null
  const binDir = path.dirname(candidate)
  const env = {
    ...process.env,
    PR_PILOT_VERIFY_REEXEC: '1',
    PATH: `${binDir}${path.delimiter}${process.env.PATH ?? ''}`,
  }
  delete env.npm_execpath
  const result = spawnSync(candidate, [fileURLToPath(import.meta.url), ...argv], {
    stdio: 'inherit',
    env,
    shell: false,
  })
  return result.status ?? 1
}

export async function executeChecks(checks, runner, onComplete) {
  const lanes = []
  const byLane = new Map()
  for (const [index, check] of checks.entries()) {
    const key = check.lane ?? `check-${index}`
    if (!byLane.has(key)) {
      const group = []
      byLane.set(key, group)
      lanes.push(group)
    }
    byLane.get(key).push({ index, check })
  }

  let nextLane = 0
  async function worker() {
    while (nextLane < lanes.length) {
      const lane = lanes[nextLane++]
      for (const { index, check } of lane) {
        const started = Date.now()
        const result = await runner(check)
        onComplete({ index, check, result, seconds: ((Date.now() - started) / 1000).toFixed(0) })
      }
    }
  }

  const concurrency = Math.min(MAX_PARALLEL_LANES, lanes.length)
  await Promise.all(Array.from({ length: concurrency }, () => worker()))
}

async function main(argv) {
  const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
  if (!nodeVersionSupported(process.version)) {
    const reexecCode = maybeReexecWithSupportedNode(repoRoot, argv)
    if (reexecCode !== null) return reexecCode
    console.error(
      `Node ${process.version} is too old; webview tooling needs >= ${MIN_NODE.join('.')} (see .nvmrc).`,
    )
    return 1
  }
  const flags = new Set(argv.filter((arg) => arg.startsWith('--')))
  const explicit = argv.filter((arg) => !arg.startsWith('--'))
  const files = explicit.length > 0 ? explicit : changedFiles(repoRoot)
  const { checks, notes } = planChecks(files, { all: flags.has('--all') })

  if (checks.length === 0) {
    console.log(files.length === 0 ? 'No changes detected.' : 'No local checks cover these changes.')
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
  const record = ({ index, check, result, seconds }) => {
    notes.push(...result.warnings)
    if (result.ok) {
      console.log(`ok   ${check.name} (${seconds}s)`)
      return
    }
    failed++
    const logFile = path.join(logDir, `${index}-${check.name.replace(/[^\w.-]+/g, '_').slice(0, 60)}.log`)
    fs.writeFileSync(logFile, result.output)
    const tail = result.output.trimEnd().split('\n').slice(-60).join('\n')
    console.log(`FAIL ${check.name} (${seconds}s) - ${describe(check)}\n${tail}\nfull log: ${logFile}\n`)
  }

  const inProcessChecks = checks
    .map((check, index) => ({ check, index }))
    .filter(({ check }) => Boolean(check.inProcess))
  const processChecks = checks.filter((check) => !check.inProcess)

  for (const { check, index } of inProcessChecks) {
    const started = Date.now()
    const result = runInProcessCheck(check, repoRoot)
    record({ index, check, result, seconds: ((Date.now() - started) / 1000).toFixed(0) })
  }
  await executeChecks(
    processChecks,
    (check) => runProcessCheck(check, repoRoot),
    ({ index, check, result, seconds }) =>
      record({ index: index + inProcessChecks.length, check, result, seconds }),
  )

  notes.forEach((note) => console.log(`note: ${note}`))
  if (failed === 0) fs.rmSync(logDir, { recursive: true, force: true })
  return failed === 0 ? 0 : 1
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2)).then((code) => process.exit(code))
}
