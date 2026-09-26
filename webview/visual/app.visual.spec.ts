import { expect, test, type Page } from '@playwright/test'
import { examplePr, installHostFixture, pushHostMessage } from '../a11y/hostFixture'
import { MIN_REVIEW_BODY_HEIGHT } from '../src/components/ReviewPane/chatHeight'
import { buildExampleFixPrompt, buildVerifyCommentPrompt } from '../src/components/ReviewPane/verifyPrompt'

test.beforeEach(async ({ page }) => {
  await installHostFixture(page)
})

async function expectViewportFilled(page: Page) {
  const dimensions = await page.locator('#root').evaluate((root) => {
    const main = root.querySelector('main')
    return {
      rootHeight: root.getBoundingClientRect().height,
      mainHeight: main?.getBoundingClientRect().height,
      viewportHeight: window.innerHeight,
    }
  })

  expect(dimensions.rootHeight).toBe(dimensions.viewportHeight)
  expect(dimensions.mainHeight).toBe(dimensions.viewportHeight)
}

/**
 * Text-critical panes also get a tight, region-level comparison: whole-page tolerance alone let copy
 * and hierarchy changes pass. Never loosen this; use per-locator `maxDiffPixels` for proven noise.
 */
const REGION_SCREENSHOT = { maxDiffPixelRatio: 0.002 }

async function expectNoHorizontalOverflow(page: Page, selector: string) {
  const dimensions = await page.locator(selector).evaluate((element) => ({
    clientWidth: element.clientWidth,
    scrollWidth: element.scrollWidth,
  }))
  expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.clientWidth)
}

const reviewDiff = `diff --git a/src/auth.ts b/src/auth.ts
--- a/src/auth.ts
+++ b/src/auth.ts
@@ -1,1 +1,2 @@
 export const ready = true
+export const accessible = true
`
const reviewComment = {
  file: 'src/auth.ts',
  line: 2,
  type: 'issue',
  body: 'Explain this security behavior.',
}

async function selectExamplePr(page: Page, hasReviewDraft: boolean) {
  const pr = { ...examplePr, hasReviewDraft }
  await pushHostMessage(page, { type: 'prListLoaded', prs: [pr] })
  await page.locator('nav li > button').first().click()

  await expect.poll(() => page.evaluate(() => {
    const fixture = (window as unknown as {
      __hostFixture: { outgoing: Array<Record<string, unknown>> }
    }).__hostFixture
    return fixture.outgoing.find((message) => message.type === 'selectPR')
  })).toMatchObject({ type: 'selectPR', number: 42, owner: 'acme', repo: 'platform' })
}

async function openDraftReview(page: Page) {
  await selectExamplePr(page, true)
  await pushHostMessage(page, {
    type: 'draftLoaded',
    prKey: 'acme/platform#42',
    prState: 'DRAFT_PRESENT',
    reviewId: 'draft-1',
    diff: reviewDiff,
    validationDiff: reviewDiff,
    result: {
      summary: 'Authentication review',
      verdict: 'COMMENT',
      lineComments: [reviewComment],
    },
  })
}

async function openFindingNavigationReview(page: Page) {
  const files = [
    'src/security/AuthenticationPolicyVerificationCoordinator.ts',
    'src/security/AuthenticationPolicyVerificationRepository.ts',
    'src/security/AuthenticationPolicyVerificationTelemetry.ts',
  ]
  const diff = files.map((file, index) => [
    `diff --git a/${file} b/${file}`,
    `--- a/${file}`,
    `+++ b/${file}`,
    '@@ -0,0 +1 @@',
    `+export const value${index} = true`,
  ].join('\n')).join('\n')

  await selectExamplePr(page, true)
  await pushHostMessage(page, {
    type: 'draftLoaded',
    prKey: 'acme/platform#42',
    prState: 'DRAFT_PRESENT',
    reviewId: 'draft-findings',
    diff,
    validationDiff: diff,
    result: {
      summary: 'Authentication policy review',
      verdict: 'COMMENT',
      lineComments: [
        {
          file: files[1],
          line: 1,
          type: 'issue',
          severity: 'major',
          category: 'security',
          confidence: 'high',
          body: 'Reject policies that do not have a trusted issuer before persisting them.',
        },
        {
          file: files[0],
          line: 1,
          type: 'issue',
          severity: 'blocker',
          category: 'correctness',
          confidence: 'high',
          body: 'Stop evaluation when the authentication policy cannot be verified.',
        },
        {
          file: files[2],
          line: 999,
          type: 'note',
          severity: 'minor',
          category: 'maintainability',
          confidence: 'medium',
          body: 'Record the rejected policy reason in telemetry.',
        },
      ],
    },
  })
}

