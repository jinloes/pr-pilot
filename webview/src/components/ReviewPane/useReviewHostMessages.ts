import { useEffect, type Dispatch, type SetStateAction } from 'react'
import { toast } from 'sonner'
import {
  onHostMessage,
  sendToHost,
  type DeepReviewPreparedMessage,
  type PR,
  type RetainedDeepReview,
  type ReviewResult,
} from '../../bridge/types'
import {
  appendReviewActivity,
  emptyReviewActivity,
  finishReviewActivity,
  type ReviewActivity,
} from './reviewActivity'
import {
  armWatchdog,
  clearWatchdog,
  prKey,
  type InFlightSave,
  type WatchdogRef,
} from './reviewControllerState'
import { normalizeReviewResult, type DraftPresentState, type PaneState, type ReviewStateEvent } from './reviewState'
import { reviewSnapshot } from '@/lib/autosave'
import { validateComments } from '@/lib/validateComments'

type Ref<T> = { current: T }

type SetState<T> = Dispatch<SetStateAction<T>>

interface UseReviewHostMessagesOptions {
  currentPrRef: Ref<PR | null>
  activeReviewOperationIdRef: Ref<string | null>
  generationStartedAtRef: Ref<number | null>
  maintenanceOperationRef: Ref<string | null>
  beforeDeepPauseRef: Ref<PaneState>
  repositoryInstructionsRef: Ref<string>
  repositoryInstructionsDraftRef: Ref<string>
  generatedBaselineRef: Ref<ReviewResult | null>
  lastSavedSnapshotRef: Ref<string | null>
  inFlightSaveRef: Ref<InFlightSave | null>
  pendingSubmitRef: Ref<{ verdict: 'APPROVE' | 'REQUEST_CHANGES' | 'COMMENT'; comment: string } | null>
  submitInFlightRef: Ref<boolean>
  deleteDraftStateRef: Ref<DraftPresentState | null>
  saveWatchdogRef: WatchdogRef
  submitWatchdogRef: WatchdogRef
  deleteWatchdogRef: WatchdogRef
  repositoryInstructionsWatchdogRef: WatchdogRef
  dispatch: Dispatch<ReviewStateEvent>
  setDeepSetup: SetState<DeepReviewPreparedMessage | null>
  setDeepBusy: SetState<boolean>
  setReviewActivity: SetState<ReviewActivity>
  setRetainedDeepReviews: SetState<RetainedDeepReview[]>
  setDeepMaintenanceError: SetState<string>
  setRepositoryInstructions: SetState<string>
  setRepositoryInstructionsDraftState: SetState<string>
  setRepositoryInstructionsSaving: SetState<boolean>
  setRepositoryInstructionsError: SetState<string>
  setRepositoryInstructionsSaved: SetState<boolean>
  setCommentsMovedToBody: SetState<boolean>
  setIntellijAssistedEnabled: SetState<boolean>
  setIntellijAssisted: SetState<boolean>
  setFocusedCommentIdx: Dispatch<SetStateAction<number>>
  setSaving: SetState<boolean>
  setSubmitting: SetState<boolean>
  setDeleting: SetState<boolean>
}

export function useReviewHostMessages({
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
}: UseReviewHostMessagesOptions) {
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
          {
            const remembered = message.repositoryInstructions ?? ''
            // A reload must not discard instructions the reviewer is still editing.
            if (repositoryInstructionsDraftRef.current === repositoryInstructionsRef.current) {
              repositoryInstructionsDraftRef.current = remembered
              setRepositoryInstructionsDraftState(remembered)
            }
            repositoryInstructionsRef.current = remembered
            setRepositoryInstructions(remembered)
          }
          setCommentsMovedToBody(false)
          const assistedEnabled = message.intellijAssistedEnabled === true
          setIntellijAssistedEnabled(assistedEnabled)
          if (!assistedEnabled) setIntellijAssisted(false)
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
          setCommentsMovedToBody(false)
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
          dispatch({
            type: 'reviewResult',
            result,
            diff,
            validationDiff,
            generationElapsedSec,
            ...(message.reviewScope ? { reviewScope: message.reviewScope } : {}),
          })
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
          if (message.commentsDropped) setCommentsMovedToBody(true)
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
          setCommentsMovedToBody(false)
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
          setCommentsMovedToBody(false)
          clearWatchdog(deleteWatchdogRef)
          setDeleting(false)
          deleteDraftStateRef.current = null
          dispatch({ type: 'draftDeleted' })
          break

        case 'repositoryInstructionsSaved':
          clearWatchdog(repositoryInstructionsWatchdogRef)
          repositoryInstructionsRef.current = message.instructions
          repositoryInstructionsDraftRef.current = message.instructions
          setRepositoryInstructions(message.instructions)
          setRepositoryInstructionsDraftState(message.instructions)
          setRepositoryInstructionsSaving(false)
          setRepositoryInstructionsError('')
          setRepositoryInstructionsSaved(true)
          break

        case 'repositoryInstructionsSaveError':
          clearWatchdog(repositoryInstructionsWatchdogRef)
          setRepositoryInstructionsSaving(false)
          setRepositoryInstructionsSaved(false)
          setRepositoryInstructionsError(message.message)
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
}
