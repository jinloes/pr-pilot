import { test, expect, type Page } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { deepPreparation, exampleDiff, examplePr, installHostFixture, latestHostRequest, pushHostMessage } from './hostFixture'

async function expectNoViolations(page: import('@playwright/test').Page, include?: string) {
  const builder = new AxeBuilder({ page })
  const results = await (include ? builder.include(include) : builder).analyze()
  expect(results.violations, results.violations.map((v) => `${v.id} (${v.impact}): ${v.description} [${v.nodes.length}]`).join('\n')).toEqual([])
}

async function openGeneratingReview(page: Page) {
  await pushHostMessage(page, { type: 'prListLoaded', prs: [examplePr] })
  await page.getByRole('button', { name: /Improve authentication/ }).click()
  await pushHostMessage(page, {
    type: 'draftLoaded',
    prKey: 'acme/platform#42',
    prState: 'NO_DRAFT',
    diff: exampleDiff,
    validationDiff: exampleDiff,
    providerReadiness: { provider: 'claude', available: true, detail: 'Ready' },
  })
  await page.getByRole('button', { name: 'Generate Review' }).click()
}

test.beforeEach(async ({ page }) => {
  await installHostFixture(page)
  await page.goto('/')
  await page.waitForLoadState('networkidle')
})

test('deep setup supports keyboard Continue, Retry, explicit fallback and safe no-PR cleanup', async ({ page }) => {
  await page.setViewportSize({ width: 380, height: 900 })
  await pushHostMessage(page, { type: 'prListLoaded', prs: [] })
  await page.getByRole('button', { name: 'Show review', exact: true }).click()
  await page.getByText('Retained IntelliJ review worktrees').press('Enter')
  await page.getByRole('button', { name: 'Refresh retained worktrees' }).press('Enter')
  const list = await latestHostRequest(page, 'listDeepReviews')
  await pushHostMessage(page, { type: 'retainedDeepReviews', operationId: list.operationId,
    retained: [{ id: deepPreparation.retainedId, worktree: deepPreparation.worktree,
      repository: '/fixture', head: deepPreparation.head, createdAt: 1 }] })
  await expect(page.getByRole('button', { name: 'Remove retained worktree' })).toBeDisabled()
  await page.getByRole('checkbox', { name: 'I closed this exact project in every IDE' }).press('Space')
  await page.getByRole('button', { name: 'Remove retained worktree' }).press('Enter')
  const cleanup = await latestHostRequest(page, 'cleanupDeepReview')
  expect(cleanup.projectClosed).toBe(true)
  await pushHostMessage(page, { type: 'retainedDeepReviews', operationId: cleanup.operationId, retained: [] })
  await page.getByRole('button', { name: 'Show pull requests' }).click()
  await pushHostMessage(page, { type: 'prListLoaded', prs: [examplePr] })
  await page.getByRole('button', { name: /Improve authentication/ }).click()
  await pushHostMessage(page, { type: 'draftLoaded', prKey: 'acme/platform#42', prState: 'NO_DRAFT',
    diff: exampleDiff, providerReadiness: { provider: 'claude', available: true, detail: 'Ready' } })
  await page.locator('summary').filter({ hasText: 'Review instructions (optional)' }).press('Enter')
  await page.locator('summary').filter({ hasText: 'Advanced review options' }).press('Enter')
  const toggle = page.getByRole('checkbox', { name: /IntelliJ-assisted/ })
  await expect(toggle).not.toBeChecked()
  await toggle.press('Space')
  await page.getByRole('button', { name: 'Generate Review' }).press('Enter')
  const prepare = await latestHostRequest(page, 'generateReview')
  expect(prepare.intellijAssisted).toBe(true)
  await pushHostMessage(page, { ...deepPreparation, operationId: prepare.operationId })
  await expect(page.getByRole('combobox', { name: 'Configured MCP server' })).toBeVisible()
  await expectNoViolations(page, 'section[aria-label="IntelliJ-assisted review setup"]')
  await page.getByRole('button', { name: 'Continue / Retry' }).press('Enter')
  const first = await latestHostRequest(page, 'continueDeepReview')
  expect(first.server).toBe('private')
  await pushHostMessage(page, { ...deepPreparation, operationId: first.operationId })
  await page.getByRole('button', { name: 'Continue / Retry' }).press('Enter')
  const retry = await latestHostRequest(page, 'continueDeepReview')
  expect(retry.operationId).not.toBe(first.operationId)
  await pushHostMessage(page, { ...deepPreparation, operationId: retry.operationId })
  await page.getByRole('button', { name: 'Use ordinary review instead' }).press('Enter')
  expect((await latestHostRequest(page, 'generateReview')).intellijAssisted).toBeUndefined()
})
test('populated discovery and provider-ready review have no axe violations', async ({ page }) => {
  await pushHostMessage(page, { type: 'prListLoaded', prs: [examplePr] })
  await expectNoViolations(page)
  await page.getByRole('button', { name: /Improve authentication/ }).click()
  await pushHostMessage(page, {
    type: 'draftLoaded', prKey: 'acme/platform#42', prState: 'NO_DRAFT', diff: exampleDiff,
    providerReadiness: { provider: 'claude', available: true, detail: 'Ready' },
  })
  await expectNoViolations(page)
})

