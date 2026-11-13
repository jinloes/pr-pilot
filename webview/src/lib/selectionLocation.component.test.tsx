import { afterEach, describe, expect, it } from 'vitest'
import { formatLocation, selectionLocation } from './selectionLocation'

function diffMarkup(): string {
  return `
    <section data-file-path="src/A.ts">
      <table><tbody>
        <tr data-line="10" data-line-side="new"><td id="a10">added ten</td></tr>
        <tr data-line="11" data-line-side="new"><td id="a11">added eleven</td></tr>
        <tr data-line="4" data-line-side="old"><td id="a-old4">deleted four</td></tr>
        <tr data-line="5" data-line-side="old"><td id="a-old5">deleted five</td></tr>
        <tr><td id="a-header">@@ hunk @@</td></tr>
      </tbody></table>
    </section>
    <section data-file-path="src/B.ts">
      <table><tbody><tr data-line="2" data-line-side="new"><td id="b2">b two</td></tr></tbody></table>
    </section>
    <p id="outside">summary</p>`
}

function select(startId: string, endId: string): Selection {
  const start = document.getElementById(startId)!.firstChild!
  const end = document.getElementById(endId)!.firstChild!
  const range = document.createRange()
  range.setStart(start, 0)
  range.setEnd(end, (end.textContent ?? '').length)
  const selection = window.getSelection()!
  selection.removeAllRanges()
  selection.addRange(range)
  return selection
}

describe('selectionLocation', () => {
  afterEach(() => {
    window.getSelection()?.removeAllRanges()
    document.body.innerHTML = ''
  })

  it('returns undefined without a selection', () => {
    expect(selectionLocation(null)).toBeUndefined()
    window.getSelection()!.removeAllRanges()
    expect(selectionLocation(window.getSelection())).toBeUndefined()
  })

  it('locates a single added line', () => {
    document.body.innerHTML = diffMarkup()
    expect(selectionLocation(select('a10', 'a10'))).toEqual({ file: 'src/A.ts', startLine: 10, endLine: 10 })
  })

  it('locates a range of added lines', () => {
    document.body.innerHTML = diffMarkup()
    expect(selectionLocation(select('a10', 'a11'))).toEqual({ file: 'src/A.ts', startLine: 10, endLine: 11 })
  })

  it('marks a range of deleted lines as pre-change lines', () => {
    document.body.innerHTML = diffMarkup()
    expect(selectionLocation(select('a-old4', 'a-old5')))
      .toEqual({ file: 'src/A.ts', startLine: 4, endLine: 5, oldSide: true })
  })

  it('keeps the post-change line when a range crosses added and deleted rows', () => {
    document.body.innerHTML = diffMarkup()
    expect(selectionLocation(select('a11', 'a-old5'))).toEqual({ file: 'src/A.ts', startLine: 11, endLine: 11 })
  })

  it('returns only the file when neither end is on a numbered row', () => {
    document.body.innerHTML = diffMarkup()
    expect(selectionLocation(select('a-header', 'a-header'))).toEqual({ file: 'src/A.ts' })
  })

  it('returns undefined across two files or outside the diff', () => {
    document.body.innerHTML = diffMarkup()
    expect(selectionLocation(select('a10', 'b2'))).toBeUndefined()
    expect(selectionLocation(select('outside', 'outside'))).toBeUndefined()
  })
})

describe('formatLocation', () => {
  it('formats a file, a line, and a range', () => {
    expect(formatLocation({ file: 'A' })).toBe('A')
    expect(formatLocation({ file: 'A', startLine: 3 })).toBe('A:3')
    expect(formatLocation({ file: 'A', startLine: 3, endLine: 3 })).toBe('A:3')
    expect(formatLocation({ file: 'A', startLine: 3, endLine: 5 })).toBe('A:3-5')
  })
})
