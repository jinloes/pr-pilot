# Providers

Key design decisions moved from [ARCHITECTURE.md](../../ARCHITECTURE.md). Each section encodes an
active constraint future code must respect.

### Provider toggle and prompt sharing
Claude, Copilot, and Antigravity share prompt builders/parsing (`ReviewPrompts` and its per-stage
siblings such as `ReviewChatPrompts`, plus `ReviewResultParser`; static helpers in `review-engine`).
Do not fork prompt constants by provider unless absolutely required.
Full-review and chat prompts are built in the engine (inside
`ClaudeService`/`CopilotService`/`AntigravityService`) from structured fields. Chat hosts send a
`ChatContext` (PR metadata, diff, generated review, and any attached text with its file and lines)
and never assemble prompt text themselves.

### Copilot SDK runtime
Both the IntelliJ plugin and the sidecar use the official Java Copilot SDK
(`com.microsoft:copilot-sdk`, wrapped by `review-engine`'s `CopilotService`) over an explicit
`RuntimeConnection.forSpawnedStdio` child process (never in-process native runtime hosting, so a
runtime crash cannot take down the IDE or sidecar JVM) to control local
`copilot`. Stream `assistant.message_delta` to text chunks, surface `tool.execution_start` names as
status, and parse final `assistant.message` JSON with delta fallback. During a multi-stage
`ReviewPipelineService` run, `CopilotService.openClientScope()` keeps one started `RuntimeClient`
alive across the primary pass, chunked batches, supervisor, rule selection/agents, hygiene pass, and
self-critique (closing each `RuntimeSession` after its call and invalidating the shared client if
`createSession` fails), while standalone calls keep per-call client lifecycles. Both hosts also
route Copilot model discovery (`CopilotModelDiscovery` via `reviews/listCopilotModels` on
`ReviewEngineApi`) through the Java engine/sidecar rather than bundling a second Copilot SDK in the
VS Code extension.

### Antigravity CLI runtime
`AntigravityService` spawns local `agy` in headless NDJSON mode via `--print= --input-format
stream-json --output-format stream-json --agent pr-pilot-reviewer --settings <path>
--disable-slash-commands`. Unlike `claude`, `agy` parses `--print` as a string option rather than a
boolean flag (bare `--print` consumes the next flag as an inline prompt or fails at the end of
argv), so `--print=` is required when prompts arrive over `stream-json` stdin; `agy` also defines
no `--max-turns` or `--cwd` flag. Reasoning effort (`--effort`) is never passed and is hidden in
both hosts when Antigravity is selected because `agy` model slugs encode their own thinking tier
(such as `gemini-3.1-pro-high` or `claude-opus-4-6-thinking`) and `agy` exits non-zero when
`--effort` is combined with tier-encoded or non-effort models. `AntigravityModelDiscovery`
(`reviews/listAntigravityModels`, capability `antigravityModels`) parses `agy models` (`<slug>
(<Display Name>)`) after stripping ANSI escapes, caches non-empty `ACCOUNT` catalogs with
stale-while-revalidate, and falls back to built-in slugs when discovery fails.

Because `agy` walks upward from its working directory to `.git` and loads `.agents/hooks.json`,
`.agents/agents`, `AGENTS.md`, and `GEMINI.md`, running directly in an untrusted PR worktree could
execute PR-authored shell hooks. Every invocation runs inside an ephemeral owner-only
`AntigravityIsolatedWorkspace` containing an empty `.git/` boundary marker, PR Pilot's restricted
`.agents/agents/pr-pilot-reviewer.md` (`commandExecutionPolicy: off`, empty MCP/skills/plugins, and
either read-only tools `[read_file, find_by_name, grep_search, list_dir]` or `[]`), a generated
`--settings` file (`toolPermission: "request-review"`, `allowNonWorkspaceAccess: false`, and
`permissions.deny` blocking `write_file(*)`, `command(*)`, `unsandboxed(*)`, `read_url(*)`,
`execute_url(*)`, `download_url(*)`, `mcp(*)`, plus `read_file(*)` for tool-free stages), and safe
top-level links/Windows junctions into `projectDir` that exclude `.git`, `.agents`/`.agent`/
`_agents`/`_agent`/`.gemini`, `AGENTS.md`, `GEMINI.md`, and symlinks escaping `projectDir`.
`AntigravityStreamParser` relativizes any emitted isolated-workspace path back to `projectDir`.

### Provider capability isolation
Claude review/chat processes are granted a **read-only tool allowlist** (`--tools "Read Grep Glob"`,
pinned by `ClaudeService.SAFE_CLI_ARGS`), use `permission-mode=dontAsk`, pass a strict empty MCP
configuration, and read user settings only. Mutating, shell, and network tools are withheld, so the
process can read the PR-branch worktree but cannot act on it. The review prompt still embeds the
bounded GitHub diff rather than asking the CLI to fetch it, and instructs the model to use the read
tools only to confirm or attribute a finding.

The two providers have genuinely different capability surfaces, which is easy to miss:

| | Claude | Copilot |
|---|---|---|
| Tool gating | Hard allowlist (`Read Grep Glob`) | Hard allowlist (`view`, `grep`, `glob`) when MCP is off; `excludedTools` (shell/write/subagent/skill/ask_user) when MCP is on |
| Approval | N/A — anything off-list is unavailable | Permission callback approves `read` (and `mcp` when elevated) up to `MAX_SESSION_TOOL_CALLS` (40), then rejects excess calls with a JSON-output nudge and aborts only if the model ignores the rejection across more than `MAX_POST_BUDGET_TOOL_TURNS` (3) subsequent turns |

Temporary stream output is owner-only and deleted in `finally`; raw model output is not retained in
logs.

Copilot review/chat sessions replace Copilot CLI's generic interactive coding-agent system prompt
(`SystemMessageMode.REPLACE`) with a concise non-interactive review system message that enforces
batched parallel tool calls, `grep` with line numbers/context, `view_range` reads, and skipping
`.md` guidance already present in `<repo_guidelines>`. They also reject all SDK permission requests
by default, disable infinite session compaction (`InfiniteSessionConfig.setEnabled(false)`), disable
skills and file hooks, skip custom-instruction discovery (`setSkipCustomInstructions(true)`, since
sanitized base-commit guidance is injected via `BaseCommitContext`), and clear
plugin/skill/agent/hook directories unless `copilotInheritMcp` is enabled. On-disk config discovery
(`setEnableConfigDiscovery`) is **never**
enabled — unconditionally `false`, not derived from any setting — because the session working
directory is the untrusted PR-branch worktree, and discovery would scan it for a repo-local
`.mcp.json`. A malicious PR could ship an `.mcp.json` whose server `command` is an arbitrary
process, which the SDK could launch at discovery time before any tool-call permission gate runs.
**Always** pass `SessionMcpOptions.setServers(mcpServers)` on `CreateSessionOptions` even when the
map is empty: leaving `mcpServers` `null` lets `copilot --server` load `~/.copilot/mcp-config.json`
anyway, and read-only remote MCP tools can execute without triggering `onPermissionRequest`.
`copilotInheritMcp` is an explicit capability elevation, but it never touches the worktree: when
enabled, `CopilotMcpConfig.loadTrustedServers` reads MCP server definitions only from the user's own
trusted config (`<configDir>/mcp-config.json`, default `~/.copilot`) and injects them via
`SessionMcpOptions.setServers`. The optional `copilotConfigDir` setting maps to
`configDir`/`setConfigDirectory` and also scopes which trusted config file is read. Never enable
config discovery against the worktree and never replace the explicit permission handler with blanket
approval.

### Host code intelligence over MCP
`GenerateReviewParams.hostCodeIntelligence` and `ChatParams.hostCodeIntelligence` carry an optional
`{url, token}` for a host-run MCP server that answers definition, reference, implementation, type,
and symbol-search lookups from the host's language services. Only VS Code sends it, behind
`experimentalVsCodeCodeIntelligence`. Its server lives in `vscode-extension/src/codeIntelligence*.ts`.
The constraints:

- **The engine trusts nothing but loopback.** `HostCodeIntelligence.of` accepts only `http` URLs on
  `127.0.0.1`, `localhost`, or `[::1]` with an explicit port and no credentials, query, or fragment,
  and a 32–256 character `[A-Za-z0-9_-]` token. Anything else is dropped, and the review runs
  without the server.
- **Copilot only, and only where MCP is allowed.** `CopilotSdkRuntime.mcpServers` registers the
  server as `pr-pilot-code-intelligence`, which replaces any inherited server with that name, and
  sends the token as a bearer header. Attaching it approves `mcp` permission requests even when
  `copilotInheritMcp` is off. The server attaches to the primary pass, the critique, and chat. It
  never attaches to passes that run with MCP disabled (supervisor follow-ups, hygiene, rules) or to
  tool-free sessions, because `CopilotExecutor.complete` forwards each pass's `allowMcp`. Claude
  keeps its strict empty MCP config, so a Claude review ignores the field. Deep review and the
  second reviewer also never attach it.
- **It reads the checkout, never the worktree.** The server resolves paths against the user's
  checkout (`ViewState.gitRoot`). It is started only when that checkout is the PR's repository.
  Language servers index only the open workspace: an IntelliJ-backed Java server queried on a file
  in the review worktree answered from the workspace's copy and returned stale, mismatched
  positions. The checkout can be at a different commit, so every result says so, and the prompt
  section `ReviewInstructions.HOST_CODE_INTELLIGENCE` tells the model to confirm cited lines with
  Read in the worktree.
- **The tools take untrusted input.** The model chooses arguments while it reads pull-request text.
  Paths must be repository-relative. `..` escapes and symbolic links that resolve outside the
  checkout are rejected. Locations outside the checkout are counted, not listed. Inputs, result
  counts, snippets, and lookup time are bounded. Language-server error text is not echoed back.
- **The server lives for one request.** `withCodeIntelligenceServer` starts it with a fresh token
  for each review or chat request and closes it in `finally`. It refuses any request carrying an
  `Origin` header, which browsers always send. If it cannot start, the request runs without it.

IntelliJ does not send the field yet. That is a host lag, not a platform gap: an IntelliJ backend
could answer the same tools from PSI over the open project.

### Reasoning effort normalization
Persisted values are `none|low|medium|high|xhigh|max`; SDK accepts `low|medium|high|xhigh`.
Normalize before session creation: `none -> low`, `max -> xhigh`, blank/unknown -> `medium`.

### Copilot model discovery
Both hosts discover the account's Copilot models from the runtime's live session model catalog:
they open a short-lived, tool-free SDK session, call its `model.list` RPC, and delete the session
without sending a prompt. That is the catalog the CLI's own `/model` picker shows. The global
`models.list` RPC (SDK `listModels()`) is only the fallback when the session route fails or
returns nothing, because it omits models the session catalog includes (newly enabled Gemini models
were missing from it while selectable in sessions). Neither host uses `copilot help config` as a
primary source: the help output is a list baked into the installed CLI binary, so it misses newly
rolled-out models and ignores account policy. Settings
render the last successful list immediately and re-probe every time they open
(stale-while-revalidate); a failed probe never replaces a previous good list, and failures are not
cached, so the next open or **Refresh** retries. Only when nothing has ever loaded do hosts fall
back to a short hardcoded suggestion list.

- **IntelliJ**: `CopilotModelDiscovery.refresh()` boots a headless SDK client and reads the session
  catalog (`accountModels`, then `listModels()`); if both fail it falls back to parsing the
  `` `model`: `` section of `copilot help
  config` (reported to the user as possibly stale). Concurrent refreshes share one probe.
  `PluginSettingsComponent` merges the result into the (editable) model combo in Settings → Tools →
  PR Pilot, shows a **Refresh** button, and explains the result's source or failure in the hint
  below the combo.
- **VS Code**: `copilot.ts` `listModels(sidecar, forceRefresh)` calls the sidecar's
  `reviews/listCopilotModels` capability (`CopilotModelDiscovery` in `review-engine`) so the
  extension never spawns `copilot` directly or ships `@github/copilot-sdk`. The native Settings entries use
  static dropdowns for the supported Claude and Copilot fallback models. The **settings webview**
  (`settings.ts` + `settingsView.ts`) — opened via the gear in the PR Pilot view title or the
  `pr-pilot.openSettings` command — shows a live model dropdown and hides the non-active provider's
  model field. VS Code's declarative settings JSON cannot self-populate an enum or conditionally
  hide fields, which is why the webview is used for the account-specific picker. The underlying
  settings (`reviewProvider`, `reviewModel`, `reviewModelCopilot`, `reviewEffort`, `githubBaseUrl`,
  `copilotInheritMcp`, `copilotAutoEnableMcpOnReview`, `copilotConfigDir`) remain configurable in
  native Settings. The settings webview validates `githubBaseUrl` before saving, posts per-field
  saved/error feedback, exposes model-refresh status, and has a **Check connection** action that
  verifies `gh` authentication for the configured host.

### Binary resolution
Probe known hard-coded paths for `gh`, `claude`, `copilot`, and `agy` before falling back to
command name, because GUI-launched IDEs often have incomplete `PATH`. `review-engine`'s
`BinaryLocator` is the single implementation of this probing logic for both IntelliJ (in-process)
and VS Code (via the sidecar's `reviews/probeProviderSetup` capability; the former TypeScript copy
in `claude.ts`/`copilot.ts` is retired). JVM provider subprocesses must prepend `~/.local/bin`,
`~/.npm-global/bin`, and `~/.volta/bin` as well as Homebrew/local-bin and Antigravity install paths
(`%LOCALAPPDATA%\agy\bin`, `~/.local/share/agy/bin`): a resolved CLI wrapper otherwise cannot find
its runtime when Toolbox launches the IDE without shell initialization.

### Provider preflight
Before the PR list is first shown, both hosts check that the configured provider's CLI
(`claude`/`copilot`/`agy`) is resolvable and publish binary/authentication states in the setup
bridge payload. Claude authentication uses the bounded, non-interactive `claude auth status --text`
probe; Copilot and Antigravity expose no safe non-interactive status-only command, so their
authentication is explicitly `unverified` and the checklist gives the manual `copilot login` or
`agy login` action rather than claiming readiness. Missing binaries
and conclusively signed-out auth responses keep onboarding open with settings access. Timeouts,
execution errors, and unsupported or changed auth commands degrade to `unverified` so an
inconclusive probe cannot block onboarding. The same binary preflight remains in the review path as
defense in depth and produces actionable `provider_not_installed` copy instead of attempting a
doomed spawn. Both hosts run this logic through `ProviderSetupProbe` in `review-engine` — IntelliJ
in-process, VS Code via `reviews/probeProviderSetup` (`checkAuthentication: true` for onboarding,
`false` for fast review preflight) so the extension never spawns `claude` directly.

### Max-turns recovery for Claude
If stream-json returns `error_max_turns` with `session_id`, auto-resume via `claude --resume
<session_id> --max-turns 3` and nudge for final JSON. `review-engine`'s `ClaudeService` is the
single implementation of this behavior for both hosts (IntelliJ in-process, VS Code via the
sidecar).
