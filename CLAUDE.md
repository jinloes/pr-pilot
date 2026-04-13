# Claude PR Reviews — IntelliJ Plugin

IntelliJ plugin that lists GitHub Pull Requests and generates AI-powered code reviews using the local `claude` CLI.

> **CLAUDE.md maintenance rule:** This file must be updated as part of every coding task. Before finishing any task:
> - Add new features to the "Major features" list
> - Update "Project layout" when files are added or renamed
> - Update "Key design decisions" when architectural choices change
> - Update "Settings persistence" when new settings are added
> - Update "Local data files" when new persistent files are introduced

---

## Major features

- **PR browser** — lists open PRs filtered by Assigned / Review Requested / Created by me, with an optional per-repo scope selector
- **AI code review** — sends the PR diff to the local `claude` CLI and produces a summary, inline line comments categorised by type (issue / suggestion / praise / note), and a verdict (Approve / Request Changes / Comment); the prompt automatically includes project conventions (CLAUDE.md, AGENTS.md, or a style sample) and enforces a 100% branch-coverage check on non-trivial changes
- **Syntax-highlighted diff viewer** — colour-coded add/del lines with line numbers in the gutter and full semantic token highlighting for Java, Python, Go, Kotlin, JavaScript/JSX, TypeScript/TSX, Rust, Bash, and Proto
- **GitHub-backed draft reviews** — saves and loads a pending review on GitHub (summary, verdict, inline comments); automatically detects an existing pending review when a PR is selected
- **Draft submit** — publishes the pending review with a chosen event (Approve / Request Changes / Comment) and an optional top-level comment entered in the submit dialog
- **Load Draft dialog** — lists previously saved drafts from a local index so you can reload any saved PR
- **Comment editing** — inline comment cards are editable in-place; dismissing a card removes it from the review so it is excluded from the next save
- **Comment navigation** — ▲/▼ buttons scroll to each comment in document order; the count label updates as cards are dismissed
- **Chat panel** — collapsible follow-up chat below the diff; each message is sent with the PR metadata, summary, comments, and verdict as automatic context
- **Selection context** — selecting text in the diff, or clicking a line-number in the gutter, prepends a quoted context block to the next chat message
- **Right-click "Ask Claude" menu** — right-clicking the diff offers *Explain this line*, *Explain selection*, and *Summarize this file* actions, all sent with the full review context
- **Add-comment button** — hovering over any diff line reveals a "+" button that inserts a new blank comment card at that line for immediate typing
- **Markdown rendering** — Claude responses render fenced code blocks with syntax highlighting, bold/italic, inline code, numbered/bullet lists, and headers
- **PR notifications** — optional background polling fires IDE balloon notifications for new review-requested PRs and/or new PRs on starred repos (configurable interval, default 5 min)
- **Verify pattern in repo** — right-clicking a comment card → "Verify pattern in repo" asks Claude to check whether the flagged pattern is an established convention in the repo; findings are saved to a per-repo knowledge file and injected into future review prompts so verified patterns are not re-flagged
- **PR summary sidebar** — the AI-generated summary is shown in a right-side panel (always visible alongside the diff); sections: Overview, Key Changes, Risk Areas
- **Width-constrained comment cards** — comment cards cap their width to match the diff's longest line and align with the diff gutter
- **Auto-cleanup of stale drafts** — when a merged PR is selected, the plugin automatically deletes its pending GitHub draft and removes it from the local index

---

## Project layout

