# Claude PR Reviews — IntelliJ Plugin

IntelliJ plugin that lists GitHub Pull Requests and generates AI-powered code reviews using the local `claude` CLI.

> **CLAUDE.md maintenance rule:** Update this file as part of every coding task.
> - Update "Project layout" when files are added or renamed.
> - Add to "Key design decisions" **only** when a non-obvious constraint is introduced that future code must respect and that is not obvious from reading the source.
> - Update "Settings persistence" when new settings are added.
> - Update "Local data files" when new persistent files are introduced.
> - Do **not** document how existing code works — that belongs in code comments or git history.

---

## Project layout

```
src/main/java/com/jinloes/claudereviews/
  model/
    PullRequest.java               – Plain data class (title, number, owner, repo, author, etc.)
    ReviewResult.java              – Holds summary, verdict, and a mutable List<LineComment>
    LineComment.java               – file, line, type ("issue"|"suggestion"|"praise"|"note"), body
    ChatMessage.java               – Role + content for chat history
    PRReviewRequest.java           – Parameter object passed to ClaudeService.reviewPR
  services/
    GitHubAuthService.java         – Runs `gh auth token`; probes known gh binary paths
    GitHubService.java             – GitHub REST API: search PRs, repo PRs, diff, draft review CRUD
    ClaudeService.java             – Shells out to `claude --print` via stdin; streams output to EDT
  services/stream/
    StreamEvent.java               – Jackson DTO for claude stream-json events
    EventMessage.java              – Jackson DTO for message payload inside a stream event
    ContentBlock.java              – Jackson DTO for a content block (tool_use / text)
    PendingReviewIndex.java        – Local JSON index of saved drafts (~/.claude-reviews/pending-prs.json); Entry includes headSha for stale-commit detection
    PatternKnowledgeBase.java      – Per-repo pattern knowledge file (~/.claude-reviews/patterns/) [unused in main flow; kept for future use]
    SeenPRSet.java                 – Local JSON set of notified PR IDs (~/.claude-reviews/seen-prs.json)
    PRNotificationService.java     – Background polling service; fires IDE balloon notifications
    PRNotificationStartup.java     – postStartupActivity that starts polling if enabled
  settings/
    PluginSettings.java            – PersistentStateComponent; stores all plugin settings
    PluginSettingsComponent.java   – Settings UI
    PluginSettingsConfigurable.java – Wires settings into IntelliJ settings tree under Tools
  util/
    ProcessUtil.java               – findBinary(name, candidates) for locating CLI binaries
  ui/
    PRToolWindow.java              – Main tool window: PR list, filter/repo combos, review panel, chat
    PRToolWindowFactory.java       – Creates PRToolWindow on demand
    ReviewPanel.java               – Syntax-highlighted diff viewer with inline CommentCards
    ChatPanel.java                 – Chat UI: streaming bubbles, commonmark-rendered responses
    CommentCard.java               – Editable inline comment card with dismiss callback; constructors: (comment, onDismiss) and (comment, onDismiss, maxPixelWidth)
    DiffParser.java                – Pure-Java unified diff parser; DiffFile / DiffLine types
    ThemeColors.java               – Centralized theme-aware color palette (light/dark detection)
  highlighting/
    DiffHighlighter.java           – Syntax highlighting facade delegating to TreeSitterHighlighter
    TreeSitterHighlighter.java     – Tree-sitter-based highlighter; lazy-init with graceful fallback
src/main/resources/highlights/
  java.scm kotlin.scm python.scm go.scm javascript.scm typescript.scm rust.scm bash.scm proto.scm
src/main/resources/META-INF/plugin.xml
build.gradle
```

---

## Key design decisions

Only decisions that encode an active constraint future code must respect and that are not obvious from reading the source.

### GitHub authentication — no stored token
`GitHubAuthService.resolveToken()` runs `gh auth token` each time a token is needed. The plugin never writes a token to disk.

### Repo auto-detection
`PRToolWindow.detectCurrentRepo(basePath)` reads `.git/config` directly (no process) and parses the `origin` remote URL via `parseOwnerRepo()`. The detected repo is prepended to `repoCombo` and selected so the current IntelliJ project is always available without requiring the user to have starred it.

