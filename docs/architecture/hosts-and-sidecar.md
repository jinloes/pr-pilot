# Hosts and sidecar

Key design decisions moved from [ARCHITECTURE.md](../../ARCHITECTURE.md). Each section encodes an
active constraint future code must respect.

### Host operation correlation and acknowledged state

PR-scoped bridge messages carry a mandatory canonical `prKey` (`owner/repo#number`), and the webview
rejects them when it is absent or does not match the active PR. Draft saves additionally carry a
monotonically increasing `saveId` that both hosts echo on `draftSaved`/`draftSaveError`.
`ReviewPane` keeps separate in-flight and acknowledged snapshots: dispatching a save never advances
the saved baseline, stale acknowledgements are ignored, and dirty state clears only when the
matching success arrives. PR-switch/page-hide flushes may send a newer snapshot while an older save
is in flight, which is why positional “last request wins” assumptions are invalid. A save with an
explicit result remains persistable after its PR stops being active, but its completion must update
only per-PR bookkeeping and never the new selection's UI state.

Review generation is similarly fenced by a host-local generation ID in addition to the PR selection
revision. Starting or cancelling a review invalidates the prior ID before cancelling the provider
process; every status, chunk, result, and error callback checks both identities.
Provider/model/effort/MCP/self-critique/supervisor and resolved guidance settings are snapshotted
once when the request begins, and outcome attribution stores the generation-time
provider/model/supervisor mode beside the generated result per PR rather than reading mutable
settings at submit time. The webview carries the final merged model-authored result separately on
every draft save so chunked reviews are compared against that full baseline rather than the final
batch. Worktree cleanup must cancel and invalidate provider work before removing the directory;
disposal and chat clearing follow the same cancellation-first rule. VS Code fetches the larger
validation diff concurrently; when it completes after `reviewResult`, it uses the narrow
`validationDiffUpdated` bridge event rather than replaying `draftLoaded` and resetting review state.

`reviewChunk` remains a host protocol notification for transport compatibility, but the shared
webview must neither persist nor render its provider `text` or `thinking` payloads. `ReviewActivity`
is the single user-visible generation model and accepts only lifecycle/status messages, which are
reduced to safe phase or tool labels before display. This keeps private reasoning and partial
provider output out of both the DOM and accessibility tree.

Selection transitions atomically invalidate generation and chat IDs, detach the active provider
services, and advance the worktree epoch before cancellation or cleanup runs outside the state lock.
Every asynchronous selection publication checks the captured revision even when the user reselects
the same PR. Worktree creation is a single coalesced future per PR key and epoch; clearing,
switching, or disposing invalidates an in-flight lease, fails all coalesced waiters, and makes a
late creator remove its worktree instead of installing it. A worktree stores only directory
coordinates; every review/chat operation creates its own provider adapter so cancellation and
completion cannot cross-talk between operations. Chat uses its own monotonically increasing request
ID, snapshots runtime settings and history before awaits, and only the current request may append
history or publish chunks/results/errors. A user stop (`cancelChat` with the active chat
`operationId`) cancels the provider operation only when the host owns that ID and keeps the
conversation history, while `clearChat` also deletes history. Neither host sends `chatError` for a
stop, and the webview ignores late chunks for a stopped operation.

### Cross-host parity
When host-specific logic changes in IntelliJ or VS Code, update the paired implementation in the
other host. The mapping table and enforcement workflow live in `AGENTS.md`.

### First-run onboarding path
When startup PR loading fails, hosts push a `setupRequired` bridge message with actionable detail
instead of silently failing. Supported reasons are `gh_not_installed`, `gh_not_authenticated`, and
`load_failed` (non-auth load errors). When this message arrives before the first successful PR-list
load of the session, or with any reason other than `load_failed`, the app shows the full-pane
setup/error screen with its checklist and Refresh. After a successful load, `load_failed` instead
shows an inline, dismissible PR-list banner with Retry, keeping the last results, the review pane,
chat, and submit controls usable; `App` and `PRList` apply the same rule. Shared-engine domain
messages and each host's error mapper preserve actionable authentication guidance; VS Code
additionally classifies setup-worthy auth failures in `classifySetupAuthError`. The VS Code host
also triggers an initial `handleRefreshPRs` call immediately after the editor panel's webview is
initialized (`initializeWebview`) so the webview never hangs on its initial loading state.

The setup screen is a guided in-app wizard with host detection. Both hosts expose status re-check,
settings, and auth-guide actions; VS Code additionally supports one-click `gh auth login` automation
via a `runAuthLogin` bridge action that opens an integrated terminal and runs the command. After the
first successful load (or a recovery from `setupRequired`), the shared PR list shows a one-time
success coach banner that points users at scope switching and the `PR-DRAFT` vs `REV-DRAFT` mental
model.

### Notification parity
Background PR notifications are available in both hosts and are off by default. The first poll seeds
existing PRs silently. Both hosts support review-requested PR notifications and optional
starred-repository PR notifications, using the persisted settings listed in
[Settings](settings.md#fields). Each notification is labeled with its provenance (`Review requested`
vs `★ Starred repo`) so a starred-repo PR — which need not appear in the main list's current-repo
scope — is never mistaken for a review request; when a PR matches both sources, review-requested
takes precedence. The labeling/merge logic is shared-shaped across hosts
(`PRNotificationService.mergeCandidates`/`notificationTitle` in IntelliJ, `notifications.ts`
`mergeBySource`/`notificationMessage` in VS Code). Notification actions now route into PR Pilot
itself (`activatePR`) instead of straight to the browser: the shared list pins the opened PR into
the list when needed and marks it as notification-opened even if it is outside the current discovery
scope. The seen-PR set is persisted across reloads/restarts (IntelliJ via `SeenPRSet`, VS Code via
extension `globalState`) so PRs that appear while the editor is closed are still announced on the
next poll rather than silently absorbed by a re-seed. Changing the notification scope
(enable/disable, review-requested, starred repos, or GitHub base URL) re-seeds silently so existing
in-scope PRs are not announced retroactively.

