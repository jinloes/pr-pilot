import { validateComments } from '@/lib/validateComments'
import type { LineComment, ReviewResult } from '../bridge/types'

export type ReviewQualityAction = 'removeUnanchored' | 'addMissingRationale' | 'downgradeHighRisk'

export interface ReviewQualityIssue {
  id: 'hallucinationRisk' | 'outdatedAnchors' | 'missingRationale'
  title: string
  severity: 'high' | 'medium' | 'low'
  count: number
  description: string
}

export interface ReviewQualityReport {
  score: number
  issues: ReviewQualityIssue[]
  suggestions: ReviewQualityAction[]
  orphanComments: LineComment[]
  riskyComments: LineComment[]
  missingRationaleComments: LineComment[]
}

interface DiffFileStat {
  path: string
  changedLines: number
}

function parseDiffFileStats(diff: string): DiffFileStat[] {
  const rows = diff.split(/\r?\n/)
  const stats = new Map<string, number>()
  let currentFile = ''

  for (const row of rows) {
    if (row.startsWith('+++ b/')) {
      currentFile = row.slice('+++ b/'.length).trim()
      if (!stats.has(currentFile)) stats.set(currentFile, 0)
      continue
    }
    if (!currentFile) continue
    if (row.startsWith('+') && !row.startsWith('+++')) {
      stats.set(currentFile, (stats.get(currentFile) ?? 0) + 1)
    }
  }

  return [...stats.entries()].map(([path, changedLines]) => ({ path, changedLines }))
}

function isHighRiskLowEvidence(comment: LineComment, changedFiles: Set<string>): boolean {
  if (!changedFiles.has(comment.file)) return true
  const severity = comment.severity ?? 'minor'
  const confidence = comment.confidence ?? 'medium'
  const shortIssueBody = comment.type === 'issue' && comment.body.trim().length < 35
  return (
    (severity === 'blocker' || severity === 'major')
    && (confidence === 'low' || shortIssueBody)
  )
}

function issueWeight(issue: ReviewQualityIssue): number {
  if (issue.id === 'outdatedAnchors') return issue.count * 12
  if (issue.id === 'hallucinationRisk') return issue.count * 10
  return issue.count * 6
}

export function runReviewQualityCheck(result: ReviewResult, validationDiff: string): ReviewQualityReport {
  const { orphans } = validateComments(validationDiff, result.lineComments)
  const changedFiles = new Set(parseDiffFileStats(validationDiff).map((f) => f.path))

  const riskyComments = result.lineComments.filter((comment) => isHighRiskLowEvidence(comment, changedFiles))
  const missingRationaleComments = result.lineComments.filter((comment) => {
    if (comment.type === 'note') return false
    const confidence = comment.confidence ?? 'medium'
    if (confidence === 'low') return false
    return !comment.rationale?.trim()
  })

  const issues: ReviewQualityIssue[] = []
  if (riskyComments.length > 0) {
    issues.push({
      id: 'hallucinationRisk',
      title: 'Potential hallucination risk',
      severity: 'high',
      count: riskyComments.length,
      description: 'High-severity comments with weak evidence (low confidence, short body, or file mismatch).',
    })
  }
  if (orphans.length > 0) {
    issues.push({
      id: 'outdatedAnchors',
      title: 'Outdated line anchors',
      severity: 'high',
      count: orphans.length,
      description: 'Comments no longer map to valid PR hunks and will not be attached inline.',
    })
  }
  if (missingRationaleComments.length > 0) {
    issues.push({
      id: 'missingRationale',
      title: 'Missing rationale',
      severity: 'medium',
      count: missingRationaleComments.length,
      description: 'Medium/high-confidence comments should include rationale for reviewer trust.',
    })
  }

  const penalty = issues.reduce((acc, issue) => acc + issueWeight(issue), 0)
  const score = Math.max(0, 100 - penalty)
  const suggestions: ReviewQualityAction[] = []
  if (orphans.length > 0) suggestions.push('removeUnanchored')
  if (missingRationaleComments.length > 0) suggestions.push('addMissingRationale')
  if (riskyComments.length > 0) suggestions.push('downgradeHighRisk')

  return {
    score,
    issues,
    suggestions,
    orphanComments: orphans,
    riskyComments,
    missingRationaleComments,
  }
}

function matchesComment(a: LineComment, b: LineComment): boolean {
  return a.file === b.file && a.line === b.line && a.body === b.body && a.type === b.type
}

export function applyReviewQualityRepairs(
  result: ReviewResult,
  report: ReviewQualityReport,
  repairs: ReviewQualityAction[],
): ReviewResult {
  const repairSet = new Set(repairs)
  const nextComments = result.lineComments.map((comment) => ({ ...comment }))

  let repaired = nextComments

  if (repairSet.has('removeUnanchored')) {
    repaired = repaired.filter((comment) => !report.orphanComments.some((orphan) => matchesComment(orphan, comment)))
  }

  if (repairSet.has('addMissingRationale')) {
    repaired = repaired.map((comment) => {
      const needsRationale = report.missingRationaleComments.some((target) => matchesComment(target, comment))
      if (!needsRationale) return comment
      return {
        ...comment,
        rationale:
          comment.rationale?.trim()
          || `Evidence needs verification in ${comment.file}:${comment.line}.`,
      }
    })
  }

  if (repairSet.has('downgradeHighRisk')) {
    repaired = repaired.map((comment) => {
      const risky = report.riskyComments.some((target) => matchesComment(target, comment))
      if (!risky) return comment
      return {
        ...comment,
        type: comment.type === 'issue' ? 'suggestion' : comment.type,
        severity:
          comment.severity === 'blocker' || comment.severity === 'major'
            ? 'minor'
            : comment.severity,
      }
    })
  }

  return { ...result, lineComments: repaired }
}

export interface DiffBatch {
  label: string
  files: string[]
}

export function buildDiffBatches(validationDiff: string, maxFilesPerBatch = 6): DiffBatch[] {
  const stats = parseDiffFileStats(validationDiff)
  if (stats.length === 0) return []

  const sorted = [...stats].sort((a, b) => b.changedLines - a.changedLines)
  const batches: DiffBatch[] = []
  for (let i = 0; i < sorted.length; i += maxFilesPerBatch) {
    const slice = sorted.slice(i, i + maxFilesPerBatch)
    const files = slice.map((item) => item.path)
    const changedLines = slice.reduce((sum, item) => sum + item.changedLines, 0)
    batches.push({
      label: `Batch ${batches.length + 1} (${files.length} files, ${changedLines} changed lines)`,
      files,
    })
  }
  return batches
}

export function estimateFileConfidence(comments: LineComment[], files: string[]): number {
  const fileSet = new Set(files)
  const matched = comments.filter((comment) => fileSet.has(comment.file))
  if (matched.length === 0) return 0.55

  const score = matched.reduce((sum, comment) => {
    switch (comment.confidence) {
      case 'high':
        return sum + 1
      case 'medium':
        return sum + 0.66
      case 'low':
        return sum + 0.33
      default:
        return sum + 0.5
    }
  }, 0)

  return Math.min(1, Math.max(0, score / matched.length))
}

