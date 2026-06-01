# PR Pilot Architecture

IntelliJ and VS Code extension that lists GitHub Pull Requests and generates AI-powered code reviews using a local AI CLI/runtime (Claude Code or GitHub Copilot, selected per-host in settings).

## Scope

- Keep this file focused on stable architecture and design constraints.
- Put workflow/checklist instructions in `AGENTS.md`.
- Put volatile version inventory in build manifests unless a version is itself an architecture constraint.

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
      ReviewProvider.kt              – enum (CLAUDE | COPILOT); fromId(id) Java-friendly factory
    parser/
      DiffParser.kt                  – Kotlin object; unified diff parser; DiffFile / DiffLine types
    util/
      ProcessUtil.kt                 – expect object; findBinary(name, candidates); jvmMain actual uses java.io.File, jsMain actual uses Node.js fs
  src/commonMain/kotlin/com/jinloes/prpilot/services/
      GitHubService.kt               – GitHub REST API: search PRs, diff, draft review CRUD; Ktor + kotlinx.serialization; blocking wrappers for Java callers
      RunBlockingCompat.kt           – expect bridge from suspend to blocking API for Java callers
      UrlEncode.kt                   – expect URL encoding helper
  src/jvmMain/kotlin/com/jinloes/prpilot/services/
      GitHubAuthService.kt           – Runs `gh auth token`; probes known gh binary paths
      ClaudeService.kt               – Shells out to `claude --print`; synchronous/blocking API
      CopilotService.kt              – Uses the official Copilot Java SDK to drive local `copilot`; mirrors ClaudeService API
      CopilotModelDiscovery.kt       – Runs `copilot help config` once per session and caches model list
      PendingReviewIndex.kt          – Local JSON index of saved drafts (~/.pr-pilot/pending-prs.json)
      SeenPRSet.kt                   – Local JSON set of notified PR IDs (~/.pr-pilot/seen-prs.json)
  src/jsMain/kotlin/com/jinloes/prpilot/
      services/RunBlockingCompat.kt  – JS actual throws UnsupportedOperationException
      services/UrlEncode.kt          – JS actual uses encodeURIComponent
      util/ProcessUtil.kt            – JS actual uses Node fs.existsSync/statSync

intellij-plugin/                       – IntelliJ plugin host; depends on :core
  src/main/java/com/jinloes/prpilot/
    services/
      IntellijGitHubService.java
      IntellijClaudeService.java
      PRNotificationService.java
      PRNotificationStartup.java
    settings/
      PluginSettings.java
      PluginSettingsComponent.java
      PluginSettingsConfigurable.java
    ui/
      PRToolWindowFactory.java
      WebviewPanel.java
      RepoDetector.java

webview/                               – Vite + React + TypeScript webview
  src/
    bridge/types.ts
    lib/validateComments.ts
    components/...
    App.tsx

vscode-extension/                      – VS Code extension host
  src/
    extension.ts
    github.ts
    claude.ts
    copilot.ts                         – Copilot SDK service (`@github/copilot-sdk`) with streaming/status forwarding
    core.d.ts
  test/
    copilot.test.ts
