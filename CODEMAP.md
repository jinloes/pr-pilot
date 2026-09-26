# PR Pilot Code Map

Lookup guide for implementation work. Read this file when locating code or tests; read
`ARCHITECTURE.md` only when the change affects system boundaries, persistence, or design constraints.

## Task-to-code index

| Task | Start here | Follow through | Primary tests |
|---|---|---|---|
| Inspect internal source coverage (not readiness) | `model/SourceInventory.java`, `review/SourceInventoryClient.java` | `SourceInventoryFiles.java`, IntelliJ `SourceInventoryService.java` and `SourceInventoryMcpProvider.java` | `SourceInventoryTest`, `SourceInventoryFilesTest`, `SourceInventoryClientTest`, `SourceInventoryServiceTest`, `SourceInventoryMcpProviderTest`; manual protocol recipe in `docs/intellij-assisted-review.md` |
| Change review generation or prompts | `review-engine/.../ClaudeService.java`, `CopilotService.java` | `ReviewEngineApi.java`, `ReviewSessionService.java`, host request wiring | Matching `review-engine` service tests; prompt mirrors listed in `AGENTS.md` |
| Add an engine capability | `GitHubEngineApi.java` or `ReviewEngineApi.java` | `StdioJsonRpcServer.java`, `SidecarBootstrapService.java`, `vscode-extension/src/sidecar.ts` | `EngineCapabilityCoverageTest.java`, `wireCatalog.test.ts` |
| Change PR discovery, metadata, diff, or draft mutations | `github-engine/.../sidecar/pr/` | `GitHubEngine.java`, both host bridges, shared webview messages | Matching `github-engine` service test plus host bridge tests |
| Change the shared review UI | `webview/src/App.tsx`, `webview/src/components/` | `webview/src/bridge/types.ts`, both host bridge handlers | Component tests plus accessibility/visual suites when behavior or layout changes |
| Change IntelliJ host behavior | `intellij-plugin/.../ui/WebviewPanel.java` | `services/`, `settings/`, shared webview bridge | Matching IntelliJ JUnit tests |
| Change VS Code host behavior | `vscode-extension/src/extension.ts` | `sidecar.ts`, `settings.ts`, `settingsView.ts` | `vscode-extension/test/` |
| Change settings | `intellij-plugin/.../settings/PluginSettings.java` | Both settings UIs, `vscode-extension/package.json`, host readers | IntelliJ settings tests and VS Code settings tests |
| Change notifications | `intellij-plugin/.../PRNotificationService.java` | `vscode-extension/src/notifications.ts`, both host lifecycle entry points | Notification tests in both hosts |
| Change local draft/index persistence | `PendingReviewIndex.java`, `DraftRecoveryStore.java`, `SeenPRSet.java`, `vscode-extension/src/draftRecovery.ts` | Both host lifecycle callers; persistence contract in `ARCHITECTURE.md` | Matching IntelliJ and VS Code service tests |
| Change packaging or releases | `.github/workflows/`, module build files | VS Code staging scripts, root Gradle configuration | CI workflow commands and sidecar smoke test |
| Select a local IntelliJ sandbox without changing the compile SDK | `gradle/intellij-sandbox.gradle`, `intellij-plugin/build.gradle` | `runIdeLocal`, `printSandboxIdeSelection` | `gradle/intellij-sandbox-tests.gradle` / `:intellij-plugin:testSandboxIdeSelection` |

Paths below omit `src/main/java/com/jinloes/prpilot/` and equivalent test roots where the module
context makes them unambiguous.

## Repository map

### Root and automation

- `README.md` - User setup, development, checks, and release flow.
- `docs/intellij-assisted-review.md` - Experimental IntelliJ-assisted review, native readiness and source-inventory protocol.
- `AGENTS.md` - Agent workflow, testing rules, and cross-host obligations.
- `ARCHITECTURE.md` - Stable design constraints, settings persistence, and local data.
- `diagrams/` - Mermaid architecture and PR review-generation sequence diagrams.
- `.github/workflows/ci.yml` - Push/PR checks, Java 17 sidecar smoke test, and packaged-VSIX assertion.
- `.github/workflows/release.yml` - Tag-driven IntelliJ ZIP and VSIX GitHub releases.
- `scripts/portable-process.mjs` and `run-gradle.mjs` - Shell-free npm and Gradle wrapper
  invocation used by portable packaging/tests and targeted host CI.
