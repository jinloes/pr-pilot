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
      GitWorktreeService.kt          – Creates/removes temporary git worktrees for PR branch reviews
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
      UserFacingErrors.java           – Maps runtime/network exceptions to actionable UI copy
      PRNotificationService.java
      PRNotificationStartup.java
    settings/
      PluginSettings.java
      PluginSettingsComponent.java
      PluginSettingsConfigurable.java
    ui/
      PRToolWindowFactory.java
      PRPilotFileEditorProvider.java      – Registers PR Pilot as a center editor-tab file editor
      PRPilotFileEditor.java              – FileEditor wrapper hosting WebviewPanel in editor tab
      PRPilotEditorOpener.java            – Opens/reveals singleton editor-tab virtual file per project
      PRPilotVirtualFile.java             – Marker LightVirtualFile for PR Pilot editor tab
      WebviewPanel.java
      ReviewMapper.java              – MapStruct mapper (core model -> webview DTOs)
      WebviewDtos.java               – package-private DTO records serialized to webview bridge
      RepoDetector.java

webview/                               – Vite + React + TypeScript webview
  src/
    bridge/types.ts
    lib/validateComments.ts
    lib/reviewQuality.ts            – Review Quality Check heuristics, repair helpers, and diff chunk planning
    components/...
    App.tsx

vscode-extension/                      – VS Code extension host
  src/
    extension.ts                       – VS Code activation plus PR Pilot webview tab/view bridge
    github.ts
    claude.ts
    copilot.ts                         – Copilot SDK service (`@github/copilot-sdk`) with streaming/status forwarding
    review.ts                          – Shared review-JSON extraction + schema validation (used by claude.ts + copilot.ts)
    worktree.ts                        – Creates/removes temporary git worktrees for PR branch reviews (mirrors GitWorktreeService.kt)
    settings.ts                        – Settings webview controller (panel lifecycle + config read/write); mirrors PluginSettingsConfigurable
    settingsView.ts                    – Pure settings-webview view logic (HTML, model-merge, escaping); no vscode import, unit-tested
    userFacingError.ts                 – Maps host/provider errors to user-actionable copy
    workspace.ts                       – Resolves the VS Code workspace dir, including dev-host target repo override
    core.d.ts
  shared/
    user-facing-errors.yaml            – Shared message templates consumed by both hosts
  test/
    claude.test.ts
    copilot.test.ts
    review.test.ts
    userFacingError.test.ts
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

### Copilot MCP inheritance
Copilot review/chat sessions inherit the CLI's MCP servers by setting the SDK session option `enableConfigDiscovery=true` (Java `setEnableConfigDiscovery`). Discovery alone loads `~/.copilot/mcp-config.json` (and repo-local `.mcp.json`) even when the working dir is a worktree; setting `configDir`/`setConfigDirectory` without discovery does **not** load them. The optional `copilotConfigDir` setting maps to `configDir` for non-default Copilot homes. This is Copilot-only — the Claude CLI inherits MCP through its own global config, not these settings.

### Reasoning effort normalization
Persisted values are `none|low|medium|high|xhigh|max`; SDK accepts `low|medium|high|xhigh`. Normalize before session creation: `none -> low`, `max -> xhigh`, blank/unknown -> `medium`.

### Copilot model discovery
Both hosts discover the available Copilot model list at runtime and cache it for the session, falling back to a short hardcoded suggestion list on probe failure.

