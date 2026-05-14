# PR Pilot

IntelliJ and VS Code extension that lists GitHub Pull Requests and generates AI-powered code reviews using a local AI CLI.

> **CLAUDE.md maintenance rule:** Update this file as part of every coding task.
> - Update "Project layout" when files are added or renamed.
> - Add to "Key design decisions" **only** when a non-obvious constraint is introduced that future code must respect and that is not obvious from reading the source.
> - Update "Settings persistence" when new settings are added.
> - Update "Local data files" when new persistent files are introduced.
> - Do **not** document how existing code works — that belongs in code comments or git history.

---

## Project layout

Multi-module Gradle project:

```
core/                                  – KMP module (jvm + js targets); Java services compiled as jvmMain
  src/commonMain/kotlin/com/jinloes/prpilot/
    model/
      PullRequest.kt                 – @Serializable data class (title, number, owner, repo, author, etc.)
      ReviewResult.kt                – @Serializable class; holds summary, verdict, mutable List<LineComment>
      LineComment.kt                 – @Serializable class; file, line, type ("issue"|"suggestion"|"note"), body
      ChatMessage.kt                 – @Serializable data class; Role + content for chat history
      PRReviewRequest.kt             – @Serializable data class; parameter object for ClaudeService.reviewPR
    parser/
      DiffParser.kt                  – Kotlin object; unified diff parser; DiffFile / DiffLine types
    util/
      ProcessUtil.kt                 – expect object; findBinary(name, candidates); jvmMain actual uses java.io.File, jsMain actual uses Node.js fs
  src/jvmMain/kotlin/com/jinloes/prpilot/
    services/
      GitHubAuthService.kt           – Runs `gh auth token`; probes known gh binary paths
      ClaudeService.kt               – Shells out to `claude --print`; synchronous/blocking API
    services/stream/
      StreamEvent.kt                 – @Serializable DTO for claude stream-json events
      EventMessage.kt                – @Serializable DTO for message payload inside a stream event
      ContentBlock.kt                – @Serializable DTO for a content block (tool_use / text); AnySerializer handles Map<String,Any> input field
      AnySerializer.kt               – internal KSerializer<Any> for tool-use input map values
  src/commonMain/kotlin/com/jinloes/prpilot/
    services/
      GitHubService.kt               – GitHub REST API: search PRs, diff, draft review CRUD; Ktor + kotlinx.serialization; runBlocking wrappers for Java callers
      RunBlockingCompat.kt           – internal expect fun runBlockingCompat; bridges suspend to blocking for Java callers without JVM-only runBlocking in commonMain
      UrlEncode.kt (expect)          – expect fun urlEncode(value: String): String
  src/jvmMain/kotlin/com/jinloes/prpilot/
    services/
      GitHubAuthService.kt           – Runs `gh auth token`; probes known gh binary paths
      ClaudeService.kt               – Shells out to `claude --print`; synchronous/blocking API
      PendingReviewIndex.kt          – Local JSON index of saved drafts (~/.pr-pilot/pending-prs.json)
      PatternKnowledgeBase.kt        – Per-repo pattern knowledge file (~/.pr-pilot/patterns/) [unused in main flow]
      SeenPRSet.kt                   – Local JSON set of notified PR IDs (~/.pr-pilot/seen-prs.json)
      RunBlockingCompat.kt (actual/jvm) – actual delegates to kotlinx.coroutines.runBlocking
      UrlEncode.kt (actual/jvm)      – java.net.URLEncoder implementation
    services/stream/
      StreamEvent.kt                 – @Serializable DTO for claude stream-json events
      EventMessage.kt                – @Serializable DTO for message payload inside a stream event
      ContentBlock.kt                – @Serializable DTO for a content block (tool_use / text)
      AnySerializer.kt               – internal KSerializer<Any> for tool-use input map values
    util/
      ProcessUtil.kt (actual/jvm)    – actual object; java.io.File.isFile() check; @JvmStatic on findBinary
  src/jsMain/kotlin/com/jinloes/prpilot/
    services/
      RunBlockingCompat.kt (actual/js) – actual throws UnsupportedOperationException (JS has no blocking primitive)
      UrlEncode.kt (actual/js)       – encodeURIComponent implementation
    util/
      ProcessUtil.kt (actual/js)     – actual object; Node.js fs.existsSync / statSync check

intellij-plugin/                       – IntelliJ Platform plugin; depends on :core
  src/main/java/com/jinloes/prpilot/
    services/
      IntellijGitHubService.java     – @Service adapter: wraps core GitHubService with PluginSettings apiBase
      IntellijClaudeService.java     – Wrapper: dispatches core ClaudeService to pooled thread, callbacks to EDT
      PRNotificationService.java     – Background polling service; fires IDE balloon notifications
      PRNotificationStartup.java     – postStartupActivity that starts polling if enabled
    settings/
      PluginSettings.java            – PersistentStateComponent; stores all plugin settings
      PluginSettingsComponent.java   – Settings UI
      PluginSettingsConfigurable.java – Wires settings into IntelliJ settings tree under Tools
    ui/
      PRToolWindowFactory.java       – Creates WebviewPanel; shows JCEF-unavailable label when JCEF not supported
      WebviewPanel.java              – JCEF browser wrapper; loads React webview, wires Java↔JS bridge; accepts Project for repo detection
      RepoDetector.java              – Static helpers: detectCurrentRepo (walks .git/config) and parseOwnerRepo
  src/main/resources/
    META-INF/plugin.xml

webview/                               – Vite + React + TypeScript webview
  src/
    bridge/types.ts                  – IDE↔webview message types and sendToHost/onHostMessage helpers
    lib/utils.ts                     – cn() helper (clsx + tailwind-merge)
    components/ui/                   – shadcn/ui components (button, badge, alert, alert-dialog, context-menu, tooltip, select, toggle-group, scroll-area, textarea, separator, skeleton)
    components/PRList/               – PR list: state/role filters, repo selector, search, keyboard nav
    components/ReviewPane/           – Review pane: state machine, diff viewer, footer actions
    components/ReviewDisplay/        – Verdict badge + react-markdown summary display
    components/DiffViewer/           – Unified diff renderer with inline comment cards
    components/ChatPane/             – Ask Claude chat: react-markdown streaming replies, history per PR
    App.tsx                          – Root component; draggable column divider; seeds fixture data in dev
    main.tsx                         – React entry point
  components.json                    – shadcn/ui config (New York style, slate base, sky accent)
  tailwind.config.js                 – Tailwind v3 config (class dark mode, shadcn CSS var tokens, mono font stack)
  postcss.config.js                  – PostCSS: tailwindcss + autoprefixer
  package.json / vite.config.ts / tsconfig.json / index.html

vscode-extension/                      – VS Code extension (npm build, no Gradle tasks)
  src/
    extension.ts                       – Entry point; ClaudeReviewsViewProvider; wires all webview messages to github/claude services
    github.ts                          – GitHub API service: resolveToken (via gh CLI), searchPRs, getPRDiff, loadDraftReview, saveDraftReview, submitReview, deleteDraftReview, getExistingReviewsSummary, detectCurrentRepo
    claude.ts                          – Claude CLI service: reviewPR (stream-json), chat (plain --print), buildPrompt/buildChatPrompt/buildFocusedChatPrompt, cancelCurrentRequest
    core.d.ts                          – Ambient type declarations for the KMP-compiled core module (not yet used; requires @JsExport on Kotlin classes)
  package.json / tsconfig.json         – TypeScript build config; vscode engine ^1.85.0; contributes claude-reviews.githubBaseUrl + claude-reviews.reviewModel settings
  build.gradle                         – Empty placeholder so settings.gradle include is valid
```

