import { test, expect } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

function blockingViolations(results: Awaited<ReturnType<AxeBuilder['analyze']>>) {
  return results.violations.filter((violation) => violation.impact === 'critical' || violation.impact === 'serious')
}

test('review pane shell has no serious or critical accessibility violations', async ({ page }) => {
  await page.goto('/')
  await page.waitForLoadState('networkidle')

  const results = await new AxeBuilder({ page }).include('[data-testid="review-pane-shell"]').analyze()
  const violations = blockingViolations(results)

  expect(
    violations,
    violations
      .map((v) => `${v.id} (${v.impact}): ${v.description} [${v.nodes.length} node(s)]`)
      .join('\n'),
  ).toEqual([])
})


