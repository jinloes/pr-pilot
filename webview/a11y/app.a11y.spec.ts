import { test, expect, type Page } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { deepPreparation, exampleDiff, examplePr, installHostFixture, latestHostRequest, pushHostMessage } from './hostFixture'
import { pseudoLocalize } from '../src/i18n/format'

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
  await pushHostMessage(page, { type: 'prListLoaded', prs: [], intellijAssistedEnabled: true })
  await page.getByRole('button', { name: 'Show review', exact: true }).click()
  await page.getByText('Review maintenance').press('Enter')
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
  await page.getByTestId('review-pane-shell').getByRole('button', { name: 'Show pull requests' }).click()
  await pushHostMessage(page, { type: 'prListLoaded', prs: [examplePr], intellijAssistedEnabled: true })
  await page.getByRole('button', { name: /Improve authentication/ }).click()
  await pushHostMessage(page, { type: 'draftLoaded', prKey: 'acme/platform#42', prState: 'NO_DRAFT',
    diff: exampleDiff, providerReadiness: { provider: 'claude', available: true, detail: 'Ready' },
    intellijAssistedEnabled: true })
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

for (const width of [220, 280, 320, 396, 520]) {
  for (const theme of ['light', 'dark', 'highContrastLight', 'highContrastDark'] as const) {
    test(`discovery context and neutral readiness at ${width}px in ${theme}`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 })
      await pushHostMessage(page, { type: 'themeChanged', theme })
      const provider = width === 320 || width === 520 ? 'claude' : 'copilot'
      const longRepo = 'organization-with-a-long-name/repository-with-a-long-name'
      const pr = {
        ...examplePr,
        owner: 'organization-with-a-long-name',
        repo: 'repository-with-a-long-name',
        author: 'reviewer-with-a-long-name',
        title: 'A long pull request title that must wrap without displacing repository context',
        isDraft: true, hasReviewDraft: true, reviewStatus: 'UPDATED_SINCE_REVIEW',
      }
      const listStatus = {
        searchScope: 'currentRepo', currentRepo: longRepo, resultLimit: 50,
        limited: false, reviewStatusAvailable: true,
      }
      await pushHostMessage(page, {
        type: 'prListLoaded', prs: [pr, { ...examplePr, number: 43 }], listStatus,
        providerReadiness: { provider, available: true, authenticationStatus: 'unverified', detail: 'CLI found' },
      })
      const nav = page.locator('nav')
      await expect(page.getByTestId('pr-list-shell')).toHaveCSS('width', `${width}px`)
      await expect(page.getByTestId('pr-list-repository')).toHaveText(longRepo)
      const rows = nav.locator('li > button')
      await expect(rows.first().getByText(longRepo)).toHaveClass('sr-only')
      await expect(rows.nth(1).getByText('acme/platform')).not.toHaveClass('sr-only')
      await expect(rows.first()).toHaveAccessibleName(new RegExp(longRepo))
      const coach = nav.getByRole('status').filter({ hasText: 'Sign-in has not been checked.' })
      await expect(coach).toHaveClass(/bg-muted/)
      await expect(coach.locator('svg').first()).toHaveClass(/lucide-info/)
      await expect(coach).toContainText(provider === 'copilot' ? 'Copilot CLI found.' : 'Claude CLI found.')
      const contrasts = await coach.evaluate((element) => {
        const canvas = document.createElement('canvas')
        const context = canvas.getContext('2d')!
        const luminance = (color: string) => {
          context.fillStyle = color
          context.fillRect(0, 0, 1, 1)
          const rgb = Array.from(context.getImageData(0, 0, 1, 1).data).slice(0, 3)
          const linear = rgb.map(value => {
            const channel = value / 255
            return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4
          })
          return linear[0] * 0.2126 + linear[1] * 0.7152 + linear[2] * 0.0722
        }
        const background = luminance(getComputedStyle(element).backgroundColor)
        return ['p', 'svg'].map(selector => {
          const foreground = luminance(getComputedStyle(element.querySelector(selector)!).color)
          return (Math.max(background, foreground) + 0.05) / (Math.min(background, foreground) + 0.05)
        })
      })
      expect(contrasts[0]).toBeGreaterThanOrEqual(4.5)
      expect(contrasts[1]).toBeGreaterThanOrEqual(3)
      for (const element of [nav, coach, page.getByTestId('pr-list-repository'), rows.first(), rows.nth(1)]) {
        expect(await element.evaluate(node => node.scrollWidth <= node.clientWidth)).toBe(true)
      }
      await expectNoViolations(page, 'nav')
      const dismiss = coach.getByRole('button', { name: 'Dismiss readiness message' })
      if (width === 220) {
        await dismiss.focus()
        await expect(dismiss).toBeFocused()
        await page.keyboard.press('Enter')
      } else {
        await dismiss.click()
      }
      await expect(coach).toHaveCount(0)
      await expect.poll(() => page.evaluate(() => localStorage.getItem('pr-pilot:first-success-coach-shown'))).toBe('1')
    })
  }
}

