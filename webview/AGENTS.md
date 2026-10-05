# webview agent guide

Rules for work under `webview/`. The root `AGENTS.md` still applies.

- The webview is host-neutral UI shared by IntelliJ (JCEF) and VS Code. Never import host APIs here;
  talk to hosts only through `src/bridge/`.
- `src/bridge/types.ts` is the host↔UI contract. Any schema change must update
  `intellij-plugin/.../ui/WebviewBridgeMessages.java` + `WebviewBridgeHandler.java` and the handlers
  in `vscode-extension/src/` (`extension.ts` `setupMessageBridge`, `prHandlers.ts`,
  `reviewHandlers.ts`) in the same change.
- Pure logic goes in `src/lib/` with a `*.test.ts` beside it (run by `tsx --test`); component tests
  are `*.component.test.ts(x)` (run by Vitest).
- Do not add `eslint-disable` comments without a `--` explanation.

## Checks

`node scripts/verify.mjs` (from the repo root) runs lint, typecheck, unit, and a11y tests for webview
changes. Visual snapshots are canonical Ubuntu/Chromium artifacts and must not be verified natively
on other operating systems. Run them with the Playwright version pinned by `package-lock.json`; for
the current lockfile, from the repo root:

```bash
mkdir -p /tmp/pr-pilot-playwright-node-modules /tmp/pr-pilot-playwright-npm-cache
docker run --rm --ipc=host \
  --user "$(id -u):$(id -g)" \
  -e HOME=/tmp -e npm_config_cache=/tmp/npm-cache -e CI=1 \
  -v "$PWD:/work" \
  -v /tmp/pr-pilot-playwright-node-modules:/work/webview/node_modules \
  -v /tmp/pr-pilot-playwright-npm-cache:/tmp/npm-cache \
  -w /work/webview \
  mcr.microsoft.com/playwright:v1.61.1-noble \
  bash -lc "npm ci && npm run test:visual -- --reporter=line"
```

For an intentional baseline regeneration, use the same command and image but change the final
command to `npm ci && npm run test:visual -- --update-snapshots --reporter=line`. Review every image
diff, then rerun the non-update command before accepting the baseline. Never raise visual tolerances
merely to make a changed baseline pass.
