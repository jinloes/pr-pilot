import type { ReactNode } from 'react'
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { ProviderReadiness } from '../../bridge/types'
import { SetupScreen } from '../Setup/SetupScreen'
import { ErrorBoundary } from './ErrorBoundary'

function ThrowingChild({ shouldThrow }: { shouldThrow: boolean }) {
  if (shouldThrow) {
    throw new Error('render boom')
  }
  return <div>healthy child</div>
}

describe('ErrorBoundary', () => {
  it('renders children when no error occurs', () => {
    render(
      <ErrorBoundary>
        <ThrowingChild shouldThrow={false} />
      </ErrorBoundary>,
    )
    expect(screen.getByText('healthy child')).toBeInTheDocument()
  })

  it('catches render errors, renders an accessible alert card, and recovers in-app without window.location.reload', () => {
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const onReload = vi.fn()
    let shouldThrow = true
    function RecoverableChild() {
      if (shouldThrow) throw new Error('render boom')
      return <div>recovered child</div>
    }
    try {
      render(
        <ErrorBoundary
          onReload={() => {
            shouldThrow = false
            onReload()
          }}
        >
          <RecoverableChild />
        </ErrorBoundary>,
      )
      const alert = screen.getByRole('alert')
      expect(alert).toHaveTextContent('Something went wrong displaying PR Pilot')
      fireEvent.click(screen.getByRole('button', { name: 'Reload view' }))
      expect(onReload).toHaveBeenCalledTimes(1)
      expect(screen.getByText('recovered child')).toBeInTheDocument()
    } finally {
      errorSpy.mockRestore()
    }
  })

  it('handles non-Error thrown values without crashing inside componentDidCatch', () => {
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const nonErrorValues: unknown[] = [null, Object.create(null)]
    let idx = 0
    function NonErrorThrowingChild(): ReactNode {
      const value = nonErrorValues[idx] ?? null
      // eslint-disable-next-line @typescript-eslint/only-throw-error -- verifying React boundary handling of non-Error throws
      throw value
    }
    try {
      const { rerender } = render(
        <ErrorBoundary onReload={() => { idx = 1 }}>
          <NonErrorThrowingChild />
        </ErrorBoundary>,
      )
      expect(screen.getByRole('alert')).toHaveTextContent('Something went wrong displaying PR Pilot')
      fireEvent.click(screen.getByRole('button', { name: 'Reload view' }))
      rerender(
        <ErrorBoundary>
          <NonErrorThrowingChild />
        </ErrorBoundary>,
      )
      expect(screen.getByRole('alert')).toHaveTextContent('Something went wrong displaying PR Pilot')
    } finally {
      errorSpy.mockRestore()
    }
  })

  it('SetupScreen renders Last checked at and announces check completion via polite live status without authCommand', () => {
    const claudeReadiness: ProviderReadiness = {
      provider: 'claude',
      available: false,
      binaryStatus: 'missing',
      authenticationStatus: 'unavailable',
      authCommand: 'claude auth login',
      detail: 'Claude Code CLI is missing.',
    }
    const onRefresh = vi.fn()
    const { rerender } = render(
      <SetupScreen
        reason="provider_not_installed"
        detail="Claude Code CLI is missing."
        providerReadiness={claudeReadiness}
        refreshing={false}
        onRefresh={onRefresh}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: 'Check status' }))
    expect(onRefresh).toHaveBeenCalledTimes(1)
    expect(screen.getByText(/Last checked at/)).toBeVisible()

    rerender(
      <SetupScreen
        reason="provider_not_installed"
        detail="Claude Code CLI is missing."
        providerReadiness={claudeReadiness}
        refreshing={true}
        onRefresh={onRefresh}
      />,
    )
    rerender(
      <SetupScreen
        reason="provider_not_installed"
        detail="Claude Code CLI is missing."
        providerReadiness={claudeReadiness}
        refreshing={false}
        onRefresh={onRefresh}
      />,
    )
    expect(screen.getByRole('status')).toHaveTextContent(/Status checked at/)
  })
})