- **IntelliJ**: `CopilotModelDiscovery` runs `copilot help config` and parses the `` `model`: `` section; `PluginSettingsComponent` merges the result into the (editable) model combo in Settings → Tools → PR Pilot.
- **VS Code**: `copilot.ts` `listModels()` queries the SDK's `client.listModels()` directly (no CLI parsing) and `filterModelIds()` drops policy-`disabled`/blank IDs. Surfaced two ways: (1) the **settings webview** (`settings.ts` + `settingsView.ts`) — opened via the gear in the PR Pilot view title or the `pr-pilot.openSettings` command — shows a live model dropdown and hides the non-active provider's model field; (2) the `pr-pilot.selectCopilotModel` quick-pick command (palette). VS Code's declarative settings JSON can't self-populate an enum or conditionally hide fields, which is why a webview is used for the rich settings UI. The underlying settings (`reviewProvider`, `reviewModel`, `reviewModelCopilot`, `reviewEffort`, `githubBaseUrl`, `copilotInheritMcp`, `copilotConfigDir`) remain editable in native Settings too.
  The settings webview validates `githubBaseUrl` before saving, posts per-field saved/error feedback, exposes model-refresh status, and has a `Test` action that verifies `gh` authentication for the configured host.

### PR discovery scope
The shared PR list sends an explicit `searchScope` on `refreshPRs`: `currentRepo`, `reviewRequested`, `assigned`, or `authored`. Both hosts build GitHub search queries from that scope and return `listStatus` (`searchScope`, `currentRepo`, `resultLimit`, `limited`) with `prListLoaded` so the webview can explain what was searched and when additional PRs are hidden. To distinguish "exactly the limit" from "more exist", the search over-fetches one row beyond the display limit (`resultLimit` = 50, fetch 51): `limited` is true only when more than 50 match, and the list is sliced back to 50. `currentRepo` searches only the detected repository; if no repo is detected it falls back to `author:@me`. Starred repositories are used only by optional notification polling, not by the main list's current-repo scope.

### Binary resolution
Probe known hard-coded paths for `gh`, `claude`, and `copilot` before falling back to command name, because GUI-launched IntelliJ often has incomplete `PATH`.

### Prompt-injection hardening
When wrapping untrusted payloads in XML-like tags, escape matching closing tags inside payload to prevent tag breakout in tool-enabled model runs.

### Diff acquisition model
Review prompts do not embed full diff; model fetches diff on demand via `gh pr diff` instruction in prompt (`diff = ""` in request object).

### Worktree-based PR context
When the PR's repo matches the open project/workspace and a git root is found, both hosts create a temporary git worktree checked out to the PR branch and reuse it for both review and chat. This gives the model accurate local file context (correct branch state) for type lookups and cross-file references across the full PR session. Cleanup runs when the active PR changes or the view is disposed. Falls back silently to the open project/workspace dir if worktree creation fails or the PR is from an unrelated repo. Fork PRs use `git fetch <clone_url> <branch>` + `FETCH_HEAD`.

- **IntelliJ**: `WebviewPanel.resolvePrClaudeService` builds a per-PR `IntellijClaudeService` pointed at the worktree, using `GitWorktreeService` (jvmMain).
- **VS Code**: `extension.ts` `resolveWorkingDir`/`clearWorktree` resolve the per-view worktree dir passed as `workingDir` to the review/chat CLIs, using `worktree.ts`. Chat reuses an existing worktree with the cached token only (never triggers a fresh auth).

### Cross-host parity
When host-specific logic changes in IntelliJ or VS Code, update the paired implementation in the other host. The mapping table and enforcement workflow live in `AGENTS.md`.

### User-facing error copy
Do not surface raw provider/HTTP exception strings directly to users in review/draft/chat flows. Both hosts map low-level errors to actionable guidance (`UserFacingErrors` in IntelliJ, `userFacingError.ts` in VS Code) to keep messaging consistent across providers. Message strings live in shared YAML templates (`vscode-extension/shared/user-facing-errors.yaml`) and support `{placeholder}` substitution.

### Bridge payload validation
Both hosts validate inbound webview bridge messages before dispatching handlers (`WebviewPanel.isValidIncomingMessage` in IntelliJ, `bridgeValidation.ts` in VS Code). Unknown message types or malformed PR identities are rejected and logged instead of reaching business logic.

### GitHub API resilience policy
Both hosts apply a transient-failure policy on GitHub REST calls: 15s request/connect/socket timeout, retries on `429`/`5xx`, and retry of timeout-style transport errors. This keeps PR loading/review flows resilient to short-lived network or GitHub edge failures while preserving fast-fail behavior for permanent `4xx` errors.