```
src/main/java/com/jinloes/claudereviews/
  model/
    PullRequest.java               – Plain data class (title, number, owner, repo, author, etc.)
    ReviewResult.java              – Holds summary, verdict, and a mutable List<LineComment>
    LineComment.java               – file, line, type ("issue"|"suggestion"|"praise"|"note"), body
    ChatMessage.java               – Role + content for chat history
    PRReviewRequest.java           – Parameter object: PullRequest + diff + knownPatterns, passed to ClaudeService.reviewPR
    ReviewDraft.java               – (legacy, unused)
  services/
    GitHubAuthService.java         – Runs `gh auth token` to resolve credentials; probes known gh binary paths
    GitHubService.java             – GitHub REST API: search PRs, repo PRs, diff, draft review CRUD
    ClaudeService.java             – Shells out to `claude --print` via stdin; streams chunks back to EDT
  services/stream/
    StreamEvent.java               – Jackson DTO for claude stream-json events; Optional accessors for nullable fields
    EventMessage.java              – Jackson DTO for the message payload inside a stream event
    ContentBlock.java              – Jackson DTO for a single content block (tool_use / text) inside a message
    PendingReviewIndex.java        – Local JSON index of saved drafts (~/.claude-reviews/pending-prs.json)
    PatternKnowledgeBase.java      – Per-repo Markdown knowledge file of verified patterns (~/.claude-reviews/patterns/)
    SeenPRSet.java                 – Local JSON set of notified PR IDs (~/.claude-reviews/seen-prs.json)
    PRNotificationService.java     – Background polling service; fires IDE balloon notifications
    PRNotificationStartup.java     – postStartupActivity that starts polling if notifications are enabled
  settings/
    PluginSettings.java            – PersistentStateComponent; stores githubBaseUrl, username, notification prefs
    PluginSettingsComponent.java   – Settings UI: base URL, auth status, notification toggles + interval
    PluginSettingsConfigurable.java – Wires settings component into IntelliJ settings tree under Tools
  ui/
    PRToolWindow.java              – Main tool window: PR list, filter/repo combos, review panel, chat panel
    PRToolWindowFactory.java       – Creates PRToolWindow on demand
    ReviewPanel.java               – Syntax-highlighted diff viewer with inline CommentCards; right-click menus
    ChatPanel.java                 – Chat UI: streaming bubbles, selection polling, code-block rendering
    CommentCard.java               – Editable inline comment card with dismiss callback
    GenerationProgressBar.java     – Indeterminate progress bar shown during generation
  highlighting/
    DiffHighlighter.java           – Syntax highlighting facade delegating to TreeSitterHighlighter; Apache Commons Text htmlEscape
    TreeSitterHighlighter.java     – Tree-sitter-based highlighter using io.github.bonede:tree-sitter-ng; lazy-init with graceful fallback; highlight queries bundled as resources
src/main/resources/highlights/
  java.scm kotlin.scm python.scm go.scm javascript.scm typescript.scm rust.scm bash.scm proto.scm
                               – tree-sitter highlight query files (nvim-treesitter format)
src/main/resources/META-INF/plugin.xml
build.gradle
```

---

## Key design decisions

### GitHub authentication — no stored token
The plugin never stores a GitHub token. `GitHubAuthService.resolveToken()` runs `gh auth token` each time a token is needed. Token lives only in the `gh` CLI keychain/config.

### Finding the `gh` binary
`GitHubAuthService.findGhBinary()` probes hard-coded paths before falling back to bare `"gh"`:
```
/opt/homebrew/bin/gh   ← Apple Silicon Homebrew
/usr/local/bin/gh      ← Intel Homebrew / manual
/usr/bin/gh            ← system package managers
/home/linuxbrew/.linuxbrew/bin/gh
```
**Why:** IntelliJ launched from the macOS Dock doesn't inherit the user's shell `PATH`. Wrapping in `zsh --login -c` was tried first but doesn't source `~/.zshrc`, so tools added to `PATH` only there (asdf, mise, etc.) were still not found. `HOME` is also set explicitly on the `ProcessBuilder` so `gh` can find its config.

### Finding the `claude` binary
`ClaudeService.buildProcess()` augments the inherited `PATH` with `/usr/local/bin`, `/opt/homebrew/bin`, `/usr/bin` rather than probing. The `claude` CLI is invoked directly (not through a login shell).

### Claude invocation
`claude --print` enables non-interactive mode. The full prompt is written to `stdin` (not passed as a CLI arg) to avoid OS argument-length limits. Output is streamed in 512-char chunks and appended to the review text area on the EDT.

