import { useCallback, useEffect, useRef, useState } from 'react'
import {
  onHostMessage,
  sendToHost,
  type PR,
  type ReviewResult,
  type LineComment,
} from '../../bridge/types'
import { ChatPane } from '../ChatPane'
import { DiffViewer } from '../DiffViewer'
import { ReviewDisplay } from '../ReviewDisplay'
import './ReviewPane.css'

const CHAT_HEIGHT_KEY = 'claude-reviews:chat-height'
const MIN_CHAT_HEIGHT = 100
const MAX_CHAT_HEIGHT = 600
const DEFAULT_CHAT_HEIGHT = 240

function loadChatHeight(): number {
  const saved = Number(localStorage.getItem(CHAT_HEIGHT_KEY))
  return saved >= MIN_CHAT_HEIGHT && saved <= MAX_CHAT_HEIGHT ? saved : DEFAULT_CHAT_HEIGHT
}

interface Props {
  pr: PR | null
}

type Verdict = 'APPROVE' | 'REQUEST_CHANGES' | 'COMMENT'

type PaneState =
  | { kind: 'idle' }
  | { kind: 'draftLoading' }
  | { kind: 'noDraft' }
  | { kind: 'authError'; message: string }
  | { kind: 'draftPresent'; result: ReviewResult; reviewId: string; staleCommits: boolean; diff?: string }
  | { kind: 'generating'; messages: string[]; chunks: Array<{ kind: 'text' | 'thinking'; content: string }> }
  | { kind: 'reviewUnsaved'; result: ReviewResult; diff: string }
  | { kind: 'merged'; status?: string }
  | { kind: 'submitted' }
  | { kind: 'error'; message: string }
  | { kind: 'saveError'; message: string }
  | { kind: 'submitError'; message: string }

function sortedComments(comments: LineComment[]): LineComment[] {
  return [...comments].sort((a, b) => a.file.localeCompare(b.file) || a.line - b.line)
}

function withSortedComments(result: ReviewResult): ReviewResult {
  return { ...result, lineComments: sortedComments(result.lineComments) }
}

function commentCountFromState(s: PaneState): number {
  if (s.kind === 'draftPresent' || s.kind === 'reviewUnsaved') {
    return s.result.lineComments.length
  }
  return 0
}

