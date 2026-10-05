import { useCallback, useEffect, useMemo, useReducer, useRef, useState } from 'react'
import type { PointerEvent as ReactPointerEvent } from 'react'
import { sendToHost, type LineComment, type ReviewResult, type DeepReviewPreparedMessage, type RetainedDeepReview } from '../../bridge/types'
import { MAX_REPOSITORY_INSTRUCTIONS } from '../../bridge/validation'
import { coverageGain, parseDiffCoverage } from '@/lib/diffCoverage'
import { parseDiffSafely } from '@/lib/diffParse'
import { applyReviewQualityRepairs, runReviewQualityCheck, type ReviewQualityAction, type ReviewQualityReport } from '@/lib/reviewQuality'
import { validateComments } from '@/lib/validateComments'
import type { VerifyResult } from '../ChatPane/structuredResult'
import { CHAT_HEIGHT_KEY, clampChatHeight, loadChatHeight } from './chatHeight'
import { adjacentCommentIndex, focusedIndexAfterCommentDeletion } from './commentNavigation'
import { diffOf, initialPaneState, resultOf, reviewReducer, validationDiffOf, type DraftPresentState, type PaneState, type Verdict } from './reviewState'
import { emptyReviewActivity, finishReviewActivity, formatReviewActivityLabel, type ReviewActivity } from './reviewActivity'
import { buildExampleFixPrompt, buildVerifyCommentPrompt, resolveVerifyTarget } from './verifyPrompt'
import { publishedBodySections } from './publishBody'
import {
  armWatchdog,
  clearWatchdog,
  chatContextSummary,
  chunkRecommendation,
  newOperationId,
  prKey,
  summarizeDiffPreflight,
  type PendingChatMessage,
  type InFlightSave,
  type PendingAutosave,
  type ReviewController,
  type UseReviewControllerProps,
} from './reviewControllerState'
import { useReviewAutosave } from './useReviewAutosave'
import { useReviewChatEffects } from './useReviewChatEffects'
import { useReviewGeneration } from './useReviewGeneration'
import { useReviewHostMessages } from './useReviewHostMessages'

export type {
  ChunkRecommendation,
  DiffPreflight,
  PendingChatMessage,
  ReviewActions,
  ReviewController,
  ReviewRefs,
  ReviewViewModel,
} from './reviewControllerState'
export { MUTATION_WATCHDOG_MS, SELECTION_CAPTURE_DEBOUNCE_MS } from './reviewControllerState'

