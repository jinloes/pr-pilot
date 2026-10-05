import { useCallback, useEffect, type Dispatch } from 'react'
import {
  sendToHost,
  type LineComment,
  type PR,
  type ReviewResult,
} from '../../bridge/types'
import { autosaveDelayMs, isReviewDirty, reviewSnapshot } from '@/lib/autosave'
import {
  armWatchdog,
  prKey,
  type InFlightSave,
  type PendingAutosave,
  type WatchdogRef,
} from './reviewControllerState'
import type { PaneState, ReviewStateEvent, Verdict } from './reviewState'

type Ref<T> = { current: T }

interface UseReviewAutosaveOptions {
  pr: PR | null
  state: PaneState
  partitionOrphans: LineComment[]
  saving: boolean
  submitting: boolean
  deleting: boolean
  onDirtyStateChange?: (dirty: boolean) => void
  pendingSubmitRef: Ref<{ verdict: Verdict; comment: string } | null>
  submitInFlightRef: Ref<boolean>
  lastSavedSnapshotRef: Ref<string | null>
  generatedBaselineRef: Ref<ReviewResult | null>
  inFlightSaveRef: Ref<InFlightSave | null>
  pendingAutosaveRef: Ref<PendingAutosave | null>
  autosaveTimerRef: Ref<ReturnType<typeof setTimeout> | null>
  saveWatchdogRef: WatchdogRef
  suppressOutgoingAutosaveRef: Ref<boolean>
  allocateSaveId: () => number
  dispatch: Dispatch<ReviewStateEvent>
  setSaving: (saving: boolean) => void
}

export function useReviewAutosave({
  pr,
  state,
  partitionOrphans,
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
}: UseReviewAutosaveOptions) {
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
      orphans: partitionOrphans,
      snapshot: savableSnapshot,
    }
    if (saving || submitting || deleting) return
    const delay = autosaveDelayMs(state.kind === 'reviewUnsaved' ? 'reviewUnsaved' : 'draftPresent')
    if (delay === 0) {
      dispatchSave(pr, savableResult, partitionOrphans, true)
      return
    }
    autosaveTimerRef.current = setTimeout(() => {
      autosaveTimerRef.current = null
      dispatchSave(pr, savableResult, partitionOrphans, true)
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
    partitionOrphans,
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

  return { autosaveDirty, dispatchSave, discardPendingChanges }
}
