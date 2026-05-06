import { Fragment, useEffect, useRef, useState } from 'react'
import { parseDiff, isDelete, isInsert } from 'react-diff-view'
import type { ChangeData, FileData, HunkData } from 'react-diff-view'
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
}

// Each comment carries its global index in the `comments` array
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

// Pending new-comment form anchored to a specific file+line in the diff
interface PendingNew {
  file: string
  line: number
}

export function DiffViewer({ diff, comments, focusedCommentIdx, onEditComment, onDeleteComment, onAddComment }: Props) {
  const [pendingNew, setPendingNew] = useState<PendingNew | null>(null)
  const [showAll, setShowAll] = useState(false)

  // Scroll to the focused comment when the index changes
  useEffect(() => {
    if (focusedCommentIdx !== undefined) {
      document
        .getElementById(`diff-comment-${focusedCommentIdx}`)
        ?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
    }
  }, [focusedCommentIdx])

  if (!diff) return null

  let files: FileData[]
  try {
    files = parseDiff(diff)
  } catch {
    return null
  }
  if (files.length === 0) return null

  // Count total changes across all files
  const totalChanges = files.reduce(
    (sum, f) => sum + f.hunks.reduce((s, h) => s + h.changes.length, 0),
    0,
  )
  const truncating = !showAll && totalChanges > MAX_CHANGES

  // Build a truncated view of files if needed
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
    <div className="diff-viewer">
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
            pendingNew={pendingNew?.file === displayPath ? pendingNew.line : undefined}
            onLineClick={
              onAddComment
                ? (line) => setPendingNew({ file: displayPath, line })
                : undefined
            }
            onPendingCancel={() => setPendingNew(null)}
            onPendingSave={(type, body) => {
              if (pendingNew) {
                onAddComment?.({ file: pendingNew.file, line: pendingNew.line, type, body })
              }
              setPendingNew(null)
            }}
            onEditComment={onEditComment}
            onDeleteComment={onDeleteComment}
          />
        )
      })}
      {truncating && (
        <div className="diff-truncated">
          <span>Showing {MAX_CHANGES} of {totalChanges} changed lines</span>
          <button className="diff-truncated__btn" onClick={() => setShowAll(true)}>
            Show full diff ↓
          </button>
        </div>
      )}
    </div>
  )
}

// ── File block ────────────────────────────────────────────────────────────────

interface FileViewProps {
  file: FileData
  displayPath: string
  comments: LineCommentMap
  focusedCommentIdx?: number
  pendingNew?: number
  onLineClick?: (line: number) => void
  onPendingCancel: () => void
  onPendingSave: (type: LineComment['type'], body: string) => void
  onEditComment?: (idx: number, body: string) => void
  onDeleteComment?: (idx: number) => void
}

function FileView({ file, displayPath, comments, focusedCommentIdx, pendingNew, onLineClick, onPendingCancel, onPendingSave, onEditComment, onDeleteComment }: FileViewProps) {
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
              comments={comments}
              focusedCommentIdx={focusedCommentIdx}
              pendingNewLine={pendingNew}
              onLineClick={onLineClick}
              onPendingCancel={onPendingCancel}
              onPendingSave={onPendingSave}
              onEditComment={onEditComment}
              onDeleteComment={onDeleteComment}
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
  comments: LineCommentMap
  focusedCommentIdx?: number
  pendingNewLine?: number
  onLineClick?: (line: number) => void
  onPendingCancel: () => void
  onPendingSave: (type: LineComment['type'], body: string) => void
  onEditComment?: (idx: number, body: string) => void
  onDeleteComment?: (idx: number) => void
}

