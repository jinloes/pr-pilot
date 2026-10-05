# PR Pilot Agent Guide

Operational guide for coding agents working in this repository.

## Source of truth split

- `AGENTS.md` (this file): workflow, testing, coding conventions, and cross-host sync obligations.
- `ARCHITECTURE.md`: system boundaries, settings, local data files, and an index of design
  constraints whose full text lives in `docs/architecture/*.md`.
- `CODEMAP.md`: implementation locations, task entry points, related tests, and cross-module paths.
- Treat `AGENTS.md` files as the only agent instruction files in this repo. This root file applies
  everywhere; `intellij-plugin/AGENTS.md`, `webview/AGENTS.md`, and `vscode-extension/AGENTS.md`
  add rules for work inside those directories. Read the nested file before editing files under it.
- Read `ARCHITECTURE.md` when a change affects design or crosses module boundaries. Read
  `CODEMAP.md` when locating implementation and test files. Do not load either file when the task
  does not need that context.

## Maintenance rule

Update docs as part of each coding task:

- Update `CODEMAP.md` when documented files, task entry points, or cross-module paths change.
- Record non-obvious constraints future code must respect as a `###` section in the matching
  `docs/architecture/*.md` topic file, plus an index line under `ARCHITECTURE.md` "Key design decisions".
- Update `ARCHITECTURE.md` "Settings persistence" when new persisted settings are added.
- Update `ARCHITECTURE.md` "Local data files" when persistent files are added.
- Update affected Mermaid sources under `diagrams/` when code changes alter depicted components,
  boundaries, ownership, persistence, or workflows. In particular, keep `architecture.md` aligned
  with host/sidecar/engine boundaries and keep `review-generation-sequence.md` aligned with PR
  context loading, worktree setup, generation stages, cancellation, and result delivery.
- Update `diagrams/README.md` and documentation links when diagrams are added, removed, or renamed.
- Keep this file focused on workflow rules; keep implementation details in code.
- Keep file/class inventories out of `ARCHITECTURE.md`; put them in `CODEMAP.md`.

## Host parity obligations

### The capability rule

**Engine capabilities are defined once and exposed everywhere.** The full capability surface of each
engine is declared as an interface:

- `github-engine`: `com.jinloes.prpilot.engine.GitHubEngineApi`
- `review-engine`: `com.jinloes.prpilot.engine.ReviewEngineApi`

Each interface carries an `RPC_METHODS` map from Java method name to JSON-RPC wire name.
`sidecar/StdioJsonRpcServer` (handlers in `StdioJsonRpcServerGitHubHandlers`/`StdioJsonRpcServerReviewHandlers`) must register a handler for every entry, and
`SidecarBootstrapService.CAPABILITY_METHODS` must group every wire name under a logical capability
(that grouping is what the `initialize` handshake advertises).

This is **enforced by `EngineCapabilityCoverageTest`** in the sidecar module, which fails the build if
a capability is declared without a wire name, exposed without being declared, declared without being
registered, or registered without being advertised. Do not maintain any of these mappings by hand —
add the method to the interface, add its `RPC_METHODS` entry, register the handler, and group it in
`CAPABILITY_METHODS`; the tests tell you if you missed a step.

**The client side is enforced too**, by `vscode-extension/test/wireCatalog.test.ts`, which reads the
engine interfaces as the source of truth and fails if the `vscode-extension/src/sidecar*.ts` clients have no method for a wire
name (or calls one no engine declares), and if `REQUIRED_CAPABILITIES` drifts from
`CAPABILITY_METHODS`. So a new capability is not "done" until VS Code can reach it.

### What parity does and does not require

**Required:** every capability is reachable by every host. A capability lives in `core`,
`github-engine`, or `review-engine` — never in a host. Do not add host-local GitHub behavior,
fallback transport, or a second CLI-spawning review implementation in
`vscode-extension/src/claude.ts` or `copilot.ts`.

**Not required:** that every host *consumes* every capability on the same day. A host may lag in
surfacing a capability in its UI. What is never acceptable is a host working around a missing
capability by re-implementing it locally.