test('discovery compact rows remove one line, preserve keyboard selection and fall back during scope transitions', async ({ page }) => {
  await page.setViewportSize({ width: 396, height: 900 })
  const pr = { ...examplePr, title: 'Short title', author: 'octocat', createdAt: '2026-07-10T10:00:00Z', reviewStatus: 'UNREVIEWED' }
  const listStatus = { searchScope: 'currentRepo', currentRepo: 'acme/platform', resultLimit: 50, limited: false, reviewStatusAvailable: true }
  await pushHostMessage(page, { type: 'prListLoaded', prs: [pr], listStatus })
  const row = page.locator('nav li > button')
  const compactHeight = (await row.boundingBox())!.height
  const titleFont = await row.getByText('Short title').evaluate(node => getComputedStyle(node).fontSize)
  expect(titleFont).toBe('14px')
  await expect(row.getByText('@octocat')).toHaveCSS('font-size', '12px')
  await pushHostMessage(page, { type: 'prListLoaded', prs: [pr] })
  await expect(page.getByTestId('pr-list-repository')).toHaveCount(0)
  await expect(row.getByText('acme/platform')).not.toHaveClass('sr-only')
  expect((await row.boundingBox())!.height - compactHeight).toBeGreaterThanOrEqual(16)
  await pushHostMessage(page, { type: 'prListLoaded', prs: [pr], listStatus })
  await page.getByRole('combobox', { name: 'Pull request scope' }).click()
  await page.getByRole('option', { name: 'Authored by me', exact: true }).click()
  await expect(page.getByTestId('pr-list-repository')).toHaveCount(0)
  await expect(row.getByText('acme/platform')).not.toHaveClass('sr-only')
  await pushHostMessage(page, { type: 'prListLoaded', prs: [pr], listStatus: { ...listStatus, searchScope: 'authored' } })
  await expect(page.getByTestId('pr-list-repository')).toHaveCount(0)
  await page.getByRole('combobox', { name: 'Pull request scope' }).click()
  await page.getByRole('option', { name: 'Current repo', exact: true }).click()
  await expect(page.getByTestId('pr-list-repository')).toHaveCount(0)
  await pushHostMessage(page, { type: 'prListLoaded', prs: [pr], listStatus })
  await expect(page.getByTestId('pr-list-repository')).toHaveText('acme/platform')
  const filter = page.getByRole('textbox', { name: 'Filter pull requests' })
  await filter.fill('#42')
  await expect(row).toHaveCount(1)
  await filter.press('Escape')
  await expect(filter).toHaveValue('')
  await expect(row).toHaveCount(1)
  await row.focus()
  await expect(row).toBeFocused()
  await page.keyboard.press('Space')
  expect(await latestHostRequest(page, 'selectPR')).toMatchObject({ number: 42, owner: 'acme', repo: 'platform' })
})

