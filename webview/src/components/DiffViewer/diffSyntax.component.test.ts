import { describe, expect, it } from 'vitest'
import { escapeHtml, syntaxHighlight } from './diffSyntax'

describe('diffSyntax', () => {
  it('escapes unsupported file contents without changing text', () => {
    expect(syntaxHighlight('<tag>', 'README')).toBe('&lt;tag&gt;')
    expect(escapeHtml('a&<b>')).toBe('a&amp;&lt;b&gt;')
  })

  it('uses highlight.js for recognized extensions', () => {
    expect(syntaxHighlight('const answer = 42', 'answer.ts'))
      .toContain('hljs-keyword')
  })
})
