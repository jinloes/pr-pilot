# PR Pilot Agent Guide

Operational guide for coding agents working in this repository.

## Source of truth split

- `AGENTS.md` (this file): workflow, testing, coding conventions, and cross-host sync obligations.
- `ARCHITECTURE.md`: system boundaries, a settings summary, local data files, and an index of
  design constraints whose full text lives in `docs/architecture/*.md`.
- `CODEMAP.md`: task entry points and cross-module paths, plus per-module maps in
  `docs/codemap/*.md` that list implementation files and related tests.
- Treat `AGENTS.md` files as the only agent instruction files in this repo. This root file applies
  everywhere; `intellij-plugin/AGENTS.md`, `webview/AGENTS.md`, and `vscode-extension/AGENTS.md`
  add rules for work inside those directories. Read the nested file before editing files under it.
- Read `ARCHITECTURE.md` when a change affects design or crosses module boundaries. Read
  `CODEMAP.md` when locating implementation and test files. Do not load either file when the task
  does not need that context.

## Maintenance rule

Update docs as part of each coding task:

- Update `CODEMAP.md` or the matching `docs/codemap/*.md` module map when documented files, task
  entry points, or cross-module paths change.
- Record non-obvious constraints future code must respect as a `###` section in the matching
  `docs/architecture/*.md` topic file, plus an index line under `ARCHITECTURE.md` "Key design
  decisions".
- Add new persisted settings to the `ARCHITECTURE.md` "Settings persistence" table and describe
  them in `docs/architecture/settings.md`.
- Update `ARCHITECTURE.md` "Local data files" when persistent files are added.
- Update affected Mermaid sources under `diagrams/` when code changes alter depicted components,
  boundaries, ownership, persistence, or workflows. In particular, keep `architecture.md` aligned
  with host/sidecar/engine boundaries and keep `review-generation-sequence.md` aligned with PR
  context loading, worktree setup, generation stages, cancellation, and result delivery.
- Update `diagrams/README.md` and documentation links when diagrams are added, removed, or renamed.
- Keep this file focused on workflow rules; keep implementation details in code.
- Keep file/class inventories out of `ARCHITECTURE.md`; put them in the code maps.
- Keep each agent doc (every `AGENTS.md`, `ARCHITECTURE.md`, `CODEMAP.md`, and the
  `docs/architecture` and `docs/codemap` topic files) under 20,000 bytes so agents read it in one
  pass; split a topic file rather than growing it. `scripts/verify.mjs` checks their links,
  anchors, decision index, and sizes.

## Host parity obligations

### The capability rule

**Engine capabilities are defined once and exposed everywhere.** The full capability surface of each
engine is declared as an interface:

- `github-engine`: `com.jinloes.prpilot.engine.GitHubEngineApi`
- `review-engine`: `com.jinloes.prpilot.engine.ReviewEngineApi`

Each interface carries an `RPC_METHODS` map from Java method name to JSON-RPC wire name.
`sidecar/StdioJsonRpcServer` (handlers in `StdioJsonRpcServerGitHubHandlers`,
`StdioJsonRpcServerReviewHandlers`, and `StdioJsonRpcServerAsyncReviewHandlers`) must register a
handler for every entry, and `SidecarBootstrapService.CAPABILITY_METHODS` must group every wire
name under a logical capability (that grouping is what the `initialize` handshake advertises).

This is **enforced by `EngineCapabilityCoverageTest`** in the sidecar module, which fails the build if
a capability is declared without a wire name, exposed without being declared, declared without being
registered, or registered without being advertised. Do not maintain any of these mappings by hand —
add the method to the interface, add its `RPC_METHODS` entry, register the handler, and group it in
`CAPABILITY_METHODS`; the tests tell you if you missed a step.

**The client side is enforced too**, by `vscode-extension/test/wireCatalog.test.ts`, which reads the
engine interfaces as the source of truth and fails if the `vscode-extension/src/sidecar*.ts`
clients have no method for a wire name (or calls one no engine declares), and if
`REQUIRED_CAPABILITIES` drifts from `CAPABILITY_METHODS`. So a new capability is not "done" until
VS Code can reach it.

### What parity does and does not require

**Required:** every capability is reachable by every host. A capability lives in `core`,
`github-engine`, or `review-engine` — never in a host. Do not add host-local GitHub behavior,
fallback transport, or a second CLI-spawning review implementation in
`vscode-extension/src/claude.ts` or `copilot.ts`.

