# Providers

Key design decisions moved from [ARCHITECTURE.md](../../ARCHITECTURE.md). Each section encodes an
active constraint future code must respect.

### Provider toggle and prompt sharing
Copilot and Claude share prompt builders/parsing (`ReviewPrompts` and its per-stage siblings such
as `ReviewChatPrompts`, plus `ReviewResultParser`; static helpers in `review-engine`). Do not fork
prompt constants by provider unless absolutely required.
Full-review and chat prompts are built in the engine (inside `ClaudeService`/`CopilotService`) from
structured fields. Chat hosts send a `ChatContext` (PR metadata, diff, generated review, and any
attached text with its file and lines) and never assemble prompt text themselves.

### Copilot SDK runtime
Both the IntelliJ plugin and the sidecar use the official Java Copilot SDK
(`com.github:copilot-sdk-java`, wrapped by `review-engine`'s `CopilotService`) to control local
`copilot`. Stream `assistant.message_delta` to text chunks, surface `tool.execution_start` names as
status, and parse final `assistant.message` JSON with delta fallback. VS Code's `copilot.ts`
additionally uses the TypeScript SDK (`@github/copilot-sdk`) directly, but only for model discovery
(`listModels`) — there is no sidecar RPC endpoint for model discovery yet, so that one path still
spawns the CLI in the extension process.

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
| Tool gating | Hard allowlist of three read tools | No allowlist |
| Approval | N/A — anything off-list is unavailable | Permission callback approves any tool the CLI classifies as `kind == "read"` |

Copilot's surface is therefore whatever the CLI labels read-only, not a fixed set. Temporary stream
output is owner-only and deleted in `finally`; raw model output is not retained in logs.

Copilot review/chat sessions reject all SDK permission requests by default. On-disk config discovery
(`setEnableConfigDiscovery`) is **never** enabled — unconditionally `false`, not derived from any
setting — because the session working directory is the untrusted PR-branch worktree, and discovery
would scan it for a repo-local `.mcp.json`. A malicious PR could ship an `.mcp.json` whose server
`command` is an arbitrary process, which the SDK could launch at discovery time before any tool-call
permission gate runs. `copilotInheritMcp` is an explicit capability elevation, but it never touches
the worktree: when enabled, `CopilotMcpConfig.loadTrustedServers` reads MCP server definitions only
from the user's own trusted config (`<configDir>/mcp-config.json`, default `~/.copilot`) and injects
them via `SessionConfig.setMcpServers`. The permission handler still approves only `read` (always)
and `mcp` (when elevated) and keeps rejecting shell/write/url. The optional `copilotConfigDir`
setting maps to `configDir`/`setConfigDirectory` and also scopes which trusted config file is read.
Never enable config discovery against the worktree and never replace the explicit permission handler
with blanket approval.

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
- **VS Code**: `copilot.ts` `listModels()` reads the session catalog, falling back to the SDK's
  `client.listModels()` (no CLI parsing), and `filterModelIds()` drops policy-`disabled`/blank IDs.
  The native Settings entries use
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
Probe known hard-coded paths for `gh`, `claude`, and `copilot` before falling back to command name,
because GUI-launched IntelliJ often has incomplete `PATH`. `review-engine`'s `BinaryLocator` is the
single JVM implementation of this probing logic (used by both `ClaudeService` and `CopilotService`,
for both IntelliJ and the sidecar); `claude.ts`/`copilot.ts` keep an independent TypeScript copy for
the same probing since the sidecar (Java) and the extension process (Node) are different runtimes —
the sidecar is the one that actually spawns the CLI for VS Code, but the extension still
preflight-checks binary availability locally before even asking the sidecar to start a review (see
Provider preflight below). JVM provider subprocesses must prepend `~/.local/bin`,
`~/.npm-global/bin`, and `~/.volta/bin` as well as Homebrew/local-bin paths: a resolved Node-backed
CLI wrapper otherwise cannot find its runtime when Toolbox launches the IDE without shell
initialization.

### Provider preflight
Before the PR list is first shown, both hosts check that the configured provider's CLI
(`claude`/`copilot`) is resolvable and publish binary/authentication states in the setup bridge
payload. Claude authentication uses the bounded, non-interactive `claude auth status --text` probe;
Copilot exposes no status-only command, so its authentication is explicitly `unverified` and the
checklist gives the manual `copilot login` action rather than claiming readiness. Missing binaries
and conclusively signed-out auth responses keep onboarding open with settings access. Timeouts,
execution errors, and unsupported or changed auth commands degrade to `unverified` so an
inconclusive probe cannot block onboarding. The same binary preflight remains in the review path as
defense in depth and produces actionable `provider_not_installed` copy instead of attempting a
doomed spawn. Availability checks: `ClaudeService.isBinaryAvailable()`/
`CopilotService.isBinaryAvailable()` (JVM, backed by `BinaryLocator` and `ProviderSetupProbe` in
`review-engine`); `claudeBinaryAvailable()`/`copilotBinaryAvailable()` plus shared `existsOnPath`
(`claude.ts`/`copilot.ts`) in VS Code.

### Max-turns recovery for Claude
If stream-json returns `error_max_turns` with `session_id`, auto-resume via `claude --resume
<session_id> --max-turns 3` and nudge for final JSON. `review-engine`'s `ClaudeService` is the
single implementation of this behavior for both hosts (IntelliJ in-process, VS Code via the
sidecar).
