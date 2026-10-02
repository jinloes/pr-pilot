import { test, expect } from '@playwright/test'
import { exampleDiff, examplePr, installHostFixture, latestHostRequest, pushHostMessage } from './hostFixture'
import { pseudoLocalize } from '../src/i18n/format'
import {
  coverageTrailer,
  expectNoContrastViolations,
  expectNoViolations,
  longDiff,
  noHorizontalOverflow,
  openDraftReview,
  selectExamplePr,
  waitForFocusedFindingToSettle,
} from './a11yHelpers'

test.beforeEach(async ({ page }) => {
  await installHostFixture(page)
  await page.goto('/')
  await page.waitForLoadState('networkidle')
})

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

for (const theme of ['light', 'dark', 'highContrastDark'] as const) {
  test(`remembered repository instructions are labelled, keyboard-operable, and announce saves in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width: 400, height: 900 })
    await selectExamplePr(page, theme)
    await pushHostMessage(page, {
      type: 'draftLoaded', prKey: 'acme/platform#42', prState: 'NO_DRAFT', diff: exampleDiff,
      providerReadiness: { provider: 'claude', available: true, detail: 'Ready' },
      repositoryInstructions: 'API-only PRs precede the service PR.',
    })
    const pane = page.getByTestId('review-pane-shell')
    await expect(pane.getByText('Remembered for repository')).toBeVisible()
    await pane.locator('summary').filter({ hasText: 'Review instructions (optional)' }).press('Enter')

    const field = pane.getByRole('textbox', { name: 'Remembered instructions for acme/platform' })
    await expect(field).toHaveValue('API-only PRs precede the service PR.')
    await expect(field).toHaveAccessibleDescription(/Applied to every review of acme\/platform/)
    const remember = pane.getByRole('button', { name: 'Remember for this repository' })
    await expect(remember).toBeDisabled()
    expect(await noHorizontalOverflow(page, 'review-scroll-body')).toBe(true)
    await expectNoViolations(page, '[data-testid="repository-instructions"]')

    await field.focus()
    await field.evaluate((element: HTMLTextAreaElement) => {
      element.setSelectionRange(element.value.length, element.value.length)
    })
    await page.keyboard.type(' Skip handler validation requests.')
    await page.keyboard.press('Tab')
    await expect(remember).toBeFocused()
    await page.keyboard.press('Enter')
    const save = await latestHostRequest(page, 'saveRepositoryInstructions')
    expect(save).toMatchObject({ owner: 'acme', repo: 'platform', number: 42,
      instructions: 'API-only PRs precede the service PR. Skip handler validation requests.' })

    await pushHostMessage(page, { type: 'repositoryInstructionsSaved', prKey: 'acme/platform#42',
      instructions: 'API-only PRs precede the service PR. Skip handler validation requests.' })
    await expect(pane.getByRole('status').filter({ hasText: 'Remembered for acme/platform.' })).toBeVisible()
    await pushHostMessage(page, { type: 'repositoryInstructionsSaveError', prKey: 'acme/platform#42',
      message: 'Could not save PR Pilot settings. Try again.' })
    await expect(pane.getByRole('status').filter({ hasText: 'Could not save PR Pilot settings.' })).toBeVisible()
    await expectNoViolations(page, '[data-testid="repository-instructions"]')
  })
}
