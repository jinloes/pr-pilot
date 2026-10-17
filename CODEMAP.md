# PR Pilot Code Map

Lookup guide for implementation work. Read this file when locating code or tests; read
`ARCHITECTURE.md` only when the change affects system boundaries, persistence, or design
constraints. Per-module file maps live in `docs/codemap/` (see [Module maps](#module-maps)); open
only the map for the module you are changing.

## Task-to-code index

| Task | Start here | Follow through | Primary tests |
|---|---|---|---|
| Inspect internal source coverage (not readiness) | `model/SourceInventory.java`, `review/SourceInventoryClient.java` | `SourceInventoryFiles.java`, IntelliJ `SourceInventoryService.java` and `SourceInventoryMcpProvider.java` | `SourceInventoryTest`, `SourceInventoryFilesTest`, `SourceInventoryClientTest`, `SourceInventoryServiceTest`, `SourceInventoryMcpProviderTest` (positive-traversal tests carry `@RequiresSecureTraversal`); manual protocol recipe in `docs/intellij-assisted-review.md` |
| Change review generation or prompts | `review-engine/.../ReviewPrompts.java` and its per-stage `Review*Prompts.java`/`ReviewInstructions.java`/`*Checklists.java` siblings, `ReviewResultParser.java`, `ClaudeService.java`, `CopilotService.java` | `ReviewEngineApi.java`, `ReviewSessionService.java`, host request wiring | Matching `review-engine` service tests; prompt mirrors listed in `scripts/host-mirrors.mjs` |
| Add an engine capability | `GitHubEngineApi.java` or `ReviewEngineApi.java` | `StdioJsonRpcServerGitHubHandlers.java`/`StdioJsonRpcServerReviewHandlers.java` (cancellable or streaming work: `StdioJsonRpcServerAsyncReviewHandlers.java`), `SidecarBootstrapService.java`, `vscode-extension/src/sidecarGitHubClient.ts`/`sidecarReviewClient.ts` | `EngineCapabilityCoverageTest.java`, `wireCatalog.test.ts` |
| Change PR discovery, metadata, diff, or draft mutations | `github-engine/.../sidecar/pr/` | `GitHubEngine.java`, both host bridges, shared webview messages | Matching `github-engine` service test plus host bridge tests |
| Change the shared review UI | `webview/src/App.tsx`, `webview/src/components/` | `webview/src/bridge/types.ts`, both host bridge handlers | Component tests plus accessibility/visual suites when behavior or layout changes |
| Change IntelliJ host behavior | `intellij-plugin/.../ui/WebviewBridgeHandler.java` and the `Webview*Controller` collaborators, `PrChatController.java`, `DeepReviewController.java` | `services/`, `settings/`, shared webview bridge | Matching IntelliJ JUnit tests |
| Change VS Code host behavior | `vscode-extension/src/extension.ts`, `prHandlers.ts`, `reviewHandlers.ts` | `sidecar*.ts`, `settings.ts`, `settingsView.ts` | `vscode-extension/test/` |
| Change settings | `intellij-plugin/.../settings/PluginSettings.java` | Both settings UIs, `vscode-extension/package.json`, host readers | IntelliJ settings tests and VS Code settings tests |
| Change notifications | `intellij-plugin/.../PRNotificationService.java` | `vscode-extension/src/notifications.ts`, both host lifecycle entry points | Notification tests in both hosts |
| Change local draft/index persistence | `PendingReviewIndex.java`, `DraftRecoveryStore.java`, `SeenPRSet.java`, `vscode-extension/src/draftRecovery.ts` | Both host lifecycle callers; persistence contract in `ARCHITECTURE.md` | Matching IntelliJ and VS Code service tests |
| Change packaging or releases | `.github/workflows/release.yml`, `vscode-extension/scripts/package-vsix.mjs` (and the `vsce` pin in `vscode-extension/package.json`), module build files | VS Code staging scripts, root Gradle configuration, `docs/architecture/ci.md` | CI workflow commands, sidecar smoke test, extension-host smoke |
| Change CI checks or workflows | `.github/workflows/`, `.github/scripts/trusted-jdks.sh`, `.github/dependabot.yml` | `docs/architecture/ci.md` (required-check job names, JDK trust, image pins), `scripts/verify.mjs` | actionlint, shellcheck, and zizmor (`Lint workflows`, `zizmor`), `playwright-image.test.mjs`, `verify.test.mjs` |
| Measure review recall against Mae | `review-benchmark/.../benchmark/ReviewBenchmark.java` | `MaeComments.java`, `LocalCheckout.java`, `FindingMatcher.java`, `LlmJudge.java`, `BenchmarkReport.java`, `RepeatSummary.java` | `review-benchmark/src/test/.../benchmark/` |
| Select a local IntelliJ sandbox without changing the compile SDK | `gradle/intellij-sandbox.gradle`, `intellij-plugin/build.gradle` | `runIdeLocal`, `printSandboxIdeSelection` | `gradle/intellij-sandbox-tests.gradle` / `:intellij-plugin:testSandboxIdeSelection` |

## Repository map

### Root and automation

- `README.md` - User setup, development, checks, and release flow.
- `docs/intellij-assisted-review.md` - Experimental IntelliJ-assisted review, native readiness and
  source-inventory protocol.
- `AGENTS.md` - Agent workflow, testing rules, and cross-host obligations; `intellij-plugin/`,
  `webview/`, and `vscode-extension/` each add a directory-scoped `AGENTS.md`.
- `.nvmrc` - Node version for webview/extension tooling (matches CI).
- `.ignore` - Hides lockfiles, visual snapshots, and build output from ripgrep-based search tools.
- `ARCHITECTURE.md` - Design-constraint index, settings persistence, and local data.
- `docs/architecture/*.md` - Full design-constraint sections by topic (review pipeline, review
  checklists, protobuf review, benchmarks, comments and context, diff coverage, providers, semantic
  review, hosts and sidecar, sidecar capabilities, module and runtime boundaries, GitHub drafts and
  worktrees, webview, local data, CI and releases) plus the settings reference.
- `docs/codemap/*.md` - Per-module file maps and the semantic-review lifecycle entry points.
- `diagrams/` - Mermaid architecture and PR review-generation sequence diagrams.
- `.github/workflows/ci.yml` - Pull-request and `main` gate: Gradle check with JaCoCo, IntelliJ
  plugin verifier, webview and VS Code extension suites, visual regression in the Playwright
  container, extension-host smoke, Windows/macOS host checks, report-only changed-line coverage, and
  non-blocking Windows parity. Required checks match its job names (`docs/architecture/ci.md`).
- `.github/workflows/release.yml` - Tag-driven, cache-free build and verification of the IntelliJ
  ZIP and VSIX, then a separate `gh` publish job.
- `.github/workflows/codeql.yml`, `dependency-review.yml`, `dependency-submission.yml` - Code
  scanning, pull-request dependency review, and `main`'s submitted Gradle dependency graph.
- `.github/workflows/workflow-lint.yml` - actionlint, shellcheck, the Playwright image pin check,
  and zizmor.
- `.github/workflows/mutation.yml` - Nightly report-only PIT (`core`, `review-engine`) and Stryker
  (`webview`).
- `.github/dependabot.yml` - Weekly grouped updates with a 7-day cooldown.
- `.github/scripts/trusted-jdks.sh` - Owner-only JDK copies for Linux Gradle jobs.
- `.gitattributes` - LF pins for the diff-coverage golden and shell scripts, and no line-ending
  conversion for the digest-pinned semantic-skills bundle.
- `scripts/portable-process.mjs` and `run-gradle.mjs` - Shell-free npm and Gradle wrapper
  invocation used by portable packaging/tests and targeted host CI.
- `scripts/reviewbench.mjs` - Local ReviewBench run: pinned corpus checkout, the `reviewBench`
  Gradle task, and ReviewBench's judge on Copilot via a generated pi `models.json`
  (`reviewbench.test.mjs`, run by the extension runner).
- `scripts/reviewbench-report.mjs` - ReviewBench run report: metric spread across rounds, missed
  golden findings with nearby and critique-dropped findings, false positives, and a noise-aware
  baseline comparison (`reviewbench-report.test.mjs`, run by the extension runner).
- `scripts/verify.mjs` - Change-aware verification: `planChecks` maps changed paths to the minimal
  Gradle/npm checks plus the in-process agent-doc and Playwright image checks, adds hand-mirror and
  CI-lint reminders, and prints only failing output (`verify.test.mjs`, run by the extension
  runner).
- `scripts/playwright-image.mjs` - Fails when a Playwright container reference (CI, release, or
  `webview/AGENTS.md`) is not the locked `@playwright/test` version's tag pinned by the shared
  digest (`playwright-image.test.mjs`, run by the extension runner).
- `scripts/host-mirrors.mjs` - `MIRRORS`, the hand-mirrored file pairs no test keeps in sync, and
  the reminders `verify.mjs` prints when one side changes (`host-mirrors.test.mjs`, run by the
  extension runner, which runs every `scripts/*.test.mjs`).
- `scripts/agent-docs.mjs` - Agent-doc consistency: every `docs/architecture` decision indexed once
  in `ARCHITECTURE.md`, relative links and anchors resolve, every code map is linked, and size
  warnings above 20,000 bytes (`agent-docs.test.mjs`, run by the extension runner).
- `gradle/intellij-sandbox.gradle` - Lazy installed-IDE selection and separate `runIdeLocal`
  platform/runtime/sandbox wiring; compilation and ordinary `runIde` stay pinned.
- `gradle/intellij-sandbox-tests.gradle` - Temporary metadata fixtures exercising the production
  selector plus launch-free pinned/custom configuration assertions, attached to check.

### `diagrams/`

- `README.md` - Diagram index and maintenance guidance.
- `architecture.md` - Host, transport, engine, external-system, and persistence boundaries.
- `review-generation-sequence.md` - End-to-end PR selection and review-generation sequence.

### Module maps

| Module | Map | Covers |
|---|---|---|
| `core/` | [docs/codemap/core.md](docs/codemap/core.md) | Shared plain-Java models |
| `review-engine/` | [docs/codemap/review-engine.md](docs/codemap/review-engine.md) | Providers, prompts, parsing, pipeline, worktrees, semantic runtime |
| `github-engine/` | [docs/codemap/github-engine.md](docs/codemap/github-engine.md) | GitHub API, PR discovery and context, draft reviews, repo detection |
| `sidecar/` | [docs/codemap/sidecar.md](docs/codemap/sidecar.md) | VS Code stdio JSON-RPC adapter |
| `review-benchmark/` | [docs/codemap/review-benchmark.md](docs/codemap/review-benchmark.md) | Developer-only recall and ReviewBench CLIs |
| `intellij-plugin/` | [docs/codemap/intellij-plugin.md](docs/codemap/intellij-plugin.md) | IntelliJ host: services, settings, JCEF webview bridge |
| `webview/` | [docs/codemap/webview.md](docs/codemap/webview.md) | Shared React review UI |
| `vscode-extension/` | [docs/codemap/vscode-extension.md](docs/codemap/vscode-extension.md) | VS Code host: bridge handlers, sidecar client, settings view |
| Semantic review | [docs/codemap/semantic-review.md](docs/codemap/semantic-review.md) | Cross-module semantic-review lifecycle entry points |

## Cross-module paths

### Review generation

`ReviewPane`/`App.tsx` -> host bridge -> `ReviewEngineApi` -> `ReviewSessionService` ->
`ReviewPipelineService` -> regenerated protobuf code left out of the diff -> base-commit
guidance/history/protobuf-schema-delta/call-site enrichment -> direct or chunked primary pass
(plus optional parallel second reviewer, merged) -> optional bounded supervisor/follow-up ->
optional `ReviewRulesPass` (trigger selection, one agent per rule) -> contract-index-backed final
critique/CI suppression -> status/chunk notifications -> host bridge -> shared webview.

### GitHub operations

Shared webview -> host bridge -> `GitHubEngineApi` -> `GitHubEngine` -> focused service in
`github-engine/sidecar/` -> token-free result -> host bridge -> shared webview.

For discovery, `PrListService` performs REST search, viewer lookup, and one bounded GraphQL
enrichment through `PrReviewStatusService`; see
[`diagrams/pr-discovery-sequence.md`](diagrams/pr-discovery-sequence.md).

### VS Code transport

`extension.ts`/handlers -> `sidecar*Client.ts` -> `sidecarTransport.ts` -> stdio framing ->
`StdioJsonRpcServer` -> engine API. IntelliJ skips this adapter and calls the same engine
implementations in-process.

### Settings

IntelliJ: `PluginSettingsConfigurable` -> `PluginSettingsComponent` (+ `settings/` editors) ->
`PluginSettings`.

VS Code: configuration contributions in `package.json` -> `settings.ts`/`settingsView.ts` ->
configuration reads in `extension.ts`.
