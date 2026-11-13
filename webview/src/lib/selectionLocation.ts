import type { ChatContextLocation } from '../bridge/types'

interface LineMark {
  line: number
  oldSide: boolean
}

function elementOf(node: Node | null): Element | null {
  if (!node) return null
  return node.nodeType === Node.ELEMENT_NODE ? (node as Element) : node.parentElement
}

function lineMark(element: Element | null): LineMark | null {
  const row = element?.closest('[data-line]')
  const line = Number(row?.getAttribute('data-line'))
  if (!row || !Number.isInteger(line) || line <= 0) return null
  return { line, oldSide: row.getAttribute('data-line-side') === 'old' }
}

/**
 * Where a selection in the diff came from: the file whose section contains both ends, and the
 * line range when both ends sit on numbered rows of the same side. Returns undefined for
 * selections outside a file section or spanning two files.
 */
export function selectionLocation(selection: Selection | null): ChatContextLocation | undefined {
  if (!selection || selection.rangeCount === 0) return undefined
  const range = selection.getRangeAt(0)
  const start = elementOf(range.startContainer)
  const end = elementOf(range.endContainer)
  const section = start?.closest('[data-file-path]')
  if (!section || section !== end?.closest('[data-file-path]')) return undefined
  const file = section.getAttribute('data-file-path')?.trim()
  if (!file) return undefined

  const marks = [lineMark(start), lineMark(end)].filter((mark): mark is LineMark => mark !== null)
  if (marks.length === 0) return { file }
  // A range crossing added and deleted rows has no single numbering; keep the post-change side.
  const sameSide = marks.every((mark) => mark.oldSide === marks[0].oldSide)
  const used = sameSide ? marks : marks.filter((mark) => !mark.oldSide)
  const lines = used.map((mark) => mark.line)
  const location: ChatContextLocation = {
    file,
    startLine: Math.min(...lines),
    endLine: Math.max(...lines),
  }
  if (used[0].oldSide) location.oldSide = true
  return location
}

/** Location as `path:start-end`, `path:line`, or `path`. */
export function formatLocation(location: ChatContextLocation): string {
  const { file, startLine, endLine } = location
  if (!startLine) return file
  if (!endLine || endLine <= startLine) return `${file}:${startLine}`
  return `${file}:${startLine}-${endLine}`
}