async function openNoDraftReview(page: Page, diff = reviewDiff, intellijAssistedEnabled = false) {
  await selectExamplePr(page, false)
  await pushHostMessage(page, {
    type: 'draftLoaded',
    prKey: 'acme/platform#42',
    prState: 'NO_DRAFT',
    diff,
    validationDiff: diff,
    providerReadiness: { provider: 'claude', available: true, detail: 'Ready' },
    ...(intellijAssistedEnabled ? { intellijAssistedEnabled } : {}),
  })
}

async function openGeneratingReview(page: Page, activityCount = 1) {
  await openNoDraftReview(page)
  await page.getByRole('button', { name: 'Generate Review' }).click()
  for (let index = 0; index < activityCount; index += 1) {
    await pushHostMessage(page, {
      type: 'reviewGenerating',
      prKey: 'acme/platform#42',
      message: index === 0 ? 'read_file' : `tool_${index}`,
    })
  }
}

async function openLongestRiskySubmit(page: Page) {
  await selectExamplePr(page, true)
  await pushHostMessage(page, {
    type: 'draftLoaded',
    prKey: 'acme/platform#42',
    prState: 'DRAFT_PRESENT',
    reviewId: 'draft-risky',
    diff: reviewDiff,
    validationDiff: reviewDiff,
    result: {
      summary: [
        'Authentication changes need a careful final review before publishing.',
        'Confirm the trust boundary, stale anchor, and stated evidence.',
        'This deliberately exercises the longest supported submit confirmation.',
      ].join('\n\n'),
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
}

test('setup recovery layout', async ({ page }) => {
  await page.goto('/')
  await pushHostMessage(page, { type: 'themeChanged', theme: 'dark' })
  await pushHostMessage(page, { type: 'setupRequired', reason: 'gh_not_authenticated', detail: 'Authenticate GitHub CLI to continue.' })
  await expectViewportFilled(page)
  await expect(page).toHaveScreenshot('setup-dark.png')
})

test('narrow setup keeps every recovery action reachable without horizontal overflow', async ({ page }) => {
  for (const width of [320, 400]) {
    await page.setViewportSize({ width, height: 568 })
    await page.goto('/')
    await pushHostMessage(page, { type: 'themeChanged', theme: width === 320 ? 'dark' : 'light' })
    await pushHostMessage(page, {
      type: 'setupRequired',
      reason: 'gh_not_authenticated',
      detail: 'Authenticate GitHub CLI to continue.',
      providerReadiness: {
        provider: 'claude',
        available: false,
        detail: 'Claude Code authentication must be repaired before reviews can run.',
        authCommand: 'claude auth login',
        authenticationStatus: 'unavailable',
      },
    })

    const main = page.getByRole('main', { name: 'PR Pilot setup' })
    const title = page.getByRole('heading', { name: 'GitHub not connected' })
    await expect(title).toBeInViewport()
    await expectNoHorizontalOverflow(page, 'main[aria-label="PR Pilot setup"]')

    if (width === 320) {
      await expect(page).toHaveScreenshot('setup-narrow-dark.png')
    }

    const finalAction = page.getByRole('button', { name: 'Check status' })
    for (let press = 0; press < 20 && !(await finalAction.evaluate((element) => element === document.activeElement)); press += 1) {
      await page.keyboard.press('Tab')
    }
    await expect(finalAction).toBeFocused()
    await expect(finalAction).toBeInViewport()
    expect(await main.evaluate((element) => element.scrollTop)).toBeGreaterThan(0)
  }
})

test('setup remains usable at 200% zoom', async ({ page }) => {
  await page.setViewportSize({ width: 400, height: 568 })
  await page.goto('/')
  await pushHostMessage(page, {
    type: 'setupRequired',
    reason: 'gh_not_authenticated',
    detail: 'Authenticate GitHub CLI to continue.',
    providerReadiness: {
      provider: 'claude',
      available: false,
      detail: 'Claude Code authentication must be repaired before reviews can run.',
      authCommand: 'claude auth login',
      authenticationStatus: 'unavailable',
    },
  })
  await page.evaluate(() => {
    document.documentElement.style.zoom = '2'
  })

  const main = page.getByRole('main', { name: 'PR Pilot setup' })
  await expect(page.getByRole('heading', { name: 'GitHub not connected' })).toBeInViewport()
  await expectNoHorizontalOverflow(page, 'main[aria-label="PR Pilot setup"]')

  const finalAction = page.getByRole('button', { name: 'Check status' })
  for (let press = 0; press < 20 && !(await finalAction.evaluate((element) => element === document.activeElement)); press += 1) {
    await page.keyboard.press('Tab')
  }
  await expect(finalAction).toBeFocused()
  await expect(finalAction).toBeInViewport()
  expect(await main.evaluate((element) => element.scrollTop)).toBeGreaterThan(0)
})

test('populated discovery layout', async ({ page }) => {
  await page.goto('/')
  await pushHostMessage(page, { type: 'themeChanged', theme: 'light' })
  await pushHostMessage(page, {
    type: 'prListLoaded',
    prs: [
      examplePr,
      {
        ...examplePr,
        number: 43,
        title: 'Add long translated review workflow guidance',
        repo: 'workflows',
        isDraft: true,
        hasReviewDraft: true,
        reviewStatus: 'UPDATED_SINCE_REVIEW',
      },
    ],
    listStatus: {
      searchScope: 'currentRepo',
      currentRepo: 'acme/platform',
      resultLimit: 50,
      limited: false,
      reviewStatusAvailable: true,
    },
    providerReadiness: {
      provider: 'copilot', available: true, authenticationStatus: 'unverified', detail: 'CLI found',
    },
  })
  await expect(page.locator('nav li > button')).toHaveCount(2)
  await expect(page.getByText(examplePr.title, { exact: true })).toBeVisible()
  await expect(page.getByText('Add long translated review workflow guidance', { exact: true })).toBeVisible()
  const reviewPane = page.getByTestId('review-pane-shell')
  await expect(reviewPane.getByRole('heading', { name: 'Choose a pull request to begin' })).toBeVisible()
  await expect(reviewPane.getByText('Select a pull request to open its diff and review actions.')).toBeVisible()
  await expect(page.getByText('Review maintenance', { exact: true })).toHaveCount(0)
  await expect(page.getByText('Retained IntelliJ review worktrees')).toHaveCount(0)
  await expectViewportFilled(page)
  await expect(page).toHaveScreenshot('discovery-light.png')
  await expect(reviewPane).toHaveScreenshot('discovery-review-pane-light.png', REGION_SCREENSHOT)
  await expect(page.getByTestId('pr-list-shell')).toHaveScreenshot('discovery-pr-list-light.png', REGION_SCREENSHOT)
})

test('narrow pseudo-localized layout', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 568 })
  await page.goto('/?locale=pseudo')
  await pushHostMessage(page, {
    type: 'prListLoaded',
    prs: [{
      ...examplePr,
      title: 'Update authentication after the security review changed',
      isDraft: true,
      hasReviewDraft: true,
      reviewStatus: 'UPDATED_SINCE_REVIEW',
    }],
    listStatus: {
      searchScope: 'currentRepo',
      currentRepo: 'acme/platform',
      resultLimit: 50,
      limited: false,
      reviewStatusAvailable: true,
    },
    providerReadiness: {
      provider: 'claude', available: true, authenticationStatus: 'unverified', detail: 'CLI found',
    },
  })
  await expect(page.locator('nav li > button').first()).toBeInViewport()
  await expectNoHorizontalOverflow(page, 'nav')
  await expect(page).toHaveScreenshot('narrow-pseudo.png')
})

