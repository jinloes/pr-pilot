# Code map: `intellij-plugin/`

Part of the [code map](../../CODEMAP.md), which holds the task-to-code index and cross-module paths.
Paths omit `src/main/java/com/jinloes/prpilot/` and equivalent test roots where the module context
makes them unambiguous; other paths are relative to the module directory.

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
- `SemanticSnapshotServiceTest` and `SemanticMcpToolsProviderTest` (shared fixtures in
  `SemanticSnapshotTestFixtures`) - Real service rejection paths,
  subscribed document/reload/disposal callbacks, queued tool dispatch/cancellation and model
  limitations. These bounded tests do not claim a live successful import or ready capture.
- `src/main/resources/META-INF/prpilot-mcp.xml` - Optional project service and MCP provider
  registration; absent MCP does not load the inventory integration.
- `services/UserFacingErrors.java` - Actionable host error copy.
- `services/PendingReviewIndex.java` - Saved-draft index.
- `services/DraftRecoveryStore.java` - Token-free local recovery snapshots for interrupted draft
  replacement.
- `services/PendingReviewIndexNotifications.java` - Corrupt-index warning and quarantine action.
- `services/SeenPRSet.java` - Notification deduplication state.
- `services/PRNotificationService.java` - PR polling, source labels, and merge behavior.
- `services/PRNotificationStartup.java` - Notification lifecycle entry point.
- `settings/PluginSettings.java` - Persisted settings model.
- `settings/RepositoryReviewInstructions.java` - Remembered per-repository review instructions: key
  normalization, limits, and composition into `customInstructions` (mirrors
  `vscode-extension/src/repositoryInstructions.ts`).
- `settings/PluginSettingsComponent.java` - Settings form composition root.
- `settings/CopilotModelSelector.java` - Provider-aware Copilot model combo and advanced controls.
- `settings/ReviewGuidanceEditor.java` - Guidance profiles, focus areas, and custom instructions UI.
- `settings/RepositoryInstructionsEditor.java` - Remembered repository-instructions UI
  (`RepositoryInstructionsEditorTest`).
- `settings/SettingsUi.java` - Shared Swing layout helpers.
- `settings/PluginSettingsConfigurable.java` - Settings lifecycle integration.
- `settings/GithubBaseUrlValidator.java` - HTTPS GitHub-origin normalization.
- `ui/PRToolWindowFactory.java` - Tool-window entry point; title actions (Pop Out, Settings) and the
  gear-menu "Reload PR Pilot View" action, built by package-private helpers covered by
  `PRToolWindowFactoryTest`.
- `ui/WebviewPanel.java` - JCEF host composition root wiring the collaborators below.
- `ui/WebviewBridgeHandler.java` - Bridge message parsing and dispatch.
- `ui/WebviewBrowserController.java` - Browser/resource-server startup and repaint scheduling.
- `ui/WebviewPanelLifecycle.java` - Selection transitions, cancellation, and disposal.
- `ui/WebviewPrListController.java` and `WebviewPrSelectionController.java` - PR list loading and
  PR selection/detail loading.
- `ui/WebviewReviewController.java` - Review generation, streaming, and draft mutations.
- `ui/WebviewWorktreeManager.java` - Worktree acquisition, reuse, cleanup, and per-PR Claude service
  resolution (`resolve`, behind `WebviewPanel.resolvePrClaudeService`).
- `ui/WebviewChatHost.java`, `WebviewDeepHost.java` - Host adapters for the chat and deep-review
  controllers.
- `ui/WebviewThemeController.java` - Theme publication.
- `ui/WebviewPanelSupport.java` - Shared panel helpers and provider readiness.
- `ui/WebviewBridgeMessages.java` - Java records for bridge message payloads.
- `ui/PrChatController.java` - PR chat state, ask/clear/cancel, and chat reset.
- `ui/DeepReviewController.java` - IntelliJ-assisted review setup, continuation, maintenance, and
  generation-failure restore.
- `ui/WorktreeCoordinator.java` - Active-PR worktree ownership and leases.
- `ui/WebviewResourceServer.java` - Local webview resource server lifecycle and path resolution.
- `ui/WebviewPrSupport.java` - Pure PR/draft/worktree-key helpers used by the panel.
- Tests: `WebviewPanelTest` and `WebviewPanelDeepReviewTest` (bridge-driven, sharing
  `WebviewPanelFixture`), `WebviewPanelMessageValidationTest` (incoming-message validation),
  `WebviewIncrementalReviewTest`, `WebviewPrSupportTest`, `WorktreeCoordinatorTest`,
  `WebviewResourceServerTest`.
- `ui/HostThemeClassifier.java` - Host theme normalization.
- `ui/ReviewMapper.java` and `WebviewDtos.java` - Core-to-bridge DTO mapping.
- Tests: `intellij-plugin/src/test/java/com/jinloes/prpilot/`, mirroring production packages.
