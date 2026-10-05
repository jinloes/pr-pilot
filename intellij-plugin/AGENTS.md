# intellij-plugin agent guide

Rules for work under `intellij-plugin/`. The root `AGENTS.md` still applies.

- Threading: background work on pooled threads; UI updates on the EDT via `invokeLater()`.
- Jackson is allowed for webview bridge deserialization (`ui/WebviewBridgeMessages`).
- `WebviewPanel` is a thin composition root. Bridge dispatch lives in `ui/WebviewBridgeHandler`;
  feature logic belongs in its collaborators (`WebviewReviewController`, `WebviewPrSelectionController`,
  `WebviewPrListController`, `WebviewWorktreeManager`, `DeepReviewController`, `PrChatController`, ...)
  or in an engine module. `PluginSettingsComponent` likewise delegates to `settings/` editors
  (`CopilotModelSelector`, `ReviewGuidanceEditor`, `RepositoryInstructionsEditor`).
- GitHub and review behavior come from the engines in-process (`IntellijGitHubService`,
  `IntellijClaudeService`); do not add host-local GitHub or CLI logic.
- Tests: `src/test/java/com/jinloes/prpilot/`, JUnit 5 + AssertJ, run by `:intellij-plugin:unitTest`
  (part of `check`).
