import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { I18nProvider } from '../../i18n/I18nProvider'
import { ReviewPane } from './ReviewPane'
// Vitest collects only *.component.test.* files; this pulls the pure publish-body tests into the run.
import './publishBody.test'
import { diffWithFiles, hostMessage, installHost, loadDraftPresent, outgoingOf, pr } from './reviewPaneTestUtils'

afterEach(() => {
  vi.restoreAllMocks()
  delete (window as unknown as { cefQuery?: unknown }).cefQuery
})

describe('publish review body', () => {
  async function openPublish(user: ReturnType<typeof userEvent.setup>) {
    await user.click(screen.getByRole('button', { name: 'Submit review…' }))
    return screen.getByRole('alertdialog')
  }

  it('prefills a comment count instead of the summary and publishes the edited text', async () => {
    const user = userEvent.setup()
    const cefQuery = installHost()
    render(<ReviewPane pr={pr} />)
    const diff = diffWithFiles(1)
    loadDraftPresent({
      summary: '## Overview\nA long generated summary.',
      verdict: 'COMMENT',
      lineComments: [
        { file: 'src/file-0.ts', line: 1, type: 'note', body: 'First finding.' },
        { file: 'src/file-0.ts', line: 2, type: 'note', body: 'Second finding.' },
      ],
    }, { diff, validationDiff: diff })

    const dialog = await openPublish(user)
    const body = within(dialog).getByRole('textbox', { name: 'Review body' })
    expect(body).toHaveValue('Left 2 comments.')
    expect(body).toHaveAccessibleDescription(
      "Published as the review's main comment. It starts with a short comment count; edit or clear it.",
    )
    expect(within(dialog).queryByText('Final review body (optional)')).not.toBeInTheDocument()
    await user.clear(body)
    await user.type(body, 'A couple of comments.')
    await user.click(within(dialog).getByRole('button', { name: 'Publish as Comment' }))

    expect(outgoingOf(cefQuery, 'submitReview')).toEqual([
      expect.objectContaining({ verdict: 'COMMENT', comment: 'A couple of comments.' }),
    ])
  })

  it('starts empty and warns about the canned fallback when there is nothing else to publish', async () => {
    const user = userEvent.setup()
    const cefQuery = installHost()
    render(<I18nProvider><ReviewPane pr={pr} /></I18nProvider>)
    loadDraftPresent({ summary: 'Looks good.', verdict: 'APPROVE', lineComments: [] })

    const dialog = await openPublish(user)
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

  it('discards edits on cancel and prefills the comment count again on the next open', async () => {
    const user = userEvent.setup()
    installHost()
    render(<ReviewPane pr={pr} />)
    const diff = diffWithFiles(1)
    loadDraftPresent({
      summary: 'Generated summary.',
      verdict: 'COMMENT',
      lineComments: [{ file: 'src/file-0.ts', line: 1, type: 'issue', body: 'Inline finding.' }],
    }, { diff, validationDiff: diff })

    let dialog = await openPublish(user)
    const body = within(dialog).getByRole('textbox', { name: 'Review body' })
    await user.clear(body)
    await user.type(body, 'My own words')
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }))

    dialog = await openPublish(user)
    expect(within(dialog).getByRole('textbox', { name: 'Review body' })).toHaveValue('Left 1 comment.')
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
