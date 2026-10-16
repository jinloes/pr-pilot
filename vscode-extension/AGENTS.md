# vscode-extension agent guide

Rules for work under `vscode-extension/`. The root `AGENTS.md` still applies, including its
hand-mirrored logic: most `MIRRORS` entries in `scripts/host-mirrors.mjs` name a file in this
directory, and `node scripts/verify.mjs` names the JVM file to check when you change one.

- The Java sidecar is the only transport. Never invoke `gh`, `git`, or a review CLI from the
  extension, and never re-implement engine logic in TypeScript; add or call an engine capability.
- Every request wire method needs a client method in `src/sidecarGitHubClient.ts` or
  `src/sidecarReviewClient.ts` (`test/wireCatalog.test.ts` enforces it); `src/sidecar.ts` is only the
  re-export facade. Notification shapes (`reviews/status`, `reviews/chunk`, `reviews/chatChunk`) are not
  enforced, so update the `sidecarTransport.ts` dispatch by hand when the JVM side changes them.
- A new setting needs a `package.json` `contributes.configuration` entry plus its reader in
  `src/settings.ts`/`src/extension.ts`, mirroring `PluginSettings` in `intellij-plugin`.
- `src/extension.ts` keeps activation and the bridge switch (`setupMessageBridge`); put handler
  logic in `prHandlers.ts`/`reviewHandlers.ts` or a feature module, not in `extension.ts`.
- Tests live in `test/*.test.ts` and run on `node:test` after `tsc -p tsconfig.test.json`; the same
  runner (`scripts/run-unit-tests.mjs`) also executes every root `../scripts/*.test.mjs`.
  `npm run test:coverage` runs that runner under c8.
- `integration-test/` holds the extension-host smoke (`npm run test:integration`). It runs the
  `engines.vscode` minimum (`.vscode-test.mjs`), so move both together. Keep the declared
  `@types/vscode` range at or below that floor: vsce refuses to package when it is newer.
- vsce is pinned inside the `vsce` script's `npm exec --package=@vscode/vsce@<version>` string,
  which Dependabot cannot see; bump it there by hand.
- Do not add `eslint-disable` comments without a `--` explanation.
