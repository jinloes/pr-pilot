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

/** Copy of `comment` without a suggested change; the replacement only fits the line it targeted. */
export function withoutSuggestion(comment: LineComment): LineComment {
  const copy = { ...comment }
  delete copy.suggestedChange
  return copy
}

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

/** Valid new-file lines of a single hunk, plus the span used to decide which hunk owns a line. */
interface HunkLines {
  lines: Set<number>
  start: number
  end: number
}

/**
 * Index: file path → one entry per hunk. Stores the displayed path (`file.newPath`, falling back
 * to `oldPath` for deletions) so the suffix match in lookup works for either form the model emits.
 *
 * Kept per-hunk rather than as one flat set per file: a flat set lets a comment snap from the end
 * of one hunk into the start of the next, which anchors it to unrelated code.
 */
function buildLineIndex(files: FileData[]): Map<string, HunkLines[]> {
  const idx = new Map<string, HunkLines[]>()
  for (const file of files) {
    const path = file.newPath !== '/dev/null' ? file.newPath : file.oldPath
    if (!path) continue
    const hunks: HunkLines[] = []
    for (const hunk of file.hunks) {
      const lines = new Set<number>()
      for (const change of hunk.changes) {
        const n = newLineOf(change)
        if (n !== undefined) lines.add(n)
      }
      if (lines.size === 0) continue
      let start = Number.MAX_SAFE_INTEGER
      let end = -1
      for (const n of lines) {
        if (n < start) start = n
        if (n > end) end = n
      }
      hunks.push({ lines, start, end })
    }
    idx.set(path, hunks)
  }
  return idx
}

function findValidLinesForFile(idx: Map<string, HunkLines[]>, file: string): HunkLines[] | null {
  if (idx.has(file)) return idx.get(file)!
  const matches: HunkLines[][] = []
  for (const [key, val] of idx) {
    // Require a '/' boundary so e.g. "Action.java" does not match "UserAction.java".
    if (file === key || file.endsWith('/' + key) || key.endsWith('/' + file)) matches.push(val)
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
 * Resolves `target` to a real diff line within a single hunk.
 *
 * Returns the line unchanged when some hunk already contains it. Otherwise snaps only when
 * exactly one hunk has a line within `SNAP_RADIUS`. Two qualifying hunks means the target sits in
 * the gap between them and belongs to neither, so it is left unanchored rather than attached to
 * whichever happens to be marginally nearer — a misattributed comment is worse than no comment.
 */
function resolveLine(
  target: number,
  hunks: HunkLines[],
): { line: number; snapped: boolean } | null {
  for (const hunk of hunks) {
    if (hunk.lines.has(target)) return { line: target, snapped: false }
  }

  let candidate: number | null = null
  for (const hunk of hunks) {
    if (target < hunk.start - SNAP_RADIUS || target > hunk.end + SNAP_RADIUS) continue
    const near = nearestLine(target, hunk.lines)
    if (!near || near.distance > SNAP_RADIUS) continue
    if (candidate !== null && candidate !== near.line) return null
    candidate = near.line
  }
  return candidate === null ? null : { line: candidate, snapped: true }
}

/**
 * Partitions `comments` into inline-eligible (`adjusted`) and orphan (`orphans`) sets
 * based on whether each comment's (file, line) corresponds to a real position in `diff`.
 *
 * - In-hunk lines pass through unchanged.
 * - Lines within ±SNAP_RADIUS of a line in exactly one hunk are silently moved to that line.
 *   Counting drift is the most common model failure mode and a small snap reliably fixes it.
 *   Snapping is scoped to a single hunk, so a comment sitting in the gap between two hunks is
 *   orphaned rather than pulled into whichever is nearer.
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
    const hunks = findValidLinesForFile(index, c.file)
    if (!hunks) {
      orphans.push(c)
      continue
    }
    const resolved = resolveLine(c.line, hunks)
    if (!resolved) {
      orphans.push(c)
      continue
    }
    if (resolved.snapped) {
      adjusted.push(withoutSuggestion({ ...c, line: resolved.line }))
      snappedCount++
    } else {
      adjusted.push(c)
    }
  }

  return { adjusted, orphans, snappedCount }
}

export const SNAP_RADIUS_FOR_TESTS = SNAP_RADIUS
