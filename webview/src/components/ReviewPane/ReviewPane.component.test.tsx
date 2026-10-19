import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ReviewFooter } from './ReviewFooter'
import { ReviewPane } from './ReviewPane'
import { TooltipProvider } from '@/components/ui/tooltip'
import {
  coverageTrailer,
  diffWithFiles,
  hostMessage,
  installHost,
  loadDraftPresent,
  loadNoDraft,
  outgoingOf,
  pr,
} from './reviewPaneTestUtils'


afterEach(() => {
  vi.restoreAllMocks()
  delete (window as unknown as { cefQuery?: unknown }).cefQuery
})

describe('no-draft workspace', () => {
  it('shows the read-only diff below a generation card with Generate before the instructions', () => {
    installHost()
    render(<ReviewPane pr={pr} />)
    loadNoDraft(diffWithFiles(2))

    const card = screen.getByTestId('generation-card')
    const generate = within(card).getByRole('button', { name: 'Generate Review' })
    const instructions = within(card).getByText('Review instructions (optional)')
    const navigation = screen.getByRole('navigation', { name: 'Review navigation' })
    expect(generate.compareDocumentPosition(instructions) & Node.DOCUMENT_POSITION_FOLLOWING).not.toBe(0)
    expect(card.compareDocumentPosition(navigation) & Node.DOCUMENT_POSITION_FOLLOWING).not.toBe(0)
    expect(screen.getAllByText(/src\/file-1\.ts/).length).toBeGreaterThan(0)
    expect(screen.queryByRole('button', { name: /^Add comment on/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Verify with AI' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Suggest fix with AI' })).not.toBeInTheDocument()
    expect(screen.getAllByText('Review instructions (optional)')).toHaveLength(1)
  })

  it('keeps the diff visible when the provider is unavailable', () => {
    installHost()
    render(<ReviewPane pr={pr} />)
    loadNoDraft(diffWithFiles(1), diffWithFiles(1), false)

    expect(screen.getByRole('button', { name: 'Generate Review' })).toBeDisabled()
    expect(screen.getByText('Claude CLI was not found.')).toHaveClass('text-status-issue')
    expect(screen.getByRole('button', { name: 'Open Settings' })).toBeVisible()
    expect(screen.getByRole('navigation', { name: 'Review navigation' })).toBeVisible()
  })

  it('renders only the generation card for an empty diff', () => {
    installHost()
    render(<ReviewPane pr={pr} />)
    loadNoDraft('')

    expect(screen.getByTestId('generation-card')).toBeVisible()
    expect(screen.queryByRole('navigation', { name: 'Review navigation' })).not.toBeInTheDocument()
  })

  it('renders the coverage banner once above the diff', () => {
    installHost()
    render(<ReviewPane pr={pr} />)
    loadNoDraft(`${diffWithFiles(1)}\n${coverageTrailer(2, ['src/huge.ts'])}`, diffWithFiles(3))

    expect(screen.getAllByText(/aren't included in this review/)).toHaveLength(1)
    expect(screen.getAllByText('Show omitted files')).toHaveLength(1)
  })
})

describe('incremental review', () => {
  const updatedPr = { ...pr, reviewStatus: 'UPDATED_SINCE_REVIEW' as const }

  function generatingResult(reviewScope?: unknown) {
    act(() => hostMessage({
      type: 'reviewResult',
      prKey: 'acme/widget#42',
      result: { summary: 'Done.', verdict: 'COMMENT', lineComments: [] },
      diff: diffWithFiles(1),
      validationDiff: diffWithFiles(1),
      ...(reviewScope ? { reviewScope } : {}),
    }))
  }

  it('offers the incremental button only when the PR changed since the last review', () => {
    installHost()
    const view = render(<ReviewPane pr={pr} />)
    loadNoDraft(diffWithFiles(1))
    expect(screen.queryByTestId('generate-incremental-review')).not.toBeInTheDocument()

    view.rerender(<ReviewPane pr={{ ...pr, reviewStatus: 'REVIEWED' }} />)
    loadNoDraft(diffWithFiles(1))
    expect(screen.queryByTestId('generate-incremental-review')).not.toBeInTheDocument()
  })

  it('sends an incremental request without diff, chunking, or assisted mode', async () => {
    const user = userEvent.setup()
    const cefQuery = installHost()
    render(<ReviewPane pr={updatedPr} intellijAssistedEnabled />)
    loadNoDraft(`${diffWithFiles(1)}\n${coverageTrailer(1, ['src/file-1.ts'])}`, diffWithFiles(2))
    await user.click(screen.getByRole('button', { name: 'Include them with chunked review' }))

    const button = screen.getByTestId('generate-incremental-review')
    expect(button).toHaveTextContent('Review changes since last review')
    await user.click(button)

    const sent = outgoingOf(cefQuery, 'generateReview')
    expect(sent).toHaveLength(1)
    expect(sent[0]).toMatchObject({ incremental: true, number: 42, owner: 'acme', repo: 'widget' })
    expect(sent[0]).not.toHaveProperty('diff')
    expect(sent[0]).not.toHaveProperty('chunkedReview')
    expect(sent[0]).not.toHaveProperty('intellijAssisted')
  })

  it('omits the incremental flag from an ordinary generation', async () => {
    const user = userEvent.setup()
    const cefQuery = installHost()
    render(<ReviewPane pr={updatedPr} />)
    loadNoDraft(diffWithFiles(1))
    await user.click(screen.getByRole('button', { name: 'Generate Review' }))
    expect(outgoingOf(cefQuery, 'generateReview')[0]).not.toHaveProperty('incremental')
  })

  it('disables the incremental button when the provider is unavailable', () => {
    installHost()
    render(<ReviewPane pr={updatedPr} />)
    loadNoDraft(diffWithFiles(1), diffWithFiles(1), false)
    expect(screen.getByTestId('generate-incremental-review')).toBeDisabled()
  })

  it('shows the incremental scope banner with the short baseline SHA', async () => {
    const user = userEvent.setup()
    installHost()
    render(<ReviewPane pr={updatedPr} />)
    loadNoDraft(diffWithFiles(1))
    await user.click(screen.getByTestId('generate-incremental-review'))
    generatingResult({ kind: 'incremental', baselineSha: 'abcdef1'.padEnd(40, '0') })

    expect(screen.getByTestId('review-scope-banner'))
      .toHaveTextContent('Reviewed only changes since your last review (abcdef1).')
  })

  const reasons: [string, string][] = [
    ['no_prior_review', "you haven't submitted a review on this pull request"],
    ['up_to_date', 'no commits were pushed since your last review'],
    ['baseline_not_in_history', "the commit you last reviewed is no longer in this pull request's history"],
    ['baseline_unavailable', "GitHub couldn't compare against the commit you last reviewed"],
    ['empty_incremental_diff', 'the changes since your last review have no diff'],
    ['incremental_diff_too_large', 'the changes since your last review are too large to compare'],
  ]
  for (const [reason, text] of reasons) {
    it(`explains the full-review fallback for ${reason}`, async () => {
      const user = userEvent.setup()
      installHost()
      render(<ReviewPane pr={updatedPr} />)
      loadNoDraft(diffWithFiles(1))
      await user.click(screen.getByTestId('generate-incremental-review'))
      generatingResult({ kind: 'full', fallbackReason: reason })

      expect(screen.getByTestId('review-scope-banner')).toHaveTextContent(
        `Couldn't review only new changes (${text}). Reviewed the full pull request instead.`)
    })
  }

  it('shows no scope banner for an ordinary result', async () => {
    const user = userEvent.setup()
    installHost()
    render(<ReviewPane pr={updatedPr} />)
    loadNoDraft(diffWithFiles(1))
    await user.click(screen.getByRole('button', { name: 'Generate Review' }))
    generatingResult()
    expect(screen.queryByTestId('review-scope-banner')).not.toBeInTheDocument()
  })
})

describe('coverage banner remedy', () => {
  it('turns on chunked mode from the no-draft banner without starting a review', async () => {
    const user = userEvent.setup()
    const cefQuery = installHost()
    render(<ReviewPane pr={pr} />)
    loadNoDraft(`${diffWithFiles(1)}\n${coverageTrailer(2, ['src/file-1.ts', 'src/file-2.ts'])}`, diffWithFiles(3))

    const banner = screen.getByText(/aren't included in this review/).closest('[role="alert"]') as HTMLElement
    expect(banner).not.toHaveTextContent(/single-pass/i)
    expect(banner).not.toHaveTextContent(/budget/i)
    expect(within(banner).getAllByRole('button')).toHaveLength(1)
    await user.click(within(banner).getByRole('button', { name: 'Include them with chunked review' }))

    expect(banner).toHaveTextContent('Chunked review is on. The next review will include 2 more files.')
    expect(within(banner).queryByRole('button')).not.toBeInTheDocument()
    expect(outgoingOf(cefQuery, 'generateReview')).toEqual([])
    await user.click(screen.getByText('Review instructions (optional)'))
    await user.click(screen.getByText('Advanced review options'))
    expect(screen.getByRole('checkbox', { name: /Use chunked review mode/ })).toBeChecked()
  })

  it('offers chunked review for the next regeneration from a saved draft without regenerating', async () => {
    const user = userEvent.setup()
    const cefQuery = installHost()
    render(<ReviewPane pr={pr} />)
    act(() => {
      hostMessage({
        type: 'draftLoaded',
        prKey: 'acme/widget#42',
        prState: 'DRAFT_PRESENT',
        reviewId: 'draft-1',
        result: { summary: 'Saved review.', verdict: 'COMMENT', lineComments: [] },
        diff: `${diffWithFiles(1)}\n${coverageTrailer(1, ['src/file-1.ts'])}`,
        validationDiff: diffWithFiles(2),
      })
    })

    expect(screen.getByText(/Chunked review can include 1 of them\./)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Use chunked review for the next regeneration' }))

    expect(screen.getByText(/Chunked review is on\. The next review will include 1 more file\./)).toBeInTheDocument()
    expect(outgoingOf(cefQuery, 'generateReview')).toEqual([])
  })
})

describe('regeneration options placement', () => {
  it('moves instructions after the diff as Regeneration options and keeps values', async () => {
    const user = userEvent.setup()
    installHost()
    render(<ReviewPane pr={pr} />)
    loadNoDraft(diffWithFiles(1))
    await user.click(screen.getByText('Review instructions (optional)'))
    await user.type(screen.getByLabelText('Focus areas'), 'security')

    act(() => {
      hostMessage({
        type: 'draftLoaded',
        prKey: 'acme/widget#42',
        prState: 'DRAFT_PRESENT',
        reviewId: 'draft-1',
        result: { summary: 'Saved review.', verdict: 'COMMENT', lineComments: [] },
        diff: diffWithFiles(1),
        validationDiff: diffWithFiles(1),
      })
    })

    const options = screen.getByText('Regeneration options').closest('details')!
    const summary = screen.getByText('Saved review.')
    const navigation = screen.getByRole('navigation', { name: 'Review navigation' })
    expect(summary.compareDocumentPosition(options) & Node.DOCUMENT_POSITION_FOLLOWING).not.toBe(0)
    expect(navigation.compareDocumentPosition(options) & Node.DOCUMENT_POSITION_FOLLOWING).not.toBe(0)
    expect(screen.queryByText('Review instructions (optional)')).not.toBeInTheDocument()
    expect(screen.getByText('1 override applied')).toBeInTheDocument()
    expect(screen.getByLabelText('Focus areas')).toHaveValue('security')
  })

  it('omits the instructions in the submitted and authentication-error states', () => {
    installHost()
    render(<ReviewPane pr={pr} />)
    loadDraftPresent({ summary: 'Saved review.', verdict: 'COMMENT', lineComments: [] })
    expect(screen.getByText('Regeneration options')).toBeInTheDocument()

    act(() => hostMessage({ type: 'reviewSubmitted', prKey: 'acme/widget#42' }))
    expect(screen.getByText('Review submitted.')).toBeVisible()
    expect(screen.queryByTestId('review-overrides-disclosure')).not.toBeInTheDocument()

    act(() => {
      hostMessage({
        type: 'draftLoaded',
        prKey: 'acme/widget#42',
        prState: 'NO_DRAFT',
        status: 'GitHub authentication failed. Run gh auth login.',
        diff: diffWithFiles(1),
      })
    })
    expect(screen.getByText('GitHub authentication failed. Run gh auth login.')).toBeVisible()
    expect(screen.queryByTestId('review-overrides-disclosure')).not.toBeInTheDocument()
  })
})

describe('regenerate confirmation', () => {
  it('states that regeneration replaces edits and regenerates exactly once on confirm', async () => {
    const user = userEvent.setup()
    const cefQuery = installHost()
    render(<ReviewPane pr={pr} />)
    loadDraftPresent({ summary: 'Saved review.', verdict: 'COMMENT', lineComments: [] })

    await user.click(screen.getByRole('button', { name: 'Regenerate' }))
    const dialog = screen.getByRole('alertdialog')
    expect(within(dialog).getByRole('heading', { name: 'Regenerate review?' })).toBeVisible()
    expect(dialog).toHaveTextContent(
      'PR Pilot will generate a new review. When it finishes, it replaces this draft on GitHub, including comments '
      + 'you edited or added. The current draft stays visible until then.',
    )
    await user.click(within(dialog).getByRole('button', { name: 'Keep draft' }))
    expect(outgoingOf(cefQuery, 'generateReview')).toEqual([])

    await user.click(screen.getByRole('button', { name: 'Regenerate' }))
    await user.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Regenerate' }))
    expect(outgoingOf(cefQuery, 'generateReview')).toHaveLength(1)
  })

  it('confirms before regenerating an unsaved review too', async () => {
    const user = userEvent.setup()
    const onRegenerate = vi.fn()
    render(
      <TooltipProvider>
        <ReviewFooter
          state={{
            kind: 'reviewUnsaved',
            result: { summary: 'Fresh review.', verdict: 'COMMENT', lineComments: [] },
            diff: '',
            validationDiff: '',
          }}
          saving={false}
          autosaveDirty
          submitting={false}
          deleting={false}
          onSave={vi.fn()}
          onSubmit={vi.fn()}
          onRegenerate={onRegenerate}
          onDelete={vi.fn()}
          onRunQualityCheck={vi.fn()}
          inlineCommentCount={0}
          publishSections={{ generalNotes: [], unanchored: [], inlineCount: 0 }}
          commentsMovedToBody={false}
          qualityReport={null}
          diffUnavailable={false}
        />
      </TooltipProvider>,
    )

    await user.click(screen.getByRole('button', { name: 'Regenerate' }))
    expect(onRegenerate).not.toHaveBeenCalled()
    expect(screen.getByRole('heading', { name: 'Regenerate review?' })).toBeVisible()
    await user.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Regenerate' }))
    expect(onRegenerate).toHaveBeenCalledOnce()
  })
})

describe('narrow review footer', () => {
  const originalWidth = window.innerWidth

  afterEach(() => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: originalWidth })
  })

  function renderNarrow() {
    Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: 400 })
    const cefQuery = installHost()
    render(<ReviewPane pr={pr} />)
    loadDraftPresent({ summary: 'Saved review.', verdict: 'COMMENT', lineComments: [] })
    return cefQuery
  }

  it('keeps save status and publishing in one row and moves secondary actions into a menu', async () => {
    const user = userEvent.setup()
    renderNarrow()
    const footer = screen.getByTestId('review-footer')

    expect(within(footer).queryByRole('button', { name: 'Regenerate' })).not.toBeInTheDocument()
    expect(within(footer).queryByRole('button', { name: 'Delete' })).not.toBeInTheDocument()
    expect(within(footer).getByRole('status')).toHaveTextContent('Saved')
    expect(within(footer).getByRole('button', { name: 'Submit review…' })).toBeVisible()

    await user.click(within(footer).getByRole('button', { name: 'More review actions' }))
    const items = screen.getAllByRole('menuitem').map((item) => item.textContent)
    expect(items).toEqual(['Regenerate', 'Review quality · No risks', 'Delete draft'])
    expect(screen.getByRole('menuitem', { name: 'Delete draft' })).toHaveClass('text-status-issue')
  })

  it('keeps the regenerate and delete confirmations when opened from the menu', async () => {
    const user = userEvent.setup()
    const cefQuery = renderNarrow()

    await user.click(screen.getByRole('button', { name: 'More review actions' }))
    await user.click(screen.getByRole('menuitem', { name: 'Regenerate' }))
    expect(await screen.findByRole('heading', { name: 'Regenerate review?' })).toBeVisible()
    await user.click(screen.getByRole('button', { name: 'Keep draft' }))
    expect(outgoingOf(cefQuery, 'generateReview')).toEqual([])

    await user.click(screen.getByRole('button', { name: 'More review actions' }))
    await user.click(screen.getByRole('menuitem', { name: 'Delete draft' }))
    expect(await screen.findByRole('heading', { name: 'Delete draft review?' })).toBeVisible()
    await user.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Delete' }))
    expect(outgoingOf(cefQuery, 'deleteDraft')).toHaveLength(1)
  })
})
