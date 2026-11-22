# PR Pilot Agent Guide

Operational guide for coding agents working in this repository.

## Source of truth split

- `AGENTS.md` (this file): workflow, testing, coding conventions, and cross-host sync obligations.
  Loaded on every turn — keep under 10,000 LF bytes (`scripts/agent-docs.mjs` enforces this).
- `ARCHITECTURE.md`: system boundaries, local data files, and an index of design constraints whose
  full text lives in `docs/architecture/*.md` (settings live in `docs/architecture/settings.md`).
- `CODEMAP.md`: task entry points and cross-module paths, plus per-module maps in
  `docs/codemap/*.md` listing implementation files and related tests.
- Treat `AGENTS.md` files as the only agent instruction files in this repo. This root file applies
  everywhere; `intellij-plugin/AGENTS.md`, `webview/AGENTS.md`, and `vscode-extension/AGENTS.md`
  add rules for work inside those directories. Read the nested file before editing files under it.
- Read `ARCHITECTURE.md` only when a change affects design or crosses module boundaries. Read
  `CODEMAP.md` only when locating implementation and test files, then read just the relevant
  `docs/architecture/*.md` or `docs/codemap/*.md` topic file.

## Context-window efficiency (264k budget)

Keep the working set fit comfortably inside a **264k-token context window** after system/tool
schemas and always-loaded instructions:

- Scope reads and searches: prefer narrow directory/glob searches and `view_range` on legacy large
  files instead of loading whole suites or unrelated modules. `.ignore` hides build, staged,
  report, and snapshot artifacts from ripgrep.
- Filter inner-loop test runs before running full suites:
  - JVM: `./gradlew :<module>:test --tests "*MyClassTest*"` (failures print full stack/assertion
    diffs inline without `--info`).
  - VS Code extension: `npm --prefix vscode-extension run test:unit -- <name-substring>`
    (add `--verbose` for full per-test `spec` output).
  - Webview logic/UI: `npm --prefix webview run test:logic -- <name-substring>` or
    `npx --prefix webview vitest run src/components/Foo.test.tsx`.
- Keep context sizes bounded (`scripts/agent-docs.mjs`, `scripts/file-budgets.mjs`, `scripts/verify.mjs`):
  - **Doc caps (`agent-docs.mjs`):** `AGENTS.md` <= 10 KB, nested `*/AGENTS.md` <= 8 KB, topic docs
    <= 20 KB, and total doc corpus <= 350 KB (prune stale prose rather than splitting forever).
  - **Advisory code/test targets (`file-budgets.mjs`):** aim for source <= 32 KB and tests <= 20 KB
    (`warnings` only, never build failures; `LEGACY_CEILINGS` quiets known larger files). Clean code
    and cohesive design come first — split only along real responsibility seams, never artificially
    to hit a byte count (going over or raising a baseline is fine when justified; use `view_range`
    on larger files).

## Maintenance rule

Update docs as part of each coding task:

- Update `CODEMAP.md` or the matching `docs/codemap/*.md` module map when documented files, task
  entry points, or cross-module paths change.
- Record non-obvious constraints future code must respect as a `###` section in the matching
  `docs/architecture/*.md` topic file, plus an index line under `ARCHITECTURE.md` "Key design
  decisions".
- Add new persisted settings to `docs/architecture/settings.md` (summary table and field notes).
- Update `docs/architecture/local-data.md` (and `ARCHITECTURE.md` "Local data files" if new file
  names are added) when persistent files change.
- Update affected Mermaid sources under `diagrams/` (`architecture.md`,
  `review-generation-sequence.md`, `pr-discovery-sequence.md`) and `diagrams/README.md` when code
  changes alter depicted boundaries, persistence, or workflows.
- Keep file/class inventories in `docs/codemap/*.md`, design rules in `docs/architecture/*.md`, and
  each agent doc under 20,000 LF bytes (split topic files rather than growing them).

## Host parity obligations

### The capability rule

**Engine capabilities are defined once and exposed everywhere** on the engine interfaces:

- `github-engine`: `com.jinloes.prpilot.engine.GitHubEngineApi`
- `review-engine`: `com.jinloes.prpilot.engine.ReviewEngineApi`

Each interface maps Java method names to JSON-RPC wire names in `RPC_METHODS`.
`sidecar/StdioJsonRpcServer` (`StdioJsonRpcServerGitHubHandlers`,
`StdioJsonRpcServerReviewHandlers`, `StdioJsonRpcServerAsyncReviewHandlers`) registers a handler for
every entry, and `SidecarBootstrapService.CAPABILITY_METHODS` groups every wire name under a logical
capability advertised in `initialize`.

