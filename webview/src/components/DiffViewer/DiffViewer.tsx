import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { Fragment, startTransition, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { parseDiff, isDelete, isInsert } from 'react-diff-view'
import type { ChangeData, FileData, HunkData } from 'react-diff-view'
import { Check, ChevronDown, ChevronUp, Pencil, Plus, Search, ShieldCheck, Trash2, X } from 'lucide-react'
import hljs from 'highlight.js/lib/core'
import hljsBash from 'highlight.js/lib/languages/bash'
import hljsCss from 'highlight.js/lib/languages/css'
import hljsGo from 'highlight.js/lib/languages/go'
import hljsJava from 'highlight.js/lib/languages/java'
import hljsJson from 'highlight.js/lib/languages/json'
import hljsJs from 'highlight.js/lib/languages/javascript'
import hljsKotlin from 'highlight.js/lib/languages/kotlin'
import hljsProto from 'highlight.js/lib/languages/protobuf'
import hljsPython from 'highlight.js/lib/languages/python'
import hljsRust from 'highlight.js/lib/languages/rust'
import hljsSql from 'highlight.js/lib/languages/sql'
import hljsTs from 'highlight.js/lib/languages/typescript'
import hljsXml from 'highlight.js/lib/languages/xml'
import hljsYaml from 'highlight.js/lib/languages/yaml'

hljs.registerLanguage('bash', hljsBash)
hljs.registerLanguage('css', hljsCss)
hljs.registerLanguage('go', hljsGo)
hljs.registerLanguage('java', hljsJava)
hljs.registerLanguage('json', hljsJson)
hljs.registerLanguage('javascript', hljsJs)
hljs.registerLanguage('kotlin', hljsKotlin)
hljs.registerLanguage('protobuf', hljsProto)
hljs.registerLanguage('python', hljsPython)
hljs.registerLanguage('rust', hljsRust)
hljs.registerLanguage('sql', hljsSql)
hljs.registerLanguage('typescript', hljsTs)
hljs.registerLanguage('xml', hljsXml)
hljs.registerLanguage('yaml', hljsYaml)

const EXT_LANG: Record<string, string> = {
  bash: 'bash', sh: 'bash', zsh: 'bash',
  css: 'css', scss: 'css', less: 'css',
  go: 'go',
  html: 'xml', htm: 'xml', svg: 'xml', xml: 'xml',
  java: 'java',
  js: 'javascript', jsx: 'javascript', mjs: 'javascript',
  json: 'json',
  kt: 'kotlin', kts: 'kotlin',
  proto: 'protobuf',
  py: 'python',
  rs: 'rust',
  sql: 'sql',
  ts: 'typescript', tsx: 'typescript',
  yaml: 'yaml', yml: 'yaml',
}

function syntaxHighlight(code: string, filePath: string): string {
  const ext = filePath.split('.').pop()?.toLowerCase() ?? ''
  const lang = EXT_LANG[ext]
  if (!lang) return escapeHtml(code)
  return hljs.highlight(code, { language: lang, ignoreIllegals: true }).value
}

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
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
  AlertDialogTrigger,
} from '@/components/ui/alert-dialog'
import { Badge } from '@/components/ui/badge'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { cn } from '@/lib/utils'
import type { LineComment } from '../../bridge/types'
import './DiffViewer.css'

const MAX_CHANGES = 500

interface Props {
  diff: string
  comments: LineComment[]
  focusedCommentIdx?: number
  onEditComment?: (idx: number, body: string) => void
  onDeleteComment?: (idx: number) => void
  onAddComment?: (comment: LineComment) => void
  onVerifyComment?: (comment: LineComment) => void
}

type IndexedComment = { comment: LineComment; globalIdx: number }
type LineCommentMap = Map<number, IndexedComment[]>
type FileCommentMap = Map<string, LineCommentMap>

function groupComments(comments: LineComment[]): FileCommentMap {
  const map: FileCommentMap = new Map()
  for (let i = 0; i < comments.length; i++) {
    const c = comments[i]
    if (!map.has(c.file)) map.set(c.file, new Map())
    const lineMap = map.get(c.file)!
    if (!lineMap.has(c.line)) lineMap.set(c.line, [])
    lineMap.get(c.line)!.push({ comment: c, globalIdx: i })
  }
  return map
}

