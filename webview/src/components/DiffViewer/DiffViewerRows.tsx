import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { Fragment, useEffect, useMemo, useRef, useState } from 'react'
import type { FileData, HunkData } from 'react-diff-view'
import {
  Check,
  MoreHorizontal,
  Pencil,
  Plus,
  ShieldCheck,
  Sparkles,
  Trash2,
  X,
} from 'lucide-react'
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from '@/components/ui/tooltip'
import { Button } from '@/components/ui/button'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Badge } from '@/components/ui/badge'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import type { LineComment } from '@/bridge/types'
import { cn } from '@/lib/utils'
import { findingLabel } from './findingNavigation'
import {
  DELETE_COMMENT_DESCRIPTION,
  newLineOf,
  oldLineOf,
  type LineCommentMap,
  type PendingNew,
} from './diffHelpers'
import { middleTruncateFileName, type DiffFileTreeNode } from './fileNavigation'
import { syntaxHighlight } from './diffSyntax'

function statusAbbreviation(status: string): string {
  switch (status) {
    case 'add':
      return 'A'
    case 'delete':
      return 'D'
    case 'rename':
      return 'R'
    case 'copy':
      return 'C'
    default:
      return 'M'
  }
}

function folderLabel(node: DiffFileTreeNode): string {
  const parts = node.name.split('/')
  if (parts.length <= 2) return node.name
  return `${parts[0]}/.../${parts[parts.length - 1]}`
}

export function FileTreeNodeView({
  node,
  activeFilePath,
  onSelectFile,
}: {
  node: DiffFileTreeNode
  activeFilePath: string
  onSelectFile: (displayPath: string) => void
}) {
  if (node.file) {
    return (
      <li>
        <button
          type="button"
          className={cn('diff-file-tree__file', node.file.displayPath === activeFilePath && 'diff-file-tree__file--active')}
          onClick={() => onSelectFile(node.file!.displayPath)}
          aria-label={node.file.displayPath}
          aria-current={node.file.displayPath === activeFilePath ? 'location' : undefined}
          title={node.file.displayPath}
        >
          <span className={cn('diff-file-tree__status', `diff-file-tree__status--${node.file.status}`)} aria-hidden="true">
            {statusAbbreviation(node.file.status)}
          </span>
          <span className="diff-file-tree__label">{middleTruncateFileName(node.name)}</span>
          {node.file.commentCount > 0 && <span className="diff-file-tree__meta">{node.file.commentCount}</span>}
        </button>
      </li>
    )
  }

  return (
    <li>
      <div className="diff-file-tree__folder" title={node.name}>{folderLabel(node)}</div>
      <ul className="diff-file-tree__children">
        {node.children.map((child) => (
          <FileTreeNodeView
            key={child.key}
            node={child}
            activeFilePath={activeFilePath}
            onSelectFile={onSelectFile}
          />
        ))}
      </ul>
    </li>
  )
}

// ── File block ────────────────────────────────────────────────────────────────

interface FileViewProps {
  file: FileData
  fileIndex: number
  displayPath: string
  comments: LineCommentMap
  focusedCommentIdx?: number
  searchQuery?: string
  pendingNew?: PendingNew
  onSectionRef: (element: HTMLElement | null) => void
  onLineClick?: (target: { line: number; rowId: string }) => void
  onPendingCancel: () => void
  onPendingSave: (type: LineComment['type'], body: string) => void
  onEditComment?: (idx: number, body: string) => void
  onDeleteComment?: (idx: number) => void
  onVerifyComment?: (comment: LineComment) => void
  onSuggestFixComment?: (comment: LineComment) => void
  readOnly: boolean
}

export function FileView({
  file,
  fileIndex,
  displayPath,
  comments,
  focusedCommentIdx,
  searchQuery,
  pendingNew,
  onSectionRef,
  onLineClick,
  onPendingCancel,
  onPendingSave,
  onEditComment,
  onDeleteComment,
  onVerifyComment,
  onSuggestFixComment,
  readOnly,
}: FileViewProps) {
  return (
    <section
      ref={onSectionRef}
      className="diff-file focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
      data-testid={`diff-file-section-${fileIndex}`}
      data-file-path={displayPath}
      aria-labelledby={`diff-file-${CSS.escape(displayPath)}`}
      // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex -- A read-only diff has no gutter buttons, so its horizontal scroll needs a keyboard focus target.
      tabIndex={readOnly ? 0 : undefined}
    >
      <div className="diff-file__header">
        <h2 id={`diff-file-${CSS.escape(displayPath)}`} className="diff-file__path">{displayPath}</h2>
        {file.type !== 'modify' && (
          <span className={`diff-file__badge diff-file__badge--${file.type}`}>{file.type}</span>
        )}
      </div>
      <table className="diff-table">
        <caption className="sr-only">Changes in {displayPath}</caption>
        <thead className="sr-only"><tr><th scope="col">Old line</th><th scope="col">New line</th><th scope="col">Code</th></tr></thead>
        <tbody>
          {file.hunks.map((hunk) => (
            <HunkRows
              key={hunk.content}
              hunk={hunk}
              filePath={displayPath}
              comments={comments}
              focusedCommentIdx={focusedCommentIdx}
              searchQuery={searchQuery}
              pendingNew={pendingNew}
              onLineClick={onLineClick}
              onPendingCancel={onPendingCancel}
              onPendingSave={onPendingSave}
              onEditComment={onEditComment}
              onDeleteComment={onDeleteComment}
              onVerifyComment={onVerifyComment}
              onSuggestFixComment={onSuggestFixComment}
              readOnly={readOnly}
            />
          ))}
        </tbody>
      </table>
    </section>
  )
}

