import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './a11y',
  retries: process.env.CI ? 2 : 0,
  fullyParallel: true,
  timeout: 60_000,
  use: {
    baseURL: 'http://127.0.0.1:4173',
    trace: 'on-first-retry',
  },
  webServer: {
    // Write to an isolated outDir under test-results/ so running Playwright a11y concurrently
    // with Gradle's :intellij-plugin:buildWebview never races on webview/dist.
    command: 'npx vite build --outDir test-results/a11y-dist && npx vite preview --outDir test-results/a11y-dist --host 127.0.0.1 --port 4173',
    url: 'http://127.0.0.1:4173',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
})

