# Webview Accessibility Gate

Temporary accessibility gate for the `webview` package until ESLint 10-compatible JSX a11y linting is available.

## What it checks

- Starts the built webview with `vite preview`
- Opens the app in Chromium via Playwright
- Runs axe-core checks on the review pane shell (`data-testid="review-pane-shell"`)
- Fails on `serious` and `critical` violations

## Run locally

```bash
cd /Users/jinloes/workspaces/pr-pilot/webview
npm install
npm run a11y:install
npm run test:a11y
```

## CI usage

Use these steps in CI for a deterministic run:

```bash
cd /Users/jinloes/workspaces/pr-pilot/webview
npm ci
npx playwright install --with-deps chromium
npm run test:a11y
```