for (const width of [220, 280, 320, 396, 520]) {
  test(`discovery expanded readiness and repository text wrap at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 })
    await page.goto('/?locale=pseudo')
    await page.evaluate(() => localStorage.removeItem('pr-pilot:first-success-coach-shown'))
    await pushHostMessage(page, {
      type: 'prListLoaded',
      prs: [{ ...examplePr, owner: 'long-organization-name', repo: 'long-repository-name', author: 'long-reviewer-name' }],
      listStatus: { searchScope: 'currentRepo', currentRepo: 'long-organization-name/long-repository-name', resultLimit: 50, limited: false, reviewStatusAvailable: true },
      providerReadiness: { provider: 'claude', available: true, authenticationStatus: 'unverified', detail: 'CLI found' },
    })
    const coach = page.locator('nav [role="status"]').filter({ has: page.locator('.lucide-info') })
    await expect(coach).toContainText('⟦')
    expect(await coach.evaluate(node => node.scrollWidth <= node.clientWidth)).toBe(true)
    for (const selector of ['nav li > button', '[data-testid="pr-list-repository"]', 'nav']) {
      const geometry = await page.locator(selector).evaluate(node => ({
        clientWidth: node.clientWidth, scrollWidth: node.scrollWidth,
        overflowing: Array.from(node.querySelectorAll('*')).filter(child => child.clientWidth && child.scrollWidth > child.clientWidth && !child.classList.contains('sr-only'))
          .map(child => ({ tag: child.tagName, className: child.getAttribute('class'), clientWidth: child.clientWidth, scrollWidth: child.scrollWidth })),
      }))
      expect(geometry.scrollWidth, `${width}px ${selector}: ${JSON.stringify(geometry)}`).toBeLessThanOrEqual(geometry.clientWidth)
    }
    await expectNoViolations(page, 'nav')
    const stateButtons = page.getByRole('radiogroup').getByRole('radio')
    await expect(stateButtons).toHaveCount(3)
    const navBox = (await page.locator('nav').boundingBox())!
    for (const button of await stateButtons.all()) {
      const box = (await button.boundingBox())!
      expect(box.x).toBeGreaterThanOrEqual(navBox.x)
      expect(box.x + box.width).toBeLessThanOrEqual(navBox.x + navBox.width)
      expect(box.height).toBeGreaterThanOrEqual(24)
    }
    await stateButtons.first().focus()
    await page.keyboard.press('ArrowRight')
    await expect(stateButtons.nth(1)).toBeFocused()
    await page.keyboard.press('Space')
    expect(await latestHostRequest(page, 'refreshPRs')).toMatchObject({ state: 'closed' })
  })
}

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
  await page.getByRole('button', { name: 'Submit review…' }).click()
  const dialog = page.getByRole('alertdialog')
  const submit = page.getByRole('button', { name: 'Publish as Comment' })
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

test('the suggested verdict is changed with the keyboard in the publish dialog', async ({ page }) => {
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

  await expect(page.getByTestId('review-scroll-body').getByText('Suggested: Approve')).toBeVisible()
  await page.getByRole('button', { name: 'Submit review…' }).click()
  const dialog = page.getByRole('alertdialog')
  await expect(dialog.getByRole('heading', { name: 'Publish review' })).toBeVisible()
  const suggested = dialog.getByRole('radio', { name: 'Approve (suggested)' })
  await expect(suggested).toBeChecked()
  await suggested.focus()
  await page.keyboard.press('ArrowUp')
  await expect(dialog.getByRole('radio', { name: 'Comment' })).toBeChecked()
  await expectNoViolations(page, '[role="alertdialog"]')
  await page.keyboard.press('Tab')
  await expect(dialog.getByRole('textbox', { name: 'Review body' })).toBeFocused()
  await dialog.getByRole('button', { name: 'Publish as Comment' }).click()

  await expect.poll(() => page.evaluate(() => {
    const fixture = (window as unknown as {
      __hostFixture: { outgoing: Array<{ type?: string; verdict?: string }> }
    }).__hostFixture
    return fixture.outgoing.filter((message) => message.type === 'submitReview')
  })).toEqual([expect.objectContaining({ verdict: 'COMMENT', comment: 'Ready to submit' })])
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

  await expect(page.getByRole('region', { name: 'Chat', exact: true })).toBeVisible()
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
  await page.getByRole('button', { name: 'Submit review…' }).click()
  await expect(page.getByRole('button', { name: 'Publish as Comment' })).toBeDisabled()
  await expectNoViolations(page)
})

type HostThemeName = 'light' | 'dark' | 'highContrastLight' | 'highContrastDark'

const longDiff = Array.from({ length: 6 }, (_, index) => [
  `diff --git a/src/module-${index}.ts b/src/module-${index}.ts`,
  `--- a/src/module-${index}.ts`,
  `+++ b/src/module-${index}.ts`,
  '@@ -1,1 +1,3 @@',
  ` export const existing${index} = true`,
  `+export const added${index} = computeAVeryLongIdentifierNameThatMustScrollWithinTheDiff(${index})`,
  `+export const another${index} = true`,
].join('\n')).join('\n') + '\n'

function coverageTrailer(omitted: number, paths: string[]): string {
  return `[pr-pilot:diff-coverage] omitted=${omitted} listed=${paths.length} budget=250000 scan=complete\n`
    + paths.map((path) => `[pr-pilot:omitted] ${path}\n`).join('')
}

async function selectExamplePr(page: Page, theme: HostThemeName = 'dark') {
  await page.evaluate(() => localStorage.setItem('pr-pilot:first-success-coach-shown', '1'))
  await pushHostMessage(page, { type: 'themeChanged', theme })
  await pushHostMessage(page, { type: 'prListLoaded', prs: [examplePr] })
  await page.getByRole('button', { name: /Improve authentication/ }).click()
}

async function noHorizontalOverflow(page: Page, testId: string) {
  return page.getByTestId(testId).evaluate((element) => element.scrollWidth <= element.clientWidth)
}

for (const width of [320, 1440]) {
  for (const theme of ['dark', 'highContrastDark'] as const) {
    test(`no-draft review shows the read-only diff below Generate at ${width}px in ${theme}`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 })
      await selectExamplePr(page, theme)
      await pushHostMessage(page, {
        type: 'draftLoaded', prKey: 'acme/platform#42', prState: 'NO_DRAFT', diff: longDiff, validationDiff: longDiff,
        providerReadiness: { provider: 'claude', available: true, detail: 'Ready' },
      })

      const pane = page.getByTestId('review-pane-shell')
      await expect(pane.getByRole('navigation', { name: 'Review navigation' })).toBeVisible()
      await expect(pane.getByRole('button', { name: /^Add comment on/ })).toHaveCount(0)
      const generate = pane.getByRole('button', { name: 'Generate Review' })
      const summary = pane.locator('summary').filter({ hasText: 'Review instructions (optional)' })
      expect((await generate.boundingBox())!.y).toBeLessThan((await summary.boundingBox())!.y)
      await generate.focus()
      await page.keyboard.press('Tab')
      await expect(summary).toBeFocused()
      expect(await noHorizontalOverflow(page, 'review-scroll-body')).toBe(true)
      await expectNoViolations(page, '[data-testid="review-pane-shell"]')

      if (width === 320) {
        // Read-only diffs have no gutter buttons, so the file section itself is the keyboard scroll target.
        const section = pane.getByRole('region', { name: 'src/module-0.ts' })
        await expect(section).toHaveAttribute('tabindex', '0')
        expect(await section.evaluate((element) => element.scrollWidth > element.clientWidth)).toBe(true)
        expect(await section.evaluate((element) => getComputedStyle(element).boxShadow)).toBe('none')
        await section.focus()
        await expect(section).toBeFocused()
        expect(await section.evaluate((element) => getComputedStyle(element).boxShadow)).not.toBe('none')
        const scrollLeft = await section.evaluate((element) => element.scrollLeft)
        await page.keyboard.press('ArrowRight')
        await expect.poll(() => section.evaluate((element) => element.scrollLeft)).toBeGreaterThan(scrollLeft)
        await expectNoViolations(page, '[data-testid="review-pane-shell"]')
      }
    })
  }
}

for (const theme of ['light', 'dark'] as const) {
  test(`omitted-file banner states have no axe violations in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 800 })
    await selectExamplePr(page, theme)
    await pushHostMessage(page, {
      type: 'draftLoaded', prKey: 'acme/platform#42', prState: 'NO_DRAFT',
      diff: `${exampleDiff}${coverageTrailer(3, ['src/huge.ts', 'data/generated.json'])}`,
      validationDiff: exampleDiff,
      providerReadiness: { provider: 'claude', available: true, detail: 'Ready' },
    })
    const include = page.getByRole('button', { name: 'Include them with chunked review' })
    await expect(include).toBeVisible()
    expect(await noHorizontalOverflow(page, 'review-scroll-body')).toBe(true)
    await expectNoViolations(page, '[data-testid="review-scroll-body"]')
    await include.focus()
    await page.keyboard.press('Enter')
    await expect(page.getByText(/Chunked review is on\. The next review will include 3 more files\./)).toBeVisible()
    await expectNoViolations(page, '[data-testid="review-scroll-body"]')
  })
}