export function ReviewPane({ pr }: Props) {
  const [state, setState] = useState<PaneState>({ kind: 'idle' })
  const [saving, setSaving] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [focusedCommentIdx, setFocusedCommentIdx] = useState(0)
  const [chatVisible, setChatVisible] = useState(false)
  const [selectedContext, setSelectedContext] = useState('')
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; text: string } | null>(null)
  const [pendingChatMessage, setPendingChatMessage] = useState<{ q: string; ctx: string; id: number } | null>(null)
  const [chatHeight, setChatHeight] = useState(loadChatHeight)
  const chatHeightRef = useRef(chatHeight)
  const chatDragRef = useRef<{ startY: number; startHeight: number } | null>(null)
  const latestResult = useRef<ReviewResult | null>(null)
  const latestDiff = useRef('')

  useEffect(() => {
    setState({ kind: pr ? 'draftLoading' : 'idle' })
    latestResult.current = null
    latestDiff.current = ''
    setSaving(false)
    setSubmitting(false)
    setDeleting(false)
    setFocusedCommentIdx(0)
    setChatVisible(false)
    setSelectedContext('')
  }, [pr?.number, pr?.owner, pr?.repo])

  useEffect(() => {
    const cleanup = onHostMessage((msg) => {
      switch (msg.type) {
        case 'draftLoading':
          setState({ kind: 'draftLoading' })
          break

        case 'draftLoaded':
          if (msg.diff) latestDiff.current = msg.diff
          if (msg.prState === 'MERGED') {
            setState({ kind: 'merged', status: msg.status })
          } else if (msg.prState === 'DRAFT_PRESENT' && msg.result) {
            const result = withSortedComments(msg.result)
            latestResult.current = result
            setFocusedCommentIdx(0)
            setState({
              kind: 'draftPresent',
              result,
              reviewId: msg.reviewId ?? '',
              staleCommits: msg.staleCommits ?? false,
              diff: msg.diff,
            })
          } else {
            // Non-empty status on a NO_DRAFT means the server reported an error (e.g. missing token)
            const status = msg.status ?? ''
            setState(status ? { kind: 'authError', message: status } : { kind: 'noDraft' })
          }
          break

        case 'reviewGenerating':
          setState((prev) => ({
            kind: 'generating',
            messages: prev.kind === 'generating' ? [...prev.messages, msg.message] : [msg.message],
            chunks: prev.kind === 'generating' ? prev.chunks : [],
          }))
          break

        case 'reviewChunk':
          setState((prev) => {
            if (prev.kind !== 'generating') return prev
            return {
              ...prev,
              chunks: [...prev.chunks, { kind: msg.kind, content: msg.chunk }],
            }
          })
          break

        case 'reviewResult': {
          const result = withSortedComments(msg.result)
          latestDiff.current = msg.diff ?? ''
          latestResult.current = result
          setFocusedCommentIdx(0)
          setState({ kind: 'reviewUnsaved', result, diff: msg.diff ?? '' })
          break
        }

        case 'reviewError':
          setState({ kind: 'error', message: msg.message })
          break

        case 'draftSaved': {
          setSaving(false)
          const saved = latestResult.current
          if (saved) {
            setState((prev) => ({
              kind: 'draftPresent',
              result: saved,
              reviewId: msg.reviewId,
              staleCommits: false,
              diff: prev.kind === 'reviewUnsaved' || prev.kind === 'draftPresent' ? prev.diff : undefined,
            }))
          }
          break
        }

        case 'draftSaveError':
          setSaving(false)
          setState({ kind: 'saveError', message: msg.message })
          break

        case 'reviewSubmitted':
          setSubmitting(false)
          setState({ kind: 'submitted' })
          break

        case 'reviewSubmitError':
          setSubmitting(false)
          setState({ kind: 'submitError', message: msg.message })
          break

        case 'draftDeleted':
          setDeleting(false)
          latestResult.current = null
          latestDiff.current = ''
          setState({ kind: 'noDraft' })
          break

        case 'draftDeleteError':
          setDeleting(false)
          setState({ kind: 'error', message: msg.message })
          break

        default:
          break
      }
    })
    return cleanup
  }, [])

  // Capture text selections from the diff/review body so they can be sent as chat context.
  // Only active when the chat panel is shown; ignores selections originating in the chat input itself.
  const showChat = state.kind === 'draftPresent' || state.kind === 'reviewUnsaved'

  useEffect(() => {
    if (!showChat) {
      setSelectedContext('')
      setContextMenu(null)
      return
    }

    function handleMouseUp(e: MouseEvent) {
      if ((e.target as HTMLElement).closest?.('.chat-pane__input')) return
      const text = window.getSelection()?.toString().trim() ?? ''
      if (text) setSelectedContext(text)
    }

    function handleContextMenu(e: MouseEvent) {
      if ((e.target as HTMLElement).closest?.('.chat-pane')) return
      const text = window.getSelection()?.toString().trim() ?? ''
      if (!text) return
      e.preventDefault()
      setContextMenu({ x: e.clientX, y: e.clientY, text })
    }

    // Dismiss context menu when clicking outside it.
    function handlePointerDown(e: PointerEvent) {
      if (!(e.target as HTMLElement).closest?.('.review-pane__context-menu')) {
        setContextMenu(null)
      }
    }

    document.addEventListener('mouseup', handleMouseUp)
    document.addEventListener('contextmenu', handleContextMenu)
    document.addEventListener('pointerdown', handlePointerDown, { capture: true })
    return () => {
      document.removeEventListener('mouseup', handleMouseUp)
      document.removeEventListener('contextmenu', handleContextMenu)
      document.removeEventListener('pointerdown', handlePointerDown, { capture: true })
    }
  }, [showChat])

  const handleChatResizeMove = useCallback((e: MouseEvent) => {
    if (!chatDragRef.current) return
    const delta = chatDragRef.current.startY - e.clientY
    const newHeight = Math.max(MIN_CHAT_HEIGHT, Math.min(MAX_CHAT_HEIGHT, chatDragRef.current.startHeight + delta))
    chatHeightRef.current = newHeight
    setChatHeight(newHeight)
  }, [])

  const handleChatResizeUp = useCallback(() => {
    chatDragRef.current = null
    document.body.style.cursor = ''
    document.body.style.userSelect = ''
    localStorage.setItem(CHAT_HEIGHT_KEY, String(chatHeightRef.current))
    document.removeEventListener('mousemove', handleChatResizeMove)
    document.removeEventListener('mouseup', handleChatResizeUp)
  }, [handleChatResizeMove])

  if (!pr) {
    return (
      <div className="review-pane review-pane--empty">
        <span className="review-pane__placeholder">← select a pull request</span>
      </div>
    )
  }

  const currentPr = pr

  function handleGenerate() {
    setState({ kind: 'generating', messages: ['Starting review…'], chunks: [] })
    sendToHost({ type: 'generateReview', number: currentPr.number, owner: currentPr.owner, repo: currentPr.repo })
  }

  function handleCancel() {
    sendToHost({ type: 'cancelReview' })
    setState({ kind: 'noDraft' })
  }

  function handleSave() {
    setSaving(true)
    sendToHost({ type: 'saveDraft', number: currentPr.number, owner: currentPr.owner, repo: currentPr.repo })
  }

  function handleDelete() {
    setDeleting(true)
    sendToHost({ type: 'deleteDraft', number: currentPr.number, owner: currentPr.owner, repo: currentPr.repo })
  }

  function handleSubmit(verdict: Verdict) {
    setSubmitting(true)
    sendToHost({
      type: 'submitReview',
      number: currentPr.number,
      owner: currentPr.owner,
      repo: currentPr.repo,
      verdict,
      comment: '',
    })
  }

  function handleChatResizeDown(e: React.MouseEvent) {
    e.preventDefault()
    chatDragRef.current = { startY: e.clientY, startHeight: chatHeight }
    document.body.style.cursor = 'ns-resize'
    document.body.style.userSelect = 'none'
    document.addEventListener('mousemove', handleChatResizeMove)
    document.addEventListener('mouseup', handleChatResizeUp)
  }

  const commentCount = commentCountFromState(state)

  return (
    <div className="review-pane">
      <PRHeader
        pr={pr}
        commentCount={commentCount}
        focusedIdx={focusedCommentIdx}
        onNavigate={setFocusedCommentIdx}
        showChat={showChat}
        chatVisible={chatVisible}
        onToggleChat={() => setChatVisible((v) => !v)}
      />
      <div className="review-pane__body">
        <PaneContent
          state={state}
          setState={setState}
          latestResult={latestResult}
          latestDiff={latestDiff}
          focusedCommentIdx={focusedCommentIdx}
          setFocusedCommentIdx={setFocusedCommentIdx}
          onGenerate={handleGenerate}
        />
      </div>
      {showChat && chatVisible && (
        <div className="review-pane__chat-panel" style={{ height: chatHeight }}>
          <div
            className="review-pane__chat-resize-handle"
            onMouseDown={handleChatResizeDown}
            title="Drag to resize"
          />
          <ChatPane
            pr={currentPr}
            selectedContext={selectedContext}
            onContextUsed={() => setSelectedContext('')}
            pendingMessage={pendingChatMessage ?? undefined}
            onPendingMessageSent={() => setPendingChatMessage(null)}
          />
        </div>
      )}
      {contextMenu && (
        <div
          className="review-pane__context-menu"
          style={{ top: contextMenu.y, left: contextMenu.x }}
        >
          <button
            className="review-pane__context-menu-item"
            onClick={() => {
              const { text } = contextMenu
              setSelectedContext(text)
              setContextMenu(null)
              if (!chatVisible) setChatVisible(true)
              setPendingChatMessage({ q: 'What does this do?', ctx: text, id: Date.now() })
            }}
          >
            ◈ Ask Claude: what does this do?
          </button>
        </div>
      )}
      <ReviewFooter
        state={state}
        saving={saving}
        submitting={submitting}
        deleting={deleting}
        onSave={handleSave}
        onSubmit={handleSubmit}
        onCancel={handleCancel}
        onRegenerate={handleGenerate}
        onDelete={handleDelete}
      />
    </div>
  )
}