### Review prompt structure (`ClaudeService.buildPrompt`)
The review prompt instructs Claude as a "senior software engineer" focused on bugs, security, and design (not linter-enforceable style). It defines a strict JSON schema and includes:
- **Line numbering rule**: take the `+start` number from each `@@ -old +new @@` header, count forward +1 for context and added lines, skip deleted lines.
- **Comment body template**: every body must state the problem, why it matters, and a concrete fix.
- **Priority-ordered checklist**: correctness → security → test coverage (100% branch on non-trivial changes) → performance → design.
- **Typed severities**: `issue` = must fix (triggers REQUEST_CHANGES verdict); `suggestion` = non-blocking; `note` = informational.
- **Verdict criteria**: APPROVE if no issues; REQUEST_CHANGES if any issue; COMMENT for questions without a blocking concern.

### Diff truncation
`GitHubService.getPRDiff()` truncates diffs to 80 KB (`MAX_DIFF_BYTES = 80_000`) to stay within reasonable context limits.

### Syntax highlighting — tree-sitter hunk-level parsing
`TreeSitterHighlighter.colorHunk(lines, extension)` is the sole highlighting entry point. `ReviewPanel.precomputeSpans()` groups diff lines into hunks (split at `DiffLine.hunkStart()` boundaries) and calls `DiffHighlighter.colorHunk()`, which delegates to `TreeSitterHighlighter`. The entire hunk is joined with `\n` and fed to the tree-sitter parser as one string so multi-line constructs (Javadoc, block comments, text blocks) resolve correctly. Captures are priority-sorted by `patternIndex` ascending (more specific patterns overwrite general ones) and painted into a `charColors[]` array, which is then merged into per-line `Span` lists. Languages without a grammar (or parse failures) return single `FG_COLOR` spans.

`TreeSitterHighlighter` initialises lazily on first call, catching `UnsatisfiedLinkError` so any native-library failure degrades gracefully. Highlight queries live in `src/main/resources/highlights/` (adapted from nvim-treesitter).

### Syntax highlighting — predicate evaluation
nvim-treesitter query files use `#lua-match?`, `#eq?`, `#any-of?`, and `#match?` predicates to narrow general captures (e.g. `(identifier) @type` only for uppercase identifiers). `TreeSitterHighlighter.parsePredicates` reads `TSQueryPredicateStep[]` arrays from `TSQuery.getPredicateForPattern` and parses them into `QueryPredicate` records. During match iteration, predicates are evaluated via `evaluatePredicates`; matches that fail a predicate are skipped entirely. Lua character-class escapes (`%d`, `%u`, `%l`, `%a`, etc.) are converted to Java regex via `luaPatternToJava` and matched with `Matcher.find()` (partial matching, consistent with Lua `string.match` semantics).

### Syntax highlighting — in-quote suppression
`buildSpans` calls `buildInQuoteMap(source)` to precompute a boolean array marking char positions that fall strictly inside `"..."` pairs. Any capture whose char start is in a quoted region is skipped before being added to `captureRanges`. This fixes a class of grammar bugs where tree-sitter error recovery re-tokenizes string content and emits spurious keyword captures (e.g. the proto grammar treating `rpc` in `/grpc/` as the `rpc` keyword when parsing an import path as a fragment without a `syntax = "proto3"` header). The check is language-agnostic and has negligible cost.

### Syntax highlighting — capture-name to colour mapping
`TreeSitterHighlighter.captureColor` maps tree-sitter capture names to hardcoded GitHub dark-mode colours from `@primer/primitives` (dark scale). Unknown capture names (e.g. `@variable`, `@operator`, `@spell`) return `null` and are skipped — this prevents `@spell` annotations on comment nodes from overwriting the `@comment` colour, and means call sites (`@function.call`) are rendered in plain `FG_COLOR` just like on GitHub (only declarations get colour). No IntelliJ `EditorColorsScheme` or `DefaultLanguageHighlighterColors` is involved in colour resolution.

### Syntax highlighting — colour palette
All token colours are hardcoded GitHub dark mode values from `@primer/primitives` (dark scale) — no IntelliJ colour scheme involvement: `#ff7b72` (scale.red[3]) keywords; `#ffa657` (scale.orange[2]) declarations; `#79c0ff` (scale.blue[2]) constants/numbers/builtins; `#a5d6ff` (scale.blue[1]) strings; `#8b949e` (scale.gray[3]) comments; `#e6edf3` default foreground for everything else.

