import { describe, expect, it } from 'vitest'
import type { LineComment, ReviewResult } from '../../bridge/types'
import {
  diffOf,
  initialPaneState,
  normalizeReviewResult,
  resultOf,
  reviewScopeOf,
  reviewReducer,
  validationDiffOf,
  type DraftPresentState,
  type PaneState,
  type ReviewStateEvent,
} from './reviewState'

const diff = [
  'diff --git a/src/value.ts b/src/value.ts',
  '--- a/src/value.ts',
  '+++ b/src/value.ts',
  '@@ -0,0 +1 @@',
  '+const value = readValue()',
].join('\n')

const comment: LineComment = {
  file: 'src/value.ts',
  line: 1,
  type: 'issue',
  body: 'Handle this value.',
}

const result: ReviewResult = {
  summary: 'Review summary.',
  verdict: 'COMMENT',
  lineComments: [comment],
}

const draft: DraftPresentState = {
  kind: 'draftPresent',
  result,
  reviewId: 'draft-1',
  staleCommits: false,
  importedFromGitHub: false,
  diff,
  validationDiff: diff,
}

describe('reviewReducer host transitions', () => {
  it.each<{
    name: string
    state: PaneState
    event: ReviewStateEvent
    expectedKind: PaneState['kind']
  }>([
    {
      name: 'resets for a selected PR',
      state: initialPaneState,
      event: { type: 'reset', hasPr: true },
      expectedKind: 'draftLoading',
    },
    {
      name: 'returns to draft loading after cancellation',
      state: draft,
      event: { type: 'draftLoading' },
      expectedKind: 'draftLoading',
    },
    {
      name: 'loads no draft',
      state: { kind: 'draftLoading' },
      event: { type: 'draftLoaded', prState: 'NO_DRAFT', diff, validationDiff: diff },
      expectedKind: 'noDraft',
    },
    {
      name: 'loads an authentication error',
      state: { kind: 'draftLoading' },
      event: {
        type: 'draftLoaded',
        prState: 'NO_DRAFT',
        diff,
        validationDiff: diff,
        status: 'Authentication required.',
      },
      expectedKind: 'authError',
    },
    {
      name: 'loads a draft',
      state: { kind: 'draftLoading' },
      event: {
        type: 'draftLoaded',
        prState: 'DRAFT_PRESENT',
        result,
        reviewId: 'draft-1',
        diff,
        validationDiff: diff,
      },
      expectedKind: 'draftPresent',
    },
    {
      name: 'loads a merged PR',
      state: { kind: 'draftLoading' },
      event: { type: 'draftLoaded', prState: 'MERGED', diff: '', validationDiff: '' },
      expectedKind: 'merged',
    },
    {
      name: 'starts generation from a local command',
      state: { kind: 'noDraft' },
      event: { type: 'startGenerating' },
      expectedKind: 'generating',
    },
    {
      name: 'accepts a review result',
      state: {
        kind: 'generating',
        result: null,
        diff,
        validationDiff: diff,
        replacingDraft: false,
      },
      event: { type: 'reviewResult', result, diff, validationDiff: diff, generationElapsedSec: 2 },
      expectedKind: 'reviewUnsaved',
    },
    {
      name: 'records a review error',
      state: { kind: 'draftLoading' },
      event: { type: 'reviewError', message: 'Generation failed.' },
      expectedKind: 'error',
    },
    {
      name: 'acknowledges a draft save',
      state: { kind: 'reviewUnsaved', result, diff, validationDiff: diff },
      event: { type: 'draftSaved', reviewId: 'draft-2' },
      expectedKind: 'draftPresent',
    },
    {
      name: 'records a draft save error',
      state: { kind: 'reviewUnsaved', result, diff, validationDiff: diff },
      event: { type: 'saveError', message: 'Save failed.' },
      expectedKind: 'saveError',
    },
    {
      name: 'records a submit error',
      state: draft,
      event: { type: 'reviewSubmitError', message: 'Submit failed.' },
      expectedKind: 'submitError',
    },
    {
      name: 'records submission',
      state: draft,
      event: { type: 'reviewSubmitted' },
      expectedKind: 'submitted',
    },
    {
      name: 'records deletion',
      state: draft,
      event: { type: 'draftDeleted' },
      expectedKind: 'noDraft',
    },
    {
      name: 'keeps the draft with a delete error',
      state: draft,
      event: { type: 'draftDeleteError', message: 'Delete failed.', draft },
      expectedKind: 'deleteError',
    },
  ])('$name', ({ state, event, expectedKind }) => {
    expect(reviewReducer(state, event).kind).toBe(expectedKind)
  })

  it('retains inspectable diff context without provider output while generating', () => {
    expect(reviewReducer({
      kind: 'noDraft',
      diff,
      validationDiff: diff,
    }, { type: 'startGenerating' })).toEqual({
      kind: 'generating',
      result: null,
      diff,
      validationDiff: diff,
      replacingDraft: false,
      generationElapsedSec: undefined,
    })
  })

  it('retains the current draft as read-only replacement context', () => {
    expect(reviewReducer(draft, { type: 'startGenerating' })).toMatchObject({
      kind: 'generating',
      result,
      diff,
      validationDiff: diff,
      replacingDraft: true,
    })
  })

  it('retains generation context when the provider fails', () => {
    const generating = reviewReducer(draft, { type: 'startGenerating' })

    expect(reviewReducer(generating, { type: 'reviewError', message: 'Provider failed.' })).toEqual({
      kind: 'error',
      message: 'Provider failed.',
      result,
      diff,
      validationDiff: diff,
    })
  })

  it('preserves the controller-computed generation duration on the result', () => {
    expect(reviewReducer(
      {
        kind: 'generating',
        result: null,
        diff,
        validationDiff: diff,
        replacingDraft: false,
      },
      { type: 'reviewResult', result, diff, validationDiff: diff, generationElapsedSec: 2 },
    )).toMatchObject({
      kind: 'reviewUnsaved',
      generationElapsedSec: 2,
    })
  })

  it('falls back to a generic error when delete recovery has no draft', () => {
    expect(reviewReducer({ kind: 'idle' }, {
      type: 'draftDeleteError',
      message: 'Delete failed.',
      draft: null,
    })).toEqual({ kind: 'error', message: 'Delete failed.' })
  })

  it('revalidates comments when a late validation diff arrives', () => {
    const unanchored: PaneState = {
      kind: 'reviewUnsaved',
      result,
      diff: '',
      validationDiff: '',
    }

    const updated = reviewReducer(unanchored, { type: 'validationDiffUpdated', validationDiff: diff })

    expect(resultOf(updated)?.lineComments).toEqual([comment])
    expect(validationDiffOf(updated)).toBe(diff)
  })
})