function newLineOf(change: ChangeData): number | undefined {
  if (isInsert(change)) return change.lineNumber
  if (isDelete(change)) return undefined
  return change.newLineNumber
}

function oldLineOf(change: ChangeData): number | undefined {
  if (isDelete(change)) return change.lineNumber
  if (isInsert(change)) return undefined
  return change.oldLineNumber
}

function findByPathSuffix(map: FileCommentMap, path: string): LineCommentMap | undefined {
  for (const [key, val] of map) {
    if (path.endsWith(key) || key.endsWith(path)) return val
  }
  return undefined
}

interface PendingNew {
  file: string
  line: number
}

export function DiffViewer({
  diff,
  comments,
  focusedCommentIdx,
  onEditComment,
  onDeleteComment,
  onAddComment,
  onVerifyComment,
}: Props) {
  const [pendingNew, setPendingNew] = useState<PendingNew | null>(null)
  const [showAll, setShowAll] = useState(false)
  const [searchOpen, setSearchOpen] = useState(false)
  const [searchQuery, setSearchQuery] = useState('')
  const [searchCursor, setSearchCursor] = useState(0)
  const [matchCount, setMatchCount] = useState(0)
  const searchInputRef = useRef<HTMLInputElement>(null)
  const containerRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (focusedCommentIdx !== undefined) {
      document
        .getElementById(`diff-comment-${focusedCommentIdx}`)
        ?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
    }
  }, [focusedCommentIdx])

  const openSearch = useCallback(() => {
    setSearchOpen(true)
    setTimeout(() => searchInputRef.current?.focus(), 0)
  }, [])

  const closeSearch = useCallback(() => {
    setSearchOpen(false)
    setSearchQuery('')
    setSearchCursor(0)
  }, [])

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'f') {
        e.preventDefault()
        openSearch()
      }
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [openSearch])

  useEffect(() => { setSearchCursor(0) }, [searchQuery])

  useEffect(() => {
    if (!containerRef.current) return
    const count = searchQuery
      ? containerRef.current.querySelectorAll('.diff-line--search-match').length
      : 0
    setMatchCount(count)
  }, [searchQuery, showAll, diff])

  useEffect(() => {
    if (!containerRef.current || !searchQuery || matchCount === 0) return
    const matches = Array.from(
      containerRef.current.querySelectorAll<HTMLElement>('.diff-line--search-match'),
    )
    const idx = Math.min(searchCursor, matches.length - 1)
    matches.forEach((el, i) => el.classList.toggle('diff-line--search-match--current', i === idx))
    matches[idx]?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
  }, [searchCursor, searchQuery, matchCount])

  const files = useMemo<FileData[]>(() => {
    if (!diff) return []
    try {
      return parseDiff(diff)
    } catch {
      return []
    }
  }, [diff])

  if (files.length === 0) return null

  const totalChanges = files.reduce(
    (sum, f) => sum + f.hunks.reduce((s, h) => s + h.changes.length, 0),
    0,
  )
  const truncating = !showAll && totalChanges > MAX_CHANGES

  let remaining = MAX_CHANGES
  const visibleFiles: FileData[] = truncating
    ? files
        .map((file) => {
          if (remaining <= 0) return null
          const visibleHunks = file.hunks
            .map((hunk) => {
              if (remaining <= 0) return null
              const keep = hunk.changes.slice(0, remaining)
              remaining -= keep.length
              return keep.length > 0 ? { ...hunk, changes: keep } : null
            })
            .filter((h): h is HunkData => h !== null)
          return visibleHunks.length > 0 ? { ...file, hunks: visibleHunks } : null
        })
        .filter((f): f is FileData => f !== null)
    : files

  const byFile = groupComments(comments)

  return (
    <TooltipProvider delayDuration={400}>
      <div ref={containerRef} className="diff-viewer" tabIndex={-1}>
        {searchOpen && (
          <div className="diff-search-bar">
            <Search className="w-3 h-3 text-muted-foreground shrink-0" />
            <input
              ref={searchInputRef}
              type="text"
              className="diff-search-input"
              placeholder="Find in diff…"
              value={searchQuery}
              onChange={(e) => {
                setSearchQuery(e.target.value)
                setSearchCursor(0)
              }}
              onKeyDown={(e) => {
                if (e.key === 'Escape') { e.stopPropagation(); closeSearch() }
                if (e.key === 'Enter') {
                  e.preventDefault()
                  if (matchCount > 0) setSearchCursor((c) => e.shiftKey ? (c - 1 + matchCount) % matchCount : (c + 1) % matchCount)
                }
              }}
            />
            <span className="diff-search-count">
              {searchQuery ? (matchCount === 0 ? 'No results' : `${Math.min(searchCursor + 1, matchCount)} / ${matchCount}`) : ''}
            </span>
            <Button variant="ghost" size="sm" className="h-5 w-5 p-0 text-muted-foreground" onClick={() => matchCount > 0 && setSearchCursor((c) => (c - 1 + matchCount) % matchCount)} disabled={matchCount === 0} aria-label="Previous match"><ChevronUp className="w-3 h-3" /></Button>
            <Button variant="ghost" size="sm" className="h-5 w-5 p-0 text-muted-foreground" onClick={() => matchCount > 0 && setSearchCursor((c) => (c + 1) % matchCount)} disabled={matchCount === 0} aria-label="Next match"><ChevronDown className="w-3 h-3" /></Button>
            <Button variant="ghost" size="sm" className="h-5 w-5 p-0 text-muted-foreground" onClick={closeSearch} aria-label="Close search"><X className="w-3 h-3" /></Button>
          </div>
        )}
        {visibleFiles.map((file) => {
          const displayPath = file.newPath !== '/dev/null' ? file.newPath : file.oldPath
          const fileComments =
            byFile.get(file.newPath) ??
            byFile.get(file.oldPath) ??
            findByPathSuffix(byFile, file.newPath) ??
            new Map<number, IndexedComment[]>()
          return (
            <FileView
              key={`${file.oldRevision}-${file.newRevision}-${file.newPath}`}
              file={file}
              displayPath={displayPath}
              comments={fileComments}
              focusedCommentIdx={focusedCommentIdx}
              searchQuery={searchQuery}
              pendingNew={pendingNew?.file === displayPath ? pendingNew.line : undefined}
              onLineClick={onAddComment ? (line) => setPendingNew({ file: displayPath, line }) : undefined}
              onPendingCancel={() => setPendingNew(null)}
              onPendingSave={(type, body) => {
                if (pendingNew) onAddComment?.({ file: pendingNew.file, line: pendingNew.line, type, body })
                setPendingNew(null)
              }}
              onEditComment={onEditComment}
              onDeleteComment={onDeleteComment}
              onVerifyComment={onVerifyComment}
            />
          )
        })}
        {truncating && (
          <div className="flex items-center justify-between px-4 py-2 border-t border-border bg-card text-xs text-muted-foreground font-mono">
            <span>Showing {MAX_CHANGES} of {totalChanges} changed lines</span>
            <Button variant="outline" size="sm" className="h-6 text-xs" onClick={() => startTransition(() => setShowAll(true))}>
              Show full diff ↓
            </Button>
          </div>
        )}
      </div>
    </TooltipProvider>
  )
}