### GitHub Enterprise
If `githubBaseUrl` is not `https://github.com`, the REST API base is derived as `<base>/api/v3` and `gh auth token --hostname <host>` is used.

### JSON serialization — Jackson with Optional accessors
All JSON parsing and serialization uses `com.fasterxml.jackson.databind.ObjectMapper`. `JsonNode`/`ArrayNode`/`ObjectNode` replace Gson's `JsonElement`/`JsonArray`/`JsonObject`. `TypeReference<T>` replaces Gson's `TypeToken<T>` for generic deserialization. Pretty-printing uses `MAPPER.enable(SerializationFeature.INDENT_OUTPUT)`.

Stream-event DTOs (`StreamEvent`, `EventMessage`, `ContentBlock`) use `@Data @Getter(AccessLevel.NONE)` — Lombok generates setters that Jackson uses for deserialization, and all field accessors are written manually. Nullable fields expose `Optional<T>` getters (`Optional.ofNullable(field)`) while non-nullable fields return their type directly.

### Threading model
Background I/O uses `ApplicationManager.getApplication().executeOnPooledThread()`. All UI updates go through `ApplicationManager.getApplication().invokeLater()` (EDT). No raw `Thread` creation. Notification polling uses `AppExecutorUtil.getAppScheduledExecutorService()`.

### GitHub draft review round-trip fidelity
GitHub's review comment API returns `line: null` for comments that become outdated when the PR is updated. To preserve line numbers across save/load cycles, `GitHubService.encodeBody()` embeds all comments as a compact JSON array inside an HTML comment in the review body:
```
<!-- claude-comments: [{"f":"src/Foo.java","l":42,"t":"issue","b":"body text"},...] -->
```
`decodeReview()` reads from this block first (reliable). If the block is absent (old draft), it falls back to parsing the GitHub API comment list with `original_line` as a secondary fallback.

### Draft review creation
Creating a GitHub pending (draft) review requires omitting the `event` field entirely from the POST payload. Setting `event: "PENDING"` is invalid and causes a 422. If inline comments cause a 422 (invalid path/line for that commit), the request is retried with an empty comments array so the review body is always persisted.

### Duplicate comment prevention
Two guards prevent inline comments from appearing twice on GitHub:
1. **Deduplication at save time** — `saveDraftReview()` tracks `(file, line, body)` triples in a `LinkedHashSet` and skips duplicates. Defends against Claude returning the same comment twice while still allowing distinct comments on the same line.
2. **Submit locks the save button** — `PRToolWindow.submitDraftReview()` disables `saveDraftButton` immediately (alongside `submitButton`) before the background HTTP call. This closes a race where the user could click "Save Draft" while a submit was in flight, causing a new pending draft to be created after the submitted review was no longer PENDING, and then submitted a second time.

### Selection context persistence
`ReviewPanel.getSelectedText()` only updates `lastKnownSelection` when there is an active non-empty selection — it never clears it on focus loss. This lets the selection survive when the user clicks into the chat input. `clearSelection()` resets it explicitly on new PR load.

### Comment dismissal
`CommentCard` accepts a `Consumer<CommentCard> onDismiss` callback. When the × button is clicked, `ReviewPanel.onCardDismissed()` removes the card from `commentCards`, removes the `LineComment` from `result.getLineComments()` (always a mutable `ArrayList`), clamps `currentCommentIndex`, and fires the `onCommentRemoved` runnable so `PRToolWindow` can refresh the nav count.

---

## Testing conventions

**Rule: every code change must include tests for any new or modified non-UI logic. Pure-Java service and utility code must have 100% branch coverage.**

- Run tests with `./gradlew unitTest`
- Tests live under `src/test/java/com/jinloes/claudereviews/` mirroring the main source tree.
- Use **JUnit 5** (`@Test`, `@Nested`, `@TempDir`) and **AssertJ** for assertions.
- Group related tests inside `@Nested` inner classes named after the method or scenario (e.g., `@Nested class InlineHtml`, `@Nested class RoundTrip`).
- Methods that need to be callable from tests are widened to package-private (drop `private`); never make them `public` just for tests.
- Tests must not depend on IntelliJ platform classes (`ApplicationManager`, `JBLabel`, etc.) — pure-Java logic only.
- UI classes (anything extending `JPanel`, `JComponent`, etc.) are excluded from the coverage requirement as they require a display to render.
- File-based classes (`PendingReviewIndex`, `SeenPRSet`, `PatternKnowledgeBase`) use `@TempDir` for isolation — never write to `~/.claude-reviews` in tests.
- `DiffHighlighter.htmlEscape` is a **token renderer** that converts spaces to `&nbsp;` — test inputs that contain spaces will have them replaced.