for (const theme of ['dark', 'highContrastDark'] as const) {
  test(`a refresh failure after loading stays inline and keyboard reachable in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width: 900, height: 700 })
    await page.evaluate(() => localStorage.setItem('pr-pilot:first-success-coach-shown', '1'))
    await pushHostMessage(page, { type: 'themeChanged', theme })
    await pushHostMessage(page, { type: 'prListLoaded', prs: [examplePr] })
    await expect(page.getByTestId('pr-list-shell')).toHaveCSS('width', '280px')
    await page.getByRole('button', { name: /Improve authentication/ }).click()
    await pushHostMessage(page, {
      type: 'draftLoaded', prKey: 'acme/platform#42', prState: 'DRAFT_PRESENT', reviewId: 'draft-1',
      diff: exampleDiff, validationDiff: exampleDiff,
      result: { summary: 'Keep this review usable.', verdict: 'COMMENT', lineComments: [] },
    })
    await page.getByRole('button', { name: 'Refresh pull requests' }).click()
    await pushHostMessage(page, { type: 'setupRequired', reason: 'load_failed', detail: 'GitHub is unreachable.' })

    await expect(page.getByRole('main', { name: 'PR Pilot setup' })).toHaveCount(0)
    const banner = page.getByTestId('pr-list-refresh-error')
    await expect(banner).toContainText("Couldn't refresh pull requests. GitHub is unreachable.")
    await expect(page.getByText('Keep this review usable.')).toBeVisible()
    const list = page.getByTestId('pr-list-shell').locator('nav')
    expect(await list.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true)
    await expectNoViolations(page, '[data-testid="pr-list-shell"]')
    const retry = banner.getByRole('button', { name: 'Retry' })
    await retry.focus()
    await expect(retry).toBeFocused()
    await page.keyboard.press('Tab')
    await expect(banner.getByRole('button', { name: 'Dismiss refresh error' })).toBeFocused()
    await page.keyboard.press('Enter')
    await expect(banner).toHaveCount(0)
  })
}

test('retained-worktree maintenance stays hidden when the experimental setting is not on', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 720 })
  await pushHostMessage(page, { type: 'prListLoaded', prs: [examplePr] })
  await expect(page.getByRole('heading', { name: 'Choose a pull request to begin' })).toBeVisible()
  await expect(page.getByText('Review maintenance')).toHaveCount(0)
  await pushHostMessage(page, { type: 'prListLoaded', prs: [examplePr], intellijAssistedEnabled: true })
  await expect(page.getByText('Review maintenance')).toBeVisible()
})

async function openDraftReview(page: Page, width: number, height: number, theme: HostThemeName, result?: object) {
  await page.setViewportSize({ width, height })
  await selectExamplePr(page, theme)
  await pushHostMessage(page, {
    type: 'draftLoaded', prKey: 'acme/platform#42', prState: 'DRAFT_PRESENT', reviewId: 'draft-1',
    diff: exampleDiff, validationDiff: exampleDiff,
    result: result ?? {
      summary: 'Authentication review.',
      verdict: 'REQUEST_CHANGES',
      lineComments: [{ file: 'src/auth.ts', line: 2, type: 'issue', body: 'Check the new flag.', severity: 'major' }],
    },
  })
  await expect(page.getByRole('button', { name: 'Submit review…' })).toBeVisible()
}

async function waitForFocusedFindingToSettle(page: Page) {
  await expect(page.locator('.diff-comment-row--focused')).toHaveCount(1)
  await page.waitForFunction(() =>
    (document.querySelector('.diff-comment-row--focused .diff-comment-cell')?.getAnimations().length ?? 0) === 0)
}

async function expectNoContrastViolations(page: Page, include?: string) {
  const builder = new AxeBuilder({ page }).withRules(['color-contrast'])
  const results = await (include ? builder.include(include) : builder).analyze()
  expect(
    results.violations.map((violation) => violation.nodes.map((node) => node.target)),
    results.violations.flatMap((violation) => violation.nodes.map((node) => node.failureSummary)).join('\n'),
  ).toEqual([])
}

for (const theme of ['light', 'dark', 'highContrastLight', 'highContrastDark'] as const) {
  test(`draft review text meets contrast requirements at 1440x900 in ${theme}`, async ({ page }) => {
    await openDraftReview(page, 1440, 900, theme)
    await waitForFocusedFindingToSettle(page)
    await expectNoContrastViolations(page)
    const deleteButton = page.getByTestId('review-footer').getByRole('button', { name: 'Delete' })
    const ratio = await deleteButton.evaluate((element) => {
      const canvas = document.createElement('canvas')
      const context = canvas.getContext('2d')!
      const luminance = (color: string) => {
        context.fillStyle = color
        context.fillRect(0, 0, 1, 1)
        const rgb = Array.from(context.getImageData(0, 0, 1, 1).data).slice(0, 3)
        const linear = rgb.map((value) => {
          const channel = value / 255
          return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4
        })
        return linear[0] * 0.2126 + linear[1] * 0.7152 + linear[2] * 0.0722
      }
      const foreground = luminance(getComputedStyle(element).color)
      const background = luminance(getComputedStyle(element.closest('[data-testid="review-footer"]')!).backgroundColor)
      return (Math.max(foreground, background) + 0.05) / (Math.min(foreground, background) + 0.05)
    })
    expect(ratio).toBeGreaterThanOrEqual(4.5)
  })
}

for (const theme of ['dark', 'highContrastDark'] as const) {
  test(`a focused major finding and its active findings item meet contrast requirements in ${theme}`, async ({ page }) => {
    await openDraftReview(page, 1440, 900, theme, {
      summary: 'Authentication review.',
      verdict: 'REQUEST_CHANGES',
      lineComments: [
        { file: 'src/auth.ts', line: 2, type: 'issue', severity: 'major', body: 'Check the new flag.' },
        { file: 'src/auth.ts', line: 1, type: 'issue', severity: 'blocker', body: 'Stop when authentication cannot be verified.' },
      ],
    })
    await page.getByRole('button', { name: /^Findings/ }).click()
    const major = page.getByRole('list', { name: 'Anchored findings', exact: true })
      .getByRole('button', { name: /src\/auth\.ts, line 2:/ })
    await major.click()
    await expect(major).toHaveAttribute('aria-current', 'location')
    // Measure the settled active tint, not the hover tint left behind by the click.
    await page.mouse.move(1, 1)
    await waitForFocusedFindingToSettle(page)
    await expectNoContrastViolations(page, '[data-testid="review-pane-shell"]')
  })

  test(`a keyboard-highlighted Delete comment menu item meets contrast requirements in ${theme}`, async ({ page }) => {
    await openDraftReview(page, 1440, 900, theme)
    await page.getByRole('button', { name: 'More finding actions' }).focus()
    await page.keyboard.press('Enter')
    const deleteItem = page.getByRole('menu').getByRole('menuitem', { name: 'Delete comment' })
    await expect(deleteItem).toBeVisible()
    for (let presses = 0; presses < 5 && (await deleteItem.getAttribute('data-highlighted')) === null; presses += 1) {
      await page.keyboard.press('ArrowDown')
    }
    await expect(deleteItem).toHaveAttribute('data-highlighted', '')
    await page.waitForFunction(() =>
      (document.querySelector('[role="menu"]')?.getAnimations({ subtree: true }).length ?? 0) === 0)
    await expectNoContrastViolations(page, '[role="menu"]')
  })
}

for (const theme of ['light', 'dark', 'highContrastDark'] as const) {
  test(`chat errors meet contrast requirements in ${theme}`, async ({ page }) => {
    await openDraftReview(page, 1000, 800, theme)
    await page.getByRole('button', { name: 'Chat' }).click()
    const input = page.getByRole('textbox', { name: 'Ask about this pull request' })
    await input.fill('Why is this risky?')
    await input.press('Enter')
    await pushHostMessage(page, { type: 'chatError', prKey: 'acme/platform#42', message: 'The provider could not answer.' })
    await expect(page.getByText('The provider could not answer.')).toBeVisible()
    await expectNoViolations(page, '[data-testid="chat-panel"]')
  })
}

test('the narrow review actions menu meets contrast requirements', async ({ page }) => {
  await openDraftReview(page, 400, 568, 'dark')
  await page.getByRole('button', { name: 'More review actions' }).click()
  await expect(page.getByRole('menuitem', { name: 'Delete draft' })).toBeVisible()
  await page.waitForTimeout(300)
  await expectNoViolations(page, '[role="menu"]')
})

test('overflowing chat history can be focused and scrolled with the keyboard', async ({ page }) => {
  await openDraftReview(page, 1000, 800, 'dark')
  await page.getByRole('button', { name: 'Chat' }).click()
  const input = page.getByRole('textbox', { name: 'Ask about this pull request' })
  for (let index = 0; index < 6; index += 1) {
    await input.fill(`Question ${index}`)
    await input.press('Enter')
    await pushHostMessage(page, {
      type: 'chatResponse', prKey: 'acme/platform#42',
      response: `Answer ${index}\n\n${'A detailed explanation line. '.repeat(8)}`,
    })
    await expect(page.getByText(`Answer ${index}`)).toBeVisible()
  }
  const history = page.getByRole('region', { name: 'Chat messages' })
  expect(await history.evaluate((element) => element.scrollHeight > element.clientHeight)).toBe(true)
  const bottom = await history.evaluate((element) => element.scrollTop)
  await input.focus()
  await page.keyboard.press('Shift+Tab')
  await expect(history).toBeFocused()
  await page.keyboard.press('PageUp')
  await expect.poll(() => history.evaluate((element) => element.scrollTop)).toBeLessThan(bottom)
  await expectNoViolations(page, '[data-testid="chat-panel"]')
})

for (const width of [320, 400]) {
  for (const theme of ['dark', 'highContrastDark', 'light'] as const) {
    test(`narrow footer keeps one row and chat keeps its composer at ${width}x568 in ${theme}`, async ({ page }) => {
      await openDraftReview(page, width, 568, theme)
      const footer = page.getByTestId('review-footer')
      expect((await footer.boundingBox())!.height).toBeLessThanOrEqual(48)

      await page.getByRole('button', { name: 'Chat' }).click()
      await page.waitForTimeout(200)
      const body = await page.getByTestId('review-scroll-body').boundingBox()
      expect(body!.height).toBeGreaterThanOrEqual(160)
      const composer = page.getByRole('textbox', { name: 'Ask about this pull request' })
      const send = page.getByRole('button', { name: 'Send' })
      for (const control of [composer, send]) {
        const box = (await control.boundingBox())!
        const footerBox = (await footer.boundingBox())!
        expect(box.y).toBeGreaterThanOrEqual(0)
        expect(box.y + box.height).toBeLessThanOrEqual(footerBox.y)
      }
      await composer.focus()
      await expect(composer).toBeFocused()

      const more = footer.getByRole('button', { name: 'More review actions' })
      await more.focus()
      await page.keyboard.press('Enter')
      await expect(page.getByRole('menuitem', { name: 'Regenerate' })).toBeFocused()
      await page.keyboard.press('ArrowDown')
      await expect(page.getByRole('menuitem', { name: /^Review quality · / })).toBeFocused()
      await page.keyboard.press('ArrowDown')
      await expect(page.getByRole('menuitem', { name: 'Delete draft' })).toBeFocused()
      await page.keyboard.press('Escape')
      await expect(more).toBeFocused()
      await page.keyboard.press('Enter')
      await page.getByRole('menuitem', { name: 'Delete draft' }).press('Enter')
      await expect(page.getByRole('alertdialog')).toContainText('Delete draft review?')
      await page.getByRole('button', { name: 'Cancel' }).click()
      await expect.poll(() => page.evaluate(() => (window as unknown as {
        __hostFixture: { outgoing: Array<{ type?: string }> }
      }).__hostFixture.outgoing.filter((message) => message.type === 'deleteDraft').length)).toBe(0)
    })
  }
}

for (const width of [320, 400]) {
  for (const theme of ['dark', 'highContrastDark', 'light'] as const) {
    for (const saveState of ['Saved', 'Save now'] as const) {
      test(`pseudo-localized narrow footer keeps its three controls in one row at ${width}x568 in ${theme} (${saveState})`, async ({ page }) => {
        await page.setViewportSize({ width, height: 568 })
        await page.goto('/?locale=pseudo')
        await page.waitForLoadState('networkidle')
        await page.evaluate(() => localStorage.setItem('pr-pilot:first-success-coach-shown', '1'))
        await pushHostMessage(page, { type: 'themeChanged', theme })
        await pushHostMessage(page, { type: 'prListLoaded', prs: [examplePr] })
        await page.locator('nav li > button').first().click()
        await pushHostMessage(page, {
          type: 'draftLoaded', prKey: 'acme/platform#42', prState: 'DRAFT_PRESENT', reviewId: 'draft-1',
          diff: exampleDiff, validationDiff: exampleDiff,
          result: {
            summary: 'Authentication review.',
            verdict: 'REQUEST_CHANGES',
            lineComments: [
              { file: 'src/auth.ts', line: 2, type: 'issue', body: 'Check the new flag.', severity: 'major' },
              { file: 'src/session.ts', line: 7, type: 'note', body: 'Outside the diff.' },
            ],
          },
        })
        const footer = page.getByTestId('review-footer')
        const trigger = footer.getByRole('button', { name: pseudoLocalize('More review actions') })
        const submit = footer.getByRole('button', { name: pseudoLocalize('Submit review…') })
        await expect(submit).toBeVisible()

        let status = footer.getByRole('status')
        if (saveState === 'Save now') {
          // Edit the unanchored comment: it sits inside the viewport, so no ancestor has to scroll sideways.
          await page.getByRole('button', { name: pseudoLocalize('Edit unanchored comment') }).click()
          const editor = page.getByRole('textbox', { name: pseudoLocalize('Edit unanchored comment on src/session.ts, line 7') })
          await editor.fill('Still outside the diff.')
          await editor.press('Control+Enter')
          status = footer.getByRole('button', { name: pseudoLocalize('Save now') })
          await expect(status).toHaveAttribute(
            'title',
            pseudoLocalize('Changes save to the GitHub draft automatically. Click to save right now.'),
          )
        } else {
          await expect(status).toHaveText(pseudoLocalize('Saved'))
          await expect(status).toHaveAttribute('title', pseudoLocalize('Saved to GitHub'))
        }
        await expect(status).toBeVisible()

        const footerBox = (await footer.boundingBox())!
        expect(footerBox.height).toBeLessThanOrEqual(48)
        const boxes = []
        for (const control of [trigger, status, submit]) boxes.push((await control.boundingBox())!)
        const geometry = JSON.stringify({ footer: footerBox, controls: boxes })
        for (const box of boxes) {
          expect(box.x, geometry).toBeGreaterThanOrEqual(0)
          expect(box.x + box.width, geometry).toBeLessThanOrEqual(width)
          expect(box.y, geometry).toBeGreaterThanOrEqual(footerBox.y)
          expect(box.y + box.height, geometry).toBeLessThanOrEqual(footerBox.y + footerBox.height)
        }
        for (let index = 1; index < boxes.length; index += 1) {
          expect(boxes[index - 1].x + boxes[index - 1].width, geometry).toBeLessThanOrEqual(boxes[index].x)
        }
        expect(await footer.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true)
      })
    }
  }
}

for (const width of [320, 400, 1440]) {
  for (const theme of ['light', 'dark', 'highContrastLight', 'highContrastDark'] as const) {
    test(`review alerts do not overflow horizontally at ${width}px in ${theme}`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 })
      await selectExamplePr(page, theme)
      const diff = `${exampleDiff}${coverageTrailer(2, ['src/huge.ts'])}`
      await pushHostMessage(page, {
        type: 'draftLoaded', prKey: 'acme/platform#42', prState: 'DRAFT_PRESENT', reviewId: 'draft-1',
        diff, validationDiff: diff, staleCommits: true, importedFromGitHub: true,
        result: { summary: 'Imported review.', verdict: 'COMMENT', lineComments: [] },
      })
      await expect(page.getByText(/Draft generated against an older commit/)).toBeVisible()
      await expect(page.getByText(/Draft was reconstructed from GitHub comments/)).toBeVisible()
      await expect(page.getByText(/aren't included in this review/)).toBeVisible()
      expect(await noHorizontalOverflow(page, 'review-scroll-body')).toBe(true)
      const scrollBody = (await page.getByTestId('review-scroll-body').boundingBox())!
      for (const alert of await page.getByTestId('review-scroll-body').getByRole('alert').all()) {
        const box = (await alert.boundingBox())!
        expect(box.x + box.width).toBeLessThanOrEqual(scrollBody.x + scrollBody.width)
      }
    })
  }
}

test('a keyboard-made selection can be sent to chat', async ({ page }) => {
  await openDraftReview(page, 1280, 800, 'dark', {
    summary: 'Keyboard selectable summary text.', verdict: 'COMMENT', lineComments: [],
  })
  await expect(page.getByRole('button', { name: 'Ask selection' })).toHaveCount(0)
  await page.getByText('Keyboard selectable summary text.').evaluate((element) => {
    const range = document.createRange()
    range.selectNodeContents(element)
    const selection = window.getSelection()!
    selection.removeAllRanges()
    selection.addRange(range)
  })
  const ask = page.getByRole('button', { name: 'Ask selection' })
  await expect(ask).toBeVisible()
  await ask.click()
  await expect(page.getByRole('region', { name: 'Chat', exact: true })).toContainText('Keyboard selectable summary text.')
})

for (const [width, height] of [[320, 568], [1280, 800]] as const) {
  for (const theme of ['dark', 'highContrastDark'] as const) {
    test(`publish dialog previews the full review body at ${width}x${height} in ${theme}`, async ({ page }) => {
      await openDraftReview(page, width, height, theme, {
        summary: 'Generated summary for publishing.',
        verdict: 'COMMENT',
        lineComments: [
          { file: '', line: 1, type: 'note', body: 'General note about the whole change.' },
          { file: 'src/auth.ts', line: 2, type: 'issue', body: 'Inline finding.' },
          { file: 'src/auth.ts', line: 999, type: 'note', body: 'Detached finding outside every hunk.' },
        ],
      })
      await page.getByRole('button', { name: 'Submit review…' }).click()
      const dialog = page.getByRole('alertdialog')
      await expect(dialog).toContainText('This will publish the pending GitHub review with 1 inline comment.')
      await expect(dialog.getByRole('textbox', { name: 'Review body' })).toHaveValue('Generated summary for publishing.')
      const region = dialog.getByRole('region', { name: 'Also published in the review body' })
      await expect(region).toContainText('General note about the whole change.')
      await expect(region).toContainText('src/auth.ts:999')
      const box = (await dialog.boundingBox())!
      expect(box.x + box.width).toBeLessThanOrEqual(width)
      expect(box.y + box.height).toBeLessThanOrEqual(height)
      expect(await dialog.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true)
      const acknowledgement = dialog.getByRole('checkbox')
      if (await acknowledgement.count()) await acknowledgement.check()
      await dialog.getByRole('textbox', { name: 'Review body' }).focus()
      await page.keyboard.press('Tab')
      await expect(region).toBeFocused()
      await page.keyboard.press('Tab')
      await expect(dialog.getByRole('button', { name: 'Cancel' })).toBeFocused()
      await page.keyboard.press('Tab')
      await expect(dialog.getByRole('button', { name: 'Publish as Comment' })).toBeFocused()
      await expectNoViolations(page, '[role="alertdialog"]')
    })
  }
}
