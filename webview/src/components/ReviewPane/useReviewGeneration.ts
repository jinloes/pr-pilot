import { useCallback, type Dispatch, type SetStateAction } from 'react'
import { toast } from 'sonner'
import { sendToHost, type DeepReviewPreparedMessage, type PR } from '../../bridge/types'
import { startReviewActivity, type ReviewActivity } from './reviewActivity'
import { newOperationId } from './reviewControllerState'
import { validationDiffOf, type PaneState, type ReviewStateEvent } from './reviewState'

type Ref<T> = { current: T }

interface UseReviewGenerationOptions {
  pr: PR | null
  state: PaneState
  chunkedMode: boolean
  intellijAssisted: boolean
  intellijAssistedEnabled: boolean
  focusAreasOverride: string
  customInstructionsOverride: string
  beforeDeepPauseRef: Ref<PaneState>
  activeReviewOperationIdRef: Ref<string | null>
  generationStartedAtRef: Ref<number | null>
  setDeepSetup: Dispatch<SetStateAction<DeepReviewPreparedMessage | null>>
  setDeepBusy: Dispatch<SetStateAction<boolean>>
  setReviewActivity: Dispatch<SetStateAction<ReviewActivity>>
  dispatch: Dispatch<ReviewStateEvent>
}

export function useReviewGeneration({
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
}: UseReviewGenerationOptions) {
  return useCallback((ordinary = false) => {
    if (!pr) return
    setDeepSetup(null)
    beforeDeepPauseRef.current = state
    const assisted = intellijAssisted && intellijAssistedEnabled && !ordinary
    setDeepBusy(assisted)
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
        ...(assisted ? { intellijAssisted: true } : {}),
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
      ...(assisted ? { intellijAssisted: true } : {}),
      focusAreas: focusAreas || undefined,
      customInstructions: customInstructions || undefined,
    })
  }, [
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
  ])
}
