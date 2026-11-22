import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createElement } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { SetupScreen } from './SetupScreen'
import { setupRecoveryAction, setupSteps } from './setupRecovery'

afterEach(() => {
  vi.restoreAllMocks()
  delete (window as unknown as { cefQuery?: unknown }).cefQuery
  delete (window as unknown as { acquireVsCodeApi?: unknown }).acquireVsCodeApi
})

describe('provider onboarding steps', () => {
  it('shows a missing provider binary as incomplete', () => {
    const steps = setupSteps('provider_not_installed', {
      provider: 'claude',
      available: false,
      detail: 'Claude Code CLI is missing.',
      binaryStatus: 'missing',
      authenticationStatus: 'unavailable',
      authCommand: 'claude auth login',
    })

    expect(steps.find((step) => step.label === 'Install Claude Code CLI')?.done).toBe(false)
    expect(steps.find((step) => step.label === 'Verify provider authentication')?.done).toBe(false)
  })

  it('labels unsupported authentication checks as unverified rather than ready', () => {
    const steps = setupSteps('provider_not_installed', {
      provider: 'copilot',
      available: true,
      detail: 'Provider CLI found.',
      binaryStatus: 'ready',
      authenticationStatus: 'unverified',
      authCommand: 'copilot login',
    })

    expect(steps.find((step) => step.label === 'Install GitHub Copilot CLI')?.done).toBe(true)
    const auth = steps.find((step) => step.label === 'Verify provider authentication')
    expect(auth?.done).toBe(false)
    expect(auth?.detail).toContain('copilot login')
  })

  it('marks binary and authentication complete for a fully ready provider', () => {
    const steps = setupSteps('load_failed', {
      provider: 'claude',
      available: true,
      detail: 'Ready.',
      binaryStatus: 'ready',
      authenticationStatus: 'ready',
      authCommand: 'claude auth login',
    })

    expect(steps.slice(2, 4).map((step) => step.done)).toEqual([true, true])
  })

  it('shows repair guidance instead of authentication steps for an unavailable draft index', () => {
    const steps = setupSteps('draft_index_unavailable')

    expect(steps.map((step) => step.label)).toEqual([
      'Repair the preserved draft index',
      'Refresh PR Pilot',
    ])
    expect(steps[0].detail).toContain('pending-prs.json')
  })
})

