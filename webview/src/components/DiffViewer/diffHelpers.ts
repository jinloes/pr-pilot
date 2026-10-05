import { isDelete, isInsert } from 'react-diff-view'
import type { ChangeData } from 'react-diff-view'
import type { LineComment } from '@/bridge/types'
import { AUTOSAVE_DEBOUNCE_MS } from '@/lib/autosave'

export type IndexedComment = { comment: LineComment; globalIdx: number }
export type LineCommentMap = Map<number, IndexedComment[]>
export type FileCommentMap = Map<string, LineCommentMap>

export interface PendingNew {
  file: string
  line: number
  rowId: string
}

/** Deletions persist through draft autosave, so the copy follows its debounce. */
export const DELETE_COMMENT_DESCRIPTION = `This comment will be removed from your pending GitHub review at the next `
  + `autosave (within ${AUTOSAVE_DEBOUNCE_MS / 1000} seconds). Use Save now to update GitHub immediately.`

export function findingToneClass(comment: LineComment): string {
  if (comment.severity === 'blocker' || comment.severity === 'major' || comment.type === 'issue') {
    return 'text-status-issue'
  }
  if (comment.severity === 'minor' || comment.type === 'suggestion') return 'text-status-suggestion'
  return 'text-status-note'
}

export function groupComments(comments: LineComment[]): FileCommentMap {
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

export function newLineOf(change: ChangeData): number | undefined {
  if (isInsert(change)) return change.lineNumber
  if (isDelete(change)) return undefined
  return change.newLineNumber
}

export function oldLineOf(change: ChangeData): number | undefined {
  if (isDelete(change)) return change.lineNumber
  if (isInsert(change)) return undefined
  return change.oldLineNumber
}

export function findByPathSuffix(map: FileCommentMap, path: string): LineCommentMap | undefined {
  for (const [key, val] of map) {
    if (path.endsWith(key) || key.endsWith(path)) return val
  }
  return undefined
}
