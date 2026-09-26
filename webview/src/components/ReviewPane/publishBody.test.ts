import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import type { LineComment } from '../../bridge/types'
import { FALLBACK_REVIEW_BODY, publishedBodySections } from './publishBody'

function comment(file: string, line: number, body: string): LineComment {
  return { file, line, type: 'note', body }
}

describe('publishedBodySections', () => {
  it('selects general notes the way the engine encoder does', () => {
    const sections = publishedBodySections([
      comment('', 3, 'No file.'),
      comment('src/a.ts', 0, 'No line.'),
      comment('  ', 2, 'Blank file.'),
      comment('src/a.ts', 4, 'Inline.'),
    ], [])

    expect(sections.generalNotes).toEqual(['No file.', 'No line.', 'Blank file.'])
  })

  it('lists unanchored comments in order with their location', () => {
    const orphan = comment('src/missing.ts', 99, 'Detached.')
    const sections = publishedBodySections([comment('src/a.ts', 1, 'Inline.'), orphan], [orphan])

    expect(sections.unanchored).toEqual([{ file: 'src/missing.ts', line: 99, body: 'Detached.' }])
  })

  it('counts only anchored, non-blank comments that are not unanchored as inline', () => {
    const orphan = comment('src/missing.ts', 99, 'Detached.')
    const sections = publishedBodySections([
      comment('src/a.ts', 1, 'Inline one.'),
      comment('src/b.ts', 2, 'Inline two.'),
      comment('src/b.ts', 3, '   '),
      comment('', 1, 'General.'),
      orphan,
      { ...orphan },
    ], [orphan])

    expect(sections.inlineCount).toBe(2)
  })

  it('mirrors the engine fallback bodies exactly', () => {
    // Tests run from the webview package root.
    const engine = readFileSync(resolve(
      process.cwd(),
      '../github-engine/src/main/java/com/jinloes/prpilot/sidecar/pr/DraftReviewMutationService.java',
    ), 'utf8')

    expect(FALLBACK_REVIEW_BODY).toEqual({
      APPROVE: 'Looks good to me!',
      REQUEST_CHANGES: 'Requesting changes.',
      COMMENT: 'Leaving comments.',
    })
    for (const [event, text] of Object.entries(FALLBACK_REVIEW_BODY)) {
      expect(engine).toContain(`case "${event}" -> "${text}";`)
    }
  })
})
