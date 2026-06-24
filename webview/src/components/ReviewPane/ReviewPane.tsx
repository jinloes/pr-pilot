import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  onHostMessage,
  sendToHost,
  type PR,
  type ReviewResult,
  type LineComment,
} from '../../bridge/types'
import { validateComments } from '@/lib/validateComments'
import {
  applyReviewQualityRepairs,
  buildDiffBatches,
  estimateFileConfidence,
  runReviewQualityCheck,
  type DiffBatch,
  type ReviewQualityAction,
  type ReviewQualityReport,
} from '@/lib/reviewQuality'
import {
  AlertTriangle,
  Check,
  CheckCircle2,
  ChevronDown,
  ChevronUp,
  CloudUpload,
  ExternalLink,
  GitMerge,
  Loader2,
  MessageSquare,
  RefreshCw,
  RotateCcw,
  Settings2,
  Trash2,
  X,
  XCircle,
} from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Alert, AlertDescription } from '@/components/ui/alert'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '@/components/ui/alert-dialog'
import {
  ContextMenu,
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuLabel,
  ContextMenuSeparator,
  ContextMenuTrigger,
} from '@/components/ui/context-menu'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip'
import { cn } from '@/lib/utils'
import { ChatPane } from '../ChatPane'
import { DiffViewer } from '../DiffViewer'
import { ReviewDisplay } from '../ReviewDisplay'

const CHAT_HEIGHT_KEY = 'claude-reviews:chat-height'
const MIN_CHAT_HEIGHT = 100
const MAX_CHAT_HEIGHT = 600
const DEFAULT_CHAT_HEIGHT = 240

function loadChatHeight(): number {
  const saved = Number(localStorage.getItem(CHAT_HEIGHT_KEY))
  return saved >= MIN_CHAT_HEIGHT && saved <= MAX_CHAT_HEIGHT ? saved : DEFAULT_CHAT_HEIGHT
}

interface Props {
  pr: PR | null
  onDirtyStateChange?: (dirty: boolean) => void
}

type Verdict = 'APPROVE' | 'REQUEST_CHANGES' | 'COMMENT'

type PaneState =
  | { kind: 'idle' }
  | { kind: 'draftLoading' }
  | { kind: 'noDraft'; diff?: string; validationDiff?: string }
  | { kind: 'authError'; message: string; diff?: string; validationDiff?: string }
  | {
      kind: 'draftPresent'
      result: ReviewResult
      reviewId: string
      staleCommits: boolean
      importedFromGitHub: boolean
      diff?: string
      validationDiff?: string
      generationElapsedSec?: number
    }
  | {
      kind: 'generating'
      messages: string[]
      chunks: Array<{ kind: 'text' | 'thinking'; content: string }>
      startedAtMs: number
    }
  | { kind: 'reviewUnsaved'; result: ReviewResult; diff: string; validationDiff: string; generationElapsedSec?: number }
  | { kind: 'merged'; status?: string }
  | { kind: 'submitted' }
  | { kind: 'error'; message: string }
  | { kind: 'saveError'; message: string; result: ReviewResult | null; diff: string; validationDiff: string }
  | { kind: 'submitError'; message: string; result: ReviewResult | null; diff: string; validationDiff: string }

interface ChunkedProgress {
  running: boolean
  currentBatch: number
  totalBatches: number
  activeLabel: string
  completed: Array<{
    label: string
    confidence: number
    comments: number
    fileConfidence: Array<{ file: string; confidence: number }>
  }>
}

interface DiffPreflight {
  fileCount: number
  changedLines: number
}

interface ChunkSession {
  prKey: string
  startedAtMs: number
  batches: DiffBatch[]
  nextBatchIndex: number
  aggregated: Array<{ result: ReviewResult; files: string[] }>
  completed: Array<{
    label: string
    confidence: number
    comments: number
    fileConfidence: Array<{ file: string; confidence: number }>
  }>
  diff: string
  validationDiff: string
  focusAreas: string
  customInstructions: string
}

function sortedComments(comments: LineComment[]): LineComment[] {
  return [...comments].sort((a, b) => a.file.localeCompare(b.file) || a.line - b.line)
}

function withSortedComments(result: ReviewResult): ReviewResult {
  return { ...result, lineComments: sortedComments(result.lineComments) }
}

/**
 * Runs `validateComments` once at intake and bakes the snapped line numbers back into
 * `result.lineComments` so the diff viewer renders comments at their corrected positions.
 * Orphans pass through unchanged; subsequent partitions are computed lazily at render time.
 */
function withValidatedComments(result: ReviewResult, diff: string): ReviewResult {
  const { adjusted, orphans } = validateComments(diff, result.lineComments)
  return { ...result, lineComments: [...adjusted, ...orphans] }
}

function diffOf(state: PaneState): string {
  if (state.kind === 'reviewUnsaved') return state.diff
  if (state.kind === 'draftPresent') return state.diff ?? ''
  if (state.kind === 'noDraft' || state.kind === 'authError') return state.diff ?? ''
  if (state.kind === 'saveError' || state.kind === 'submitError') return state.diff
  return ''
}

function validationDiffOf(state: PaneState): string {
  if (state.kind === 'reviewUnsaved') return state.validationDiff
  if (state.kind === 'draftPresent') return state.validationDiff ?? state.diff ?? ''
  if (state.kind === 'noDraft' || state.kind === 'authError') {
    return state.validationDiff ?? state.diff ?? ''
  }
  if (state.kind === 'saveError' || state.kind === 'submitError') return state.validationDiff
  return diffOf(state)
}

function resultOf(state: PaneState): ReviewResult | null {
  if (state.kind === 'draftPresent' || state.kind === 'reviewUnsaved') return state.result
  if (state.kind === 'saveError' || state.kind === 'submitError') return state.result
  return null
}

function mutateComments(
  prev: PaneState,
  kinds: PaneState['kind'][],
  fn: (comments: LineComment[]) => LineComment[],
): PaneState {
  if (!kinds.includes(prev.kind)) return prev
  const s = prev as { kind: string; result: ReviewResult; diff?: string; reviewId?: string; staleCommits?: boolean }
  const updated = { ...s.result, lineComments: fn(s.result.lineComments) }
  return { ...prev, result: updated } as PaneState
}

const VERDICT_COLOR: Record<ReviewResult['verdict'], string> = {
  APPROVE: 'text-status-approve',
  REQUEST_CHANGES: 'text-status-changes',
  COMMENT: 'text-status-comment',
}

const VERDICT_LABEL: Record<ReviewResult['verdict'], string> = {
  APPROVE: 'Approve',
  REQUEST_CHANGES: 'Request Changes',
  COMMENT: 'Comment',
}

function prKey(pr: Pick<PR, 'owner' | 'repo' | 'number'>): string {
  return `${pr.owner}/${pr.repo}#${pr.number}`
}

function chunkInstructions(batch: DiffBatch, index: number, total: number): string {
  return [
    `Chunked review mode is enabled: process batch ${index + 1}/${total}.`,
    'Only emit findings for the following files in this batch:',
    ...batch.files.map((file) => `- ${file}`),
    'If a potential finding is outside this file set, ignore it for this batch.',
  ].join('\n')
}

function summarizeDiffPreflight(diff: string): DiffPreflight | null {
  if (!diff.trim()) return null
  const rows = diff.split(/\r?\n/)
  const files = new Set<string>()
  let changedLines = 0
  for (const row of rows) {
    if (row.startsWith('+++ b/')) {
      files.add(row.slice('+++ b/'.length).trim())
      continue
    }
    if ((row.startsWith('+') && !row.startsWith('+++')) || (row.startsWith('-') && !row.startsWith('---'))) {
      changedLines += 1
    }
  }
  return { fileCount: files.size, changedLines }
}

function chunkRecommendation(preflight: DiffPreflight | null, truncated: boolean): {
  recommendChunked: boolean
  reason: string
} {
  if (truncated) {
    return { recommendChunked: true, reason: 'Diff context is truncated.' }
  }
  if (!preflight) {
    return { recommendChunked: false, reason: 'Recommendation appears once the diff is loaded.' }
  }
  if (preflight.fileCount >= 8) {
    return { recommendChunked: true, reason: 'Many changed files.' }
  }
  if (preflight.changedLines >= 300) {
    return { recommendChunked: true, reason: 'Large changed-line count.' }
  }
  return { recommendChunked: false, reason: 'Single-pass review is likely sufficient.' }
}

function mergeChunkResults(results: ReviewResult[]): ReviewResult {
  const lineCommentSeen = new Set<string>()
  const lineComments: LineComment[] = []
  const summaryParts: string[] = []
  let verdict: ReviewResult['verdict'] = 'APPROVE'

  for (const result of results) {
    if (result.verdict === 'REQUEST_CHANGES') verdict = 'REQUEST_CHANGES'
    else if (verdict === 'APPROVE' && result.verdict === 'COMMENT') verdict = 'COMMENT'

    if (result.summary.trim()) summaryParts.push(result.summary.trim())

    for (const comment of result.lineComments) {
      const key = `${comment.file}|${comment.line}|${comment.type}|${comment.body}`
      if (lineCommentSeen.has(key)) continue
      lineCommentSeen.add(key)
      lineComments.push(comment)
    }
  }

  const combinedSummary = summaryParts.length > 0
    ? `## Overview\nChunked review completed across ${results.length} batch${results.length === 1 ? '' : 'es'}.\n\n${summaryParts.join('\n\n')}`.slice(0, 790)
    : '## Overview\nChunked review completed.'

  return { summary: combinedSummary, lineComments, verdict }
}