test('PR-list toolbar adapts to minimum, default, and wide containers', async ({ page }) => {
  await page.setViewportSize({ width: 1_200, height: 720 })
  for (const [paneWidth, theme, locale] of [
    [220, 'light', 'pseudo'],
    [280, 'dark', 'en'],
    [320, 'highContrastDark', 'en'],
    [520, 'light', 'en'],
  ] as const) {
    await page.setViewportSize({
      width: paneWidth === 520 ? paneWidth : 1_200,
      height: 720,
    })
    await page.goto(`/?locale=${locale}`)
    await page.evaluate((width) => {
      localStorage.setItem('claude-reviews:divider-width', String(width))
      localStorage.setItem('pr-pilot:first-success-coach-shown', '1')
    }, paneWidth)
    await page.reload()
    await pushHostMessage(page, { type: 'themeChanged', theme })
    await pushHostMessage(page, {
      type: 'prListLoaded',
      prs: [examplePr, { ...examplePr, number: 43, repo: 'workflows' }],
      listStatus: {
        searchScope: 'currentRepo',
        currentRepo: 'acme/platform',
        resultLimit: 50,
        limited: false,
        reviewStatusAvailable: true,
      },
    })

    const shell = page.getByTestId('pr-list-shell')
    const toolbar = page.getByTestId('pr-list-toolbar')
    const labels = toolbar.locator('.pr-list-toolbar-label')
    const settings = toolbar.locator('button').nth(0)
    const refresh = toolbar.locator('button').nth(1)
    await expect(shell).toHaveCSS('width', `${paneWidth}px`)
    await expectNoHorizontalOverflow(page, '[data-testid="pr-list-toolbar"]')

    for (const action of [settings, refresh]) {
      const box = await action.boundingBox()
      expect(box).not.toBeNull()
      expect(box!.width).toBeGreaterThanOrEqual(24)
      expect(box!.height).toBeGreaterThanOrEqual(24)
    }
    if (paneWidth <= 320) {
      await expect(labels.first()).toBeHidden()
    } else {
      await expect(labels.first()).toBeVisible()
    }

    if (paneWidth === 220) {
      await settings.focus()
      await expect(page.getByRole('tooltip')).toBeVisible()
      await page.keyboard.press('Escape')
      await expect(shell).toHaveScreenshot('pr-toolbar-min-pseudo.png')
    }
    if (paneWidth === 280) {
      await expect(shell).toHaveScreenshot('pr-toolbar-default-dark.png')
    }
  }
})