// ── PR header ────────────────────────────────────────────────────────────────

interface PRHeaderProps {
  pr: PR
  commentCount: number
  focusedIdx: number
  onNavigate: (idx: number) => void
  showChat: boolean
  chatVisible: boolean
  onToggleChat: () => void
}

function PRHeader({ pr, commentCount, focusedIdx, onNavigate, showChat, chatVisible, onToggleChat }: PRHeaderProps) {
  return (
    <div className="review-pane__header">
      <div className="review-pane__header-top">
        <span className="review-pane__pr-number">#{pr.number}</span>
        <span className="review-pane__pr-title" title={pr.title}>
          {pr.title}
        </span>
        {commentCount > 0 && (
          <div className="review-pane__header-nav">
            <button
              className="review-pane__nav-btn"
              onClick={() => onNavigate(Math.max(0, focusedIdx - 1))}
              disabled={focusedIdx <= 0}
              title="Previous comment"
            >
              ↑
            </button>
            <span className="review-pane__nav-pos">{focusedIdx + 1} of {commentCount}</span>
            <button
              className="review-pane__nav-btn"
              onClick={() => onNavigate(Math.min(commentCount - 1, focusedIdx + 1))}
              disabled={focusedIdx >= commentCount - 1}
              title="Next comment"
            >
              ↓
            </button>
          </div>
        )}
        {showChat && (
          <button
            className={`review-pane__chat-btn${chatVisible ? ' review-pane__chat-btn--active' : ''}`}
            onClick={onToggleChat}
            title={chatVisible ? 'Collapse chat' : 'Open Ask Claude'}
          >
            ◈ Chat {chatVisible ? '▾' : '▸'}
          </button>
        )}
        <button
          className="review-pane__open-btn"
          onClick={() => sendToHost({ type: 'openUrl', url: pr.htmlUrl })}
          title="Open PR on GitHub"
        >
          ↗
        </button>
      </div>
      <div className="review-pane__header-meta">
        <span className="review-pane__repo">
          {pr.owner}/{pr.repo}
        </span>
        <span className="review-pane__meta-sep">·</span>
        <span className="review-pane__author">@{pr.author}</span>
      </div>
    </div>
  )
}