export function ReviewPane({ pr, onDirtyStateChange }: Props) {
  const [state, setState] = useState<PaneState>({ kind: 'idle' })
  const [focusAreasOverride, setFocusAreasOverride] = useState('')
  const [customInstructionsOverride, setCustomInstructionsOverride] = useState('')
  const [saving, setSaving] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [focusedCommentIdx, setFocusedCommentIdx] = useState(0)
  const [chatVisible, setChatVisible] = useState(false)
  const [selectedContext, setSelectedContext] = useState('')
  const [pendingChatMessage, setPendingChatMessage] = useState<{ q: string; ctx: string; id: number } | null>(null)
  const [chunkedMode, setChunkedMode] = useState(false)
  const [chunkedProgress, setChunkedProgress] = useState<ChunkedProgress | null>(null)
  const [qualityCheckedAt, setQualityCheckedAt] = useState<number | null>(null)
  const [chatHeight, setChatHeight] = useState(loadChatHeight)
  const chatHeightRef = useRef(chatHeight)
  const chatDragRef = useRef<{ startY: number; startHeight: number } | null>(null)
  const pendingSubmit = useRef<{ verdict: Verdict; comment: string } | null>(null)
  const currentPrRef = useRef(pr)
  const chunkSessionRef = useRef<ChunkSession | null>(null)

  useEffect(() => {
    currentPrRef.current = pr
  }, [pr])

  useEffect(() => {
    setState({ kind: pr ? 'draftLoading' : 'idle' })
    setFocusAreasOverride('')
    setCustomInstructionsOverride('')
    pendingSubmit.current = null
    setSaving(false)
    setSubmitting(false)
    setDeleting(false)
    setFocusedCommentIdx(0)
    setChatVisible(false)
    setSelectedContext('')
    setQualityCheckedAt(null)
    setChunkedProgress(null)
    chunkSessionRef.current = null
  }, [pr])

  useEffect(() => {
    const dirty = state.kind === 'reviewUnsaved' || state.kind === 'saveError'
    onDirtyStateChange?.(dirty)
  }, [state, onDirtyStateChange])

  useEffect(() => {
    const cleanup = onHostMessage((msg) => {
      const activePr = currentPrRef.current
      if ('prKey' in msg && msg.prKey && (!activePr || msg.prKey !== prKey(activePr))) return

      switch (msg.type) {
        case 'draftLoading':
          setState({ kind: 'draftLoading' })
          break

        case 'draftLoaded':
          if (msg.prState === 'MERGED') {
            setState({ kind: 'merged', status: msg.status })
          } else if (msg.prState === 'DRAFT_PRESENT' && msg.result) {
            const diff = msg.diff ?? msg.validationDiff ?? ''
            const validationDiff = msg.validationDiff ?? diff
            const result = withSortedComments(withValidatedComments(msg.result, validationDiff))
            setFocusedCommentIdx(0)
            setState({
              kind: 'draftPresent',
              result,
              reviewId: msg.reviewId ?? '',
              staleCommits: msg.staleCommits ?? false,
              importedFromGitHub: msg.importedFromGitHub ?? false,
              diff,
              validationDiff,
            })
          } else {
            const status = msg.status ?? ''
            const diff = msg.diff ?? msg.validationDiff ?? ''
            const validationDiff = msg.validationDiff ?? diff
            setState(
              status
                ? { kind: 'authError', message: status, diff, validationDiff }
                : { kind: 'noDraft', diff, validationDiff },
            )
          }
          break

        case 'reviewGenerating':
          if (chunkSessionRef.current) {
            setState((prev) => ({
              kind: 'generating',
              messages: prev.kind === 'generating' ? [...prev.messages, msg.message] : [msg.message],
              chunks: prev.kind === 'generating' ? prev.chunks : [],
              startedAtMs: prev.kind === 'generating' ? prev.startedAtMs : Date.now(),
            }))
            break
          }
          setState((prev) => ({
            kind: 'generating',
            messages: prev.kind === 'generating' ? [...prev.messages, msg.message] : [msg.message],
            chunks: prev.kind === 'generating' ? prev.chunks : [],
            startedAtMs: prev.kind === 'generating' ? prev.startedAtMs : Date.now(),
          }))
          break

        case 'reviewChunk':
          if (chunkSessionRef.current) {
            setState((prev) => {
              if (prev.kind !== 'generating') return prev
              return { ...prev, chunks: [...prev.chunks, { kind: msg.kind, content: msg.chunk }] }
            })
            break
          }
          setState((prev) => {
            if (prev.kind !== 'generating') return prev
            return { ...prev, chunks: [...prev.chunks, { kind: msg.kind, content: msg.chunk }] }
          })
          break

        case 'reviewResult': {
          const diff = msg.diff ?? msg.validationDiff ?? ''
          const validationDiff = msg.validationDiff ?? diff
          const result = withSortedComments(withValidatedComments(msg.result, validationDiff))

          const chunkSession = chunkSessionRef.current
          if (chunkSession) {
            const batchIdx = chunkSession.nextBatchIndex
            const batch = chunkSession.batches[batchIdx]
            if (!batch) {
              chunkSessionRef.current = null
              break
            }

            chunkSession.aggregated.push({ result, files: batch.files })
            const confidence = estimateFileConfidence(result.lineComments, batch.files)
            const fileConfidence = batch.files.map((file) => ({
              file,
              confidence: estimateFileConfidence(result.lineComments, [file]),
            }))
            const completed = [
              ...chunkSession.completed,
              { label: batch.label, confidence, comments: result.lineComments.length, fileConfidence },
            ]
            chunkSession.completed = completed

            chunkSession.nextBatchIndex += 1
            if (chunkSession.nextBatchIndex < chunkSession.batches.length) {
              const nextBatch = chunkSession.batches[chunkSession.nextBatchIndex]
              setChunkedProgress({
                running: true,
                currentBatch: chunkSession.nextBatchIndex + 1,
                totalBatches: chunkSession.batches.length,
                activeLabel: nextBatch.label,
                completed,
              })
              setState({
                kind: 'generating',
                messages: [`Starting ${nextBatch.label}…`],
                chunks: [],
                startedAtMs: chunkSession.startedAtMs,
              })
              const activePr = currentPrRef.current
              if (!activePr || prKey(activePr) !== chunkSession.prKey) {
                chunkSessionRef.current = null
                setState({ kind: 'error', message: 'Chunked review cancelled because the selected PR changed.' })
                break
              }
              sendToHost({
                type: 'generateReview',
                number: activePr.number,
                owner: activePr.owner,
                repo: activePr.repo,
                focusAreas: chunkSession.focusAreas || undefined,
                customInstructions: [
                  chunkSession.customInstructions,
                  chunkInstructions(nextBatch, chunkSession.nextBatchIndex, chunkSession.batches.length),
                ]
                  .filter((value) => value.trim().length > 0)
                  .join('\n\n'),
              })
              break
            }

            const merged = mergeChunkResults(chunkSession.aggregated.map((item) => item.result))
            const elapsedSec = Math.max(0, Math.round((Date.now() - chunkSession.startedAtMs) / 1000))
            const finalized = withSortedComments(withValidatedComments(merged, chunkSession.validationDiff))
            chunkSessionRef.current = null
            setChunkedProgress({
              running: false,
              currentBatch: chunkSession.batches.length,
              totalBatches: chunkSession.batches.length,
              activeLabel: 'Completed',
              completed,
            })
            setFocusedCommentIdx(0)
            setState({
              kind: 'reviewUnsaved',
              result: finalized,
              diff: chunkSession.diff || chunkSession.validationDiff,
              validationDiff: chunkSession.validationDiff,
              generationElapsedSec: elapsedSec,
            })
            toast.success(`Chunked review completed across ${chunkSession.batches.length} batches.`)
            break
          }

          setFocusedCommentIdx(0)
          setState((prev) => {
            const elapsedSec =
              prev.kind === 'generating'
                ? Math.max(0, Math.round((Date.now() - prev.startedAtMs) / 1000))
                : undefined
            return {
              kind: 'reviewUnsaved',
              result,
              diff: diff || validationDiff,
              validationDiff,
              generationElapsedSec: elapsedSec,
            }
          })
          break
        }

        case 'reviewError':
          if (chunkSessionRef.current) {
            const session = chunkSessionRef.current
            chunkSessionRef.current = null
            setChunkedProgress((prev) =>
              prev
                ? {
                    ...prev,
                    running: false,
                    activeLabel: `Failed at batch ${session.nextBatchIndex + 1}`,
                  }
                : prev,
            )
            setState({
              kind: 'error',
              message: `Chunked review failed at batch ${session.nextBatchIndex + 1}/${session.batches.length}: ${msg.message}`,
            })
            break
          }
          setState({ kind: 'error', message: msg.message })
          break

        case 'draftSaved': {
          setSaving(false)
          if (msg.commentsDropped) {
            toast.warning('Some comments were dropped', {
              description: 'Outdated line references were removed when saving to GitHub.',
            })
          }
          setState((prev) => {
            if (prev.kind !== 'reviewUnsaved' && prev.kind !== 'draftPresent') return prev
            return {
              kind: 'draftPresent',
              result: prev.result,
              reviewId: msg.reviewId,
              staleCommits: false,
              importedFromGitHub: false,
              diff: prev.diff,
              validationDiff: prev.validationDiff,
              generationElapsedSec: prev.generationElapsedSec,
            }
          })
          const pending = pendingSubmit.current
          const submitPr = currentPrRef.current
          if (pending && submitPr) {
            pendingSubmit.current = null
            setSubmitting(true)
            sendToHost({
              type: 'submitReview',
              number: submitPr.number,
              owner: submitPr.owner,
              repo: submitPr.repo,
              verdict: pending.verdict,
              comment: pending.comment,
            })
          }
          break
        }

        case 'draftSaveError':
          setSaving(false)
          pendingSubmit.current = null
          setState((prev) => ({
            kind: 'saveError',
            message: msg.message,
            result: prev.kind === 'reviewUnsaved' || prev.kind === 'draftPresent' ? prev.result : null,
            diff: prev.kind === 'reviewUnsaved' ? prev.diff : prev.kind === 'draftPresent' ? (prev.diff ?? '') : '',
            validationDiff:
              prev.kind === 'reviewUnsaved'
                ? prev.validationDiff
                : prev.kind === 'draftPresent'
                  ? (prev.validationDiff ?? prev.diff ?? '')
                  : '',
          }))
          break

        case 'reviewSubmitted':
          setSubmitting(false)
          setState({ kind: 'submitted' })
          break

        case 'reviewSubmitError':
          setSubmitting(false)
          setState((prev) => ({
            kind: 'submitError',
            message: msg.message,
            result: prev.kind === 'draftPresent' || prev.kind === 'reviewUnsaved' ? prev.result : null,
            diff: prev.kind === 'draftPresent' ? (prev.diff ?? '') : prev.kind === 'reviewUnsaved' ? prev.diff : '',
            validationDiff:
              prev.kind === 'draftPresent'
                ? (prev.validationDiff ?? prev.diff ?? '')
                : prev.kind === 'reviewUnsaved'
                  ? prev.validationDiff
                  : '',
          }))
          break

        case 'draftDeleted':
          setDeleting(false)
          setState({ kind: 'noDraft' })
          break

        case 'draftDeleteError':
          setDeleting(false)
          setState({ kind: 'error', message: msg.message })
          break

        default:
          break
      }
    })
    return cleanup
  }, [])

  const showChat = Boolean(pr)

  useEffect(() => {
    if (!pr) {
      setSelectedContext('')
      return
    }

    function handleMouseUp(e: MouseEvent) {
      if ((e.target as HTMLElement).closest?.('.chat-pane__input')) return
      const text = window.getSelection()?.toString().trim() ?? ''
      if (text) setSelectedContext(text)
    }

    document.addEventListener('mouseup', handleMouseUp)
    return () => document.removeEventListener('mouseup', handleMouseUp)
  }, [pr])

  const handleChatResizeMove = useCallback((e: MouseEvent) => {
    if (!chatDragRef.current) return
    const delta = chatDragRef.current.startY - e.clientY
    const newHeight = Math.max(MIN_CHAT_HEIGHT, Math.min(MAX_CHAT_HEIGHT, chatDragRef.current.startHeight + delta))
    chatHeightRef.current = newHeight
    setChatHeight(newHeight)
  }, [])

  function handleChatResizeUp() {
    chatDragRef.current = null
    document.body.style.cursor = ''
    document.body.style.userSelect = ''
    localStorage.setItem(CHAT_HEIGHT_KEY, String(chatHeightRef.current))
    document.removeEventListener('mousemove', handleChatResizeMove)
    document.removeEventListener('mouseup', handleChatResizeUp)
  }

  // Hooks must run in the same order on every render — keep all hooks above the early return.
  const result = resultOf(state)
  const diff = diffOf(state)
  const validationDiff = validationDiffOf(state)
  const partition = useMemo(
    () => validateComments(validationDiff, result?.lineComments ?? []),
    [validationDiff, result?.lineComments],
  )
  const qualityReport = useMemo<ReviewQualityReport | null>(
    () => (result ? runReviewQualityCheck(result, validationDiff) : null),
    [result, validationDiff],
  )
  const preflight = useMemo(() => summarizeDiffPreflight(validationDiff), [validationDiff])
  const recommendation = useMemo(
    () => chunkRecommendation(preflight, isDiffTruncated(validationDiff) || isDiffTruncated(diff)),
    [preflight, validationDiff, diff],
  )

  function handleRunQualityCheck() {
    setQualityCheckedAt(Date.now())
  }

  function applyQualityRepair(action: ReviewQualityAction) {
    if (!qualityReport) return
    setState((prev) =>
      mutateComments(prev, ['draftPresent', 'reviewUnsaved'], () => {
        const baseResult = resultOf(prev)
        if (!baseResult) return []
        return applyReviewQualityRepairs(baseResult, qualityReport, [action]).lineComments
      }),
    )
    setQualityCheckedAt(Date.now())
  }

  if (!pr) {
    return (
      <div className="flex h-full items-center justify-center bg-background">
        <span className="text-sm text-muted-foreground italic">← select a pull request</span>
      </div>
    )
  }

  const currentPr = pr

  function sendChunkBatchRequest(session: ChunkSession, batchIndex: number) {
    const batch = session.batches[batchIndex]
    if (!batch) return
    setChunkedProgress({
      running: true,
      currentBatch: batchIndex + 1,
      totalBatches: session.batches.length,
      activeLabel: batch.label,
      completed: session.completed,
    })
    setState({
      kind: 'generating',
      messages: [`Starting ${batch.label}…`],
      chunks: [],
      startedAtMs: session.startedAtMs,
    })
    sendToHost({
      type: 'generateReview',
      number: currentPr.number,
      owner: currentPr.owner,
      repo: currentPr.repo,
      focusAreas: session.focusAreas || undefined,
      customInstructions: [
        session.customInstructions,
        chunkInstructions(batch, batchIndex, session.batches.length),
      ]
        .filter((value) => value.trim().length > 0)
        .join('\n\n'),
    })
  }

  function handleGenerate() {
    const focusAreas = focusAreasOverride.trim()
    const customInstructions = customInstructionsOverride.trim()

    if (chunkedMode) {
      const sourceDiff = validationDiffOf(state)
      if (!sourceDiff.trim()) {
        toast.error('Chunked mode needs a loaded diff. Reload the PR and try again.')
        return
      }
      const batches = buildDiffBatches(sourceDiff)
      if (batches.length <= 1) {
        toast.info('Chunked mode skipped: this PR has too few changed files for batching.')
      } else {
        const session: ChunkSession = {
          prKey: prKey(currentPr),
          startedAtMs: Date.now(),
          batches,
          nextBatchIndex: 0,
          aggregated: [],
          completed: [],
          diff: diffOf(state) || sourceDiff,
          validationDiff: sourceDiff,
          focusAreas,
          customInstructions,
        }
        chunkSessionRef.current = session
        sendChunkBatchRequest(session, 0)
        return
      }
    }

    chunkSessionRef.current = null
    setChunkedProgress(null)
    setState({ kind: 'generating', messages: ['Starting review…'], chunks: [], startedAtMs: Date.now() })
    sendToHost({
      type: 'generateReview',
      number: currentPr.number,
      owner: currentPr.owner,
      repo: currentPr.repo,
      focusAreas: focusAreas || undefined,
      customInstructions: customInstructions || undefined,
    })
  }

  function handleCancel() {
    sendToHost({ type: 'cancelReview' })
    chunkSessionRef.current = null
    setChunkedProgress((prev) => (prev ? { ...prev, running: false, activeLabel: 'Cancelled' } : null))
    setState({ kind: 'draftLoading' })
    sendToHost({ type: 'selectPR', number: currentPr.number, owner: currentPr.owner, repo: currentPr.repo })
  }

  function handleSave() {
    if (state.kind !== 'reviewUnsaved' && state.kind !== 'draftPresent') return
    setSaving(true)
    sendToHost({
      type: 'saveDraft',
      number: currentPr.number,
      owner: currentPr.owner,
      repo: currentPr.repo,
      result: state.result,
      orphans: orphanComments,
    })
  }

  function handleDelete() {
    setDeleting(true)
    sendToHost({ type: 'deleteDraft', number: currentPr.number, owner: currentPr.owner, repo: currentPr.repo })
  }

  function handleReloadDraft() {
    setState({ kind: 'draftLoading' })
    sendToHost({ type: 'selectPR', number: currentPr.number, owner: currentPr.owner, repo: currentPr.repo })
  }

  function handleSubmit(verdict: Verdict, comment = '') {
    if (state.kind === 'reviewUnsaved') {
      pendingSubmit.current = { verdict, comment }
      setSaving(true)
      sendToHost({
        type: 'saveDraft',
        number: currentPr.number,
        owner: currentPr.owner,
        repo: currentPr.repo,
        result: state.result,
        orphans: orphanComments,
      })
      return
    }
    setSubmitting(true)
    sendToHost({ type: 'submitReview', number: currentPr.number, owner: currentPr.owner, repo: currentPr.repo, verdict, comment })
  }

  function handleVerifyComment(comment: LineComment) {
    const q = `Verify this review comment on ${comment.file} line ${comment.line}:\n\n> ${comment.body}\n\nIs this ${comment.type} actually present in the diff?`
    if (!chatVisible) setChatVisible(true)
    setPendingChatMessage({ q, ctx: '', id: Date.now() })
  }

  function handleChatResizeDown(e: React.MouseEvent) {
    e.preventDefault()
    chatDragRef.current = { startY: e.clientY, startHeight: chatHeight }
    document.body.style.cursor = 'ns-resize'
    document.body.style.userSelect = 'none'
    document.addEventListener('mousemove', handleChatResizeMove)
    document.addEventListener('mouseup', handleChatResizeUp)
  }

  const inlineComments = partition.adjusted
  const orphanComments = partition.orphans
  const commentCount = inlineComments.length
  const orphanCount = orphanComments.length
  const totalCount = commentCount + orphanCount
  const hasReview = state.kind === 'draftPresent' || state.kind === 'reviewUnsaved'
  const contextSummary = chatContextSummary(pr, diff, result, selectedContext)
  const showReviewOverrides = state.kind !== 'draftLoading' && state.kind !== 'generating' && state.kind !== 'merged'

  // DiffViewer indexes comments by position in the array we pass it (`inlineComments`),
  // but our mutators operate on positions in the canonical `result.lineComments`. The
  // inlineComments references are stable (validateComments returns the same object when
  // no snap is needed, and snaps are already baked in at intake), so indexOf is safe.
  function inlineToOriginal(inlineIdx: number): number {
    if (!result) return -1
    const target = inlineComments[inlineIdx]
    return target ? result.lineComments.indexOf(target) : -1
  }

  function mutateAtOriginal(origIdx: number, fn: (c: LineComment) => LineComment | null) {
    if (origIdx < 0) return
    setState((prev) =>
      mutateComments(prev, ['draftPresent', 'reviewUnsaved'], (comments) => {
        const next = comments.slice()
        const updated = fn(next[origIdx])
        if (updated === null) next.splice(origIdx, 1)
        else next[origIdx] = updated
        return next
      }),
    )
  }

  const editCommentHandlers = {
    onEditComment: (idx: number, body: string) => {
      mutateAtOriginal(inlineToOriginal(idx), (c) => ({ ...c, body }))
    },
    onDeleteComment: (idx: number) => {
      setFocusedCommentIdx((f) => (f > 0 && f >= idx ? f - 1 : f))
      mutateAtOriginal(inlineToOriginal(idx), () => null)
    },
    onAddComment: (comment: LineComment) => {
      let newFocusIdx = 0
      setState((prev) => {
        if (prev.kind !== 'draftPresent' && prev.kind !== 'reviewUnsaved') return prev
        newFocusIdx = inlineComments.length
        return { ...prev, result: { ...prev.result, lineComments: [...prev.result.lineComments, comment] } }
      })
      setFocusedCommentIdx(newFocusIdx)
    },
  }

  function orphanToOriginal(orphan: LineComment): number {
    if (!result) return -1
    return result.lineComments.indexOf(orphan)
  }

  const orphanHandlers = {
    onEditOrphan: (orphan: LineComment, body: string) => {
      mutateAtOriginal(orphanToOriginal(orphan), (c) => ({ ...c, body }))
    },
    onDeleteOrphan: (orphan: LineComment) => {
      mutateAtOriginal(orphanToOriginal(orphan), () => null)
    },
  }

  return (
    <TooltipProvider delayDuration={400}>
      <div className="flex flex-col h-full bg-background">
        {/* Header */}
        <div className="shrink-0 px-4 py-2.5 border-b border-border bg-card">
          <div className="flex items-center gap-2 min-w-0">
            <span className="font-mono text-xs text-muted-foreground shrink-0">#{pr.number}</span>
            <span className="text-sm font-medium truncate flex-1" title={pr.title}>{pr.title}</span>

            {commentCount > 0 && (
              <div className="flex items-center gap-0.5 shrink-0">
                <Button
                  variant="ghost"
                  size="sm"
                  className="h-6 w-6 p-0"
                  onClick={() => setFocusedCommentIdx(Math.max(0, focusedCommentIdx - 1))}
                  disabled={focusedCommentIdx <= 0}
                  aria-label="Previous comment"
                >
                  <ChevronUp className="w-3.5 h-3.5" />
                </Button>
                <span className="text-xs text-muted-foreground font-mono px-0.5 tabular-nums">
                  {focusedCommentIdx + 1}/{commentCount}
                </span>
                <Button
                  variant="ghost"
                  size="sm"
                  className="h-6 w-6 p-0"
                  onClick={() => setFocusedCommentIdx(Math.min(commentCount - 1, focusedCommentIdx + 1))}
                  disabled={focusedCommentIdx >= commentCount - 1}
                  aria-label="Next comment"
                >
                  <ChevronDown className="w-3.5 h-3.5" />
                </Button>
              </div>
            )}

            {showChat && (
              <Button
                variant={chatVisible ? 'secondary' : 'ghost'}
                size="sm"
                className="h-6 px-2 text-xs shrink-0 gap-1.5"
                onClick={() => setChatVisible((v) => !v)}
                title={chatVisible ? 'Collapse chat' : 'Open chat'}
              >
                <MessageSquare className="w-3.5 h-3.5" />
                Chat
              </Button>
            )}

            {showChat && selectedContext && !chatVisible && (
              <Button
                variant="secondary"
                size="sm"
                className="h-6 px-2 text-xs shrink-0 gap-1.5"
                onClick={() => setChatVisible(true)}
                title="Open chat with selected text attached"
              >
                <MessageSquare className="w-3.5 h-3.5" />
                Ask selection
              </Button>
            )}

            <Tooltip>
              <TooltipTrigger asChild>
                <Button
                  variant="ghost"
                  size="sm"
                  className="h-6 w-6 p-0 shrink-0 text-muted-foreground"
                  onClick={() => sendToHost({ type: 'openUrl', url: pr.htmlUrl })}
                  aria-label="Open PR on GitHub"
                >
                  <ExternalLink className="w-3.5 h-3.5" />
                </Button>
              </TooltipTrigger>
              <TooltipContent>Open on GitHub</TooltipContent>
            </Tooltip>
          </div>

          {/* Context line: verdict+count when review exists, repo otherwise */}
          <div className="flex items-center gap-1.5 mt-1 text-xs">
            {hasReview && (state as { result: ReviewResult }).result ? (
              <>
                <span className={cn('font-mono font-semibold tracking-wide', VERDICT_COLOR[(state as { result: ReviewResult }).result.verdict])}>
                  {VERDICT_LABEL[(state as { result: ReviewResult }).result.verdict]}
                </span>
                {totalCount > 0 && (
                  <>
                    <span className="text-muted-foreground">·</span>
                    <span className="text-muted-foreground">{totalCount} comment{totalCount !== 1 ? 's' : ''}</span>
                  </>
                )}
                {orphanCount > 0 && (
                  <Tooltip>
                    <TooltipTrigger asChild>
                      <span className="text-status-suggestion font-mono cursor-help">· {orphanCount} unanchored</span>
                    </TooltipTrigger>
                    <TooltipContent>
                      Comment{orphanCount !== 1 ? 's' : ''} GitHub cannot attach inline — line is outside the PR's hunks.
                    </TooltipContent>
                  </Tooltip>
                )}
                {state.kind === 'reviewUnsaved' && (
                  <span className="text-status-suggestion font-mono">· unsaved</span>
                )}
              </>
            ) : (
              <span className="font-mono text-muted-foreground/70">{pr.owner}/{pr.repo}</span>
            )}
          </div>
        </div>

        {/* Body */}
        <ContextMenu>
          <ContextMenuTrigger asChild>
            <div className="flex-1 overflow-y-auto min-h-0">
              {showReviewOverrides && (
                <ReviewOverrides
                  focusAreas={focusAreasOverride}
                  customInstructions={customInstructionsOverride}
                  chunkedMode={chunkedMode}
                  preflight={preflight}
                  recommendation={recommendation}
                  onFocusAreasChange={setFocusAreasOverride}
                  onCustomInstructionsChange={setCustomInstructionsOverride}
                  onChunkedModeChange={setChunkedMode}
                />
              )}
              {result && !qualityCheckedAt && (
                <div className="px-4 pt-3">
                  <QualityCheckHintCard onRunQualityCheck={handleRunQualityCheck} />
                </div>
              )}
              {result && qualityCheckedAt && qualityReport && (
                <div className="px-4 pt-3">
                  <ReviewQualityCheckCard
                    checkedAt={qualityCheckedAt}
                    report={qualityReport}
                    onApplyRepair={applyQualityRepair}
                  />
                </div>
              )}
              {chunkedProgress && (
                <div className="px-4 pt-3">
                  <ChunkedProgressCard progress={chunkedProgress} />
                </div>
              )}
              <PaneContent
                state={state}
                focusedCommentIdx={focusedCommentIdx}
                setFocusedCommentIdx={setFocusedCommentIdx}
                onGenerate={handleGenerate}
                onVerifyComment={hasReview ? handleVerifyComment : undefined}
                editCommentHandlers={editCommentHandlers}
                inlineComments={inlineComments}
                orphanComments={orphanComments}
                onEditOrphan={orphanHandlers.onEditOrphan}
                onDeleteOrphan={orphanHandlers.onDeleteOrphan}
                onReloadDraft={handleReloadDraft}
                onOpenSettings={() => sendToHost({ type: 'openSettings' })}
                onOpenAuthGuide={() => sendToHost({ type: 'openUrl', url: 'https://cli.github.com/manual/gh_auth_login' })}
              />
            </div>
          </ContextMenuTrigger>
          <ContextMenuContent>
            {pr ? (
              selectedContext ? (
                <>
                  <ContextMenuLabel className="text-[10px] font-normal text-muted-foreground max-w-[220px] truncate py-1">
                    "{selectedContext.length > 60 ? selectedContext.slice(0, 60) + '…' : selectedContext}"
                  </ContextMenuLabel>
                  <ContextMenuSeparator />
                  {(['What does this do?', 'Why is this here?', 'Is this correct?', 'Can this be simplified?'] as const).map((q) => (
                    <ContextMenuItem
                      key={q}
                      onSelect={() => {
                        if (!chatVisible) setChatVisible(true)
                        setPendingChatMessage({ q, ctx: selectedContext, id: Date.now() })
                      }}
                      className="gap-2 text-xs"
                    >
                      <MessageSquare className="w-3.5 h-3.5" />
                      {q}
                    </ContextMenuItem>
                  ))}
                </>
              ) : (
                <ContextMenuItem disabled className="gap-2 text-xs opacity-60">
                  <MessageSquare className="w-3.5 h-3.5" />
                  Select text to chat about it
                </ContextMenuItem>
              )
            ) : (
              <ContextMenuItem disabled className="gap-2 text-xs opacity-60">
                <MessageSquare className="w-3.5 h-3.5" />
                Select text to chat about it
              </ContextMenuItem>
            )}
          </ContextMenuContent>
        </ContextMenu>

        {/* Chat panel */}
        {showChat && (
          <div
            className="shrink-0 border-t border-border overflow-hidden transition-[height]"
            style={{ height: chatVisible ? chatHeight : 0 }}
          >
            <ChatPane
              pr={currentPr}
              selectedContext={selectedContext}
              onContextUsed={() => setSelectedContext('')}
              pendingMessage={pendingChatMessage ?? undefined}
              onPendingMessageSent={() => setPendingChatMessage(null)}
              onResizeStart={handleChatResizeDown}
              contextSummary={contextSummary}
            />
          </div>
        )}

        {/* Footer */}
        <ReviewFooter
          state={state}
          saving={saving}
          submitting={submitting}
          deleting={deleting}
          onSave={handleSave}
          onSubmit={handleSubmit}
          onCancel={handleCancel}
          onRegenerate={handleGenerate}
          onDelete={handleDelete}
          onRunQualityCheck={handleRunQualityCheck}
          inlineCommentCount={commentCount}
          orphanCommentCount={orphanCount}
          summary={result?.summary ?? ''}
        />
      </div>
    </TooltipProvider>
  )
}