- `gradle/intellij-sandbox.gradle` - Lazy installed-IDE selection and separate `runIdeLocal`
  platform/runtime/sandbox wiring; compilation and ordinary `runIde` stay pinned.
- `gradle/intellij-sandbox-tests.gradle` - Temporary metadata fixtures exercising the production
  selector plus launch-free pinned/custom configuration assertions, attached to check.

### `diagrams/`

- `README.md` - Diagram index and maintenance guidance.
- `architecture.md` - Host, transport, engine, external-system, and persistence boundaries.
- `review-generation-sequence.md` - End-to-end PR selection and review-generation sequence.

### `core/`

Plain Java 17 shared models with no host dependencies.

- `model/PullRequest.java` - Immutable PR identity, metadata, and review-freshness state.
- `model/ReviewStatus.java` - Authenticated-user review freshness (`UNREVIEWED`, `REVIEWED`,
  `UPDATED_SINCE_REVIEW`, or `UNAVAILABLE`).
- `model/ReviewResult.java` - Review summary, verdict, and line comments.
- `model/LineComment.java` - Inline comment anchor and quality metadata.
- `model/ChatMessage.java` - Immutable chat role and content.
- `model/PRReviewRequest.java` - Immutable review-generation parameter object; its builder strips a
  diff-coverage trailer from the diff into a never-null `diffCoverage`.
- `model/DiffCoverage.java` - Engine-authored diff-coverage trailer: omitted/listed counts, budget,
  scan completeness, up to 200 omitted paths, strict end-anchored `split`, and escaped prompt text.
  Pinned with its webview mirror by `core/src/test/resources/diff-coverage/trailer.golden.txt`.
- `model/ReviewProvider.java` - Claude/Copilot provider enum.
- `model/SourceInventory.java` - Strict required/nullable wire shapes, canonical identities,
  source manifest digest and shared protocol limits; no READY state.
- Tests: `core/src/test/java/com/jinloes/prpilot/`.

### `review-engine/`

Host-neutral provider invocation, prompt construction, review parsing, worktrees, and repository
guidance.

- `engine/ReviewEngineApi.java` - Complete review capability surface and JSON-RPC wire-name map.
- `engine/ReviewSessionService.java` - Provider dispatch and operation-scoped cancellation.
- `review/ClaudeService.java` - Claude CLI execution, canonical review/chat prompts, and review category parsing including compatibility findings.
- `review/CopilotService.java` - Copilot SDK execution with the same review API.
- `review/ChunkedReviewService.java` - Shared diff batching, contract-index generation, and mandatory global reconciliation.
- `review/ReviewPipelineService.java` - Shared primary/chunked orchestration, bounded supervision,
  final critique, cancellation checkpoints, fallback behavior, and final CI suppression.
- `review/InspectionManifest.java`, `ReviewPassParser.java`, `InspectionLedger.java`, and
  `EvidenceRef.java` - Stable changed targets plus validated inspection/evidence accounting.
- `review/ReviewCoverageAnalyzer.java`, `CoverageGap.java`, `ReviewSupervisorPrompts.java`, and
  `FollowUpDirective.java` - High-risk gap detection and bounded follow-up selection.
- `review/ReviewAnchorValidator.java` and `ReviewResultMerger.java` - Changed-line filtering and
  baseline/follow-up deduplication.
- `review/CancellationToken.java` - Shared cancellation state.
- `review/BoundedProcessRunner.java` - Bounded subprocess lifecycle and output draining;
  opt-in owned-tree termination/waiting and separate stderr rejection for inventory launches.
- `review/SourceInventoryFiles.java` - Secure no-follow full-leaf enumeration and bounded
  source/Git-object hashing; unavailable secure traversal blocks.
- `review/SourceInventoryClient.java` - Explicit trusted `Launch`, public `collect(root, expectedHead)`
  parent launcher and `--worker`/`--capture-settings` main; fixed ijctl transport, strict
  v2 native/worker envelopes, physical identity and settings fingerprints,
  independent physical/model reconciliation and repeated Git/source hashing.
  Not exposed over engine RPC or connected to review generation.
