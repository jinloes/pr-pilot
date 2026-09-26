import { act, render, screen } from '@testing-library/react'
import { createElement } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { clampLeftWidth, maxLeftWidth } from './lib/layout'

describe('responsive divider bounds', () => {
  it('keeps the divider within configured and viewport limits', () => {
    expect(maxLeftWidth(1_200)).toBe(420)
    expect(maxLeftWidth(700)).toBe(340)
    expect(maxLeftWidth(400)).toBe(180)
  })

  it('clamps stale widths after the viewport shrinks', () => {
    expect(clampLeftWidth(420, 700)).toBe(340)
    expect(clampLeftWidth(100, 1_200)).toBe(180)
    expect(clampLeftWidth(280, 1_200)).toBe(280)
  })
})

describe('setup overlay routing', () => {
  const pr = {
    number: 42,
    title: 'Keep reviewing while refresh fails',
    owner: 'acme',
    repo: 'widget',
    author: 'octocat',
    createdAt: '2026-07-29T00:00:00Z',
    htmlUrl: 'https://github.com/acme/widget/pull/42',
    isDraft: false,
    hasReviewDraft: false,
    reviewStatus: 'UNREVIEWED',
  }

  function hostMessage(message: object) {
    const handler = (window as unknown as { __handleMessage?: (payload: object) => void }).__handleMessage
    if (!handler) throw new Error('App did not register the bridge handler')
    act(() => handler({ protocolVersion: 1, ...message }))
  }

  function renderApp() {
    ;(window as unknown as { cefQuery?: unknown }).cefQuery = vi.fn()
    localStorage.setItem('pr-pilot:first-success-coach-shown', '1')
    return render(createElement(App))
  }

  afterEach(() => {
    delete (window as unknown as { cefQuery?: unknown }).cefQuery
    localStorage.clear()
  })

  it('shows the setup overlay for a load failure before any list has loaded', () => {
    renderApp()
    hostMessage({ type: 'setupRequired', reason: 'load_failed', detail: 'Network down.' })

    expect(screen.getByRole('main', { name: 'PR Pilot setup' })).toBeVisible()
    expect(screen.getByText('Could not load pull requests')).toBeVisible()
  })

  it('keeps the workspace usable and reports a refresh failure inline after a load', () => {
    renderApp()
    hostMessage({ type: 'prListLoaded', prs: [pr] })
    hostMessage({ type: 'setupRequired', reason: 'load_failed', detail: 'Network down.' })

    expect(screen.queryByRole('main', { name: 'PR Pilot setup' })).not.toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent("Couldn't refresh pull requests. Network down.")
    expect(screen.getByText('Keep reviewing while refresh fails')).toBeVisible()
    expect(document.querySelector('main[aria-hidden="true"]')).toBeNull()
  })

  it('re-enables setup checks when a later refresh fails behind an open setup screen', () => {
    renderApp()
    hostMessage({ type: 'prListLoaded', prs: [pr] })
    hostMessage({ type: 'setupRequired', reason: 'gh_not_authenticated', detail: 'Sign in.' })
    const check = screen.getByRole('button', { name: 'Check status' })
    act(() => check.click())
    expect(check).toBeDisabled()

    hostMessage({ type: 'setupRequired', reason: 'load_failed', detail: 'Network down.' })

    expect(screen.getByRole('main', { name: 'PR Pilot setup' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Check status' })).toBeEnabled()
  })

  it('still shows the setup overlay for non-refresh setup problems after a load', () => {
    renderApp()
    hostMessage({ type: 'prListLoaded', prs: [pr] })
    for (const reason of ['gh_not_installed', 'gh_not_authenticated', 'provider_not_installed',
      'provider_not_authenticated', 'draft_index_unavailable']) {
      hostMessage({ type: 'setupRequired', reason, detail: 'Fix setup.' })
      expect(screen.getByRole('main', { name: 'PR Pilot setup' })).toBeInTheDocument()
      hostMessage({ type: 'prListLoaded', prs: [pr] })
      expect(screen.queryByRole('main', { name: 'PR Pilot setup' })).not.toBeInTheDocument()
    }
  })
})
