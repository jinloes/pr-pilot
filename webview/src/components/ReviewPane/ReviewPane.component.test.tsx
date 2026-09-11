import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createRef } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { PR } from '../../bridge/types'
import { ReviewPane, type ReviewPaneHandle } from './ReviewPane'

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

function diffWithFiles(count: number): string {
  return Array.from({ length: count }, (_, index) => [
    `diff --git a/src/file-${index}.ts b/src/file-${index}.ts`,
    `--- a/src/file-${index}.ts`,
    `+++ b/src/file-${index}.ts`,
    '@@ -1 +1 @@',
    '-old',
    '+new',
  ].join('\n')).join('\n')
}

function hostMessage(message: object) {
  const handler = (window as unknown as { __handleMessage?: (payload: object) => void }).__handleMessage
  if (!handler) throw new Error('ReviewPane did not register the JCEF bridge handler')
  handler({ protocolVersion: 1, ...message })
}

afterEach(() => {
  vi.restoreAllMocks()
  delete (window as unknown as { cefQuery?: unknown }).cefQuery
})

describe('IntelliJ-assisted workflow', () => {
  function fixture() {
    const outgoing: Record<string, unknown>[] = []
    ;(window as unknown as { cefQuery: (arg: { request: string }) => void }).cefQuery =
      ({ request }) => outgoing.push(JSON.parse(request))
    const view = render(<ReviewPane pr={pr} />)
    act(() => hostMessage({ type: 'draftLoaded', prKey: 'acme/widget#42', prState: 'NO_DRAFT',
      diff: diffWithFiles(1), providerReadiness: { provider: 'claude', available: true, detail: 'Ready' } }))
    const last = (type: string) => { const matches = outgoing.filter(m => m.type === type); return matches[matches.length - 1] }
    const prepared = (operationId: unknown) => ({ type: 'deepReviewPrepared', prKey: 'acme/widget#42',
      operationId, retainedId: '11111111-1111-4111-8111-111111111111', head: 'a'.repeat(40),
      worktree: '/fixture/deep', servers: ['private'], message: 'Manual sync required' })
    return { outgoing, view, last, prepared }
  }

  it('pauses, ignores stale setup, retries with new IDs, and explicitly falls back', async () => {
    const user = userEvent.setup()
    const f = fixture()
    await user.click(screen.getByText('Review instructions (optional)'))
    await user.click(screen.getByText('Advanced review options'))
    expect(screen.getByRole('checkbox', { name: /IntelliJ-assisted/ })).not.toBeChecked()
    await user.click(screen.getByRole('checkbox', { name: /IntelliJ-assisted/ }))
    await user.click(screen.getByRole('button', { name: 'Generate Review' }))
    const prepare = f.last('generateReview')
    expect(prepare.intellijAssisted).toBe(true)
    act(() => hostMessage(f.prepared('stale-operation')))
    expect(screen.queryByRole('button', { name: 'Continue / Retry' })).not.toBeInTheDocument()
    act(() => hostMessage(f.prepared(prepare.operationId)))
    expect(screen.getByText('/fixture/deep')).toBeVisible()
    expect(f.outgoing.filter(m => m.type === 'continueDeepReview')).toHaveLength(0)
    await user.click(screen.getByRole('button', { name: 'Continue / Retry' }))
    const first = f.last('continueDeepReview')
    expect(first.server).toBe('private')
    act(() => hostMessage(f.prepared(first.operationId)))
    await user.click(screen.getByRole('button', { name: 'Continue / Retry' }))
    const second = f.last('continueDeepReview')
    expect(second.operationId).not.toBe(first.operationId)
    act(() => hostMessage(f.prepared(second.operationId)))
    await user.click(screen.getByRole('button', { name: 'Use ordinary review instead' }))
    expect(f.last('generateReview').intellijAssisted).toBeUndefined()
    expect(screen.queryByText('/fixture/deep')).not.toBeInTheDocument()
  })

  it('cancels a paused setup without cleanup and discards it across selection changes', async () => {
    const user = userEvent.setup()
    const f = fixture()
    await user.click(screen.getByText('Review instructions (optional)'))
    await user.click(screen.getByText('Advanced review options'))
    await user.click(screen.getByRole('checkbox', { name: /IntelliJ-assisted/ }))
    await user.click(screen.getByRole('button', { name: 'Generate Review' }))
    const operation = f.last('generateReview').operationId
    act(() => hostMessage(f.prepared(operation)))
    await user.click(screen.getByRole('button', { name: 'Cancel IntelliJ-assisted review' }))
    expect(f.last('cancelReview').operationId).toBe(operation)
    expect(f.outgoing.filter(m => m.type === 'cleanupDeepReview')).toHaveLength(0)
    expect(screen.queryByText('/fixture/deep')).not.toBeInTheDocument()
    f.view.rerender(<ReviewPane pr={{ ...pr, number: 43 }} />)
    f.view.rerender(<ReviewPane pr={pr} />)
    expect(screen.queryByText('/fixture/deep')).not.toBeInTheDocument()
  })

  it('lists and safely confirms cleanup without any selected PR and ignores stale responses', async () => {
    const user = userEvent.setup()
    const f = fixture()
    f.view.rerender(<ReviewPane pr={null} />)
    await user.click(screen.getByText('Retained IntelliJ review worktrees'))
    await user.click(screen.getByRole('button', { name: 'Refresh retained worktrees' }))
    const retained = [{ id: '11111111-1111-4111-8111-111111111111', repository: '/fixture',
      worktree: '/fixture/deep', head: 'a'.repeat(40), createdAt: 1 }]
    act(() => hostMessage({ type: 'retainedDeepReviews', operationId: 'stale', retained }))
    expect(screen.queryByRole('button', { name: 'Remove retained worktree' })).not.toBeInTheDocument()
    act(() => hostMessage({ type: 'retainedDeepReviews', operationId: f.last('listDeepReviews').operationId, retained }))
    expect(screen.getByRole('button', { name: 'Remove retained worktree' })).toBeDisabled()
    await user.click(screen.getByRole('checkbox', { name: 'I closed this exact project in every IDE' }))
    await user.click(screen.getByRole('button', { name: 'Remove retained worktree' }))
    expect(f.last('cleanupDeepReview').projectClosed).toBe(true)
    expect(screen.getByRole('button', { name: 'Remove retained worktree' })).toBeDisabled()
  })
})