### First-run onboarding path
When startup PR loading fails, hosts push a `setupRequired` bridge message with actionable detail instead of silently failing. Supported reasons are `gh_not_installed`, `gh_not_authenticated`, and `load_failed` (non-auth load errors). `PRList` renders a full-pane setup/error screen with a checklist covering GitHub CLI installation, GitHub authentication, and PR loading, plus a Refresh button when this message is received. IntelliJ maps auth diagnosis to stable reason IDs via `PRToolWindowFactory.setupReason` and also emits `load_failed` for post-auth load exceptions; VS Code classifies setup-worthy auth failures (including 401/403/bad-credentials responses) in `classifySetupAuthError`. The VS Code host also triggers an initial `handleRefreshPRs` call immediately after `resolveWebviewView` so the webview never hangs on its initial loading state.

The setup screen is a guided in-app wizard with host detection. Both hosts expose status re-check, settings, and auth-guide actions; VS Code additionally supports one-click `gh auth login` automation via a `runAuthLogin` bridge action that opens an integrated terminal and runs the command.

### PR chat scope
Chat is available after PR selection, before and after review generation. Hosts build chat context from the active PR title/body, the full diff (already capped at 80 KB by the diff fetch), and the generated review when one exists; both hosts send the same full diff so chat answers do not diverge by host. The webview displays which context buckets are attached and adds selected text when the user right-clicks or verifies a comment. Chat reuses the PR worktree when available. The VS Code host sources the active PR's title/body from `getPRDetail` on select (the webview `selectPR` message carries only number/owner/repo), so the review prompt and chat context always include the real PR description.

### DTO mapping in IntelliJ webview bridge
`WebviewPanel` model-to-DTO conversion uses MapStruct (`ReviewMapper`) instead of hand-rolled mappers so field drift fails at compile time.

### Webview bridge PR correlation
PR-scoped lifecycle messages (`draftLoaded`, review generation/chunks/results/errors, draft save/submit/delete, and chat responses) carry a `prKey` of `owner/repo#number`. The React webview drops keyed messages that do not match the active PR so late async results from a previously selected PR cannot repaint or submit against the current PR. When adding a new PR-scoped host message, include the same `prKey` in both hosts.

### Max-turns recovery for Claude
If stream-json returns `error_max_turns` with `session_id`, auto-resume via `claude --resume <session_id> --max-turns 3` and nudge for final JSON.

### Draft review storage semantics
Inline comment metadata is encoded in review body HTML comment for resilient draft reload. Pending review creation omits `event`. On 422 for inline comments, fallback to body-first creation then per-comment POST. When a pending draft lacks usable hidden metadata, hosts fall back to GitHub API review comments and set `importedFromGitHub`; the webview warns that recovered review details may be incomplete.

### Large diff visibility
GitHub diffs are truncated at 80 KB in both hosts. The webview detects the truncation marker and warns that diff display and chat context are incomplete, while `DiffViewer` still lazily limits rendered changed lines for browser performance.

### Review quality gate and chunked review mode
The webview supports a pre-submit `Review Quality Check` pass that runs local heuristics over the current draft and validation diff to flag trust risks (unanchored comments, low-evidence high-severity findings, and missing rationale metadata). The pass provides one-click in-memory repairs (`remove unanchored`, `add rationale placeholders`, `downgrade high-risk issues`) before save/submit.

For larger PRs, reviewers can enable chunked mode in per-review overrides. The webview splits the changed files into risk-priority batches, runs one model pass per batch with batch-scoped file instructions, then merges batch outputs into a single draft and shows explicit batch progress with per-file confidence summaries.