// ── Hunk rows ─────────────────────────────────────────────────────────────────

interface HunkRowsProps {
  hunk: HunkData
  filePath: string
  comments: LineCommentMap
  focusedCommentIdx?: number
  searchQuery?: string
  pendingNew?: PendingNew
  onLineClick?: (target: { line: number; rowId: string }) => void
  onPendingCancel: () => void
  onPendingSave: (type: LineComment['type'], body: string) => void
  onEditComment?: (idx: number, body: string) => void
  onDeleteComment?: (idx: number) => void
  onVerifyComment?: (comment: LineComment) => void
  onSuggestFixComment?: (comment: LineComment) => void
  readOnly: boolean
}

function HunkRows({
  hunk,
  filePath,
  comments,
  focusedCommentIdx,
  searchQuery,
  pendingNew,
  onLineClick,
  onPendingCancel,
  onPendingSave,
  onEditComment,
  onDeleteComment,
  onVerifyComment,
  onSuggestFixComment,
  readOnly,
}: HunkRowsProps) {
  const highlighted = useMemo(
    () => hunk.changes.map((c) => syntaxHighlight(c.content, filePath)),
    [hunk, filePath],
  )

  return (
    <Fragment>
      <tr className="diff-hunk-header">
        <td className="diff-gutter" colSpan={2} />
        <td className="diff-hunk-label">{hunk.content}</td>
      </tr>
      {hunk.changes.map((change, i) => {
        const newLine = newLineOf(change)
        const oldLine = oldLineOf(change)
        const lineComments = newLine !== undefined ? (comments.get(newLine) ?? []) : []
        const clickableLine = newLine ?? oldLine
        const rowId = `${change.type}:${oldLine ?? 'na'}:${newLine ?? 'na'}:${i}`
        const canAddOldLineComment = onLineClick && oldLine !== undefined && clickableLine !== undefined
        const canAddNewLineComment = onLineClick && newLine !== undefined && clickableLine !== undefined
        const handleAddComment = () => {
          if (onLineClick && clickableLine !== undefined) onLineClick({ line: clickableLine, rowId })
        }
        return (
          <Fragment key={i}>
            <tr className={cn(`diff-line diff-line--${change.type}`, searchQuery && change.content.toLowerCase().includes(searchQuery.toLowerCase()) && 'diff-line--search-match')}>
              <td
                className={cn('diff-gutter', canAddOldLineComment && 'diff-gutter--clickable')}
              >
                {canAddOldLineComment ? (
                  <button type="button" className="diff-gutter__button" onClick={handleAddComment} aria-label={`Add comment on ${filePath}, old line ${oldLine}`}>
                    {oldLine}
                  </button>
                ) : (oldLine ?? '')}
              </td>
              <td
                className={cn('diff-gutter', canAddNewLineComment && 'diff-gutter--clickable')}
              >
                {canAddNewLineComment ? (
                  <button type="button" className="diff-gutter__button" onClick={handleAddComment} aria-label={`Add comment on ${filePath}, new line ${newLine}`}>
                    {newLine}
                  </button>
                ) : (newLine ?? '')}
              </td>
              <td className="diff-code">
                <div className="diff-code__scroll">
                  <span className="diff-prefix">
                    {change.type === 'insert' ? '+' : change.type === 'delete' ? '-' : ' '}
                  </span>
                  <span dangerouslySetInnerHTML={{ __html: highlighted[i] }} />
                </div>
              </td>
            </tr>

            {lineComments.map(({ comment, globalIdx }) => (
              <InlineCommentRow
                key={`c-${globalIdx}`}
                comment={comment}
                globalIdx={globalIdx}
                focused={globalIdx === focusedCommentIdx}
                onEdit={onEditComment ? (body) => onEditComment(globalIdx, body) : undefined}
                onDelete={onDeleteComment ? () => onDeleteComment(globalIdx) : undefined}
                onVerify={onVerifyComment ? () => onVerifyComment(comment) : undefined}
                onSuggestFix={onSuggestFixComment ? () => onSuggestFixComment(comment) : undefined}
                readOnly={readOnly}
              />
            ))}

            {pendingNew?.rowId === rowId && (
              <NewCommentRow file={filePath} line={pendingNew.line} onSave={onPendingSave} onCancel={onPendingCancel} />
            )}
          </Fragment>
        )
      })}
    </Fragment>
  )
}