---

## Build

```bash
./gradlew buildPlugin          # produces build/distributions/*.zip
./gradlew runIde               # launches a sandboxed IntelliJ with the plugin loaded
./gradlew spotlessApply        # format all Java sources (runs automatically via Claude Code hook)
./gradlew spotlessCheck        # verify formatting without modifying files (useful in CI)
```

- Java 17, IntelliJ platform `2024.1` (IC), Gradle IntelliJ plugin `2.13.1`
- `sinceBuild = 253`, `untilBuild = 253.*`
- No bundled plugin dependencies — syntax highlighting is handled entirely by tree-sitter, so the plugin works in any IntelliJ-based IDE without requiring `com.intellij.java`
- Runtime dependencies: `com.fasterxml.jackson.core:jackson-databind:2.17.1`, `org.apache.commons:commons-lang3:3.17.0`, `org.apache.commons:commons-text:1.12.0`, `org.apache.commons:commons-collections4:4.4`, `commons-io:commons-io:2.16.1`, `io.github.bonede:tree-sitter:0.26.3`, `io.github.bonede:tree-sitter-java:0.23.5`, `io.github.bonede:tree-sitter-proto:main-a` (native libs bundled for macOS/Linux/Windows on x86_64 + aarch64)
- **Apache Commons preference**: use `CollectionUtils.isEmpty` (collections4), `StringUtils.isNotBlank` / `StringUtils.defaultString` (lang3), `Strings.CS.removeStart` / `Strings.CS.removeEnd` (lang3 3.17+), and `StringEscapeUtils.escapeHtml4` (commons-text) in preference to hand-rolled null/empty/prefix checks
- Lombok is `compileOnly` (annotation processing only, not shipped); use `@Slf4j` for logging — Lombok generates the `log` field automatically, no manual `Logger` declaration needed
- **Code style**: Spotless + Google Java Format AOSP variant (4-space indent, 100-col limit). A `Stop` hook in `.claude/settings.json` runs `./gradlew spotlessApply` automatically at the end of every Claude response (applies to all contributors).
- **Google Java Style Guide compliance** (https://google.github.io/styleguide/javaguide.html): no FQNs in method bodies (always add an import), descriptive local variable names (not `hl`, `attrs`), static imports before non-static, braces on all blocks.
- **Commenting policy**: add inline comments only where the *why* is non-obvious. Good candidates: intentionally swallowed exceptions (explain what non-fatal condition is expected), non-obvious OS/platform workarounds (e.g. IntelliJ Dock launch not inheriting PATH), magic error codes or string sentinels that require domain knowledge to decode (e.g. POSIX `error=2` = ENOENT), and deliberate algorithmic choices that a reader might be tempted to "fix" (e.g. a small read buffer to enable incremental streaming). Do not comment self-evident code or restate what the method name already says.

---

## Settings persistence

`PluginSettings` is an `@State` application-level service persisted to `claudeReviews.xml`. It stores:
- `githubBaseUrl` (default `https://github.com`)
- `githubUsername` (display cache only — re-fetched on demand)
- `notificationsEnabled` (default `false`)
- `notifyReviewRequested` (default `true`)
- `notifyStarredRepos` (default `false`)
- `notificationPollMinutes` (default `5`)

No API keys or tokens are ever written to disk.

---

## Local data files

| Path | Purpose |
|------|---------|
| `~/.claude-reviews/pending-prs.json` | Index of PRs with saved drafts (owner, repo, number, title, savedAt) |
| `~/.claude-reviews/seen-prs.json` | Set of `owner/repo#number` strings already notified about |
| `~/.claude-reviews/patterns/{owner}_{repo}.md` | Per-repo Markdown log of verified pattern findings; injected into future review prompts |