describe('ReviewPane review submission', () => {
  it('keeps lone-comment navigation enabled and scrolls on either arrow', async () => {
    const scrollIntoView = vi.spyOn(Element.prototype, 'scrollIntoView').mockImplementation(() => {})
    render(<ReviewPane pr={pr} />)
    const diff = diffWithFiles(1)
    act(() => {
      hostMessage({
        type: 'draftLoaded',
        prKey: 'acme/widget#42',
        prState: 'DRAFT_PRESENT',
        reviewId: 'draft-1',
        result: {
          summary: 'Saved review.',
          verdict: 'COMMENT',
          lineComments: [{ file: 'src/file-0.ts', line: 1, type: 'issue', body: 'Review this line.' }],
        },
        diff,
        validationDiff: diff,
      })
    })

    const previous = screen.getByRole('button', { name: 'Previous comment' })
    const next = screen.getByRole('button', { name: 'Next comment' })
    expect(screen.getAllByText('1/1')).toHaveLength(2)
    expect(previous).toBeEnabled()
    expect(next).toBeEnabled()

    scrollIntoView.mockClear()
    fireEvent.click(previous)
    await waitFor(() => expect(scrollIntoView).toHaveBeenCalled())

    scrollIntoView.mockClear()
    fireEvent.click(next)
    await waitFor(() => expect(scrollIntoView).toHaveBeenCalled())
  })

  it('discards a pending edit without saving it during PR cleanup', async () => {
    const user = userEvent.setup()
    const cefQuery = vi.fn()
    const ref = createRef<ReviewPaneHandle>()
    ;(window as unknown as { cefQuery?: typeof cefQuery }).cefQuery = cefQuery
    const { rerender } = render(<ReviewPane ref={ref} pr={pr} />)

    act(() => {
      hostMessage({
        type: 'draftLoaded',
        prKey: 'acme/widget#42',
        prState: 'DRAFT_PRESENT',
        reviewId: 'draft-1',
        result: {
          summary: 'Saved review.',
          verdict: 'COMMENT',
          lineComments: [{ file: 'missing.ts', line: 1, type: 'issue', body: 'Remove this.' }],
        },
        diff: '',
        validationDiff: '',
      })
    })
    await user.click(screen.getByRole('button', { name: 'Delete unanchored comment' }))
    await user.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Delete' }))

    expect(ref.current?.discardPendingChanges()).toBe(true)
    rerender(<ReviewPane ref={ref} pr={{ ...pr, number: 43 }} />)

    const saves = cefQuery.mock.calls
      .map(([arg]) => JSON.parse(arg.request) as { type: string })
      .filter((message) => message.type === 'saveDraft')
    expect(saves).toEqual([])
  })



  describe('ReviewPane chunked review fallback', () => {
    function loadReviewableDiff(diff: string) {
      act(() => {
        hostMessage({
          type: 'draftLoaded',
          prKey: 'acme/widget#42',
          prState: 'NO_DRAFT',
          diff,
          validationDiff: diff,
          providerReadiness: { provider: 'claude', available: true, detail: 'Ready.' },
        })
      })
    }

    function generateMessages(cefQuery: ReturnType<typeof vi.fn>) {
      return cefQuery.mock.calls
        .map(([arg]) => JSON.parse(arg.request) as {
          type: string
          diff?: string
          chunkedReview?: boolean
          customInstructions?: string
        })
        .filter((message) => message.type === 'generateReview')
    }

    async function openAdvanced(user: ReturnType<typeof userEvent.setup>) {
      await user.click(screen.getByText('Review instructions (optional)'))
      await user.click(screen.getByText('Advanced review options'))
    }

    it('puts Generate Review first and preserves collapsed override values', async () => {
      const user = userEvent.setup()
      ;(window as unknown as { cefQuery?: ReturnType<typeof vi.fn> }).cefQuery = vi.fn()
      render(<ReviewPane pr={pr} />)
      loadReviewableDiff(diffWithFiles(1))

      const generate = screen.getByRole('button', { name: 'Generate Review' })
      const disclosure = screen.getByText('Review instructions (optional)').closest('summary')!
      expect(generate.compareDocumentPosition(disclosure) & Node.DOCUMENT_POSITION_FOLLOWING).not.toBe(0)
      expect(screen.getByLabelText('Focus areas')).not.toBeVisible()

      disclosure.focus()
      await user.keyboard('{Enter}')
      await user.type(screen.getByLabelText('Focus areas'), 'security')
      await user.click(screen.getByText('Advanced review options'))
      await user.type(screen.getByLabelText(/Custom instructions/), 'Check boundary cases')
      expect(screen.getByText('2 overrides applied')).toBeInTheDocument()

      screen.getByText('Review instructions (optional)').closest('summary')!.focus()
      await user.keyboard('{Enter}')
      expect(screen.getByLabelText('Focus areas')).not.toBeVisible()
      screen.getByText('Review instructions (optional)').closest('summary')!.focus()
      await user.keyboard('{Enter}')
      expect(screen.getByLabelText('Focus areas')).toHaveValue('security')
      expect(screen.getByLabelText(/Custom instructions/)).toHaveValue('Check boundary cases')
    })

    it('keeps chunking off for a large PR recommendation and generates a single-pass review', async () => {
      const user = userEvent.setup()
      const cefQuery = vi.fn()
      ;(window as unknown as { cefQuery?: typeof cefQuery }).cefQuery = cefQuery
      render(<ReviewPane pr={pr} />)
      loadReviewableDiff(diffWithFiles(8))

      await openAdvanced(user)
      expect(screen.getByRole('checkbox', { name: /Use chunked review mode/ })).not.toBeChecked()
      expect(screen.getByText('Fallback available: consider chunked mode.')).toBeInTheDocument()
      expect(screen.getByText(/miss cross-file interactions and provide limited synthesis/)).toBeInTheDocument()

      await user.click(screen.getByRole('button', { name: 'Generate Review' }))

      expect(generateMessages(cefQuery)).toEqual([
        expect.not.objectContaining({ diff: expect.any(String), chunkedReview: true }),
      ])
    })

    it('shows generation activity and keeps the completed timeline available', async () => {
      const user = userEvent.setup()
      ;(window as unknown as { cefQuery?: ReturnType<typeof vi.fn> }).cefQuery = vi.fn()
      render(<ReviewPane pr={pr} />)
      const diff = diffWithFiles(1)
      loadReviewableDiff(diff)

      await user.click(screen.getByRole('button', { name: 'Generate Review' }))
      expect(screen.getByRole('region', { name: 'Review generation activity' })).toBeVisible()
      expect(within(screen.getByTestId('review-scroll-body')).getAllByText('Starting review')).toHaveLength(1)
      expect(screen.getByRole('button', { name: 'Stop generation' })).toBeVisible()
      expect(screen.getByRole('navigation', { name: 'Review navigation' })).toBeVisible()
      expect(screen.getByText(/Keep inspecting the changed files/)).toBeVisible()
      await user.click(screen.getByRole('button', { name: 'Find in diff' }))
      expect(screen.getByRole('textbox', { name: 'Find in diff' })).toBeVisible()

      act(() => {
        hostMessage({
          type: 'reviewGenerating',
          prKey: 'acme/widget#42',
          message: 'read_file',
        })
      })
      expect(within(screen.getByTestId('review-scroll-body')).getAllByText('Reading files')).toHaveLength(1)

      act(() => {
        hostMessage({
          type: 'reviewChunk',
          prKey: 'acme/widget#42',
          kind: 'thinking',
          chunk: 'PRIVATE_PROVIDER_REASONING_SENTINEL',
        })
        hostMessage({
          type: 'reviewChunk',
          prKey: 'acme/widget#42',
          kind: 'text',
          chunk: 'RAW_PROVIDER_TEXT_SENTINEL',
        })
      })
      expect(screen.queryByText(/PRIVATE_PROVIDER_REASONING_SENTINEL/)).not.toBeInTheDocument()
      expect(screen.queryByText(/RAW_PROVIDER_TEXT_SENTINEL/)).not.toBeInTheDocument()

      act(() => {
        hostMessage({
          type: 'reviewResult',
          prKey: 'acme/widget#42',
          result: { summary: 'Generated review.', verdict: 'COMMENT', lineComments: [] },
          diff,
          validationDiff: diff,
        })
      })
      expect(screen.getByText(/Completed in/)).toBeVisible()
      await user.click(screen.getByRole('button', { name: 'Show details for Review activity' }))
      expect(screen.getByText('Review complete')).toBeVisible()
    })

    it('keeps the stop action next to progress and sends a scoped cancellation', async () => {
      const user = userEvent.setup()
      const cefQuery = vi.fn()
      ;(window as unknown as { cefQuery?: typeof cefQuery }).cefQuery = cefQuery
      render(<ReviewPane pr={pr} />)
      loadReviewableDiff(diffWithFiles(1))

      await user.click(screen.getByRole('button', { name: 'Generate Review' }))
      const activity = screen.getByRole('region', { name: 'Review generation activity' })
      const stop = within(activity).getByRole('button', { name: 'Stop generation' })
      expect(screen.queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument()

      await user.click(stop)

      const messages = cefQuery.mock.calls.map(([arg]) => JSON.parse(arg.request) as { type: string })
      expect(messages).toContainEqual(expect.objectContaining({ type: 'cancelReview' }))
    })

    it('keeps the current draft visible but read-only while generating its replacement', async () => {
      const user = userEvent.setup()
      ;(window as unknown as { cefQuery?: ReturnType<typeof vi.fn> }).cefQuery = vi.fn()
      render(<ReviewPane pr={pr} />)
      const diff = diffWithFiles(2)
      act(() => {
        hostMessage({
          type: 'draftLoaded',
          prKey: 'acme/widget#42',
          prState: 'DRAFT_PRESENT',
          reviewId: 'draft-1',
          result: {
            summary: 'Keep this draft visible.',
            verdict: 'COMMENT',
            lineComments: [{
              file: 'src/file-0.ts',
              line: 1,
              type: 'issue',
              severity: 'major',
              body: 'Existing finding.',
            }],
          },
          diff,
          validationDiff: diff,
        })
      })
      await user.click(screen.getByRole('button', { name: 'src/file-1.ts' }))
      expect(screen.getByTestId('diff-current-file-path')).toHaveTextContent('src/file-1.ts')

      await user.click(screen.getByRole('button', { name: 'Regenerate' }))
      await user.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Regenerate' }))

      expect(screen.getByText('Keep this draft visible.')).toBeVisible()
      expect(screen.getByText(/current draft remains until the new review is ready/)).toBeVisible()
      expect(screen.getByRole('navigation', { name: 'Review navigation' })).toBeVisible()
      expect(screen.getByRole('button', { name: 'Verify with AI' })).toBeDisabled()
      expect(screen.getByRole('button', { name: 'Suggest fix with AI' })).toBeDisabled()
      expect(screen.getByRole('button', { name: 'More finding actions' })).toBeDisabled()
      expect(screen.queryByRole('button', { name: 'Comment' })).not.toBeInTheDocument()
      expect(screen.getByTestId('diff-current-file-path')).toHaveTextContent('src/file-1.ts')

      act(() => {
        hostMessage({
          type: 'reviewResult',
          prKey: 'acme/widget#42',
          result: {
            summary: 'Replacement review.',
            verdict: 'COMMENT',
            lineComments: [{
              file: 'src/file-0.ts',
              line: 1,
              type: 'issue',
              severity: 'major',
              body: 'Replacement finding.',
            }],
          },
          diff,
          validationDiff: diff,
        })
      })
      expect(screen.getByTestId('diff-current-file-path')).toHaveTextContent('src/file-1.ts')
    })

    it('places failure recovery before activity and retry instructions', async () => {
      const user = userEvent.setup()
      ;(window as unknown as { cefQuery?: ReturnType<typeof vi.fn> }).cefQuery = vi.fn()
      render(<ReviewPane pr={pr} />)
      loadReviewableDiff(diffWithFiles(1))

      await user.click(screen.getByRole('button', { name: 'Generate Review' }))
      act(() => {
        hostMessage({
          type: 'reviewError',
          prKey: 'acme/widget#42',
          message: 'Provider failed. Check credentials and retry.',
        })
      })

      const alert = screen.getByRole('alert')
      const activity = screen.getByRole('region', { name: 'Review generation activity' })
      const instructions = screen.getByText('Adjust instructions before retry').closest('details')!
      expect(alert.compareDocumentPosition(activity) & Node.DOCUMENT_POSITION_FOLLOWING).not.toBe(0)
      expect(activity.compareDocumentPosition(instructions) & Node.DOCUMENT_POSITION_FOLLOWING).not.toBe(0)
      expect(screen.getByRole('button', { name: 'Try Again' })).toBeVisible()
    })

    it('keeps chunking off when the diff is truncated', async () => {
      const user = userEvent.setup()
      ;(window as unknown as { cefQuery?: ReturnType<typeof vi.fn> }).cefQuery = vi.fn()
      render(<ReviewPane pr={pr} />)
      loadReviewableDiff(`${diffWithFiles(1)}\n[... diff truncated at 250 KB ...]`)

      await openAdvanced(user)

      expect(screen.getByRole('checkbox', { name: /Use chunked review mode/ })).not.toBeChecked()
      expect(screen.getByText('Fallback available: consider chunked mode.')).toBeInTheDocument()
      expect(screen.getByText('Diff context is truncated.')).toBeInTheDocument()
    })

    it('keeps chunking off for a small PR', async () => {
      const user = userEvent.setup()
      ;(window as unknown as { cefQuery?: ReturnType<typeof vi.fn> }).cefQuery = vi.fn()
      render(<ReviewPane pr={pr} />)
      loadReviewableDiff(diffWithFiles(1))

      await openAdvanced(user)

      expect(screen.getByRole('checkbox', { name: /Use chunked review mode/ })).not.toBeChecked()
      expect(screen.getByText('Recommended: Single-pass mode.')).toBeInTheDocument()
    })

    it('runs chunked review only after the user explicitly enables it', async () => {
      const user = userEvent.setup()
      const cefQuery = vi.fn()
      ;(window as unknown as { cefQuery?: typeof cefQuery }).cefQuery = cefQuery
      render(<ReviewPane pr={pr} />)
      loadReviewableDiff(diffWithFiles(8))

      await openAdvanced(user)
      await user.click(screen.getByRole('checkbox', { name: /Use chunked review mode/ }))
      await user.click(screen.getByRole('button', { name: 'Generate Review' }))

      expect(generateMessages(cefQuery)).toEqual([
        expect.objectContaining({
          diff: expect.stringMatching(/src\/file-0\.ts[\s\S]*src\/file-7\.ts/),
          chunkedReview: true,
        }),
      ])
    })

    it('honors an explicit opt-out after chunking was selected', async () => {
      const user = userEvent.setup()
      const cefQuery = vi.fn()
      ;(window as unknown as { cefQuery?: typeof cefQuery }).cefQuery = cefQuery
      render(<ReviewPane pr={pr} />)
      loadReviewableDiff(diffWithFiles(8))

      await openAdvanced(user)
      const chunked = screen.getByRole('checkbox', { name: /Use chunked review mode/ })
      await user.click(chunked)
      await user.click(chunked)
      await user.click(screen.getByRole('button', { name: 'Generate Review' }))

      expect(generateMessages(cefQuery)).toEqual([
        expect.not.objectContaining({ diff: expect.any(String), chunkedReview: true }),
      ])
    })
  })

  it('refuses to discard after a save has already been sent', async () => {
    const cefQuery = vi.fn()
    const ref = createRef<ReviewPaneHandle>()
    ;(window as unknown as { cefQuery?: typeof cefQuery }).cefQuery = cefQuery
    render(<ReviewPane ref={ref} pr={pr} />)

    act(() => {
      hostMessage({
        type: 'reviewResult',
        prKey: 'acme/widget#42',
        result: { summary: 'Generated review.', verdict: 'COMMENT', lineComments: [] },
        diff: '',
        validationDiff: '',
      })
    })
    await waitFor(() => expect(cefQuery).toHaveBeenCalled())

    expect(ref.current?.discardPendingChanges()).toBe(false)
  })

  it('keeps the review visible and offers operation-specific recovery after delete fails', async () => {
    const user = userEvent.setup()
    const cefQuery = vi.fn()
    ;(window as unknown as { cefQuery?: typeof cefQuery }).cefQuery = cefQuery
    render(<ReviewPane pr={pr} />)

    act(() => {
      hostMessage({
        type: 'draftLoaded',
        prKey: 'acme/widget#42',
        prState: 'DRAFT_PRESENT',
        reviewId: 'draft-1',
        result: { summary: 'Keep this review visible.', verdict: 'COMMENT', lineComments: [] },
        diff: '',
        validationDiff: '',
      })
    })
    await user.click(screen.getByRole('button', { name: 'Delete' }))
    await user.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Delete' }))
    act(() => {
      hostMessage({ type: 'draftDeleteError', prKey: 'acme/widget#42', message: 'Delete failed.' })
    })

    expect(screen.getByText('Keep this review visible.')).toBeInTheDocument()
    expect(screen.getByText('Delete failed.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Retry delete' }))
    const deletes = cefQuery.mock.calls
      .map(([arg]) => JSON.parse(arg.request) as { type: string })
      .filter((message) => message.type === 'deleteDraft')
    expect(deletes).toHaveLength(2)

    act(() => {
      hostMessage({ type: 'draftDeleteError', prKey: 'acme/widget#42', message: 'Still failed.' })
    })
    await user.click(screen.getByRole('button', { name: 'Keep draft' }))
    expect(screen.queryByText('Still failed.')).not.toBeInTheDocument()
    expect(screen.getByText('Keep this review visible.')).toBeInTheDocument()
  })

  it('requires acknowledgement before submitting when the diff cannot be rendered', async () => {
    const user = userEvent.setup()
    ;(window as unknown as { cefQuery?: ReturnType<typeof vi.fn> }).cefQuery = vi.fn()
    render(<ReviewPane pr={pr} />)

    act(() => {
      hostMessage({
        type: 'draftLoaded',
        prKey: 'acme/widget#42',
        prState: 'DRAFT_PRESENT',
        reviewId: 'draft-1',
        result: { summary: 'Review summary.', verdict: 'COMMENT', lineComments: [] },
        diff: 'not a unified diff',
        validationDiff: 'not a unified diff',
      })
    })
    await user.click(screen.getByRole('button', { name: 'Comment' }))

    expect(screen.getByText('The diff could not be rendered. Review the raw diff before publishing.')).toBeInTheDocument()
    const submit = screen.getByRole('button', { name: 'Submit Comment' })
    expect(submit).toBeDisabled()
    await user.click(screen.getByRole('checkbox'))
    expect(submit).toBeEnabled()
  })

  it('keeps the selected submit option as the primary action', async () => {
    const user = userEvent.setup()
    ;(window as unknown as { cefQuery?: ReturnType<typeof vi.fn> }).cefQuery = vi.fn()
    render(<ReviewPane pr={pr} />)

    act(() => {
      hostMessage({
        type: 'draftLoaded',
        prKey: 'acme/widget#42',
        prState: 'DRAFT_PRESENT',
        reviewId: 'draft-1',
        result: { summary: 'Changes needed.', verdict: 'REQUEST_CHANGES', lineComments: [] },
        diff: '',
        validationDiff: '',
      })
    })

    expect(screen.getByRole('button', { name: 'Request Changes' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'More submit options' }))
    await user.click(screen.getByRole('menuitem', { name: 'Approve' }))
    await user.click(await screen.findByRole('button', { name: 'Cancel' }))

    expect(screen.getByRole('button', { name: 'Approve' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Request Changes' })).not.toBeInTheDocument()
  })

  it('submits Comment from an Approve review split menu', async () => {
    const user = userEvent.setup()
    const cefQuery = vi.fn()
    ;(window as unknown as { cefQuery?: typeof cefQuery }).cefQuery = cefQuery
    render(<ReviewPane pr={pr} />)

    act(() => {
      hostMessage({
        type: 'draftLoaded',
        prKey: 'acme/widget#42',
        prState: 'DRAFT_PRESENT',
        reviewId: 'draft-1',
        result: { summary: 'Looks good.', verdict: 'APPROVE', lineComments: [] },
        diff: '',
        validationDiff: '',
      })
    })

    await user.click(screen.getByRole('button', { name: 'More submit options' }))
    const commentOption = screen.getByRole('menuitem', { name: 'Comment' })
    expect(commentOption).not.toHaveAttribute('data-disabled')
    await user.click(commentOption)
    await user.click(await screen.findByRole('button', { name: 'Submit Comment' }))

    const outgoing = cefQuery.mock.calls
      .map(([arg]) => JSON.parse(arg.request) as { type: string; verdict?: string })
      .filter((message) => message.type === 'submitReview')
    expect(outgoing).toEqual([expect.objectContaining({ verdict: 'COMMENT' })])
  })

  it('sends one submitReview message when the confirmation action is clicked twice before rerender', () => {
    const cefQuery = vi.fn()
    ;(window as unknown as { cefQuery?: typeof cefQuery }).cefQuery = cefQuery
    render(<ReviewPane pr={pr} />)

    act(() => {
      hostMessage({
        type: 'draftLoaded',
        prKey: 'acme/widget#42',
        prState: 'DRAFT_PRESENT',
        reviewId: 'draft-1',
        result: { summary: 'Looks good.', verdict: 'APPROVE', lineComments: [] },
        diff: '',
        validationDiff: '',
      })
    })

    fireEvent.click(screen.getByRole('button', { name: 'Approve' }))
    const confirm = screen.getByRole('button', { name: 'Submit Approve' })
    fireEvent.click(confirm)
    fireEvent.click(confirm)

    const outgoing = cefQuery.mock.calls
      .map(([arg]) => JSON.parse(arg.request) as { type: string })
      .filter((message) => message.type === 'submitReview')
    expect(outgoing).toHaveLength(1)
  })

  it('keeps a review dirty until the matching save acknowledgement', async () => {
    const cefQuery = vi.fn()
    const onDirtyStateChange = vi.fn()
    ;(window as unknown as { cefQuery?: typeof cefQuery }).cefQuery = cefQuery
    render(<ReviewPane pr={pr} onDirtyStateChange={onDirtyStateChange} />)

    act(() => {
      hostMessage({
        type: 'reviewResult',
        prKey: 'acme/widget#42',
        result: { summary: 'Generated review.', verdict: 'COMMENT', lineComments: [] },
        diff: '',
        validationDiff: '',
      })
    })

    let saveId = 0
    await waitFor(() => {
      const save = cefQuery.mock.calls
        .map(([arg]) => JSON.parse(arg.request) as {
          type: string
          saveId?: number
          generatedResult?: { summary: string }
        })
        .find((message) => message.type === 'saveDraft')
      expect(save?.saveId).toBeTypeOf('number')
      expect(save?.generatedResult?.summary).toBe('Generated review.')
      saveId = save?.saveId ?? 0
      expect(onDirtyStateChange).toHaveBeenLastCalledWith(true)
      expect(screen.getByRole('button', { name: 'Saving…' })).toBeDisabled()
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
    expect(screen.getByRole('button', { name: 'Saving…' })).toBeDisabled()

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
      expect(screen.getByText('Saved to GitHub')).toBeVisible()
      expect(screen.queryByRole('button', { name: 'Save now' })).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: /Review quality.*No risks/ })).toBeVisible()
      expect(onDirtyStateChange).toHaveBeenLastCalledWith(false)
    })
  })

  it('revalidates an unanchored comment when the full diff arrives late', async () => {
    const cefQuery = vi.fn()
    ;(window as unknown as { cefQuery?: typeof cefQuery }).cefQuery = cefQuery
    render(<ReviewPane pr={pr} />)

    act(() => {
      hostMessage({
        type: 'reviewResult',
        prKey: 'acme/widget#42',
        result: {
          summary: 'Generated review.',
          verdict: 'COMMENT',
          lineComments: [{ file: 'src/value.ts', line: 1, type: 'issue', body: 'Handle this value.' }],
        },
        diff: '',
        validationDiff: '',
      })
    })
    expect(await screen.findByText('· 1 unanchored')).toBeInTheDocument()

    act(() => {
      hostMessage({
        type: 'validationDiffUpdated',
        prKey: 'acme/widget#42',
        validationDiff: [
          'diff --git a/src/value.ts b/src/value.ts',
          '--- a/src/value.ts',
          '+++ b/src/value.ts',
          '@@ -0,0 +1 @@',
          '+const value = readValue()',
        ].join('\n'),
      })
    })

    await waitFor(() => {
      expect(screen.queryByText('· 1 unanchored')).not.toBeInTheDocument()
      expect(screen.getByText('Generated review.')).toBeInTheDocument()
    })
  })
})
