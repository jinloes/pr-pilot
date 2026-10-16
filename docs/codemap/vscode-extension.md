# Code map: `vscode-extension/`

Part of the [code map](../../CODEMAP.md), which holds the task-to-code index and cross-module paths.
Paths are relative to `vscode-extension/`.

VS Code host integration. All GitHub and review generation routes through the Java sidecar.

- `src/extension.ts` - Activation, commands, webview bridge switch (`setupMessageBridge`), and host
  lifecycle.
- `src/prHandlers.ts` and `reviewHandlers.ts` - Bridge handlers for PR
  selection/refresh/instructions/deep review, and for review generation/drafts/submission/chat.
  `extensionTypes.ts` holds shared state types.
- `src/worktree.ts` - Worktree lifecycle (`resolveWorkingDir`/`clearWorktree`).
- `src/notificationPoller.ts` - `PRNotificationPoller`.
- `src/copilotModel.ts` - `selectCopilotModel` command.
- `src/draftRecovery.ts` - Token-free `globalState` snapshots used until GitHub confirms a draft
  save.
- `src/sidecar.ts` - Re-export facade for the mandatory JSON-RPC client (`SidecarClient`).
- `src/sidecarGitHubClient.ts` and `sidecarReviewClient.ts` - Client methods per engine.
- `src/sidecarTransport.ts` - Process lifecycle, recovery, and notification dispatch.
- `src/sidecarProtocol.ts`, `sidecarTypes.ts`, `sidecarFraming.ts`, `sidecarJar.ts` - Capability
  declarations and parsers, DTOs, framing, and JAR resolution.
- `src/models.ts` - Host-neutral PR/review view models.
- `src/claude.ts` - Claude preflight and remaining mirrored prompt helpers.
- `src/copilot.ts` - Copilot model discovery and binary preflight.
- `src/providerSetup.ts` - Conservative Claude authentication probe classification for onboarding.
- `src/settings.ts` and `settingsView.ts` - Settings controller, and the settings state, model
  options, and pure normalization/validation helpers. `settingsViewHtml.ts` renders the webview
  document from `settingsViewStyles.ts` and the inline script that `settingsViewScript.ts`
  assembles from `settingsViewScriptHelpers.ts` (state, rendering, validation) and
  `settingsViewScriptEvents.ts` (event wiring and host messages). Tests: `settingsView.test.ts`,
  `settingsViewScript.test.ts`.
- `src/reviewGuidanceProfiles.ts` - Guidance-profile validation and resolution.
- `src/repositoryInstructions.ts` - Remembered per-repository review instructions
  (`pr-pilot.repositoryReviewInstructions`): key normalization, limits, settings-view update
  validation, and composition into `customInstructions`.
- `src/operationCorrelation.ts` - Async selection/generation/chat invalidation.
- `src/notifications.ts` - Notification labeling, deduplication, and merge rules.
- `src/hostTheme.ts` - Theme classification.
- `src/userFacingError.ts` - User-actionable error mapping.
- `src/workspace.ts` - Workspace and development-target resolution.
- `src/webviewAssets.ts` - Packaged assets with development fallback.
- `shared/user-facing-errors.yaml` - Shared host error templates.
- `scripts/stage-webview.mjs` - Packages the built shared webview.
- `scripts/stage-sidecar.mjs` - Packages the sidecar JAR.
- `scripts/smoke-sidecar.mjs` - Verifies initialize protocol and capabilities.
- `scripts/run-coverage.mjs` - Runs the unit tests under c8 and writes `coverage/lcov.info` with
  repository-relative paths for diff-cover (`test:coverage`).
- `.vscode-test.mjs`, `integration-test/smoke.test.js` - Extension-host smoke test
  (`test:integration`) on the minimum supported VS Code: activation, contributed command
  registration, opening the main UI, and the staged sidecar's required capabilities.
- `test/wireCatalog.test.ts` - Enforces engine declarations against the TypeScript client.
- `test/hostHandlers.test.ts` - Runs the compiled `extension.ts` bridge handlers with VS Code, the
  providers, and the sidecar replaced (PR-list payload fields and `cancelChat`).
- `test/sidecar.test.ts` - `SidecarClient` JAR resolution, lifecycle, and recovery;
  `sidecarContext.test.ts` covers context and worktree reads. Both drive the fake sidecar process
  in `test/sidecarHarness.ts`. `sidecarFraming.test.ts` and `sidecarProtocol.test.ts` cover framing
  and result parsers.
- Other tests in `test/` mirror extension helpers and host behavior.
