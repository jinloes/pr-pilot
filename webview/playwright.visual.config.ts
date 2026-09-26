import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './visual',
  timeout: 60_000,
  // Whole-page tolerance absorbs antialiasing only; it is intentionally loose enough that copy and
  // hierarchy changes can pass. Text-critical panes therefore add locator-level screenshots with a
  // tighter ratio plus explicit text assertions in the spec. Never raise this to make a baseline pass.
  expect: { toHaveScreenshot: { animations: 'disabled', maxDiffPixelRatio: 0.01 } },
  use: {
    ...devices['Desktop Chrome'],
    baseURL: 'http://127.0.0.1:4175',
    colorScheme: 'dark',
    locale: 'en-US',
    timezoneId: 'UTC',
  },
  webServer: {
    command: 'npm run build && npm run preview -- --host 127.0.0.1 --port 4175',
    url: 'http://127.0.0.1:4175',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
  snapshotPathTemplate: '{testDir}/__snapshots__/{testFilePath}/{arg}{ext}',
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