**But record the lag as a lag.** The tests prove a capability is *reachable* and that a client method
*exists* — not that any UI actually surfaces it. Phase 1's four context capabilities sat
exposed-but-uncalled in VS Code for months while marked done, so every VS Code review
shipped with empty CI/commits/issue/profile prompt sections. `wireCatalog.test.ts` now catches that
exact shape, but a client method wired to no UI would still slip through. When you ship a capability
a host does not yet consume, say so explicitly in the PR description — do not mark the
work complete.

If a genuine platform constraint makes a capability impossible in one host, document the gap and the
reason as a section in the matching `docs/architecture/*.md` topic file (with an index line under
`ARCHITECTURE.md` "Key design decisions") and call it out in the PR description.

### Modularity guardrails

1. No IntelliJ types in `core`, `github-engine`, or `review-engine`.
2. Every new capability lands on the engine interface first, host wiring second.
3. `webview/` stays host-neutral and shared — it is UI, not host code.
4. `webview/src/bridge/types.ts` remains the host↔UI contract.
5. Deleting a TypeScript reimplementation is always preferred over updating it.

### VS Code support floor

VS Code is a first-class host, not a port. Concretely:

- Every engine capability is exposed over the sidecar (test-enforced above).
- Any user-facing feature built on engine capabilities must have a VS Code path in the same change,
  or a documented gap. UI polish may differ; access to functionality may not.
- The sidecar is the only VS Code transport. No direct `gh`/CLI invocation from the extension.

### Remaining hand-mirrored logic

These are **not** covered by the coverage test because they are genuinely duplicated implementations
or per-host wiring. Treat this list as tech debt, not as a pattern to extend — do not add rows
without justification.

| Changed file | Must also update |
|---|---|
| `review-engine/ReviewPrompts.java` `CHAT_PERSONA` | `vscode-extension/src/claude.ts` same constant |
| `review-engine/ReviewPrompts.java` `buildFocusedChatPrompt` | `vscode-extension/src/claude.ts` same function |
| `WebviewWorktreeManager`/`WorktreeCoordinator` worktree lifecycle | `vscode-extension/src/worktree.ts` `resolveWorkingDir`/`clearWorktree` — the *lifecycle* only (which dir belongs to the active PR, when to tear it down); the git work is no longer mirrored |
| `PRNotificationService` poll/source-labeling/merge logic | `vscode-extension/src/notifications.ts` + `notificationPoller.ts` `PRNotificationPoller.poll` |
| `review-engine/BinaryLocator.java` | `vscode-extension/src/claude.ts` + `vscode-extension/src/copilot.ts` binary-probing candidates |
| `review-engine/CopilotModelDiscovery.java` model probing / `settings/CopilotModelSelector` model combo | `vscode-extension/src/copilot.ts` `listModels`/`filterModelIds` + `copilotModel.ts` `selectCopilotModel` command |
| `PluginSettingsComponent` settings UI (provider-aware model selector, effort, base URL) | `vscode-extension/src/settings.ts` + `settingsView.ts` settings webview |
| `review-engine/CopilotService.DEFAULT_REASONING_EFFORT` | `vscode-extension/src/copilot.ts` |
| `webview/src/bridge/types.ts` message schemas | `WebviewBridgeMessages.java`/`WebviewBridgeHandler.java` and `vscode-extension/src/extension.ts` `setupMessageBridge` + `prHandlers.ts`/`reviewHandlers.ts` |
| `core/.../model/DiffCoverage.java` trailer grammar (`split`, header/path format, limits) | `webview/src/lib/diffCoverage.ts` `splitDiffCoverage`/`parseDiffCoverage`; add accepted and rejected cases to the shared `core/src/test/resources/diff-coverage/trailer.golden.txt`, which both `DiffCoverageTest` and `diffCoverage.test.ts` read |
| `github-engine/.../DraftReviewMutationService.java` `effectiveBody` fallback bodies and `DraftReviewCodec.encodeBody` general-note/detached-section rules | `webview/src/components/ReviewPane/publishBody.ts` `FALLBACK_REVIEW_BODY`/`publishedBodySections`; `publishBody.test.ts` and `DraftReviewMutationServiceTest` pin the strings. Retire it by adding a read-only publish-preview engine capability. |
| `intellij-plugin/.../settings/RepositoryReviewInstructions.java` key normalization, limits and `compose` format | `vscode-extension/src/repositoryInstructions.ts` same functions; host-owned settings resolution like guidance profiles, so there is no engine capability to share |
| `PluginSettings` adding new setting | `vscode-extension/package.json` config contribution + `vscode-extension/src/extension.ts` reader |
| Any new **notification** shape (`reviews/status`, `reviews/chunk`, `reviews/chatChunk`) | `vscode-extension/src/sidecarTransport.ts` dispatch — notifications are not in `RPC_METHODS`, so nothing enforces them |