// ── Fixed footer with action buttons ─────────────────────────────────────────

interface FooterProps {
  state: PaneState
  saving: boolean
  submitting: boolean
  deleting: boolean
  onSave: () => void
  onSubmit: (v: Verdict) => void
  onCancel: () => void
  onRegenerate: () => void
  onDelete: () => void
}

function ReviewFooter({ state, saving, submitting, deleting, onSave, onSubmit, onCancel, onRegenerate, onDelete }: FooterProps) {
  const [deletePending, setDeletePending] = useState(false)
  const [regenPending, setRegenPending] = useState(false)
  const deleteTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const regenTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    setDeletePending(false)
    setRegenPending(false)
    if (deleteTimerRef.current) clearTimeout(deleteTimerRef.current)
    if (regenTimerRef.current) clearTimeout(regenTimerRef.current)
  }, [state.kind])

  useEffect(() => {
    return () => {
      if (deleteTimerRef.current) clearTimeout(deleteTimerRef.current)
      if (regenTimerRef.current) clearTimeout(regenTimerRef.current)
    }
  }, [])

  function handleDeleteClick() {
    if (!deletePending) {
      setDeletePending(true)
      deleteTimerRef.current = setTimeout(() => setDeletePending(false), 3000)
    } else {
      if (deleteTimerRef.current) clearTimeout(deleteTimerRef.current)
      setDeletePending(false)
      onDelete()
    }
  }

  function handleRegenClick() {
    if (state.kind !== 'draftPresent') {
      onRegenerate()
      return
    }
    if (!regenPending) {
      setRegenPending(true)
      regenTimerRef.current = setTimeout(() => setRegenPending(false), 3000)
    } else {
      if (regenTimerRef.current) clearTimeout(regenTimerRef.current)
      setRegenPending(false)
      onRegenerate()
    }
  }

  if (state.kind === 'generating') {
    return (
      <div className="review-pane__footer">
        <button className="review-pane__btn review-pane__btn--danger" onClick={onCancel}>
          ✕ Cancel
        </button>
      </div>
    )
  }

  if (state.kind === 'draftPresent' || state.kind === 'reviewUnsaved') {
    const busy = saving || submitting || deleting
    return (
      <div className="review-pane__footer">
        <button
          className={`review-pane__btn ${regenPending ? 'review-pane__btn--regen-confirm' : 'review-pane__btn--regen'}`}
          onClick={handleRegenClick}
          disabled={busy}
          title={state.kind === 'draftPresent' ? 'Discard draft and regenerate' : 'Regenerate review'}
        >
          {regenPending ? '↺ Confirm Regen?' : '↺ Regen'}
        </button>
        <div className="review-pane__footer-spacer" />
        {state.kind === 'draftPresent' && (
          <button
            className={`review-pane__btn ${deletePending ? 'review-pane__btn--danger-confirm' : 'review-pane__btn--danger'}`}
            onClick={handleDeleteClick}
            disabled={deleting}
            title="Delete this draft review from GitHub"
          >
            {deleting ? '⋯' : deletePending ? '✕ Confirm?' : '✕ Delete'}
          </button>
        )}
        <button
          className="review-pane__btn review-pane__btn--secondary"
          onClick={onSave}
          disabled={busy}
        >
          {saving ? '↑ Saving…' : '↑ Save'}
        </button>
        <div className="review-pane__footer-divider" />
        <SubmitButtons onSubmit={onSubmit} submitting={submitting} disabled={saving || deleting} />
      </div>
    )
  }

  if (state.kind === 'saveError') {
    return (
      <div className="review-pane__footer">
        <button
          className="review-pane__btn review-pane__btn--secondary"
          onClick={onSave}
          disabled={saving || submitting}
        >
          {saving ? '↑ Saving…' : '↑ Retry Save'}
        </button>
        <SubmitButtons onSubmit={onSubmit} submitting={submitting} disabled={saving} />
      </div>
    )
  }

  if (state.kind === 'submitError') {
    return (
      <div className="review-pane__footer">
        <SubmitButtons onSubmit={onSubmit} submitting={submitting} disabled={false} />
      </div>
    )
  }

  return null
}

