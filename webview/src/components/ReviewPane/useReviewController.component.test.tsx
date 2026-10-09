import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { PR, ReviewResult } from '../../bridge/types'
import { MUTATION_WATCHDOG_MS, SELECTION_CAPTURE_DEBOUNCE_MS, useReviewController } from './useReviewController'

const pr: PR = {
  number: 42,
  title: 'Avoid duplicate approvals',
  owner: 'acme',
  repo: 'widget',
  author: 'octocat',
  createdAt: '2026-07-29T00:00:00Z',
  htmlUrl: 'https://github.com/acme/widget/pull/42',
  isDraft: false,
  hasReviewDraft: true,
  reviewStatus: 'UNREVIEWED',
}

const diff = [
  'diff --git a/src/value.ts b/src/value.ts',
  '--- a/src/value.ts',
  '+++ b/src/value.ts',
  '@@ -0,0 +1 @@',
  '+const value = readValue()',
].join('\n')

const review: ReviewResult = {
  summary: 'Review summary.',
  verdict: 'COMMENT',
  lineComments: [{
    file: 'src/value.ts',
    line: 1,
    type: 'issue',
    body: 'Handle this value.',
  }],
}

function hostMessage(message: object) {
  const handler = (window as unknown as { __handleMessage?: (payload: object) => void }).__handleMessage
  if (!handler) throw new Error('Review controller did not register the JCEF bridge handler')
  handler({ protocolVersion: 1, ...message })
}

function outgoingMessages(cefQuery: ReturnType<typeof vi.fn>) {
  return cefQuery.mock.calls.map(([argument]) => JSON.parse(argument.request) as {
    type: string
    number?: number
    saveId?: number
  })
}

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
  delete (window as unknown as { cefQuery?: unknown }).cefQuery
})

