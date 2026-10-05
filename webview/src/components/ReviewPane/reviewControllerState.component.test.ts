import { describe, expect, it } from 'vitest'
import type { PR, ReviewResult } from '../../bridge/types'
import {
  chatContextSummary,
  chunkRecommendation,
  summarizeDiffPreflight,
} from './reviewControllerState'

const pr: PR = {
  number: 7,
  title: 'Improve review',
  owner: 'acme',
  repo: 'widget',
  author: 'octocat',
  createdAt: '2026-01-01T00:00:00Z',
  htmlUrl: 'https://github.com/acme/widget/pull/7',
  isDraft: false,
  hasReviewDraft: false,
  reviewStatus: 'UNREVIEWED',
}

const review: ReviewResult = {
  summary: 'Summary',
  verdict: 'COMMENT',
  lineComments: [],
}

describe('reviewControllerState helpers', () => {
  it('summarizes changed files and lines from a unified diff', () => {
    expect(summarizeDiffPreflight([
      '--- a/one.ts',
      '+++ b/one.ts',
      '+added',
      '-removed',
      ' context',
      '+++ b/two.ts',
      '+another',
    ].join('\n'))).toEqual({ fileCount: 2, changedLines: 3 })
    expect(summarizeDiffPreflight('')).toBeNull()
  })

  it('prefers actual chunk coverage gains over size heuristics', () => {
    const preflight = { fileCount: 1, changedLines: 2 }
    expect(chunkRecommendation(preflight, {
      omitted: 1,
      listed: 1,
      budget: 100,
      scanComplete: false,
      paths: ['two.ts'],
    }, {
      omitted: 0,
      listed: 0,
      budget: 200,
      scanComplete: true,
      paths: [],
    })).toMatchObject({ recommendChunked: true })
  })

  it('summarizes the context available to chat', () => {
    expect(chatContextSummary(pr, 'diff', null, review, 'selected'))
      .toEqual(['PR title/body', 'diff', 'generated review', 'selected text'])
    expect(chatContextSummary(null, 'diff', null, review, 'selected')).toEqual([])
  })
})