test('discovery exceptions and notification badges remain compact', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 568 })
  await page.goto('/')
  await page.evaluate(() => localStorage.setItem('pr-pilot:first-success-coach-shown', '1'))
  await pushHostMessage(page, { type: 'themeChanged', theme: 'highContrastDark' })
  await pushHostMessage(page, {
    type: 'prListLoaded',
    prs: [{
      ...examplePr,
      title: 'A very long pull request title that changed after the reviewer submitted feedback',
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

  await expectNoHorizontalOverflow(page, 'nav')
  await expect(page.getByText('Review status is unavailable. Refresh to try again.')).toBeVisible()
  await expect(page.getByText('From notification')).toBeVisible()
  await expect(page).toHaveScreenshot('discovery-exceptions-narrow-high-contrast.png')
})

test('no-draft hierarchy keeps the primary action ahead of optional overrides', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 568 })
  await page.goto('/?locale=pseudo')
  await pushHostMessage(page, { type: 'themeChanged', theme: 'highContrastDark' })
  await openNoDraftReview(page)

  const generate = page.getByTestId('generate-review')
  const disclosure = page.getByTestId('review-overrides-disclosure').locator(':scope > summary')
  const [generateBox, disclosureBox] = await Promise.all([generate.boundingBox(), disclosure.boundingBox()])
  expect(generateBox).not.toBeNull()
  expect(disclosureBox).not.toBeNull()
  expect(generateBox!.y).toBeLessThan(disclosureBox!.y)
  await expect(page.locator('#review-focus-areas')).toBeHidden()
  const reviewPane = page.getByTestId('review-pane-shell')
  await expect(reviewPane.getByTestId('generation-card')).toContainText('⟦')
  await expect(reviewPane.getByRole('navigation', { name: /Reeviieew naaviigaatiioon/ })).toBeVisible()
  await expect(reviewPane.locator('.diff-gutter__button')).toHaveCount(0)
  await expectNoHorizontalOverflow(page, '[data-testid="review-scroll-body"]')
  await expect(page).toHaveScreenshot('no-draft-narrow-pseudo-high-contrast.png')
  await expect(reviewPane).toHaveScreenshot('no-draft-review-pane-narrow-pseudo-high-contrast.png', REGION_SCREENSHOT)
})

test('wide generation stays on a bounded reading rail without exposing provider output', async ({ page }) => {
  await page.setViewportSize({ width: 1_440, height: 900 })
  await page.goto('/')
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

  const activity = page.getByRole('region', { name: 'Review generation activity' })
  const geometry = await activity.evaluate((element) => {
    const bounds = element.getBoundingClientRect()
    return { width: bounds.width, x: bounds.x }
  })
  expect(geometry.width).toBeGreaterThan(900)
  await expect(page.getByRole('navigation', { name: 'Review navigation' })).toBeVisible()
  await expect(page.getByText('PRIVATE_PROVIDER_REASONING_SENTINEL')).toHaveCount(0)
  await expect(page.getByText('RAW_PROVIDER_TEXT_SENTINEL')).toHaveCount(0)
  await expect(activity.getByRole('button', { name: 'Stop generation' })).toBeVisible()
  await expect(page.getByText(/Keep inspecting the changed files while PR Pilot generates the review/)).toBeVisible()
  await expect(page.getByTestId('review-overrides-disclosure')).toHaveCount(0)
  await expect(page).toHaveScreenshot('review-generation-wide-dark.png')
  await expect(page.getByTestId('review-pane-shell'))
    .toHaveScreenshot('review-generation-review-pane-wide-dark.png', REGION_SCREENSHOT)
})

test('long generation activity remains usable in a narrow high-contrast host', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 568 })
  await page.goto('/')
  await pushHostMessage(page, { type: 'themeChanged', theme: 'highContrastDark' })
  await openGeneratingReview(page, 16)

  const activity = page.getByRole('region', { name: 'Review generation activity' })
  await activity.getByRole('button', { name: 'Show details for Generating review' }).click()
  const entries = activity.getByRole('region', { name: 'Review activity entries' })
  await expectNoHorizontalOverflow(page, '[data-testid="review-pane-shell"]')
  expect(await entries.evaluate((element) => element.scrollHeight > element.clientHeight)).toBe(true)
  await entries.focus()
  await expect(entries).toBeFocused()
  await expect(page).toHaveScreenshot('review-generation-long-narrow-high-contrast.png')
})

