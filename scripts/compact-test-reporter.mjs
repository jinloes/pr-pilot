// Compact `node --test` reporter and CLI filter helper so passing runs emit a single summary line
// instead of tens of kilobytes of per-test checkmarks, while failures print full locations,
// assertion diffs, and crash stderr. Pass `--verbose` (or `PR_PILOT_VERBOSE_TESTS=1`) to fall back
// to Node's built-in `spec` reporter.
import path from 'node:path'
import util from 'node:util'
import { fileURLToPath, pathToFileURL } from 'node:url'

const MAX_STDERR_BUFFER_CHARS = 16_000
const REPORTER_URL = import.meta.url

function escapeRegex(literal) {
  return literal.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

function formatError(details, bufferedStderr) {
  const error = details?.error
  const cause = error?.cause ?? error
  if (error?.failureType === 'testCodeFailure' && bufferedStderr?.trim()) {
    return bufferedStderr.trimEnd()
  }
  if (cause instanceof Error) {
    return cause.stack || cause.message || String(cause)
  }
  if (typeof cause === 'string') {
    return bufferedStderr?.trim() ? `${cause}\n${bufferedStderr.trimEnd()}` : cause
  }
  if (cause !== undefined) {
    return util.inspect(cause, { depth: 6, breakLength: 100 })
  }
  return bufferedStderr?.trimEnd() || 'Test failed without error details'
}

function formatLocation(data) {
  if (!data?.file) return ''
  const rel = path.isAbsolute(data.file) ? path.relative(process.cwd(), data.file) : data.file
  const normalized = rel.replaceAll('\\', '/')
  if (data.line && data.column) return `${normalized}:${data.line}:${data.column}`
  if (data.line) return `${normalized}:${data.line}`
  return normalized
}

export function formatTestEventStream(source) {
  return compactReporter(source)
}

export default async function* compactReporter(source) {
  const names = new Map()
  const stderrByFile = new Map()
  const leafFailureFiles = new Set()
  let sawGlobalSummary = false
  let passed = 0
  let failed = 0
  let skipped = 0
  let todo = 0

  for await (const event of source) {
    const { type, data } = event
    if (type === 'test:start' && data?.testId !== undefined) {
      const parent = data.parentId ? names.get(data.parentId) : ''
      names.set(data.testId, parent ? `${parent} > ${data.name}` : data.name)
    } else if (type === 'test:stderr' && data?.file && data?.message) {
      const key = path.resolve(data.entryFile ?? data.file)
      const prev = stderrByFile.get(key) ?? ''
      if (prev.length < MAX_STDERR_BUFFER_CHARS) {
        stderrByFile.set(key, (prev + data.message).slice(0, MAX_STDERR_BUFFER_CHARS))
      }
    } else if (type === 'test:pass') {
      if (data?.details?.type !== 'suite') {
        if (data?.skip) skipped++
        else if (data?.todo) todo++
        else passed++
      }
    } else if (type === 'test:fail') {
      const isSuiteRollup =
        data?.details?.type === 'suite' || data?.details?.error?.failureType === 'subtestsFailed'
      const fileKey = data?.file ? path.resolve(data.file) : ''
      if (isSuiteRollup && (!fileKey || leafFailureFiles.has(fileKey))) {
        continue
      }
      if (fileKey) leafFailureFiles.add(fileKey)
      failed++
      const label = (data?.testId !== undefined && names.get(data.testId)) || data?.name || 'unknown test'
      const loc = formatLocation(data)
      const header = loc ? `FAIL ${label} (${loc})` : `FAIL ${label}`
      const detail = formatError(data?.details, fileKey ? stderrByFile.get(fileKey) : '')
      yield `${header}\n${detail}\n\n`
    } else if (type === 'test:summary' && !data?.file) {
      sawGlobalSummary = true
      const counts = data.counts ?? {}
      const passCount = counts.passed ?? passed
      const failCount = counts.failed ?? failed
      const skipCount = counts.skipped ?? skipped
      const todoCount = counts.todo ?? todo
      const cancelledCount = counts.cancelled ?? 0
      const secs = ((data.duration_ms ?? 0) / 1000).toFixed(1)
      const extras = []
      if (skipCount > 0) extras.push(`${skipCount} skipped`)
      if (todoCount > 0) extras.push(`${todoCount} todo`)
      if (cancelledCount > 0) extras.push(`${cancelledCount} cancelled`)
      const suffix = extras.length > 0 ? `, ${extras.join(', ')}` : ''
      if (data.success && failCount === 0 && cancelledCount === 0) {
        yield `ok   ${passCount} passed${suffix} (${secs}s)\n`
      } else {
        yield `FAIL ${failCount} failed, ${passCount} passed${suffix} (${secs}s)\n`
      }
    }
  }

  if (!sawGlobalSummary) {
    if (failed === 0) {
      yield `ok   ${passed} passed\n`
    } else {
      yield `FAIL ${failed} failed, ${passed} passed\n`
    }
  }
}

/**
 * Parses wrapper CLI arguments (`--verbose`/`-v`, `--test-name-pattern`/`-t`, and positional file
 * or test-name substrings) into `node --test` arguments. Positional terms narrow `files` when any
 * candidate path matches (case-insensitive substring), and otherwise become `--test-name-pattern`
 * regexes so both `npm run test:unit -- wireCatalog` and `npm run test:unit -- resolveNvmrcNode`
 * work naturally.
 */
export function buildNodeTestInvocation({
  files,
  argv = [],
  env = process.env,
  reporterPath = REPORTER_URL,
}) {
  let verbose = env.PR_PILOT_VERBOSE_TESTS === '1'
  const namePatterns = []
  const positional = []

  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i]
    if (arg === '--') continue
    if (arg === '--verbose' || arg === '-v') {
      verbose = true
    } else if (arg === '--test-name-pattern' || arg === '-t') {
      const next = argv[++i]
      if (!next) throw new Error(`Missing pattern after ${arg}`)
      namePatterns.push(next)
    } else if (arg.startsWith('--test-name-pattern=')) {
      const value = arg.slice('--test-name-pattern='.length)
      if (!value) throw new Error('Missing pattern in --test-name-pattern=<pattern>')
      namePatterns.push(value)
    } else if (arg === '--only') {
      namePatterns.push('--test-only')
    } else if (arg.startsWith('-')) {
      throw new Error(`Unknown test runner flag: ${arg}`)
    } else {
      positional.push(arg)
    }
  }

  let selectedFiles = [...files]
  const unmatchedTerms = []
  if (positional.length > 0) {
    const matched = new Set()
    for (const term of positional) {
      const needle = term.replaceAll('\\', '/').toLowerCase()
      const hits = files.filter((file) => file.replaceAll('\\', '/').toLowerCase().includes(needle))
      if (hits.length > 0) {
        hits.forEach((hit) => matched.add(hit))
      } else {
        unmatchedTerms.push(escapeRegex(term))
      }
    }
    if (matched.size > 0) {
      selectedFiles = files.filter((file) => matched.has(file))
    }
  }

  const nodeArgs = ['--test']
  if (verbose) {
    nodeArgs.push('--test-reporter=spec')
  } else {
    const specifier =
      reporterPath.startsWith('file://') || !path.isAbsolute(reporterPath)
        ? reporterPath
        : pathToFileURL(reporterPath).href
    nodeArgs.push(`--test-reporter=${specifier}`)
  }
  for (const pattern of namePatterns) {
    if (pattern === '--test-only') nodeArgs.push('--test-only')
    else nodeArgs.push(`--test-name-pattern=${pattern}`)
  }
  if (unmatchedTerms.length > 0) {
    nodeArgs.push(`--test-name-pattern=${unmatchedTerms.join('|')}`)
  }
  nodeArgs.push(...selectedFiles)

  return { verbose, selectedFiles, nodeArgs }
}