- `:review-engine:sourceInventoryWorkerJar` - Minimal plain runnable Jar at
  `review-engine/build/libs/pr-pilot-source-inventory-worker.jar`; inventory/runner plus Jackson only.
  Client and actual SDK-wrapper tests depend on this packaged artifact, not a worker test classpath.
- `review/SemanticRuntime.java` - Owner-controlled schema-1 launch configuration, Node/ijctl
  version enforcement, classloader-resource digest verification and owned worker extraction.
  `SemanticRuntimeTest` exercises open/close, forked settings, unsupported macOS JDK17,
  ordinary JAR and physically nested resource streams; actual installed Boot loading is separate.
- `review/CopilotModelDiscovery.java` - Copilot model discovery from the live account catalog (help-config fallback), cached with refresh-on-open.
- `review/GitWorktreeService.java` - Temporary PR-head worktree lifecycle.
- `review/RepoGuidelinesReader.java` - Bounded repository-guidance discovery.
- `review/BinaryLocator.java` - Provider binary-path probing.
- `review/ProviderSetupProbe.java` - Bounded provider authentication readiness for onboarding.
- `review/stream/` - Jackson DTOs for Claude stream-json events.
- Tests: `review-engine/src/test/java/com/jinloes/prpilot/`.

### `github-engine/`

Host-neutral GitHub, repository detection, PR context, and draft-review behavior. IDE hosts never
receive GitHub tokens.

- `engine/GitHubEngineApi.java` - Complete GitHub capability surface and JSON-RPC wire-name map.
- `engine/GitHubEngine.java` - Composition root delegating to GitHub services.
- `sidecar/github/GitHubAuthService.java` - `gh` and GitHub API authentication checks.
- `sidecar/github/GitHubApiBase.java` - Validated GitHub.com/GHES REST and GraphQL endpoints.
- `sidecar/github/GitHubHttpClient.java` - Shared authenticated GET/POST transport with the default
  retry policy and optional single-attempt deadlines.
- `sidecar/github/CheckAuthResult.java` - Stable authentication diagnosis.
- `sidecar/pr/PrSearchQueryService.java` - Normalized PR search construction.
- `sidecar/pr/PrListService.java` - Authenticated PR search, truncation, and bounded freshness
  enrichment.
- `sidecar/pr/PrReviewStatusService.java` - Viewer lookup plus one GraphQL freshness query for at
  most 50 list results.
- `sidecar/pr/PrDetailService.java` - PR metadata and worktree-head lookup.
- `sidecar/pr/PrDiffService.java` - Whole-file, UTF-8-safe review/validation diff bounding
  (smallest-first, 250 KB per file, coverage trailer) and HTTP 406 as `diff_too_large`.
- `sidecar/pr/DraftReviewService.java` - Pending-review lookup and decoding.
- `sidecar/pr/DraftReviewCodec.java` - PR Pilot review metadata encoding/decoding, and `visibleBody`, which strips hidden metadata from a pending body before publishing.
- `sidecar/pr/DraftReviewMutationService.java` - Save, submit, and delete orchestration; `submit` reads the pending review and publishes the reviewer's text plus its visible sections (`composeSubmitBody`).
- `sidecar/pr/PrSupplementalService.java` - Raw search, starred repositories, and prompt context.
- `sidecar/pr/*Result.java` and DTOs - Token-free engine outcomes.
- `sidecar/repo/RepoDetector.java` - Repository detection orchestration.
- `sidecar/repo/GitDirectoryResolver.java` - Git metadata/worktree resolution.
- `sidecar/repo/GitConfigOriginReader.java` - Origin URL reading.
- `sidecar/repo/RemoteUrlParser.java` - HTTPS/SSH/SCP owner/repository parsing.
- Tests: `github-engine/src/test/java/com/jinloes/prpilot/sidecar/`, mirroring service packages.

### `sidecar/`

Thin Java 17, non-web Spring Boot stdio JSON-RPC adapter used only by VS Code.

