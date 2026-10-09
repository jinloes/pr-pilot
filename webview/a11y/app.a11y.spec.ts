import { test, expect } from '@playwright/test'
import { deepPreparation, exampleDiff, examplePr, installHostFixture, latestHostRequest, pushHostMessage } from './hostFixture'
import { expectNoViolations, openGeneratingReview } from './a11yHelpers'

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
          suggestedChange: 'export const ready = true // trusted only after verifyToken()',
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
          suggestedChange: 'export const accessible = verifyToken()',
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
  await expect(page.getByRole('region', { name: 'Suggested change' })).toBeVisible()
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
