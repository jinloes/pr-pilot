import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createRef } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { PR } from '../../bridge/types'
import { I18nProvider } from '../../i18n/I18nProvider'
import { ReviewFooter } from './ReviewFooter'
import { ReviewPane, type ReviewPaneHandle } from './ReviewPane'
import { TooltipProvider } from '@/components/ui/tooltip'
// Vitest collects only *.component.test.* files; this pulls the pure publish-body tests into the run.
import './publishBody.test'

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

function coverageTrailer(omitted: number, paths: string[], scan: 'complete' | 'incomplete' = 'complete'): string {
  return `[pr-pilot:diff-coverage] omitted=${omitted} listed=${paths.length} budget=250000 scan=${scan}\n`
    + paths.map((path) => `[pr-pilot:omitted] ${path}\n`).join('')
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

function outgoingOf(cefQuery: ReturnType<typeof vi.fn>, type: string) {
  return cefQuery.mock.calls
    .map(([arg]) => JSON.parse((arg as { request: string }).request) as Record<string, unknown>)
    .filter((message) => message.type === type)
}

function installHost() {
  const cefQuery = vi.fn()
  ;(window as unknown as { cefQuery?: typeof cefQuery }).cefQuery = cefQuery
  return cefQuery
}

function loadNoDraft(diff: string, validationDiff = diff, available = true) {
  act(() => {
    hostMessage({
      type: 'draftLoaded',
      prKey: 'acme/widget#42',
      prState: 'NO_DRAFT',
      diff,
      validationDiff,
      providerReadiness: available
        ? { provider: 'claude', available: true, detail: 'Ready.' }
        : { provider: 'claude', available: false, detail: 'Claude CLI was not found.' },
    })
  })
}

function loadDraftPresent(result: object, extra: object = {}) {
  act(() => {
    hostMessage({
      type: 'draftLoaded',
      prKey: 'acme/widget#42',
      prState: 'DRAFT_PRESENT',
      reviewId: 'draft-1',
      result,
      diff: '',
      validationDiff: '',
      ...extra,
    })
  })
}

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