### Security constraints
`githubBaseUrl` must be an HTTPS origin without credentials, an explicit port, path, query, or
fragment; external links must use HTTPS. GitHub/provider tokens are not persisted by PR Pilot.
Provider review input is adversarial: do not enable shell/write permissions or broader environment
capabilities by default. Primary review and the single supervisor follow-up may use only read-only
tools in the detached PR worktree; supervisor prioritization has no tools, and both supervisor calls
have MCP disabled. A detached worktree protects the active checkout but is not a machine sandbox, so
explicit capability elevation must remain visible in settings.

### Repo detection and webview hosting
Repo detection walks upward to `.git/config` and reads the `[remote "origin"]` URL specifically (not
the first `url=` in the file) so multi-remote/fork setups resolve to origin consistently across
hosts, handling SCP and `ssh://` remotes correctly. Webview assets are served via loopback
`HttpServer` for proper same-origin module loading; path normalization blocks traversal.

### Pinned IntelliJ SDK versus local sandbox

Compilation, ordinary tests, packaging, CI, and the original `runIde` resolve the 2026.1.4 SDK
unconditionally. `ideaLocalPath` is sandbox-only: the separate `intellijPlatformTesting.runIde`
registration, `runIdeLocal`, owns independent platform/runtime/sandbox configurations. Its
installed-build selection is lazy, so ordinary build/check must work without an eligible local
installation and must never launch an IDE. Selector fixture tests run under check without launching
one.

The explicit override must be valid and at least build 262; it never silently falls back. Automatic
macOS discovery is limited to direct IntelliJ app bundles in the two Applications directories and
orders full numeric metadata builds, including EAPs. Other platforms require an explicit path.
Discovery may resolve aliases read-only, but must not alter app bundles or normal IDE
configuration/plugins. No remote latest-version lookup or local-selection download fallback is
permitted.

### VS Code extension development target repo
The `.vscode/launch.json` config `Run PR Pilot Extension Against Target Repo` prompts for an
absolute repository path and passes it as `PR_PILOT_TARGET_REPO` to the Extension Development Host.
Use it when the PR Pilot source repo is open in the main VS Code window but PR Pilot should inspect
PRs for a different local checkout; `workspace.ts` makes repo detection, worktree creation, and CLI
working directories resolve against the target repo instead of whichever folder VS Code opened in
the dev host.

Both launch configs' preLaunchTask also restages the webview build into
`vscode-extension/webview-dist/` and runs `:sidecar:bootJar`, restaging the jar into
`vscode-extension/sidecar/`. `webviewAssets.ts` and `sidecarJar.ts` prefer those staged copies over
`webview/dist` and `sidecar/build/libs`. A stale staged webview silently rejects newer bridge
payloads (for example a new comment category), leaving a finished review stuck on "Generating
review". A stale jar fails the `initialize` capability check.

### No silent review drops
A review run has three hand-offs (sidecar response, host delivery, webview processing), and a silent
drop at any of them leaves the webview showing "Generating review" indefinitely with nothing logged.
Keep each hand-off loud:

- `StdioJsonRpcServerOperationScheduler` sends an error response for any operation that throws
  (including `Error`, which the executor's `Future` would otherwise swallow) or returns without
  completing, so the client is never left waiting on its 35-minute request timeout.
- The VS Code host logs (`[pr-pilot] Discarded ...`) whenever `handleGenerateReview` drops a result
  or error because the generation went stale, using `staleGenerationReason`. It does not notify the
  webview: every stale cause (view closed, PR switched or reselected, newer run) has already reset
  the webview's run. `push` also logs undelivered webview messages.
- The webview's `reviewResult` handler ends the run as failed if processing the result throws.
- When bridge validation rejects a host message, the webview logs which type and (for review
  results) which comment's enum fields failed, never bodies or paths. A rejected `reviewResult` or
  `reviewError` is replaced by a `reviewError` telling the user to reload. The usual cause is a UI
  older than its host, which is why the launch task restages `webview-dist`.

### Diagnostics keep the cause behind a user-facing error
User-facing errors are short summaries (`vscode-extension/shared/user-facing-errors.yaml`, shared
with IntelliJ's `UserFacingErrors`), so the raw cause must be kept somewhere a user can find it.

- **VS Code** writes to a "PR Pilot" log output channel (`src/log.ts`): sidecar stderr line by
  line, each sidecar request that hits its client timeout, and the raw message behind every
  `toUserFacingError` call. A log channel also persists with VS Code's own logs, so a failure can
  be diagnosed after its window is gone. Lines pass through `redact`, which masks GitHub tokens and
  bearer credentials; keep anything that can carry a credential out of log lines regardless.
  Timeout and generic-failure messages end with a pointer to that panel. IntelliJ runs the engine
  in-process, so the same causes already land in `idea.log`.
- **Timeouts name their step.** Both hosts map `git <command> timed out` (worktree fetch and add)
  and `copilot runtime startup` / `session creation timed out` (which includes MCP server startup)
  to step-specific advice; VS Code also names the sidecar request that hit its 60-second client
  timeout. Only an unrecognized timeout, in practice the provider's own run, keeps the "smaller
  diff / lower reasoning effort" advice. Keep the engine's timeout messages in these shapes, or
  update both mappers with them.
