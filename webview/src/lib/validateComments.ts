import { parseDiff } from 'react-diff-view'
import type { ChangeData, FileData } from 'react-diff-view'
import type { LineComment } from '../bridge/types'

export interface ValidationResult {
  /** Comments that will be sent inline. May be the original or snapped to a nearby hunk line. */
  adjusted: LineComment[]
  /** Comments whose line is too far from any hunk in the file — must go in the body section. */
  orphans: LineComment[]
  /** Number of comments whose `line` was changed by a snap (≤ SNAP_RADIUS). */
  snappedCount: number
}

// Maximum distance (in new-file line numbers) we'll silently move a comment so it
// lands on a real diff line. Above this distance we treat the comment as unanchored
// rather than guess — the prompt warns "a misattributed comment is worse than no comment".
const SNAP_RADIUS = 3

function safeParse(diff: string): FileData[] {
  if (!diff) return []
  try {
    return parseDiff(diff)
  } catch {
    return []
  }
}

function newLineOf(change: ChangeData): number | undefined {
  if (change.type === 'insert') return change.lineNumber
  if (change.type === 'normal') return change.newLineNumber
  return undefined
}

/**
 * Index: file path → set of new-file line numbers that are valid positions in the diff.
 * Stores both the displayed path (`file.newPath`) and the `b/`-prefixed path so the
 * suffix match in lookup works for either form Claude might emit.
 */
function buildLineIndex(files: FileData[]): Map<string, Set<number>> {
  const idx = new Map<string, Set<number>>()
  for (const file of files) {
    const path = file.newPath !== '/dev/null' ? file.newPath : file.oldPath
    if (!path) continue
    const lines = new Set<number>()
    for (const hunk of file.hunks) {
      for (const change of hunk.changes) {
        const n = newLineOf(change)
        if (n !== undefined) lines.add(n)
      }
    }
    idx.set(path, lines)
  }
  return idx
}

function findValidLinesForFile(idx: Map<string, Set<number>>, file: string): Set<number> | null {
  if (idx.has(file)) return idx.get(file)!
  const matches: Set<number>[] = []
  for (const [key, val] of idx) {
    if (file.endsWith(key) || key.endsWith(file)) matches.push(val)
  }
  if (matches.length === 1) return matches[0]
  return null
}

function nearestLine(target: number, lines: Set<number>): { line: number; distance: number } | null {
  if (lines.size === 0) return null
  let best = -1
  let bestDist = Number.MAX_SAFE_INTEGER
  for (const candidate of lines) {
    const d = Math.abs(candidate - target)
    if (d < bestDist) {
      best = candidate
      bestDist = d
    }
  }
  return best < 0 ? null : { line: best, distance: bestDist }
}

/**
 * Partitions `comments` into inline-eligible (`adjusted`) and orphan (`orphans`) sets
 * based on whether each comment's (file, line) corresponds to a real position in `diff`.
 *
 * - In-hunk lines pass through unchanged.
 * - Lines within ±SNAP_RADIUS of the nearest hunk line are silently moved to that line.
 *   Counting drift is the most common model failure mode and a small snap reliably fixes it.
 * - Anything farther becomes an orphan — the host appends it to the body in a
 *   "Comments not attached inline" section instead of attempting an invalid inline POST.
 *
 * Comments with no file or non-positive line are not validated here — `buildCommentArray`
 * on the host already filters those.
 */
export function validateComments(diff: string, comments: LineComment[]): ValidationResult {
  const files = safeParse(diff)
  if (files.length === 0) {
    const adjusted: LineComment[] = []
    const orphans: LineComment[] = []
    for (const c of comments) {
      if (!c.file || c.line <= 0 || !c.body) adjusted.push(c)
      else orphans.push(c)
    }
    return { adjusted, orphans, snappedCount: 0 }
  }
  const index = buildLineIndex(files)
  const adjusted: LineComment[] = []
  const orphans: LineComment[] = []
  let snappedCount = 0

  for (const c of comments) {
    if (!c.file || c.line <= 0 || !c.body) {
      adjusted.push(c)
      continue
    }
    const lines = findValidLinesForFile(index, c.file)
    if (!lines) {
      orphans.push(c)
      continue
    }
    if (lines.has(c.line)) {
      adjusted.push(c)
      continue
    }
    const near = nearestLine(c.line, lines)
    if (near && near.distance <= SNAP_RADIUS) {
      adjusted.push({ ...c, line: near.line })
      snappedCount++
    } else {
      orphans.push(c)
    }
  }

  return { adjusted, orphans, snappedCount }
}

export const SNAP_RADIUS_FOR_TESTS = SNAP_RADIUS
