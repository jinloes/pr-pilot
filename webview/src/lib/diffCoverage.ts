/**
 * Reads the coverage trailer the engine appends to a bounded PR diff, which says which changed
 * files the diff omits.
 *
 * Mirrors `core/src/main/java/com/jinloes/prpilot/model/DiffCoverage.java#split` exactly: a header
 * line `[pr-pilot:diff-coverage] omitted=<n> listed=<n> budget=<n> scan=<complete|incomplete>`
 * followed by exactly `listed` lines `[pr-pilot:omitted] <path>`, each ending in `\n`. Parsing is
 * strict and end-anchored, so a lookalike inside diff content is never treated as a trailer. Both
 * parsers are pinned to `core/src/test/resources/diff-coverage/trailer.golden.txt`; change them
 * together.
 */

export interface DiffCoverage {
  /** Changed files the diff omits; a lower bound when `scanComplete` is false. */
  omitted: number
  /** Omitted files named in `paths`. */
  listed: number
  /** Byte budget the diff was bounded to. */
  budget: number
  /** False when the source diff was too large to scan to its end. */
  scanComplete: boolean
  /** Listed omitted paths in original diff order. */
  paths: string[]
}

export interface DiffCoverageSplit {
  /** The diff without its trailer; the whole input when no valid trailer is present. */
  body: string
  /** The declared coverage, or `null` when the diff carries no valid trailer. */
  coverage: DiffCoverage | null
}

export const MAX_LISTED_PATHS = 200

const HEADER_PREFIX = '[pr-pilot:diff-coverage] '
const PATH_PREFIX = '[pr-pilot:omitted] '
const COUNT = '(0|[1-9][0-9]{0,8})'
const HEADER = new RegExp(
  `^\\[pr-pilot:diff-coverage\\] omitted=${COUNT} listed=${COUNT} budget=${COUNT} scan=(complete|incomplete)$`,
)
// Matches Java's Character.isISOControl: C0 controls, DEL, and C1 controls.
// eslint-disable-next-line no-control-regex
const CONTROL_CHARACTER = /[\u0000-\u001f\u007f-\u009f]/

/** Separates a valid trailing coverage block from a diff; anything malformed is left in place. */
export function splitDiffCoverage(diff: string | null | undefined): DiffCoverageSplit {
  const input = diff ?? ''
  const rejected: DiffCoverageSplit = { body: input, coverage: null }
  const start = lastHeaderStart(input)
  if (start < 0 || !input.endsWith('\n')) return rejected
  const lines = input.slice(start, input.length - 1).split('\n')
  const header = HEADER.exec(lines[0])
  if (!header) return rejected
  const omitted = Number(header[1])
  const listed = Number(header[2])
  const budget = Number(header[3])
  const scanComplete = header[4] === 'complete'
  if (
    listed > omitted
    || listed > MAX_LISTED_PATHS
    || (omitted === 0 && scanComplete)
    || lines.length - 1 !== listed
  ) {
    return rejected
  }
  const paths: string[] = []
  for (const line of lines.slice(1)) {
    if (!line.startsWith(PATH_PREFIX)) return rejected
    const path = line.slice(PATH_PREFIX.length)
    if (!path || CONTROL_CHARACTER.test(path)) return rejected
    paths.push(path)
  }
  return { body: input.slice(0, start), coverage: { omitted, listed, budget, scanComplete, paths } }
}

/** The coverage a diff's trailer declares, or `null` when the diff is complete or has no valid trailer. */
export function parseDiffCoverage(diff: string | null | undefined): DiffCoverage | null {
  return splitDiffCoverage(diff).coverage
}

/** Omitted files the trailer counts but does not name. */
export function unlistedCount(coverage: DiffCoverage): number {
  return coverage.omitted - coverage.paths.length
}

/**
 * How many more changed files the chunked (validation) diff includes than the single-pass review
 * diff. A `null` coverage means that diff omits nothing.
 */
export function coverageGain(review: DiffCoverage | null, chunk: DiffCoverage | null): number {
  return Math.max(0, (review?.omitted ?? 0) - (chunk?.omitted ?? 0))
}

/** Formats a byte budget with decimal units: 250000 → "250 KB", 1000000 → "1 MB". */
export function formatBudget(bytes: number): string {
  if (bytes >= 1_000_000) return `${compactNumber(bytes / 1_000_000)} MB`
  if (bytes >= 1_000) return `${compactNumber(bytes / 1_000)} KB`
  return `${bytes} bytes`
}

function compactNumber(value: number): string {
  return String(Number(value.toFixed(1)))
}

function lastHeaderStart(input: string): number {
  let index = input.lastIndexOf(HEADER_PREFIX)
  while (index > 0 && input.charAt(index - 1) !== '\n') {
    index = input.lastIndexOf(HEADER_PREFIX, index - 1)
  }
  return index
}