describe('setup recovery actions', () => {
  const claude = {
    provider: 'claude' as const,
    available: false,
    detail: 'Claude Code needs attention.',
    binaryStatus: 'missing' as const,
    authenticationStatus: 'unavailable' as const,
    authCommand: 'claude auth login',
  }
  const copilot = {
    ...claude,
    provider: 'copilot' as const,
    detail: 'Copilot CLI needs attention.',
    authCommand: 'copilot login',
  }
  const antigravity = {
    ...claude,
    provider: 'antigravity' as const,
    detail: 'Antigravity CLI needs attention.',
    authCommand: 'agy',
  }

  it('maps every setup reason to the diagnosed next step', () => {
    expect(setupRecoveryAction('gh_not_installed', claude)).toMatchObject({
      kind: 'install',
      label: 'Install GitHub CLI',
      guideUrl: 'https://cli.github.com/',
    })
    expect(setupRecoveryAction('gh_not_authenticated', copilot)).toMatchObject({
      kind: 'authenticate',
      command: 'gh auth login',
      guideUrl: 'https://cli.github.com/manual/gh_auth_login',
      canRunInHost: true,
    })
    expect(setupRecoveryAction('provider_not_installed', claude)).toMatchObject({
      kind: 'install',
      label: 'Install Claude Code CLI',
      guideUrl: 'https://code.claude.com/docs/en/setup',
    })
    expect(setupRecoveryAction('provider_not_authenticated', claude)).toMatchObject({
      kind: 'authenticate',
      command: 'claude auth login',
      guideUrl: 'https://code.claude.com/docs/en/authentication',
    })
    expect(setupRecoveryAction('provider_not_installed', copilot)).toMatchObject({
      kind: 'install',
      label: 'Install GitHub Copilot CLI',
      guideUrl: expect.stringContaining('/install-copilot-cli'),
    })
    expect(setupRecoveryAction('provider_not_authenticated', copilot)).toMatchObject({
      kind: 'authenticate',
      command: 'copilot login',
      guideUrl: expect.stringContaining('/authenticate-copilot-cli'),
    })
    expect(setupRecoveryAction('provider_not_installed', antigravity)).toMatchObject({
      kind: 'install',
      label: 'Install Antigravity CLI',
      guideUrl: 'https://antigravity.google/download',
    })
    expect(setupRecoveryAction('provider_not_authenticated', antigravity)).toMatchObject({
      kind: 'authenticate',
      label: 'Authenticate Antigravity CLI',
      command: 'agy',
      guideUrl: 'https://antigravity.google/docs',
    })
    expect(setupSteps('provider_not_installed', antigravity)[2]?.label).toBe('Install Antigravity CLI')
    expect(setupRecoveryAction('load_failed', claude)).toEqual({ kind: 'retry', label: 'Retry' })
    expect(setupRecoveryAction('draft_index_unavailable', copilot)).toEqual({ kind: 'check', label: 'Check status' })
  })

  it('never offers an auth command when the diagnosed binary is missing', () => {
    render(createElement(SetupScreen, {
      reason: 'provider_not_installed',
      detail: 'Claude Code CLI is missing.',
      providerReadiness: claude,
      refreshing: false,
      onRefresh: vi.fn(),
    }))

    expect(screen.queryByRole('button', { name: /auth command/i })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Open Claude Code install guide' })).toBeInTheDocument()
  })

  it('opens the provider-specific guide and limits host-run login to GitHub auth', async () => {
    const user = userEvent.setup()
    const cefQuery = vi.fn()
    ;(window as unknown as { cefQuery?: typeof cefQuery }).cefQuery = cefQuery
    ;(window as unknown as { acquireVsCodeApi?: () => object }).acquireVsCodeApi = () => ({})
    const { rerender } = render(createElement(SetupScreen, {
      reason: 'gh_not_authenticated',
      detail: 'Authenticate GitHub CLI.',
      providerReadiness: claude,
      refreshing: false,
      onRefresh: vi.fn(),
    }))

    await user.click(screen.getByRole('button', { name: 'Run gh auth login' }))
    let lastCall = cefQuery.mock.calls[cefQuery.mock.calls.length - 1]
    expect(JSON.parse(lastCall[0].request)).toMatchObject({ type: 'runAuthLogin' })

    rerender(createElement(SetupScreen, {
      reason: 'provider_not_authenticated',
      detail: 'Authenticate Claude Code.',
      providerReadiness: { ...claude, binaryStatus: 'ready' },
      refreshing: false,
      onRefresh: vi.fn(),
    }))
    await user.click(screen.getByRole('button', { name: 'Open Claude Code authentication guide' }))
    lastCall = cefQuery.mock.calls[cefQuery.mock.calls.length - 1]
    expect(JSON.parse(lastCall[0].request)).toMatchObject({
      type: 'openUrl',
      url: 'https://code.claude.com/docs/en/authentication',
    })
  })

  it('exposes Open Settings on every setup reason and renders semantic checklist status', async () => {
    const user = userEvent.setup()
    const cefQuery = vi.fn()
    ;(window as unknown as { cefQuery?: typeof cefQuery }).cefQuery = cefQuery
    const reasons = [
      'gh_not_installed',
      'gh_not_authenticated',
      'provider_not_installed',
      'provider_not_authenticated',
      'load_failed',
      'draft_index_unavailable',
    ] as const
    const { rerender } = render(createElement(SetupScreen, {
      reason: reasons[0],
      detail: 'Setup needed.',
      providerReadiness: claude,
      refreshing: false,
      onRefresh: vi.fn(),
    }))

    for (const reason of reasons) {
      rerender(createElement(SetupScreen, {
        reason,
        detail: 'Setup needed.',
        providerReadiness: claude,
        refreshing: false,
        onRefresh: vi.fn(),
      }))
      const checklist = screen.getByRole('list', { name: 'Setup checklist' })
      expect(checklist.tagName).toBe('OL')
      expect(checklist.textContent).toMatch(/Completed:|Incomplete:/)
      await user.click(screen.getByRole('button', { name: 'Open Settings' }))
      const lastCall = cefQuery.mock.calls[cefQuery.mock.calls.length - 1]
      expect(JSON.parse(lastCall[0].request)).toMatchObject({ type: 'openSettings' })
    }
  })

  it('renders both Run gh auth login and Copy auth command with custom hostname in VS Code', async () => {
    const user = userEvent.setup()
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    })
    ;(window as unknown as { acquireVsCodeApi?: () => object }).acquireVsCodeApi = () => ({ postMessage: vi.fn() })

    render(createElement(SetupScreen, {
      reason: 'gh_not_authenticated',
      detail: 'Sign in to GitHub Enterprise.',
      ghAuthCommand: 'gh auth login --hostname github.example.com',
      providerReadiness: claude,
      refreshing: false,
      onRefresh: vi.fn(),
    }))

    expect(screen.getByText('gh auth login --hostname github.example.com')).toBeVisible()
    expect(screen.getByRole('button', { name: 'Run gh auth login' })).toBeVisible()
    await user.click(screen.getByRole('button', { name: 'Copy auth command' }))
    expect(writeText).toHaveBeenCalledWith('gh auth login --hostname github.example.com')
  })
})
