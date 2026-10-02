import { expect, type Page } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { exampleDiff, examplePr, pushHostMessage } from './hostFixture'

export async function expectNoViolations(page: import('@playwright/test').Page, include?: string) {
  const builder = new AxeBuilder({ page })
  const results = await (include ? builder.include(include) : builder).analyze()
  expect(results.violations, results.violations.map((v) => `${v.id} (${v.impact}): ${v.description} [${v.nodes.length}]`).join('\n')).toEqual([])
}

export async function openGeneratingReview(page: Page) {
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

export type HostThemeName = 'light' | 'dark' | 'highContrastLight' | 'highContrastDark'

export const longDiff = Array.from({ length: 6 }, (_, index) => [
  `diff --git a/src/module-${index}.ts b/src/module-${index}.ts`,
  `--- a/src/module-${index}.ts`,
  `+++ b/src/module-${index}.ts`,
  '@@ -1,1 +1,3 @@',
  ` export const existing${index} = true`,
  `+export const added${index} = computeAVeryLongIdentifierNameThatMustScrollWithinTheDiff(${index})`,
  `+export const another${index} = true`,
].join('\n')).join('\n') + '\n'

export function coverageTrailer(omitted: number, paths: string[]): string {
  return `[pr-pilot:diff-coverage] omitted=${omitted} listed=${paths.length} budget=250000 scan=complete\n`
    + paths.map((path) => `[pr-pilot:omitted] ${path}\n`).join('')
}

export async function selectExamplePr(page: Page, theme: HostThemeName = 'dark') {
  await page.evaluate(() => localStorage.setItem('pr-pilot:first-success-coach-shown', '1'))
  await pushHostMessage(page, { type: 'themeChanged', theme })
  await pushHostMessage(page, { type: 'prListLoaded', prs: [examplePr] })
  await page.getByRole('button', { name: /Improve authentication/ }).click()
}

export async function noHorizontalOverflow(page: Page, testId: string) {
  return page.getByTestId(testId).evaluate((element) => element.scrollWidth <= element.clientWidth)
}

export async function openDraftReview(page: Page, width: number, height: number, theme: HostThemeName, result?: object) {
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

export async function waitForFocusedFindingToSettle(page: Page) {
  await expect(page.locator('.diff-comment-row--focused')).toHaveCount(1)
  await page.waitForFunction(() =>
    (document.querySelector('.diff-comment-row--focused .diff-comment-cell')?.getAnimations().length ?? 0) === 0)
}

export async function expectNoContrastViolations(page: Page, include?: string) {
  const builder = new AxeBuilder({ page }).withRules(['color-contrast'])
  const results = await (include ? builder.include(include) : builder).analyze()
  expect(
    results.violations.map((violation) => violation.nodes.map((node) => node.target)),
    results.violations.flatMap((violation) => violation.nodes.map((node) => node.failureSummary)).join('\n'),
  ).toEqual([])
}