### Finding the `gh` binary
`GitHubAuthService.findGhBinary()` probes hard-coded paths before falling back to bare `"gh"`:
```
/opt/homebrew/bin/gh   ← Apple Silicon Homebrew
/usr/local/bin/gh      ← Intel Homebrew / manual
/usr/bin/gh            ← system package managers
/home/linuxbrew/.linuxbrew/bin/gh
```
**Why:** IntelliJ launched from the macOS Dock doesn't inherit the user's shell `PATH`. Wrapping in `zsh --login -c` doesn't source `~/.zshrc`, so tools added only there (asdf, mise, etc.) are still not found. `HOME` is set explicitly on the `ProcessBuilder` so `gh` can find its config.

### Finding the `claude` binary
`ClaudeService.buildProcess()` augments the inherited `PATH` with `/usr/local/bin`, `/opt/homebrew/bin`, `/usr/bin` rather than probing hard-coded paths. The `claude` CLI is invoked directly (not through a login shell).

### Claude invocation
`claude --print` enables non-interactive mode. The full prompt is written to `stdin` (not passed as a CLI arg) to avoid OS argument-length limits. When a review model is configured in Settings, `--model <id>` is appended to the CLI arguments.

### Cancel support — `AtomicReference<Process>`
`ClaudeService` uses `AtomicReference<Process> activeProcess`. `cancelCurrentRequest()` uses `getAndSet(null)` to atomically read and clear the field before calling `destroyForcibly()`. This eliminates the TOCTOU race that a `volatile` field would leave between the null-check and the destroy call.

### Review prompt structure
The prompt instructs Claude as a senior staff engineer. All untrusted input sections are wrapped in XML tags (`<diff>`, `<pr_description>`, `<project_conventions>`, `<known_patterns>`, `<existing_reviews>`, `<prior_review>`) and marked as data-only to guard against prompt injection. Output is a strict JSON schema: `summary` (string), `verdict` (`"APPROVE"` | `"REQUEST_CHANGES"` | `"COMMENT"`), and `lineComments` array with `file`, `line` (positive int), `type` (`"issue"` | `"suggestion"` | `"note"`), `body` (≤300 chars). Priority checklist: correctness → security → test coverage (100% branch on non-trivial changes) → performance → design.

### stream-json result filtering
`ClaudeService.handleStreamEvent()` only appends to `resultBuffer` for `result` events with `subtype == "success"` and `is_error == false`. This prevents error results or tool-result events from being concatenated with the review JSON, which would produce a malformed JSON parse error surfaced to the user. `StreamEvent` maps `"is_error"` via `@JsonProperty("is_error")` since Jackson's default naming strategy does not translate snake_case to camelCase.

### Chat prompt structure
Two paths: `buildChatPrompt` (general follow-up — includes full PR context and history wrapped in XML turn tags) and `buildFocusedChatPrompt` (right-click actions — wraps only a code snippet in `<code_context>`, no PR comment list). Both mark their XML-delimited content as untrusted data.

### GitHub draft encoding scheme
GitHub's review comment API returns `line: null` for outdated comments. To preserve line numbers across save/load cycles, `GitHubService.encodeBody()` embeds all comments as a compact JSON array inside an HTML comment in the review body:
```
<!-- claude-comments: [{"f":"src/Foo.java","l":42,"t":"issue","b":"body text"},...] -->
```
`decodeReview()` reads from this block first (reliable), falling back to the GitHub API comment list only for legacy drafts without the block. `-->` is escaped to `-- >` everywhere inside HTML comment tags.

### Draft review creation — omit `event` field
Creating a GitHub pending (draft) review requires omitting the `event` field entirely from the POST payload. Setting `event: "PENDING"` is invalid and causes a 422.

### SSRF prevention
`PluginSettings.setGithubBaseUrl()` rejects any URL that does not start with `https://`, falling back to `https://github.com`. This prevents a crafted base URL from forwarding GitHub tokens to an attacker-controlled host.

