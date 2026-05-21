import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  onHostMessage,
  sendToHost,
  type PR,
  type ReviewResult,
  type LineComment,
} from '../../bridge/types'
import { validateComments } from '@/lib/validateComments'
import {
  AlertTriangle,
  Check,
  CheckCircle2,
  ChevronDown,
  ChevronUp,
  CloudUpload,
  ExternalLink,
  GitMerge,
  Loader2,
  MessageSquare,
  RotateCcw,
  Trash2,
  X,
  XCircle,
} from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Alert, AlertDescription } from '@/components/ui/alert'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '@/components/ui/alert-dialog'
import {
  ContextMenu,
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuLabel,
  ContextMenuSeparator,
  ContextMenuTrigger,
} from '@/components/ui/context-menu'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip'
import { cn } from '@/lib/utils'
import { ChatPane } from '../ChatPane'
import { DiffViewer } from '../DiffViewer'
import { ReviewDisplay } from '../ReviewDisplay'

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
  | { kind: 'saveError'; message: string; result: ReviewResult | null; diff: string }
  | { kind: 'submitError'; message: string; result: ReviewResult | null; diff: string }

function sortedComments(comments: LineComment[]): LineComment[] {
  return [...comments].sort((a, b) => a.file.localeCompare(b.file) || a.line - b.line)
}

function withSortedComments(result: ReviewResult): ReviewResult {
  return { ...result, lineComments: sortedComments(result.lineComments) }
}

/**
 * Runs `validateComments` once at intake and bakes the snapped line numbers back into
 * `result.lineComments` so the diff viewer renders comments at their corrected positions.
 * Orphans pass through unchanged; subsequent partitions are computed lazily at render time.
 */
function withValidatedComments(result: ReviewResult, diff: string): ReviewResult {
  const { adjusted, orphans } = validateComments(diff, result.lineComments)
  return { ...result, lineComments: [...adjusted, ...orphans] }
}

function diffOf(state: PaneState): string {
  if (state.kind === 'reviewUnsaved') return state.diff
  if (state.kind === 'draftPresent') return state.diff ?? ''
  if (state.kind === 'saveError' || state.kind === 'submitError') return state.diff
  return ''
}

function resultOf(state: PaneState): ReviewResult | null {
  if (state.kind === 'draftPresent' || state.kind === 'reviewUnsaved') return state.result
  if (state.kind === 'saveError' || state.kind === 'submitError') return state.result
  return null
}

function mutateComments(
  prev: PaneState,
  kinds: PaneState['kind'][],
  fn: (comments: LineComment[]) => LineComment[],
): PaneState {
  if (!kinds.includes(prev.kind)) return prev
  const s = prev as { kind: string; result: ReviewResult; diff?: string; reviewId?: string; staleCommits?: boolean }
  const updated = { ...s.result, lineComments: fn(s.result.lineComments) }
  return { ...prev, result: updated } as PaneState
}

const VERDICT_COLOR: Record<ReviewResult['verdict'], string> = {
  APPROVE: 'text-status-approve',
  REQUEST_CHANGES: 'text-status-changes',
  COMMENT: 'text-status-comment',
}

const VERDICT_LABEL: Record<ReviewResult['verdict'], string> = {
  APPROVE: 'Approve',
  REQUEST_CHANGES: 'Request Changes',
  COMMENT: 'Comment',
}

