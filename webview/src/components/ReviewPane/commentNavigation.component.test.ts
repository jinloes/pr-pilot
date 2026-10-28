import { describe, expect, it } from 'vitest'
import { adjacentCommentIndex, focusedIndexAfterCommentDeletion, nextCommentIndex } from './commentNavigation'

describe('adjacentCommentIndex', () => {
  it('wraps previous navigation from the first comment to the last', () => {
    expect(adjacentCommentIndex(0, -1, 3)).toBe(2)
  })

  it('wraps next navigation from the last comment to the first', () => {
    expect(adjacentCommentIndex(2, 1, 3)).toBe(0)
  })

  it('keeps a lone comment selected in both directions', () => {
    expect(adjacentCommentIndex(0, -1, 1)).toBe(0)
    expect(adjacentCommentIndex(0, 1, 1)).toBe(0)
  })
})

describe('focusedIndexAfterCommentDeletion', () => {
  it('selects the next comment when the focused comment is removed before the end', () => {
    expect(focusedIndexAfterCommentDeletion(1, 1, 3)).toBe(1)
  })

  it('selects the previous comment when the last focused comment is removed', () => {
    expect(focusedIndexAfterCommentDeletion(2, 2, 3)).toBe(1)
  })

  it('keeps the same logical comment focused when an earlier comment is removed', () => {
    expect(focusedIndexAfterCommentDeletion(2, 0, 3)).toBe(1)
  })

  it('keeps the focused comment when a later comment is removed', () => {
    expect(focusedIndexAfterCommentDeletion(0, 2, 3)).toBe(0)
  })

  it('returns the empty-state index when the only comment is removed', () => {
    expect(focusedIndexAfterCommentDeletion(0, 0, 1)).toBe(0)
  })
})

describe('nextCommentIndex', () => {
  it('lands on the focused comment before the reviewer has navigated', () => {
    expect(nextCommentIndex(0, 3, false)).toBe(0)
  })

  it('advances once the reviewer has navigated', () => {
    expect(nextCommentIndex(0, 3, true)).toBe(1)
    expect(nextCommentIndex(2, 3, true)).toBe(0)
  })

  it('clamps a stale focused index before the reviewer has navigated', () => {
    expect(nextCommentIndex(5, 3, false)).toBe(2)
    expect(nextCommentIndex(-1, 3, false)).toBe(0)
  })

  it('returns zero when there are no comments', () => {
    expect(nextCommentIndex(0, 0, false)).toBe(0)
    expect(nextCommentIndex(0, 0, true)).toBe(0)
  })
})