test('generation failure puts recovery first in a narrow dark host', async ({ page }) => {
  await page.setViewportSize({ width: 400, height: 600 })
  await page.goto('/')
  await pushHostMessage(page, { type: 'themeChanged', theme: 'dark' })
  await openGeneratingReview(page, 5)
  await pushHostMessage(page, {
    type: 'reviewError',
    prKey: 'acme/platform#42',
    message: 'Provider failed. Check credentials and retry.',
  })

  const [alertBox, activityBox, instructionsBox] = await Promise.all([
    page.getByRole('alert').boundingBox(),
    page.getByRole('region', { name: 'Review generation activity' }).boundingBox(),
    page.getByTestId('review-overrides-disclosure').boundingBox(),
  ])
  expect(alertBox).not.toBeNull()
  expect(activityBox).not.toBeNull()
  expect(instructionsBox).not.toBeNull()
  expect(alertBox!.y).toBeLessThan(activityBox!.y)
  expect(activityBox!.y).toBeLessThan(instructionsBox!.y)
  await expect(page.getByRole('button', { name: 'Try Again' })).toBeVisible()
  await expect(page).toHaveScreenshot('review-generation-failed-narrow-dark.png')
})

test('longest risky-submit dialog stays inside a 320x568 viewport and traps focus', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 568 })
  await page.goto('/')
  await pushHostMessage(page, { type: 'themeChanged', theme: 'dark' })
  await openLongestRiskySubmit(page)

  const dialog = page.getByRole('alertdialog')
  const title = page.getByRole('heading', { name: 'Publish review' })
  const cancel = page.getByRole('button', { name: 'Cancel' })
  const submit = page.getByRole('button', { name: 'Publish as Comment' })
  await expect(dialog).toContainText('3 unresolved trust risks')
  const geometry = await dialog.evaluate((element) => {
    const box = element.getBoundingClientRect()
    return {
      x: box.x,
      y: box.y,
      right: box.right,
      bottom: box.bottom,
      clientHeight: element.clientHeight,
      scrollHeight: element.scrollHeight,
      scrollTop: element.scrollTop,
    }
  })
  expect(geometry.x).toBeGreaterThanOrEqual(16)
  expect(geometry.y).toBeGreaterThanOrEqual(16)
  expect(geometry.right).toBeLessThanOrEqual(304)
  expect(geometry.bottom).toBeLessThanOrEqual(552)
  expect(geometry.scrollHeight).toBeGreaterThan(geometry.clientHeight)
  expect(geometry.scrollTop).toBe(0)
  await expect(title).toBeInViewport()
  await expect(page).toHaveScreenshot('risky-submit-narrow-dark.png')

  await page.getByRole('checkbox', { name: /I reviewed these unresolved trust risks/ }).check()
  await cancel.focus()
  await page.keyboard.press('Tab')
  await expect(submit).toBeFocused()
  await expect(submit).toBeInViewport()
  await page.keyboard.press('Tab')
  expect(await dialog.evaluate((element) => element.contains(document.activeElement))).toBe(true)
  await page.keyboard.press('Escape')
  await expect(dialog).toBeHidden()
})

test('toast theme follows the host when OS and host themes disagree', async ({ page }) => {
  await page.setViewportSize({ width: 800, height: 600 })
  await page.emulateMedia({ colorScheme: 'light' })
  await page.goto('/')
  await pushHostMessage(page, { type: 'themeChanged', theme: 'dark' })
  await openNoDraftReview(page, '', true)
  await page.getByText('Review instructions (optional)').click()
  await page.getByText('Advanced review options').click()
  const deepMode = page.getByRole('checkbox', { name: 'IntelliJ-assisted review (requires manual worktree import)' })
  const chunked = page.getByRole('checkbox', { name: 'Use chunked review mode as an advanced fallback' })
  await expect(deepMode).toBeVisible()
  await expect(deepMode).not.toBeChecked()
  await chunked.check()
  await expect(chunked).toBeChecked()
  await page.getByRole('button', { name: 'Generate Review' }).click()

  const toaster = page.locator('[data-sonner-toaster]')
  const toast = page.locator('[data-sonner-toast]')
  await expect(toaster).toHaveAttribute('data-sonner-theme', 'dark')
  await expect(toast).toContainText('Chunked mode needs a loaded diff')
  expect(await page.evaluate(() => (window as unknown as {
    __hostFixture: { outgoing: Record<string, unknown>[] }
  }).__hostFixture.outgoing.filter(message =>
    ['generateReview', 'prepareDeepReview', 'continueDeepReview'].includes(String(message.type)),
  ))).toEqual([])
  await toast.hover()
  await expect(page).toHaveScreenshot('toast-host-dark-os-light.png')

  await page.emulateMedia({ colorScheme: 'dark' })
  await page.reload()
  await pushHostMessage(page, { type: 'themeChanged', theme: 'light' })
  await openNoDraftReview(page, '', true)
  await page.getByText('Review instructions (optional)').click()
  await page.getByText('Advanced review options').click()
  await page.getByRole('checkbox', { name: 'Use chunked review mode as an advanced fallback' }).check()
  await page.getByRole('button', { name: 'Generate Review' }).click()
  await expect(toaster).toHaveAttribute('data-sonner-theme', 'light')

  await page.reload()
  await pushHostMessage(page, { type: 'themeChanged', theme: 'highContrastDark' })
  await openNoDraftReview(page, '', true)
  await page.getByText('Review instructions (optional)').click()
  await page.getByText('Advanced review options').click()
  await page.getByRole('checkbox', { name: 'Use chunked review mode as an advanced fallback' }).check()
  await page.getByRole('button', { name: 'Generate Review' }).click()
  await expect(toaster).toHaveAttribute('data-sonner-theme', 'dark')
  await expect(toast).toContainText('Chunked mode needs a loaded diff')
  await expect.poll(() => toaster.evaluate((element) => {
    const style = getComputedStyle(element)
    const background = style.getPropertyValue('--background').trim()
    return style.getPropertyValue('--error-bg').trim() === `hsl(${background})`
  })).toBe(true)
  await expect.poll(() => toast.evaluate((element) => {
    const toastStyle = getComputedStyle(element)
    const hostStyle = getComputedStyle(document.body)
    return toastStyle.backgroundColor === hostStyle.backgroundColor
      && toastStyle.borderColor === hostStyle.color
      && toastStyle.color === hostStyle.color
  })).toBe(true)
  await toast.hover()
  await expect(page).toHaveScreenshot('toast-host-high-contrast-dark.png')
})

