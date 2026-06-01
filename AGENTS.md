# PR Pilot Agent Guide

Operational guide for coding agents working in this repository.

## Source of truth split

- `AGENTS.md` (this file): workflow, testing, coding conventions, and cross-host sync obligations.
- `ARCHITECTURE.md`: project layout, design constraints, settings, and local data files.
- Treat `AGENTS.md` as the single instruction file for agent workflows in this repo.

## Maintenance rule

Update docs as part of each coding task:

- Update `ARCHITECTURE.md` "Project layout" when files are added/renamed in documented areas.
- Add to `ARCHITECTURE.md` "Key design decisions" only for non-obvious constraints future code must respect.
- Update `ARCHITECTURE.md` "Settings persistence" when new persisted settings are added.
- Update `ARCHITECTURE.md` "Local data files" when persistent files are added.
- Keep this file focused on workflow rules; keep implementation details in code.
- Prefer updating `ARCHITECTURE.md` over adding architectural detail here.

## IntelliJ <-> VS Code sync obligations

When logic changes in one host, update the parallel file in the other host:

| Changed file | Must also update |
|---|---|
| `ClaudeService.kt` prompt constants (`REVIEW_INSTRUCTIONS`, `CHAT_PERSONA`) | `vscode-extension/src/claude.ts` same constants |
| `ClaudeService.kt` `buildPrompt`/`buildChatPrompt`/`buildFocusedChatPrompt` | `vscode-extension/src/claude.ts` same functions |
| `ClaudeService.kt` stream-json review parsing | `vscode-extension/src/claude.ts` review event loop |
| `GitHubService.kt` encode/decode/comment-body helpers | `vscode-extension/src/github.ts` matching helpers |
| `GitHubService.kt` API call structure/headers/error handling | `vscode-extension/src/github.ts` `ghRequest` + callers |
| `PRToolWindowFactory.buildQuery` query behavior | `vscode-extension/src/github.ts` `searchPRs` |
| `GitHubAuthService.findGhBinary` binary path probes | `vscode-extension/src/github.ts` `findGhBinary` |
| `ClaudeService.findClaudeBinary` / `CopilotService.findCopilotBinary` | `vscode-extension/src/claude.ts` + `vscode-extension/src/copilot.ts` |
| `CopilotService.kt` SDK session setup, stream events, effort normalization | `vscode-extension/src/copilot.ts` |
| `CopilotService.DEFAULT_REASONING_EFFORT` | `vscode-extension/src/copilot.ts` |
| `webview/src/bridge/types.ts` message schemas | `WebviewPanel.java` and `vscode-extension/src/extension.ts` handlers |
| `PluginSettings` adding new setting | `vscode-extension/package.json` config contribution + `vscode-extension/src/extension.ts` reader |

## Testing conventions

Every code change must include tests for new/modified non-UI logic.

Checklist before completing a coding task:

1. Identify every changed non-UI method (service, utility, model, static helper).
2. Widen private methods to package-private only when needed for test access (never public).
3. Add tests for happy path, edge cases, and error paths.
4. Run required test suites and verify pass.

Test framework and location rules:

- Core tests: `core/src/jvmTest/kotlin/com/jinloes/prpilot/` using Kotest `FunSpec`.
- IntelliJ plugin tests: `intellij-plugin/src/test/java/com/jinloes/prpilot/` using JUnit 5 + AssertJ.
- Group Kotest tests by `context("MethodName")`.
- For temp directories in Kotest, use `beforeTest`/`afterTest` + `Files.createTempDirectory`.
- Do not write tests to `~/.pr-pilot`; use temp dirs.

## Required verification commands

```bash
./gradlew spotlessApply
./gradlew spotlessCheck
./gradlew check
./gradlew :core:jvmTest :intellij-plugin:unitTest
```

```bash
(cd webview && npm run lint)
(cd webview && npx tsc --noEmit)
(cd vscode-extension && npm run lint)
(cd vscode-extension && npx tsc --noEmit)
(cd vscode-extension && npm run test:unit)
```

## Coding rules

- Prefer Apache Commons helpers over hand-rolled equivalents (`CollectionUtils`, `StringUtils`, `Strings.CS`, `StringEscapeUtils`).
- In `core`, use kotlinx.serialization for JSON; do not introduce Jackson/Gson there.
- In `intellij-plugin`, Jackson is allowed for webview bridge deserialization.
- IntelliJ threading: background work on pooled threads, UI updates on EDT via `invokeLater()`.
- Follow Google Java Style (Spotless-enforced), avoid FQNs in method bodies, keep imports explicit.
- Use comments only for non-obvious "why", not to restate code.
- No `Co-Authored-By` trailers in commit messages.
- Do not add `eslint-disable` comments without a `--` explanation.

## Scope reminder

- Keep this file short and operational; put architecture and version details in `ARCHITECTURE.md`.