---

## IntelliJ ↔ VS Code sync obligations

When logic changes in one host, the **parallel file in the other host must be updated too**. These pairs must stay in sync:

| Changed file | Must also update |
|---|---|
| `ClaudeService.kt` — prompt constants (`REVIEW_INSTRUCTIONS`, `CHAT_PERSONA`) | `vscode-extension/src/claude.ts` — same constants copied verbatim |
| `ClaudeService.kt` — `buildPrompt`, `buildChatPrompt`, `buildFocusedChatPrompt` | `vscode-extension/src/claude.ts` — same functions |
| `ClaudeService.kt` — stream-json parsing (`handleStreamEvent`, `textBuffer` fallback) | `vscode-extension/src/claude.ts` — `reviewPR` event loop |
| `GitHubService.kt` — `encodeBody` / `decodeReview` / `buildCommentArray` | `vscode-extension/src/github.ts` — same functions |
| `GitHubService.kt` — GitHub API calls (URL structure, headers, error handling) | `vscode-extension/src/github.ts` — `ghRequest` helper and all callers |
| `PRToolWindowFactory.buildQuery` — PR search query logic | `vscode-extension/src/github.ts` — `searchPRs` |
| `GitHubAuthService.findGhBinary` — known gh binary paths | `vscode-extension/src/github.ts` — `findGhBinary` |
| `webview/src/bridge/types.ts` — any `OutgoingMessage` or `IncomingMessage` shape | `WebviewPanel.java` (IntelliJ) AND `vscode-extension/src/extension.ts` — all message handlers |
| `PluginSettings` — add a new setting | `vscode-extension/package.json` contributes.configuration AND `vscode-extension/src/extension.ts` reader |

