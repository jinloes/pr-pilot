import { act } from '@testing-library/react'
import { vi } from 'vitest'
import type { PR } from '../../bridge/types'

export const pr: PR = {
  number: 42,
  title: 'Avoid duplicate approvals',
  owner: 'acme',
  repo: 'widget',
  author: 'octocat',
  createdAt: '2026-07-29T00:00:00Z',
  htmlUrl: 'https://github.com/acme/widget/pull/42',
  isDraft: false,
  hasReviewDraft: true,
  reviewStatus: 'UNREVIEWED',
}

export function diffWithFiles(count: number): string {
  return Array.from({ length: count }, (_, index) => [
    `diff --git a/src/file-${index}.ts b/src/file-${index}.ts`,
    `--- a/src/file-${index}.ts`,
    `+++ b/src/file-${index}.ts`,
    '@@ -1 +1 @@',
    '-old',
    '+new',
  ].join('\n')).join('\n')
}

export function coverageTrailer(omitted: number, paths: string[], scan: 'complete' | 'incomplete' = 'complete'): string {
  return `[pr-pilot:diff-coverage] omitted=${omitted} listed=${paths.length} budget=250000 scan=${scan}\n`
    + paths.map((path) => `[pr-pilot:omitted] ${path}\n`).join('')
}

export function hostMessage(message: object) {
  const handler = (window as unknown as { __handleMessage?: (payload: object) => void }).__handleMessage
  if (!handler) throw new Error('ReviewPane did not register the JCEF bridge handler')
  handler({ protocolVersion: 1, ...message })
}

export function outgoingOf(cefQuery: ReturnType<typeof vi.fn>, type: string) {
  return cefQuery.mock.calls
    .map(([arg]) => JSON.parse((arg as { request: string }).request) as Record<string, unknown>)
    .filter((message) => message.type === type)
}

export function installHost() {
  const cefQuery = vi.fn()
  ;(window as unknown as { cefQuery?: typeof cefQuery }).cefQuery = cefQuery
  return cefQuery
}

export function loadNoDraft(diff: string, validationDiff = diff, available = true) {
  act(() => {
    hostMessage({
      type: 'draftLoaded',
      prKey: 'acme/widget#42',
      prState: 'NO_DRAFT',
      diff,
      validationDiff,
      providerReadiness: available
        ? { provider: 'claude', available: true, detail: 'Ready.' }
        : { provider: 'claude', available: false, detail: 'Claude CLI was not found.' },
    })
  })
}

export function loadDraftPresent(result: object, extra: object = {}) {
  act(() => {
    hostMessage({
      type: 'draftLoaded',
      prKey: 'acme/widget#42',
      prState: 'DRAFT_PRESENT',
      reviewId: 'draft-1',
      result,
      diff: '',
      validationDiff: '',
      ...extra,
    })
  })
}
