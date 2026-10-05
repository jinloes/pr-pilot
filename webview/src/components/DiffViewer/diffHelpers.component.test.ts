import { describe, expect, it } from 'vitest'
import type { ChangeData } from 'react-diff-view'
import type { LineComment } from '@/bridge/types'
import { findByPathSuffix, groupComments, newLineOf, oldLineOf } from './diffHelpers'

const comments: LineComment[] = [
  { file: 'src/app.ts', line: 4, type: 'issue', body: 'Handle this branch.' },
  { file: 'src/app.ts', line: 4, type: 'suggestion', body: 'Simplify this.' },
  { file: 'lib/other.ts', line: 2, type: 'note', body: 'Context.' },
]

describe('diffHelpers', () => {
  it('groups comments by file and preserves their original indexes', () => {
    const grouped = groupComments(comments)

    expect(grouped.get('src/app.ts')?.get(4)).toEqual([
      { comment: comments[0], globalIdx: 0 },
      { comment: comments[1], globalIdx: 1 },
    ])
    expect(grouped.get('lib/other.ts')?.get(2)?.[0].globalIdx).toBe(2)
  })

  it('maps inserted, deleted, and unchanged rows to their line numbers', () => {
    const insert = { type: 'insert', isInsert: true, lineNumber: 8 } as ChangeData
    const deletion = { type: 'delete', isDelete: true, lineNumber: 7 } as ChangeData
    const normal = { type: 'normal', oldLineNumber: 6, newLineNumber: 9 } as ChangeData

    expect(newLineOf(insert)).toBe(8)
    expect(oldLineOf(insert)).toBeUndefined()
    expect(newLineOf(deletion)).toBeUndefined()
    expect(oldLineOf(deletion)).toBe(7)
    expect(newLineOf(normal)).toBe(9)
    expect(oldLineOf(normal)).toBe(6)
  })

  it('finds comments when a diff path and comment path use different prefixes', () => {
    const grouped = groupComments(comments)

    expect(findByPathSuffix(grouped, 'packages/reviewer/src/app.ts'))
      .toBe(grouped.get('src/app.ts'))
    expect(findByPathSuffix(grouped, 'missing.ts')).toBeUndefined()
  })
})