### `PatternKnowledgeBase` filename separator
`fileFor()` uses `%` as the owner/repo separator (e.g. `owner%repo.md`) — `%` cannot appear in GitHub owner or repo names. `fileFor()` also verifies the resolved canonical path starts with the canonical base dir to prevent path traversal via crafted owner/repo strings.

### Stale-commit detection on draft load
`PendingReviewIndex.Entry` includes `headSha` (the PR HEAD SHA at save time). When `loadDraftFromGitHub()` finds a draft, it calls `getPRHeadSha()` and compares with the stored SHA. If they differ, a ⚠ warning is shown in the status bar but the draft is still loaded. `headSha()` returns `""` for entries serialized before this field was added (backward-compatible null guard in the accessor).

### Auto-save on comment dismiss
When a review is in `DRAFT_PRESENT` state (`pendingReviewId != null`), dismissing a comment triggers a background `autoSaveDraft()` call — a silent fire-and-forget save that does not update the status bar. This keeps the GitHub draft in sync without interrupting the user.

---

## Testing conventions

**Every code change must include tests for any new or modified non-UI logic. Pure-Java service and utility code must have 100% branch coverage. Tests must be written as part of the same task — never deferred.**

**Checklist — before marking any coding task complete:**
1. Identify every new or modified non-UI method (service, utility, model, static helper).
2. Widen any `private` method that needs test access to package-private (never `public`).
3. Write tests covering every branch: happy path, edge cases, and error paths.
4. Run `./gradlew unitTest` and confirm all tests pass.

- Tests live under `src/test/java/com/jinloes/claudereviews/` mirroring the main source tree.
- Use **JUnit 5** (`@Test`, `@Nested`, `@TempDir`) and **AssertJ** for assertions.
- Group related tests in `@Nested` inner classes named after the method or scenario.
- Tests must not depend on IntelliJ platform classes — pure-Java logic only.
- UI classes (anything extending `JPanel`, `JComponent`, etc.) are excluded from the coverage requirement.
- File-based classes use `@TempDir` — never write to `~/.claude-reviews` in tests.

---

## Build

```bash
./gradlew buildPlugin          # produces build/distributions/*.zip
./gradlew runIde               # launches a sandboxed IntelliJ with the plugin loaded
./gradlew spotlessApply        # format all Java sources (runs automatically via Claude Code hook)
./gradlew spotlessCheck        # verify formatting without modifying files
./gradlew unitTest             # run tests
```

- Java 17, IntelliJ platform `2024.1` (IC), Gradle IntelliJ plugin `2.13.1`
- `sinceBuild = 253`, `untilBuild = 253.*`
- Runtime dependencies: Jackson Databind 2.17.1, CommonMark 0.22.0, Commons Lang3 3.17.0, Commons Text 1.12.0, Commons Collections4 4.4, Commons IO 2.16.1, tree-sitter-ng (macOS/Linux/Windows, x86_64 + aarch64)
- Lombok is `compileOnly`; use `@Slf4j` for logging.

**Coding rules (enforced on every PR):**
- **Apache Commons**: use `CollectionUtils.isEmpty`, `StringUtils.isNotBlank` / `defaultString`, `Strings.CS.removeStart` / `removeEnd`, `StringEscapeUtils.escapeHtml4` — no hand-rolled equivalents.
- **Jackson only**: all JSON via `ObjectMapper` with typed records — no raw `JsonNode` traversal in method bodies, no Gson.
- **Threading**: background I/O via `executeOnPooledThread()`; all UI updates via `invokeLater()` (EDT); no raw `Thread` creation; notification polling via `AppExecutorUtil.getAppScheduledExecutorService()`.
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
|------|--------|
| `~/.claude-reviews/pending-prs.json` | Index of PRs with saved drafts (owner, repo, number, title, savedAt) |
| `~/.claude-reviews/seen-prs.json` | Set of `owner/repo#number` strings already notified about |
| `~/.claude-reviews/patterns/{owner}%{repo}.md` | Per-repo verified pattern log; injected into future review prompts |