test('narrow discovery exceptions and simultaneous statuses have no axe violations', async ({ page }) => {
  await page.setViewportSize({ width: 220, height: 720 })
  await page.evaluate(() => localStorage.setItem('pr-pilot:first-success-coach-shown', '1'))
  await pushHostMessage(page, { type: 'themeChanged', theme: 'highContrastDark' })
  await pushHostMessage(page, {
    type: 'prListLoaded',
    prs: [{
      ...examplePr,
      title: 'A long localized title that changed after review',
      repo: 'a-very-long-repository-name',
      author: 'a-very-long-reviewer-name',
      isDraft: true,
      hasReviewDraft: true,
      reviewStatus: 'UPDATED_SINCE_REVIEW',
    }],
    listStatus: {
      searchScope: 'currentRepo',
      resultLimit: 50,
      limited: true,
      reviewStatusAvailable: false,
    },
  })
  await pushHostMessage(page, {
    type: 'activatePR',
    source: 'notification',
    pr: { ...examplePr, reviewStatus: 'UNAVAILABLE' },
  })
  await page.getByRole('button', { name: 'Show pull requests' }).click()

  const nav = page.getByRole('navigation', { name: 'Pull Requests' })
  const geometry = await nav.evaluate((element) => ({
    clientWidth: element.clientWidth,
    scrollWidth: element.scrollWidth,
  }))
  expect(geometry.scrollWidth).toBeLessThanOrEqual(geometry.clientWidth)
  await expect(page.getByText('Updated since your review')).toBeVisible()
  await expect(page.getByText('From notification')).toBeVisible()
  await expectNoViolations(page, 'nav')
})

test('full-workspace setup has no axe violations', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 568 })
  await pushHostMessage(page, { type: 'themeChanged', theme: 'dark' })
  await pushHostMessage(page, {
    type: 'setupRequired',
    reason: 'gh_not_authenticated',
    detail: 'Authenticate GitHub CLI.',
    providerReadiness: {
      provider: 'claude',
      available: false,
      detail: 'Claude Code authentication must be repaired before reviews can run.',
      authCommand: 'claude auth login',
      authenticationStatus: 'unavailable',
    },
  })
  const main = page.getByRole('main', { name: 'PR Pilot setup' })
  await expect(main).toBeVisible()
  const horizontalGeometry = await main.evaluate((element) => ({
    clientWidth: element.clientWidth,
    scrollWidth: element.scrollWidth,
  }))
  expect(horizontalGeometry.scrollWidth).toBeLessThanOrEqual(horizontalGeometry.clientWidth)
  await expectNoViolations(page)
})

test('longest risky submit dialog is accessible, viewport-safe, and requires acknowledgement', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 568 })
  await pushHostMessage(page, { type: 'prListLoaded', prs: [examplePr] })
  await page.getByRole('button', { name: /Improve authentication/ }).click()
  await pushHostMessage(page, {
    type: 'draftLoaded',
    prKey: 'acme/platform#42',
    prState: 'DRAFT_PRESENT',
    reviewId: 'draft-1',
    diff: exampleDiff,
    result: {
      summary: 'Authentication review\n\nConfirm each unresolved trust concern before publishing this deliberately long summary.',
      verdict: 'COMMENT',
      lineComments: [
        {
          file: 'src/auth.ts',
          line: 2,
          type: 'issue',
          body: 'Authentication may be bypassed.',
          severity: 'major',
          confidence: 'low',
          rationale: 'The model marked this claim as low confidence.',
        },
        {
          file: 'src/auth.ts',
          line: 1,
          type: 'issue',
          body: 'Document the trust boundary.',
          severity: 'minor',
          confidence: 'high',
        },
        {
          file: 'src/auth.ts',
          line: 999,
          type: 'note',
          body: 'This anchor no longer exists.',
          severity: 'minor',
          confidence: 'high',
          rationale: 'The target line is outside every current hunk.',
        },
      ],
    },
  })
  await page.getByRole('button', { name: 'Comment', exact: true }).click()
  const dialog = page.getByRole('alertdialog')
  const submit = page.getByRole('button', { name: 'Submit Comment' })
  await expect(dialog).toContainText('3 unresolved trust risks')
  await expect(submit).toBeDisabled()
  const box = await dialog.boundingBox()
  expect(box).not.toBeNull()
  expect(box!.x).toBeGreaterThanOrEqual(16)
  expect(box!.y).toBeGreaterThanOrEqual(16)
  expect(box!.x + box!.width).toBeLessThanOrEqual(304)
  expect(box!.y + box!.height).toBeLessThanOrEqual(552)
  await expectNoViolations(page, '[role="alertdialog"]')
  await page.getByRole('checkbox', { name: /I reviewed these unresolved trust risks/ }).check()
  await expect(submit).toBeEnabled()
})