export function ReviewPane({ pr }: Props) {
  const [state, setState] = useState<PaneState>({ kind: 'idle' })
  const [saving, setSaving] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [focusedCommentIdx, setFocusedCommentIdx] = useState(0)
  const [chatVisible, setChatVisible] = useState(false)
  const [selectedContext, setSelectedContext] = useState('')
  const [pendingChatMessage, setPendingChatMessage] = useState<{ q: string; ctx: string; id: number } | null>(null)
  const [chatHeight, setChatHeight] = useState(loadChatHeight)
  const chatHeightRef = useRef(chatHeight)
  const chatDragRef = useRef<{ startY: number; startHeight: number } | null>(null)
  const pendingVerdict = useRef<Verdict | null>(null)
  const currentPrRef = useRef(pr)

  currentPrRef.current = pr

  useEffect(() => {
    setState({ kind: pr ? 'draftLoading' : 'idle' })
    pendingVerdict.current = null
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
          if (msg.prState === 'MERGED') {
            setState({ kind: 'merged', status: msg.status })
          } else if (msg.prState === 'DRAFT_PRESENT' && msg.result) {
            const diff = msg.diff ?? ''
            const result = withSortedComments(withValidatedComments(msg.result, diff))
            setFocusedCommentIdx(0)
            setState({ kind: 'draftPresent', result, reviewId: msg.reviewId ?? '', staleCommits: msg.staleCommits ?? false, diff })
          } else {
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
            return { ...prev, chunks: [...prev.chunks, { kind: msg.kind, content: msg.chunk }] }
          })
          break

        case 'reviewResult': {
          const diff = msg.diff ?? ''
          const result = withSortedComments(withValidatedComments(msg.result, diff))
          setFocusedCommentIdx(0)
          setState({ kind: 'reviewUnsaved', result, diff })
          break
        }

        case 'reviewError':
          setState({ kind: 'error', message: msg.message })
          break

        case 'draftSaved': {
          setSaving(false)
          if (msg.commentsDropped) {
            toast.warning('Some comments were dropped', {
              description: 'Outdated line references were removed when saving to GitHub.',
            })
          }
          setState((prev) => {
            if (prev.kind !== 'reviewUnsaved' && prev.kind !== 'draftPresent') return prev
            return {
              kind: 'draftPresent',
              result: prev.result,
              reviewId: msg.reviewId,
              staleCommits: false,
              diff: prev.diff,
            }
          })
          const verdict = pendingVerdict.current
          const activePr = currentPrRef.current
          if (verdict && activePr) {
            pendingVerdict.current = null
            setSubmitting(true)
            sendToHost({ type: 'submitReview', number: activePr.number, owner: activePr.owner, repo: activePr.repo, verdict, comment: '' })
          }
          break
        }

        case 'draftSaveError':
          setSaving(false)
          pendingVerdict.current = null
          setState((prev) => ({
            kind: 'saveError',
            message: msg.message,
            result: prev.kind === 'reviewUnsaved' || prev.kind === 'draftPresent' ? prev.result : null,
            diff: prev.kind === 'reviewUnsaved' ? prev.diff : prev.kind === 'draftPresent' ? (prev.diff ?? '') : '',
          }))
          break

        case 'reviewSubmitted':
          setSubmitting(false)
          setState({ kind: 'submitted' })
          break

        case 'reviewSubmitError':
          setSubmitting(false)
          setState((prev) => ({
            kind: 'submitError',
            message: msg.message,
            result: prev.kind === 'draftPresent' || prev.kind === 'reviewUnsaved' ? prev.result : null,
            diff: prev.kind === 'draftPresent' ? (prev.diff ?? '') : prev.kind === 'reviewUnsaved' ? prev.diff : '',
          }))
          break

        case 'draftDeleted':
          setDeleting(false)
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

  const showChat = state.kind === 'draftPresent' || state.kind === 'reviewUnsaved'

  useEffect(() => {
    if (!showChat) {
      setSelectedContext('')
      return
    }

    function handleMouseUp(e: MouseEvent) {
      if ((e.target as HTMLElement).closest?.('.chat-pane__input')) return
      const text = window.getSelection()?.toString().trim() ?? ''
      if (text) setSelectedContext(text)
    }

    document.addEventListener('mouseup', handleMouseUp)
    return () => document.removeEventListener('mouseup', handleMouseUp)
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

  // Hooks must run in the same order on every render — keep all hooks above the early return.
  const result = resultOf(state)
  const diff = diffOf(state)
  const partition = useMemo(
    () => validateComments(diff, result?.lineComments ?? []),
    [diff, result?.lineComments],
  )

  if (!pr) {
    return (
      <div className="flex h-full items-center justify-center bg-background">
        <span className="text-sm text-muted-foreground italic">← select a pull request</span>
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
    setState({ kind: 'draftLoading' })
    sendToHost({ type: 'selectPR', number: currentPr.number, owner: currentPr.owner, repo: currentPr.repo })
  }

  function handleSave() {
    if (state.kind !== 'reviewUnsaved' && state.kind !== 'draftPresent') return
    setSaving(true)
    sendToHost({
      type: 'saveDraft',
      number: currentPr.number,
      owner: currentPr.owner,
      repo: currentPr.repo,
      result: state.result,
      orphans: orphanComments,
    })
  }

  function handleDelete() {
    setDeleting(true)
    sendToHost({ type: 'deleteDraft', number: currentPr.number, owner: currentPr.owner, repo: currentPr.repo })
  }

  function handleSubmit(verdict: Verdict) {
    if (state.kind === 'reviewUnsaved') {
      pendingVerdict.current = verdict
      setSaving(true)
      sendToHost({
        type: 'saveDraft',
        number: currentPr.number,
        owner: currentPr.owner,
        repo: currentPr.repo,
        result: state.result,
        orphans: orphanComments,
      })
      return
    }
    setSubmitting(true)
    sendToHost({ type: 'submitReview', number: currentPr.number, owner: currentPr.owner, repo: currentPr.repo, verdict, comment: '' })
  }

  function handleVerifyComment(comment: LineComment) {
    const q = `Verify this review comment on ${comment.file} line ${comment.line}:\n\n> ${comment.body}\n\nIs this ${comment.type} actually present in the diff?`
    if (!chatVisible) setChatVisible(true)
    setPendingChatMessage({ q, ctx: '', id: Date.now() })
  }

  function handleChatResizeDown(e: React.MouseEvent) {
    e.preventDefault()
    chatDragRef.current = { startY: e.clientY, startHeight: chatHeight }
    document.body.style.cursor = 'ns-resize'
    document.body.style.userSelect = 'none'
    document.addEventListener('mousemove', handleChatResizeMove)
    document.addEventListener('mouseup', handleChatResizeUp)
  }

  const inlineComments = partition.adjusted
  const orphanComments = partition.orphans
  const commentCount = inlineComments.length
  const orphanCount = orphanComments.length
  const totalCount = commentCount + orphanCount
  const hasReview = state.kind === 'draftPresent' || state.kind === 'reviewUnsaved'

  // DiffViewer indexes comments by position in the array we pass it (`inlineComments`),
  // but our mutators operate on positions in the canonical `result.lineComments`. The
  // inlineComments references are stable (validateComments returns the same object when
  // no snap is needed, and snaps are already baked in at intake), so indexOf is safe.
  function inlineToOriginal(inlineIdx: number): number {
    if (!result) return -1
    const target = inlineComments[inlineIdx]
    return target ? result.lineComments.indexOf(target) : -1
  }

  function mutateAtOriginal(origIdx: number, fn: (c: LineComment) => LineComment | null) {
    if (origIdx < 0) return
    setState((prev) =>
      mutateComments(prev, ['draftPresent', 'reviewUnsaved'], (comments) => {
        const next = comments.slice()
        const updated = fn(next[origIdx])
        if (updated === null) next.splice(origIdx, 1)
        else next[origIdx] = updated
        return next
      }),
    )
  }

  const editCommentHandlers = {
    onEditComment: (idx: number, body: string) => {
      mutateAtOriginal(inlineToOriginal(idx), (c) => ({ ...c, body }))
    },
    onDeleteComment: (idx: number) => {
      setFocusedCommentIdx((f) => (f > 0 && f >= idx ? f - 1 : f))
      mutateAtOriginal(inlineToOriginal(idx), () => null)
    },
    onAddComment: (comment: LineComment) => {
      let newFocusIdx = 0
      setState((prev) => {
        if (prev.kind !== 'draftPresent' && prev.kind !== 'reviewUnsaved') return prev
        newFocusIdx = inlineComments.length
        return { ...prev, result: { ...prev.result, lineComments: [...prev.result.lineComments, comment] } }
      })
      setFocusedCommentIdx(newFocusIdx)
    },
  }

  function orphanToOriginal(orphan: LineComment): number {
    if (!result) return -1
    return result.lineComments.indexOf(orphan)
  }

  const orphanHandlers = {
    onEditOrphan: (orphan: LineComment, body: string) => {
      mutateAtOriginal(orphanToOriginal(orphan), (c) => ({ ...c, body }))
    },
    onDeleteOrphan: (orphan: LineComment) => {
      mutateAtOriginal(orphanToOriginal(orphan), () => null)
    },
  }

  return (
    <TooltipProvider delayDuration={400}>
      <div className="flex flex-col h-full bg-background">
        {/* Header */}
        <div className="shrink-0 px-4 py-2.5 border-b border-border bg-card">
          <div className="flex items-center gap-2 min-w-0">
            <span className="font-mono text-xs text-muted-foreground shrink-0">#{pr.number}</span>
            <span className="text-sm font-medium truncate flex-1" title={pr.title}>{pr.title}</span>

            {commentCount > 0 && (
              <div className="flex items-center gap-0.5 shrink-0">
                <Button
                  variant="ghost"
                  size="sm"
                  className="h-6 w-6 p-0"
                  onClick={() => setFocusedCommentIdx(Math.max(0, focusedCommentIdx - 1))}
                  disabled={focusedCommentIdx <= 0}
                  aria-label="Previous comment"
                >
                  <ChevronUp className="w-3.5 h-3.5" />
                </Button>
                <span className="text-xs text-muted-foreground font-mono px-0.5 tabular-nums">
                  {focusedCommentIdx + 1}/{commentCount}
                </span>
                <Button
                  variant="ghost"
                  size="sm"
                  className="h-6 w-6 p-0"
                  onClick={() => setFocusedCommentIdx(Math.min(commentCount - 1, focusedCommentIdx + 1))}
                  disabled={focusedCommentIdx >= commentCount - 1}
                  aria-label="Next comment"
                >
                  <ChevronDown className="w-3.5 h-3.5" />
                </Button>
              </div>
            )}

            {showChat && (
              <Button
                variant={chatVisible ? 'secondary' : 'ghost'}
                size="sm"
                className="h-6 px-2 text-xs shrink-0 gap-1.5"
                onClick={() => setChatVisible((v) => !v)}
                title={chatVisible ? 'Collapse chat' : 'Open Ask Claude'}
              >
                <MessageSquare className="w-3.5 h-3.5" />
                Ask Claude
              </Button>
            )}

            <Tooltip>
              <TooltipTrigger asChild>
                <Button
                  variant="ghost"
                  size="sm"
                  className="h-6 w-6 p-0 shrink-0 text-muted-foreground"
                  onClick={() => sendToHost({ type: 'openUrl', url: pr.htmlUrl })}
                  aria-label="Open PR on GitHub"
                >
                  <ExternalLink className="w-3.5 h-3.5" />
                </Button>
              </TooltipTrigger>
              <TooltipContent>Open on GitHub</TooltipContent>
            </Tooltip>
          </div>

          {/* Context line: verdict+count when review exists, repo otherwise */}
          <div className="flex items-center gap-1.5 mt-1 text-xs">
            {hasReview && (state as { result: ReviewResult }).result ? (
              <>
                <span className={cn('font-mono font-semibold tracking-wide', VERDICT_COLOR[(state as { result: ReviewResult }).result.verdict])}>
                  {VERDICT_LABEL[(state as { result: ReviewResult }).result.verdict]}
                </span>
                {totalCount > 0 && (
                  <>
                    <span className="text-muted-foreground">·</span>
                    <span className="text-muted-foreground">{totalCount} comment{totalCount !== 1 ? 's' : ''}</span>
                  </>
                )}
                {orphanCount > 0 && (
                  <Tooltip>
                    <TooltipTrigger asChild>
                      <span className="text-status-suggestion font-mono cursor-help">· {orphanCount} unanchored</span>
                    </TooltipTrigger>
                    <TooltipContent>
                      Comment{orphanCount !== 1 ? 's' : ''} GitHub cannot attach inline — line is outside the PR's hunks.
                    </TooltipContent>
                  </Tooltip>
                )}
                {state.kind === 'reviewUnsaved' && (
                  <span className="text-status-suggestion font-mono">· unsaved</span>
                )}
              </>
            ) : (
              <span className="font-mono text-muted-foreground/70">{pr.owner}/{pr.repo}</span>
            )}
          </div>
        </div>

        {/* Body */}
        <ContextMenu>
          <ContextMenuTrigger asChild>
            <div className="flex-1 overflow-y-auto min-h-0">
              <PaneContent
                state={state}
                focusedCommentIdx={focusedCommentIdx}
                setFocusedCommentIdx={setFocusedCommentIdx}
                onGenerate={handleGenerate}
                onVerifyComment={showChat ? handleVerifyComment : undefined}
                editCommentHandlers={editCommentHandlers}
                inlineComments={inlineComments}
                orphanComments={orphanComments}
                onEditOrphan={orphanHandlers.onEditOrphan}
                onDeleteOrphan={orphanHandlers.onDeleteOrphan}
              />
            </div>
          </ContextMenuTrigger>
          <ContextMenuContent>
            {showChat ? (
              selectedContext ? (
                <>
                  <ContextMenuLabel className="text-[10px] font-normal text-muted-foreground max-w-[220px] truncate py-1">
                    "{selectedContext.length > 60 ? selectedContext.slice(0, 60) + '…' : selectedContext}"
                  </ContextMenuLabel>
                  <ContextMenuSeparator />
                  {(['What does this do?', 'Why is this here?', 'Is this correct?', 'Can this be simplified?'] as const).map((q) => (
                    <ContextMenuItem
                      key={q}
                      onSelect={() => {
                        if (!chatVisible) setChatVisible(true)
                        setPendingChatMessage({ q, ctx: selectedContext, id: Date.now() })
                      }}
                      className="gap-2 text-xs"
                    >
                      <MessageSquare className="w-3.5 h-3.5" />
                      {q}
                    </ContextMenuItem>
                  ))}
                </>
              ) : (
                <ContextMenuItem disabled className="gap-2 text-xs opacity-60">
                  <MessageSquare className="w-3.5 h-3.5" />
                  Select text to ask Claude
                </ContextMenuItem>
              )
            ) : (
              <ContextMenuItem disabled className="gap-2 text-xs opacity-60">
                <MessageSquare className="w-3.5 h-3.5" />
                Generate a review to enable chat
              </ContextMenuItem>
            )}
          </ContextMenuContent>
        </ContextMenu>

        {/* Chat panel */}
        {showChat && (
          <div
            className="shrink-0 border-t border-border overflow-hidden transition-[height]"
            style={{ height: chatVisible ? chatHeight : 0 }}
          >
            <ChatPane
              pr={currentPr}
              selectedContext={selectedContext}
              onContextUsed={() => setSelectedContext('')}
              pendingMessage={pendingChatMessage ?? undefined}
              onPendingMessageSent={() => setPendingChatMessage(null)}
              onResizeStart={handleChatResizeDown}
            />
          </div>
        )}

        {/* Footer */}
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
    </TooltipProvider>
  )
}

// ── Pane content switcher ────────────────────────────────────────────────────

interface ContentProps {
  state: PaneState
  focusedCommentIdx: number
  setFocusedCommentIdx: React.Dispatch<React.SetStateAction<number>>
  onGenerate: () => void
  onVerifyComment?: (comment: LineComment) => void
  editCommentHandlers: {
    onEditComment: (idx: number, body: string) => void
    onDeleteComment: (idx: number) => void
    onAddComment: (comment: LineComment) => void
  }
  inlineComments: LineComment[]
  orphanComments: LineComment[]
  onEditOrphan: (orphan: LineComment, body: string) => void
  onDeleteOrphan: (orphan: LineComment) => void
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

function ReviewAndDiff({
  result,
  diff,
  focusedCommentIdx,
  setFocusedCommentIdx,
  editCommentHandlers,
  onVerifyComment,
  staleCommits,
  inlineComments,
  orphanComments,
  onEditOrphan,
  onDeleteOrphan,
}: {
  result: ReviewResult
  diff?: string
  focusedCommentIdx: number
  setFocusedCommentIdx: React.Dispatch<React.SetStateAction<number>>
  editCommentHandlers: ContentProps['editCommentHandlers']
  onVerifyComment?: (comment: LineComment) => void
  staleCommits?: boolean
  inlineComments: LineComment[]
  orphanComments: LineComment[]
  onEditOrphan: (orphan: LineComment, body: string) => void
  onDeleteOrphan: (orphan: LineComment) => void
}) {
  return (
    <>
      {staleCommits && (
        <Alert className="mx-4 mt-3 mb-0 border-status-suggestion/40 bg-status-suggestion/5">
          <AlertTriangle className="h-3.5 w-3.5 text-status-suggestion" />
          <AlertDescription className="text-xs text-status-suggestion/90">
            Draft generated against an older commit — new commits may have been pushed.
          </AlertDescription>
        </Alert>
      )}
      <div className="px-4 pt-3">
        <ReviewDisplay result={result} />
      </div>
      {orphanComments.length > 0 && (
        <div className="px-4 pt-3">
          <OrphanCommentsSection
            orphans={orphanComments}
            onEdit={onEditOrphan}
            onDelete={onDeleteOrphan}
          />
        </div>
      )}
      {diff && (
        <div className="px-4 pb-4">
          <DiffViewer
            diff={diff}
            comments={inlineComments}
            focusedCommentIdx={focusedCommentIdx}
            onEditComment={editCommentHandlers.onEditComment}
            onDeleteComment={(idx) => {
              setFocusedCommentIdx((f) => (f > 0 && f >= idx ? f - 1 : f))
              editCommentHandlers.onDeleteComment(idx)
            }}
            onAddComment={editCommentHandlers.onAddComment}
            onVerifyComment={onVerifyComment}
          />
        </div>
      )}
    </>
  )
}

function ErrorWithReview({
  message,
  result,
  diff,
  focusedCommentIdx,
  setFocusedCommentIdx,
  editCommentHandlers,
  inlineComments,
  orphanComments,
  onEditOrphan,
  onDeleteOrphan,
}: {
  message: string
  result: ReviewResult | null
  diff: string
  focusedCommentIdx: number
  setFocusedCommentIdx: React.Dispatch<React.SetStateAction<number>>
  editCommentHandlers: ContentProps['editCommentHandlers']
  inlineComments: LineComment[]
  orphanComments: LineComment[]
  onEditOrphan: (orphan: LineComment, body: string) => void
  onDeleteOrphan: (orphan: LineComment) => void
}) {
  return (
    <div className="flex flex-col">
      {result && (
        <ReviewAndDiff
          result={result}
          diff={diff || undefined}
          focusedCommentIdx={focusedCommentIdx}
          setFocusedCommentIdx={setFocusedCommentIdx}
          editCommentHandlers={editCommentHandlers}
          inlineComments={inlineComments}
          orphanComments={orphanComments}
          onEditOrphan={onEditOrphan}
          onDeleteOrphan={onDeleteOrphan}
        />
      )}
      <div className="px-4 pb-3">
        <Alert variant="destructive">
          <AlertDescription>{message}</AlertDescription>
        </Alert>
      </div>
    </div>
  )
}

function PaneContent({
  state,
  focusedCommentIdx,
  setFocusedCommentIdx,
  onGenerate,
  onVerifyComment,
  editCommentHandlers,
  inlineComments,
  orphanComments,
  onEditOrphan,
  onDeleteOrphan,
}: ContentProps) {
  const elapsed = useElapsedSeconds(state.kind === 'generating')

  switch (state.kind) {
    case 'idle':
      return null

    case 'draftLoading':
      return (
        <div className="flex items-center gap-2.5 px-4 pt-3 text-sm text-muted-foreground">
          <Loader2 className="w-4 h-4 text-primary animate-spin shrink-0" />
          Checking for draft…
        </div>
      )

    case 'noDraft':
      return (
        <div className="flex flex-col items-center justify-center gap-4 p-8">
          <p className="text-sm text-muted-foreground">No pending draft for this PR.</p>
          <Button onClick={onGenerate} className="gap-2">
            Generate Review
          </Button>
        </div>
      )

    case 'authError':
      return (
        <div className="p-4 flex flex-col gap-3">
          <Alert variant="destructive">
            <AlertDescription>{state.message}</AlertDescription>
          </Alert>
          <p className="text-xs text-muted-foreground">Check your GitHub token in plugin settings.</p>
        </div>
      )

    case 'generating': {
      const latestMessage = state.messages[state.messages.length - 1] ?? 'Starting review…'
      const isThinking = elapsed >= 10 && state.chunks.length === 0
      const latestChunks = state.chunks.slice(-5)
      return (
        <div className="flex flex-col gap-4 p-6">
          <div className="flex items-center gap-3">
            <Loader2 className="w-4 h-4 text-primary animate-spin shrink-0" />
            <div className="flex-1 min-w-0">
              <p className="text-sm text-foreground/80 truncate">{latestMessage}</p>
              <p className="text-xs text-muted-foreground font-mono mt-0.5">
                {elapsed < 2 ? 'Starting…' : formatElapsed(elapsed)}
              </p>
            </div>
          </div>

          {/* Indeterminate progress bar */}
          <div className="relative h-0.5 bg-border rounded-full overflow-hidden">
            <div
              className="absolute inset-y-0 w-2/5 bg-primary rounded-full"
              style={{ animation: 'progress-slide 1.5s ease-in-out infinite' }}
            />
          </div>

          {isThinking && (
            <p className="text-xs text-muted-foreground italic">
              {elapsed >= 60 ? 'Large diffs may take a few minutes…' : 'Claude is thinking…'}
            </p>
          )}

          {latestChunks.length > 0 && (
            <p className="text-xs text-muted-foreground/60 font-mono break-words line-clamp-3 leading-relaxed">
              {latestChunks.map((c) => c.content).join('')}
            </p>
          )}
        </div>
      )
    }

    case 'draftPresent':
      return (
        <ReviewAndDiff
          result={state.result}
          diff={state.diff}
          focusedCommentIdx={focusedCommentIdx}
          setFocusedCommentIdx={setFocusedCommentIdx}
          editCommentHandlers={editCommentHandlers}
          onVerifyComment={onVerifyComment}
          staleCommits={state.staleCommits}
          inlineComments={inlineComments}
          orphanComments={orphanComments}
          onEditOrphan={onEditOrphan}
          onDeleteOrphan={onDeleteOrphan}
        />
      )

    case 'reviewUnsaved':
      return (
        <ReviewAndDiff
          result={state.result}
          diff={state.diff}
          focusedCommentIdx={focusedCommentIdx}
          setFocusedCommentIdx={setFocusedCommentIdx}
          editCommentHandlers={editCommentHandlers}
          onVerifyComment={onVerifyComment}
          inlineComments={inlineComments}
          orphanComments={orphanComments}
          onEditOrphan={onEditOrphan}
          onDeleteOrphan={onDeleteOrphan}
        />
      )

    case 'merged':
      return (
        <div className="flex flex-col items-center justify-center gap-2 p-8">
          <GitMerge className="w-9 h-9 text-muted-foreground/50" />
          <p className="text-sm text-muted-foreground">This pull request has been merged.</p>
          {state.status && <p className="text-xs text-muted-foreground">{state.status}</p>}
        </div>
      )

    case 'submitted':
      return (
        <div className="flex flex-col items-center justify-center gap-3 p-8">
          <CheckCircle2 className="w-9 h-9 text-emerald-500" />
          <p className="text-sm text-muted-foreground">Review submitted.</p>
          <Button variant="outline" onClick={onGenerate} className="gap-2">
            <RotateCcw className="w-3.5 h-3.5" />
            Generate New Review
          </Button>
        </div>
      )

    case 'error':
      return (
        <div className="p-4 flex flex-col gap-3">
          <Alert variant="destructive">
            <AlertDescription>{state.message}</AlertDescription>
          </Alert>
          <Button variant="outline" size="sm" onClick={onGenerate} className="w-fit gap-1.5">
            <RotateCcw className="w-3.5 h-3.5" />
            Try Again
          </Button>
        </div>
      )

    case 'saveError':
      return (
        <ErrorWithReview
          message={state.message}
          result={state.result}
          diff={state.diff}
          focusedCommentIdx={focusedCommentIdx}
          setFocusedCommentIdx={setFocusedCommentIdx}
          editCommentHandlers={editCommentHandlers}
          inlineComments={inlineComments}
          orphanComments={orphanComments}
          onEditOrphan={onEditOrphan}
          onDeleteOrphan={onDeleteOrphan}
        />
      )

    case 'submitError':
      return (
        <ErrorWithReview
          message={state.message}
          result={state.result}
          diff={state.diff}
          focusedCommentIdx={focusedCommentIdx}
          setFocusedCommentIdx={setFocusedCommentIdx}
          editCommentHandlers={editCommentHandlers}
          inlineComments={inlineComments}
          orphanComments={orphanComments}
          onEditOrphan={onEditOrphan}
          onDeleteOrphan={onDeleteOrphan}
        />
      )
  }
}

// ── Footer ────────────────────────────────────────────────────────────────────

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

function ReviewFooter({
  state,
  saving,
  submitting,
  deleting,
  onSave,
  onSubmit,
  onCancel,
  onRegenerate,
  onDelete,
}: FooterProps) {
  if (state.kind === 'generating') {
    return (
      <div className="shrink-0 flex items-center gap-2 px-4 py-2.5 border-t border-border bg-card">
        <Button variant="destructive" size="sm" onClick={onCancel} className="gap-1.5">
          <X className="w-3.5 h-3.5" />
          Cancel
        </Button>
      </div>
    )
  }

  if (state.kind === 'draftPresent' || state.kind === 'reviewUnsaved') {
    const busy = saving || submitting || deleting
    return (
      <div className="shrink-0 flex items-center gap-2 px-4 py-2.5 border-t border-border bg-card">
        {/* Regen — confirm when saved draft exists */}
        {state.kind === 'draftPresent' ? (
          <AlertDialog>
            <AlertDialogTrigger asChild>
              <Button variant="ghost" size="sm" disabled={busy} className="gap-1.5 text-xs">
                <RotateCcw className="w-3.5 h-3.5" />
                Regen
              </Button>
            </AlertDialogTrigger>
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>Regenerate review?</AlertDialogTitle>
                <AlertDialogDescription>
                  The current draft will be discarded and a new review generated from scratch.
                </AlertDialogDescription>
              </AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel>Cancel</AlertDialogCancel>
                <AlertDialogAction onClick={onRegenerate}>Regenerate</AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        ) : (
          <Button variant="ghost" size="sm" disabled={busy} className="gap-1.5 text-xs" onClick={onRegenerate}>
            <RotateCcw className="w-3.5 h-3.5" />
            Regen
          </Button>
        )}

        <div className="flex-1" />

        {/* Delete — always confirm */}
        {state.kind === 'draftPresent' && (
          <AlertDialog>
            <AlertDialogTrigger asChild>
              <Button variant="ghost" size="sm" disabled={deleting} className="gap-1.5 text-xs text-destructive hover:text-destructive">
                {deleting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Trash2 className="w-3.5 h-3.5" />}
                Delete
              </Button>
            </AlertDialogTrigger>
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>Delete draft review?</AlertDialogTitle>
                <AlertDialogDescription>
                  This removes the pending review from GitHub permanently.
                </AlertDialogDescription>
              </AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel>Cancel</AlertDialogCancel>
                <AlertDialogAction
                  onClick={onDelete}
                  className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
                >
                  Delete
                </AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        )}

        <Button variant="secondary" size="sm" onClick={onSave} disabled={busy} className="gap-1.5 text-xs">
          {saving ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <CloudUpload className="w-3.5 h-3.5" />}
          {saving ? 'Saving…' : 'Save Draft'}
        </Button>

        <SubmitSplitButton verdict={state.kind === 'draftPresent' || state.kind === 'reviewUnsaved' ? state.result.verdict : 'APPROVE'} onSubmit={onSubmit} submitting={submitting} disabled={saving || deleting} />
      </div>
    )
  }

  if (state.kind === 'saveError') {
    return (
      <div className="shrink-0 flex items-center gap-2 px-4 py-2.5 border-t border-border bg-card">
        <Button variant="secondary" size="sm" onClick={onSave} disabled={saving || submitting} className="gap-1.5 text-xs">
          {saving ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <CloudUpload className="w-3.5 h-3.5" />}
          {saving ? 'Saving…' : 'Retry Save'}
        </Button>
        <SubmitSplitButton verdict={state.kind === 'saveError' && state.result ? state.result.verdict : 'APPROVE'} onSubmit={onSubmit} submitting={submitting} disabled={saving} />
      </div>
    )
  }

  if (state.kind === 'submitError') {
    return (
      <div className="shrink-0 flex items-center gap-2 px-4 py-2.5 border-t border-border bg-card">
        <SubmitSplitButton verdict={state.kind === 'submitError' && state.result ? state.result.verdict : 'APPROVE'} onSubmit={onSubmit} submitting={submitting} disabled={false} />
      </div>
    )
  }

  return null
}

// ── Split submit button ───────────────────────────────────────────────────────

function SubmitSplitButton({
  verdict,
  onSubmit,
  submitting,
  disabled,
}: {
  verdict: Verdict
  onSubmit: (v: Verdict) => void
  submitting: boolean
  disabled: boolean
}) {
  const ICON: Record<Verdict, React.ReactNode> = {
    APPROVE: <Check className="w-3.5 h-3.5" />,
    REQUEST_CHANGES: <XCircle className="w-3.5 h-3.5" />,
    COMMENT: <MessageSquare className="w-3.5 h-3.5" />,
  }
  const LABEL: Record<Verdict, string> = {
    APPROVE: 'Approve',
    REQUEST_CHANGES: 'Request Changes',
    COMMENT: 'Comment',
  }
  const VARIANT: Record<Verdict, 'default' | 'destructive' | 'secondary'> = {
    APPROVE: 'default',
    REQUEST_CHANGES: 'destructive',
    COMMENT: 'secondary',
  }

  const others: Verdict[] = (['COMMENT', 'APPROVE', 'REQUEST_CHANGES'] as Verdict[]).filter((v) => v !== verdict)

  return (
    <div className="flex items-stretch rounded-md overflow-hidden">
      <Button
        variant={VARIANT[verdict]}
        size="sm"
        className="text-xs rounded-r-none gap-1.5 border-r border-white/20"
        onClick={() => onSubmit(verdict)}
        disabled={submitting || disabled}
      >
        {submitting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : ICON[verdict]}
        {submitting ? 'Submitting…' : LABEL[verdict]}
      </Button>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button
            variant={VARIANT[verdict]}
            size="sm"
            className="text-xs rounded-l-none px-1.5"
            disabled={submitting || disabled}
            aria-label="More submit options"
          >
            <ChevronDown className="w-3.5 h-3.5" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-48">
          {others.filter((v) => v !== 'REQUEST_CHANGES').map((v) => (
            <DropdownMenuItem
              key={v}
              onSelect={() => onSubmit(v)}
              className="gap-2 text-xs cursor-pointer"
            >
              {ICON[v]}
              {LABEL[v]}
            </DropdownMenuItem>
          ))}
          {others.includes('REQUEST_CHANGES') && <DropdownMenuSeparator />}
          {others.includes('REQUEST_CHANGES') && (
            <DropdownMenuItem
              onSelect={() => onSubmit('REQUEST_CHANGES')}
              className="gap-2 text-xs cursor-pointer text-destructive focus:text-destructive"
            >
              {ICON.REQUEST_CHANGES}
              {LABEL.REQUEST_CHANGES}
            </DropdownMenuItem>
          )}
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  )
}

// ── Orphan (unanchored) comments ──────────────────────────────────────────────

const ORPHAN_BADGE: Record<LineComment['type'], string> = {
  issue:      'text-status-issue border-status-issue/50 bg-status-issue/10',
  suggestion: 'text-status-suggestion border-status-suggestion/50 bg-status-suggestion/10',
  note:       'text-status-note border-status-note/50 bg-status-note/10',
}

function OrphanCommentsSection({
  orphans,
  onEdit,
  onDelete,
}: {
  orphans: LineComment[]
  onEdit: (orphan: LineComment, body: string) => void
  onDelete: (orphan: LineComment) => void
}) {
  return (
    <div className="rounded border border-status-suggestion/40 bg-status-suggestion/5">
      <div className="flex items-center gap-2 px-3 py-1.5 border-b border-status-suggestion/30">
        <AlertTriangle className="h-3.5 w-3.5 text-status-suggestion shrink-0" />
        <span className="text-xs font-semibold tracking-wide text-status-suggestion">
          Unanchored comments
        </span>
        <span className="text-[10px] text-muted-foreground font-mono">
          GitHub can't attach these inline
        </span>
      </div>
      <ul className="divide-y divide-status-suggestion/20">
        {orphans.map((o, i) => (
          <OrphanRow
            key={`${o.file}|${o.line}|${i}`}
            orphan={o}
            onEdit={(body) => onEdit(o, body)}
            onDelete={() => onDelete(o)}
          />
        ))}
      </ul>
    </div>
  )
}

function OrphanRow({
  orphan,
  onEdit,
  onDelete,
}: {
  orphan: LineComment
  onEdit: (body: string) => void
  onDelete: () => void
}) {
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(orphan.body)
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  useEffect(() => { if (!editing) setDraft(orphan.body) }, [editing, orphan.body])
  useEffect(() => {
    if (editing && textareaRef.current) {
      const el = textareaRef.current
      el.focus()
      el.style.height = 'auto'
      el.style.height = el.scrollHeight + 'px'
    }
  }, [editing])

  function handleSave() {
    const trimmed = draft.trim()
    if (trimmed) onEdit(trimmed)
    setEditing(false)
  }

  return (
    <li className="px-3 py-2 flex flex-col gap-1.5">
      <div className="flex items-center gap-2">
        <span className={cn('inline-flex items-center rounded border px-1.5 py-0 text-[9px] font-bold tracking-widest uppercase', ORPHAN_BADGE[orphan.type])}>
          {orphan.type}
        </span>
        <span className="font-mono text-[11px] text-muted-foreground truncate flex-1">
          {orphan.file}:{orphan.line}
        </span>
        {!editing && (
          <div className="flex items-center gap-1">
            <Button
              variant="ghost"
              size="sm"
              className="h-6 px-1.5 text-[10px] text-muted-foreground hover:text-foreground"
              onClick={() => setEditing(true)}
              aria-label="Edit unanchored comment"
            >
              Edit
            </Button>
            <Button
              variant="ghost"
              size="sm"
              className="h-6 px-1.5 text-[10px] text-muted-foreground hover:text-destructive"
              onClick={onDelete}
              aria-label="Delete unanchored comment"
            >
              Delete
            </Button>
          </div>
        )}
      </div>
      {editing ? (
        <div className="flex flex-col gap-1.5">
          <textarea
            ref={textareaRef}
            className="diff-comment__textarea"
            value={draft}
            rows={2}
            onChange={(e) => {
              setDraft(e.target.value)
              e.target.style.height = 'auto'
              e.target.style.height = e.target.scrollHeight + 'px'
            }}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) handleSave()
              if (e.key === 'Escape') setEditing(false)
            }}
          />
          <div className="flex gap-1.5">
            <Button size="sm" className="h-6 text-xs gap-1" onClick={handleSave}>
              <Check className="w-3 h-3" />Save
            </Button>
            <Button variant="ghost" size="sm" className="h-6 text-xs gap-1" onClick={() => setEditing(false)}>
              <X className="w-3 h-3" />Cancel
            </Button>
          </div>
        </div>
      ) : (
        <p className="text-xs leading-snug whitespace-pre-wrap">{orphan.body}</p>
      )}
    </li>
  )
}