interface ReviewOverridesProps {
  focusAreas: string
  customInstructions: string
  chunkedMode: boolean
  preflight: DiffPreflight | null
  recommendation: { recommendChunked: boolean; reason: string }
  onFocusAreasChange: (value: string) => void
  onCustomInstructionsChange: (value: string) => void
  onChunkedModeChange: (value: boolean) => void
}

function ReviewOverrides({
  focusAreas,
  customInstructions,
  chunkedMode,
  preflight,
  recommendation,
  onFocusAreasChange,
  onCustomInstructionsChange,
  onChunkedModeChange,
}: ReviewOverridesProps) {
  const hasOverrides = focusAreas.trim().length > 0 || customInstructions.trim().length > 0
  return (
    <div className="px-4 pt-3">
      <div className="rounded border border-border bg-muted/20 px-3 py-2.5">
        <div className="flex items-center justify-between gap-2">
          <p className="text-xs font-medium text-foreground">Per-review instructions (optional)</p>
          {hasOverrides && (
            <Button
              variant="ghost"
              size="sm"
              className="h-6 px-2 text-[11px]"
              onClick={() => {
                onFocusAreasChange('')
                onCustomInstructionsChange('')
              }}
            >
              Clear
            </Button>
          )}
        </div>
        <p className="mt-1 text-[11px] text-muted-foreground">
          Leave blank to use defaults from Settings.
        </p>
        <input
          className="mt-2 w-full rounded border border-border bg-background px-2 py-1 text-xs outline-none focus:ring-1 focus:ring-ring"
          placeholder="Focus areas (e.g. security, performance, tests)"
          value={focusAreas}
          onChange={(e) => onFocusAreasChange(e.target.value)}
        />
        <textarea
          className="mt-2 w-full rounded border border-border bg-background px-2 py-1 text-xs outline-none focus:ring-1 focus:ring-ring resize-y"
          rows={2}
          placeholder="Custom instructions for this review only"
          value={customInstructions}
          onChange={(e) => onCustomInstructionsChange(e.target.value)}
        />
        <label className="mt-2 flex items-center gap-2 text-xs text-muted-foreground">
          <input
            type="checkbox"
            checked={chunkedMode}
            onChange={(e) => onChunkedModeChange(e.target.checked)}
          />
          Use chunked review mode (file batches with per-batch confidence)
        </label>
        <div className="mt-1 pl-6 text-[11px] text-muted-foreground">
          {preflight
            ? `PR size: ${preflight.fileCount} file${preflight.fileCount === 1 ? '' : 's'}, ${preflight.changedLines} changed lines.`
            : 'PR size: loading diff metadata…'}
        </div>
        <div className="mt-1 pl-6 text-[11px]">
          <span className={cn('font-medium', recommendation.recommendChunked ? 'text-status-suggestion' : 'text-status-approve')}>
            {recommendation.recommendChunked ? 'Recommended: Chunked mode.' : 'Recommended: Single-pass mode.'}
          </span>
          <span className="text-muted-foreground"> {recommendation.reason}</span>
        </div>
        <p className="mt-1 pl-6 text-[11px] text-muted-foreground">
          Best for large or high-risk PRs (many files, truncated diff context). Leave off for smaller PRs when you
          want the fastest single-pass review.
        </p>
      </div>
    </div>
  )
}

