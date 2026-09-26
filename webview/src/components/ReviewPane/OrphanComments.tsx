import { useEffect, useRef, useState } from 'react'
import { AlertTriangle, Check, X } from 'lucide-react'
import type { LineComment } from '../../bridge/types'
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
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { DELETE_COMMENT_DESCRIPTION } from '../DiffViewer/DiffViewer'

const ORPHAN_BADGE: Record<LineComment['type'], string> = {
  issue: 'text-status-issue border-status-issue/50 bg-status-issue/10',
  suggestion: 'text-status-suggestion border-status-suggestion/50 bg-status-suggestion/10',
  note: 'text-status-note border-status-note/50 bg-status-note/10',
}

export function OrphanCommentsSection({
  orphans,
  onEdit,
  onDelete,
  readOnly = false,
}: {
  orphans: LineComment[]
  onEdit?: (orphan: LineComment, body: string) => void
  onDelete?: (orphan: LineComment) => void
  readOnly?: boolean
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
        {orphans.map((orphan, index) => (
          <OrphanRow
            key={`${orphan.file}|${orphan.line}|${index}`}
            index={index}
            orphan={orphan}
            onEdit={onEdit ? (body) => onEdit(orphan, body) : undefined}
            onDelete={onDelete ? () => onDelete(orphan) : undefined}
            readOnly={readOnly}
          />
        ))}
      </ul>
    </div>
  )
}

function OrphanRow({
  index,
  orphan,
  onEdit,
  onDelete,
  readOnly,
}: {
  index: number
  orphan: LineComment
  onEdit?: (body: string) => void
  onDelete?: () => void
  readOnly: boolean
}) {
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(orphan.body)
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  useEffect(() => {
    if (!editing) setDraft(orphan.body)
  }, [editing, orphan.body])
  useEffect(() => {
    if (readOnly) setEditing(false)
  }, [readOnly])
  useEffect(() => {
    if (editing && textareaRef.current) {
      const element = textareaRef.current
      element.focus()
      element.style.height = 'auto'
      element.style.height = `${element.scrollHeight}px`
    }
  }, [editing])

  function handleSave() {
    const trimmed = draft.trim()
    if (trimmed) onEdit?.(trimmed)
    setEditing(false)
  }

  return (
    <li id={`orphan-comment-${index}`} className="scroll-mt-16 px-3 py-2 flex flex-col gap-1.5">
      <div className="flex items-center gap-2">
        <span className={cn('inline-flex items-center rounded border px-1.5 py-0 text-[9px] font-bold tracking-widest uppercase', ORPHAN_BADGE[orphan.type])}>
          {orphan.type}
        </span>
        <span className="font-mono text-[11px] text-muted-foreground truncate flex-1">
          {orphan.file}:{orphan.line}
        </span>
        {!editing && (onEdit || onDelete) && (
          <div className="flex items-center gap-1">
            {onEdit && (
              <Button
                variant="ghost"
                size="sm"
                className="h-6 px-1.5 text-[10px] text-muted-foreground hover:text-foreground"
                onClick={() => setEditing(true)}
                aria-label="Edit unanchored comment"
                disabled={readOnly}
              >
                Edit
              </Button>
            )}
            {onDelete && (
              <AlertDialog>
                <AlertDialogTrigger asChild>
                  <Button
                    variant="ghost"
                    size="sm"
                    className="h-6 px-1.5 text-[10px] text-muted-foreground hover:text-status-issue"
                    aria-label="Delete unanchored comment"
                    disabled={readOnly}
                  >
                    Delete
                  </Button>
                </AlertDialogTrigger>
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
            aria-label={`Edit unanchored comment on ${orphan.file}, line ${orphan.line}`}
            onChange={(event) => {
              setDraft(event.target.value)
              event.target.style.height = 'auto'
              event.target.style.height = `${event.target.scrollHeight}px`
            }}
            onKeyDown={(event) => {
              if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) handleSave()
              if (event.key === 'Escape') setEditing(false)
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