// ── File block ────────────────────────────────────────────────────────────────

interface FileViewProps {
  file: FileData
  displayPath: string
  comments: LineCommentMap
  focusedCommentIdx?: number
  searchQuery?: string
  pendingNew?: number
  onLineClick?: (line: number) => void
  onPendingCancel: () => void
  onPendingSave: (type: LineComment['type'], body: string) => void
  onEditComment?: (idx: number, body: string) => void
  onDeleteComment?: (idx: number) => void
  onVerifyComment?: (comment: LineComment) => void
}

function FileView({
  file,
  displayPath,
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
}: FileViewProps) {
  return (
    <div className="diff-file">
      <div className="diff-file__header">
        <span className="diff-file__path">{displayPath}</span>
        {file.type !== 'modify' && (
          <span className={`diff-file__badge diff-file__badge--${file.type}`}>{file.type}</span>
        )}
      </div>
      <table className="diff-table">
        <tbody>
          {file.hunks.map((hunk) => (
            <HunkRows
              key={hunk.content}
              hunk={hunk}
              filePath={displayPath}
              comments={comments}
              focusedCommentIdx={focusedCommentIdx}
              searchQuery={searchQuery}
              pendingNewLine={pendingNew}
              onLineClick={onLineClick}
              onPendingCancel={onPendingCancel}
              onPendingSave={onPendingSave}
              onEditComment={onEditComment}
              onDeleteComment={onDeleteComment}
              onVerifyComment={onVerifyComment}
            />
          ))}
        </tbody>
      </table>
    </div>
  )
}