// ── Inline comment row ────────────────────────────────────────────────────────

const COMMENT_BADGE_CLASS: Record<LineComment['type'], string> = {
  issue:      'text-status-issue border-status-issue/50 bg-status-issue/10',
  suggestion: 'text-status-suggestion border-status-suggestion/50 bg-status-suggestion/10',
  note:       'text-status-note border-status-note/50 bg-status-note/10',
}

const SEVERITY_BADGE_CLASS: Record<NonNullable<LineComment['severity']>, string> = {
  blocker: 'text-status-issue border-status-issue/60 bg-status-issue/5',
  major:   'text-status-issue border-status-issue/40 bg-status-issue/10',
  minor:   'text-status-suggestion border-status-suggestion/40 bg-status-suggestion/10',
  nit:     'text-status-note border-status-note/40 bg-status-note/10',
}

interface InlineCommentRowProps {
  comment: LineComment
  globalIdx: number
  focused: boolean
  onEdit?: (body: string) => void
  onDelete?: () => void
  onVerify?: () => void
  onSuggestFix?: () => void
  readOnly: boolean
}

function InlineCommentRow({
  comment,
  globalIdx,
  focused,
  onEdit,
  onDelete,
  onVerify,
  onSuggestFix,
  readOnly,
}: InlineCommentRowProps) {
  const [editing, setEditing] = useState(false)
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false)
  const [draft, setDraft] = useState(comment.body)
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  useEffect(() => {
    if (!editing) setDraft(comment.body)
  }, [comment.body, editing])

  useEffect(() => {
    if (readOnly) {
      setEditing(false)
      setDeleteDialogOpen(false)
    }
  }, [readOnly])

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
    if (trimmed && onEdit) onEdit(trimmed)
    setEditing(false)
  }

  return (
    <tr
      id={`diff-comment-${globalIdx}`}
      className={cn('diff-comment-row', focused && 'diff-comment-row--focused')}
    >
      <td colSpan={3} className={`diff-comment-cell diff-comment-cell--${comment.type}`}>
        <div className="diff-comment">
          <div className="diff-comment__header">
            <div className="diff-comment__identity">
              <Badge
                variant="outline"
                className={cn(
                  'text-[9px] font-bold tracking-wide uppercase px-1.5 py-0',
                  comment.severity ? SEVERITY_BADGE_CLASS[comment.severity] : COMMENT_BADGE_CLASS[comment.type],
                )}
              >
                {findingLabel(comment)}
              </Badge>
              {(comment.category || comment.confidence) && (
                <span className="diff-comment__metadata">
                  {comment.category}
                  {comment.category && comment.confidence && <span aria-hidden="true"> · </span>}
                  {comment.confidence && (
                    <Tooltip>
                      <TooltipTrigger asChild>
                        <span className="cursor-help">{comment.confidence} confidence</span>
                      </TooltipTrigger>
                      {comment.rationale && <TooltipContent side="top" className="max-w-xs">{comment.rationale}</TooltipContent>}
                    </Tooltip>
                  )}
                </span>
              )}
            </div>
            {(onVerify || onSuggestFix || onEdit || onDelete) && !editing && (
              <div className="diff-comment__actions">
                {onVerify && (
                  <Tooltip>
                    <TooltipTrigger asChild>
                      <Button
                        variant="ghost"
                        size="sm"
                        className="diff-comment__ai-action text-muted-foreground hover:text-amber-400"
                        onClick={onVerify}
                        aria-label="Verify with AI"
                        disabled={readOnly}
                      >
                        <ShieldCheck className="w-3.5 h-3.5" />
                        <span className="diff-comment__action-label">Verify</span>
                      </Button>
                    </TooltipTrigger>
                    <TooltipContent side="top">Verify with AI</TooltipContent>
                  </Tooltip>
                )}
                {onSuggestFix && comment.type !== 'note' && (
                  <Tooltip>
                    <TooltipTrigger asChild>
                      <Button
                        variant="ghost"
                        size="sm"
                        className="diff-comment__ai-action text-muted-foreground hover:text-primary"
                        onClick={onSuggestFix}
                        aria-label="Suggest fix with AI"
                        disabled={readOnly}
                      >
                        <Sparkles className="w-3.5 h-3.5" />
                        <span className="diff-comment__action-label">Suggest fix</span>
                      </Button>
                    </TooltipTrigger>
                    <TooltipContent side="top">Suggest fix with AI</TooltipContent>
                  </Tooltip>
                )}
                {(onEdit || onDelete) && (
                  <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                      <Button
                        variant="ghost"
                        size="sm"
                        className="h-6 w-6 p-0 text-muted-foreground hover:text-foreground"
                        aria-label="More finding actions"
                        disabled={readOnly}
                      >
                        <MoreHorizontal className="w-3.5 h-3.5" />
                      </Button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="end">
                      {onEdit && (
                        <DropdownMenuItem className="gap-2 text-xs" onSelect={() => setEditing(true)}>
                          <Pencil className="h-3.5 w-3.5" />
                          Edit comment
                        </DropdownMenuItem>
                      )}
                      {onEdit && onDelete && <DropdownMenuSeparator />}
                      {onDelete && (
                        <DropdownMenuItem
                          className="gap-2 text-xs text-status-issue focus:text-status-issue"
                          onSelect={() => setDeleteDialogOpen(true)}
                        >
                          <Trash2 className="h-3.5 w-3.5" />
                          Delete comment
                        </DropdownMenuItem>
                      )}
                    </DropdownMenuContent>
                  </DropdownMenu>
                )}
                <AlertDialog open={deleteDialogOpen} onOpenChange={setDeleteDialogOpen}>
                  <AlertDialogContent>
                    <AlertDialogHeader>
                      <AlertDialogTitle>Delete this comment?</AlertDialogTitle>
                      <AlertDialogDescription>{DELETE_COMMENT_DESCRIPTION}</AlertDialogDescription>
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
                aria-label={`Edit comment on ${comment.file}, line ${comment.line}`}
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
                <Button size="sm" className="h-6 text-xs gap-1" onClick={handleSave}><Check className="w-3 h-3" />Save</Button>
                <Button variant="ghost" size="sm" className="h-6 text-xs gap-1" onClick={() => setEditing(false)}><X className="w-3 h-3" />Cancel</Button>
              </div>
            </div>
          ) : (
            <div className="diff-comment__body">
            <ReactMarkdown remarkPlugins={[remarkGfm]}>{comment.body}</ReactMarkdown>
          </div>
          )}
        </div>
      </td>
    </tr>
  )
}