```

## Key design decisions

Only decisions that encode active constraints future code must respect and are not obvious from source.

### Webview styling
All webview UI uses shadcn/ui + Tailwind CSS v3. Avoid ad-hoc CSS modules/inline layout styles. `DiffViewer.css` is the only hand-crafted CSS exception for diff-table specifics. Use semantic status tokens (`text-status-*`, `bg-status-*/10`, `border-status-*/50`) rather than hardcoded palette classes.

### Module boundaries
`core` is KMP and has zero IntelliJ dependencies. `intellij-plugin` depends on JVM variant of `core`. Keep Java sources in `core/src/main/java` and `core/src/test/java` (do not move to `src/jvmMain/java`).

### Java interop conventions for commonMain Kotlin
Java callers must use generated getters/setters (`getX()`), not Kotlin property or record-style accessors. `DiffParser` access from Java is via `DiffParser.INSTANCE.*`. Keep JSON in `core` on kotlinx.serialization.

### expect/actual bridges
`ProcessUtil` and `runBlockingCompat` are expect/actual bridges; JVM provides blocking/runBlocking behavior, JS throws for blocking API path.

### GitHubService lifecycle
`GitHubService` is stateless except `apiBase`; IntelliJ adapter creates fresh instances so settings changes apply immediately. Keep `HTTP_CLIENT` shared/static to avoid per-call client pools.

### Threading model
`ClaudeService`/`CopilotService` are synchronous core services. IntelliJ adapters own threading (pooled thread for I/O, EDT for UI callbacks).

### Provider toggle and prompt sharing
Copilot and Claude share prompt builders/parsing (`ClaudeService` companion helpers). Do not fork prompt constants by provider unless absolutely required.

### Copilot SDK runtime
Both hosts use official Copilot SDKs (`com.github:copilot-sdk-java` and `@github/copilot-sdk`) to control local `copilot`. Stream `assistant.message_delta` to text chunks, surface `tool.execution_start` names as status, and parse final `assistant.message` JSON with delta fallback.

### Reasoning effort normalization
Persisted values are `none|low|medium|high|xhigh|max`; SDK accepts `low|medium|high|xhigh`. Normalize before session creation: `none -> low`, `max -> xhigh`, blank/unknown -> `medium`.

### Copilot model discovery
IntelliJ model suggestions are discovered from `copilot help config` once per session and cached. VS Code setting remains freeform (schema enum is static).

### Binary resolution
Probe known hard-coded paths for `gh`, `claude`, and `copilot` before falling back to command name, because GUI-launched IntelliJ often has incomplete `PATH`.

### Prompt-injection hardening
When wrapping untrusted payloads in XML-like tags, escape matching closing tags inside payload to prevent tag breakout in tool-enabled model runs.

### Diff acquisition model
Review prompts do not embed full diff; model fetches diff on demand via `gh pr diff` instruction in prompt (`diff = ""` in request object).

### Cross-host parity
When host-specific logic changes in IntelliJ or VS Code, update the paired implementation in the other host. The mapping table and enforcement workflow live in `AGENTS.md`.

### Max-turns recovery for Claude
If stream-json returns `error_max_turns` with `session_id`, auto-resume via `claude --resume <session_id> --max-turns 3` and nudge for final JSON.

### Draft review storage semantics
Inline comment metadata is encoded in review body HTML comment for resilient draft reload. Pending review creation omits `event`. On 422 for inline comments, fallback to body-first creation then per-comment POST.

### Comment anchoring policy
Client-side validation partitions comments: keep in-hunk, snap within +-3 lines, orphan otherwise. Orphans are excluded from inline POST and appended to review body section.

### Security constraints
`githubBaseUrl` must start with `https://` (SSRF guard). No tokens are persisted to disk.

### Repo detection and webview hosting
Repo detection walks upward to `.git/config`, handling SCP and `ssh://` remotes correctly. Webview assets are served via loopback `HttpServer` for proper same-origin module loading; path normalization blocks traversal.

## Settings persistence

`PluginSettings` (`claudeReviews.xml`) stores:

- `githubBaseUrl` (default `https://github.com`)
- `githubUsername` (display cache)
- `notificationsEnabled` (default `false`)
- `notifyReviewRequested` (default `true`)
- `notifyStarredRepos` (default `false`)
- `notificationPollMinutes` (default `5`)
- `reviewModel` (default `""`)
- `reviewModelCopilot` (default `"claude-sonnet-4.6"`)
- `reviewProvider` (default `"claude"`; values `claude|copilot`)
- `reviewEffort` (default `"medium"`; values `none|low|medium|high|xhigh|max`)

No API keys or tokens are written to disk.

## Local data files

| Path | Purpose |
|------|---------|
| `~/.pr-pilot/pending-prs.json` | Index of PRs with saved drafts (owner, repo, number, title, savedAt, headSha) |
| `~/.pr-pilot/seen-prs.json` | Set of `owner/repo#number` strings already notified about |