- `sidecar/PrPilotSidecarApplication.java` - Process entry point and stdio lifecycle.
- `sidecar/SidecarConfiguration.java` - Engine composition.
- `sidecar/StdioFrameCodec.java` - Bounded Content-Length UTF-8 framing.
- `sidecar/StdioJsonRpcServer.java` - Validation, dispatch, errors, and async notifications.
- `sidecar/SidecarBootstrapService.java` - Initialize response and advertised capability groups.
- `src/main/resources/logback-spring.xml` - Stderr logging; stdout remains protocol-only.
- `EngineCapabilityCoverageTest.java` - Enforces declaration, registration, and advertisement parity.
- Other tests mirror the frame codec and RPC server.

### `intellij-plugin/`

IntelliJ host integration. Depends directly on `core`, `github-engine`, and `review-engine`.

- `services/IntellijGitHubService.java` - IntelliJ-facing GitHub engine adapter.
- `services/IntellijClaudeService.java` - Provider adapter with pooled I/O and EDT callbacks.
- `services/SourceInventoryService.java` - Project-bound native roots/membership/epochs,
  one expiring discovery slot, independent VFS traversal/hash verification (no disk verification)
  and a narrow algorithm test seam.
- `services/SourceInventoryMcpProvider.java` - Optional MCP pooled coroutine bridge,
  strict request decoder and fail-closed 262 descriptor/category ABI adapter.
- `services/SemanticSnapshotService.java` - Native STATUS/CAPTURE/VERIFY, pre-armed paired-import
  baselines, settings document/VFS/list invalidation and bounded PSI declaration limitations.
- `services/SemanticMcpToolsProvider.java` - Strict snapshot decoder using the shared
  native-project/coroutine cancellation dispatcher and optional 262 ABI boundary.
- `SemanticSnapshotServiceTest` and `SemanticMcpToolsProviderTest` - Real service rejection paths,
  subscribed document/reload/disposal callbacks, queued tool dispatch/cancellation and model
  limitations. These bounded tests do not claim a live successful import or ready capture.
- `src/main/resources/META-INF/prpilot-mcp.xml` - Optional project service and MCP provider
  registration; absent MCP does not load the inventory integration.
- `services/UserFacingErrors.java` - Actionable host error copy.
- `services/PendingReviewIndex.java` - Saved-draft index.
- `services/DraftRecoveryStore.java` - Token-free local recovery snapshots for interrupted draft replacement.
- `services/PendingReviewIndexNotifications.java` - Corrupt-index warning and quarantine action.
- `services/SeenPRSet.java` - Notification deduplication state.
- `services/PRNotificationService.java` - PR polling, source labels, and merge behavior.
- `services/PRNotificationStartup.java` - Notification lifecycle entry point.
- `settings/PluginSettings.java` - Persisted settings model.
- `settings/RepositoryReviewInstructions.java` - Remembered per-repository review instructions: key normalization, limits, and composition into `customInstructions` (mirrors `vscode-extension/src/repositoryInstructions.ts`).
- `settings/PluginSettingsComponent.java` - Provider-aware settings UI.
- `settings/PluginSettingsConfigurable.java` - Settings lifecycle integration.
- `settings/GithubBaseUrlValidator.java` - HTTPS GitHub-origin normalization.
- `ui/PRToolWindowFactory.java` - Tool-window entry point; title actions (Pop Out, Settings) and the gear-menu "Reload PR Pilot View" action, built by package-private helpers covered by `PRToolWindowFactoryTest`.
- `ui/WebviewPanel.java` - JCEF host and Java/webview bridge.
- `ui/HostThemeClassifier.java` - Host theme normalization.
- `ui/ReviewMapper.java` and `WebviewDtos.java` - Core-to-bridge DTO mapping.
- Tests: `intellij-plugin/src/test/java/com/jinloes/prpilot/`, mirroring production packages.

### `webview/`

Shared Vite/React/TypeScript UI used by both IDE hosts.

- `src/App.tsx` - Application state and top-level host workflow.
- `src/bridge/types.ts` - Cross-host message schemas.
- `src/components/` - PR discovery, diff, review, chat, settings-adjacent UI, and reusable controls.
- `src/components/PRList/PRList.tsx` - Pull-request discovery state, host-message ingestion,
  shared current-repository context, and local text/#number filtering.
- `src/components/PRList/PRListControls.tsx`, `PRListNotices.tsx` - Scope/filter controls and
  exception-only list notices; state controls wrap in narrow, expanded-text layouts.
