import { act, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { I18nProvider } from '../../i18n/I18nProvider'
import { ReviewPane } from './ReviewPane'
import { diffWithFiles, hostMessage, pr } from './reviewPaneTestUtils'

afterEach(() => {
  vi.restoreAllMocks()
  delete (window as unknown as { cefQuery?: unknown }).cefQuery
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