test('selected review and chat remain usable in a narrow dark host', async ({ page }) => {
  await page.setViewportSize({ width: 400, height: 568 })
  await page.goto('/')
  await pushHostMessage(page, { type: 'themeChanged', theme: 'dark' })
  await openDraftReview(page)
  await page.getByRole('button', { name: 'Chat' }).click()

  const reviewPane = page.getByTestId('review-pane-shell')
  await expect(page.getByRole('region', { name: 'Chat', exact: true })).toBeVisible()
  await expect(page.getByRole('textbox', { name: 'Ask about this pull request' })).toBeInViewport({ ratio: 1 })
  await expect(reviewPane.getByText('Suggested: Comment')).toHaveCount(2)
  await expect(page.getByTestId('review-footer').getByRole('status')).toHaveText('Saved')
  await expect(page.getByRole('button', { name: 'Submit review…' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'More review actions' })).toBeVisible()
  expect((await page.getByTestId('review-footer').boundingBox())!.height).toBeLessThanOrEqual(48)
  expect((await page.getByTestId('review-scroll-body').boundingBox())!.height).toBeGreaterThanOrEqual(MIN_REVIEW_BODY_HEIGHT)
  await expectNoHorizontalOverflow(page, '[data-testid="review-pane-shell"]')
  await expect(page).toHaveScreenshot('selected-review-chat-narrow-dark.png')
  await expect(reviewPane).toHaveScreenshot('selected-review-pane-narrow-dark.png', REGION_SCREENSHOT)
})

test('wide review provides scannable file and finding navigation', async ({ page }) => {
  await page.setViewportSize({ width: 1_440, height: 900 })
  await page.goto('/')
  await pushHostMessage(page, { type: 'themeChanged', theme: 'dark' })
  await openFindingNavigationReview(page)

  await expect(page.getByRole('navigation', { name: 'Review navigation' })).toBeVisible()
  await expect(page).toHaveScreenshot('selected-review-files-wide-dark.png')

  await page.getByRole('button', { name: /Findings/ }).click()
  await expect(page.getByRole('list', { name: 'Anchored findings', exact: true })).toBeVisible()
  await expect(page.getByRole('list', { name: 'Unanchored findings' })).toBeVisible()
  await expect(page).toHaveScreenshot('selected-review-findings-wide-dark.png')
})

test('adaptive file navigation remains useful at wide-layout boundaries', async ({ page }) => {
  for (const width of [1_100, 2_133]) {
    await page.setViewportSize({ width, height: 900 })
    await page.goto('/')
    await pushHostMessage(page, { type: 'themeChanged', theme: 'dark' })
    await openFindingNavigationReview(page)

    const navigation = page.getByRole('navigation', { name: 'Review navigation' })
    const navigationWidth = await navigation.evaluate((element) => element.getBoundingClientRect().width)
    expect(navigationWidth).toBeGreaterThanOrEqual(280)
    expect(navigationWidth).toBeLessThanOrEqual(360)
    await expect(page).toHaveScreenshot(`selected-review-files-${width}-dark.png`)
  }
})