export function useReviewController({
  pr,
  onDirtyStateChange,
}: UseReviewControllerProps): ReviewController {
  const [state, dispatch] = useReducer(reviewReducer, initialPaneState)
  const [activity, setReviewActivity] = useState<ReviewActivity>(emptyReviewActivity)
  const [focusAreasOverride, setFocusAreasOverride] = useState('')
  const [customInstructionsOverride, setCustomInstructionsOverride] = useState('')
  const [repositoryInstructions, setRepositoryInstructions] = useState('')
  const [repositoryInstructionsDraft, setRepositoryInstructionsDraftState] = useState('')
  const [repositoryInstructionsSaving, setRepositoryInstructionsSaving] = useState(false)
  const [repositoryInstructionsError, setRepositoryInstructionsError] = useState('')
  const [repositoryInstructionsSaved, setRepositoryInstructionsSaved] = useState(false)
  const repositoryInstructionsRef = useRef('')
  const repositoryInstructionsDraftRef = useRef('')
  const repositoryInstructionsWatchdogRef = useRef<ReturnType<typeof setTimeout> | null>(null)
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
  const [intellijAssistedEnabled, setIntellijAssistedEnabled] = useState(false)
  const [deepSetup, setDeepSetup] = useState<DeepReviewPreparedMessage | null>(null)
  const [retainedDeepReviews, setRetainedDeepReviews] = useState<RetainedDeepReview[]>([])
  const [deepMaintenanceError, setDeepMaintenanceError] = useState('')
  const [deepBusy, setDeepBusy] = useState(false)
  const maintenanceOperationRef = useRef<string | null>(null)
  const beforeDeepPauseRef = useRef<PaneState>(initialPaneState)
  const [qualityExpanded, setQualityExpanded] = useState(false)
  const [commentsMovedToBody, setCommentsMovedToBody] = useState(false)
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
    clearWatchdog(repositoryInstructionsWatchdogRef)
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
    clearWatchdog(repositoryInstructionsWatchdogRef)
    repositoryInstructionsRef.current = ''
    repositoryInstructionsDraftRef.current = ''
    setRepositoryInstructions('')
    setRepositoryInstructionsDraftState('')
    setRepositoryInstructionsSaving(false)
    setRepositoryInstructionsError('')
    setRepositoryInstructionsSaved(false)
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
    setCommentsMovedToBody(false)
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

  useReviewHostMessages({
    currentPrRef,
    activeReviewOperationIdRef,
    generationStartedAtRef,
    maintenanceOperationRef,
    beforeDeepPauseRef,
    repositoryInstructionsRef,
    repositoryInstructionsDraftRef,
    generatedBaselineRef,
    lastSavedSnapshotRef,
    inFlightSaveRef,
    pendingSubmitRef,
    submitInFlightRef,
    deleteDraftStateRef,
    saveWatchdogRef,
    submitWatchdogRef,
    deleteWatchdogRef,
    repositoryInstructionsWatchdogRef,
    dispatch,
    setDeepSetup,
    setDeepBusy,
    setReviewActivity,
    setRetainedDeepReviews,
    setDeepMaintenanceError,
    setRepositoryInstructions,
    setRepositoryInstructionsDraftState,
    setRepositoryInstructionsSaving,
    setRepositoryInstructionsError,
    setRepositoryInstructionsSaved,
    setCommentsMovedToBody,
    setIntellijAssistedEnabled,
    setIntellijAssisted,
    setFocusedCommentIdx,
    setSaving,
    setSubmitting,
    setDeleting,
  })

  const showChat = Boolean(pr)

  useReviewChatEffects({
    pr,
    showChat,
    chatVisible,
    chatHeight,
    stateKind: state.kind,
    paneRef,
    reviewBodyRef,
    chatHeightRef,
    setChatAvailableHeight,
    setChatHeightState,
    setSelectedContext,
  })

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
  const reviewCoverage = useMemo(() => parseDiffCoverage(diff), [diff])
  const chunkCoverage = useMemo(() => parseDiffCoverage(validationDiff), [validationDiff])
  const recommendation = useMemo(
    () => chunkRecommendation(preflight, reviewCoverage, chunkCoverage),
    [preflight, reviewCoverage, chunkCoverage],
  )
  const reviewCoverageGain = coverageGain(reviewCoverage, chunkCoverage)
  const publishSections = useMemo(
    () => publishedBodySections(result?.lineComments ?? [], partition.orphans),
    [result?.lineComments, partition.orphans],
  )
  const { autosaveDirty, dispatchSave, discardPendingChanges } = useReviewAutosave({
    pr,
    state,
    partitionOrphans: partition.orphans,
    saving,
    submitting,
    deleting,
    onDirtyStateChange,
    pendingSubmitRef,
    submitInFlightRef,
    lastSavedSnapshotRef,
    generatedBaselineRef,
    inFlightSaveRef,
    pendingAutosaveRef,
    autosaveTimerRef,
    saveWatchdogRef,
    suppressOutgoingAutosaveRef,
    allocateSaveId,
    dispatch,
    setSaving,
  })

  const handleGenerate = useReviewGeneration({
    pr,
    state,
    chunkedMode,
    intellijAssisted,
    intellijAssistedEnabled,
    focusAreasOverride,
    customInstructionsOverride,
    beforeDeepPauseRef,
    activeReviewOperationIdRef,
    generationStartedAtRef,
    setDeepSetup,
    setDeepBusy,
    setReviewActivity,
    dispatch,
  })

  function setRepositoryInstructionsDraft(value: string) {
    repositoryInstructionsDraftRef.current = value
    setRepositoryInstructionsDraftState(value)
    setRepositoryInstructionsSaved(false)
    setRepositoryInstructionsError('')
  }

  function handleSaveRepositoryInstructions() {
    if (!pr || repositoryInstructionsSaving) return
    const instructions = repositoryInstructionsDraft.trim()
    if (instructions.length > MAX_REPOSITORY_INSTRUCTIONS) {
      setRepositoryInstructionsError(
        `Repository instructions are limited to ${MAX_REPOSITORY_INSTRUCTIONS.toLocaleString()} characters.`,
      )
      return
    }
    setRepositoryInstructionsSaving(true)
    setRepositoryInstructionsSaved(false)
    setRepositoryInstructionsError('')
    armWatchdog(repositoryInstructionsWatchdogRef, () => {
      setRepositoryInstructionsSaving(false)
      setRepositoryInstructionsError('The host did not respond in time. Check your connection and try again.')
    })
    sendToHost({
      type: 'saveRepositoryInstructions',
      number: pr.number,
      owner: pr.owner,
      repo: pr.repo,
      instructions,
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

  const hasReview = result !== null
  const showReviewOverrides = state.kind !== 'draftLoading'
    && state.kind !== 'generating'
    && state.kind !== 'merged'
    && state.kind !== 'authError'
    && state.kind !== 'submitted'
  const contextSummary = chatContextSummary(pr, diff, reviewCoverage, result, selectedContext)
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
      intellijAssistedEnabled,
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
      coverageGain: reviewCoverageGain,
      publishSections,
      commentsMovedToBody,
      focusAreasOverride,
      customInstructionsOverride,
      repositoryInstructions,
      repositoryInstructionsDraft,
      repositoryInstructionsSaving,
      repositoryInstructionsError,
      repositoryInstructionsSaved,
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
      setRepositoryInstructionsDraft,
      saveRepositoryInstructions: handleSaveRepositoryInstructions,
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
