import { useCallback, useEffect, useMemo, useReducer, useRef, useState } from 'react'
import type { PointerEvent as ReactPointerEvent, RefObject } from 'react'
import { toast } from 'sonner'
import {
  onHostMessage,
  sendToHost,
  type LineComment,
  type PR,
  type ReviewResult,
  type DeepReviewPreparedMessage,
  type RetainedDeepReview,
} from '../../bridge/types'
import { autosaveDelayMs, isReviewDirty, reviewSnapshot } from '@/lib/autosave'
import { parseDiffSafely } from '@/lib/diffParse'
import {
  applyReviewQualityRepairs,
  runReviewQualityCheck,
  type ReviewQualityAction,
  type ReviewQualityReport,
} from '@/lib/reviewQuality'
import { validateComments } from '@/lib/validateComments'
import type { VerifyResult } from '../ChatPane/structuredResult'
import {
  CHAT_HEIGHT_KEY,
  clampChatHeight,
  effectiveChatAvailableHeight,
  loadChatHeight,
} from './chatHeight'
import { adjacentCommentIndex, focusedIndexAfterCommentDeletion } from './commentNavigation'
import {
  diffOf,
  initialPaneState,
  isDiffTruncated,
  normalizeReviewResult,
  resultOf,
  reviewReducer,
  validationDiffOf,
  type DraftPresentState,
  type PaneState,
  type Verdict,
} from './reviewState'
import {
  appendReviewActivity,
  emptyReviewActivity,
  finishReviewActivity,
  formatReviewActivityLabel,
  startReviewActivity,
  type ReviewActivity,
} from './reviewActivity'
import { buildExampleFixPrompt, buildVerifyCommentPrompt, resolveVerifyTarget } from './verifyPrompt'

export interface DiffPreflight {
  fileCount: number
  changedLines: number
}

export interface ChunkRecommendation {
  recommendChunked: boolean
  reason: string
}

export interface PendingChatMessage {
  q: string
  ctx: string
  id: number
  token?: string
  contextSummary?: string[]
}

export interface ReviewViewModel {
  intellijAssisted: boolean
  deepSetup: DeepReviewPreparedMessage | null
  retainedDeepReviews: RetainedDeepReview[]
  deepMaintenanceError: string
  deepBusy: boolean
  pr: PR | null
  state: PaneState
  activity: ReviewActivity
  result: ReviewResult | null
  diff: string
  validationDiff: string
  diffUnavailable: boolean
  inlineComments: LineComment[]
  orphanComments: LineComment[]
  qualityReport: ReviewQualityReport | null
  qualityRiskCount: number
  preflight: DiffPreflight | null
  recommendation: ChunkRecommendation
  focusAreasOverride: string
  customInstructionsOverride: string
  chunkedMode: boolean
  showReviewOverrides: boolean
  saving: boolean
  submitting: boolean
  deleting: boolean
  autosaveDirty: boolean
  focusedCommentIdx: number
  commentFocusRequestId: number
  showChat: boolean
  chatVisible: boolean
  selectedContext: string
  pendingChatMessage: PendingChatMessage | null
  chatHeight: number
  chatAvailableHeight: number
  contextSummary: string[]
  qualityExpanded: boolean
  hasReview: boolean
  statusMessage: string
}

export interface ReviewActions {
  setIntellijAssisted: (value: boolean) => void
  continueDeepReview: (server: string) => void
  ordinaryReview: () => void
  listDeepReviews: () => void
  cleanupDeepReview: (id: string) => void
  setFocusAreasOverride: (value: string) => void
  setCustomInstructionsOverride: (value: string) => void
  setChunkedMode: (value: boolean) => void
  generate: () => void
  cancel: () => void
  save: () => void
  deleteDraft: () => void
  reloadDraft: () => void
  keepDraft: () => void
  reanchorDraft: () => void
  submit: (verdict: Verdict, comment?: string) => void
  verifyComment: (comment: LineComment) => void
  suggestFixComment: (comment: LineComment) => void
  applyVerifyAction: (verify: VerifyResult, token: string) => void
  editCommentHandlers: {
    onEditComment: (index: number, body: string) => void
    onDeleteComment: (index: number) => void
    onAddComment: (comment: LineComment) => void
  }
  orphanHandlers: {
    onEditOrphan: (orphan: LineComment, body: string) => void
    onDeleteOrphan: (orphan: LineComment) => void
  }
  runQualityCheck: () => void
  applyQualityRepair: (action: ReviewQualityAction) => void
  collapseQualityCheck: () => void
  focusComment: (index: number) => void
  focusPreviousComment: () => void
  focusNextComment: () => void
  toggleChat: () => void
  openChat: () => void
  clearSelectedContext: () => void
  askAboutSelection: (question: string) => void
  pendingMessageSent: () => void
  setChatHeight: (height: number) => void
  commitChatHeight: (height: number) => void
  startChatResize: (event: ReactPointerEvent) => void
  openPr: () => void
  openSettings: () => void
  openAuthGuide: () => void
  discardPendingChanges: () => boolean
}