function HunkRows({ hunk, comments, focusedCommentIdx, pendingNewLine, onLineClick, onPendingCancel, onPendingSave, onEditComment, onDeleteComment }: HunkRowsProps) {
  return (
    <Fragment>
      <tr className="diff-hunk-header">
        <td className="diff-gutter" colSpan={2} />
        <td className="diff-hunk-label">{hunk.content}</td>
      </tr>
      {hunk.changes.map((change, i) => {
        const newLine = newLineOf(change)
        const oldLine = oldLineOf(change)
        const lineComments =
          (newLine !== undefined ? comments.get(newLine) : undefined) ??
          (oldLine !== undefined ? comments.get(oldLine) : undefined) ??
          []
        const clickableLine = newLine ?? oldLine
        return (
          <Fragment key={i}>
            <tr className={`diff-line diff-line--${change.type}`}>
              <td
                className={`diff-gutter${onLineClick && oldLine ? ' diff-gutter--clickable' : ''}`}
                onClick={() => onLineClick && clickableLine && onLineClick(clickableLine)}
                title={onLineClick && clickableLine ? `Add comment at line ${clickableLine}` : undefined}
              >
                {oldLineOf(change) ?? ''}
              </td>
              <td
                className={`diff-gutter${onLineClick && newLine ? ' diff-gutter--clickable' : ''}`}
                onClick={() => onLineClick && clickableLine && onLineClick(clickableLine)}
                title={onLineClick && clickableLine ? `Add comment at line ${clickableLine}` : undefined}
              >
                {newLine ?? ''}
              </td>
              <td className="diff-code">
                <div className="diff-code__scroll">
                  <span className="diff-prefix">
                    {change.type === 'insert' ? '+' : change.type === 'delete' ? '-' : ' '}
                  </span>
                  {change.content}
                </div>
              </td>
            </tr>

            {/* Inline comment annotations */}
            {lineComments.map(({ comment, globalIdx }) => (
              <InlineCommentRow
                key={`c-${globalIdx}`}
                comment={comment}
                globalIdx={globalIdx}
                focused={globalIdx === focusedCommentIdx}
                onEdit={onEditComment ? (body) => onEditComment(globalIdx, body) : undefined}
                onDelete={onDeleteComment ? () => onDeleteComment(globalIdx) : undefined}
              />
            ))}

            {/* Click-to-add new comment form */}
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

interface InlineCommentRowProps {
  comment: LineComment
  globalIdx: number
  focused: boolean
  onEdit?: (body: string) => void
  onDelete?: () => void
}

function InlineCommentRow({ comment, globalIdx, focused, onEdit, onDelete }: InlineCommentRowProps) {
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
      className={`diff-comment-row${focused ? ' diff-comment-row--focused' : ''}`}
    >
      <td colSpan={3} className={`diff-comment-cell diff-comment-cell--${comment.type}`}>
        <div className="diff-comment">
          <div className="diff-comment__header">
            <span className={`diff-comment__badge diff-comment__badge--${comment.type}`}>
              {comment.type}
            </span>
            {(onEdit || onDelete) && !editing && (
              <div className="diff-comment__actions">
                {onEdit && (
                  <button className="diff-action-btn" onClick={() => setEditing(true)} title="Edit">
                    ✎
                  </button>
                )}
                {onDelete && (
                  <button className="diff-action-btn diff-action-btn--delete" onClick={onDelete} title="Delete">
                    ×
                  </button>
                )}
              </div>
            )}
          </div>
          {editing ? (
            <div className="diff-comment__edit">
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
              <div className="diff-comment__edit-actions">
                <button className="diff-edit-btn diff-edit-btn--save" onClick={handleSave}>✓ Save</button>
                <button className="diff-edit-btn diff-edit-btn--cancel" onClick={() => setEditing(false)}>Cancel</button>
              </div>
            </div>
          ) : (
            <p className="diff-comment__body">{comment.body}</p>
          )}
        </div>
      </td>
    </tr>
  )
}

// ── New comment form (click-to-add) ──────────────────────────────────────────

function NewCommentRow({ onSave, onCancel }: { onSave: (type: LineComment['type'], body: string) => void; onCancel: () => void }) {
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
        <div className="diff-comment diff-new-form">
          <div className="diff-new-form__header">
            <select
              className="diff-new-form__type"
              value={type}
              onChange={(e) => setType(e.target.value as LineComment['type'])}
            >
              <option value="note">note</option>
              <option value="issue">issue</option>
              <option value="suggestion">suggestion</option>
            </select>
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
          <div className="diff-comment__edit-actions">
            <button className="diff-edit-btn diff-edit-btn--save" onClick={handleSave}>+ Add</button>
            <button className="diff-edit-btn diff-edit-btn--cancel" onClick={onCancel}>Cancel</button>
          </div>
        </div>
      </td>
    </tr>
  )
}