---

## Key design decisions

Only decisions that encode an active constraint future code must respect and that are not obvious from reading the source.

### Webview styling: shadcn/ui + Tailwind CSS v3
All webview UI uses **shadcn/ui** (New York style, zinc base, violet accent) built on Radix UI primitives, with **Tailwind CSS v3** for layout and utilities. Do not introduce new CSS-module files or ad-hoc inline style objects for layout — use Tailwind utilities. The only hand-crafted CSS file is `DiffViewer.css`, which handles the table-based diff grid where Tailwind utilities cannot reach individual table cells and pseudo-elements reliably.

`index.css` owns all CSS custom property definitions (the shadcn HSL token system). Do not hard-code color values in components — always use `hsl(var(--token))` references or the Tailwind color aliases defined in `tailwind.config.js`.

**Semantic status colors — required:** For any UI that reflects review verdict, comment type, or PR/review state, always use the `text-status-*` / `bg-status-*/10` / `border-status-*/50` Tailwind tokens (`approve`, `changes`, `comment`, `issue`, `suggestion`, `note`). Never use hardcoded palette classes like `text-emerald-400`, `text-red-400`, `text-sky-400`, or `text-amber-400` for status/verdict/comment-type semantics — these do not adapt to light/dark IDE themes. The token definitions live in `index.css` under `:root` and `.dark`; the Tailwind aliases live in `tailwind.config.js` under `colors.status`.

`react-markdown` + `remark-gfm` is the sole markdown renderer. Do not add a second markdown library or write a custom parser.

### Multi-module split: core vs. intellij-plugin
`core` is a Kotlin Multiplatform module (jvm + js targets) with zero IntelliJ Platform dependencies. Its existing Java sources live in `src/main/java` and are compiled as part of the `jvmMain` source set (wired via `tasks.named('compileJvmMainJava') { source fileTree('src/main/java') }`). `intellij-plugin` depends on `project(':core')` and Gradle attribute matching resolves the JVM variant automatically. This split enables `core` to be consumed by future hosts (VS Code extension, CLI, web app) without dragging in IntelliJ APIs.

### KMP Java source wiring — do not move source directories
`core/src/main/java` and `core/src/test/java` are registered directly on the `compileJvmMainJava` / `compileJvmTestJava` `JavaCompile` tasks. Do not move these directories to `src/jvmMain/java` — that would break the existing package structure and git history. New Kotlin files for the jvm target go in `src/jvmMain/kotlin`; new Kotlin files shared across targets go in `src/commonMain/kotlin`.

### Kotlin model/parser/util classes — Java interop conventions
Model classes (`PullRequest`, `ReviewResult`, `LineComment`, `ChatMessage`, `PRReviewRequest`) and `DiffParser`/`ProcessUtil` live in `commonMain/kotlin`. The Java service layer in `src/main/java` calls them:

- **Properties**: Kotlin properties generate JavaBean-style `getX()`/`setX()` accessors. Java callers must use `request.getPr()`, `request.getDiff()`, `file.getName()`, `line.getNewLineNum()`, etc. — never bare Kotlin property names from Java.
- **No record-compat methods**: `DiffLine`, `ChatMessage`, and `PRReviewRequest` are plain data classes — they do **not** expose no-arg `type()`, `content()`, `newLineNum()` methods. Java callers must use `getX()` getters, not record-style component accessors.
- **No `@JvmField` in commonMain**: `DiffFile.name` and `DiffFile.lines` are regular Kotlin properties accessible from Java via `getName()` / `getLines()`. `@JvmField` was removed — it cannot be applied to properties in a `class` body in the JS target.
- **No `@JvmStatic` in commonMain**: `DiffParser` is an `object`, so Java callers must use `DiffParser.INSTANCE.parseDiff(...)` and `DiffParser.INSTANCE.computeMaxColumns(...)`. `GitHubService` companion methods require `GitHubService.Companion.encodeBody(...)` etc. Do not add `@JvmStatic` to `object` or `companion object` members in `commonMain` — the annotation is not available in the JS stdlib.
- **JSON in `core`**: All JSON parsing in `core` uses `kotlinx.serialization` — no Jackson. `ReviewResult` and `LineComment` use backing fields with `@SerialName` to map JSON keys (`summary`, `verdict`, `lineComments`, `file`, `line`, `type`, `body`) to the underscore-named fields. `WebviewPanel` in `intellij-plugin` has its own `ObjectMapper` (with `KotlinModule`) for deserializing `ReviewResult` from the webview message; `intellij-plugin` depends on `jackson-module-kotlin:2.17.1` directly.

### KMP expect/actual — `ProcessUtil` and `runBlockingCompat`
Two platform-specific bridges live in commonMain as `expect` declarations:

- **`ProcessUtil`** (`util/ProcessUtil.kt`): `expect object ProcessUtil { fun findBinary(...) }`. The jvmMain `actual` uses `java.io.File.isFile()`; the jsMain `actual` uses the Node.js `fs` module (`existsSync` / `statSync`). Java callers use `ProcessUtil.INSTANCE.findBinary(...)` — no `@JvmStatic` is present.
- **`runBlockingCompat`** (`services/RunBlockingCompat.kt`): `internal expect fun <T> runBlockingCompat(block: suspend () -> T): T`. The jvmMain `actual` delegates to `kotlinx.coroutines.runBlocking`; the jsMain `actual` throws `UnsupportedOperationException` (JS has no blocking primitive). This bridges the synchronous Java API of `GitHubService` without placing a JVM-only `runBlocking` call in commonMain.

### `GitHubService` is stateless except for `apiBase`; uses Ktor + runBlocking
The core `GitHubService` takes `apiBase` as a constructor parameter. `IntellijGitHubService.core()` constructs a fresh instance per call so URL changes in settings take effect immediately without requiring restart or cache invalidation.

`GitHubService.HTTP_CLIENT` is a `companion object val` — it is shared across all per-call instances. Do not convert it to an instance property: doing so would create a new Ktor `HttpClient` (and its connection pool) on every API call.

All public `GitHubService` methods are **synchronous** from the Java caller's perspective — they wrap Ktor suspend functions with `runBlocking { }` internally. This lets `IntellijGitHubService.java` call them on a pooled thread without needing coroutines. Do not make them suspend without providing a blocking Java-callable wrapper.

`GitHubService` lives in `commonMain` and uses Ktor for HTTP + kotlinx.serialization for JSON — no Jackson, no java.net.http. The `urlEncode` expect/actual splits URL encoding: `URLEncoder.encode` on JVM, `encodeURIComponent` on JS.

### `ClaudeService` is synchronous; `IntellijClaudeService` owns threading
Core `ClaudeService.reviewPR()`, `chat()`, and `chatFocused()` are blocking — they run on the calling thread. `IntellijClaudeService` wraps each call in `executeOnPooledThread()` and dispatches callbacks to the EDT via `invokeLater()`. Threading is an IntelliJ concern, not a core concern.

`ClaudeService(String projectDir)` sets the working directory for all spawned `claude` processes to the project root. This lets the CLI discover and inject `CLAUDE.md` automatically — the plugin does not read or forward `CLAUDE.md` content manually. When `projectDir` is blank/null, the working directory falls back to `$HOME`.

### GitHub authentication — no stored token
The plugin never writes a token to disk. `GitHubAuthService.resolveToken()` runs `gh auth token` each time.