- **Server enforcement:** `EngineCapabilityCoverageTest` (`sidecar`) fails if a capability lacks a
  wire name, handler registration, or `CAPABILITY_METHODS` entry, or if an undeclared method is
  exposed.
- **Client enforcement:** `vscode-extension/test/wireCatalog.test.ts` fails if
  `vscode-extension/src/sidecar*.ts` lacks a method for a wire name, calls an undeclared one, or
  drifts from `CAPABILITY_METHODS` in `REQUIRED_CAPABILITIES`.

### Parity requirements and modularity guardrails

- **Required:** every capability lives in `core`, `github-engine`, or `review-engine` and is
  reachable by every host. Never add host-local GitHub HTTP, fallback transport, or CLI-spawning
  review logic in `vscode-extension/` or `intellij-plugin/`.
- **UI lag must be recorded:** tests prove wire reachability, not UI consumption. If one host does
  not yet surface a new capability, call that out in the PR description (and document genuine
  platform constraints in `docs/architecture/*.md` + `ARCHITECTURE.md`).
- **VS Code support floor:** VS Code is a first-class host; the sidecar is its only transport, and
  user-facing engine features must ship with a VS Code path or a documented gap.
- **Guardrails:**
  1. No IntelliJ types in `core`, `github-engine`, or `review-engine`.
  2. Engine interface first, host wiring second.
  3. `webview/` stays host-neutral; `webview/src/bridge/types.ts` is the host↔UI contract.
  4. Prefer retiring `MIRRORS` entries in `scripts/host-mirrors.mjs` (`host-mirrors.test.mjs`
     enforces path existence; `verify.mjs` prints cross-host reminders) by moving shared logic into
     an engine capability and deleting the TypeScript copy. Notifications (`reviews/status`,
     `reviews/chunk`, `reviews/chatChunk`) remain listed because engine interfaces declare requests
     only.

## Testing conventions

Every code change must include tests for new/modified non-UI logic:

1. Identify every changed non-UI method (service, utility, model, static helper).
2. Widen `private` methods to package-private only when needed for tests (never `public`).
3. Cover happy paths, edge cases, and error paths.
4. Place JUnit 5 + AssertJ tests in `<module>/src/test/java/com/jinloes/prpilot/`, grouped by
   `@Nested` classes named after the method under test.
5. Prefer splitting large test classes (~20 KB) along logical areas into `<Class><Area>Test` files
   sharing a package-private `<Class>TestSupport` when natural seams exist.
6. Use `@BeforeEach`/`@AfterEach` + `Files.createTempDirectory`; never write to `~/.pr-pilot`.

## Required verification commands

Use Node from `.nvmrc` (`>= 26.0`). Run `./gradlew spotlessApply` after Java edits, then:

```bash
node scripts/verify.mjs --uncommitted  # inner-loop: uncommitted/untracked changes (--since HEAD)
node scripts/verify.mjs                # all changes vs origin/main, incl. uncommitted
node scripts/verify.mjs --dry-run      # show planned checks and notes only
node scripts/verify.mjs --all          # full local suite; run before opening a PR
```

`verify.mjs` maps changed paths to the smallest covering set (affected JVM `check` tasks,
webview/extension lint+typecheck+tests, cross-toolchain tests, and in-process doc / file-budget /
Playwright checks), printing one line per passing check and only failure tails. Update `planChecks`
when module dependencies or cross-toolchain tests change; CI-only checks live in `docs/architecture/ci.md`.

## Coding rules

- Prefer Apache Commons helpers (`CollectionUtils`, `StringUtils`, `Strings.CS`,
  `StringEscapeUtils`) over hand-rolled equivalents.
- Keep `core` models plain JavaBeans serialized via Jackson bean introspection — no Kotlin,
  `kotlinx.serialization`, or `jackson-module-kotlin`.
- Build Java JSON through Jackson `ObjectMapper` (including test fixtures, except intentional
  malformed-JSON tests); never concatenate raw JSON strings.
- Follow Google Java Style (Spotless-enforced), avoid FQNs in method bodies, keep imports explicit,
  and comment only non-obvious "why".
- Never include `Co-Authored-By` trailers in commit messages.

## Keep the repository company-neutral

Code, prompts, built-in rules, checklists, tests, fixtures, docs, and diagrams must not name any
employer or internal system (products, services, repos, bot logins, hostnames, URLs, ticket keys,
URNs, build tools, or guide paths). Use neutral placeholders (`acme/widgets`, `urn:example:…`,
`.review/ai-agent/*.md`, `svc-review-bot`), express real-PR review gaps as generic defect patterns,
keep repo-specific rules in `reviewGuidanceGlobs`, and scan changes before finishing.