test('dark and high-contrast primary controls have no axe violations', async ({ page }) => {
  await page.setViewportSize({ width: 400, height: 600 })
  for (const theme of ['dark', 'highContrastDark', 'highContrastLight'] as const) {
    await pushHostMessage(page, { type: 'themeChanged', theme })
    await pushHostMessage(page, { type: 'prListLoaded', prs: [examplePr] })
    await page.getByRole('button', { name: /Improve authentication/ }).click()
    await pushHostMessage(page, {
      type: 'draftLoaded',
      prKey: 'acme/platform#42',
      prState: 'NO_DRAFT',
      diff: exampleDiff,
      validationDiff: exampleDiff,
      providerReadiness: { provider: 'claude', available: true, detail: 'Ready' },
    })

    await expect(page.getByRole('button', { name: 'Generate Review' })).toBeVisible()
    await page.waitForTimeout(200)
    await expectNoViolations(page)
    await page.getByRole('button', { name: 'Show pull requests' }).click()
  }
})

test('generation, long activity, reduced motion, and failure recovery are accessible', async ({ page }) => {
  await page.setViewportSize({ width: 400, height: 600 })
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await pushHostMessage(page, { type: 'themeChanged', theme: 'dark' })
  await openGeneratingReview(page)

  await pushHostMessage(page, {
    type: 'reviewChunk',
    prKey: 'acme/platform#42',
    kind: 'thinking',
    chunk: 'PRIVATE_PROVIDER_REASONING_SENTINEL',
  })
  await pushHostMessage(page, {
    type: 'reviewChunk',
    prKey: 'acme/platform#42',
    kind: 'text',
    chunk: 'RAW_PROVIDER_TEXT_SENTINEL',
  })
  for (let index = 0; index < 16; index += 1) {
    await pushHostMessage(page, {
      type: 'reviewGenerating',
      prKey: 'acme/platform#42',
      message: `tool_${index}`,
    })
  }

  const activity = page.getByRole('region', { name: 'Review generation activity' })
  await activity.getByRole('button', { name: 'Show details for Generating review' }).click()
  const entries = activity.getByRole('region', { name: 'Review activity entries' })
  await expect(page.getByText('PRIVATE_PROVIDER_REASONING_SENTINEL')).toHaveCount(0)
  await expect(page.getByText('RAW_PROVIDER_TEXT_SENTINEL')).toHaveCount(0)
  await expect(activity.getByRole('button', { name: 'Stop generation' })).toBeVisible()
  await expect(activity.getByRole('progressbar', { name: 'Review generation progress' })).toBeVisible()
  expect(await entries.evaluate((element) => element.scrollHeight > element.clientHeight)).toBe(true)

  await entries.focus()
  await expect(entries).toBeFocused()
  await page.keyboard.press('Home')
  await expect.poll(() => entries.evaluate((element) => element.scrollTop)).toBe(0)
  await page.keyboard.press('ArrowDown')
  await expect.poll(() => entries.evaluate((element) => element.scrollTop)).toBeGreaterThan(0)
  await page.keyboard.press('Home')
  await expect.poll(() => entries.evaluate((element) => element.scrollTop)).toBe(0)
  await pushHostMessage(page, {
    type: 'reviewGenerating',
    prKey: 'acme/platform#42',
    message: 'tool_after_keyboard_scroll',
  })
  await expect.poll(() => entries.evaluate((element) => element.scrollTop)).toBe(0)
  await expectNoViolations(page)

  await pushHostMessage(page, { type: 'themeChanged', theme: 'highContrastDark' })
  await expectNoViolations(page)

  await pushHostMessage(page, {
    type: 'reviewError',
    prKey: 'acme/platform#42',
    message: 'Provider failed. Check credentials and retry.',
  })
  const alert = page.getByRole('alert')
  const instructions = page.getByTestId('review-overrides-disclosure')
  const [alertBox, activityBox, instructionsBox] = await Promise.all([
    alert.boundingBox(),
    activity.boundingBox(),
    instructions.boundingBox(),
  ])
  expect(alertBox).not.toBeNull()
  expect(activityBox).not.toBeNull()
  expect(instructionsBox).not.toBeNull()
  expect(alertBox!.y).toBeLessThan(activityBox!.y)
  expect(activityBox!.y).toBeLessThan(instructionsBox!.y)
  await expect(page.getByRole('button', { name: 'Try Again' })).toBeVisible()
  await expect(page.getByText('Adjust instructions before retry')).toBeVisible()
  await expectNoViolations(page)
})