**Not required:** that every host *consumes* every capability on the same day. A host may lag in
surfacing a capability in its UI. What is never acceptable is a host working around a missing
capability by re-implementing it locally.

**But record the lag as a lag.** The tests prove a capability is *reachable* and that a client
method *exists*, not that any UI surfaces it. (VS Code once shipped months of reviews with empty
context prompt sections because four exposed capabilities were never called.) When you ship a
capability a host does not yet consume, say so explicitly in the PR description — do not mark the
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

Some logic is still duplicated across hosts or wired per host, so no coverage test keeps the copies
in sync. `MIRRORS` in `scripts/host-mirrors.mjs` lists each pair, and `node scripts/verify.mjs`
(including `--dry-run`) prints a reminder naming the other side whenever a change touches one.
`scripts/host-mirrors.test.mjs` fails when a listed path disappears, so move an entry with its
files. Treat the list as tech debt, not as a pattern to extend — do not add entries without
justification.

New **request** wire methods are not on the list: `wireCatalog.test.ts` fails the build when the
`sidecar*.ts` clients lack a method for one. Notifications (`reviews/status`, `reviews/chunk`,
`reviews/chatChunk`) are, because neither engine interface declares them, so there is no source
of truth to check them against.

**Retiring an entry is the preferred fix** (guardrail #5): expose the logic as an engine
capability, call it from `sidecar.ts`, and delete the TypeScript. `RepoGuidelinesReader` and
`GitWorktreeService` were retired this way after their copies had already diverged (different
truncation markers; different temp-directory name formats, which matter because cleanup matches on
the `pr-pilot-wt-` prefix).

## Testing conventions

Every code change must include tests for new/modified non-UI logic.

Checklist before completing a coding task:

1. Identify every changed non-UI method (service, utility, model, static helper).
2. Widen private methods to package-private only when needed for test access (never public).
3. Add tests for happy path, edge cases, and error paths.
4. Run required test suites and verify pass.

Test framework and location rules:

- Core tests: `core/src/test/java/com/jinloes/prpilot/` using JUnit 5 + AssertJ (same convention
  as `intellij-plugin`).
- IntelliJ plugin tests: `intellij-plugin/src/test/java/com/jinloes/prpilot/` using JUnit 5 +
  AssertJ.
- `review-engine`/`github-engine`/`sidecar` tests: `<module>/src/test/java/com/jinloes/prpilot/`
  using JUnit 5 + AssertJ (same convention as `intellij-plugin`).
- Group tests by `@Nested` classes named after the method under test.
- Prefer a new test class over growing one past about 20 KB, which agents must read in pieces:
  split by area into `<Class><Area>Test` files that share a package-private `<Class>TestSupport`.
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
lint/typecheck/tests; cross-toolchain tests for the engine interfaces and the diff-coverage golden;
in-process agent-docs and Playwright image pin checks when their inputs change). It prints one line
per passing check and only the tail of failing output, with the full log path. Its notes name the
other side of any hand-mirrored file you changed, the Docker visual check in `webview/AGENTS.md` for
webview UI files, and the workflow linters for `.github/` changes. Update `planChecks` in
`scripts/verify.mjs` when module dependencies or cross-toolchain tests change. CI additionally runs
checks this script does not (plugin verifier, visual regression, extension-host smoke, coverage);
see `docs/architecture/ci.md`.

## Coding rules

- Prefer Apache Commons helpers over hand-rolled equivalents (`CollectionUtils`, `StringUtils`,
  `Strings.CS`, `StringEscapeUtils`).
- `core`'s shared model classes are plain Java (JavaBean getters/setters). Don't reintroduce
  Kotlin, kotlinx.serialization, or jackson-module-kotlin for these classes — Jackson (plain bean
  introspection) is the only runtime JSON serializer used against them.
- For Java JSON, use the applicable Jackson `ObjectMapper` to serialize DTOs, maps, or tree nodes;
  do not concatenate raw JSON strings. Tests must build valid JSON fixtures through that
  serialization path, except when intentionally testing malformed JSON input.
- Follow Google Java Style (Spotless-enforced), avoid FQNs in method bodies, keep imports explicit.
- Use comments only for non-obvious "why", not to restate code.
- No `Co-Authored-By` trailers in commit messages.

## Scope reminder

- Keep this file short and operational; put design constraints in `ARCHITECTURE.md` and
  implementation navigation in `CODEMAP.md`.
