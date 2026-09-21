import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { PR } from '../../bridge/types'
import { PRListItem } from './PRListItem'

const pr: PR = {
  number: 42,
  title: 'A long pull request title that remains the first thing reviewers scan',
  owner: 'acme',
  repo: 'platform',
  author: 'octocat',
  createdAt: '2026-07-29T00:00:00Z',
  htmlUrl: 'https://github.com/acme/platform/pull/42',
  isDraft: true,
  hasReviewDraft: true,
  reviewStatus: 'UPDATED_SINCE_REVIEW',
}

describe('PRListItem', () => {
  it('combines number, author and date only in compact mode without losing identity or badges', () => {
    render(<PRListItem pr={pr} selected spotlighted compactMetadata onClick={() => undefined} />)
    const row = screen.getByRole('button', { name: /acme\/platform/ })
    const metadata = screen.getByText('@octocat').parentElement!
    expect(screen.getByText('acme/platform')).toHaveClass('sr-only')
    expect(metadata).toHaveTextContent(/^#42·@octocat·.+/)
    expect(metadata).toHaveClass('flex-wrap', 'text-xs')
    expect(screen.getByText('#42').parentElement).toBe(metadata)
    expect(row).toHaveAttribute('aria-current', 'page')
    expect(screen.getByText('Draft PR')).toBeVisible()
    expect(screen.getByText('Review draft')).toBeVisible()
    expect(screen.getByText('From notification')).toBeVisible()
    expect(screen.getByText('Updated since your review')).toBeVisible()
  })

  it('omits a missing date and allows keyboard activation of compact rows', async () => {
    const user = userEvent.setup()
    const onClick = vi.fn()
    render(<PRListItem pr={{ ...pr, createdAt: '' }} selected={false} spotlighted={false} compactMetadata onClick={onClick} />)
    expect(screen.getByText('@octocat').parentElement).toHaveTextContent(/^#42·@octocat$/)
    await user.tab()
    expect(screen.getByRole('button')).toHaveFocus()
    await user.keyboard('{Enter}')
    await user.keyboard(' ')
    expect(onClick).toHaveBeenCalledTimes(2)
  })

  it('renders title-first metadata and readable wrapping statuses', () => {
    render(<PRListItem pr={pr} selected spotlighted onClick={() => undefined} />)

    const row = screen.getByRole('button', { name: /long pull request title/i })
    expect(row).toHaveAttribute('aria-current', 'page')
    expect(screen.getByText(pr.title)).toHaveClass('line-clamp-2')
    expect(screen.getByText('acme/platform')).toBeVisible()
    expect(screen.getByText('#42')).toBeVisible()
    expect(screen.getByText('@octocat')).toBeVisible()
    expect(screen.getByText('Draft PR')).toBeVisible()
    expect(screen.getByText('Review draft')).toBeVisible()
    expect(screen.getByText('From notification')).toBeVisible()
    expect(screen.getByText('Updated since your review')).toBeVisible()
    expect(row).not.toHaveTextContent('PR-DRAFT')
    expect(row).not.toHaveTextContent('REV-DRAFT')
    expect(row).not.toHaveTextContent('NOTIFIED')
  })

  it('activates the full row and omits a badge for unreviewed status', () => {
    const onClick = vi.fn()
    render(
      <PRListItem
        pr={{ ...pr, isDraft: false, hasReviewDraft: false, reviewStatus: 'UNREVIEWED' }}
        selected={false}
        spotlighted={false}
        onClick={onClick}
      />,
    )

    const row = screen.getByRole('button')
    fireEvent.click(row)

    expect(onClick).toHaveBeenCalledOnce()
    expect(row).not.toHaveAttribute('aria-current')
    expect(screen.queryByLabelText('Pull request status')).not.toBeInTheDocument()
  })

  it('shows a positive reviewed badge', () => {
    render(
      <PRListItem
        pr={{ ...pr, isDraft: false, hasReviewDraft: false, reviewStatus: 'REVIEWED' }}
        selected={false}
        spotlighted={false}
        onClick={() => undefined}
      />,
    )

    expect(screen.getByText('Reviewed')).toBeVisible()
  })
})