test('Comment can be selected from an Approve split menu', async ({ page }) => {
  await pushHostMessage(page, { type: 'prListLoaded', prs: [examplePr] })
  await page.getByRole('button', { name: /Improve authentication/ }).click()
  await pushHostMessage(page, {
    type: 'draftLoaded',
    prKey: 'acme/platform#42',
    prState: 'DRAFT_PRESENT',
    reviewId: 'draft-1',
    diff: exampleDiff,
    result: { summary: 'Ready to submit', verdict: 'APPROVE', lineComments: [] },
  })

  await page.getByRole('button', { name: 'More submit options' }).click()
  const comment = page.getByRole('menuitem', { name: 'Comment' })
  await expect(comment).toBeEnabled()
  await comment.click()
  await expect(page.getByRole('alertdialog')).toContainText('Submit comment?')
  await page.getByRole('button', { name: 'Submit Comment' }).click()

  await expect.poll(() => page.evaluate(() => {
    const fixture = (window as unknown as {
      __hostFixture: { outgoing: Array<{ type?: string; verdict?: string }> }
    }).__hostFixture
    return fixture.outgoing.filter((message) => message.type === 'submitReview')
  })).toEqual([expect.objectContaining({ verdict: 'COMMENT' })])
})

test('finding navigation is keyboard operable and has no axe violations', async ({ page }) => {
  await page.setViewportSize({ width: 1_440, height: 900 })
  await pushHostMessage(page, { type: 'prListLoaded', prs: [{ ...examplePr, hasReviewDraft: true }] })
  await page.getByRole('button', { name: /Improve authentication/ }).click()
  await pushHostMessage(page, {
    type: 'draftLoaded',
    prKey: 'acme/platform#42',
    prState: 'DRAFT_PRESENT',
    reviewId: 'draft-findings',
    diff: exampleDiff,
    validationDiff: exampleDiff,
    result: {
      summary: 'Authentication review',
      verdict: 'COMMENT',
      lineComments: [
        {
          file: 'src/auth.ts',
          line: 2,
          type: 'issue',
          severity: 'blocker',
          body: 'Stop when authentication cannot be verified.',
        },
        {
          file: 'src/auth.ts',
          line: 999,
          type: 'note',
          severity: 'minor',
          body: 'Document the rejected request.',
        },
      ],
    },
  })

  await page.getByRole('button', { name: /Findings/ }).click()
  const finding = page.getByRole('list', { name: 'Anchored findings', exact: true }).getByRole('button')
  await finding.focus()
  await expect(finding).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(finding).toHaveAttribute('aria-current', 'location')
  await expect(page.getByRole('list', { name: 'Unanchored findings' })).toBeVisible()
  await expectNoViolations(page)
})

test('selected review and chat have no axe violations in a narrow viewport', async ({ page }) => {
  await page.setViewportSize({ width: 400, height: 600 })
  await pushHostMessage(page, { type: 'prListLoaded', prs: [examplePr] })
  await page.getByRole('button', { name: /Improve authentication/ }).click()
  await pushHostMessage(page, {
    type: 'draftLoaded',
    prKey: 'acme/platform#42',
    prState: 'DRAFT_PRESENT',
    reviewId: 'draft-1',
    diff: exampleDiff,
    validationDiff: exampleDiff,
    result: { summary: '- Check authentication\n- Check keyboard behavior', verdict: 'COMMENT', lineComments: [] },
  })
  await page.getByRole('button', { name: 'Chat' }).click()

  await expect(page.getByRole('region', { name: 'Chat' })).toBeVisible()
  await expectNoViolations(page)
})

test('unrenderable diff warning and submit acknowledgement have no axe violations', async ({ page }) => {
  await pushHostMessage(page, { type: 'prListLoaded', prs: [examplePr] })
  await page.getByRole('button', { name: /Improve authentication/ }).click()
  await pushHostMessage(page, {
    type: 'draftLoaded',
    prKey: 'acme/platform#42',
    prState: 'DRAFT_PRESENT',
    reviewId: 'draft-1',
    diff: 'not a unified diff',
    validationDiff: 'not a unified diff',
    result: { summary: 'Review summary', verdict: 'COMMENT', lineComments: [] },
  })

  await expect(page.getByRole('alert')).toContainText('could not render this diff')
  await expectNoViolations(page)
  await page.getByRole('button', { name: 'Comment', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Submit Comment' })).toBeDisabled()
  await expectNoViolations(page)
})