// ── Main content switcher ────────────────────────────────────────────────────

interface ContentProps {
  state: PaneState
  setState: React.Dispatch<React.SetStateAction<PaneState>>
  latestResult: React.MutableRefObject<ReviewResult | null>
  latestDiff: React.MutableRefObject<string>
  focusedCommentIdx: number
  setFocusedCommentIdx: React.Dispatch<React.SetStateAction<number>>
  onGenerate: () => void
}

function useElapsedSeconds(active: boolean): number {
  const [seconds, setSeconds] = useState(0)
  useEffect(() => {
    if (!active) { setSeconds(0); return }
    setSeconds(0)
    const id = setInterval(() => setSeconds((s) => s + 1), 1000)
    return () => clearInterval(id)
  }, [active])
  return seconds
}

function formatElapsed(s: number): string {
  const m = Math.floor(s / 60)
  const sec = s % 60
  return m > 0 ? `${m}:${String(sec).padStart(2, '0')}` : `${sec}s`
}

function PaneContent({ state, setState, latestResult, latestDiff, focusedCommentIdx, setFocusedCommentIdx, onGenerate }: ContentProps) {
  const streamLogRef = useRef<HTMLDivElement>(null)
  const elapsed = useElapsedSeconds(state.kind === 'generating')

  useEffect(() => {
    if (state.kind === 'generating' && streamLogRef.current) {
      streamLogRef.current.scrollTop = streamLogRef.current.scrollHeight
    }
  }, [state])

  switch (state.kind) {
    case 'idle':
      return null

    case 'draftLoading':
      return (
        <div className="review-pane__status">
          <span className="review-pane__cursor">█</span>
          <span>Checking for draft…</span>
        </div>
      )

    case 'noDraft':
      return (
        <div className="review-pane__section review-pane__section--center">
          <p className="review-pane__no-draft-text">No pending draft for this PR.</p>
          <button className="review-pane__btn review-pane__btn--primary" onClick={onGenerate}>
            ◈ Generate Review
          </button>
        </div>
      )

    case 'authError':
      return (
        <div className="review-pane__section review-pane__section--center">
          <div className="review-pane__error">{state.message}</div>
          <p className="review-pane__info-sub">Check your GitHub token in plugin settings.</p>
        </div>
      )

    case 'generating':
      return (
        <div className="review-pane__section">
          <div className="review-pane__stream-header">
            <span className="review-pane__cursor">█</span>
            <span className="review-pane__stream-timer">{formatElapsed(elapsed)}</span>
          </div>
          <div className="review-pane__stream-log" ref={streamLogRef}>
            {state.messages.map((msg, i) => (
              <div key={i} className="review-pane__stream-line">
                <span className="review-pane__stream-arrow">→</span>
                <span className="review-pane__stream-text">{msg}</span>
              </div>
            ))}
            {state.chunks.map((c, i) => (
              <div key={`c-${i}`} className={`review-pane__stream-chunk review-pane__stream-chunk--${c.kind}`}>
                <span className="review-pane__stream-chunk-label">{c.kind === 'thinking' ? '◌' : '❯'}</span>
                <span className="review-pane__stream-chunk-content">{c.content}</span>
              </div>
            ))}
          </div>
        </div>
      )

    case 'draftPresent': {
      const result = state.result
      return (
        <>
          {state.staleCommits && <StaleCommitWarning />}
          <div className="review-pane__display-wrap">
            <ReviewDisplay result={result} />
          </div>
          {state.diff && (
            <DiffViewer
              diff={state.diff}
              comments={result.lineComments}
              focusedCommentIdx={focusedCommentIdx}
              onEditComment={(idx, body) => {
                setState((prev) => {
                  if (prev.kind !== 'draftPresent') return prev
                  const lineComments = prev.result.lineComments.map((c, i) =>
                    i === idx ? { ...c, body } : c,
                  )
                  const updated = { ...prev.result, lineComments }
                  latestResult.current = updated
                  return { ...prev, result: updated }
                })
              }}
              onDeleteComment={(idx) => {
                setFocusedCommentIdx((f) => (f > 0 && f >= idx ? f - 1 : f))
                setState((prev) => {
                  if (prev.kind !== 'draftPresent') return prev
                  const lineComments = prev.result.lineComments.filter((_, i) => i !== idx)
                  const updated = { ...prev.result, lineComments }
                  latestResult.current = updated
                  return { ...prev, result: updated }
                })
              }}
              onAddComment={(comment) => {
                const newIdx = result.lineComments.length
                setFocusedCommentIdx(newIdx)
                setState((prev) => {
                  if (prev.kind !== 'draftPresent') return prev
                  const lineComments = [...prev.result.lineComments, comment]
                  const updated = { ...prev.result, lineComments }
                  latestResult.current = updated
                  return { ...prev, result: updated }
                })
              }}
            />
          )}
        </>
      )
    }

    case 'reviewUnsaved': {
      const result = state.result
      return (
        <>
          <div className="review-pane__display-wrap">
            <ReviewDisplay result={result} />
          </div>
          {state.diff && (
            <DiffViewer
              diff={state.diff}
              comments={result.lineComments}
              focusedCommentIdx={focusedCommentIdx}
              onEditComment={(idx, body) => {
                setState((prev) => {
                  if (prev.kind !== 'reviewUnsaved') return prev
                  const lineComments = prev.result.lineComments.map((c, i) =>
                    i === idx ? { ...c, body } : c,
                  )
                  const updated = { ...prev.result, lineComments }
                  latestResult.current = updated
                  return { ...prev, result: updated }
                })
              }}
              onDeleteComment={(idx) => {
                setFocusedCommentIdx((f) => (f > 0 && f >= idx ? f - 1 : f))
                setState((prev) => {
                  if (prev.kind !== 'reviewUnsaved') return prev
                  const lineComments = prev.result.lineComments.filter((_, i) => i !== idx)
                  const updated = { ...prev.result, lineComments }
                  latestResult.current = updated
                  return { ...prev, result: updated }
                })
              }}
              onAddComment={(comment) => {
                const newIdx = result.lineComments.length
                setFocusedCommentIdx(newIdx)
                setState((prev) => {
                  if (prev.kind !== 'reviewUnsaved') return prev
                  const lineComments = [...prev.result.lineComments, comment]
                  const updated = { ...prev.result, lineComments }
                  latestResult.current = updated
                  return { ...prev, result: updated }
                })
              }}
            />
          )}
        </>
      )
    }

    case 'merged':
      return (
        <div className="review-pane__section review-pane__section--center">
          <span className="review-pane__merged-icon">⌀</span>
          <p className="review-pane__info-text">This pull request has been merged.</p>
          {state.status && <p className="review-pane__info-sub">{state.status}</p>}
        </div>
      )

    case 'submitted':
      return (
        <div className="review-pane__section review-pane__section--center">
          <span className="review-pane__success-icon">✓</span>
          <p className="review-pane__info-text">Review submitted.</p>
          <button className="review-pane__btn review-pane__btn--primary" onClick={onGenerate}>
            ◈ Generate New Review
          </button>
        </div>
      )

    case 'error':
      return (
        <div className="review-pane__section">
          <div className="review-pane__error">{state.message}</div>
          <div className="review-pane__inbody-actions">
            <button className="review-pane__btn review-pane__btn--primary" onClick={onGenerate}>
              ↺ Retry
            </button>
          </div>
        </div>
      )

    case 'saveError': {
      const result = latestResult.current
      const diff = latestDiff.current
      return (
        <div className="review-pane__section">
          {result && (
            <div className="review-pane__display-wrap">
              <ReviewDisplay result={result} />
            </div>
          )}
          {result && diff && (
            <DiffViewer
              diff={diff}
              comments={result.lineComments}
              focusedCommentIdx={focusedCommentIdx}
            />
          )}
          <div className="review-pane__error">{state.message}</div>
        </div>
      )
    }

    case 'submitError': {
      const result = latestResult.current
      const diff = latestDiff.current
      return (
        <div className="review-pane__section">
          {result && (
            <div className="review-pane__display-wrap">
              <ReviewDisplay result={result} />
            </div>
          )}
          {result && diff && (
            <DiffViewer
              diff={diff}
              comments={result.lineComments}
              focusedCommentIdx={focusedCommentIdx}
            />
          )}
          <div className="review-pane__error">{state.message}</div>
        </div>
      )
    }
  }
}

// ── Submit buttons ───────────────────────────────────────────────────────────

function SubmitButtons({
  onSubmit,
  submitting,
  disabled,
}: {
  onSubmit: (v: Verdict) => void
  submitting: boolean
  disabled: boolean
}) {
  return (
    <>
      <button
        className="review-pane__btn review-pane__btn--approve"
        onClick={() => onSubmit('APPROVE')}
        disabled={submitting || disabled}
      >
        {submitting ? '…' : '✓ Approve'}
      </button>
      <button
        className="review-pane__btn review-pane__btn--request-changes"
        onClick={() => onSubmit('REQUEST_CHANGES')}
        disabled={submitting || disabled}
      >
        {submitting ? '…' : '✗ Request Changes'}
      </button>
      <button
        className="review-pane__btn review-pane__btn--comment"
        onClick={() => onSubmit('COMMENT')}
        disabled={submitting || disabled}
      >
        {submitting ? '…' : '◇ Comment'}
      </button>
    </>
  )
}

// ── Stale-commit warning ─────────────────────────────────────────────────────

function StaleCommitWarning() {
  return (
    <div className="review-pane__stale-warning">
      ⚠ Draft generated against an older commit — new commits may have been pushed.
    </div>
  )
}