- `src/components/PRList/PRListItem.tsx`, `PRStatusBadges.tsx` - Title-first rows and readable
  draft/notification/review-freshness badges; compact metadata only when shared repository context
  matches, with repository identity retained for accessibility and out-of-context rows.
- `src/components/PRList/ReadinessCoach.tsx` - Compact persisted first-success confirmation;
  unverified provider sign-in uses localized, neutral information styling.
- `src/components/Setup/` - App-level prerequisite recovery UI and the setup reason/action matrix.
- `src/components/ReviewPane/ReviewPane.tsx` - Review feature composition root and public component API.
- `src/components/ReviewPane/useReviewController.ts` - PR-scoped bridge events, autosave, mutation
  watchdogs, review commands, chat sizing, and the view-model/action contract.
- `src/components/ReviewPane/reviewState.ts` - Pure review lifecycle transitions and state selectors.
- `src/components/ReviewPane/reviewActivity.ts`, `ReviewActivityLog.tsx` - Timestamped review-generation
  lifecycle/tool activity state and its expandable, privacy-safe timeline.
- `src/components/ReviewPane/ReviewOverrides.tsx`, `ReviewQuality.tsx`, `ReviewContent.tsx`,
  `ReviewFooter.tsx`, and `OrphanComments.tsx` - Feature-private review presentation modules.
- `src/components/ReviewPane/publishBody.ts` - Publish-dialog preview model (general notes, unanchored comments, inline count) and the fallback bodies mirrored from `DraftReviewMutationService`; tested by `publishBody.test.ts`.
- `src/lib/reviewQuality.ts` - Quality heuristics and in-memory repair suggestions.
- `src/lib/diffCoverage.ts` - Strict mirror of `DiffCoverage.split` plus coverage-gain and budget
  formatting for the coverage banner and chunked-review recommendation.
- `src/lib/autosave.ts` - Draft dirty-check, snapshot, and debounce decisions.
- `src/lib/validateComments.ts` - Inline-comment validation.
- `src/lib/keyboard.ts`, `layout.ts`, `motion.ts` - Shared interaction/layout policies.
- `src/components/DiffViewer/fileNavigation.ts`, `findingNavigation.ts` - Changed-file and
  severity-sorted review-finding navigation.
- `src/components/ReviewPane/` helpers - Chat sizing, comment navigation, and verify prompts.
- `src/components/a11y/LiveStatus.tsx` - Screen-reader announcements.
- `src/i18n/` - Typed English catalog and test pseudo-localization.
- `src/theme/hostTheme.ts` - Host theme application.
- `a11y/` - Playwright and axe end-to-end accessibility scenarios.
- `visual/` - Deterministic visual-regression scenarios: whole-page baselines plus locator-level pane baselines with a tighter per-locator tolerance and text assertions for text-critical states.
- Tests: colocated `*.test.ts`/`*.test.tsx` files.

### Semantic-review lifecycle entry points

- `intellij-plugin/.../services/SemanticSnapshotService.java` — strict production platform adapter
  plus package-private raw-input seam. `SemanticSnapshotServiceTest` drives real service
  transitions with queued native observations; `SemanticMcpToolsProviderTest` exercises scheduled
  CAPTURE/VERIFY and cancellation. These tests do not replace installed SDK/import evidence.
- `review-engine/.../review/SemanticReviewService.java` — prepare, collect, query bracketing,
  engine-owned execution authority, and final validation. `SemanticReviewServiceTest` covers
  collector negatives; `ReviewPipelineServiceTest` drives actual provider adapters with fake IO.
- `review-engine/.../review/IjctlClient.java` — fixed read-only allowlist, schemas and limits.
- `review-engine/.../review/SemanticSkillBundle.java` and `src/main/resources/semantic-skills/`
  — complete pinned instructions, provenance and license; never project-selected instructions.
- `review-engine/.../review/SemanticWorktreeStore.java` — durable retained identity and OS leases;
  `GitWorktreeService` protects retained trees from ordinary cleanup and non-force removal.
- `review-engine/.../engine/ReviewEngineApi.java`, `ReviewSessionService.java` and
  `sidecar/.../StdioJsonRpcServer.java` — prepare/list/cleanup capability and lifetime boundary.
