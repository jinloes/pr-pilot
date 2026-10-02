import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createRef } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ReviewPane, type ReviewPaneHandle } from './ReviewPane'
import { coverageTrailer, diffWithFiles, hostMessage, pr } from './reviewPaneTestUtils'

afterEach(() => {
  vi.restoreAllMocks()
  delete (window as unknown as { cefQuery?: unknown }).cefQuery
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
      loadDiffs(diff, diff)
    }

    function loadDiffs(diff: string, validationDiff: string) {
      act(() => {
        hostMessage({
          type: 'draftLoaded',
          prKey: 'acme/widget#42',
          prState: 'NO_DRAFT',
          diff,
          validationDiff,
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
      expect(screen.getByText('Recommended: standard review.')).toBeInTheDocument()
      expect(screen.getByText(
        'Chunked review works in file batches, so it can miss cross-file interactions. Use it when a standard '
        + 'review would leave files out.',
      )).toBeInTheDocument()

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

    it('names the coverage gain only when chunked review includes files single-pass review omits', async () => {
      const user = userEvent.setup()
      ;(window as unknown as { cefQuery?: ReturnType<typeof vi.fn> }).cefQuery = vi.fn()
      render(<ReviewPane pr={pr} />)
      loadDiffs(
        `${diffWithFiles(1)}\n${coverageTrailer(2, ['src/file-1.ts', 'src/file-2.ts'])}`,
        diffWithFiles(3),
      )

      await openAdvanced(user)

      expect(screen.getByRole('checkbox', { name: /Use chunked review mode/ })).not.toBeChecked()
      expect(screen.getByText('Recommended for this PR: chunked review.')).toBeInTheDocument()
      expect(screen.getByText('Chunked review includes 2 changed files that single-pass review omits.'))
        .toBeInTheDocument()
      expect(screen.queryByText(/would not add coverage/)).not.toBeInTheDocument()
    })

    it('says chunked review would not add coverage when both diffs omit the same files', async () => {
      const user = userEvent.setup()
      ;(window as unknown as { cefQuery?: ReturnType<typeof vi.fn> }).cefQuery = vi.fn()
      render(<ReviewPane pr={pr} />)
      loadReviewableDiff(`${diffWithFiles(1)}\n${coverageTrailer(2, ['src/huge.ts'])}`)

      await openAdvanced(user)

      expect(screen.getByRole('checkbox', { name: /Use chunked review mode/ })).not.toBeChecked()
      expect(screen.getByText('Recommended: standard review.')).toBeInTheDocument()
      expect(screen.getByText('Chunked review would not add coverage.')).toBeInTheDocument()
      expect(screen.queryByText(/Chunked review includes/)).not.toBeInTheDocument()
    })

    it('keeps the size heuristics when an incomplete diff gains no coverage from chunking', async () => {
      const user = userEvent.setup()
      ;(window as unknown as { cefQuery?: ReturnType<typeof vi.fn> }).cefQuery = vi.fn()
      render(<ReviewPane pr={pr} />)
      loadReviewableDiff(`${diffWithFiles(8)}\n${coverageTrailer(1, ['src/huge.ts'])}`)

      await openAdvanced(user)

      expect(screen.getByRole('checkbox', { name: /Use chunked review mode/ })).not.toBeChecked()
      expect(screen.getByText('Recommended: standard review.')).toBeInTheDocument()
      expect(screen.getByText('Many changed files. Chunked review would not add coverage.')).toBeInTheDocument()
    })

    it('explains which changed files the bounded review diff omits', () => {
      ;(window as unknown as { cefQuery?: ReturnType<typeof vi.fn> }).cefQuery = vi.fn()
      render(<ReviewPane pr={pr} />)
      loadDiffs(
        `${diffWithFiles(1)}\n${coverageTrailer(5, ['src/huge.ts', 'data/generated.json'])}`,
        diffWithFiles(6),
      )

      expect(screen.getByText(
        "5 changed files aren't included in this review because the pull request's diff is larger than 250 KB. "
        + "They won't appear in the diff below, the generated review, or chat. Chunked review can include 5 of them.",
      )).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Include them with chunked review' })).toBeVisible()
      const omitted = screen.getByText('Show omitted files').closest('details')!
      expect(within(omitted).getByText('src/huge.ts')).toBeInTheDocument()
      expect(within(omitted).getByText('data/generated.json')).toBeInTheDocument()
      expect(within(omitted).getByText('+3 more not listed')).toBeInTheDocument()
      expect(screen.queryByText(/\[pr-pilot:/)).not.toBeInTheDocument()
      expect(screen.queryByText(/Diff display and chat context are/)).not.toBeInTheDocument()
    })

    it('says at least when the source diff was too large to scan completely', () => {
      ;(window as unknown as { cefQuery?: ReturnType<typeof vi.fn> }).cefQuery = vi.fn()
      render(<ReviewPane pr={pr} />)
      loadReviewableDiff(`${diffWithFiles(1)}\n${coverageTrailer(3, ['src/huge.ts'], 'incomplete')}`)

      expect(screen.getByText(
        "At least 3 changed files aren't included in this review because the pull request's diff is larger than "
        + "250 KB. They won't appear in the diff below, the generated review, or chat. Chunked review would not add "
        + 'these files. Consider splitting the pull request.',
      )).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /chunked review/ })).not.toBeInTheDocument()
      expect(screen.getByText('+2 more not listed')).toBeInTheDocument()
    })

    it('shows no coverage banner and keeps the single-pass recommendation for a complete diff', async () => {
      const user = userEvent.setup()
      ;(window as unknown as { cefQuery?: ReturnType<typeof vi.fn> }).cefQuery = vi.fn()
      render(<ReviewPane pr={pr} />)
      loadDiffs(diffWithFiles(1), diffWithFiles(1))

      await openAdvanced(user)

      expect(screen.queryByText(/included in this review/)).not.toBeInTheDocument()
      expect(screen.queryByText('Show omitted files')).not.toBeInTheDocument()
      expect(screen.getByText('Single-pass review is likely sufficient.')).toBeInTheDocument()
    })

    it('keeps the banner beside a saved review and never renders trailer lines in the diff view', () => {
      render(<ReviewPane pr={pr} />)
      const diff = `${diffWithFiles(1)}\n${coverageTrailer(1, ['src/huge.ts'])}`
      act(() => {
        hostMessage({
          type: 'draftLoaded',
          prKey: 'acme/widget#42',
          prState: 'DRAFT_PRESENT',
          reviewId: 'draft-1',
          result: { summary: 'Saved review.', verdict: 'COMMENT', lineComments: [] },
          diff,
          validationDiff: diff,
        })
      })

      expect(screen.getByText(
        "1 changed file isn't included in this review because the pull request's diff is larger than 250 KB. "
        + "They won't appear in the diff below, the generated review, or chat. Chunked review would not add these "
        + 'files. Consider splitting the pull request.',
      )).toBeInTheDocument()
      expect(screen.getAllByText(/src\/file-0\.ts/).length).toBeGreaterThan(0)
      expect(screen.queryByText(/\[pr-pilot:/)).not.toBeInTheDocument()
      expect(screen.getByTitle('Context: PR title/body, diff excerpt, generated review')).toBeInTheDocument()
    })

    it('shows only the banner when every changed file was omitted', () => {
      render(<ReviewPane pr={pr} />)
      const diff = coverageTrailer(2, [])
      act(() => {
        hostMessage({
          type: 'draftLoaded',
          prKey: 'acme/widget#42',
          prState: 'DRAFT_PRESENT',
          reviewId: 'draft-1',
          result: { summary: 'Saved review.', verdict: 'COMMENT', lineComments: [] },
          diff,
          validationDiff: diff,
        })
      })

      expect(screen.getByText(
        "2 changed files aren't included in this review because the pull request's diff is larger than 250 KB. "
        + "They won't be part of the generated review or chat. Chunked review would not add these files. Consider "
        + 'splitting the pull request.',
      )).toBeInTheDocument()
      expect(screen.getByText('2 omitted files are not listed.')).toBeInTheDocument()
      expect(screen.queryByText(/\[pr-pilot:/)).not.toBeInTheDocument()
    })

    it('warns that files may be missing when the scan stopped before finding an omission', () => {
      render(<ReviewPane pr={pr} />)
      loadDiffs(`${diffWithFiles(1)}\n${coverageTrailer(0, [], 'incomplete')}`, diffWithFiles(1))

      expect(screen.getByText(
        "Some changed files may not be included in this review because the pull request's diff is larger than 250 KB. "
        + "They won't appear in the diff below, the generated review, or chat. Chunked review would not add these "
        + 'files. Consider splitting the pull request.',
      )).toBeInTheDocument()
      expect(screen.queryByText('Show omitted files')).not.toBeInTheDocument()
      expect(screen.queryByText(/not listed/)).not.toBeInTheDocument()
    })

    it('keeps chunking off for a small PR', async () => {
      const user = userEvent.setup()
      ;(window as unknown as { cefQuery?: ReturnType<typeof vi.fn> }).cefQuery = vi.fn()
      render(<ReviewPane pr={pr} />)
      loadReviewableDiff(diffWithFiles(1))

      await openAdvanced(user)

      expect(screen.getByRole('checkbox', { name: /Use chunked review mode/ })).not.toBeChecked()
      expect(screen.getByText('Recommended: standard review.')).toBeInTheDocument()
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
    await user.click(screen.getByRole('button', { name: 'Submit review…' }))

    expect(screen.getByText('The diff could not be rendered. Review the raw diff before publishing.')).toBeInTheDocument()
    const submit = screen.getByRole('button', { name: 'Publish as Comment' })
    expect(submit).toBeDisabled()
    await user.click(screen.getByRole('radio', { name: 'Approve' }))
    expect(screen.getByRole('button', { name: 'Publish as Approve' })).toBeDisabled()
    await user.click(screen.getByRole('checkbox'))
    expect(screen.getByRole('button', { name: 'Publish as Approve' })).toBeEnabled()
  })

  describe('suggested verdict', () => {
    function loadVerdict(verdict: 'APPROVE' | 'REQUEST_CHANGES' | 'COMMENT', summary = 'Looks good.') {
      act(() => {
        hostMessage({
          type: 'draftLoaded',
          prKey: 'acme/widget#42',
          prState: 'DRAFT_PRESENT',
          reviewId: 'draft-1',
          result: { summary, verdict, lineComments: [] },
          diff: '',
          validationDiff: '',
        })
      })
    }

    function submits(cefQuery: ReturnType<typeof vi.fn>) {
      return cefQuery.mock.calls
        .map(([arg]) => JSON.parse(arg.request) as { type: string; verdict?: string; comment?: string })
        .filter((message) => message.type === 'submitReview')
    }

    it('labels the AI verdict as a suggestion in the header and summary and uses one neutral publish button', () => {
      ;(window as unknown as { cefQuery?: ReturnType<typeof vi.fn> }).cefQuery = vi.fn()
      render(<ReviewPane pr={pr} />)
      loadVerdict('REQUEST_CHANGES')

      expect(screen.getAllByText('Suggested: Request changes')).toHaveLength(2)
      const submit = screen.getByRole('button', { name: 'Submit review…' })
      expect(submit).not.toHaveClass('bg-destructive')
      expect(screen.queryByRole('button', { name: 'More submit options' })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Request Changes' })).not.toBeInTheDocument()
    })

    it('preselects the suggestion in a verdict radio group and resets a changed verdict on cancel', async () => {
      const user = userEvent.setup()
      const cefQuery = vi.fn()
      ;(window as unknown as { cefQuery?: typeof cefQuery }).cefQuery = cefQuery
      render(<ReviewPane pr={pr} />)
      loadVerdict('APPROVE')

      await user.click(screen.getByRole('button', { name: 'Submit review…' }))
      const dialog = screen.getByRole('alertdialog')
      expect(within(dialog).getByRole('heading', { name: 'Publish review' })).toBeVisible()
      const group = within(dialog).getByRole('radiogroup', { name: 'Verdict' })
      expect(within(group).getAllByRole('radio').map((radio) => radio.getAttribute('value')))
        .toEqual(['COMMENT', 'APPROVE', 'REQUEST_CHANGES'])
      expect(within(group).getByRole('radio', { name: 'Approve (suggested)' })).toBeChecked()

      await user.click(within(group).getByRole('radio', { name: 'Request changes' }))
      const confirm = within(dialog).getByRole('button', { name: 'Publish as Request changes' })
      expect(confirm).not.toHaveClass('bg-destructive')
      await user.click(within(dialog).getByRole('button', { name: 'Cancel' }))

      await user.click(screen.getByRole('button', { name: 'Submit review…' }))
      expect(screen.getByRole('radio', { name: 'Approve (suggested)' })).toBeChecked()
      expect(screen.getByRole('button', { name: 'Publish as Approve' })).toBeVisible()
      expect(submits(cefQuery)).toEqual([])
    })

    it('publishes exactly the verdict chosen in the dialog', async () => {
      const user = userEvent.setup()
      const cefQuery = vi.fn()
      ;(window as unknown as { cefQuery?: typeof cefQuery }).cefQuery = cefQuery
      render(<ReviewPane pr={pr} />)
      loadVerdict('APPROVE')

      await user.click(screen.getByRole('button', { name: 'Submit review…' }))
      screen.getByRole('radio', { name: 'Approve (suggested)' }).focus()
      await user.keyboard('{ArrowLeft}')
      expect(screen.getByRole('radio', { name: 'Comment' })).toBeChecked()
      await user.click(screen.getByRole('button', { name: 'Publish as Comment' }))

      expect(submits(cefQuery)).toEqual([expect.objectContaining({ verdict: 'COMMENT' })])
    })

    it('sends one submitReview message when the confirmation action is clicked twice before rerender', () => {
      const cefQuery = vi.fn()
      ;(window as unknown as { cefQuery?: typeof cefQuery }).cefQuery = cefQuery
      render(<ReviewPane pr={pr} />)
      loadVerdict('APPROVE')

      fireEvent.click(screen.getByRole('button', { name: 'Submit review…' }))
      const confirm = screen.getByRole('button', { name: 'Publish as Approve' })
      fireEvent.click(confirm)
      fireEvent.click(confirm)

      expect(submits(cefQuery)).toHaveLength(1)
    })
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