function QualityCheckHintCard({ onRunQualityCheck }: { onRunQualityCheck: () => void }) {
  return (
    <div className="rounded border border-border bg-muted/20 px-3 py-2.5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="min-w-0">
          <p className="text-xs font-semibold text-foreground">Quality Check not run yet</p>
          <p className="mt-1 text-[11px] text-muted-foreground">
            Scans for outdated anchors, low-evidence high-severity comments, and missing rationale before submit.
          </p>
        </div>
        <Button variant="outline" size="sm" className="gap-1.5 text-xs" onClick={onRunQualityCheck}>
          <Check className="w-3.5 h-3.5" />
          Run Quality Check
        </Button>
      </div>
    </div>
  )
}

function ReviewQualityCheckCard({
  checkedAt,
  report,
  onApplyRepair,
}: {
  checkedAt: number
  report: ReviewQualityReport
  onApplyRepair: (action: ReviewQualityAction) => void
}) {
  const checkedAtLabel = new Date(checkedAt).toLocaleTimeString()
  const scoreTone = report.score >= 85 ? 'text-status-approve' : report.score >= 65 ? 'text-status-suggestion' : 'text-status-issue'

  return (
    <div className="rounded border border-border bg-muted/20 px-3 py-2.5">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-xs font-semibold">Review Quality Check</span>
        <span className={cn('text-xs font-mono', scoreTone)}>Score {report.score}/100</span>
        <span className="text-[11px] text-muted-foreground">Checked at {checkedAtLabel}</span>
      </div>
      {report.issues.length === 0 ? (
        <p className="mt-1 text-xs text-status-approve">No major trust issues detected in this draft.</p>
      ) : (
        <ul className="mt-2 space-y-1">
          {report.issues.map((issue) => (
            <li key={issue.id} className="text-xs text-muted-foreground">
              <span className="text-foreground">{issue.title}</span> · {issue.count} · {issue.description}
            </li>
          ))}
        </ul>
      )}
      {report.suggestions.length > 0 && (
        <div className="mt-2 flex flex-wrap items-center gap-2">
          {report.suggestions.includes('removeUnanchored') && (
            <Button variant="outline" size="sm" className="text-xs" onClick={() => onApplyRepair('removeUnanchored')}>
              Remove unanchored comments
            </Button>
          )}
          {report.suggestions.includes('addMissingRationale') && (
            <Button variant="outline" size="sm" className="text-xs" onClick={() => onApplyRepair('addMissingRationale')}>
              Add rationale placeholders
            </Button>
          )}
          {report.suggestions.includes('downgradeHighRisk') && (
            <Button variant="outline" size="sm" className="text-xs" onClick={() => onApplyRepair('downgradeHighRisk')}>
              Downgrade low-evidence issues
            </Button>
          )}
        </div>
      )}
    </div>
  )
}