test('finding actions stay clear in a narrow review', async ({ page }) => {
  await page.setViewportSize({ width: 400, height: 568 })
  await page.goto('/')
  await pushHostMessage(page, { type: 'themeChanged', theme: 'dark' })
  await openFindingNavigationReview(page)

  const finding = page.locator('#diff-comment-0')
  const body = page.getByTestId('review-scroll-body')
  await expect(page.getByRole('navigation', { name: 'Review navigation' })).toBeVisible()
  await expect(finding).toBeAttached()
  await page.evaluate(() => document.fonts.ready)
  const alignFinding = () => body.evaluate((element) => {
    const finding = element.querySelector('#diff-comment-0')!
    const bounds = element.getBoundingClientRect()
    // scrollTop is pixel-quantized here; round toward visibility, never clip the row's last fraction.
    element.scrollTop = Math.max(0, Math.min(element.scrollHeight - element.clientHeight,
      Math.ceil(element.scrollTop + finding.getBoundingClientRect().bottom - bounds.bottom)))
    return Math.abs(finding.getBoundingClientRect().bottom - bounds.bottom)
  })
  await expect.poll(alignFinding).toBeLessThanOrEqual(1)
  const geometry = () => body.evaluate((element) => {
    const bounds = element.getBoundingClientRect()
    const finding = element.querySelector('#diff-comment-0')!.getBoundingClientRect()
    return { bottom: finding.bottom, top: finding.top, bodyBottom: bounds.bottom, bodyTop: bounds.top, scrollTop: element.scrollTop }
  })
  const aligned = await geometry()
  await expect.poll(geometry).toEqual(aligned)
  expect(aligned.top).toBeGreaterThanOrEqual(aligned.bodyTop)
  expect(Math.abs(aligned.bottom - aligned.bodyBottom)).toBeLessThanOrEqual(1)
  await expect(finding).toBeInViewport({ ratio: 1 })
  await expect(finding.getByRole('button', { name: 'Verify with AI' })).toBeInViewport({ ratio: 1 })
  await expect(finding.getByRole('button', { name: 'Suggest fix with AI' })).toBeInViewport({ ratio: 1 })
  await expectNoHorizontalOverflow(page, '[data-testid="review-scroll-body"]')
  const footer = page.getByTestId('review-footer')
  await expect(footer).toBeInViewport({ ratio: 1 })
  await expect(footer.getByRole('button', { name: 'More review actions' })).toBeVisible()
  expect(await footer.evaluate(element => !!element.closest('[data-testid="review-scroll-body"]'))).toBe(false)
  await expect(page).toHaveScreenshot('selected-review-finding-actions-narrow-dark.png')
})

test('Verify with AI keeps review, chat, and footer usable in a constrained viewport', async ({ page }) => {
  await page.setViewportSize({ width: 600, height: 500 })
  await page.goto('/')
  await openDraftReview(page)

  await page.getByRole('button', { name: 'Verify with AI' }).click()
  const expected = buildVerifyCommentPrompt(reviewComment, reviewDiff)
  await expect.poll(() => page.evaluate(() => {
    const fixture = (window as unknown as {
      __hostFixture: { outgoing: Array<Record<string, unknown>> }
    }).__hostFixture
    return fixture.outgoing.filter((message) => message.type === 'askClaude')
  })).toEqual([expect.objectContaining({
    protocolVersion: 1,
    type: 'askClaude',
    operationId: expect.any(String),
    context: expected.context,
    question: expected.question,
  })])

  const chat = page.getByRole('region', { name: 'Chat', exact: true })
  const body = page.getByTestId('review-scroll-body')
  const chatPanel = page.getByTestId('chat-panel')
  const savedStatus = page.getByTestId('review-footer').getByRole('status')
  await expect(chat).toBeVisible()
  await expect(body).toBeVisible()
  await expect(savedStatus).toBeVisible()
  await expect(page.getByRole('textbox', { name: 'Ask about this pull request' })).toBeVisible()
  await expect(page.getByTestId('chat-messages')).toContainText('Verify whether the draft review comment is supported by the pull-request evidence.')
  await expect(page.getByTestId('chat-messages')).toContainText('"verdict":"valid|invalid|unclear"')
  await expect(page.getByTestId('chat-messages')).toContainText('"evidence":["relative/path:line or symbol"]')
  await expect(page.getByTestId('chat-messages')).toContainText('"action":"keep|revise|delete"')
  await expect(chat).toContainText('Context: draft comment, diff excerpt, PR worktree (read-only)')

  const geometry = await Promise.all([body.boundingBox(), chatPanel.boundingBox(), savedStatus.boundingBox()])
  expect(geometry.every((box) => box !== null)).toBe(true)
  const [bodyBox, chatBox, savedBox] = geometry
  expect(bodyBox!.height).toBeGreaterThan(0)
  expect(bodyBox!.y + bodyBox!.height).toBeLessThanOrEqual(chatBox!.y + 1)
  expect(chatBox!.y + chatBox!.height).toBeLessThanOrEqual(savedBox!.y + 1)
  expect(savedBox!.y + savedBox!.height).toBeLessThanOrEqual(501)
})

