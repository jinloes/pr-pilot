// Runs only the checks affected by the current change and prints failures, not full logs.
//
//   node scripts/verify.mjs                 # files changed vs origin/main plus uncommitted/untracked
//   node scripts/verify.mjs --since HEAD    # uncommitted/untracked changes since HEAD (or any git ref)
//   node scripts/verify.mjs --uncommitted   # shorthand for --since HEAD
//   node scripts/verify.mjs --jobs 2        # cap concurrent process lanes (or PR_PILOT_VERIFY_JOBS)
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
import { gradleWrapperInvocation, resolveNpmInvocation } from './portable-process.mjs'

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
  'github-engine/src/main/java/com/jinloes/prpilot/sidecar/pr/DraftReviewMutationService.java',
]

const ROOT_BUILD_FILES = new Set([
  'build.gradle',
  'settings.gradle',
  'gradle.properties',
  'gradlew',
  'gradlew.bat',
])

const DOC_ONLY = /\.(md|mmd)$/i
const SIZED_CODE_FILE = /\.(java|tsx?|m?js|cjs|css|gradle)$/i
const TEST_PATH = /(?:^|\/)(?:src\/test|test)\/|\.(?:test|spec)\.[^/]+$|Test\.java$/

export const TEST_FILE_MAX_BYTES = 20_000
export const SOURCE_FILE_MAX_BYTES = 32_000

/**
 * Flags touched source/test files whose LF-normalized UTF-8 size exceeds single-read budgets so
 * agents split growing files before they require multi-pass reads. Kept separate from pure
 * `planChecks` because synthetic test paths do not exist on disk.
 */
export function largeFileNotes(repoRoot, changedPaths) {
  const seen = new Set()
  const notes = []
  for (const rawPath of changedPaths) {
    const file = normalize(rawPath)
    if (!file || seen.has(file) || isAgentDoc(file) || !SIZED_CODE_FILE.test(file)) continue
    seen.add(file)
    const fullPath = path.join(repoRoot, file)
    if (!fs.statSync(fullPath, { throwIfNoEntry: false })?.isFile()) continue
    const lfBytes = Buffer.byteLength(fs.readFileSync(fullPath, 'utf8').replaceAll('\r\n', '\n'), 'utf8')
    const isTest = TEST_PATH.test(file)
    if (isTest && lfBytes > TEST_FILE_MAX_BYTES) {
      notes.push(
        `Large test file: ${file} is ${lfBytes} LF bytes (target <= ${TEST_FILE_MAX_BYTES}); ` +
          'consider splitting by area so agents can read it in one pass.',
      )
    } else if (!isTest && lfBytes > SOURCE_FILE_MAX_BYTES) {
      notes.push(
        `Large source file: ${file} is ${lfBytes} LF bytes (target <= ${SOURCE_FILE_MAX_BYTES}); ` +
          'consider extracting focused collaborators if growing.',
      )
    }
  }
  return notes
}

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