export interface ReviewRefs {
  paneRef: RefObject<HTMLDivElement | null>
  reviewBodyRef: RefObject<HTMLDivElement | null>
}

export interface ReviewController {
  model: ReviewViewModel
  actions: ReviewActions
  refs: ReviewRefs
}

interface UseReviewControllerProps {
  pr: PR | null
  onDirtyStateChange?: (dirty: boolean) => void
}

interface InFlightSave {
  saveId: number
  prKey: string
  snapshot: string
  isAuto: boolean
}

interface PendingAutosave {
  pr: PR
  result: ReviewResult
  orphans: LineComment[]
  snapshot: string
}

interface WatchdogRef {
  current: ReturnType<typeof setTimeout> | null
}

export const MUTATION_WATCHDOG_MS = 45_000

function newOperationId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`
}

function prKey(pr: Pick<PR, 'owner' | 'repo' | 'number'>): string {
  return `${pr.owner}/${pr.repo}#${pr.number}`
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

function chunkRecommendation(preflight: DiffPreflight | null, truncated: boolean): ChunkRecommendation {
  if (truncated) return { recommendChunked: true, reason: 'Diff context is truncated.' }
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

function clearWatchdog(ref: WatchdogRef) {
  if (ref.current !== null) {
    clearTimeout(ref.current)
    ref.current = null
  }
}

function armWatchdog(ref: WatchdogRef, onTimeout: () => void) {
  clearWatchdog(ref)
  ref.current = setTimeout(() => {
    ref.current = null
    onTimeout()
  }, MUTATION_WATCHDOG_MS)
}

export function useReviewController({
  pr,
  onDirtyStateChange,
}: UseReviewControllerProps): ReviewController {
  const [state, dispatch] = useReducer(reviewReducer, initialPaneState)
  const [activity, setReviewActivity] = useState<ReviewActivity>(emptyReviewActivity)
  const [focusAreasOverride, setFocusAreasOverride] = useState('')
  const [customInstructionsOverride, setCustomInstructionsOverride] = useState('')
  const [saving, setSaving] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [focusedCommentIdx, setFocusedCommentIdx] = useState(0)
  const [commentFocusRequestId, setCommentFocusRequestId] = useState(0)
  const [chatVisible, setChatVisible] = useState(false)
  const [selectedContext, setSelectedContext] = useState('')
  const [pendingChatMessage, setPendingChatMessage] = useState<PendingChatMessage | null>(null)
  const [chunkedMode, setChunkedMode] = useState(false)
  const [intellijAssisted, setIntellijAssisted] = useState(false)
  const [deepSetup, setDeepSetup] = useState<DeepReviewPreparedMessage | null>(null)
  const [retainedDeepReviews, setRetainedDeepReviews] = useState<RetainedDeepReview[]>([])
  const [deepMaintenanceError, setDeepMaintenanceError] = useState('')
  const [deepBusy, setDeepBusy] = useState(false)
  const maintenanceOperationRef = useRef<string | null>(null)
  const beforeDeepPauseRef = useRef<PaneState>(initialPaneState)
  const [qualityExpanded, setQualityExpanded] = useState(false)
  const [chatHeight, setChatHeightState] = useState(() => loadChatHeight(localStorage, window.innerHeight))
  const [chatAvailableHeight, setChatAvailableHeight] = useState(window.innerHeight)
  const chatHeightRef = useRef(chatHeight)
  const paneRef = useRef<HTMLDivElement>(null)
  const reviewBodyRef = useRef<HTMLDivElement>(null)
  const chatDragRef = useRef<{ startY: number; startHeight: number } | null>(null)
  const verifyTargetsRef = useRef<Map<string, LineComment>>(new Map())
  const pendingSubmitRef = useRef<{ verdict: Verdict; comment: string } | null>(null)
  const submitInFlightRef = useRef(false)
  const currentPrRef = useRef(pr)
  const activeReviewOperationIdRef = useRef<string | null>(null)
  const generationStartedAtRef = useRef<number | null>(null)
  const autosaveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const lastSavedSnapshotRef = useRef<string | null>(null)
  const generatedBaselineRef = useRef<ReviewResult | null>(null)
  const nextSaveIdRef = useRef(0)
  const inFlightSaveRef = useRef<InFlightSave | null>(null)
  const pendingAutosaveRef = useRef<PendingAutosave | null>(null)
  const saveWatchdogRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const submitWatchdogRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const deleteWatchdogRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const deleteDraftStateRef = useRef<DraftPresentState | null>(null)
  const suppressOutgoingAutosaveRef = useRef(false)
  const allocateSaveId = useCallback(() => ++nextSaveIdRef.current, [])

  const clearAllWatchdogs = useCallback(() => {
    clearWatchdog(saveWatchdogRef)
    clearWatchdog(submitWatchdogRef)
    clearWatchdog(deleteWatchdogRef)
  }, [])

  useEffect(() => {
    currentPrRef.current = pr
  }, [pr])

  useEffect(() => clearAllWatchdogs, [clearAllWatchdogs])

  useEffect(() => {
    dispatch({ type: 'reset', hasPr: Boolean(pr) })
    setReviewActivity(emptyReviewActivity())
    setFocusAreasOverride('')
    setCustomInstructionsOverride('')
    setChunkedMode(false)
    pendingSubmitRef.current = null
    submitInFlightRef.current = false
    setSaving(false)
    setSubmitting(false)
    setDeleting(false)
    setFocusedCommentIdx(0)
    setCommentFocusRequestId(0)
    setChatVisible(false)
    setSelectedContext('')
    setPendingChatMessage(null)
    setQualityExpanded(false)
    setDeepSetup(null)
    setDeepBusy(false)
    setIntellijAssisted(false)
    activeReviewOperationIdRef.current = null
    generationStartedAtRef.current = null
    lastSavedSnapshotRef.current = null
    generatedBaselineRef.current = null
    inFlightSaveRef.current = null
    pendingAutosaveRef.current = null
    deleteDraftStateRef.current = null
    if (autosaveTimerRef.current !== null) {
      clearTimeout(autosaveTimerRef.current)
      autosaveTimerRef.current = null
    }
    clearAllWatchdogs()
  }, [pr, clearAllWatchdogs])

  useEffect(() => {
    const cleanup = onHostMessage((message) => {
      const activePr = currentPrRef.current
      if ('prKey' in message && message.prKey && (!activePr || message.prKey !== prKey(activePr))) return

      switch (message.type) {
        case 'deepReviewPrepared':
          if (message.operationId !== activeReviewOperationIdRef.current) break
          setDeepSetup(message)
          setDeepBusy(false)
          activeReviewOperationIdRef.current = null
          generationStartedAtRef.current = null
          setReviewActivity(emptyReviewActivity())
          dispatch({ type: 'restoreBeforeDeepPause', previous: beforeDeepPauseRef.current })
          break
        case 'retainedDeepReviews':
          if (message.operationId !== maintenanceOperationRef.current) break
          maintenanceOperationRef.current = null
          setRetainedDeepReviews(message.retained)
          setDeepMaintenanceError('')
          break
        case 'deepReviewMaintenanceError':
          if (message.operationId !== maintenanceOperationRef.current) break
          maintenanceOperationRef.current = null
          setDeepMaintenanceError(message.message)
          break
        case 'draftLoading':
          dispatch({ type: 'draftLoading' })
          break

        case 'draftLoaded': {
          generatedBaselineRef.current = null
          const diff = message.diff ?? message.validationDiff ?? ''
          const validationDiff = message.validationDiff ?? diff
          const normalizedResult = message.result
            ? normalizeReviewResult(message.result, validationDiff)
            : undefined
          if (message.prState === 'DRAFT_PRESENT' && normalizedResult) {
            lastSavedSnapshotRef.current = message.recoveryPending ? null : reviewSnapshot(normalizedResult)
            setFocusedCommentIdx(0)
          }
          dispatch({
            type: 'draftLoaded',
            prState: message.prState,
            result: normalizedResult,
            reviewId: message.reviewId,
            staleCommits: message.staleCommits,
            importedFromGitHub: message.importedFromGitHub,
            diff,
            validationDiff,
            status: message.status,
            providerReadiness: message.providerReadiness,
          })
          break
        }

        case 'reviewGenerating': {
          const nowMs = Date.now()
          setReviewActivity((current) => appendReviewActivity(current, message.message, nowMs))
          break
        }

        case 'reviewChunk':
          break

        case 'reviewResult': {
          setDeepSetup(null)
          setDeepBusy(false)
          const diff = message.diff ?? message.validationDiff ?? ''
          const validationDiff = message.validationDiff ?? diff
          const result = normalizeReviewResult(message.result, validationDiff)
          const nowMs = Date.now()
          const generationElapsedSec = generationStartedAtRef.current == null
            ? undefined
            : Math.max(0, Math.round((nowMs - generationStartedAtRef.current) / 1000))
          const inlineCommentCount = validateComments(validationDiff, result.lineComments).adjusted.length
          setFocusedCommentIdx((index) => Math.min(index, Math.max(0, inlineCommentCount - 1)))
          activeReviewOperationIdRef.current = null
          generationStartedAtRef.current = null
          generatedBaselineRef.current = result
          setReviewActivity((current) =>
            finishReviewActivity(current, 'completed', 'Review complete', nowMs),
          )
          dispatch({ type: 'reviewResult', result, diff, validationDiff, generationElapsedSec })
          break
        }

        case 'reviewError': {
          setDeepBusy(false)
          const nowMs = Date.now()
          activeReviewOperationIdRef.current = null
          generationStartedAtRef.current = null
          setReviewActivity((current) =>
            finishReviewActivity(current, 'failed', 'Review failed', nowMs),
          )
          dispatch({ type: 'reviewError', message: message.message })
          break
        }

        case 'validationDiffUpdated':
          if (generatedBaselineRef.current) {
            generatedBaselineRef.current = normalizeReviewResult(
              generatedBaselineRef.current,
              message.validationDiff,
            )
          }
          dispatch({ type: 'validationDiffUpdated', validationDiff: message.validationDiff })
          break

        case 'draftSaved': {
          const inFlight = inFlightSaveRef.current
          if (!inFlight || message.saveId !== inFlight.saveId) break
          lastSavedSnapshotRef.current = inFlight.snapshot
          inFlightSaveRef.current = null
          clearWatchdog(saveWatchdogRef)
          setSaving(false)
          if (message.commentsDropped && !inFlight.isAuto) {
            toast.warning('Some comments were dropped', {
              description: 'Outdated line references were removed when saving to GitHub.',
            })
          }
          dispatch({ type: 'draftSaved', reviewId: message.reviewId })

          const pending = pendingSubmitRef.current
          const submitPr = currentPrRef.current
          if (pending && submitPr) {
            pendingSubmitRef.current = null
            setSubmitting(true)
            armWatchdog(submitWatchdogRef, () => {
              submitInFlightRef.current = false
              setSubmitting(false)
              dispatch({
                type: 'reviewSubmitError',
                message: 'The host did not respond in time. Check your connection and try again.',
              })
            })
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
          if (message.saveId !== inFlightSaveRef.current?.saveId) break
          inFlightSaveRef.current = null
          clearWatchdog(saveWatchdogRef)
          setSaving(false)
          pendingSubmitRef.current = null
          submitInFlightRef.current = false
          dispatch({ type: 'saveError', message: message.message })
          break

        case 'reviewSubmitted':
          clearWatchdog(submitWatchdogRef)
          submitInFlightRef.current = false
          setSubmitting(false)
          dispatch({ type: 'reviewSubmitted' })
          break

        case 'reviewSubmitError':
          clearWatchdog(submitWatchdogRef)
          submitInFlightRef.current = false
          setSubmitting(false)
          dispatch({ type: 'reviewSubmitError', message: message.message })
          break

        case 'draftDeleted':
          clearWatchdog(deleteWatchdogRef)
          setDeleting(false)
          deleteDraftStateRef.current = null
          dispatch({ type: 'draftDeleted' })
          break

        case 'draftDeleteError':
          clearWatchdog(deleteWatchdogRef)
          setDeleting(false)
          dispatch({
            type: 'draftDeleteError',
            message: message.message,
            draft: deleteDraftStateRef.current,
          })
          break

        default:
          break
      }
    })
    return cleanup
  }, [])

  const showChat = Boolean(pr)

  useEffect(() => {
    if (showChat && chatVisible) {
      sendToHost({ type: 'webviewLayoutChanged', reason: 'chat-panel' })
    }
  }, [showChat, chatVisible, chatHeight])

  useEffect(() => {
    const pane = paneRef.current
    if (!pane) return
    const updateBounds = () => {
      const containerHeight = pane.getBoundingClientRect().height || window.innerHeight
      const reviewBodyHeight = reviewBodyRef.current?.getBoundingClientRect().height ?? containerHeight
      const availableHeight = effectiveChatAvailableHeight(
        containerHeight,
        chatHeightRef.current,
        reviewBodyHeight,
      )
      setChatAvailableHeight(availableHeight)
      const clamped = clampChatHeight(chatHeightRef.current, availableHeight)
      chatHeightRef.current = clamped
      setChatHeightState(clamped)
      localStorage.setItem(CHAT_HEIGHT_KEY, String(clamped))
    }
    updateBounds()
    if (typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(updateBounds)
    observer.observe(pane)
    if (reviewBodyRef.current) observer.observe(reviewBodyRef.current)
    return () => observer.disconnect()
  }, [chatVisible, showChat, state.kind])

  useEffect(() => {
    if (!pr) {
      setSelectedContext('')
      return
    }

    function handleMouseUp(event: MouseEvent) {
      if ((event.target as HTMLElement).closest?.('.chat-pane__input')) return
      const text = window.getSelection()?.toString().trim() ?? ''
      if (text) setSelectedContext(text)
    }

    document.addEventListener('mouseup', handleMouseUp)
    return () => document.removeEventListener('mouseup', handleMouseUp)
  }, [pr])

  const handleChatResizeMove = useCallback((event: PointerEvent) => {
    if (!chatDragRef.current) return
    const delta = chatDragRef.current.startY - event.clientY
    const nextHeight = clampChatHeight(
      chatDragRef.current.startHeight + delta,
      chatAvailableHeight,
    )
    chatHeightRef.current = nextHeight
    setChatHeightState(nextHeight)
  }, [chatAvailableHeight])

  function handleChatResizeUp() {
    chatDragRef.current = null
    document.body.style.cursor = ''
    document.body.style.userSelect = ''
    localStorage.setItem(CHAT_HEIGHT_KEY, String(chatHeightRef.current))
    document.removeEventListener('pointermove', handleChatResizeMove)
    document.removeEventListener('pointerup', handleChatResizeUp)
  }

  const result = resultOf(state)
  const diff = diffOf(state)
  const validationDiff = validationDiffOf(state)
  const diffUnavailable = useMemo(
    () => parseDiffSafely(validationDiff || diff).status === 'unrenderable',
    [validationDiff, diff],
  )
  const partition = useMemo(
    () => validateComments(validationDiff, result?.lineComments ?? []),
    [validationDiff, result?.lineComments],
  )
  const qualityReport = useMemo<ReviewQualityReport | null>(
    () => (result ? runReviewQualityCheck(result, validationDiff) : null),
    [result, validationDiff],
  )
  const qualityRiskCount = qualityReport
    ? qualityReport.issues.reduce((count, issue) => count + issue.count, 0)
    : 0
  const preflight = useMemo(() => summarizeDiffPreflight(validationDiff), [validationDiff])
  const recommendation = useMemo(
    () => chunkRecommendation(preflight, isDiffTruncated(validationDiff) || isDiffTruncated(diff)),
    [preflight, validationDiff, diff],
  )
  const savableResult = state.kind === 'reviewUnsaved' || state.kind === 'draftPresent'
    ? state.result
    : null
  const savableSnapshot = savableResult ? reviewSnapshot(savableResult) : null
  const autosaveDirty = isReviewDirty(savableSnapshot, lastSavedSnapshotRef.current)

  useEffect(() => {
    const dirty = autosaveDirty || state.kind === 'reviewUnsaved' || state.kind === 'saveError'
    onDirtyStateChange?.(dirty)
  }, [autosaveDirty, state.kind, onDirtyStateChange])

  const dispatchSave = useCallback(
    (targetPr: PR, review: ReviewResult, orphans: LineComment[], isAuto: boolean) => {
      const saveId = allocateSaveId()
      inFlightSaveRef.current = {
        saveId,
        prKey: prKey(targetPr),
        snapshot: reviewSnapshot(review),
        isAuto,
      }
      if (autosaveTimerRef.current !== null) {
        clearTimeout(autosaveTimerRef.current)
        autosaveTimerRef.current = null
      }
      setSaving(true)
      armWatchdog(saveWatchdogRef, () => {
        if (inFlightSaveRef.current?.saveId !== saveId) return
        inFlightSaveRef.current = null
        if (pendingSubmitRef.current !== null) submitInFlightRef.current = false
        setSaving(false)
        pendingSubmitRef.current = null
        dispatch({
          type: 'saveError',
          message: 'The host did not respond in time. Check your connection and try again.',
        })
      })
      sendToHost({
        type: 'saveDraft',
        number: targetPr.number,
        owner: targetPr.owner,
        repo: targetPr.repo,
        saveId,
        result: review,
        generatedResult: generatedBaselineRef.current ?? undefined,
        orphans,
      })
    },
    [allocateSaveId],
  )

  useEffect(() => {
    if (!pr || !savableResult || !savableSnapshot || !autosaveDirty) {
      pendingAutosaveRef.current = null
      return
    }
    pendingAutosaveRef.current = {
      pr,
      result: savableResult,
      orphans: partition.orphans,
      snapshot: savableSnapshot,
    }
    if (saving || submitting || deleting) return
    const delay = autosaveDelayMs(state.kind === 'reviewUnsaved' ? 'reviewUnsaved' : 'draftPresent')
    if (delay === 0) {
      dispatchSave(pr, savableResult, partition.orphans, true)
      return
    }
    autosaveTimerRef.current = setTimeout(() => {
      autosaveTimerRef.current = null
      dispatchSave(pr, savableResult, partition.orphans, true)
    }, delay)
    return () => {
      if (autosaveTimerRef.current !== null) {
        clearTimeout(autosaveTimerRef.current)
        autosaveTimerRef.current = null
      }
    }
  }, [
    pr,
    savableResult,
    savableSnapshot,
    autosaveDirty,
    saving,
    submitting,
    deleting,
    state,
    partition,
    dispatchSave,
  ])

  useEffect(() => {
    function flushPending() {
      const pending = pendingAutosaveRef.current
      if (!pending || pending.snapshot === lastSavedSnapshotRef.current) return
      const inFlight = inFlightSaveRef.current
      if (inFlight?.prKey === prKey(pending.pr) && inFlight.snapshot === pending.snapshot) return
      if (autosaveTimerRef.current !== null) {
        clearTimeout(autosaveTimerRef.current)
        autosaveTimerRef.current = null
      }
      dispatchSave(pending.pr, pending.result, pending.orphans, true)
    }
    function onVisibilityChange() {
      if (document.visibilityState === 'hidden') flushPending()
    }
    document.addEventListener('visibilitychange', onVisibilityChange)
    window.addEventListener('pagehide', flushPending)
    return () => {
      document.removeEventListener('visibilitychange', onVisibilityChange)
      window.removeEventListener('pagehide', flushPending)
    }
  }, [dispatchSave])

  useEffect(() => {
    return () => {
      if (autosaveTimerRef.current !== null) {
        clearTimeout(autosaveTimerRef.current)
        autosaveTimerRef.current = null
      }
      const suppressSave = suppressOutgoingAutosaveRef.current
      suppressOutgoingAutosaveRef.current = false
      const pending = suppressSave ? null : pendingAutosaveRef.current
      const inFlight = inFlightSaveRef.current
      const alreadyInFlight = pending
        && inFlight?.prKey === prKey(pending.pr)
        && inFlight.snapshot === pending.snapshot
      if (pending && pending.snapshot !== lastSavedSnapshotRef.current && !alreadyInFlight) {
        const saveId = allocateSaveId()
        sendToHost({
          type: 'saveDraft',
          number: pending.pr.number,
          owner: pending.pr.owner,
          repo: pending.pr.repo,
          saveId,
          result: pending.result,
          generatedResult: generatedBaselineRef.current ?? undefined,
          orphans: pending.orphans,
        })
      }
      pendingAutosaveRef.current = null
    }
  }, [pr, allocateSaveId])

  function handleGenerate(ordinary = false) {
    if (!pr) return
    setDeepSetup(null)
    beforeDeepPauseRef.current = state
    setDeepBusy(intellijAssisted && !ordinary)
    const focusAreas = focusAreasOverride.trim()
    const customInstructions = customInstructionsOverride.trim()

    if (chunkedMode) {
      const sourceDiff = validationDiffOf(state)
      if (!sourceDiff.trim()) {
        toast.error('Chunked mode needs a loaded diff. Reload the PR and try again.')
        return
      }
      const operationId = newOperationId()
      const nowMs = Date.now()
      activeReviewOperationIdRef.current = operationId
      generationStartedAtRef.current = nowMs
      setReviewActivity((current) =>
        startReviewActivity(current, 'Preparing engine-owned review batches…', nowMs),
      )
      dispatch({ type: 'startGenerating' })
      sendToHost({
        type: 'generateReview',
        operationId,
        number: pr.number,
        owner: pr.owner,
        repo: pr.repo,
        diff: sourceDiff,
        chunkedReview: true,
        ...(intellijAssisted && !ordinary ? { intellijAssisted: true } : {}),
        focusAreas: focusAreas || undefined,
        customInstructions: customInstructions || undefined,
      })
      return
    }

    const operationId = newOperationId()
    const nowMs = Date.now()
    activeReviewOperationIdRef.current = operationId
    generationStartedAtRef.current = nowMs
    setReviewActivity((current) => startReviewActivity(current, 'Starting review…', nowMs))
    dispatch({ type: 'startGenerating' })
    sendToHost({
      type: 'generateReview',
      operationId,
      number: pr.number,
      owner: pr.owner,
      repo: pr.repo,
      ...(intellijAssisted && !ordinary ? { intellijAssisted: true } : {}),
      focusAreas: focusAreas || undefined,
      customInstructions: customInstructions || undefined,
    })
  }

  function handleCancel() {
    if (!pr) return
    const operationId = activeReviewOperationIdRef.current ?? deepSetup?.operationId
    if (!operationId) return
    if (deepSetup || deepBusy) {
      sendToHost({ type: 'cancelReview', operationId })
      activeReviewOperationIdRef.current = null
      generationStartedAtRef.current = null
      setDeepSetup(null)
      setDeepBusy(false)
      setReviewActivity(emptyReviewActivity())
      dispatch({ type: 'restoreBeforeDeepPause', previous: beforeDeepPauseRef.current })
      return
    }
    setReviewActivity((current) =>
      finishReviewActivity(current, 'cancelled', 'Review cancelled', Date.now()),
    )
    sendToHost({ type: 'cancelReview', operationId })
    activeReviewOperationIdRef.current = null
    generationStartedAtRef.current = null
    dispatch({ type: 'draftLoading' })
    sendToHost({ type: 'selectPR', number: pr.number, owner: pr.owner, repo: pr.repo })
  }

  function handleSave() {
    if (!pr || !result) return
    dispatchSave(pr, result, partition.orphans, false)
  }

  function handleDelete() {
    if (!pr) return
    const draft = state.kind === 'draftPresent'
      ? state
      : state.kind === 'deleteError'
        ? state.draft
        : null
    if (!draft) return
    deleteDraftStateRef.current = draft
    setDeleting(true)
    armWatchdog(deleteWatchdogRef, () => {
      setDeleting(false)
      dispatch({
        type: 'draftDeleteError',
        message: 'The host did not respond in time. The draft may still exist on GitHub.',
        draft,
      })
    })
    sendToHost({ type: 'deleteDraft', number: pr.number, owner: pr.owner, repo: pr.repo })
  }

  function handleReloadDraft() {
    if (!pr) return
    dispatch({ type: 'draftLoading' })
    sendToHost({ type: 'selectPR', number: pr.number, owner: pr.owner, repo: pr.repo })
  }

  function handleSubmit(verdict: Verdict, comment = '') {
    if (!pr || submitInFlightRef.current) return
    submitInFlightRef.current = true
    const needsSaveFirst = state.kind === 'reviewUnsaved' || state.kind === 'saveError' || autosaveDirty
    if (result && needsSaveFirst) {
      pendingSubmitRef.current = { verdict, comment }
      dispatchSave(pr, result, partition.orphans, false)
      return
    }
    setSubmitting(true)
    armWatchdog(submitWatchdogRef, () => {
      submitInFlightRef.current = false
      setSubmitting(false)
      dispatch({
        type: 'reviewSubmitError',
        message: 'The host did not respond in time. Check your connection and try again.',
      })
    })
    sendToHost({
      type: 'submitReview',
      number: pr.number,
      owner: pr.owner,
      repo: pr.repo,
      verdict,
      comment,
    })
  }

  function inlineToOriginal(index: number): number {
    if (!result) return -1
    const target = partition.adjusted[index]
    return target ? result.lineComments.indexOf(target) : -1
  }

  function updateAtOriginal(index: number, update: (comment: LineComment) => LineComment | null) {
    if (!result || index < 0 || index >= result.lineComments.length) return
    dispatch({ type: 'updateComment', index, comment: update(result.lineComments[index]) })
  }

  const editCommentHandlers = {
    onEditComment: (index: number, body: string) => {
      updateAtOriginal(inlineToOriginal(index), (comment) => ({ ...comment, body }))
    },
    onDeleteComment: (index: number) => {
      setFocusedCommentIdx((focusedIndex) =>
        focusedIndexAfterCommentDeletion(focusedIndex, index, partition.adjusted.length),
      )
      updateAtOriginal(inlineToOriginal(index), () => null)
    },
    onAddComment: (comment: LineComment) => {
      if (state.kind !== 'draftPresent' && state.kind !== 'reviewUnsaved') return
      setFocusedCommentIdx(partition.adjusted.length)
      dispatch({ type: 'addComment', comment })
    },
  }

  function orphanToOriginal(orphan: LineComment): number {
    return result ? result.lineComments.indexOf(orphan) : -1
  }

  const orphanHandlers = {
    onEditOrphan: (orphan: LineComment, body: string) => {
      updateAtOriginal(orphanToOriginal(orphan), (comment) => ({ ...comment, body }))
    },
    onDeleteOrphan: (orphan: LineComment) => {
      updateAtOriginal(orphanToOriginal(orphan), () => null)
    },
  }

  function handleVerifyComment(comment: LineComment) {
    const { question, context } = buildVerifyCommentPrompt(comment, validationDiff || diff)
    if (!chatVisible) setChatVisible(true)
    const id = Date.now()
    const token = `verify-${id}`
    verifyTargetsRef.current.set(token, comment)
    setPendingChatMessage({
      q: question,
      ctx: context,
      id,
      token,
      contextSummary: ['draft comment', 'diff excerpt', 'PR worktree (read-only)'],
    })
  }

  function handleApplyVerifyAction(verify: VerifyResult, token: string) {
    const target = verifyTargetsRef.current.get(token)
    if (!target || !result) return

    const index = resolveVerifyTarget(result.lineComments, target)
    if (index < 0) return

    if (verify.action === 'delete') {
      const inlineIndex = partition.adjusted.indexOf(result.lineComments[index])
      updateAtOriginal(index, () => null)
      if (inlineIndex >= 0) {
        setFocusedCommentIdx((focusedIndex) =>
          focusedIndexAfterCommentDeletion(focusedIndex, inlineIndex, partition.adjusted.length),
        )
      }
      return
    }
    const replacement = verify.replacementComment?.trim()
    if (verify.action === 'revise' && replacement) {
      updateAtOriginal(index, (comment) => ({ ...comment, body: replacement }))
    }
  }

  function handleSuggestFixComment(comment: LineComment) {
    const { question, context } = buildExampleFixPrompt(comment, validationDiff || diff)
    if (!chatVisible) setChatVisible(true)
    setPendingChatMessage({
      q: question,
      ctx: context,
      id: Date.now(),
      contextSummary: ['draft comment', 'diff excerpt'],
    })
  }

  function applyQualityRepair(action: ReviewQualityAction) {
    if (!qualityReport || !result) return
    const comments = applyReviewQualityRepairs(result, qualityReport, [action]).lineComments
    dispatch({
      type: 'replaceComments',
      kinds: ['draftPresent', 'reviewUnsaved', 'deleteError'],
      comments,
    })
    setQualityExpanded(true)
  }

  function startChatResize(event: ReactPointerEvent) {
    event.preventDefault()
    chatDragRef.current = { startY: event.clientY, startHeight: chatHeight }
    document.body.style.cursor = 'ns-resize'
    document.body.style.userSelect = 'none'
    document.addEventListener('pointermove', handleChatResizeMove)
    document.addEventListener('pointerup', handleChatResizeUp)
  }

  function setChatHeight(height: number) {
    chatHeightRef.current = height
    setChatHeightState(height)
  }

  const discardPendingChanges = useCallback(() => {
    if (inFlightSaveRef.current) return false
    suppressOutgoingAutosaveRef.current = true
    if (autosaveTimerRef.current !== null) {
      clearTimeout(autosaveTimerRef.current)
      autosaveTimerRef.current = null
    }
    pendingAutosaveRef.current = null
    return true
  }, [])

  const hasReview = result !== null
  const showReviewOverrides = state.kind !== 'draftLoading'
    && state.kind !== 'generating'
    && state.kind !== 'merged'
  const contextSummary = chatContextSummary(pr, diff, result, selectedContext)
  const statusMessage = state.kind === 'draftLoading'
    ? 'Checking for a saved review draft'
    : state.kind === 'generating'
      ? formatReviewActivityLabel(activity.entries[activity.entries.length - 1]?.message ?? 'Generating review')
      : state.kind === 'submitted'
        ? 'Review submitted'
        : saving
          ? 'Saving review draft'
          : submitting
            ? 'Submitting review'
            : ''

  return {
    model: {
      intellijAssisted,
      deepSetup: deepSetup?.prKey === (pr ? prKey(pr) : '') ? deepSetup : null,
      retainedDeepReviews,
      deepMaintenanceError,
      deepBusy,
      pr,
      state,
      activity,
      result,
      diff,
      validationDiff,
      diffUnavailable,
      inlineComments: partition.adjusted,
      orphanComments: partition.orphans,
      qualityReport,
      qualityRiskCount,
      preflight,
      recommendation,
      focusAreasOverride,
      customInstructionsOverride,
      chunkedMode,
      showReviewOverrides,
      saving,
      submitting,
      deleting,
      autosaveDirty,
      focusedCommentIdx,
      commentFocusRequestId,
      showChat,
      chatVisible,
      selectedContext,
      pendingChatMessage,
      chatHeight,
      chatAvailableHeight,
      contextSummary,
      qualityExpanded,
      hasReview,
      statusMessage,
    },
    actions: {
      setIntellijAssisted,
      continueDeepReview: (server: string) => {
        if (!pr || !deepSetup || deepSetup.prKey !== prKey(pr) || deepBusy) return
        const operationId = newOperationId()
        activeReviewOperationIdRef.current = operationId
        setDeepBusy(true)
        generationStartedAtRef.current = Date.now()
        dispatch({ type: 'startGenerating' })
        sendToHost({ type: 'continueDeepReview', operationId, number: pr.number, owner: pr.owner,
          repo: pr.repo, retainedId: deepSetup.retainedId, server })
      },
      ordinaryReview: () => { setIntellijAssisted(false); handleGenerate(true) },
      listDeepReviews: () => {
        const operationId = newOperationId()
        maintenanceOperationRef.current = operationId
        sendToHost({ type: 'listDeepReviews', operationId })
      },
      cleanupDeepReview: (retainedId: string) => {
        const operationId = newOperationId()
        maintenanceOperationRef.current = operationId
        sendToHost({ type: 'cleanupDeepReview', operationId, retainedId, projectClosed: true })
      },
      setFocusAreasOverride,
      setCustomInstructionsOverride,
      setChunkedMode,
      generate: () => handleGenerate(),
      cancel: handleCancel,
      save: handleSave,
      deleteDraft: handleDelete,
      reloadDraft: handleReloadDraft,
      keepDraft: () => dispatch({ type: 'keepDraft' }),
      reanchorDraft: () => dispatch({ type: 'reanchorDraft' }),
      submit: handleSubmit,
      verifyComment: handleVerifyComment,
      suggestFixComment: handleSuggestFixComment,
      applyVerifyAction: handleApplyVerifyAction,
      editCommentHandlers,
      orphanHandlers,
      runQualityCheck: () => setQualityExpanded(true),
      applyQualityRepair,
      collapseQualityCheck: () => setQualityExpanded(false),
      focusComment: (index) => setFocusedCommentIdx(index),
      focusPreviousComment: () => {
        setFocusedCommentIdx((index) => adjacentCommentIndex(index, -1, partition.adjusted.length))
        setCommentFocusRequestId((requestId) => requestId + 1)
      },
      focusNextComment: () => {
        setFocusedCommentIdx((index) => adjacentCommentIndex(index, 1, partition.adjusted.length))
        setCommentFocusRequestId((requestId) => requestId + 1)
      },
      toggleChat: () => setChatVisible((visible) => !visible),
      openChat: () => setChatVisible(true),
      clearSelectedContext: () => setSelectedContext(''),
      askAboutSelection: (question) => {
        if (!chatVisible) setChatVisible(true)
        setPendingChatMessage({ q: question, ctx: selectedContext, id: Date.now() })
      },
      pendingMessageSent: () => setPendingChatMessage(null),
      setChatHeight,
      commitChatHeight: (height) => localStorage.setItem(CHAT_HEIGHT_KEY, String(height)),
      startChatResize,
      openPr: () => {
        if (pr) sendToHost({ type: 'openUrl', url: pr.htmlUrl })
      },
      openSettings: () => sendToHost({ type: 'openSettings' }),
      openAuthGuide: () => {
        sendToHost({ type: 'openUrl', url: 'https://cli.github.com/manual/gh_auth_login' })
      },
      discardPendingChanges,
    },
    refs: {
      paneRef,
      reviewBodyRef,
    },
  }
}