describe('reviewReducer comment outcomes', () => {
  it('updates, deletes, adds, and replaces comments only in editable states', () => {
    const edited = reviewReducer(draft, {
      type: 'updateComment',
      index: 0,
      comment: { ...comment, body: 'Edited.' },
    })
    expect(resultOf(edited)?.lineComments[0].body).toBe('Edited.')

    const added = reviewReducer(edited, {
      type: 'addComment',
      comment: { ...comment, line: 2, body: 'Added.' },
    })
    expect(resultOf(added)?.lineComments).toHaveLength(2)

    const deleted = reviewReducer(added, { type: 'updateComment', index: 0, comment: null })
    expect(resultOf(deleted)?.lineComments.map((entry) => entry.body)).toEqual(['Added.'])

    const replaced = reviewReducer(deleted, {
      type: 'replaceComments',
      kinds: ['draftPresent'],
      comments: [comment],
    })
    expect(resultOf(replaced)?.lineComments).toEqual([comment])
    expect(reviewReducer({ kind: 'idle' }, {
      type: 'addComment',
      comment,
    })).toEqual({ kind: 'idle' })
  })

  it('restores a draft after delete recovery and reanchors imported metadata', () => {
    const errored = reviewReducer(draft, { type: 'draftDeleteError', message: 'Delete failed.', draft })
    expect(reviewReducer(errored, { type: 'keepDraft' })).toEqual(draft)

    const imported: DraftPresentState = { ...draft, importedFromGitHub: true }
    expect(reviewReducer(imported, { type: 'reanchorDraft' })).toMatchObject({
      kind: 'draftPresent',
      importedFromGitHub: false,
      result: normalizeReviewResult(result, diff),
    })
  })
})

describe('review state selectors', () => {
  it('reads review data through mutation error states', () => {
    const errored = reviewReducer(draft, { type: 'reviewSubmitError', message: 'Submit failed.' })

    expect(resultOf(errored)).toEqual(result)
    expect(diffOf(errored)).toBe(diff)
    expect(validationDiffOf(errored)).toBe(diff)
  })

  it('reads retained review data while generation is running', () => {
    const generating = reviewReducer(draft, { type: 'startGenerating' })

    expect(resultOf(generating)).toEqual(result)
    expect(diffOf(generating)).toBe(diff)
    expect(validationDiffOf(generating)).toBe(diff)
  })
})

describe('review scope', () => {
  const scope = { kind: 'incremental', baselineSha: 'a'.repeat(40) } as const
  const generating: PaneState = { kind: 'generating', result: null, diff, validationDiff: diff, replacingDraft: false }

  it('stores the scope with an incremental result', () => {
    const next = reviewReducer(generating, { type: 'reviewResult', result, diff, validationDiff: diff, reviewScope: scope })
    expect(reviewScopeOf(next)).toEqual(scope)
  })

  it('leaves the scope unset for an ordinary result', () => {
    const next = reviewReducer(generating, { type: 'reviewResult', result, diff, validationDiff: diff })
    expect(next).not.toHaveProperty('reviewScope')
  })

  it('keeps the scope when the draft is saved', () => {
    const unsaved = reviewReducer(generating, { type: 'reviewResult', result, diff, validationDiff: diff, reviewScope: scope })
    expect(reviewScopeOf(reviewReducer(unsaved, { type: 'draftSaved', reviewId: 'draft-1' }))).toEqual(scope)
  })

  it('clears the scope when a new generation starts', () => {
    const unsaved = reviewReducer(generating, { type: 'reviewResult', result, diff, validationDiff: diff, reviewScope: scope })
    const next = reviewReducer(unsaved, { type: 'startGenerating' })
    expect(reviewScopeOf(next)).toBeUndefined()
    const done = reviewReducer(next, { type: 'reviewResult', result, diff, validationDiff: diff })
    expect(reviewScopeOf(done)).toBeUndefined()
  })

  it('clears the scope when the PR changes', () => {
    const unsaved = reviewReducer(generating, { type: 'reviewResult', result, diff, validationDiff: diff, reviewScope: scope })
    expect(reviewScopeOf(reviewReducer(unsaved, { type: 'reset', hasPr: true }))).toBeUndefined()
  })
})
