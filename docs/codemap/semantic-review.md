# Code map: semantic-review lifecycle

Part of the [code map](../../CODEMAP.md), which holds the task-to-code index and cross-module paths.
The entry points span modules, so paths start at the module directory and `...` stands for
`src/main/java/com/jinloes/prpilot`.

- `intellij-plugin/.../services/SemanticSnapshotService.java` — strict production platform adapter
  plus package-private raw-input seam. `SemanticSnapshotServiceTest` drives real service
  transitions with queued native observations; `SemanticMcpToolsProviderTest` exercises scheduled
  CAPTURE/VERIFY and cancellation. These tests do not replace installed SDK/import evidence.
- `review-engine/.../review/SemanticReviewService.java` — prepare, collect, query bracketing,
  engine-owned execution authority, and final validation. `SemanticReviewServiceTest` covers
  collector negatives; `ReviewPipelineAuthorityTest` (shared fixtures in
  `ReviewPipelineTestSupport`) drives actual provider adapters with fake IO.
- `review-engine/.../review/IjctlClient.java` — fixed read-only allowlist, schemas and limits.
- `review-engine/.../review/SemanticSkillBundle.java` and `src/main/resources/semantic-skills/`
  — complete pinned instructions, provenance and license; never project-selected instructions.
- `review-engine/.../review/SemanticWorktreeStore.java` — durable retained identity and OS leases;
  `GitWorktreeService` protects retained trees from ordinary cleanup and non-force removal.
- `review-engine/.../engine/ReviewEngineApi.java`, `ReviewSessionService.java` and
  `sidecar/.../StdioJsonRpcServerAsyncReviewHandlers.java` — prepare/list/cleanup capability and
  lifetime boundary.
- `intellij-plugin/.../ui/WebviewPanel.java`, `DeepReviewController.java`,
  `services/IntellijClaudeService.java` and `vscode-extension/src/{extension,deepReview,sidecar}.ts`
  — actual correlated host callbacks; selection/head/settings/operation fencing; provider ownership
  until final delivery.
- `webview/src/components/ReviewPane/` (`DeepReviewSetup`, `ReviewOverrides`, `ReviewPane`,
  `useReviewController`)
  — opt-in, manual pause, Continue/Retry/Cancel/ordinary fallback and retained maintenance.
  `ReviewPane.assisted.component.test.tsx` (the IntelliJ-assisted workflow),
  `ReviewPane.component.test.tsx`, `ReviewPane.submission.component.test.tsx` (shared helpers in
  `reviewPaneTestUtils.tsx`) and `a11y/app.a11y.spec.ts`/`review.a11y.spec.ts` (shared helpers in
  `a11y/a11yHelpers.ts`) cover the shared interaction flow. The opt-in controls render only when
  `draftLoaded.intellijAssistedEnabled` is `true`, set from the default-off
  `PluginSettings.experimentalIntellijAssistedReview` /
  `pr-pilot.experimentalIntellijAssistedReview`; both hosts reject assisted requests while it is
  off. PR-agnostic retained-worktree maintenance renders only when the latest
  `prListLoaded.intellijAssistedEnabled` is `true` or an assisted setup is active.
- `WebviewPanelDeepReviewTest` and `vscode-extension/test/deepReview.test.ts` exercise actual
  bridge callbacks with external effects replaced. They do not stand in for installed-host/provider
  execution.
- `webview/visual/app.visual.spec.ts` — ordinary/deep-default assertions and deterministic
  narrow-finding placement. All committed baselines were regenerated and reviewed in the canonical
  container, and text-critical states also have locator-level snapshots.
