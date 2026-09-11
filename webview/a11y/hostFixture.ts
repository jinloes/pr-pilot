import type { Page } from '@playwright/test'

export const protocolVersion = 1

export async function installHostFixture(page: Page): Promise<void> {
  await page.addInitScript(() => {
    const outgoing: unknown[] = []
    const fixture = {
      outgoing,
      push(message: unknown) {
        window.dispatchEvent(new MessageEvent('message', { data: message }))
      },
    }
    Object.assign(window, {
      __hostFixture: fixture,
      acquireVsCodeApi: () => ({
        postMessage(message: unknown) { outgoing.push(message) },
        getState: () => undefined,
        setState: () => undefined,
      }),
    })
  })
}

export async function pushHostMessage(page: Page, message: Record<string, unknown>): Promise<void> {
  await page.evaluate((payload) => {
    const fixture = (window as unknown as { __hostFixture: { push: (message: unknown) => void } }).__hostFixture
    fixture.push(payload)
  }, { protocolVersion, ...message })
}

export async function latestHostRequest(page: Page, type: string): Promise<Record<string, unknown>> {
  return page.evaluate((requestType) => {
    const { outgoing } = (window as unknown as {
      __hostFixture: { outgoing: Record<string, unknown>[] }
    }).__hostFixture
    const requests = outgoing.filter(m => m.type === requestType)
    if (!requests.length) throw new Error(`Missing host request: ${requestType}`)
    return requests[requests.length - 1]
  }, type)
}

export const deepPreparation = {
  type: 'deepReviewPrepared', prKey: 'acme/platform#42',
  retainedId: '11111111-1111-4111-8111-111111111111', worktree: '/fixture/deep',
  head: 'a'.repeat(40), servers: ['private'], message: 'Manual sync required',
}

export const examplePr = {
  number: 42,
  title: 'Improve authentication error recovery and keyboard navigation',
  owner: 'acme',
  repo: 'platform',
  author: 'reviewer',
  createdAt: '2026-07-10T10:00:00Z',
  htmlUrl: 'https://github.com/acme/platform/pull/42',
  isDraft: false,
  hasReviewDraft: false,
  reviewStatus: 'REVIEWED',
}

export const exampleDiff = `diff --git a/src/auth.ts b/src/auth.ts
--- a/src/auth.ts
+++ b/src/auth.ts
@@ -1,1 +1,2 @@
 export const ready = true
+export const accessible = true
`
