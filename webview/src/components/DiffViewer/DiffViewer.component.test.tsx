import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { LineComment } from '@/bridge/types'
import { AUTOSAVE_DEBOUNCE_MS } from '@/lib/autosave'
import { DELETE_COMMENT_DESCRIPTION, DiffViewer } from './DiffViewer'

const diff = `diff --git a/src/auth.ts b/src/auth.ts
--- a/src/auth.ts
+++ b/src/auth.ts
@@ -1,1 +1,2 @@
 export const ready = true
+export const accessible = true
diff --git a/src/ui/button.tsx b/src/ui/button.tsx
--- a/src/ui/button.tsx
+++ b/src/ui/button.tsx
@@ -3,1 +3,2 @@
 export function Button() {}
+export function IconButton() {}
`

const comments: LineComment[] = [
  { file: 'src/auth.ts', line: 2, type: 'issue', body: 'Explain the new flag.' },
]

beforeAll(() => {
  Object.defineProperty(window, 'CSS', {
    configurable: true,
    value: { escape: (value: string) => value },
  })
})

describe('DiffViewer', () => {
  let scrollIntoViewSpy: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    scrollIntoViewSpy = vi.spyOn(Element.prototype, 'scrollIntoView').mockImplementation(() => {})
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation((callback: FrameRequestCallback) => {
      callback(0)
      return 1
    })
    vi.spyOn(window, 'cancelAnimationFrame').mockImplementation(() => {})
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('shows a changed-files tree and keeps the current file visible in the toolbar', () => {
    render(
      <div data-testid="review-scroll-body" style={{ overflowY: 'auto', maxHeight: '400px' }}>
        <DiffViewer diff={diff} comments={comments} />
      </div>,
    )

    expect(screen.getByRole('navigation', { name: 'Review navigation' })).toBeVisible()
    expect(screen.getByRole('button', { name: 'src/auth.ts' })).toBeVisible()
    expect(screen.getByRole('button', { name: 'src/ui/button.tsx' })).toBeVisible()
    expect(screen.getByTestId('diff-current-file-path')).toHaveTextContent('src/auth.ts')
  })

  it('renders nothing for a truly empty diff', () => {
    const { container } = render(<DiffViewer diff="" comments={[]} />)

    expect(container).toBeEmptyDOMElement()
  })

  it('shows an actionable warning and exposes raw content for an unrenderable diff', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
    render(<DiffViewer diff="not a unified diff" comments={[]} />)

    expect(screen.getByRole('alert')).toHaveTextContent('PR Pilot could not render this diff')
    fireEvent.click(screen.getByRole('button', { name: 'Copy raw diff' }))

    await waitFor(() => expect(writeText).toHaveBeenCalledWith('not a unified diff'))
    expect(screen.getByRole('button', { name: 'Copied raw diff' })).toBeVisible()
  })

  it('jumps to the selected file from the tree and updates the current-file indicator', async () => {
    render(
      <div data-testid="review-scroll-body" style={{ overflowY: 'auto', maxHeight: '400px' }}>
        <DiffViewer diff={diff} comments={comments} />
      </div>,
    )

    fireEvent.click(screen.getByRole('button', { name: 'src/ui/button.tsx' }))

    await waitFor(() => expect(screen.getByTestId('diff-current-file-path')).toHaveTextContent('src/ui/button.tsx'))
    expect(scrollIntoViewSpy).toHaveBeenCalled()
  })

  it('does not jump when comments are replaced without changing the focused index', async () => {
    const firstComment = { file: 'src/auth.ts', line: 2, type: 'issue' as const, body: 'First comment.' }
    const nextComment = { file: 'src/ui/button.tsx', line: 4, type: 'issue' as const, body: 'Next comment.' }
    const { rerender } = render(
      <div data-testid="review-scroll-body" style={{ overflowY: 'auto', maxHeight: '400px' }}>
        <DiffViewer diff={diff} comments={[firstComment, nextComment]} focusedCommentIdx={0} />
      </div>,
    )

    scrollIntoViewSpy.mockClear()
    rerender(
      <div data-testid="review-scroll-body" style={{ overflowY: 'auto', maxHeight: '400px' }}>
        <DiffViewer diff={diff} comments={[nextComment]} focusedCommentIdx={0} />
      </div>,
    )

    await waitFor(() => expect(screen.getByText('Next comment.')).toBeVisible())
    expect(scrollIntoViewSpy).not.toHaveBeenCalled()
  })

  it('scrolls to the focused comment again when a new focus request keeps the same index', () => {
    const { rerender } = render(
      <DiffViewer
        diff={diff}
        comments={comments}
        focusedCommentIdx={0}
        commentFocusRequestId={0}
      />,
    )
    scrollIntoViewSpy.mockClear()

    rerender(
      <DiffViewer
        diff={diff}
        comments={comments}
        focusedCommentIdx={0}
        commentFocusRequestId={1}
      />,
    )

    expect(scrollIntoViewSpy).toHaveBeenCalledWith(expect.objectContaining({ block: 'center' }))
  })

  it('sorts findings by severity and focuses the selected inline comment', () => {
    const onFocusComment = vi.fn()
    render(
      <div data-testid="review-scroll-body" style={{ overflowY: 'auto', maxHeight: '400px' }}>
        <DiffViewer
          diff={diff}
          comments={[
            { file: 'src/auth.ts', line: 2, type: 'note', severity: 'nit', body: 'Small cleanup.' },
            { file: 'src/ui/button.tsx', line: 4, type: 'issue', severity: 'blocker', body: 'Broken path.' },
          ]}
          orphanComments={[
            { file: 'src/old.ts', line: 99, type: 'issue', body: 'No longer anchored.' },
          ]}
          focusedCommentIdx={0}
          onFocusComment={onFocusComment}
        />
      </div>,
    )

    fireEvent.click(screen.getByRole('button', { name: /Findings/ }))
    const anchored = screen.getByRole('list', { name: 'Anchored findings' })
    expect(within(anchored).getAllByRole('button')[0]).toHaveAccessibleName(/Blocker issue/)
    expect(screen.getByText('Unanchored')).toBeVisible()

    fireEvent.click(within(anchored).getAllByRole('button')[0])
    expect(onFocusComment).toHaveBeenCalledWith(1)
    expect(scrollIntoViewSpy).toHaveBeenCalled()
  })

  it('expands a truncated diff before scrolling to a selected finding', async () => {
    const changes = Array.from({ length: 501 }, (_, index) => `+export const value${index + 1} = true`).join('\n')
    const largeDiff = [
      'diff --git a/src/large.ts b/src/large.ts',
      '--- a/src/large.ts',
      '+++ b/src/large.ts',
      '@@ -0,0 +1,501 @@',
      changes,
    ].join('\n')
    render(
      <DiffViewer
        diff={largeDiff}
        comments={[{ file: 'src/large.ts', line: 501, type: 'issue', body: 'Last finding.' }]}
        onFocusComment={vi.fn()}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: /Findings/ }))
    scrollIntoViewSpy.mockClear()

    fireEvent.click(screen.getByRole('button', { name: /Last finding/ }))

    await waitFor(() => expect(document.getElementById('diff-comment-0')).not.toBeNull())
    expect(scrollIntoViewSpy).toHaveBeenCalled()
  })

  it('shows an explicit empty state in the findings view', () => {
    render(<DiffViewer diff={diff} comments={[]} />)

    fireEvent.click(screen.getByRole('button', { name: /Findings/ }))

    expect(screen.getByText('No findings in this review.')).toBeVisible()
  })

  it('uses one primary finding label, visible AI actions, and an overflow menu for mutations', async () => {
    const user = userEvent.setup()
    render(
      <DiffViewer
        diff={diff}
        comments={[{
          ...comments[0],
          severity: 'major',
          category: 'security',
          confidence: 'high',
        }]}
        onEditComment={vi.fn()}
        onDeleteComment={vi.fn()}
        onVerifyComment={vi.fn()}
        onSuggestFixComment={vi.fn()}
      />,
    )

    expect(screen.getByText('Major issue')).toBeVisible()
    expect(screen.getByText(/security/)).toBeVisible()
    expect(screen.getByRole('button', { name: 'Verify with AI' })).toHaveTextContent('Verify')
    expect(screen.getByRole('button', { name: 'Suggest fix with AI' })).toHaveTextContent('Suggest fix')
    expect(screen.queryByRole('button', { name: 'Edit comment' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'More finding actions' }))
    expect(screen.getByRole('menuitem', { name: 'Edit comment' })).toBeVisible()
    expect(screen.getByRole('menuitem', { name: 'Delete comment' })).toBeVisible()
  })

  it('explains that a deleted comment is saved by autosave and offers Save now', async () => {
    const user = userEvent.setup()
    const onDeleteComment = vi.fn()
    render(<DiffViewer diff={diff} comments={comments} onEditComment={vi.fn()} onDeleteComment={onDeleteComment} />)

    await user.click(screen.getByRole('button', { name: 'More finding actions' }))
    const deleteItem = screen.getByRole('menuitem', { name: 'Delete comment' })
    expect(deleteItem).toHaveClass('text-status-issue')
    expect(deleteItem).not.toHaveClass('text-destructive')
    await user.click(deleteItem)

    const dialog = await screen.findByRole('alertdialog')
    const seconds = AUTOSAVE_DEBOUNCE_MS / 1000
    expect(within(dialog).getByText(
      `This comment will be removed from your pending GitHub review at the next autosave (within ${seconds} seconds). `
      + 'Use Save now to update GitHub immediately.',
    )).toBeVisible()
    expect(DELETE_COMMENT_DESCRIPTION).toContain(`within ${seconds} seconds`)
    expect(dialog).not.toHaveTextContent('Save the draft to persist the change.')
    await user.click(within(dialog).getByRole('button', { name: 'Delete' }))
    expect(onDeleteComment).toHaveBeenCalledOnce()
  })

  it('closes a pending comment editor when the diff becomes read-only', () => {
    const { rerender } = render(<DiffViewer diff={diff} comments={[]} onAddComment={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: 'Add comment on src/auth.ts, new line 1' }))
    expect(screen.getByRole('textbox', { name: 'Comment on src/auth.ts, line 1' })).toBeVisible()

    rerender(<DiffViewer diff={diff} comments={[]} onAddComment={vi.fn()} readOnly />)

    expect(screen.queryByRole('textbox', { name: 'Comment on src/auth.ts, line 1' })).not.toBeInTheDocument()
  })

  it('makes read-only file sections named keyboard scroll targets and leaves editable sections unfocusable', () => {
    const { rerender } = render(<DiffViewer diff={diff} comments={[]} readOnly />)

    const readOnlySection = screen.getByRole('region', { name: 'src/auth.ts' })
    expect(readOnlySection).toHaveAttribute('tabindex', '0')
    expect(screen.queryByRole('button', { name: /^Add comment on/ })).not.toBeInTheDocument()

    rerender(<DiffViewer diff={diff} comments={[]} onAddComment={vi.fn()} />)

    const editableSection = screen.getByRole('region', { name: 'src/auth.ts' })
    expect(editableSection).not.toHaveAttribute('tabindex')
    expect(within(editableSection).getAllByRole('button', { name: /^Add comment on src\/auth\.ts/ }).length).toBeGreaterThan(0)
  })

  it('updates the current-file indicator while the diff scroll position changes', async () => {
    render(
      <div data-testid="review-scroll-body" style={{ overflowY: 'auto', maxHeight: '400px' }}>
        <DiffViewer diff={diff} comments={comments} />
      </div>,
    )

    const scrollBody = screen.getByTestId('review-scroll-body')
    const firstSection = screen.getByTestId('diff-file-section-0')
    const secondSection = screen.getByTestId('diff-file-section-1')
    const toolbar = screen.getByTestId('diff-current-file').parentElement

    vi.spyOn(scrollBody, 'getBoundingClientRect').mockReturnValue({
      x: 0,
      y: 0,
      width: 800,
      height: 400,
      top: 0,
      right: 800,
      bottom: 400,
      left: 0,
      toJSON: () => ({}),
    })
    if (toolbar) {
      vi.spyOn(toolbar, 'getBoundingClientRect').mockReturnValue({
        x: 0,
        y: 0,
        width: 800,
        height: 40,
        top: 0,
        right: 800,
        bottom: 40,
        left: 0,
        toJSON: () => ({}),
      })
    }

    let secondTop = 260
    vi.spyOn(firstSection, 'getBoundingClientRect').mockImplementation(() => ({
      x: 0,
      y: -120,
      width: 800,
      height: 220,
      top: -120,
      right: 800,
      bottom: 100,
      left: 0,
      toJSON: () => ({}),
    }))
    vi.spyOn(secondSection, 'getBoundingClientRect').mockImplementation(() => ({
      x: 0,
      y: secondTop,
      width: 800,
      height: 220,
      top: secondTop,
      right: 800,
      bottom: secondTop + 220,
      left: 0,
      toJSON: () => ({}),
    }))

    secondTop = 24
    fireEvent.scroll(scrollBody)

    await waitFor(() => expect(screen.getByTestId('diff-current-file-path')).toHaveTextContent('src/ui/button.tsx'))
  })
})