### Finding the `gh` binary
`GitHubAuthService.findGhBinary()` probes hard-coded paths before falling back to bare `"gh"`:
```
/opt/homebrew/bin/gh   ← Apple Silicon Homebrew
/usr/local/bin/gh      ← Intel Homebrew / manual
/usr/bin/gh            ← system package managers
/home/linuxbrew/.linuxbrew/bin/gh
```
**Why:** IntelliJ launched from the macOS Dock doesn't inherit the user's shell `PATH`. Wrapping in `zsh --login -c` doesn't source `~/.zshrc`, so tools added only there (asdf, mise, etc.) are still not found. `HOME` is set explicitly on the `ProcessBuilder` so `gh` can find its config.

### Claude invocation
The full prompt is written to `stdin` (not passed as a CLI arg) to avoid OS argument-length limits. `claude --print` enables non-interactive mode.

### Cancel support — `AtomicReference<Process>`
`cancelCurrentRequest()` uses `getAndSet(null)` to atomically read and clear `activeProcess` before calling `destroyForcibly()`. This eliminates the TOCTOU race a `volatile` field would leave between the null-check and the destroy call.

### IDE tools for type context
The review prompt instructs Claude to use IDE tools (via Claude Code's IDE MCP integration) when it needs type information — method signatures, field types, class hierarchies. No pre-baked type context is injected; Claude fetches what it needs on demand.

### Review prompt output schema
Output must be a strict JSON object: `summary` (string, max 800 chars, markdown with `## Overview` / `## Key Changes` / `## Risk Areas` sections), `verdict` (`"APPROVE"` | `"REQUEST_CHANGES"` | `"COMMENT"`), `lineComments` array (max 12) with `file`, `line` (positive int), `type` (`"issue"` | `"suggestion"` | `"note"`), `body` (≤300 chars). All untrusted input tags (`<pr_metadata>`, `<pr_description>`, `<prior_review>`, `<known_patterns>`, `<existing_reviews>`) are marked data-only in `REVIEW_INSTRUCTIONS` to guard against prompt injection.

### gh MCP tool prerequisite for review generation
Both the IntelliJ plugin and VS Code extension pass `diff = ""` in `PRReviewRequest` / `buildPrompt`. There is no inline `<diff>` path. `buildPrompt` always emits a `<fetch_diff>` block instructing Claude to run `gh pr diff <number> --repo <owner>/<repo>` before reviewing.

**Why:** Embedding the full diff in the prompt consumed significant context and limited the model's extended thinking budget. Letting Claude fetch the diff on demand eliminates that overhead and avoids the 80 KB size cap.

The plugin still fetches the diff over the GitHub API to render the diff viewer in the UI. It does not forward the diff text to the Claude prompt.

### stream-json result filtering
`ClaudeService.handleStreamEvent()` only appends to `resultBuffer` for `result` events with `subtype == "success"` and `is_error == false`. `StreamEvent` maps `"is_error"` via `@SerialName("is_error")` and `"session_id"` via `@SerialName("session_id")` — kotlinx.serialization does not translate snake_case automatically.

### Max-turns recovery via `--resume`
When a review process exits non-zero with `subtype == "error_max_turns"` in the stream-json output, `ClaudeService.runReview` checks the result event for a `session_id`. If one is present it automatically calls `runResume(sessionId, ...)`, which spawns a new `claude --resume <session_id> --max-turns 3` process and sends a nudge prompt ("Output the review JSON now…") to Claude. Claude already completed its analysis — the nudge prompts it to emit the JSON it has without further tool calls. The VS Code extension mirrors this logic in `resumeReview(...)`. If no `session_id` is present (e.g., older CLI versions), the original error is surfaced.

### GitHub draft encoding scheme
GitHub's review comment API returns `line: null` for outdated comments. `GitHubService.encodeBody()` embeds all comments as a compact JSON array inside an HTML comment in the review body:
```
<!-- claude-comments: [{"f":"src/Foo.java","l":42,"t":"issue","b":"body text"},...] -->
```
`decodeReview()` reads this block first, falling back to the API comment list for legacy drafts. `-->` is escaped to `-- >` inside HTML comment tags.

### Draft review creation — omit `event` field
Creating a GitHub pending review requires omitting `event` entirely from the POST payload. Setting `event: "PENDING"` is invalid and causes a 422.

### 422 fallback — body-first, then add comments individually
When creating a pending review with inline comments returns 422 (invalid path or line number), `saveDraftReview` falls back to: (1) create the review with an empty comments array, (2) POST each comment individually to `reviews/{id}/comments`. This keeps exactly one pending review in existence at all times. **Do not revert to the old probe-review approach** (creating a temp review per comment and deleting it) — delete failures leave orphaned pending reviews whose comments appear as duplicates in the PR diff view for the author.

### SSRF prevention
`PluginSettings.setGithubBaseUrl()` rejects any URL not starting with `https://`, falling back to `https://github.com`. This prevents a crafted base URL from forwarding GitHub tokens to an attacker-controlled host.

### Stale-commit detection on draft load
`PendingReviewIndex.Entry` includes `headSha` (the PR HEAD SHA at save time). `headSha()` returns `""` for entries serialized before this field was added (backward-compatible null guard in the accessor).

### Repo auto-detection
`RepoDetector.detectCurrentRepo()` walks up the directory tree to find `.git/config` (matches git's own behavior). `parseOwnerRepo()` treats scp-style `git@host:owner/repo` separately from `ssh://` URIs so the port number in `ssh://git@host:7999/owner/repo` is not mistaken for the path separator.

### Webview served via embedded HTTP server
`intellij-plugin/build.gradle` runs `npm run build` in `webview/` and copies `dist/**` into the plugin JAR as `webview/**`. At runtime, `WebviewPanel` starts a `com.sun.net.httpserver.HttpServer` on a random loopback port and streams classpath resources on demand (e.g. `GET /index.html` → `/webview/index.html`). The browser loads `http://127.0.0.1:PORT/`. This gives Chromium a proper HTTP same-origin context so ES modules load correctly. `file://` is not used — Chromium assigns `null` origin to every local file, which silently blocks ES module scripts even for same-directory resources.

### JCEF availability guard
`PRToolWindowFactory` checks `JBCefApp.isSupported()` before creating `WebviewPanel`. JCEF is unavailable in some IntelliJ variants (e.g., remote development thin clients). When JCEF is not available, a plain label is shown explaining the requirement; no fallback Swing UI is present.

### Webview PR filter state lives in `WebviewPanel`
PR list filters (`prStateFilter`, `assignedToMeFilter`, `reviewRequestedFilter`) are stored as `volatile` fields on `WebviewPanel`, not only in React state. When the user changes a filter, the webview sends a `refreshPRs` message with the new params; `WebviewPanel` stores them, then `PRToolWindowFactory.loadAndPushPRs` reads these fields on every call. This ensures the correct filters are applied regardless of what triggered the refresh (initial load, manual refresh, poll). Do not move filter state to be React-only — Java must own it so the query builder always has the current values.

---

## Testing conventions

**Every code change must include tests for any new or modified non-UI logic. Pure-Java service and utility code must have 100% branch coverage. Tests must be written as part of the same task — never deferred.**

**Checklist — before marking any coding task complete:**
1. Identify every new or modified non-UI method (service, utility, model, static helper).
2. Widen any `private` method that needs test access to package-private (never `public`).
3. Write tests covering every branch: happy path, edge cases, and error paths.
4. Run `./gradlew :core:jvmTest :intellij-plugin:unitTest` and confirm all tests pass.

- Core tests live under `core/src/jvmTest/kotlin/com/jinloes/prpilot/` mirroring the main source tree. Written in **Kotest FunSpec** (`io.kotest:kotest-runner-junit5:5.9.1` + `kotest-assertions-core:5.9.1`).
- IntelliJ-coupled tests live under `intellij-plugin/src/test/java/com/jinloes/prpilot/`.
- Core tests use **Kotest** (`FunSpec`, `context(...)`, `test(...)`, `shouldBe`, `shouldContain`, etc.) — not JUnit 5 or AssertJ.
- IntelliJ plugin tests use **JUnit 5** (`@Test`, `@Nested`, `@TempDir`) and **AssertJ** for assertions.
- Group related tests in `context("MethodName") { test("...") {} }` blocks.
- For temp directories in Kotest, use `beforeTest`/`afterTest` with `Files.createTempDirectory`.
- Tests must not depend on IntelliJ platform classes — pure-Java/Kotlin logic only.
- UI classes (anything extending `JPanel`, `JComponent`, etc.) are excluded from the coverage requirement.
- File-based classes use temp dirs — never write to `~/.pr-pilot` in tests.

---

## Build

```bash
./gradlew :intellij-plugin:buildPlugin   # produces intellij-plugin/build/distributions/*.zip
./gradlew :intellij-plugin:runIde        # launches a sandboxed IntelliJ with the plugin loaded
./gradlew spotlessApply                  # format all Java sources (runs automatically via Claude Code hook)
./gradlew spotlessCheck                  # verify formatting without modifying files
./gradlew check                                      # run all tests (jvmTest + unitTest wired into check)
./gradlew :core:jvmTest :intellij-plugin:unitTest   # run tests directly
./gradlew idea                           # regenerate .iml/.ipr files (run after adding new excludeDirs)
```

Note: `:core:test` is the old task name (plain Java plugin). The KMP equivalent is `:core:jvmTest`.

The `idea` Gradle plugin is applied to the root project and all subprojects. It configures which directories IntelliJ should exclude from indexing (`.gradle`, `.claude`, `build`, `node_modules`, `dist`, etc.). After editing `excludeDirs` in any `build.gradle`, either run `./gradlew idea` or use "Reload All Gradle Projects" in the IntelliJ Gradle tool window for the exclusions to take effect.

- Java 17, IntelliJ platform `2024.1` (IC), Gradle IntelliJ plugin `2.13.1`
- `sinceBuild = 253`, `untilBuild = 253.*`
- Kotlin Multiplatform `2.1.21`, Ktor `3.1.3`, kotlinx-serialization-json `1.8.1`, kotlinx-coroutines-core `1.10.2`
- Core commonMain deps: ktor-client-core, ktor-client-content-negotiation, ktor-serialization-kotlinx-json, kotlinx-serialization-json, kotlinx-coroutines-core, SLF4J API 2.0.13
- Core jvmMain additional deps: ktor-client-java, Commons Lang3 3.18.0, Commons Text 1.12.0, Commons Collections4 4.4, Commons IO 2.15.1
- Core jsMain additional deps: ktor-client-js
- Plugin-only deps: jackson-module-kotlin 2.17.1 (webview message deserialization), commons-io, commons-lang3, commons-text, commons-collections4
- Lombok is `compileOnly` in both modules; use `@Slf4j` for logging.
- `gradle.properties` sets `org.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=512m` (required for KMP + IntelliJ plugin builds).

**Coding rules (enforced on every PR):**
- **Apache Commons**: use `CollectionUtils.isEmpty`, `StringUtils.isNotBlank` / `defaultString`, `Strings.CS.removeStart` / `removeEnd`, `StringEscapeUtils.escapeHtml4` — no hand-rolled equivalents.
- **Jackson in `intellij-plugin` only**: `WebviewPanel` uses `ObjectMapper` with `KotlinModule` for deserializing webview messages. All JSON in `core` uses kotlinx.serialization. No raw `JsonNode` traversal in method bodies, no Gson.
- **Threading**: background I/O via `executeOnPooledThread()`; all UI updates via `invokeLater()` (EDT); no raw `Thread` creation; notification polling via `AppExecutorUtil.getAppScheduledExecutorService()`. Core services are synchronous — threading is always managed by IntelliJ adapters.
- **Google Java Style** (4-space indent, 100-col limit, Spotless enforced): no FQNs in method bodies (always add an import), descriptive local variable names, static imports before non-static, braces on all blocks.
- **Comments**: only where the *why* is non-obvious — intentionally swallowed exceptions, non-obvious platform workarounds, magic error codes. Never restate what the method name says.
- **Commit conventions**: no `Co-Authored-By` trailer.

---

## Settings persistence

`PluginSettings` (`claudeReviews.xml`) stores:
- `githubBaseUrl` (default `https://github.com`)
- `githubUsername` (display cache — re-fetched on demand)
- `notificationsEnabled` (default `false`)
- `notifyReviewRequested` (default `true`)
- `notifyStarredRepos` (default `false`)
- `notificationPollMinutes` (default `5`)
- `reviewModel` (default `""` — uses CLI default; non-empty passed as `--model <id>`)

No API keys or tokens are ever written to disk.

---

## Local data files

| Path | Purpose |
|------|---------|
| `~/.pr-pilot/pending-prs.json` | Index of PRs with saved drafts (owner, repo, number, title, savedAt, headSha) |
| `~/.pr-pilot/seen-prs.json` | Set of `owner/repo#number` strings already notified about |
| `~/.pr-pilot/patterns/{owner}%{repo}.md` | Per-repo verified pattern log (unused in main flow) |
