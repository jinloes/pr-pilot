import { act, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import * as bridge from '../../bridge/types'
import type { PR, PRListStatus, ProviderReadiness } from '../../bridge/types'
import { I18nProvider } from '../../i18n/I18nProvider'
import { PRList } from './PRList'

const firstPr: PR = {
  number: 42,
  title: 'Improve pull request discovery',
  owner: 'acme',
  repo: 'platform',
  author: 'octocat',
  createdAt: '2026-07-29T00:00:00Z',
  htmlUrl: 'https://github.com/acme/platform/pull/42',
  isDraft: false,
  hasReviewDraft: false,
  reviewStatus: 'REVIEWED',
}

const normalStatus: PRListStatus = {
  searchScope: 'currentRepo',
  currentRepo: 'acme/platform',
  resultLimit: 50,
  limited: false,
  reviewStatusAvailable: true,
}

function hostMessage(message: object) {
  const handler = (window as unknown as { __handleMessage?: (payload: object) => void }).__handleMessage
  if (!handler) throw new Error('PRList did not register the bridge handler')
  act(() => handler({ protocolVersion: 1, ...message }))
}

function load(prs: PR[], listStatus: PRListStatus = normalStatus) {
  hostMessage({ type: 'prListLoaded', prs, listStatus })
}

afterEach(() => {
  localStorage.clear()
  vi.restoreAllMocks()
})

describe('PRList', () => {
  it('displays authoritative repository context once while preserving exception and accessible identities', () => {
    render(<PRList />)
    load([firstPr, { ...firstPr, number: 43 }, { ...firstPr, repo: 'infra' }])
    expect(screen.getByTestId('pr-list-repository')).toHaveTextContent('acme/platform')
    const rows = within(screen.getByRole('list', { name: 'Pull request results' })).getAllByRole('button')
    for (const row of rows.slice(0, 2)) {
      expect(within(row).getByText('acme/platform')).toHaveClass('sr-only')
      expect(row).toHaveAccessibleName(/acme\/platform/)
      expect(within(row).getByText('@octocat').parentElement).toHaveTextContent(/#4[23].*@octocat/)
    }
    expect(within(rows[2]).getByText('acme/infra')).not.toHaveClass('sr-only')
    fireEvent.change(screen.getByRole('textbox', { name: 'Filter pull requests' }), {
      target: { value: 'acme/infra' },
    })
    expect(screen.getByTestId('pr-list-repository')).toHaveTextContent('acme/platform')
    expect(within(screen.getByRole('list')).getByText('acme/infra')).not.toHaveClass('sr-only')
  })

  it.each([
    undefined,
    { ...normalStatus, currentRepo: undefined },
    { ...normalStatus, currentRepo: '' },
    { ...normalStatus, currentRepo: '   ' },
    { ...normalStatus, searchScope: 'authored' as const },
    { ...normalStatus, searchScope: 'assigned' as const },
    { ...normalStatus, searchScope: 'reviewRequested' as const },
  ])('keeps full row identities without authoritative current-repository context: %j', (listStatus) => {
    render(<PRList />)
    load([firstPr])
    hostMessage({ type: 'prListLoaded', prs: [firstPr], listStatus })
    expect(screen.queryByTestId('pr-list-repository')).not.toBeInTheDocument()
    expect(within(screen.getByRole('list')).getByText('acme/platform')).not.toHaveClass('sr-only')
  })

  it('keeps repository identity during refresh and replaces context with the loaded result', async () => {
    const user = userEvent.setup()
    render(<PRList />)
    expect(screen.queryByTestId('pr-list-repository')).not.toBeInTheDocument()
    load([firstPr])
    await user.click(screen.getByRole('button', { name: 'Refresh pull requests' }))
    expect(screen.queryByTestId('pr-list-repository')).not.toBeInTheDocument()
    expect(within(screen.getByRole('list')).getByText('acme/platform')).not.toHaveClass('sr-only')
    load([{ ...firstPr, repo: 'infra' }], { ...normalStatus, currentRepo: 'acme/infra' })
    expect(screen.getByTestId('pr-list-repository')).toHaveTextContent('acme/infra')
    expect(within(screen.getByRole('list')).getByText('acme/infra')).toHaveClass('sr-only')
    hostMessage({ type: 'prLoading' })
    expect(screen.queryByTestId('pr-list-repository')).not.toBeInTheDocument()
    load([], { ...normalStatus, currentRepo: 'acme/infra' })
    expect(screen.getByTestId('pr-list-repository')).toHaveTextContent('acme/infra')
    expect(screen.getByText('No pull requests for current repo')).toBeVisible()
  })

  it('retains out-of-context pinned identity across refresh and matches repository names exactly', () => {
    render(<PRList />)
    load([firstPr, { ...firstPr, owner: 'ACME', number: 44 }])
    hostMessage({
      type: 'activatePR', source: 'notification',
      pr: { ...firstPr, number: 43, repo: 'infra' },
    })
    expect(within(screen.getByRole('list')).getByText('acme/infra')).not.toHaveClass('sr-only')
    expect(within(screen.getByRole('list')).getByText('ACME/platform')).not.toHaveClass('sr-only')
    load([firstPr])
    expect(within(screen.getByRole('list')).getByText('acme/infra')).not.toHaveClass('sr-only')
    expect(screen.getByTestId('pr-list-repository')).toHaveTextContent('acme/platform')
  })

  it.each(['copilot', 'claude'] as const)('uses neutral localized copy for unverified %s without probing sign-in', async (provider) => {
    const user = userEvent.setup()
    const sendToHost = vi.spyOn(bridge, 'sendToHost').mockImplementation(() => {})
    const view = render(<I18nProvider><PRList /></I18nProvider>)
    hostMessage({
      type: 'prListLoaded', prs: [firstPr], listStatus: normalStatus,
      providerReadiness: { provider, available: true, authenticationStatus: 'unverified', detail: 'CLI found' },
    })
    const name = provider === 'copilot' ? 'Copilot' : 'Claude'
    const sentence = screen.getByText(`${name} CLI found. Sign-in has not been checked.`)
    const coach = sentence.closest('[role="status"]')!
    expect(coach).toHaveClass('bg-muted')
    expect(coach.querySelector('svg.lucide-info')).toHaveAttribute('aria-hidden', 'true')
    expect(coach.querySelector('svg.lucide-circle-check')).toBeNull()
    expect(sentence.parentElement).toHaveClass('text-muted-foreground')
    expect(coach).toHaveTextContent('Choose a pull request to start.')
    screen.getByRole('button', { name: 'Dismiss readiness message' }).focus()
    await user.keyboard(provider === 'copilot' ? '{Enter}' : ' ')
    expect(screen.queryByText(`${name} CLI found. Sign-in has not been checked.`)).not.toBeInTheDocument()
    expect(localStorage.getItem('pr-pilot:first-success-coach-shown')).toBe('1')
    load([firstPr])
    expect(screen.queryByRole('button', { name: 'Dismiss readiness message' })).not.toBeInTheDocument()
    view.unmount()
    render(<PRList />)
    load([firstPr])
    expect(screen.queryByRole('button', { name: 'Dismiss readiness message' })).not.toBeInTheDocument()
    expect(sendToHost).not.toHaveBeenCalled()
  })

  it.each([
    [false, undefined, 'PR Pilot is ready.'],
    [false, 'ready', 'PR Pilot is ready.'],
    [true, 'ready', 'GitHub and the review provider are ready.'],
    [true, 'unverified', 'Claude CLI found. Sign-in has not been checked.'],
  ] as const)('preserves readiness precedence after recovery=%s with authentication=%s', (recovered, authenticationStatus, copy) => {
    render(<PRList />)
    if (recovered) hostMessage({ type: 'setupRequired', reason: 'gh_not_authenticated', detail: 'Sign in' })
    const providerReadiness: ProviderReadiness | undefined = authenticationStatus
      ? { provider: 'claude', available: true, detail: 'CLI found', authenticationStatus }
      : undefined
    hostMessage({ type: 'prListLoaded', prs: [firstPr], providerReadiness })
    const coach = screen.getByText(copy).closest('[role="status"]')!
    expect(coach).toHaveClass(authenticationStatus === 'unverified' ? 'bg-muted' : 'bg-status-approve/5')
    expect(coach.querySelector(authenticationStatus === 'unverified' ? '.lucide-info' : '.text-status-approve')).not.toBeNull()
  })

  const searchPrs: PR[] = [
    { ...firstPr, number: 2322 },
    { ...firstPr, number: 12322, title: 'Reduce queue latency', author: 'hubot', repo: 'infra' },
    { ...firstPr, number: 77, title: 'Document #release and #2322 references', author: 'writer' },
  ]

  it.each([
    ['#2322', [2322, 12322, 77]],
    ['2322', [2322, 12322, 77]],
    ['  #2322  ', [2322, 12322, 77]],
    ['  2322  ', [2322, 12322, 77]],
    ['#9999', []],
    ['#', [77]],
    ['#release', [77]],
    ['#missing', []],
    ['##2322', []],
    ['#23x', []],
    ['DISCOVERY', [2322]],
    ['HUBOT', [12322]],
    ['ACME/INFRA', [12322]],
    ['  ', [2322, 12322, 77]],
    ['', [2322, 12322, 77]],
  ])('filters %j without changing literal text or numeric substring matching', (query, numbers) => {
    render(<PRList />)
    load(searchPrs)

    fireEvent.change(screen.getByRole('textbox', { name: 'Filter pull requests' }), {
      target: { value: query },
    })

    const results = within(screen.getByRole('list', { name: 'Pull request results' }))
    expect(results.queryAllByRole('button')).toHaveLength(numbers.length)
    for (const pr of searchPrs) {
      if (numbers.includes(pr.number)) {
        expect(results.getByText(pr.title)).toBeVisible()
      } else {
        expect(results.queryByText(pr.title)).not.toBeInTheDocument()
      }
    }
    expect(screen.getByText(`${numbers.length} pull requests shown`)).toBeInTheDocument()
    expect(screen.getByTitle(`${numbers.length} visible of ${searchPrs.length} loaded`)).toHaveTextContent(
      numbers.length === searchPrs.length ? '3' : `${numbers.length}/3`,
    )
    if (numbers.length === 0) expect(screen.getByText(`No results for "${query}"`)).toBeVisible()
  })

  it('preserves selection and focus while typing and restores rows on Escape without fetching', async () => {
    const user = userEvent.setup()
    const sendToHost = vi.spyOn(bridge, 'sendToHost').mockImplementation(() => {})
    render(<PRList />)
    load(searchPrs)
    const results = screen.getByRole('list', { name: 'Pull request results' })
    await user.click(within(results).getByRole('button', { name: /Improve pull request discovery/ }))
    expect(sendToHost).toHaveBeenCalledWith({ type: 'selectPR', number: 2322, owner: 'acme', repo: 'platform' })
    sendToHost.mockClear()

    const input = screen.getByRole('textbox', { name: 'Filter pull requests' })
    await user.type(input, '#9999')
    expect(input).toHaveFocus()
    expect(within(results).queryAllByRole('button')).toHaveLength(0)
    await user.keyboard('{Escape}')
    expect(input).toHaveValue('')
    expect(input).not.toHaveFocus()
    expect(within(results).getAllByRole('button')).toHaveLength(3)
    expect(within(results).getByRole('button', { name: /Improve pull request discovery/ })).toHaveAttribute('aria-current', 'page')
    expect(sendToHost).not.toHaveBeenCalled()
  })

  it('keeps repository filtering when accepting hash-number queries', () => {
    render(<PRList />)
    hostMessage({ type: 'prListLoaded', prs: searchPrs, listStatus: normalStatus, defaultRepo: 'acme/infra' })
    fireEvent.change(screen.getByRole('textbox', { name: 'Filter pull requests' }), {
      target: { value: '#2322' },
    })
    const results = within(screen.getByRole('list', { name: 'Pull request results' }))
    expect(results.getAllByRole('button')).toHaveLength(1)
    expect(results.getByText('Reduce queue latency')).toBeVisible()
    expect(screen.getByText('1 pull requests shown')).toBeInTheDocument()
  })

  it('treats whitespace-only queries as empty even when no PRs are loaded', () => {
    render(<PRList />)
    load([])
    fireEvent.change(screen.getByRole('textbox', { name: 'Filter pull requests' }), {
      target: { value: '   ' },
    })
    expect(screen.getByText('No pull requests for current repo')).toBeVisible()
    expect(screen.queryByText(/No results for/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Show all states' })).toBeVisible()
  })

  it('shows a compact one-time readiness message without duplicate actions', () => {
    render(<PRList />)
    load([firstPr])

    expect(screen.getByText(/PR Pilot is ready/)).toBeVisible()
    expect(screen.getByText(/Choose a pull request to start/)).toBeVisible()
    expect(screen.queryByRole('button', { name: 'Show authored PRs' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Why am I seeing this list?' })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Dismiss readiness message' }))

    expect(screen.queryByText(/Choose a pull request to start/)).not.toBeInTheDocument()
    expect(localStorage.getItem('pr-pilot:first-success-coach-shown')).toBe('1')
  })

  it('hides a redundant repository selector and exposes it for multiple repositories', () => {
    localStorage.setItem('pr-pilot:first-success-coach-shown', '1')
    render(<PRList />)
    load([firstPr])
    expect(screen.queryByRole('combobox', { name: 'Repository' })).not.toBeInTheDocument()

    load([firstPr, { ...firstPr, number: 43, owner: 'acme', repo: 'infra' }])

    expect(screen.getByRole('combobox', { name: 'Repository' })).toBeVisible()
  })

  it('omits normal scope chrome and announces only actionable exceptions', () => {
    localStorage.setItem('pr-pilot:first-success-coach-shown', '1')
    render(<PRList />)
    load([firstPr])

    expect(screen.getByRole('button', { name: 'Why?' })).toBeVisible()
    expect(screen.queryByTestId('pr-list-notices')).not.toBeInTheDocument()
    expect(screen.queryByText('Searching acme/platform')).not.toBeInTheDocument()

    load([firstPr], {
      searchScope: 'currentRepo',
      resultLimit: 50,
      limited: true,
      reviewStatusAvailable: false,
    })

    const notices = screen.getByTestId('pr-list-notices')
    expect(notices).toHaveTextContent('Current repo was not detected')
    expect(notices).toHaveTextContent('Showing the first 50')
    expect(notices).toHaveTextContent('Review status is unavailable')
  })

  it('does not replace a known review status with notification-only unavailable data', () => {
    localStorage.setItem('pr-pilot:first-success-coach-shown', '1')
    render(<PRList />)
    load([firstPr])

    hostMessage({
      type: 'activatePR',
      source: 'notification',
      pr: {
        ...firstPr,
        title: 'Notification title',
        reviewStatus: 'UNAVAILABLE',
      },
    })

    expect(screen.getByText('Notification title')).toBeVisible()
    expect(screen.getByText('Reviewed')).toBeVisible()
    expect(screen.getByText('From notification')).toBeVisible()
    expect(screen.getByTestId('pr-list-notices')).toHaveTextContent('opened from a notification')
  })

  it('announces unavailable review freshness for a notification-only pull request', () => {
    localStorage.setItem('pr-pilot:first-success-coach-shown', '1')
    render(<PRList />)
    load([firstPr])

    hostMessage({
      type: 'activatePR',
      source: 'notification',
      pr: {
        ...firstPr,
        number: 43,
        reviewStatus: 'UNAVAILABLE',
      },
    })

    const notices = screen.getByTestId('pr-list-notices')
    expect(notices).toHaveTextContent('opened from a notification')
    expect(notices).toHaveTextContent('Review status is unavailable')
  })

  it('keeps a notification-only pull request pinned across an in-flight refresh result', () => {
    localStorage.setItem('pr-pilot:first-success-coach-shown', '1')
    render(<PRList />)
    load([firstPr])

    hostMessage({
      type: 'activatePR',
      source: 'notification',
      pr: {
        ...firstPr,
        number: 43,
        title: 'Notification-only pull request',
        reviewStatus: 'UNAVAILABLE',
      },
    })
    load([firstPr])

    expect(screen.getByText('Notification-only pull request')).toBeVisible()
    expect(screen.getByText('From notification')).toBeVisible()
    const notices = screen.getByTestId('pr-list-notices')
    expect(notices).toHaveTextContent('opened from a notification')
    expect(notices).toHaveTextContent('Review status is unavailable')
  })
})
