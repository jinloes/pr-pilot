import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { I18nProvider } from '../../i18n/I18nProvider'
import { ReviewFooter } from './ReviewFooter'
import { ReviewPane } from './ReviewPane'
import { TooltipProvider } from '@/components/ui/tooltip'
// Vitest collects only *.component.test.* files; this pulls the pure publish-body tests into the run.
import './publishBody.test'
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

describe('IntelliJ-assisted workflow', () => {
  function loadDraft(intellijAssistedEnabled?: boolean) {
    act(() => hostMessage({ type: 'draftLoaded', prKey: 'acme/widget#42', prState: 'NO_DRAFT',
      diff: diffWithFiles(1), providerReadiness: { provider: 'claude', available: true, detail: 'Ready' },
      ...(intellijAssistedEnabled === undefined ? {} : { intellijAssistedEnabled }) }))
  }

  function fixture(intellijAssistedEnabled: boolean | 'absent' = true, sessionFlag = intellijAssistedEnabled === true) {
    const outgoing: Record<string, unknown>[] = []
    ;(window as unknown as { cefQuery: (arg: { request: string }) => void }).cefQuery =
      ({ request }) => outgoing.push(JSON.parse(request))
    const view = render(<ReviewPane pr={pr} intellijAssistedEnabled={sessionFlag} />)
    loadDraft(intellijAssistedEnabled === 'absent' ? undefined : intellijAssistedEnabled)
    const last = (type: string) => { const matches = outgoing.filter(m => m.type === type); return matches[matches.length - 1] }
    const prepared = (operationId: unknown) => ({ type: 'deepReviewPrepared', prKey: 'acme/widget#42',
      operationId, retainedId: '11111111-1111-4111-8111-111111111111', head: 'a'.repeat(40),
      worktree: '/fixture/deep', servers: ['private'], message: 'Manual sync required' })
    return { outgoing, view, last, prepared }
  }

  async function openAdvancedOptions(user: ReturnType<typeof userEvent.setup>) {
    await user.click(screen.getByText('Review instructions (optional)'))
    await user.click(screen.getByText('Advanced review options'))
  }

  async function openContextMenu() {
    fireEvent.contextMenu(screen.getByTestId('review-scroll-body'))
    return await screen.findByRole('menuitem', { name: 'Retained IntelliJ review worktrees' })
  }

  for (const flag of ['absent', false] as const) {
    it(`hides assisted controls by default when the host setting is ${String(flag)}`, async () => {
      const user = userEvent.setup()
      const f = fixture(flag)
      await openAdvancedOptions(user)
      expect(screen.getByText('Use chunked review mode as an advanced fallback')).toBeVisible()
      expect(screen.queryByRole('checkbox', { name: /IntelliJ-assisted/ })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Retained IntelliJ review worktrees' })).not.toBeInTheDocument()
      await user.click(screen.getByRole('button', { name: 'Generate Review' }))
      expect(f.last('generateReview').intellijAssisted).toBeUndefined()
      expect(f.last('generateReview')).toMatchObject({ number: 42, owner: 'acme', repo: 'widget' })
    })
  }

  for (const flag of ['absent', false] as const) {
    it(`hides the context-menu maintenance entry when the session setting is ${String(flag)}`, async () => {
      fixture(flag, false)
      fireEvent.contextMenu(screen.getByTestId('review-scroll-body'))
      expect(await screen.findByRole('menuitem', { name: 'Select text to chat about it' })).toBeInTheDocument()
      expect(screen.queryByRole('menuitem', { name: 'Retained IntelliJ review worktrees' })).not.toBeInTheDocument()
    })
  }

  it('keeps the context-menu maintenance entry reachable with the session setting on', async () => {
    const user = userEvent.setup()
    fixture(false, true)
    await user.click(await openContextMenu())
    expect(screen.getByRole('button', { name: 'Close retained worktrees' })).toBeVisible()
    expect(screen.getByRole('button', { name: 'Refresh retained worktrees' })).toBeInTheDocument()
  })

  it('keeps an active assisted setup and its maintenance entry even when the session setting is off', async () => {
    const user = userEvent.setup()
    const f = fixture(true, false)
    await openAdvancedOptions(user)
    await user.click(screen.getByRole('checkbox', { name: /IntelliJ-assisted/ }))
    await user.click(screen.getByRole('button', { name: 'Generate Review' }))
    act(() => hostMessage(f.prepared(f.last('generateReview').operationId)))
    expect(screen.getByText('/fixture/deep')).toBeVisible()
    expect(screen.getByText('Review maintenance')).toBeInTheDocument()
    expect(await openContextMenu()).toBeVisible()
  })

  it('shows both assisted controls and the context-menu entry with the setting on', async () => {
    const user = userEvent.setup()
    fixture(true)
    await openAdvancedOptions(user)
    expect(screen.getByRole('checkbox', { name: /IntelliJ-assisted/ })).toBeVisible()
    await user.click(screen.getByRole('button', { name: 'Retained IntelliJ review worktrees' }))
    expect(screen.getByRole('button', { name: 'Close retained worktrees' })).toBeVisible()
    await user.click(screen.getByRole('button', { name: 'Close retained worktrees' }))
    expect(await openContextMenu()).toBeVisible()
  })

  it('resets a checked assisted request when the host setting turns off', async () => {
    const user = userEvent.setup()
    const f = fixture(true)
    await openAdvancedOptions(user)
    await user.click(screen.getByRole('checkbox', { name: /IntelliJ-assisted/ }))
    expect(screen.getByRole('checkbox', { name: /IntelliJ-assisted/ })).toBeChecked()
    loadDraft(false)
    expect(screen.queryByRole('checkbox', { name: /IntelliJ-assisted/ })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Generate Review' }))
    expect(f.last('generateReview').intellijAssisted).toBeUndefined()
    loadDraft(true)
    expect(screen.getByRole('checkbox', { name: /IntelliJ-assisted/ })).not.toBeChecked()
  })

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
    f.view.rerender(<ReviewPane pr={null} intellijAssistedEnabled />)
    await user.click(screen.getByText('Review maintenance'))
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

  it('presents an actionable empty state and keeps maintenance secondary', async () => {
    const user = userEvent.setup()
    const onShowList = vi.fn()
    const view = render(<ReviewPane pr={null} onShowList={onShowList} intellijAssistedEnabled />)

    expect(screen.getByRole('heading', { name: 'Choose a pull request to begin' })).toBeVisible()
    expect(screen.getByText('Select a pull request to open its diff and review actions.')).toBeVisible()
    expect(screen.getByText('Review maintenance')).toBeVisible()
    expect(
      screen.getByRole('heading', { name: 'Choose a pull request to begin' })
        .compareDocumentPosition(screen.getByText('Review maintenance')) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBe(Node.DOCUMENT_POSITION_FOLLOWING)
    await user.click(screen.getByRole('button', { name: 'Show pull requests' }))
    expect(onShowList).toHaveBeenCalledOnce()

    view.rerender(<ReviewPane pr={pr} onShowList={onShowList} />)
    expect(screen.queryByRole('heading', { name: 'Choose a pull request to begin' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Show pull requests' })).not.toBeInTheDocument()
  })

  for (const flag of [undefined, false] as const) {
    it(`omits retained-worktree maintenance from the empty state when the session setting is ${String(flag)}`, () => {
      render(<ReviewPane pr={null} intellijAssistedEnabled={flag} />)

      expect(screen.getByRole('heading', { name: 'Choose a pull request to begin' })).toBeVisible()
      expect(screen.queryByText('Review maintenance')).not.toBeInTheDocument()
      expect(screen.queryByRole('region', { name: 'IntelliJ-assisted review setup' })).not.toBeInTheDocument()
    })
  }
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
          summary="Fresh review."
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

describe('publish review body', () => {
  async function openPublish(user: ReturnType<typeof userEvent.setup>) {
    await user.click(screen.getByRole('button', { name: 'Submit review…' }))
    return screen.getByRole('alertdialog')
  }

  it('prefills the review body with the summary and publishes the edited text', async () => {
    const user = userEvent.setup()
    const cefQuery = installHost()
    render(<ReviewPane pr={pr} />)
    loadDraftPresent({ summary: '  Looks good.  ', verdict: 'APPROVE', lineComments: [] })

    const dialog = await openPublish(user)
    const body = within(dialog).getByRole('textbox', { name: 'Review body' })
    expect(body).toHaveValue('Looks good.')
    expect(body).toHaveAccessibleDescription(
      "Published as the review's main comment. It starts with the generated summary; edit or clear it.",
    )
    expect(within(dialog).queryByText('Final review body (optional)')).not.toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Publish as Approve' }))

    expect(outgoingOf(cefQuery, 'submitReview')).toEqual([
      expect.objectContaining({ verdict: 'APPROVE', comment: 'Looks good.' }),
    ])
  })

  it('warns about the canned fallback when the body is cleared and nothing else is published', async () => {
    const user = userEvent.setup()
    const cefQuery = installHost()
    render(<I18nProvider><ReviewPane pr={pr} /></I18nProvider>)
    loadDraftPresent({ summary: 'Looks good.', verdict: 'APPROVE', lineComments: [] })

    const dialog = await openPublish(user)
    expect(within(dialog).queryByRole('status')).not.toBeInTheDocument()
    await user.clear(within(dialog).getByRole('textbox', { name: 'Review body' }))
    expect(within(dialog).getByRole('status'))
      .toHaveTextContent('The review body is empty, so PR Pilot will publish “Looks good to me!”.')
    await user.click(within(dialog).getByRole('radio', { name: 'Request changes' }))
    expect(within(dialog).getByRole('status'))
      .toHaveTextContent('The review body is empty, so PR Pilot will publish “Requesting changes.”.')
    await user.click(within(dialog).getByRole('radio', { name: 'Approve (suggested)' }))
    await user.click(within(dialog).getByRole('button', { name: 'Publish as Approve' }))

    expect(outgoingOf(cefQuery, 'submitReview')).toEqual([expect.objectContaining({ comment: '' })])
  })

  it('previews general notes and unanchored comments and counts only inline comments', async () => {
    const user = userEvent.setup()
    installHost()
    render(<ReviewPane pr={pr} />)
    const diff = diffWithFiles(1)
    loadDraftPresent({
      summary: 'Summary.',
      verdict: 'COMMENT',
      lineComments: [
        { file: '', line: 1, type: 'note', body: 'Overall, tidy change.' },
        { file: 'src/file-0.ts', line: 1, type: 'issue', body: 'Inline finding.' },
        { file: 'src/missing.ts', line: 99, type: 'issue', body: 'Detached finding.' },
      ],
    }, { diff, validationDiff: diff })

    const dialog = await openPublish(user)
    expect(dialog).toHaveTextContent('This will publish the pending GitHub review with 1 inline comment.')
    const region = within(dialog).getByRole('region', { name: 'Also published in the review body' })
    expect(region).toHaveAttribute('tabindex', '0')
    expect(within(region).getByText('General Notes:')).toBeVisible()
    expect(within(region).getByText('Overall, tidy change.')).toBeVisible()
    expect(within(region).getByText('Comments not attached inline (invalid diff positions):')).toBeVisible()
    expect(within(region).getByText('src/missing.ts:99')).toHaveClass('font-mono')
    expect(region).toHaveTextContent('Detached finding.')
    expect(within(dialog).queryByRole('status')).not.toBeInTheDocument()
    await user.clear(within(dialog).getByRole('textbox', { name: 'Review body' }))
    expect(within(dialog).queryByRole('status')).not.toBeInTheDocument()
  })

  it('discards edits on cancel and prefills the summary again on the next open', async () => {
    const user = userEvent.setup()
    installHost()
    render(<ReviewPane pr={pr} />)
    loadDraftPresent({ summary: 'Generated summary.', verdict: 'COMMENT', lineComments: [] })

    let dialog = await openPublish(user)
    const body = within(dialog).getByRole('textbox', { name: 'Review body' })
    await user.clear(body)
    await user.type(body, 'My own words')
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }))

    dialog = await openPublish(user)
    expect(within(dialog).getByRole('textbox', { name: 'Review body' })).toHaveValue('Generated summary.')
  })

  it('mentions comments moved into the body by a save until a new draft loads', async () => {
    const user = userEvent.setup()
    const cefQuery = installHost()
    render(<ReviewPane pr={pr} />)
    const diff = diffWithFiles(1)
    act(() => {
      hostMessage({
        type: 'reviewResult',
        prKey: 'acme/widget#42',
        result: {
          summary: 'Summary.',
          verdict: 'COMMENT',
          lineComments: [{ file: 'src/file-0.ts', line: 1, type: 'issue', body: 'Inline finding.' }],
        },
        diff,
        validationDiff: diff,
      })
    })
    const save = await waitFor(() => {
      const [message] = outgoingOf(cefQuery, 'saveDraft')
      expect(message).toBeDefined()
      return message
    })
    act(() => {
      hostMessage({
        type: 'draftSaved',
        prKey: 'acme/widget#42',
        saveId: save.saveId,
        reviewId: 'draft-2',
        commentsDropped: true,
      })
    })

    const dialog = await openPublish(user)
    expect(within(dialog).getByRole('region', { name: 'Also published in the review body' }))
      .toHaveTextContent('Comments GitHub could not place inline when this draft was saved are also included.')
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }))

    loadDraftPresent({ summary: 'Summary.', verdict: 'COMMENT', lineComments: [] })
    const reopened = await openPublish(user)
    expect(within(reopened).queryByRole('region', { name: 'Also published in the review body' })).not.toBeInTheDocument()
  })

  it('tells the reviewer that an imported draft keeps its GitHub body text', async () => {
    const user = userEvent.setup()
    installHost()
    render(<ReviewPane pr={pr} />)
    loadDraftPresent({ summary: '', verdict: 'COMMENT', lineComments: [] }, { importedFromGitHub: true })

    const dialog = await openPublish(user)
    expect(within(dialog).getByRole('region', { name: 'Also published in the review body' }))
      .toHaveTextContent("Text already in the GitHub draft's review body is kept.")
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

describe('remembered repository instructions', () => {
  function fixture(repositoryInstructions?: string) {
    const outgoing: Record<string, unknown>[] = []
    ;(window as unknown as { cefQuery: (arg: { request: string }) => void }).cefQuery =
      ({ request }) => outgoing.push(JSON.parse(request))
    const view = render(<I18nProvider><ReviewPane pr={pr} /></I18nProvider>)
    act(() => hostMessage({ type: 'draftLoaded', prKey: 'acme/widget#42', prState: 'NO_DRAFT',
      diff: diffWithFiles(1), providerReadiness: { provider: 'claude', available: true, detail: 'Ready' },
      ...(repositoryInstructions === undefined ? {} : { repositoryInstructions }) }))
    const saves = () => outgoing.filter((message) => message.type === 'saveRepositoryInstructions')
    return { outgoing, view, saves }
  }

  async function openInstructions(user: ReturnType<typeof userEvent.setup>) {
    await user.click(screen.getByText('Review instructions (optional)'))
    return screen.getByLabelText<HTMLTextAreaElement>('Remembered instructions for acme/widget')
  }

  it('prefills the saved instructions and marks the disclosure as applying them', async () => {
    const user = userEvent.setup()
    fixture('API-only PRs precede the service PR.')
    expect(screen.getByText('Remembered for repository')).toBeInTheDocument()
    const field = await openInstructions(user)
    expect(field.value).toBe('API-only PRs precede the service PR.')
    expect(screen.getByRole('button', { name: 'Remember for this repository' })).toBeDisabled()
  })

  it('sends the trimmed text for this PR repository and confirms after the host saves', async () => {
    const user = userEvent.setup()
    const { saves } = fixture()
    expect(screen.queryByText('Remembered for repository')).not.toBeInTheDocument()
    const field = await openInstructions(user)
    await user.type(field, '  Skip service follow-ups on API PRs.  ')
    await user.click(screen.getByRole('button', { name: 'Remember for this repository' }))
    expect(saves()).toEqual([{ protocolVersion: 1, type: 'saveRepositoryInstructions', number: 42, owner: 'acme', repo: 'widget',
      instructions: 'Skip service follow-ups on API PRs.' }])
    expect(screen.getByRole('button', { name: 'Saving…' })).toBeDisabled()

    act(() => hostMessage({ type: 'repositoryInstructionsSaved', prKey: 'acme/widget#42',
      instructions: 'Skip service follow-ups on API PRs.' }))
    expect(screen.getByText('Remembered for acme/widget.')).toHaveAttribute('role', 'status')
    expect(field.value).toBe('Skip service follow-ups on API PRs.')
    expect(screen.getByText('Remembered for repository')).toBeInTheDocument()
  })

  it('offers Forget when the saved text is cleared and reports the empty result', async () => {
    const user = userEvent.setup()
    const { saves } = fixture('Old rule')
    const field = await openInstructions(user)
    await user.clear(field)
    await user.click(screen.getByRole('button', { name: 'Forget for this repository' }))
    expect(saves()[0]).toMatchObject({ instructions: '' })
    act(() => hostMessage({ type: 'repositoryInstructionsSaved', prKey: 'acme/widget#42', instructions: '' }))
    expect(screen.getByText('No instructions are remembered for acme/widget.')).toBeInTheDocument()
    expect(screen.queryByText('Remembered for repository')).not.toBeInTheDocument()
  })

  it('shows a host save error, keeps the edit, and ignores replies for another PR', async () => {
    const user = userEvent.setup()
    fixture('Old rule')
    const field = await openInstructions(user)
    await user.type(field, ' plus more')
    await user.click(screen.getByRole('button', { name: 'Remember for this repository' }))
    act(() => hostMessage({ type: 'repositoryInstructionsSaved', prKey: 'acme/other#7', instructions: 'Wrong repo' }))
    expect(field.value).toBe('Old rule plus more')
    act(() => hostMessage({ type: 'repositoryInstructionsSaveError', prKey: 'acme/widget#42',
      message: 'Could not save PR Pilot settings.' }))
    expect(screen.getByText('Could not save PR Pilot settings.')).toBeInTheDocument()
    expect(field.value).toBe('Old rule plus more')
    expect(screen.getByRole('button', { name: 'Remember for this repository' })).toBeEnabled()
  })

  it('keeps an in-progress edit when the draft reloads', async () => {
    const user = userEvent.setup()
    fixture('Old rule')
    const field = await openInstructions(user)
    await user.type(field, ' edited')
    act(() => hostMessage({ type: 'draftLoaded', prKey: 'acme/widget#42', prState: 'NO_DRAFT',
      diff: diffWithFiles(1), repositoryInstructions: 'Old rule' }))
    expect(field.value).toBe('Old rule edited')
  })

  it('rejects text over the host limit without sending it', async () => {
    const user = userEvent.setup()
    const { saves } = fixture()
    const field = await openInstructions(user)
    fireEvent.change(field, { target: { value: 'x'.repeat(10_001) } })
    await user.click(screen.getByRole('button', { name: 'Remember for this repository' }))
    expect(saves()).toHaveLength(0)
    expect(screen.getByText('Repository instructions are limited to 10,000 characters.')).toBeInTheDocument()
  })
})
