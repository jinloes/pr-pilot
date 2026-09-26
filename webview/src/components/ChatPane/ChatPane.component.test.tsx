import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi, type Mock } from 'vitest'
import type { VerifyResult } from './structuredResult'
import type { PR } from '../../bridge/types'
import { ChatPane } from './ChatPane'

const pr: PR = {
  number: 42,
  title: 'Improve chat layout',
  owner: 'acme',
  repo: 'widget',
  author: 'octocat',
  createdAt: '2026-07-15T00:00:00Z',
  htmlUrl: 'https://github.com/acme/widget/pull/42',
  isDraft: false,
  hasReviewDraft: false,
  reviewStatus: 'UNREVIEWED',
}

describe('ChatPane', () => {
  it('keeps an empty flexible message region so the composer can remain bottom-aligned', () => {
    render(<ChatPane pr={pr} />)

    expect(screen.getByTestId('chat-messages')).toHaveClass('flex-1', 'min-h-0', 'overflow-y-auto')
    expect(screen.getByRole('textbox', { name: 'Ask about this pull request' })).toBeVisible()
  })

  it('scrolls verification messages within the chat region', async () => {
    Object.assign(window, { cefQuery: vi.fn() })
    const onPendingMessageSent = vi.fn()
    const { rerender } = render(<ChatPane pr={pr} />)
    const messages = screen.getByTestId('chat-messages')
    Object.defineProperty(messages, 'scrollHeight', { configurable: true, value: 120 })

    rerender(
      <ChatPane
        pr={pr}
        pendingMessage={{ q: 'Verify this review comment', ctx: '', id: 456 }}
        onPendingMessageSent={onPendingMessageSent}
      />,
    )

    await waitFor(() => expect(messages.scrollTop).toBe(120))
    expect(onPendingMessageSent).toHaveBeenCalledTimes(1)
  })

  it('sends each pending verification message only once', async () => {
    const cefQuery = vi.fn()
    Object.assign(window, { cefQuery })
    const onPendingMessageSent = vi.fn()
    const pendingMessage = {
      q: 'Verify this review comment',
      ctx: '',
      id: 123,
    }

    render(
      <ChatPane
        pr={pr}
        pendingMessage={pendingMessage}
        onPendingMessageSent={onPendingMessageSent}
      />,
    )

    await waitFor(() => expect(cefQuery).toHaveBeenCalledTimes(1))
    expect(onPendingMessageSent).toHaveBeenCalledTimes(1)
    expect(screen.getByText(pendingMessage.q)).toBeVisible()
    expect(JSON.parse(cefQuery.mock.calls[0][0].request)).toMatchObject({
      type: 'askClaude',
      context: '',
      question: pendingMessage.q,
    })

    act(() => {
      const hostWindow = window as unknown as {
        __handleMessage: (message: unknown) => void
      }
      hostWindow.__handleMessage({
        protocolVersion: 1,
        type: 'chatResponse',
        prKey: 'acme/widget#42',
        response: 'Confirmed',
      })
    })

    await screen.findByText('Confirmed')
    expect(cefQuery).toHaveBeenCalledTimes(1)
    expect(onPendingMessageSent).toHaveBeenCalledTimes(1)
  })

  it('retains verification context while pending and after the response', async () => {
    const cefQuery = vi.fn()
    Object.assign(window, { cefQuery })
    const view = render(
      <ChatPane
        pr={pr}
        contextSummary={['PR title/body', 'diff']}
        pendingMessage={{
          q: 'Verify this review comment',
          ctx: 'focused context',
          id: 124,
          contextSummary: ['draft comment', 'diff excerpt', 'PR worktree (read-only)'],
        }}
        onPendingMessageSent={vi.fn()}
      />,
    )

    const focusedContext = 'Context: draft comment, diff excerpt, PR worktree (read-only)'
    expect(await screen.findByText(focusedContext)).toBeVisible()
    view.rerender(<ChatPane pr={pr} contextSummary={['PR title/body', 'diff']} />)
    expect(screen.getByText(focusedContext)).toBeVisible()

    act(() => {
      const hostWindow = window as unknown as {
        __handleMessage: (message: unknown) => void
      }
      hostWindow.__handleMessage({
        protocolVersion: 1,
        type: 'chatResponse',
        prKey: 'acme/widget#42',
        response: 'Confirmed',
      })
    })

    expect(await screen.findByText('Confirmed')).toBeVisible()
    expect(screen.getByText(focusedContext)).toBeVisible()
  })

  it('renders a structured verify-comment response as a card instead of raw JSON', async () => {
    Object.assign(window, { cefQuery: vi.fn() })
    render(<ChatPane pr={pr} />)

    act(() => {
      const hostWindow = window as unknown as {
        __handleMessage: (message: unknown) => void
      }
      hostWindow.__handleMessage({
        protocolVersion: 1,
        type: 'chatResponse',
        prKey: 'acme/widget#42',
        response: JSON.stringify({
          verdict: 'invalid',
          why: 'The diff shows the null check already exists at line 12.',
          evidence: ['src/value.ts:12', 'src/value.ts:readValue'],
          action: 'revise',
          replacementComment: 'This check is redundant with the guard added above.',
        }),
      })
    })

    expect(await screen.findByText('Invalid')).toBeVisible()
    expect(screen.getByText(/Suggested action: Revise/)).toBeVisible()
    expect(screen.getByText('The diff shows the null check already exists at line 12.')).toBeVisible()
    expect(screen.getByText('Evidence checked')).toBeVisible()
    expect(screen.getByText('src/value.ts:12')).toBeVisible()
    expect(screen.getByText('src/value.ts:readValue')).toBeVisible()
    expect(screen.getByText('This check is redundant with the guard added above.')).toBeVisible()
    expect(screen.queryByText(/"verdict":"invalid"/)).not.toBeInTheDocument()
  })

  it('renders structured JSON as a card while still streaming, instead of raw JSON with a blinking cursor', async () => {
    Object.assign(window, { cefQuery: vi.fn() })
    render(<ChatPane pr={pr} />)

    const fullJson = JSON.stringify({
      verdict: 'valid',
      why: 'Supported by the diff.',
      action: 'keep',
      replacementComment: null,
    })

    act(() => {
      const hostWindow = window as unknown as {
        __handleMessage: (message: unknown) => void
      }
      hostWindow.__handleMessage({
        protocolVersion: 1,
        type: 'chatChunk',
        prKey: 'acme/widget#42',
        chunk: fullJson,
      })
    })

    expect(await screen.findByText('Valid')).toBeVisible()
    expect(screen.getByText('Supported by the diff.')).toBeVisible()
    expect(screen.queryByText(/"verdict":"valid"/)).not.toBeInTheDocument()
  })

  function pushHostMessage(message: Record<string, unknown>) {
    act(() => {
      const hostWindow = window as unknown as { __handleMessage: (message: unknown) => void }
      hostWindow.__handleMessage({ protocolVersion: 1, prKey: 'acme/widget#42', ...message })
    })
  }

  function verifyJson(over: Record<string, unknown> = {}) {
    return JSON.stringify({
      verdict: 'invalid',
      why: 'Already handled upstream.',
      action: 'delete',
      replacementComment: null,
      ...over,
    })
  }

  type ApplyVerifyMock = Mock<(result: VerifyResult, token: string) => void>

  function newApplyMock(): ApplyVerifyMock {
    return vi.fn<(result: VerifyResult, token: string) => void>()
  }

  async function renderWithPendingVerify(token: string, onApplyVerifyAction: ApplyVerifyMock) {
    Object.assign(window, { cefQuery: vi.fn() })
    const view = render(
      <ChatPane
        pr={pr}
        pendingMessage={{ q: 'Verify this comment', ctx: '', id: 1, token }}
        onApplyVerifyAction={onApplyVerifyAction}
      />,
    )
    await screen.findByText('Verify this comment')
    return view
  }

  it('applies a delete verdict to the comment the verification was requested for', async () => {
    const onApplyVerifyAction = newApplyMock()
    await renderWithPendingVerify('verify-1', onApplyVerifyAction)

    pushHostMessage({ type: 'chatResponse', response: verifyJson() })

    const applyButton = await screen.findByRole('button', { name: 'Delete this comment' })
    applyButton.click()

    expect(onApplyVerifyAction).toHaveBeenCalledTimes(1)
    expect(onApplyVerifyAction.mock.calls[0][0]).toMatchObject({ action: 'delete' })
    expect(onApplyVerifyAction.mock.calls[0][1]).toBe('verify-1')
  })

  it('offers a replace action for a revise verdict that carries replacement text', async () => {
    const onApplyVerifyAction = newApplyMock()
    await renderWithPendingVerify('verify-2', onApplyVerifyAction)

    pushHostMessage({
      type: 'chatResponse',
      response: verifyJson({ action: 'revise', replacementComment: 'Narrow this to the null case.' }),
    })

    const applyButton = await screen.findByRole('button', { name: 'Replace comment text' })
    applyButton.click()

    expect(onApplyVerifyAction.mock.calls[0][0]).toMatchObject({
      action: 'revise',
      replacementComment: 'Narrow this to the null case.',
    })
  })

  it('offers no action for a keep verdict, or a revise verdict with no replacement text', async () => {
    const onApplyVerifyAction = newApplyMock()
    const { unmount } = await renderWithPendingVerify('verify-3', onApplyVerifyAction)
    pushHostMessage({ type: 'chatResponse', response: verifyJson({ verdict: 'valid', action: 'keep' }) })

    expect(await screen.findByText(/Suggested action: Keep as-is/)).toBeVisible()
    expect(screen.queryByRole('button', { name: /Delete this comment|Replace comment text/ })).not.toBeInTheDocument()
    unmount()

    await renderWithPendingVerify('verify-4', onApplyVerifyAction)
    pushHostMessage({ type: 'chatResponse', response: verifyJson({ action: 'revise', replacementComment: '   ' }) })

    expect(await screen.findByText(/Suggested action: Revise/)).toBeVisible()
    expect(screen.queryByRole('button', { name: 'Replace comment text' })).not.toBeInTheDocument()
  })

  it('offers no action for a verdict that did not originate from a tracked verify request', async () => {
    Object.assign(window, { cefQuery: vi.fn() })
    render(<ChatPane pr={pr} onApplyVerifyAction={newApplyMock()} />)

    pushHostMessage({ type: 'chatResponse', response: verifyJson() })

    expect(await screen.findByText('Invalid')).toBeVisible()
    expect(screen.queryByRole('button', { name: 'Delete this comment' })).not.toBeInTheDocument()
  })

  it('disables the apply button after it has been used so an action cannot be applied twice', async () => {
    const onApplyVerifyAction = newApplyMock()
    await renderWithPendingVerify('verify-5', onApplyVerifyAction)
    pushHostMessage({ type: 'chatResponse', response: verifyJson() })

    const applyButton = await screen.findByRole('button', { name: 'Delete this comment' })
    act(() => applyButton.click())

    const appliedButton = await screen.findByRole('button', { name: 'Applied' })
    expect(appliedButton).toBeDisabled()
    act(() => appliedButton.click())
    expect(onApplyVerifyAction).toHaveBeenCalledTimes(1)
  })

  it('exposes the scrollable history as a named keyboard-focusable region', () => {
    render(<ChatPane pr={pr} />)

    const region = screen.getByRole('region', { name: 'Chat messages' })
    expect(region).toBe(screen.getByTestId('chat-messages'))
    expect(region).toHaveAttribute('tabindex', '0')
    expect(region).toHaveClass('focus-visible:ring-2', 'focus-visible:ring-inset', 'focus-visible:ring-ring')
  })

  it('auto-scrolls new messages without moving focus into the history', async () => {
    Object.assign(window, { cefQuery: vi.fn() })
    render(<ChatPane pr={pr} />)
    const input = screen.getByRole('textbox', { name: 'Ask about this pull request' })
    const messages = screen.getByTestId('chat-messages')
    Object.defineProperty(messages, 'scrollHeight', { configurable: true, value: 300 })

    input.focus()
    await userEvent.type(input, 'What changed?{Enter}')
    pushHostMessage({ type: 'chatResponse', response: 'A lot.' })

    await screen.findByText('A lot.')
    expect(messages.scrollTop).toBe(300)
    expect(document.activeElement).toBe(input)
  })

  it('marks chat errors with an icon and readable text instead of destructive-red copy', async () => {
    Object.assign(window, { cefQuery: vi.fn() })
    render(<ChatPane pr={pr} />)
    await userEvent.type(screen.getByRole('textbox', { name: 'Ask about this pull request' }), 'Why?{Enter}')
    pushHostMessage({ type: 'chatError', message: 'The provider failed.' })

    const bubble = (await screen.findByText('The provider failed.')).parentElement!
    expect(bubble).toHaveClass('border-status-issue/50', 'bg-status-issue/10', 'text-foreground')
    expect(bubble).not.toHaveClass('text-destructive')
    expect(bubble.querySelector('svg[aria-hidden="true"]')).toHaveClass('text-status-issue')
  })

  describe('stopping a response', () => {
    function sent(cefQuery: ReturnType<typeof vi.fn>) {
      return cefQuery.mock.calls.map(([arg]) => JSON.parse((arg as { request: string }).request) as {
        type: string
        operationId?: string
      })
    }

    it('stops the active response with one cancelChat and keeps the conversation', async () => {
      const user = userEvent.setup()
      const cefQuery = vi.fn()
      Object.assign(window, { cefQuery })
      render(<ChatPane pr={pr} />)
      const input = screen.getByRole('textbox', { name: 'Ask about this pull request' })

      await user.type(input, 'First question{Enter}')
      pushHostMessage({ type: 'chatResponse', response: 'First answer' })
      await user.type(input, 'Second question{Enter}')
      const asks = sent(cefQuery).filter((message) => message.type === 'askClaude')
      const ask = asks[asks.length - 1]

      await user.click(screen.getByRole('button', { name: 'Stop response' }))

      const cancels = sent(cefQuery).filter((message) => message.type === 'cancelChat')
      expect(cancels).toEqual([expect.objectContaining({ type: 'cancelChat', operationId: ask.operationId })])
      expect(sent(cefQuery).filter((message) => message.type === 'clearChat')).toEqual([])
      expect(screen.getByText('First question')).toBeVisible()
      expect(screen.getByText('First answer')).toBeVisible()
      expect(screen.getByText('Second question')).toBeVisible()
      expect(screen.getByText('Response stopped.')).toHaveClass('text-muted-foreground')
      expect(screen.getByRole('button', { name: 'Send' })).toBeDisabled()
      expect(screen.queryByRole('button', { name: 'Stop response' })).not.toBeInTheDocument()
    })

    it('keeps the composer editable while busy but blocks sending until idle', async () => {
      const user = userEvent.setup()
      const cefQuery = vi.fn()
      Object.assign(window, { cefQuery })
      render(<ChatPane pr={pr} />)
      const input = screen.getByRole('textbox', { name: 'Ask about this pull request' })

      await user.type(input, 'Question{Enter}')
      expect(input).toBeEnabled()
      await user.type(input, 'Draft follow-up{Enter}')

      expect(input).toHaveValue('Draft follow-up')
      expect(sent(cefQuery).filter((message) => message.type === 'askClaude')).toHaveLength(1)
      expect(screen.queryByRole('button', { name: 'Send' })).not.toBeInTheDocument()

      pushHostMessage({ type: 'chatResponse', response: 'Answer' })
      await user.click(await screen.findByRole('button', { name: 'Send' }))
      expect(sent(cefQuery).filter((message) => message.type === 'askClaude')).toHaveLength(2)
    })

    it('ignores late output from a stopped response', async () => {
      const user = userEvent.setup()
      Object.assign(window, { cefQuery: vi.fn() })
      render(<ChatPane pr={pr} />)

      await user.type(screen.getByRole('textbox', { name: 'Ask about this pull request' }), 'Question{Enter}')
      await user.click(screen.getByRole('button', { name: 'Stop response' }))
      pushHostMessage({ type: 'chatChunk', chunk: 'LATE_CHUNK_SENTINEL' })
      pushHostMessage({ type: 'chatResponse', response: 'LATE_RESPONSE_SENTINEL' })
      pushHostMessage({ type: 'chatError', message: 'LATE_ERROR_SENTINEL' })

      expect(screen.queryByText(/LATE_CHUNK_SENTINEL/)).not.toBeInTheDocument()
      expect(screen.queryByText('LATE_RESPONSE_SENTINEL')).not.toBeInTheDocument()
      expect(screen.queryByText('LATE_ERROR_SENTINEL')).not.toBeInTheDocument()
      expect(screen.getByText('Response stopped.')).toBeVisible()
    })

    it('reaches Stop from the keyboard', async () => {
      const user = userEvent.setup()
      const cefQuery = vi.fn()
      Object.assign(window, { cefQuery })
      render(<ChatPane pr={pr} />)

      await user.type(screen.getByRole('textbox', { name: 'Ask about this pull request' }), 'Question{Enter}')
      await user.tab()
      expect(screen.getByRole('button', { name: 'Stop response' })).toHaveFocus()
      await user.keyboard('{Enter}')

      expect(sent(cefQuery).filter((message) => message.type === 'cancelChat')).toHaveLength(1)
    })
  })
})