// ── Hunk rows ─────────────────────────────────────────────────────────────────

interface HunkRowsProps {
  hunk: HunkData
  filePath: string
  comments: LineCommentMap
  focusedCommentIdx?: number
  searchQuery?: string
  pendingNewLine?: number
  onLineClick?: (line: number) => void
  onPendingCancel: () => void
  onPendingSave: (type: LineComment['type'], body: string) => void
  onEditComment?: (idx: number, body: string) => void
  onDeleteComment?: (idx: number) => void
  onVerifyComment?: (comment: LineComment) => void
}

function HunkRows({
  hunk,
  filePath,
  comments,
  focusedCommentIdx,
  searchQuery,
  pendingNewLine,
  onLineClick,
  onPendingCancel,
  onPendingSave,
  onEditComment,
  onDeleteComment,
  onVerifyComment,
}: HunkRowsProps) {
  const highlighted = useMemo(
    () => hunk.changes.map((c) => syntaxHighlight(c.content, filePath)),
    // eslint-disable-next-line react-hooks/exhaustive-deps
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
        const canAddOldLineComment = onLineClick && oldLine !== undefined && clickableLine !== undefined
        const canAddNewLineComment = onLineClick && newLine !== undefined && clickableLine !== undefined
        const handleAddComment = () => {
          if (onLineClick && clickableLine !== undefined) onLineClick(clickableLine)
        }
        const handleAddCommentKeyDown = (e: React.KeyboardEvent<HTMLTableCellElement>) => {
          if (e.key !== 'Enter' && e.key !== ' ') return
          e.preventDefault()
          handleAddComment()
        }
        return (
          <Fragment key={i}>
            <tr className={cn(`diff-line diff-line--${change.type}`, searchQuery && change.content.toLowerCase().includes(searchQuery.toLowerCase()) && 'diff-line--search-match')}>
              <td
                className={cn('diff-gutter', canAddOldLineComment && 'diff-gutter--clickable')}
                onClick={canAddOldLineComment ? handleAddComment : undefined}
                onKeyDown={canAddOldLineComment ? handleAddCommentKeyDown : undefined}
                title={onLineClick && clickableLine ? `Add comment at line ${clickableLine}` : undefined}
                role={canAddOldLineComment ? 'button' : undefined}
                tabIndex={canAddOldLineComment ? 0 : undefined}
                aria-label={canAddOldLineComment ? `Add comment at line ${oldLine}` : undefined}
              >
                {oldLineOf(change) ?? ''}
              </td>
              <td
                className={cn('diff-gutter', canAddNewLineComment && 'diff-gutter--clickable')}
                onClick={canAddNewLineComment ? handleAddComment : undefined}
                onKeyDown={canAddNewLineComment ? handleAddCommentKeyDown : undefined}
                title={onLineClick && clickableLine ? `Add comment at line ${clickableLine}` : undefined}
                role={canAddNewLineComment ? 'button' : undefined}
                tabIndex={canAddNewLineComment ? 0 : undefined}
                aria-label={canAddNewLineComment ? `Add comment at line ${newLine}` : undefined}
              >
                {newLine ?? ''}
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
              />
            ))}

            {pendingNewLine !== undefined && clickableLine === pendingNewLine && (
              <NewCommentRow onSave={onPendingSave} onCancel={onPendingCancel} />
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
  blocker: 'text-status-issue border-status-issue/60 bg-status-issue/15',
  major:   'text-status-issue border-status-issue/40 bg-status-issue/10',
  minor:   'text-status-suggestion border-status-suggestion/40 bg-status-suggestion/10',
  nit:     'text-status-note border-status-note/40 bg-status-note/10',
}

const CONFIDENCE_BADGE_CLASS: Record<NonNullable<LineComment['confidence']>, string> = {
  high:   'text-status-approve border-status-approve/40 bg-status-approve/10',
  medium: 'text-status-comment border-status-comment/40 bg-status-comment/10',
  low:    'text-muted-foreground border-border bg-muted/40',
}

interface InlineCommentRowProps {
  comment: LineComment
  globalIdx: number
  focused: boolean
  onEdit?: (body: string) => void
  onDelete?: () => void
  onVerify?: () => void
}

function InlineCommentRow({ comment, globalIdx, focused, onEdit, onDelete, onVerify }: InlineCommentRowProps) {
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(comment.body)
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  useEffect(() => {
    if (!editing) setDraft(comment.body)
  }, [comment.body, editing])

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
            <Badge
              variant="outline"
              className={cn('text-[9px] font-bold tracking-widest uppercase px-1.5 py-0', COMMENT_BADGE_CLASS[comment.type])}
            >
              {comment.type}
            </Badge>
            {comment.severity && (
              <Badge
                variant="outline"
                className={cn('text-[9px] font-bold tracking-widest uppercase px-1.5 py-0', SEVERITY_BADGE_CLASS[comment.severity])}
              >
                {comment.severity}
              </Badge>
            )}
            {comment.category && (
              <Badge
                variant="outline"
                className="text-[9px] font-medium tracking-wide uppercase px-1.5 py-0 text-muted-foreground border-border bg-muted/40"
              >
                {comment.category}
              </Badge>
            )}
            {comment.confidence && (
              <Tooltip>
                <TooltipTrigger asChild>
                  <Badge
                    variant="outline"
                    className={cn('text-[9px] font-medium tracking-wide px-1.5 py-0', CONFIDENCE_BADGE_CLASS[comment.confidence])}
                  >
                    {comment.confidence} conf
                  </Badge>
                </TooltipTrigger>
                {comment.rationale && <TooltipContent side="top" className="max-w-xs">{comment.rationale}</TooltipContent>}
              </Tooltip>
            )}
            {(onVerify || onEdit || onDelete) && !editing && (
              <div className="flex items-center gap-1">
                {onVerify && (
                  <Tooltip>
                    <TooltipTrigger asChild>
                      <Button
                        variant="ghost"
                        size="sm"
                        className="h-6 w-6 p-0 text-muted-foreground hover:text-amber-400"
                        onClick={onVerify}
                        aria-label="Verify with AI"
                      >
                        <ShieldCheck className="w-3.5 h-3.5" />
                      </Button>
                    </TooltipTrigger>
                    <TooltipContent side="top">Verify with AI</TooltipContent>
                  </Tooltip>
                )}
                {onEdit && (
                  <Tooltip>
                    <TooltipTrigger asChild>
                      <Button
                        variant="ghost"
                        size="sm"
                        className="h-6 w-6 p-0 text-muted-foreground hover:text-foreground"
                        onClick={() => setEditing(true)}
                        aria-label="Edit comment"
                      >
                        <Pencil className="w-3.5 h-3.5" />
                      </Button>
                    </TooltipTrigger>
                    <TooltipContent side="top">Edit</TooltipContent>
                  </Tooltip>
                )}
                {onDelete && (
                  <AlertDialog>
                    <Tooltip>
                      <TooltipTrigger asChild>
                        <AlertDialogTrigger asChild>
                          <Button
                            variant="ghost"
                            size="sm"
                            className="h-6 w-6 p-0 text-muted-foreground hover:text-destructive"
                            aria-label="Delete comment"
                          >
                            <Trash2 className="w-3.5 h-3.5" />
                          </Button>
                        </AlertDialogTrigger>
                      </TooltipTrigger>
                      <TooltipContent side="top">Delete</TooltipContent>
                    </Tooltip>
                    <AlertDialogContent>
                      <AlertDialogHeader>
                        <AlertDialogTitle>Delete this comment?</AlertDialogTitle>
                        <AlertDialogDescription>
                          This comment will be removed. Save the draft to persist the change.
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
  onSave,
  onCancel,
}: {
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
              <SelectTrigger className="h-6 w-28 text-xs border-border bg-background">
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