describe('useReviewController', () => {
  it('correlates host events to the active PR and resets on PR switches', () => {
    const { result, rerender } = renderHook(
      ({ selectedPr }) => useReviewController({ pr: selectedPr }),
      { initialProps: { selectedPr: pr } },
    )

    act(() => {
      hostMessage({
        type: 'draftLoaded',
        prKey: 'acme/widget#99',
        prState: 'DRAFT_PRESENT',
        reviewId: 'wrong-draft',
        result: review,
        diff,
        validationDiff: diff,
      })
    })
    expect(result.current.model.state.kind).toBe('draftLoading')

    act(() => {
      hostMessage({
        type: 'draftLoaded',
        prKey: 'acme/widget#42',
        prState: 'DRAFT_PRESENT',
        reviewId: 'draft-1',
        result: review,
        diff,
        validationDiff: diff,
      })
    })
    expect(result.current.model.state).toMatchObject({ kind: 'draftPresent', reviewId: 'draft-1' })

    rerender({ selectedPr: { ...pr, number: 43 } })
    expect(result.current.model.state.kind).toBe('draftLoading')
  })

  it('keeps autosave dirty until the matching acknowledgement', async () => {
    const cefQuery = vi.fn()
    const onDirtyStateChange = vi.fn()
    ;(window as unknown as { cefQuery?: typeof cefQuery }).cefQuery = cefQuery
    const { result } = renderHook(() => useReviewController({ pr, onDirtyStateChange }))

    act(() => {
      hostMessage({
        type: 'reviewResult',
        prKey: 'acme/widget#42',
        result: review,
        diff,
        validationDiff: diff,
      })
    })

    let saveId = 0
    await waitFor(() => {
      const save = outgoingMessages(cefQuery).find((message) => message.type === 'saveDraft')
      expect(save?.saveId).toBeTypeOf('number')
      saveId = save?.saveId ?? 0
      expect(result.current.model.saving).toBe(true)
      expect(onDirtyStateChange).toHaveBeenLastCalledWith(true)
    })

    act(() => {
      hostMessage({
        type: 'draftSaved',
        prKey: 'acme/widget#42',
        saveId: saveId + 1,
        reviewId: 'stale',
        commentsDropped: false,
      })
    })
    expect(result.current.model.saving).toBe(true)

    act(() => {
      hostMessage({
        type: 'draftSaved',
        prKey: 'acme/widget#42',
        saveId,
        reviewId: 'draft-1',
        commentsDropped: false,
      })
    })

    await waitFor(() => {
      expect(result.current.model.saving).toBe(false)
      expect(result.current.model.autosaveDirty).toBe(false)
      expect(onDirtyStateChange).toHaveBeenLastCalledWith(false)
    })
  })

  it('clears a mutation watchdog when the host acknowledges the operation', () => {
    vi.useFakeTimers()
    const cefQuery = vi.fn()
    ;(window as unknown as { cefQuery?: typeof cefQuery }).cefQuery = cefQuery
    const { result } = renderHook(() => useReviewController({ pr }))

    act(() => {
      hostMessage({
        type: 'draftLoaded',
        prKey: 'acme/widget#42',
        prState: 'DRAFT_PRESENT',
        reviewId: 'draft-1',
        result: review,
        diff,
        validationDiff: diff,
      })
    })
    act(() => result.current.actions.deleteDraft())

    expect(result.current.model.deleting).toBe(true)
    expect(vi.getTimerCount()).toBe(1)

    act(() => {
      hostMessage({ type: 'draftDeleted', prKey: 'acme/widget#42' })
    })

    expect(result.current.model.deleting).toBe(false)
    expect(result.current.model.state.kind).toBe('noDraft')
    expect(vi.getTimerCount()).toBe(0)
  })

  it('recovers from a mutation watchdog timeout', () => {
    vi.useFakeTimers()
    ;(window as unknown as { cefQuery?: ReturnType<typeof vi.fn> }).cefQuery = vi.fn()
    const { result } = renderHook(() => useReviewController({ pr }))

    act(() => {
      hostMessage({
        type: 'draftLoaded',
        prKey: 'acme/widget#42',
        prState: 'DRAFT_PRESENT',
        reviewId: 'draft-1',
        result: review,
        diff,
        validationDiff: diff,
      })
    })
    act(() => result.current.actions.deleteDraft())
    act(() => {
      vi.advanceTimersByTime(MUTATION_WATCHDOG_MS)
    })

    expect(result.current.model.deleting).toBe(false)
    expect(result.current.model.state).toMatchObject({
      kind: 'deleteError',
      message: 'The host did not respond in time. The draft may still exist on GitHub.',
    })
  })

  it('flushes a pending autosave for the outgoing PR during a switch', async () => {
    const cefQuery = vi.fn()
    ;(window as unknown as { cefQuery?: typeof cefQuery }).cefQuery = cefQuery
    const { result, rerender } = renderHook(
      ({ selectedPr }) => useReviewController({ pr: selectedPr }),
      { initialProps: { selectedPr: pr } },
    )

    act(() => {
      hostMessage({
        type: 'draftLoaded',
        prKey: 'acme/widget#42',
        prState: 'DRAFT_PRESENT',
        reviewId: 'draft-1',
        result: review,
        diff,
        validationDiff: diff,
      })
    })
    act(() => result.current.actions.editCommentHandlers.onEditComment(0, 'Edited before switching.'))

    await waitFor(() => expect(result.current.model.autosaveDirty).toBe(true))
    expect(outgoingMessages(cefQuery).filter((message) => message.type === 'saveDraft')).toEqual([])

    rerender({ selectedPr: { ...pr, number: 43 } })

    await waitFor(() => {
      expect(outgoingMessages(cefQuery).filter((message) => message.type === 'saveDraft')).toEqual([
        expect.objectContaining({ number: 42 }),
      ])
    })
    expect(result.current.model.state.kind).toBe('draftLoading')
  })

  it('tracks review activity through status updates and completion, then resets it for a new PR', () => {
    const cefQuery = vi.fn()
    ;(window as unknown as { cefQuery?: typeof cefQuery }).cefQuery = cefQuery
    const { result, rerender } = renderHook(
      ({ selectedPr }) => useReviewController({ pr: selectedPr }),
      { initialProps: { selectedPr: pr } },
    )

    act(() => result.current.actions.generate())
    expect(result.current.model.activity).toMatchObject({
      outcome: 'running',
      entries: [{ message: 'Starting review…' }],
    })

    act(() => {
      hostMessage({
        type: 'reviewGenerating',
        prKey: 'acme/widget#42',
        message: 'read_file',
      })
    })

    expect(
      result.current.model.activity.entries[
        result.current.model.activity.entries.length - 1
      ]?.message,
    ).toBe('read_file')

    act(() => {
      hostMessage({
        type: 'reviewResult',
        prKey: 'acme/widget#42',
        result: review,
        diff,
        validationDiff: diff,
      })
    })
    expect(result.current.model.activity.outcome).toBe('completed')
    expect(
      result.current.model.activity.entries[
        result.current.model.activity.entries.length - 1
      ]?.message,
    ).toBe('Review complete')

    rerender({ selectedPr: { ...pr, number: 43 } })
    expect(result.current.model.activity).toEqual({
      runId: 0,
      outcome: 'idle',
      startedAtMs: null,
      endedAtMs: null,
      entries: [],
    })
  })

  it('preserves the focused finding when a replacement review completes', () => {
    const replacementReview: ReviewResult = {
      ...review,
      lineComments: [
        review.lineComments[0],
        { ...review.lineComments[0], body: 'Second finding.' },
      ],
    }
    ;(window as unknown as { cefQuery?: ReturnType<typeof vi.fn> }).cefQuery = vi.fn()
    const { result } = renderHook(() => useReviewController({ pr }))

    act(() => {
      hostMessage({
        type: 'draftLoaded',
        prKey: 'acme/widget#42',
        prState: 'DRAFT_PRESENT',
        reviewId: 'draft-1',
        result: replacementReview,
        diff,
        validationDiff: diff,
      })
    })
    act(() => result.current.actions.focusNextComment())
    expect(result.current.model.focusedCommentIdx).toBe(1)

    act(() => result.current.actions.generate())
    expect(result.current.model.state).toMatchObject({
      kind: 'generating',
      replacingDraft: true,
      result: replacementReview,
    })

    act(() => {
      hostMessage({
        type: 'reviewResult',
        prKey: 'acme/widget#42',
        result: replacementReview,
        diff,
        validationDiff: diff,
      })
    })

    expect(result.current.model.focusedCommentIdx).toBe(1)

    act(() => result.current.actions.generate())
    act(() => {
      hostMessage({
        type: 'reviewResult',
        prKey: 'acme/widget#42',
        result: review,
        diff,
        validationDiff: diff,
      })
    })

    expect(result.current.model.focusedCommentIdx).toBe(0)
  })

  it('requests focus again when navigating a lone comment', () => {
    const { result } = renderHook(() => useReviewController({ pr }))
    act(() => {
      hostMessage({
        type: 'draftLoaded',
        prKey: 'acme/widget#42',
        prState: 'DRAFT_PRESENT',
        reviewId: 'draft-1',
        result: review,
        diff,
        validationDiff: diff,
      })
    })

    expect(result.current.model.commentFocusRequestId).toBe(0)
    act(() => result.current.actions.focusPreviousComment())
    expect(result.current.model.focusedCommentIdx).toBe(0)
    expect(result.current.model.commentFocusRequestId).toBe(1)

    act(() => result.current.actions.focusNextComment())
    expect(result.current.model.focusedCommentIdx).toBe(0)
    expect(result.current.model.commentFocusRequestId).toBe(2)
  })

  it('marks review activity failed when generation errors', () => {
    ;(window as unknown as { cefQuery?: ReturnType<typeof vi.fn> }).cefQuery = vi.fn()
    const { result } = renderHook(() => useReviewController({ pr }))

    act(() => result.current.actions.generate())
    act(() => {
      hostMessage({
        type: 'reviewError',
        prKey: 'acme/widget#42',
        message: 'Provider failed',
      })
    })

    expect(result.current.model.activity.outcome).toBe('failed')
    expect(
      result.current.model.activity.entries[
        result.current.model.activity.entries.length - 1
      ]?.message,
    ).toBe('Review failed')
  })

  it('marks review activity cancelled and ignores a late provider error', () => {
    ;(window as unknown as { cefQuery?: ReturnType<typeof vi.fn> }).cefQuery = vi.fn()
    const { result } = renderHook(() => useReviewController({ pr }))

    act(() => result.current.actions.generate())
    act(() => result.current.actions.cancel())
    expect(result.current.model.activity.outcome).toBe('cancelled')
    expect(
      result.current.model.activity.entries[
        result.current.model.activity.entries.length - 1
      ]?.message,
    ).toBe('Review cancelled')

    act(() => {
      hostMessage({
        type: 'reviewError',
        prKey: 'acme/widget#42',
        message: 'Cancelled by user',
      })
    })
    expect(result.current.model.activity.outcome).toBe('cancelled')
  })

  it('labels verify requests with their read-only worktree context', () => {
    const { result } = renderHook(() => useReviewController({ pr }))
    act(() => {
      hostMessage({
        type: 'draftLoaded',
        prKey: 'acme/widget#42',
        prState: 'DRAFT_PRESENT',
        reviewId: 'draft-1',
        result: review,
        diff,
        validationDiff: diff,
      })
    })

    act(() => result.current.actions.verifyComment(review.lineComments[0]))

    expect(result.current.model.pendingChatMessage?.contextSummary).toEqual([
      'draft comment',
      'diff excerpt',
      'PR worktree (read-only)',
    ])
  })

  describe('suggested changes', () => {
    const suggestedReview: ReviewResult = {
      ...review,
      lineComments: [{
        ...review.lineComments[0],
        severity: 'major',
        rationale: 'Null on this path.',
        suggestedChange: 'const value = readValue() ?? 0',
      }],
    }

    function loadSuggestedDraft() {
      const rendered = renderHook(() => useReviewController({ pr }))
      act(() => {
        hostMessage({
          type: 'draftLoaded',
          prKey: 'acme/widget#42',
          prState: 'DRAFT_PRESENT',
          reviewId: 'draft-1',
          result: suggestedReview,
          diff,
          validationDiff: diff,
        })
      })
      return rendered
    }

    it('removes only the suggestion and marks the draft changed', () => {
      const { result } = loadSuggestedDraft()
      expect(result.current.model.autosaveDirty).toBe(false)

      act(() => result.current.actions.editCommentHandlers.onRemoveSuggestion(0))

      const { suggestedChange, ...rest } = suggestedReview.lineComments[0]
      expect(suggestedChange).toBeDefined()
      expect(result.current.model.result?.lineComments).toEqual([rest])
      expect(result.current.model.autosaveDirty).toBe(true)
    })

    it('clears the suggestion when the body is edited', () => {
      const { result } = loadSuggestedDraft()

      act(() => result.current.actions.editCommentHandlers.onEditComment(0, 'Default the value.'))

      const edited = result.current.model.result?.lineComments[0]
      expect(edited?.body).toBe('Default the value.')
      expect(edited).not.toHaveProperty('suggestedChange')
    })

    it('clears the suggestion when a verifier revision is applied', () => {
      const { result } = loadSuggestedDraft()
      act(() => result.current.actions.verifyComment(suggestedReview.lineComments[0]))
      const token = result.current.model.pendingChatMessage?.token ?? ''

      act(() => result.current.actions.applyVerifyAction({
        kind: 'verify',
        verdict: 'valid',
        why: 'Confirmed.',
        evidence: [],
        action: 'revise',
        replacementComment: 'Return a default instead.',
      }, token))

      const revised = result.current.model.result?.lineComments[0]
      expect(revised?.body).toBe('Return a default instead.')
      expect(revised).not.toHaveProperty('suggestedChange')
    })
  })

  describe('keyboard selection capture', () => {
    function selectContents(element: Element) {
      const range = document.createRange()
      range.selectNodeContents(element)
      const selection = window.getSelection()!
      selection.removeAllRanges()
      selection.addRange(range)
      document.dispatchEvent(new Event('selectionchange'))
    }

    afterEach(() => {
      window.getSelection()?.removeAllRanges()
      document.body.innerHTML = ''
    })

    it('captures a selection made without the mouse after the debounce', () => {
      vi.useFakeTimers()
      const paragraph = document.createElement('p')
      paragraph.textContent = '  Selected summary text  '
      document.body.append(paragraph)
      const { result } = renderHook(() => useReviewController({ pr }))

      act(() => selectContents(paragraph))
      expect(result.current.model.selectedContext).toBe('')
      act(() => { vi.advanceTimersByTime(SELECTION_CAPTURE_DEBOUNCE_MS) })

      expect(result.current.model.selectedContext).toBe('Selected summary text')
    })

    it('keeps the captured context when the selection collapses', () => {
      vi.useFakeTimers()
      const paragraph = document.createElement('p')
      paragraph.textContent = 'Keep me'
      document.body.append(paragraph)
      const { result } = renderHook(() => useReviewController({ pr }))

      act(() => selectContents(paragraph))
      act(() => { vi.advanceTimersByTime(SELECTION_CAPTURE_DEBOUNCE_MS) })
      act(() => {
        window.getSelection()!.removeAllRanges()
        document.dispatchEvent(new Event('selectionchange'))
      })
      act(() => { vi.advanceTimersByTime(SELECTION_CAPTURE_DEBOUNCE_MS) })

      expect(result.current.model.selectedContext).toBe('Keep me')
    })

    it('ignores selections inside the chat input and other text fields', () => {
      vi.useFakeTimers()
      const composer = document.createElement('div')
      composer.className = 'chat-pane__input'
      composer.textContent = 'Typing a question'
      const label = document.createElement('label')
      const textarea = document.createElement('textarea')
      label.textContent = 'Field label'
      label.append(textarea)
      document.body.append(composer, label)
      const { result } = renderHook(() => useReviewController({ pr }))

      act(() => selectContents(composer))
      act(() => { vi.advanceTimersByTime(SELECTION_CAPTURE_DEBOUNCE_MS) })
      textarea.focus()
      act(() => selectContents(label))
      act(() => { vi.advanceTimersByTime(SELECTION_CAPTURE_DEBOUNCE_MS) })

      expect(result.current.model.selectedContext).toBe('')
    })

    it('removes the listener when no pull request is selected', () => {
      vi.useFakeTimers()
      const paragraph = document.createElement('p')
      paragraph.textContent = 'Not captured'
      document.body.append(paragraph)
      const removeListener = vi.spyOn(document, 'removeEventListener')
      const initialProps: { selectedPr: PR | null } = { selectedPr: pr }
      const { result, rerender } = renderHook(
        ({ selectedPr }) => useReviewController({ pr: selectedPr }),
        { initialProps },
      )

      rerender({ selectedPr: null })
      expect(removeListener).toHaveBeenCalledWith('selectionchange', expect.any(Function))
      act(() => selectContents(paragraph))
      act(() => { vi.advanceTimersByTime(SELECTION_CAPTURE_DEBOUNCE_MS) })

      expect(result.current.model.selectedContext).toBe('')
    })
  })
})