function ChunkedProgressCard({ progress }: { progress: ChunkedProgress }) {
  const percent = progress.totalBatches > 0
    ? Math.round((progress.completed.length / progress.totalBatches) * 100)
    : 0
  return (
    <div className="rounded border border-border bg-muted/20 px-3 py-2.5">
      <div className="flex items-center justify-between gap-2">
        <span className="text-xs font-semibold">Chunked review progress</span>
        <span className="text-[11px] font-mono text-muted-foreground">
          {progress.completed.length}/{progress.totalBatches} ({percent}%)
        </span>
      </div>
      <p className="mt-1 text-[11px] text-muted-foreground">
        {progress.running
          ? `Running batch ${progress.currentBatch}/${progress.totalBatches}: ${progress.activeLabel}`
          : `Last run status: ${progress.activeLabel}`}
      </p>
      {progress.completed.length > 0 && (
        <ul className="mt-2 space-y-1">
          {progress.completed.map((item) => (
            <li key={item.label} className="text-[11px] text-muted-foreground">
              {item.label} · confidence {(item.confidence * 100).toFixed(0)}% · {item.comments} comments
              {item.fileConfidence.length > 0 && (
                <ul className="mt-1 space-y-0.5 text-[10px] text-muted-foreground/80">
                  {item.fileConfidence.map((file) => (
                    <li key={file.file} className="flex items-center gap-1.5" title={file.file}>
                      <span className="truncate max-w-[36rem]">{file.file}</span>
                      <span className="font-mono">{(file.confidence * 100).toFixed(0)}%</span>
                    </li>
                  ))}
                </ul>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

// ── Pane content switcher ────────────────────────────────────────────────────

interface ContentProps {
  state: PaneState
  focusedCommentIdx: number
  setFocusedCommentIdx: React.Dispatch<React.SetStateAction<number>>
  onGenerate: () => void
  onVerifyComment?: (comment: LineComment) => void
  editCommentHandlers: {
    onEditComment: (idx: number, body: string) => void
    onDeleteComment: (idx: number) => void
    onAddComment: (comment: LineComment) => void
  }
  inlineComments: LineComment[]
  orphanComments: LineComment[]
  onEditOrphan: (orphan: LineComment, body: string) => void
  onDeleteOrphan: (orphan: LineComment) => void
  onReloadDraft: () => void
  onOpenSettings: () => void
  onOpenAuthGuide: () => void
}

function useElapsedSeconds(active: boolean): number {
  const [seconds, setSeconds] = useState(0)
  useEffect(() => {
    if (!active) { setSeconds(0); return }
    setSeconds(0)
    const id = setInterval(() => setSeconds((s) => s + 1), 1000)
    return () => clearInterval(id)
  }, [active])
  return seconds
}

function formatElapsed(s: number): string {
  const m = Math.floor(s / 60)
  const sec = s % 60
  return m > 0 ? `${m}:${String(sec).padStart(2, '0')}` : `${sec}s`
}

function formatGenerationSummary(elapsedSec?: number): string | null {
  if (elapsedSec == null || elapsedSec < 0) return null
  return `Generated in ${formatElapsed(elapsedSec)}`
}

function isDiffTruncated(diff?: string): boolean {
  return Boolean(diff?.includes('[... diff truncated at 80 KB ...]'))
}

function chatContextSummary(
  pr: PR | null,
  diff: string,
  result: ReviewResult | null,
  selectedContext: string,
): string[] {
  if (!pr) return []
  const items = ['PR title/body']
  if (diff) items.push(isDiffTruncated(diff) ? 'diff excerpt' : 'diff')
  if (result) items.push('generated review')
  if (selectedContext) items.push('selected text')
  return items
}

function ReviewAndDiff({
  result,
  diff,
  generationElapsedSec,
  focusedCommentIdx,
  setFocusedCommentIdx,
  editCommentHandlers,
  onVerifyComment,
  staleCommits,
  importedFromGitHub,
  inlineComments,
  orphanComments,
  onEditOrphan,
  onDeleteOrphan,
}: {
  result: ReviewResult
  diff?: string
  generationElapsedSec?: number
  focusedCommentIdx: number
  setFocusedCommentIdx: React.Dispatch<React.SetStateAction<number>>
  editCommentHandlers: ContentProps['editCommentHandlers']
  onVerifyComment?: (comment: LineComment) => void
  staleCommits?: boolean
  importedFromGitHub?: boolean
  inlineComments: LineComment[]
  orphanComments: LineComment[]
  onEditOrphan: (orphan: LineComment, body: string) => void
  onDeleteOrphan: (orphan: LineComment) => void
}) {
  const generationSummary = formatGenerationSummary(generationElapsedSec)
  return (
    <>
      {staleCommits && (
        <Alert className="mx-4 mt-3 mb-0 border-status-suggestion/40 bg-status-suggestion/5">
          <AlertTriangle className="h-3.5 w-3.5 text-status-suggestion" />
          <AlertDescription className="text-xs text-status-suggestion/90">
            Draft generated against an older commit — new commits may have been pushed.
          </AlertDescription>
        </Alert>
      )}
      {importedFromGitHub && (
        <Alert className="mx-4 mt-3 mb-0 border-status-suggestion/40 bg-status-suggestion/5">
          <AlertTriangle className="h-3.5 w-3.5 text-status-suggestion" />
          <AlertDescription className="text-xs text-status-suggestion/90">
            Draft was reconstructed from GitHub comments — hidden PR Pilot metadata was missing, so review details may be incomplete.
          </AlertDescription>
        </Alert>
      )}
      {isDiffTruncated(diff) && (
        <Alert className="mx-4 mt-3 mb-0 border-status-suggestion/40 bg-status-suggestion/5">
          <AlertTriangle className="h-3.5 w-3.5 text-status-suggestion" />
          <AlertDescription className="text-xs text-status-suggestion/90">
            Diff display and chat context are truncated at 80 KB. Use smaller focused questions for large PRs.
          </AlertDescription>
        </Alert>
      )}
      {generationSummary && (
        <p className="px-4 pt-3 text-xs text-muted-foreground">{generationSummary}</p>
      )}
      <div className="px-4 pt-3">
        <ReviewDisplay result={result} />
      </div>
      {orphanComments.length > 0 && (
        <div className="px-4 pt-3">
          <OrphanCommentsSection
            orphans={orphanComments}
            onEdit={onEditOrphan}
            onDelete={onDeleteOrphan}
          />
        </div>
      )}
      {diff && (
        <div className="px-4 pb-4">
          <DiffViewer
            diff={diff}
            comments={inlineComments}
            focusedCommentIdx={focusedCommentIdx}
            onEditComment={editCommentHandlers.onEditComment}
            onDeleteComment={(idx) => {
              setFocusedCommentIdx((f) => (f > 0 && f >= idx ? f - 1 : f))
              editCommentHandlers.onDeleteComment(idx)
            }}
            onAddComment={editCommentHandlers.onAddComment}
            onVerifyComment={onVerifyComment}
          />
        </div>
      )}
    </>
  )
}

function ErrorWithReview({
  message,
  result,
  diff,
  focusedCommentIdx,
  setFocusedCommentIdx,
  editCommentHandlers,
  inlineComments,
  orphanComments,
  onEditOrphan,
  onDeleteOrphan,
}: {
  message: string
  result: ReviewResult | null
  diff: string
  focusedCommentIdx: number
  setFocusedCommentIdx: React.Dispatch<React.SetStateAction<number>>
  editCommentHandlers: ContentProps['editCommentHandlers']
  inlineComments: LineComment[]
  orphanComments: LineComment[]
  onEditOrphan: (orphan: LineComment, body: string) => void
  onDeleteOrphan: (orphan: LineComment) => void
}) {
  return (
    <div className="flex flex-col">
      {result && (
        <ReviewAndDiff
          result={result}
          diff={diff || undefined}
          focusedCommentIdx={focusedCommentIdx}
          setFocusedCommentIdx={setFocusedCommentIdx}
          editCommentHandlers={editCommentHandlers}
          inlineComments={inlineComments}
          orphanComments={orphanComments}
          onEditOrphan={onEditOrphan}
          onDeleteOrphan={onDeleteOrphan}
        />
      )}
      <div className="px-4 pb-3">
        <Alert variant="destructive">
          <AlertDescription>{message}</AlertDescription>
        </Alert>
      </div>
    </div>
  )
}

function PaneContent({
  state,
  focusedCommentIdx,
  setFocusedCommentIdx,
  onGenerate,
  onVerifyComment,
  editCommentHandlers,
  inlineComments,
  orphanComments,
  onEditOrphan,
  onDeleteOrphan,
  onReloadDraft,
  onOpenSettings,
  onOpenAuthGuide,
}: ContentProps) {
  const elapsed = useElapsedSeconds(state.kind === 'generating')

  switch (state.kind) {
    case 'idle':
      return null

    case 'draftLoading':
      return (
        <div className="flex items-center gap-2.5 px-4 pt-3 text-sm text-muted-foreground">
          <Loader2 className="w-4 h-4 text-primary animate-spin shrink-0" />
          Checking for draft…
        </div>
      )

    case 'noDraft':
      return (
        <div className="flex flex-col items-center justify-center gap-4 p-8">
          <p className="text-sm text-muted-foreground">No pending draft for this PR.</p>
          <Button onClick={onGenerate} className="gap-2">
            Generate Review
          </Button>
        </div>
      )

    case 'authError':
      return (
        <div className="p-4 flex flex-col gap-3">
          <Alert variant="destructive">
            <AlertDescription>{state.message}</AlertDescription>
          </Alert>
          <p className="text-xs text-muted-foreground">Check GitHub CLI authentication and host settings, then retry.</p>
          <div className="flex flex-wrap items-center gap-2">
            <Button variant="outline" size="sm" className="gap-1.5" onClick={onReloadDraft}>
              <RefreshCw className="w-3.5 h-3.5" />
              Retry
            </Button>
            <Button variant="outline" size="sm" className="gap-1.5" onClick={onOpenSettings}>
              <Settings2 className="w-3.5 h-3.5" />
              Open Settings
            </Button>
            <Button variant="outline" size="sm" className="gap-1.5" onClick={onOpenAuthGuide}>
              <ExternalLink className="w-3.5 h-3.5" />
              Auth Guide
            </Button>
          </div>
        </div>
      )

    case 'generating': {
      const latestMessage = state.messages[state.messages.length - 1] ?? 'Starting review…'
      const isThinking = elapsed >= 10 && state.chunks.length === 0
      const latestChunks = state.chunks.slice(-5)
      return (
        <div className="flex flex-col gap-4 p-6">
          <div className="flex items-center gap-3">
            <Loader2 className="w-4 h-4 text-primary animate-spin shrink-0" />
            <div className="flex-1 min-w-0">
              <p className="text-sm text-foreground/80 truncate">{latestMessage}</p>
              <p className="text-xs text-muted-foreground font-mono mt-0.5">
                {elapsed < 2 ? 'Starting…' : formatElapsed(elapsed)}
              </p>
            </div>
          </div>

          {/* Indeterminate progress bar */}
          <div className="relative h-0.5 bg-border rounded-full overflow-hidden">
            <div
              className="absolute inset-y-0 w-2/5 bg-primary rounded-full"
              style={{ animation: 'progress-slide 1.5s ease-in-out infinite' }}
            />
          </div>

          {isThinking && (
            <p className="text-xs text-muted-foreground italic">
              {elapsed >= 60 ? 'Large diffs may take a few minutes…' : 'AI is thinking…'}
            </p>
          )}

          {latestChunks.length > 0 && (
            <p className="text-xs text-muted-foreground/60 font-mono break-words line-clamp-3 leading-relaxed">
              {latestChunks.map((c) => c.content).join('')}
            </p>
          )}
        </div>
      )
    }

    case 'draftPresent':
      return (
        <ReviewAndDiff
          result={state.result}
          diff={state.diff}
          generationElapsedSec={state.generationElapsedSec}
          focusedCommentIdx={focusedCommentIdx}
          setFocusedCommentIdx={setFocusedCommentIdx}
          editCommentHandlers={editCommentHandlers}
          onVerifyComment={onVerifyComment}
          staleCommits={state.staleCommits}
          importedFromGitHub={state.importedFromGitHub}
          inlineComments={inlineComments}
          orphanComments={orphanComments}
          onEditOrphan={onEditOrphan}
          onDeleteOrphan={onDeleteOrphan}
        />
      )

    case 'reviewUnsaved':
      return (
        <ReviewAndDiff
          result={state.result}
          diff={state.diff}
          generationElapsedSec={state.generationElapsedSec}
          focusedCommentIdx={focusedCommentIdx}
          setFocusedCommentIdx={setFocusedCommentIdx}
          editCommentHandlers={editCommentHandlers}
          onVerifyComment={onVerifyComment}
          inlineComments={inlineComments}
          orphanComments={orphanComments}
          onEditOrphan={onEditOrphan}
          onDeleteOrphan={onDeleteOrphan}
        />
      )

    case 'merged':
      return (
        <div className="flex flex-col items-center justify-center gap-2 p-8">
          <GitMerge className="w-9 h-9 text-muted-foreground/50" />
          <p className="text-sm text-muted-foreground">This pull request has been merged.</p>
          {state.status && <p className="text-xs text-muted-foreground">{state.status}</p>}
        </div>
      )

    case 'submitted':
      return (
        <div className="flex flex-col items-center justify-center gap-3 p-8">
          <CheckCircle2 className="w-9 h-9 text-emerald-500" />
          <p className="text-sm text-muted-foreground">Review submitted.</p>
          <Button variant="outline" onClick={onGenerate} className="gap-2">
            <RotateCcw className="w-3.5 h-3.5" />
            Generate New Review
          </Button>
        </div>
      )

    case 'error':
      return (
        <div className="p-4 flex flex-col gap-3">
          <Alert variant="destructive">
            <AlertDescription>{state.message}</AlertDescription>
          </Alert>
          <Button variant="outline" size="sm" onClick={onGenerate} className="w-fit gap-1.5">
            <RotateCcw className="w-3.5 h-3.5" />
            Try Again
          </Button>
        </div>
      )

    case 'saveError':
      return (
        <ErrorWithReview
          message={state.message}
          result={state.result}
          diff={state.diff}
          focusedCommentIdx={focusedCommentIdx}
          setFocusedCommentIdx={setFocusedCommentIdx}
          editCommentHandlers={editCommentHandlers}
          inlineComments={inlineComments}
          orphanComments={orphanComments}
          onEditOrphan={onEditOrphan}
          onDeleteOrphan={onDeleteOrphan}
        />
      )

    case 'submitError':
      return (
        <ErrorWithReview
          message={state.message}
          result={state.result}
          diff={state.diff}
          focusedCommentIdx={focusedCommentIdx}
          setFocusedCommentIdx={setFocusedCommentIdx}
          editCommentHandlers={editCommentHandlers}
          inlineComments={inlineComments}
          orphanComments={orphanComments}
          onEditOrphan={onEditOrphan}
          onDeleteOrphan={onDeleteOrphan}
        />
      )
  }
}

// ── Footer ────────────────────────────────────────────────────────────────────

interface FooterProps {
  state: PaneState
  saving: boolean
  submitting: boolean
  deleting: boolean
  onSave: () => void
  onSubmit: (v: Verdict, comment?: string) => void
  onCancel: () => void
  onRegenerate: () => void
  onDelete: () => void
  onRunQualityCheck: () => void
  inlineCommentCount: number
  orphanCommentCount: number
  summary: string
}

function ReviewFooter({
  state,
  saving,
  submitting,
  deleting,
  onSave,
  onSubmit,
  onCancel,
  onRegenerate,
  onDelete,
  onRunQualityCheck,
  inlineCommentCount,
  orphanCommentCount,
  summary,
}: FooterProps) {
  if (state.kind === 'generating') {
    return (
      <div className="shrink-0 flex items-center gap-2 px-4 py-2.5 border-t border-border bg-card">
        <Button variant="destructive" size="sm" onClick={onCancel} className="gap-1.5">
          <X className="w-3.5 h-3.5" />
          Cancel
        </Button>
      </div>
    )
  }

  if (state.kind === 'draftPresent' || state.kind === 'reviewUnsaved') {
    const busy = saving || submitting || deleting
    return (
      <div className="shrink-0 flex items-center gap-2 px-4 py-2.5 border-t border-border bg-card">
        {/* Regen — confirm when saved draft exists */}
        {state.kind === 'draftPresent' ? (
          <AlertDialog>
            <AlertDialogTrigger asChild>
              <Button variant="ghost" size="sm" disabled={busy} className="gap-1.5 text-xs">
                <RotateCcw className="w-3.5 h-3.5" />
                Regen
              </Button>
            </AlertDialogTrigger>
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>Regenerate review?</AlertDialogTitle>
                <AlertDialogDescription>
                  The current draft will be discarded and a new review generated from scratch.
                </AlertDialogDescription>
              </AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel>Cancel</AlertDialogCancel>
                <AlertDialogAction onClick={onRegenerate}>Regenerate</AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        ) : (
          <Button variant="ghost" size="sm" disabled={busy} className="gap-1.5 text-xs" onClick={onRegenerate}>
            <RotateCcw className="w-3.5 h-3.5" />
            Regen
          </Button>
        )}

        <div className="flex-1" />

        <div className="flex items-center gap-2">
          <span className="hidden text-[11px] text-muted-foreground lg:inline">
            Scans trust risks before submit.
          </span>
          <Tooltip>
            <TooltipTrigger asChild>
              <Button variant="outline" size="sm" disabled={busy} className="gap-1.5 text-xs" onClick={onRunQualityCheck}>
                <Check className="w-3.5 h-3.5" />
                Quality Check
              </Button>
            </TooltipTrigger>
            <TooltipContent side="top" className="max-w-xs text-xs leading-relaxed">
              Checks for outdated anchors, high-risk low-evidence comments, and missing rationale, then offers one-click
              repairs.
            </TooltipContent>
          </Tooltip>
        </div>

        {/* Delete — always confirm */}
        {state.kind === 'draftPresent' && (
          <AlertDialog>
            <AlertDialogTrigger asChild>
              <Button variant="ghost" size="sm" disabled={deleting} className="gap-1.5 text-xs text-destructive hover:text-destructive">
                {deleting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Trash2 className="w-3.5 h-3.5" />}
                Delete
              </Button>
            </AlertDialogTrigger>
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>Delete draft review?</AlertDialogTitle>
                <AlertDialogDescription>
                  This removes the pending review from GitHub permanently.
                </AlertDialogDescription>
              </AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel>Cancel</AlertDialogCancel>
                <AlertDialogAction
                  onClick={onDelete}
                  className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
                >
                  Delete
                </AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        )}

        <Button variant="secondary" size="sm" onClick={onSave} disabled={busy} className="gap-1.5 text-xs">
          {saving ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <CloudUpload className="w-3.5 h-3.5" />}
          {saving ? 'Saving…' : 'Save Draft'}
        </Button>

        <SubmitSplitButton
          verdict={state.kind === 'draftPresent' || state.kind === 'reviewUnsaved' ? state.result.verdict : 'APPROVE'}
          onSubmit={onSubmit}
          submitting={submitting}
          disabled={saving || deleting}
          inlineCommentCount={inlineCommentCount}
          orphanCommentCount={orphanCommentCount}
          summary={summary}
        />
      </div>
    )
  }

  if (state.kind === 'saveError') {
    return (
      <div className="shrink-0 flex items-center gap-2 px-4 py-2.5 border-t border-border bg-card">
        <Button variant="secondary" size="sm" onClick={onSave} disabled={saving || submitting} className="gap-1.5 text-xs">
          {saving ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <CloudUpload className="w-3.5 h-3.5" />}
          {saving ? 'Saving…' : 'Retry Save'}
        </Button>
        <SubmitSplitButton
          verdict={state.kind === 'saveError' && state.result ? state.result.verdict : 'APPROVE'}
          onSubmit={onSubmit}
          submitting={submitting}
          disabled={saving}
          inlineCommentCount={inlineCommentCount}
          orphanCommentCount={orphanCommentCount}
          summary={summary}
        />
      </div>
    )
  }

  if (state.kind === 'submitError') {
    return (
      <div className="shrink-0 flex items-center gap-2 px-4 py-2.5 border-t border-border bg-card">
        <SubmitSplitButton
          verdict={state.kind === 'submitError' && state.result ? state.result.verdict : 'APPROVE'}
          onSubmit={onSubmit}
          submitting={submitting}
          disabled={false}
          inlineCommentCount={inlineCommentCount}
          orphanCommentCount={orphanCommentCount}
          summary={summary}
        />
      </div>
    )
  }

  return null
}

// ── Split submit button ───────────────────────────────────────────────────────

function SubmitSplitButton({
  verdict,
  onSubmit,
  submitting,
  disabled,
  inlineCommentCount,
  orphanCommentCount,
  summary,
}: {
  verdict: Verdict
  onSubmit: (v: Verdict, comment?: string) => void
  submitting: boolean
  disabled: boolean
  inlineCommentCount: number
  orphanCommentCount: number
  summary: string
}) {
  const [confirming, setConfirming] = useState<Verdict | null>(null)
  const [comment, setComment] = useState('')
  const ICON: Record<Verdict, React.ReactNode> = {
    APPROVE: <Check className="w-3.5 h-3.5" />,
    REQUEST_CHANGES: <XCircle className="w-3.5 h-3.5" />,
    COMMENT: <MessageSquare className="w-3.5 h-3.5" />,
  }
  const LABEL: Record<Verdict, string> = {
    APPROVE: 'Approve',
    REQUEST_CHANGES: 'Request Changes',
    COMMENT: 'Comment',
  }
  const VARIANT: Record<Verdict, 'default' | 'destructive' | 'secondary'> = {
    APPROVE: 'default',
    REQUEST_CHANGES: 'destructive',
    COMMENT: 'secondary',
  }

  const others: Verdict[] = (['COMMENT', 'APPROVE', 'REQUEST_CHANGES'] as Verdict[]).filter((v) => v !== verdict)

  return (
    <div className="flex items-stretch rounded-md overflow-hidden">
      <Button
        variant={VARIANT[verdict]}
        size="sm"
        className="text-xs rounded-r-none gap-1.5 border-r border-white/20"
        onClick={() => setConfirming(verdict)}
        disabled={submitting || disabled}
      >
        {submitting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : ICON[verdict]}
        {submitting ? 'Submitting…' : LABEL[verdict]}
      </Button>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button
            variant={VARIANT[verdict]}
            size="sm"
            className="text-xs rounded-l-none px-1.5"
            disabled={submitting || disabled}
            aria-label="More submit options"
          >
            <ChevronDown className="w-3.5 h-3.5" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-48">
          {others.filter((v) => v !== 'REQUEST_CHANGES').map((v) => (
            <DropdownMenuItem
              key={v}
              onSelect={() => setConfirming(v)}
              className="gap-2 text-xs cursor-pointer"
            >
              {ICON[v]}
              {LABEL[v]}
            </DropdownMenuItem>
          ))}
          {others.includes('REQUEST_CHANGES') && <DropdownMenuSeparator />}
          {others.includes('REQUEST_CHANGES') && (
            <DropdownMenuItem
              onSelect={() => setConfirming('REQUEST_CHANGES')}
              className="gap-2 text-xs cursor-pointer text-destructive focus:text-destructive"
            >
              {ICON.REQUEST_CHANGES}
              {LABEL.REQUEST_CHANGES}
            </DropdownMenuItem>
          )}
        </DropdownMenuContent>
      </DropdownMenu>
      <AlertDialog open={confirming !== null} onOpenChange={(open) => !open && setConfirming(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Submit {confirming ? LABEL[confirming].toLowerCase() : 'review'}?</AlertDialogTitle>
            <AlertDialogDescription>
              This will publish the pending GitHub review with {inlineCommentCount} inline comment{inlineCommentCount === 1 ? '' : 's'}
              {orphanCommentCount > 0 ? ` and ${orphanCommentCount} unanchored comment${orphanCommentCount === 1 ? '' : 's'} in the review body` : ''}.
            </AlertDialogDescription>
          </AlertDialogHeader>
          {summary && (
            <div className="max-h-28 overflow-y-auto rounded border border-border bg-muted/30 p-2 text-xs text-muted-foreground">
              {summary}
            </div>
          )}
          <textarea
            className="min-h-[72px] w-full rounded-md border border-input bg-background px-3 py-2 text-sm outline-none focus:border-ring"
            placeholder="Optional final review body…"
            value={comment}
            onChange={(e) => setComment(e.target.value)}
          />
          <AlertDialogFooter>
            <AlertDialogCancel onClick={() => setComment('')}>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                if (confirming) onSubmit(confirming, comment.trim())
                setComment('')
                setConfirming(null)
              }}
              className={confirming === 'REQUEST_CHANGES' ? 'bg-destructive text-destructive-foreground hover:bg-destructive/90' : undefined}
            >
              Submit {confirming ? LABEL[confirming] : 'Review'}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}

// ── Orphan (unanchored) comments ──────────────────────────────────────────────

const ORPHAN_BADGE: Record<LineComment['type'], string> = {
  issue:      'text-status-issue border-status-issue/50 bg-status-issue/10',
  suggestion: 'text-status-suggestion border-status-suggestion/50 bg-status-suggestion/10',
  note:       'text-status-note border-status-note/50 bg-status-note/10',
}

function OrphanCommentsSection({
  orphans,
  onEdit,
  onDelete,
}: {
  orphans: LineComment[]
  onEdit: (orphan: LineComment, body: string) => void
  onDelete: (orphan: LineComment) => void
}) {
  return (
    <div className="rounded border border-status-suggestion/40 bg-status-suggestion/5">
      <div className="flex items-center gap-2 px-3 py-1.5 border-b border-status-suggestion/30">
        <AlertTriangle className="h-3.5 w-3.5 text-status-suggestion shrink-0" />
        <span className="text-xs font-semibold tracking-wide text-status-suggestion">
          Unanchored comments
        </span>
        <span className="text-[10px] text-muted-foreground font-mono">
          GitHub can't attach these inline
        </span>
      </div>
      <ul className="divide-y divide-status-suggestion/20">
        {orphans.map((o, i) => (
          <OrphanRow
            key={`${o.file}|${o.line}|${i}`}
            orphan={o}
            onEdit={(body) => onEdit(o, body)}
            onDelete={() => onDelete(o)}
          />
        ))}
      </ul>
    </div>
  )
}

function OrphanRow({
  orphan,
  onEdit,
  onDelete,
}: {
  orphan: LineComment
  onEdit: (body: string) => void
  onDelete: () => void
}) {
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(orphan.body)
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  useEffect(() => { if (!editing) setDraft(orphan.body) }, [editing, orphan.body])
  useEffect(() => {
    if (editing && textareaRef.current) {
      const el = textareaRef.current
      el.focus()
      el.style.height = 'auto'
      el.style.height = el.scrollHeight + 'px'
    }
  }, [editing])

  function handleSave() {
    const trimmed = draft.trim()
    if (trimmed) onEdit(trimmed)
    setEditing(false)
  }

  return (
    <li className="px-3 py-2 flex flex-col gap-1.5">
      <div className="flex items-center gap-2">
        <span className={cn('inline-flex items-center rounded border px-1.5 py-0 text-[9px] font-bold tracking-widest uppercase', ORPHAN_BADGE[orphan.type])}>
          {orphan.type}
        </span>
        <span className="font-mono text-[11px] text-muted-foreground truncate flex-1">
          {orphan.file}:{orphan.line}
        </span>
        {!editing && (
          <div className="flex items-center gap-1">
            <Button
              variant="ghost"
              size="sm"
              className="h-6 px-1.5 text-[10px] text-muted-foreground hover:text-foreground"
              onClick={() => setEditing(true)}
              aria-label="Edit unanchored comment"
            >
              Edit
            </Button>
            <AlertDialog>
              <AlertDialogTrigger asChild>
                <Button
                  variant="ghost"
                  size="sm"
                  className="h-6 px-1.5 text-[10px] text-muted-foreground hover:text-destructive"
                  aria-label="Delete unanchored comment"
                >
                  Delete
                </Button>
              </AlertDialogTrigger>
              <AlertDialogContent>
                <AlertDialogHeader>
                  <AlertDialogTitle>Delete this comment?</AlertDialogTitle>
                  <AlertDialogDescription>
                    This comment will be removed. Save the draft to persist the change.
                  </AlertDialogDescription>
                </AlertDialogHeader>
                <AlertDialogFooter>
                  <AlertDialogCancel>Cancel</AlertDialogCancel>
                  <AlertDialogAction
                    onClick={onDelete}
                    className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
                  >
                    Delete
                  </AlertDialogAction>
                </AlertDialogFooter>
              </AlertDialogContent>
            </AlertDialog>
          </div>
        )}
      </div>
      {editing ? (
        <div className="flex flex-col gap-1.5">
          <textarea
            ref={textareaRef}
            className="diff-comment__textarea"
            value={draft}
            rows={2}
            onChange={(e) => {
              setDraft(e.target.value)
              e.target.style.height = 'auto'
              e.target.style.height = e.target.scrollHeight + 'px'
            }}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) handleSave()
              if (e.key === 'Escape') setEditing(false)
            }}
          />
          <div className="flex gap-1.5">
            <Button size="sm" className="h-6 text-xs gap-1" onClick={handleSave}>
              <Check className="w-3 h-3" />Save
            </Button>
            <Button variant="ghost" size="sm" className="h-6 text-xs gap-1" onClick={() => setEditing(false)}>
              <X className="w-3 h-3" />Cancel
            </Button>
          </div>
        </div>
      ) : (
        <p className="text-xs leading-snug whitespace-pre-wrap">{orphan.body}</p>
      )}
    </li>
  )
}