- `intellij-plugin/.../ui/WebviewPanel.java`, `services/IntellijClaudeService.java` and
  `vscode-extension/src/{extension,deepReview,sidecar}.ts` — actual correlated host callbacks;
  selection/head/settings/operation fencing; provider ownership until final delivery.
- `webview/src/components/ReviewPane/{DeepReviewSetup,ReviewOverrides,ReviewPane,useReviewController}`
  — opt-in, manual pause, Continue/Retry/Cancel/ordinary fallback and retained maintenance.
  `ReviewPane.component.test.tsx` and `a11y/app.a11y.spec.ts` cover the shared interaction flow.
  The opt-in controls render only when `draftLoaded.intellijAssistedEnabled` is `true`, set from the
  default-off `PluginSettings.experimentalIntellijAssistedReview` /
  `pr-pilot.experimentalIntellijAssistedReview`; both hosts reject assisted requests while it is off.
  PR-agnostic retained-worktree maintenance renders only when the latest
  `prListLoaded.intellijAssistedEnabled` is `true` or an assisted setup is active.
- `WebviewPanelTest` and `vscode-extension/test/deepReview.test.ts` exercise actual bridge callbacks
  with external effects replaced. They do not stand in for installed-host/provider execution.
- `webview/visual/app.visual.spec.ts` — ordinary/deep-default assertions and deterministic
  narrow-finding placement. All committed baselines were regenerated and reviewed in the canonical
  container, and text-critical states also have locator-level snapshots.

### `vscode-extension/`

VS Code host integration. All GitHub and review generation routes through the Java sidecar.

- `src/extension.ts` - Activation, commands, webview bridge, and host lifecycle.
- `src/draftRecovery.ts` - Token-free `globalState` snapshots used until GitHub confirms a draft save.
- `src/sidecar.ts` - Mandatory JSON-RPC client and notification dispatch.
- `src/models.ts` - Host-neutral PR/review view models.
- `src/claude.ts` - Claude preflight and remaining mirrored prompt helpers.
- `src/copilot.ts` - Copilot model discovery and binary preflight.
- `src/providerSetup.ts` - Conservative Claude authentication probe classification for onboarding.
- `src/settings.ts` and `settingsView.ts` - Settings controller and pure webview rendering.
- `src/reviewGuidanceProfiles.ts` - Guidance-profile validation and resolution.
- `src/repositoryInstructions.ts` - Remembered per-repository review instructions (`pr-pilot.repositoryReviewInstructions`): key normalization, limits, settings-view update validation, and composition into `customInstructions`.
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
- `test/wireCatalog.test.ts` - Enforces engine declarations against the TypeScript client.
- `test/hostHandlers.test.ts` - Runs the compiled `extension.ts` bridge handlers with VS Code, the providers, and the sidecar replaced (PR-list payload fields and `cancelChat`).
- Other tests in `test/` mirror extension helpers and host behavior.

## Cross-module paths

### Review generation

`ReviewPane`/`App.tsx` -> host bridge -> `ReviewEngineApi` -> `ReviewSessionService` ->
`ReviewPipelineService` -> direct or chunked primary pass -> optional bounded supervisor/follow-up ->
contract-index-backed final critique/CI suppression -> status/chunk notifications -> host bridge -> shared webview.

### GitHub operations

Shared webview -> host bridge -> `GitHubEngineApi` -> `GitHubEngine` -> focused service in
`github-engine/sidecar/` -> token-free result -> host bridge -> shared webview.

For discovery, `PrListService` performs REST search, viewer lookup, and one bounded GraphQL
enrichment through `PrReviewStatusService`; see
[`diagrams/pr-discovery-sequence.md`](diagrams/pr-discovery-sequence.md).

### VS Code transport

`extension.ts` -> `sidecar.ts` -> stdio framing -> `StdioJsonRpcServer` -> engine API. IntelliJ skips
this adapter and calls the same engine implementations in-process.

### Settings

IntelliJ: `PluginSettingsConfigurable` -> `PluginSettingsComponent` -> `PluginSettings`.

VS Code: configuration contributions in `package.json` -> `settings.ts`/`settingsView.ts` ->
configuration reads in `extension.ts`.
