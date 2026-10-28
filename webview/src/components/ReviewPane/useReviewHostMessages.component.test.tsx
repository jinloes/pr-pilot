import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { PR, ReviewResult } from '../../bridge/types'
import { useReviewController } from './useReviewController'
import { REVIEW_DISPLAY_FAILED } from './useReviewHostMessages'
import { REJECTED_REVIEW_MESSAGE } from '../../bridge/rejectedMessage'

const failNextValidation = vi.hoisted(() => ({ armed: false }))

vi.mock('@/lib/validateComments', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/validateComments')>()
  return {
    ...actual,
    validateComments: (...args: Parameters<typeof actual.validateComments>) => {
      if (failNextValidation.armed) {
        failNextValidation.armed = false
        throw new Error('validation exploded')
      }
      return actual.validateComments(...args)
    },
  }
})

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
  lineComments: [{ file: 'src/value.ts', line: 1, type: 'issue', body: 'Handle this value.' }],
}

function hostMessage(message: object) {
  const handler = (window as unknown as { __handleMessage?: (payload: object) => void }).__handleMessage
  if (!handler) throw new Error('Review controller did not register the JCEF bridge handler')
  handler({ protocolVersion: 1, ...message })
}

afterEach(() => {
  failNextValidation.armed = false
  vi.restoreAllMocks()
  delete (window as unknown as { cefQuery?: unknown }).cefQuery
})

describe('useReviewHostMessages reviewResult', () => {
  it('ends the run as failed instead of spinning when the result cannot be processed', () => {
    ;(window as unknown as { cefQuery?: ReturnType<typeof vi.fn> }).cefQuery = vi.fn()
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    const { result } = renderHook(() => useReviewController({ pr }))

    act(() => result.current.actions.generate())
    expect(result.current.model.activity.outcome).toBe('running')

    failNextValidation.armed = true
    act(() => {
      hostMessage({ type: 'reviewResult', prKey: 'acme/widget#42', result: review, diff, validationDiff: diff })
    })

    expect(result.current.model.activity.outcome).toBe('failed')
    expect(result.current.model.activity.entries[result.current.model.activity.entries.length - 1]?.message).toBe('Review failed')
    expect(JSON.stringify(result.current.model)).toContain(REVIEW_DISPLAY_FAILED)
    expect(consoleError).toHaveBeenCalledWith('[pr-pilot] could not display the generated review', expect.any(Error))
  })

  it('ends the run with a reload hint when the bridge rejects the review result', () => {
    ;(window as unknown as { cefQuery?: ReturnType<typeof vi.fn> }).cefQuery = vi.fn()
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    const { result } = renderHook(() => useReviewController({ pr }))

    act(() => result.current.actions.generate())
    const fromNewerHost = {
      ...review,
      lineComments: [{ ...review.lineComments[0], category: 'futuristic' }],
    }
    act(() => {
      hostMessage({ type: 'reviewResult', prKey: 'acme/widget#42', result: fromNewerHost, diff, validationDiff: diff })
    })

    expect(result.current.model.activity.outcome).toBe('failed')
    expect(JSON.stringify(result.current.model)).toContain(REJECTED_REVIEW_MESSAGE)
    expect(consoleError).toHaveBeenCalledWith(expect.stringContaining('category="futuristic"'))
  })

  describe('comment navigation after a review loads', () => {
    const threeComments: ReviewResult = {
      ...review,
      lineComments: [0, 1, 2].map((n) => ({ ...review.lineComments[0], body: `Finding ${n}` })),
    }

    function loadReview() {
      ;(window as unknown as { cefQuery?: ReturnType<typeof vi.fn> }).cefQuery = vi.fn()
      const hook = renderHook(() => useReviewController({ pr }))
      act(() => hook.result.current.actions.generate())
      act(() => {
        hostMessage({ type: 'reviewResult', prKey: 'acme/widget#42', result: threeComments, diff, validationDiff: diff })
      })
      return hook.result
    }

    it('jumps to the first comment on the first next press, then advances', () => {
      const result = loadReview()
      const requestBefore = result.current.model.commentFocusRequestId

      act(() => result.current.actions.focusNextComment())
      expect(result.current.model.focusedCommentIdx).toBe(0)
      expect(result.current.model.commentFocusRequestId).toBe(requestBefore + 1)

      act(() => result.current.actions.focusNextComment())
      expect(result.current.model.focusedCommentIdx).toBe(1)
    })

    it('advances from a clicked comment', () => {
      const result = loadReview()

      act(() => result.current.actions.focusComment(1))
      act(() => result.current.actions.focusNextComment())
      expect(result.current.model.focusedCommentIdx).toBe(2)
    })

    it('keeps previous navigation wrapping to the last comment', () => {
      const result = loadReview()

      act(() => result.current.actions.focusPreviousComment())
      expect(result.current.model.focusedCommentIdx).toBe(2)
    })
  })

  it('still completes a run whose result processes normally', () => {
    ;(window as unknown as { cefQuery?: ReturnType<typeof vi.fn> }).cefQuery = vi.fn()
    const { result } = renderHook(() => useReviewController({ pr }))

    act(() => result.current.actions.generate())
    act(() => {
      hostMessage({ type: 'reviewResult', prKey: 'acme/widget#42', result: review, diff, validationDiff: diff })
    })

    expect(result.current.model.activity.outcome).toBe('completed')
  })
})