// ── New comment form ──────────────────────────────────────────────────────────

function NewCommentRow({
  file,
  line,
  onSave,
  onCancel,
}: {
  file: string
  line: number
  onSave: (type: LineComment['type'], body: string) => void
  onCancel: () => void
}) {
  const [type, setType] = useState<LineComment['type']>('note')
  const [body, setBody] = useState('')
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  useEffect(() => { textareaRef.current?.focus() }, [])

  function handleSave() {
    const trimmed = body.trim()
    if (trimmed) onSave(type, trimmed)
  }

  return (
    <tr className="diff-comment-row diff-comment-row--new">
      <td colSpan={3} className="diff-comment-cell diff-comment-cell--new">
        <div className="diff-comment">
          <div className="flex items-center gap-2 mb-1.5">
            <Select value={type} onValueChange={(v) => setType(v as LineComment['type'])}>
              <SelectTrigger className="h-7 w-28 text-xs border-border bg-background" aria-label={`Comment type for ${file}, line ${line}`}>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="note" className="text-xs">note</SelectItem>
                <SelectItem value="issue" className="text-xs">issue</SelectItem>
                <SelectItem value="suggestion" className="text-xs">suggestion</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <textarea
            ref={textareaRef}
            className="diff-comment__textarea"
            placeholder="Leave a comment…"
            value={body}
            rows={2}
            aria-label={`Comment on ${file}, line ${line}`}
            onChange={(e) => {
              setBody(e.target.value)
              e.target.style.height = 'auto'
              e.target.style.height = e.target.scrollHeight + 'px'
            }}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) handleSave()
              if (e.key === 'Escape') onCancel()
            }}
          />
          <div className="flex gap-1.5 mt-1.5">
            <Button size="sm" className="h-6 text-xs gap-1" onClick={handleSave}><Plus className="w-3 h-3" />Add</Button>
            <Button variant="ghost" size="sm" className="h-6 text-xs gap-1" onClick={onCancel}><X className="w-3 h-3" />Cancel</Button>
          </div>
        </div>
      </td>
    </tr>
  )
}