test('wide tall Verify layout fills the viewport instead of collapsing to content height', async ({ page }) => {
  await page.setViewportSize({ width: 2_048, height: 1_180 })
  await page.goto('/')
  await openDraftReview(page)
  await page.getByRole('button', { name: 'Verify with AI' }).click()

  await expectViewportFilled(page)
  const prList = page.getByTestId('pr-list-shell').locator('nav')
  const reviewShell = page.getByTestId('review-pane-shell')
  const reviewContent = page.getByTestId('review-pane-content')
  const body = page.getByTestId('review-scroll-body')
  const savedStatus = reviewContent.getByText('Saved to GitHub')
  await expect(prList).toBeVisible()
  await expect(reviewContent).toBeVisible()
  await expect(savedStatus).toBeVisible()

  const geometry = await Promise.all([
    prList.boundingBox(),
    reviewShell.boundingBox(),
    reviewContent.boundingBox(),
    body.boundingBox(),
    savedStatus.boundingBox(),
  ])
  expect(geometry.every((box) => box !== null)).toBe(true)
  const [prListBox, reviewShellBox, reviewContentBox, bodyBox, savedBox] = geometry
  expect(prListBox!.height).toBe(1_180)
  expect(reviewShellBox!.height).toBe(1_180)
  expect(reviewContentBox!.height).toBe(1_180)
  expect(bodyBox!.height).toBeGreaterThan(600)
  expect(savedBox!.y).toBeGreaterThan(1_100)
  expect(savedBox!.y + savedBox!.height).toBeLessThanOrEqual(1_181)
})

test('narrow selected review keeps Save and submit controls reachable', async ({ page }) => {
  for (const width of [320, 400]) {
    await page.setViewportSize({ width, height: 568 })
    await page.goto('/')
    await openDraftReview(page)

    const footer = page.getByTestId('review-footer')
    expect((await footer.boundingBox())!.height).toBeLessThanOrEqual(48)
    for (const control of [
      footer.getByRole('status'),
      page.getByRole('button', { name: 'Submit review…' }),
      page.getByRole('button', { name: 'More review actions' }),
    ]) {
      const box = await control.boundingBox()
      expect(box).not.toBeNull()
      expect(box!.x).toBeGreaterThanOrEqual(0)
      expect(box!.x + box!.width).toBeLessThanOrEqual(width)
    }
  }
})

test('persisted tall chat leaves the review body usable in a short viewport', async ({ page }) => {
  await page.setViewportSize({ width: 600, height: 500 })
  await page.goto('/')
  await page.evaluate(() => localStorage.setItem('claude-reviews:chat-height', '600'))
  await page.reload()
  await openDraftReview(page)
  await page.getByRole('button', { name: 'Chat' }).click()

  await expect.poll(async () => (await page.getByTestId('review-scroll-body').boundingBox())?.height ?? 0)
    .toBeGreaterThanOrEqual(MIN_REVIEW_BODY_HEIGHT)
  const body = await page.getByTestId('review-scroll-body').boundingBox()
  const chat = await page.getByTestId('chat-panel').boundingBox()
  expect(body).not.toBeNull()
  expect(chat).not.toBeNull()
  expect(body!.height).toBeGreaterThanOrEqual(MIN_REVIEW_BODY_HEIGHT)
  expect(chat!.height).toBeGreaterThan(0)
})

test('Suggest fix with AI sends an example-fix prompt with focused diff context', async ({ page }) => {
  await page.goto('/')
  await openDraftReview(page)

  await page.getByRole('button', { name: 'Suggest fix with AI' }).click()
  const expected = buildExampleFixPrompt(reviewComment, reviewDiff)
  await expect.poll(() => page.evaluate(() => {
    const fixture = (window as unknown as {
      __hostFixture: { outgoing: Array<Record<string, unknown>> }
    }).__hostFixture
    return fixture.outgoing.filter((message) => message.type === 'askClaude')
  })).toEqual([expect.objectContaining({
    protocolVersion: 1,
    type: 'askClaude',
    operationId: expect.any(String),
    context: expected.context,
    question: expected.question,
  })])
})

test('clicking the gutter + button adds a new inline comment', async ({ page }) => {
  await page.goto('/')
  await openDraftReview(page)

  const gutterButton = page.getByRole('button', { name: 'Add comment on src/auth.ts, new line 1' })
  await expect(gutterButton).toBeVisible()
  await gutterButton.click()

  const commentBox = page.getByRole('textbox', { name: 'Comment on src/auth.ts, line 1' })
  await expect(commentBox).toBeVisible()
  await commentBox.fill('New inline note')
  await page.getByRole('button', { name: 'Add', exact: true }).click()

  await expect(page.getByText('New inline note')).toBeVisible()
})
