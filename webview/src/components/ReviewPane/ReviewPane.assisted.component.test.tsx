import { act, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ReviewPane } from './ReviewPane'
import { diffWithFiles, hostMessage, pr } from './reviewPaneTestUtils'

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