### Notification parity
Background PR notifications are available in both hosts and are off by default. The first poll seeds existing PRs silently. Both hosts support review-requested PR notifications and optional starred-repository PR notifications, using the persisted settings listed below. The seen-PR set is persisted across reloads/restarts (IntelliJ via `SeenPRSet`, VS Code via extension `globalState`) so PRs that appear while the editor is closed are still announced on the next poll rather than silently absorbed by a re-seed. Changing the notification scope (enable/disable, review-requested, starred repos, or GitHub base URL) re-seeds silently so existing in-scope PRs are not announced retroactively.

### Comment anchoring policy
Client-side validation partitions comments: keep in-hunk, snap within +-3 lines, orphan otherwise. Orphans are excluded from inline POST and appended to review body section.

### Security constraints
`githubBaseUrl` must start with `https://` (SSRF guard). No tokens are persisted to disk.

### Repo detection and webview hosting
Repo detection walks upward to `.git/config` and reads the `[remote "origin"]` URL specifically (not the first `url=` in the file) so multi-remote/fork setups resolve to origin consistently across hosts, handling SCP and `ssh://` remotes correctly. Webview assets are served via loopback `HttpServer` for proper same-origin module loading; path normalization blocks traversal.

### VS Code webview surfaces
The VS Code host exposes PR Pilot as an editor-tab `WebviewPanel` opened by `pr-pilot.open`. The Activity Bar webview view (`pr-pilot.main`) is a lightweight launcher that immediately reveals the editor tab and includes an "Open PR Pilot" command link; the full PR loading, review generation, chat, and worktree lifecycle run only in the editor-tab panel.

### IntelliJ webview surfaces
The IntelliJ host now mirrors VS Code's split: the `PR Pilot` tool window is a lightweight launcher with an "Open PR Pilot" action/link, and the full interactive UI runs in a center editor tab backed by a singleton `PRPilotVirtualFile` per project. PR loading/review/chat behavior remains in `WebviewPanel`; `PRToolWindowFactory` and `PRPilotFileEditorProvider` both wire the same load pipeline so both surfaces behave consistently.

### VS Code extension development target repo
The `.vscode/launch.json` config `Run PR Pilot Extension Against Target Repo` prompts for an absolute repository path and passes it as `PR_PILOT_TARGET_REPO` to the Extension Development Host. Use it when the PR Pilot source repo is open in the main VS Code window but PR Pilot should inspect PRs for a different local checkout; `workspace.ts` makes repo detection, worktree creation, and CLI working directories resolve against the target repo instead of whichever folder VS Code opened in the dev host.

## Settings persistence

`PluginSettings` (`claudeReviews.xml`) stores:

- `githubBaseUrl` (default `https://github.com`)
- `notificationsEnabled` (default `false`)
- `notifyReviewRequested` (default `true`)
- `notifyStarredRepos` (default `false`)
- `notificationPollMinutes` (default `5`)
- `githubUsername` (display cache)
- `notificationsEnabled` (default `false`)
- `notifyReviewRequested` (default `true`)
- `notifyStarredRepos` (default `false`)
- `notificationPollMinutes` (default `5`)
- `reviewModel` (default `""`)
- `reviewModelCopilot` (default `"claude-sonnet-4.6"`)
- `reviewProvider` (default `"claude"`; values `claude|copilot`)
- `reviewEffort` (default `"medium"`; values `none|low|medium|high|xhigh|max`)
- `copilotInheritMcp` (default `true`) — when set, the Copilot review/chat session enables SDK config discovery so it inherits MCP servers from the Copilot CLI config (`~/.copilot/mcp-config.json`) and any repo-local `.mcp.json`. Copilot-only.
- `copilotConfigDir` (default `""`) — optional override of the Copilot config directory used to discover MCP servers; empty uses the CLI default (`~/.copilot`). Copilot-only.

No API keys or tokens are written to disk.

## Local data files

| Path | Purpose |
|------|---------|
| `~/.pr-pilot/pending-prs.json` | Index of PRs with saved drafts (owner, repo, number, title, savedAt, headSha) |
| `~/.pr-pilot/seen-prs.json` | Set of `owner/repo#number` strings already notified about |