function webviewChecks(includeA11y = true) {
  // Pair static checks in one lane and runtime suites in another so Vitest and Playwright never
  // contend with each other. When Gradle (`jvm`) is also in the plan, Playwright (`a11y`) waits
  // for `jvm` to finish (`afterLane: 'jvm'`) so Chromium and JUnit subprocess tests do not starve
  // CPU or trigger cancellation-timer flakes; webview-only runs have no `jvm` lane and start
  // immediately.
  const checks = [
    { name: 'webview lint', cwd: 'webview', npm: ['run', 'lint'], lane: 'webview-static' },
    {
      name: 'webview typecheck',
      cwd: 'webview',
      npm: ['exec', '--', 'tsc', '--noEmit'],
      lane: 'webview-static',
    },
    { name: 'webview unit tests', cwd: 'webview', npm: ['run', 'test:unit'], lane: 'webview-tests' },
  ]
  if (includeA11y) {
    checks.push({
      name: 'webview a11y tests',
      cwd: 'webview',
      npm: ['run', 'test:a11y'],
      lane: 'webview-a11y',
      afterLane: ['gradle', 'webview-tests'],
    })
  }
  return checks
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
  let webviewA11y = false
  let extension = false
  let visual = false
  const notes = []

  const everything = options.all || files.some((file) =>
    ROOT_BUILD_FILES.has(file) || file.startsWith('gradle/') || file.startsWith('.github/workflows/'))

  if (everything) {
    JVM_ORDER.forEach((module) => jvmModules.add(module))
    webview = true
    webviewA11y = true
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
      if (DOC_ONLY.test(file)) continue
      webview = true
      const isUnitTestOnly = /^webview\/src\/.*\.test\.tsx?$/.test(file)
      if (!isUnitTestOnly) webviewA11y = true
      if (/^webview\/(src|visual)\//.test(file) && !isUnitTestOnly) visual = true
    } else if (top === 'vscode-extension') {
      if (DOC_ONLY.test(file)) continue
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
  if (webview) checks.push(...webviewChecks(webviewA11y))
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

export function changedFiles(repoRoot, baseOrOptions = 'origin/main', gitRunner = git) {
  const opts =
    typeof baseOrOptions === 'string'
      ? { base: baseOrOptions, uncommittedOnly: false }
      : { base: baseOrOptions?.base ?? 'origin/main', uncommittedOnly: Boolean(baseOrOptions?.uncommittedOnly) }
  const committedDiff = () => {
    if (opts.uncommittedOnly || opts.base === 'HEAD') return ''
    const mergeBase = gitRunner(['merge-base', opts.base, 'HEAD'], repoRoot).trim()
    return mergeBase ? gitRunner(['diff', '--name-only', mergeBase, 'HEAD'], repoRoot) : ''
  }
  const lines = [
    committedDiff(),
    gitRunner(['diff', '--name-only', 'HEAD'], repoRoot),
    gitRunner(['ls-files', '--others', '--exclude-standard'], repoRoot),
  ].join('\n')
  return [...new Set(lines.split('\n').map((line) => line.trim()).filter(Boolean))]
}

export function parseVerifyArgs(argv, env = process.env) {
  let all = false
  let dryRun = false
  let uncommittedOnly = false
  let since = 'origin/main'
  let jobs = env.PR_PILOT_VERIFY_JOBS ? Number.parseInt(env.PR_PILOT_VERIFY_JOBS, 10) : MAX_PARALLEL_LANES
  if (!Number.isInteger(jobs) || jobs < 1) {
    throw new Error(`Invalid PR_PILOT_VERIFY_JOBS: ${env.PR_PILOT_VERIFY_JOBS}`)
  }
  const files = []
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i]
    if (arg === '--all') {
      all = true
    } else if (arg === '--dry-run') {
      dryRun = true
    } else if (arg === '--uncommitted') {
      uncommittedOnly = true
      since = 'HEAD'
    } else if (arg === '--since') {
      const value = argv[++i]
      if (!value || value.startsWith('--')) throw new Error('Missing git ref after --since')
      since = value
      uncommittedOnly = value === 'HEAD'
    } else if (arg.startsWith('--since=')) {
      const value = arg.slice('--since='.length).trim()
      if (!value) throw new Error('Missing git ref in --since=<ref>')
      since = value
      uncommittedOnly = value === 'HEAD'
    } else if (arg === '--jobs') {
      const raw = argv[++i]
      const parsed = Number.parseInt(raw ?? '', 10)
      if (!Number.isInteger(parsed) || parsed < 1) throw new Error(`Invalid --jobs value: ${raw ?? ''}`)
      jobs = parsed
    } else if (arg.startsWith('--jobs=')) {
      const raw = arg.slice('--jobs='.length)
      const parsed = Number.parseInt(raw, 10)
      if (!Number.isInteger(parsed) || parsed < 1) throw new Error(`Invalid --jobs value: ${raw}`)
      jobs = parsed
    } else if (arg.startsWith('--')) {
      throw new Error(`Unknown option: ${arg}`)
    } else {
      files.push(arg)
    }
  }
  return { all, dryRun, uncommittedOnly, since, jobs, files }
}

function invocationFor(check, repoRoot) {
  if (check.gradle) {
    return { ...gradleWrapperInvocation(check.gradle, { repoRoot }), cwd: repoRoot, shell: false }
  }
  const cwd = path.join(repoRoot, check.cwd)
  return { ...resolveNpmInvocation(check.npm), cwd }
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
        windowsVerbatimArguments: Boolean(inv.windowsVerbatimArguments),
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
const MAX_PARALLEL_LANES = 3

export function nodeVersionSupported(version) {
  const [major, minor] = version.replace(/^v/, '').split('.').map(Number)
  return major > MIN_NODE[0] || (major === MIN_NODE[0] && minor >= MIN_NODE[1])
}

/**
 * Resolves a Node >= 26 binary from `.nvmrc` / nvm / volta / fnm when a non-interactive shell
 * starts on an older default Node, so agents and IDE tasks need no manual PATH setup.
 */
export function resolveNvmrcNode(repoRoot, options = {}) {
  const platform = options.platform ?? process.platform
  const isWin = platform === 'win32'
  const paths = isWin ? path.win32 : path.posix
  const home = options.homedir ?? os.homedir()
  const env = options.env ?? process.env
  const exists = options.existsSync ?? fs.existsSync
  const readdir = options.readdirSync ?? fs.readdirSync
  const readFile = options.readFileSync ?? fs.readFileSync

  const exe = isWin ? ['node.exe'] : ['bin', 'node']
  const voltaHome =
    env.VOLTA_HOME ||
    (isWin
      ? paths.join(env.LOCALAPPDATA || paths.join(home, 'AppData', 'Local'), 'Volta')
      : paths.join(home, '.volta'))
  const voltaNodeDir = paths.join(voltaHome, 'tools', 'image', 'node')

  const nvmDir = isWin
    ? env.NVM_HOME || paths.join(env.APPDATA || paths.join(home, 'AppData', 'Roaming'), 'nvm')
    : paths.join(env.NVM_DIR || paths.join(home, '.nvm'), 'versions', 'node')

  const fnmDir = paths.join(
    env.FNM_DIR ||
      (isWin
        ? paths.join(env.APPDATA || paths.join(home, 'AppData', 'Roaming'), 'fnm')
        : paths.join(home, '.local', 'share', 'fnm')),
    'node-versions',
  )

  const candidates = []
  const nvmrcPath = path.join(repoRoot, '.nvmrc')
  if (exists(nvmrcPath)) {
    const pinned = readFile(nvmrcPath, 'utf8').trim().replace(/^v/, '')
    if (pinned && nodeVersionSupported(pinned)) {
      candidates.push(paths.join(voltaNodeDir, pinned, ...exe))
      candidates.push(paths.join(voltaNodeDir, `v${pinned}`, ...exe))
      candidates.push(paths.join(nvmDir, `v${pinned}`, ...exe))
      if (isWin) candidates.push(paths.join(nvmDir, pinned, ...exe))
      candidates.push(paths.join(fnmDir, `v${pinned}`, 'installation', ...exe))
    }
  }

  const installedBins = []
  const collectVersions = (dir, toBin) => {
    if (!exists(dir)) return
    for (const entry of readdir(dir)) {
      const clean = entry.replace(/^v/, '')
      if (/^\d+\.\d+\.\d+$/.test(clean) && nodeVersionSupported(clean)) {
        installedBins.push({ version: clean, bin: toBin(entry) })
      }
    }
  }
  collectVersions(voltaNodeDir, (entry) => paths.join(voltaNodeDir, entry, ...exe))
  collectVersions(nvmDir, (entry) => paths.join(nvmDir, entry, ...exe))
  collectVersions(fnmDir, (entry) => paths.join(fnmDir, entry, 'installation', ...exe))

  installedBins.sort((a, b) => b.version.localeCompare(a.version, undefined, { numeric: true }))
  for (const item of installedBins) {
    candidates.push(item.bin)
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

export async function executeChecks(checks, runner, onComplete, options = {}) {
  const maxLanes = Math.max(1, options.maxParallelLanes ?? MAX_PARALLEL_LANES)
  const lanes = []
  const byLane = new Map()
  for (const [index, check] of checks.entries()) {
    const key = check.lane ?? `check-${index}`
    if (!byLane.has(key)) {
      const deps = new Set()
      const entry = { key, deps, items: [], started: false, completed: false }
      byLane.set(key, entry)
      lanes.push(entry)
    }
    const entry = byLane.get(key)
    const rawDeps = Array.isArray(check.afterLane)
      ? check.afterLane
      : check.afterLane
        ? [check.afterLane]
        : []
    for (const dep of rawDeps) {
      if (dep !== key) entry.deps.add(dep)
    }
    entry.items.push({ index, check })
  }
  // Ignore dependencies on lanes not present in this plan (for example `jvm` on webview-only edits).
  for (const lane of lanes) {
    for (const dep of [...lane.deps]) {
      if (!byLane.has(dep)) lane.deps.delete(dep)
    }
  }

  let activeLanes = 0
  await new Promise((resolve, reject) => {
    const pump = () => {
      if (lanes.every((lane) => lane.completed)) {
        resolve()
        return
      }
      for (const lane of lanes) {
        if (activeLanes >= maxLanes) break
        if (lane.started) continue
        const ready = [...lane.deps].every((dep) => byLane.get(dep)?.completed)
        if (!ready) continue
        lane.started = true
        activeLanes++
        ;(async () => {
          for (const { index, check } of lane.items) {
            const started = Date.now()
            const result = await runner(check)
            onComplete({
              index,
              check,
              result,
              seconds: ((Date.now() - started) / 1000).toFixed(0),
            })
          }
          lane.completed = true
          activeLanes--
          pump()
        })().catch(reject)
      }
    }
    pump()
  })
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
  let parsed
  try {
    parsed = parseVerifyArgs(argv)
  } catch (error) {
    console.error(String(error?.message ?? error))
    return 1
  }
  const files =
    parsed.files.length > 0
      ? parsed.files
      : changedFiles(repoRoot, { base: parsed.since, uncommittedOnly: parsed.uncommittedOnly })
  const { checks, notes } = planChecks(files, { all: parsed.all })
  notes.push(...largeFileNotes(repoRoot, files))

  if (checks.length === 0) {
    console.log(files.length === 0 ? 'No changes detected.' : 'No local checks cover these changes.')
    notes.forEach((note) => console.log(`note: ${note}`))
    return 0
  }
  if (parsed.dryRun) {
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
    { maxParallelLanes: parsed.jobs },
  )

  notes.forEach((note) => console.log(`note: ${note}`))
  if (failed === 0) fs.rmSync(logDir, { recursive: true, force: true })
  return failed === 0 ? 0 : 1
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2)).then((code) => process.exit(code))
}
