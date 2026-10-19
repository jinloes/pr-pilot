import type { LineComment } from '../../bridge/types'
import type { Verdict } from './reviewState'

/**
 * Canned review bodies the engine publishes only when both the reviewer's text and the draft's
 * visible sections are empty. Mirrors `DraftReviewMutationService.effectiveBody`; tests on both
 * sides pin these strings.
 */
export const FALLBACK_REVIEW_BODY: Record<Verdict, string> = {
  APPROVE: 'Looks good to me!',
  REQUEST_CHANGES: 'Requesting changes.',
  COMMENT: 'Leaving comments.',
}

/**
 * The review body the publish dialog starts with. It is a short count rather than the generated
 * summary, which stays in the pane for the reviewer. With no inline comments it is empty, so the
 * verdict's {@link FALLBACK_REVIEW_BODY} is published instead.
 */
export function defaultReviewBody(inlineCount: number): string {
  if (inlineCount <= 0) return ''
  return inlineCount === 1 ? 'Left 1 comment.' : `Left ${inlineCount} comments.`
}

export interface UnanchoredSection {
  file: string
  line: number
  body: string
}

/** What the engine appends below the reviewer's text when a draft is published. */
export interface PublishedBodySections {
  /** General Notes: comments with no file or no positive line, as `DraftReviewCodec.encodeBody` selects them. */
  generalNotes: string[]
  /** Comments not attached inline, in the order the draft stores them. */
  unanchored: UnanchoredSection[]
  /** Comments GitHub receives as inline review comments. */
  inlineCount: number
}

function isBlank(value: string | null | undefined): boolean {
  return !value || value.trim().length === 0
}

function commentKey(comment: LineComment): string {
  return `${comment.file}\u0000${comment.line}\u0000${comment.type}\u0000${comment.body}`
}

export function publishedBodySections(
  lineComments: LineComment[],
  orphans: LineComment[],
): PublishedBodySections {
  const orphanKeys = new Set(orphans.map(commentKey))
  const generalNotes = lineComments
    .filter((comment) => isBlank(comment.file) || comment.line <= 0)
    .map((comment) => comment.body)
  const inlineCount = lineComments.filter((comment) =>
    !isBlank(comment.file)
    && comment.line > 0
    && !isBlank(comment.body)
    && !orphans.includes(comment)
    && !orphanKeys.has(commentKey(comment))).length
  return {
    generalNotes,
    unanchored: orphans.map(({ file, line, body }) => ({ file, line, body })),
    inlineCount,
  }
}