New **request** wire methods are no longer on this list: `wireCatalog.test.ts` fails the build when
the `sidecar*.ts` clients lack a method for one. Notifications remain hand-mirrored because they are not
declared on either engine interface, so there is no source of truth to check them against.

**Retiring a row is the preferred fix** (guardrail #5), and the mechanism is: expose the logic as an
engine capability, call it from `sidecar.ts`, delete the TypeScript. `RepoGuidelinesReader` was
retired this way after its two copies were found to have already diverged (the JVM truncation marker
was `...`, the TypeScript one `…`). `GitWorktreeService` followed, behind the `worktrees` capability
— its two copies had drifted to different temp-directory name formats, which matters because the
cleanup path matches on the `pr-pilot-wt-` prefix. Prefer that over updating both copies.

## Testing conventions

Every code change must include tests for new/modified non-UI logic.

Checklist before completing a coding task:

1. Identify every changed non-UI method (service, utility, model, static helper).
2. Widen private methods to package-private only when needed for test access (never public).
3. Add tests for happy path, edge cases, and error paths.
4. Run required test suites and verify pass.

Test framework and location rules:

- Core tests: `core/src/test/java/com/jinloes/prpilot/` using JUnit 5 + AssertJ (same convention as `intellij-plugin`).
- IntelliJ plugin tests: `intellij-plugin/src/test/java/com/jinloes/prpilot/` using JUnit 5 + AssertJ.
- `review-engine`/`github-engine`/`sidecar` tests: `<module>/src/test/java/com/jinloes/prpilot/` using JUnit 5 + AssertJ (same convention as `intellij-plugin`).
- Group tests by `@Nested` classes named after the method under test.
- For temp directories, use `@BeforeEach`/`@AfterEach` + `Files.createTempDirectory`.
- Do not write tests to `~/.pr-pilot`; use temp dirs.

## Required verification commands

Use Node from `.nvmrc` (the script refuses Node older than 20.19). Run `./gradlew spotlessApply`
after Java edits, then:

```bash
node scripts/verify.mjs            # checks affected by changes vs origin/main, incl. uncommitted
node scripts/verify.mjs --dry-run  # show the plan only
node scripts/verify.mjs --all      # everything; run before opening a PR
```

The script maps changed paths to the smallest covering set (a JVM module plus its downstream
modules' `check`, which includes Spotless and `intellij-plugin:unitTest`; webview and extension
lint/typecheck/tests; cross-toolchain tests for the engine interfaces and the diff-coverage golden).
It prints one line per passing check and only the tail of failing output, with the full log path.
When a webview UI file changes it reminds you to run the Docker visual check in `webview/AGENTS.md`.
Update `planChecks` in `scripts/verify.mjs` when module dependencies or cross-toolchain tests change.

## Coding rules

- Prefer Apache Commons helpers over hand-rolled equivalents (`CollectionUtils`, `StringUtils`, `Strings.CS`, `StringEscapeUtils`).
- `core`'s shared model classes are plain Java (JavaBean getters/setters). Don't reintroduce Kotlin, kotlinx.serialization, or jackson-module-kotlin for these classes — Jackson (plain bean introspection) is the only runtime JSON serializer used against them.
- For Java JSON, use the applicable Jackson `ObjectMapper` to serialize DTOs, maps, or tree nodes; do not concatenate raw JSON strings. Tests must build valid JSON fixtures through that serialization path, except when intentionally testing malformed JSON input.
- Follow Google Java Style (Spotless-enforced), avoid FQNs in method bodies, keep imports explicit.
- Use comments only for non-obvious "why", not to restate code.
- No `Co-Authored-By` trailers in commit messages.

## Scope reminder

- Keep this file short and operational; put design constraints in `ARCHITECTURE.md` and
  implementation navigation in `CODEMAP.md`.
