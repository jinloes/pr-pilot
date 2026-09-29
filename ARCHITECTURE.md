# PR Pilot Architecture

IntelliJ and VS Code extension that lists GitHub Pull Requests and generates AI-powered code reviews using a local AI CLI/runtime (Claude Code or GitHub Copilot, selected per-host in settings).

## Scope

- Keep this file focused on stable architecture and design constraints.
- See `CODEMAP.md` for implementation locations, task entry points, and related tests.
- Put workflow/checklist instructions in `AGENTS.md`.
- Put volatile version inventory in build manifests unless a version is itself an architecture constraint.

## System shape

PR Pilot is a multi-module Java 17 project with two IDE hosts sharing one React webview. `core`,
`github-engine`, and `review-engine` own host-neutral behavior. IntelliJ calls the engines in-process;
VS Code reaches the same capabilities through the stdio JSON-RPC `sidecar`. Host modules own only
IDE lifecycle, settings integration, and bridge wiring.

The shared webview owns the user workflow from PR discovery through review editing and submission.
GitHub credentials remain inside `github-engine`, while provider processes and review semantics
remain inside `review-engine`. Every engine capability must remain reachable from both hosts through
the parity boundary described below.

Visual references:

- [System architecture diagram](diagrams/architecture.md)
- [PR discovery and review-freshness sequence](diagrams/pr-discovery-sequence.md)
- [PR review generation sequence](diagrams/review-generation-sequence.md)

## Key design decisions

Only decisions that encode active constraints future code must respect and are not obvious from source.

### Source inventory is evidence, not semantic readiness

The optional 262+ IntelliJ MCP source-inventory prerequisite has an internal Java
consumer used by the shared opt-in semantic-review collector. Physical coverage
and native readiness are separate mandatory authorities. The ordinary plugin compiles against 2026.1.4 and loads
without MCP; below 262 or on an unknown MCP ABI the inventory tool is not advertised.

Protocol v2 DISCOVER independently enumerates VFS leaves and native per-file source
membership, alongside content/source/exclusion roots and separate library/SDK
metadata. VERIFY binds source bytes to one project instance, expiring discovery,
model and modification epochs and returns only VFS_VERIFIED. `nativePath` means logical
VFS containment, not physical identity. No native routing unwrapping or NIO disk proof
is permitted. The internal client always launches an explicitly trusted ordinary JVM
with the minimal worker Jar; moving verification into the in-process engine is not isolation.
That external worker securely enumerates all physical leaves, proves even empty source-root
directories, and reconciles every leaf and Git index/HEAD
entry, rejects untracked source contamination regardless of ignored/generated
status, checks Git blob bytes without transformations and repeats verification.
Generated sources have no exemption; external module sources block coverage.

Worker secure handle-relative no-follow traversal is mandatory and unsupported runtimes
fail closed. Evidence is bounded observation, not an OS snapshot, future-use
lease, Git-cleanliness assertion or READY status. One in-memory discovery slot per
project expires after 180 seconds and is disposed on project close; no persistent
settings/state are added. Java/Jar/Git/ijctl/config/HOME/executable PATH are explicit
caller-trusted assets outside the worktree; no project SDK or ambient runtime arguments
are inherited. Cleared child environments and fixed commands apply to worker/tool launches.
Private client request temporaries live outside the worktree and are removed only after
bounded owned-process-tree teardown. The worker is Java17-compatible but requires a
secure-handle-capable runtime/filesystem (ordinary JDK/JBR25 on the tested macOS host).
The engine JAR embeds the minimal worker and a schema-2 digest manifest under
`semantic-worker/`; `SemanticRuntime` reads resources through its classloader and extracts
them into owned 0700 temporary storage. It never searches sibling build directories.
The semantic-review collector consumes discovery/coverage
rather than guessed manifests or module names. The separate schema-2 native snapshot tool
adds import/index/document/PSI/classpath readiness; collection/delivery revalidation
remains an additional engine gate. Both hosts call the shared lifecycle, and both
provider adapters receive bounded evidence plus separate pinned skill instructions. All IDE open/trust/import/index and installation
actions are manual; native calls never save documents, refresh or drive import.

### Successful import authority is memory-only and pre-armed

`pr_pilot_review_snapshot` STATUS installs project-lifetime observers and asynchronously
arms settings receipts using native hashes/stamps plus the independent settings fork.
Only an already armed receipt followed by paired START/SUCCESS and unchanged finish
fingerprints can establish a baseline. Missing baselines request manual sync. Every
readiness check recaptures current physical settings. Settings-document changes invalidate
receipts before mutation, including edit/revert without a VFS event; VFS/settings-list
changes, failed/cancelled reloads and disposal also invalidate. Late finish callbacks cannot
restore invalidated authority. Unsupported tracker schemas or unrepresented linked builds
fail closed, not as plain projects.

The public service constructor uses a strict platform adapter for tracker rows, module/import
facts, counters, documents and inventory access. A package-private `NativeAccess` seam replaces
only those inputs in unit fixtures. Baseline transitions, epoch comparison, settings hashing and
CAPTURE/VERIFY decisions remain in the production service. Queued fixture work exercises actual
arm and finish jobs and the scheduled MCP route; it does not establish real tracker-ABI or import
callback compatibility. That requires the separate installed-262 checkpoint and timing controls.

CAPTURE/VERIFY require the active inventory-v2 discovery and complete source hashes.
Native READY is not physical source coverage or a future provider-stage lease. Unsupported,
ambiguous, missing-PSI and capped declaration evidence is summarized in bounded
`declarationLimitations`, independently of readiness failures. No result implies all callers
were found. Native READY must never be substituted for external physical coverage.

`SemanticRuntime` accepts only owner-controlled `~/.pr-pilot/semantic-review.json` schema 1.
Executables, configuration, HOME and PATH directories must be trusted, non-symlink, outside
the worktree and not group/other-writable. Node >=20 and exactly ijctl 0.3.0 are probed with
cleared environments. Worker selection is bundled, not configurable. Worker protocol v2 adds
settings fingerprints and source physical identity; mismatched resources/versions fail closed.

### Boundary compatibility findings are evidence-gated
The engine-owned review prompt treats service/module boundary compatibility as a first-class category, but only for located callers or consumers that still use an old contract, schema, field, config key, exported type, or behavior. Review providers must search the local worktree first and may use a read-only cross-repo MCP search only when the active provider/session exposes one. If all located callers are updated, or no caller can be found through available search, the finding is dropped rather than reported speculatively. `compatibility` is therefore part of the engine category set and mirrored by host/webview validators only so engine output can round-trip safely.

### Opt-in semantic-review lifecycle

`reviews/prepareDeepReview`, `reviews/listDeepReviews`, and
`reviews/cleanupDeepReview` are shared engine capabilities. Preparation creates an exact-head,
durably retained worktree and returns manual setup instructions, without starting a provider.
The shared webview pauses until the reviewer opens and trusts that exact worktree in a supported
IntelliJ server, completes import/indexing, and explicitly Continues. Importing PR build files can
execute code: that trust decision belongs to the reviewer, never to a CLI skill or provider.

Host callbacks bind preparation and Continue to PR selection, remote head, settings and unique
operation identities. A stale/duplicate response, selection change or disposal cannot start or
publish a newer operation. Readiness failures preserve setup for explicit Retry or ordinary
fallback; cancellation does not remove a project that may still be open in an IDE.

`SemanticReviewService.Execution` owns the prepared authority and its OS lease. Request models
carry evidence, not permission to execute. The engine rechecks native snapshot and independent
physical/Git evidence around collection, each provider stage, and final publication, keeping the
lease until delivery finishes. Chunked, reconciliation, selection, follow-up and critique request
copies retain deep context. Invalid authority makes the entire deep result fail; it cannot rescue
an earlier candidate as successful output. Ordinary review fallback remains a separate user action.

`IjctlClient` allows only fixed read-only calls with closed schemas, explicit project/server,
bounded output and query counts; neither provider may invent shell/MCP access through the bundle.
CLI `--project` target metadata does not route native tool calls. After validating the closed caller
arguments and the native schema's explicit string binding, the engine injects its trusted canonical
`projectPath` into a fresh payload for all six queries, including argument-free module/dependency
queries. Caller-supplied `projectPath` is rejected even when it matches; caller maps are never mutated.
`SemanticSkillBundle` embeds the complete adapted IntelliJ connection and code-intelligence
skills, their MIT license and provenance manifest pinned to upstream
`34ec93ad2f9dcd3177a3ffb48eebecbee7512fb4`. Trusted skill instructions are separate from
untrusted source/native evidence. Deep Copilot disables inherited MCP even if ordinary settings
request it. No skill installs dependencies, opens/trusts a project, runs builds or mutates files.

Retained records survive restart; OS leases prevent another process from cleaning active work.
Explicit cleanup requires confirmation that the exact project is closed in every IDE, rechecks
identity and lease state, and uses non-force Git worktree removal. Dirty/locked/uncertain trees
remain retained with an actionable failure. Ordinary worktree cleanup cannot remove retained trees.
Cancellation/disposal release execution ownership, not durable retention.

Automated callback, fake-provider and archive-resource tests are separate evidence from installed
ZIP/Boot/VSIX execution. Live clean/defect provider matrices, native import lifecycle controls and
long-generation invalidation must be observed in the isolated caller-owned host environment.

### Webview styling

All webview UI uses shadcn/ui + Tailwind CSS. Avoid ad-hoc CSS modules/inline layout styles. `DiffViewer.css` is the only hand-crafted CSS exception for diff-table specifics. Use semantic status tokens (`text-status-*`, `bg-status-*/10`, `border-status-*/50`) rather than hardcoded palette classes. Use `text-status-issue` for destructive or problem text; `destructive` is a fill color and fails as text on dark neutrals. `src/theme/contrast.test.ts` pins the red status text tokens at 4.5:1 or more on every surface where the webview places them (flat surfaces, `accent` highlights, `muted`/`primary` state tints, and their own 5–10% chip tints), so a new state tint or token value must keep that matrix passing. Theme colors are semantic CSS variables selected by host-provided `light`, `dark`, `highContrastLight`, or `highContrastDark` bridge state; do not infer the IDE theme from browser media queries alone.

The shared webview switches from split panes to explicit list/review navigation below 640px. Pane separators must remain operable by pointer and keyboard, keep ARIA values within viewport-derived bounds, and honor reduced-motion preferences. The application shell is pinned to the browser viewport with `overflow: clip`, and pane contents grow with `flex: 1` plus `min-height: 0`; do not replace this with a nested `height: 100%` chain because IntelliJ's JCEF Chromium can resolve a flex-stretched parent's percentage-height child to content height. Descendant auto-scroll must update its local scroll container directly rather than call `scrollIntoView`, because `overflow: hidden` ancestors remain programmatically scrollable and JCEF can translate the fixed shell off-screen.

Radix menu actions that launch an alert dialog must defer opening the dialog until the menu's `onCloseAutoFocus` phase. Mounting the second modal directly from `DropdownMenuItem.onSelect` can leave the closing menu's focus/pointer guards active in embedded JCEF, making the new dialog appear unresponsive.

Embedded webviews must not use browser-native `alert`, `confirm`, or `prompt` dialogs. The shared React surface uses the existing Radix dialog primitives, while the standalone VS Code settings webview uses in-DOM dialogs with explicit focus entry, trapping, restoration, and keyboard cancellation.

The IntelliJ host uses JCEF off-screen rendering (OSR) and attaches its layout-managed component directly to the tool-window content. Do not replace this with heavyweight native rendering or call `CefBrowser.wasResized` during ordinary Swing layout: on macOS, the native child window can retain an intermediate height and clip the webview. JCEF OSR can also retain stale pixels after DOM elements move without changing the browser viewport; dynamic webview geometry changes must send `webviewLayoutChanged` so IntelliJ can debounce a targeted `CefBrowser.invalidate` after React commits the layout. VS Code validates and consumes the same parity message but needs no host repaint.

### Webview accessibility tooling
Webview development runs dev-only runtime accessibility scans via `@axe-core/react` in `main.tsx` so missing labels/roles and semantic issues surface early in local runs. ESLint also applies `eslint-plugin-jsx-a11y` rules.

CI runs full-page Playwright + axe scenarios (`npm run test:a11y`) using `playwright.a11y.config.ts` and fails on any reported violation. Deterministic screenshot scenarios use `playwright.visual.config.ts` and gate both CI and release. Text-critical states (discovery with no selection, no draft, wide generation, and narrow selected review) also take a locator-level screenshot of the relevant pane with a tighter per-locator tolerance plus explicit text assertions, because the whole-page 1% tolerance cannot detect copy changes; never raise the global tolerance. Their committed baselines are canonical Ubuntu/Chromium artifacts generated with the pinned Playwright Docker image documented in `AGENTS.md`; native runs on other operating systems are not comparable because font rasterization is platform-sensitive. Pseudo-localization is test-only and enabled with `?locale=pseudo` to expose narrow-layout overflow.

### Webview draft mutation watchdog
`ReviewPane`'s `saveDraft`/`submitReview`/`deleteDraft` round trip (webview → host → GitHub → host → webview) has no host-side timeout of its own — a dropped bridge message, a wedged host-side mutation queue, or a crashed sidecar process leaves the corresponding `saving`/`submitting`/`deleting` spinner state stuck forever with no recovery path, since the webview only clears those flags in response to a message it may never receive. `ReviewPane` arms a client-side watchdog (`MUTATION_WATCHDOG_MS`, 45s) whenever it dispatches one of these three requests and clears it when the matching success/error response arrives; if the watchdog fires first, it synthesizes a `saveError`/`submitError`/generic `error` state so the user can retry instead of the UI hanging indefinitely. Do not raise this window to match `REVIEW_REQUEST_TIMEOUT_MS`/`reviewExecutor` — GitHub draft mutations are simple bounded REST calls with 15-second requests, unlike AI review generation which can legitimately run for minutes.

### Host operation correlation and acknowledged state

PR-scoped bridge messages carry a mandatory canonical `prKey` (`owner/repo#number`), and the webview rejects them when it is absent or does not match the active PR. Draft saves additionally carry a monotonically increasing `saveId` that both hosts echo on `draftSaved`/`draftSaveError`. `ReviewPane` keeps separate in-flight and acknowledged snapshots: dispatching a save never advances the saved baseline, stale acknowledgements are ignored, and dirty state clears only when the matching success arrives. PR-switch/page-hide flushes may send a newer snapshot while an older save is in flight, which is why positional “last request wins” assumptions are invalid. A save with an explicit result remains persistable after its PR stops being active, but its completion must update only per-PR bookkeeping and never the new selection's UI state.

Review generation is similarly fenced by a host-local generation ID in addition to the PR selection revision. Starting or cancelling a review invalidates the prior ID before cancelling the provider process; every status, chunk, result, and error callback checks both identities. Provider/model/effort/MCP/self-critique/supervisor and resolved guidance settings are snapshotted once when the request begins, and outcome attribution stores the generation-time provider/model/supervisor mode beside the generated result per PR rather than reading mutable settings at submit time. The webview carries the final merged model-authored result separately on every draft save so chunked reviews are compared against that full baseline rather than the final batch. Worktree cleanup must cancel and invalidate provider work before removing the directory; disposal and chat clearing follow the same cancellation-first rule. VS Code fetches the larger validation diff concurrently; when it completes after `reviewResult`, it uses the narrow `validationDiffUpdated` bridge event rather than replaying `draftLoaded` and resetting review state.

`reviewChunk` remains a host protocol notification for transport compatibility, but the shared webview must neither persist nor render its provider `text` or `thinking` payloads. `ReviewActivity` is the single user-visible generation model and accepts only lifecycle/status messages, which are reduced to safe phase or tool labels before display. This keeps private reasoning and partial provider output out of both the DOM and accessibility tree.

Selection transitions atomically invalidate generation and chat IDs, detach the active provider services, and advance the worktree epoch before cancellation or cleanup runs outside the state lock. Every asynchronous selection publication checks the captured revision even when the user reselects the same PR. Worktree creation is a single coalesced future per PR key and epoch; clearing, switching, or disposing invalidates an in-flight lease, fails all coalesced waiters, and makes a late creator remove its worktree instead of installing it. A worktree stores only directory coordinates; every review/chat operation creates its own provider adapter so cancellation and completion cannot cross-talk between operations. Chat uses its own monotonically increasing request ID, snapshots runtime settings and history before awaits, and only the current request may append history or publish chunks/results/errors. A user stop (`cancelChat` with the active chat `operationId`) cancels the provider operation only when the host owns that ID and keeps the conversation history, while `clearChat` also deletes history. Neither host sends `chatError` for a stop, and the webview ignores late chunks for a stopped operation.

### Repository guidance confinement

`RepoGuidelinesReader` treats configured paths and globs as untrusted input. It resolves from the repository's real root, rejects absolute and escaping paths, never follows symbolic-link path segments, and accepts only physically contained regular files. Its prompt cap is measured in UTF-8 bytes and truncation stops at a Unicode code-point boundary; host code must not replace these checks with `File.isFile`, string-prefix containment, or UTF-16 `String.length()` accounting. Guidance from a PR-head worktree is also author-controlled, so hosts never read it and always send `repoGuidelines` blank.

### Review guidance and file history come from the base commit
Hosts pass the GitHub-reported `baseSha` (`PrDetail.baseSha`, validated as a full hex object ID) on `PRReviewRequest`. Before the first provider call, `ReviewPipelineService` asks `BaseCommitContext` to resolve trusted guidance (root and changed-directory-scoped `AGENTS.md`/`CLAUDE.md`, `.linkedin/ai-agent/review_guidelines.md`, and contributing/PR-template files) and the recent commit history of each changed file, reading only base-commit git objects through read-only plumbing (`cat-file`, `ls-tree`, `log`) with per-command and overall time bounds. A missing base commit is fetched once from `origin` by SHA. Guidance is never read from the worktree or the head tree, so a PR cannot inject policy by editing guidance files — those edits remain review evidence only. Any failure (not a git work tree, malformed SHA, unreachable commit, timeout) degrades to an unenriched review; only cancellation propagates. Resolved base-commit guidance replaces host-supplied `repoGuidelines`; the host value (always blank today) is kept only when the base commit yields none.

### Recall: second reviewer and candidate validation
Recall is traded for precision in one place only: the final critique. When self-critique is on or a second reviewer is configured, the review prompt allows a bounded number of low-confidence `candidate` findings that the critique must confirm or drop; unconfirmed candidates never reach the user, even when critique itself fails. An optional second reviewer (`reviewSecondReviewerModel`, always a Copilot model) runs the same request in parallel with the primary; its findings are merged and deduplicated with the primary's before critique, it can never fail the review (its errors and timeouts are logged and ignored), and cancelling the primary cancels it. Its progress (started, still running, finished/failed) is reported only from the primary's thread, because host status sinks such as the sidecar's stdout are not thread-safe. The final comment count is capped after merging. The outcome log does not yet distinguish reviews that used a second reviewer.

### Chat pane structured verify/fix results
The "Verify" and "Suggest fix" per-comment actions (`buildVerifyCommentPrompt`/`buildExampleFixPrompt` in `ReviewPane/verifyPrompt.ts`) instruct the model to return only a bare JSON object (no prose) matching one of two fixed schemas. Verification treats the supplied diff excerpt as primary evidence but may use provider read-only file tools against the detached PR-head worktree when the excerpt is insufficient; it must not use shell, write, network, or paths outside that worktree. The response records inspected repository-relative files/lines or symbols in `evidence`, which the UI renders so the reviewer can see what was checked without exposing tool arguments or file contents. `ChatPane` treats every assistant reply as potentially structured: `structuredResult.ts`'s `parseStructuredResult` tries to parse the content (tolerating a stray ```` ```json ```` fence some models add despite instructions) against the verify schema (`verdict`/`why`/`evidence`/`action`/`replacementComment`) and the example-fix schema (`approach`/`examplePatch`/`why`/`risks`/`testUpdates`/`missingContext`); a match renders a dedicated card (verdict badge, why, evidence, suggested replacement, etc.) instead of the raw JSON string being passed through the Markdown renderer, which previously showed the reviewer a wall of escaped-quote JSON text. Ordinary free-form chat replies simply fail both schema checks and fall back to the normal Markdown bubble. `parseStructuredResult` is applied both to finalized `chatResponse` messages and to the live `streaming` buffer (accumulated from `chatChunk` notifications) — without the latter, a completed structured JSON reply would render as raw escaped JSON text (with the streaming cursor still attached) for the entire duration the chunks are arriving, since the buffer only becomes valid JSON once the last chunk lands. If either prompt's JSON schema changes, `structuredResult.ts`'s parser and field rendering must be updated in lockstep.

### Module boundaries
`core` is a plain Java 17 JVM module (Kotlin Multiplatform was removed once the `js` target and every shared model class had no reason to stay Kotlin — the review-engine extraction moved the JS target's last real consumers to the sidecar, and none of `PullRequest`/`ReviewResult`/`LineComment`/`ChatMessage`/`PRReviewRequest`/`ReviewProvider` ever exercised kotlinx.serialization's JSON encode/decode at runtime, so they were converted to plain Java classes with JavaBean-style getters/setters matching their prior Kotlin-generated method names exactly — no downstream call sites changed). The module's last Kotlin file (a unified diff parser) was later converted to Java too for the same reason, then removed entirely once it was found to have no production callers — actual diff parsing/rendering happens client-side in the webview via `react-diff-view`. `core` has zero IntelliJ dependencies. `intellij-plugin` also owns two IntelliJ-only local-file-index services (`PendingReviewIndex`, `SeenPRSet`, plain Java records/classes using Jackson) rather than `core`, since they are not shared with VS Code — the VS Code equivalent (`globalState`, see Notification parity below) is a different persistence mechanism entirely, so there is no cross-host code-sharing benefit to keeping them in `core`. `github-engine` is a plain Java 17 library with no Spring or IDE APIs; both `intellij-plugin` and `sidecar` depend on it. `review-engine` is a plain Java 17 library owning Claude/Copilot CLI invocation, prompt building, and review-JSON parsing (`ClaudeService`, `CopilotService`, `CopilotModelDiscovery`, `GitWorktreeService`); both `intellij-plugin` and `sidecar` depend on it, so AI review generation has exactly one JVM implementation rather than being duplicated per host. `github-engine` and `review-engine` depend on `core` for shared models. `intellij-plugin` also depends on `core` and no longer needs `jackson-module-kotlin`/`KotlinModule` now that `core`'s models are plain Java.

`sidecar` is a Java 17 Spring Boot application configured with `WebApplicationType.NONE`; it is not an HTTP server. Its protocol uses bounded `Content-Length`-framed UTF-8 JSON-RPC over standard input/output. Standard output must contain protocol frames only—diagnostics belong on standard error. GitHub, PR, repository, and review behavior belongs in `github-engine`/`review-engine`; Spring remains only the sidecar composition and lifecycle layer.

### Pending review index coordination
`PendingReviewIndex` instances are per caller, but their default `~/.pr-pilot/pending-prs.json` is process-global. All reads, mutations, and corrupt-file quarantine operations therefore synchronize on a static lock keyed by the normalized index path; do not restore instance-only synchronization or introduce a separate write path. The lock map intentionally lives for the IntelliJ application lifetime, matching the default index lifetime, while distinct temporary paths remain independent test seams. Recovery callbacks are likewise path-scoped, but a `WebviewPanel` must close its registration on disposal so its bound reload callback cannot outlive the project.

### Engine capability boundary (test-enforced host parity)
Each engine declares its complete capability surface as one interface — `GitHubEngineApi` in `github-engine`, `ReviewEngineApi` in `review-engine`, both in package `com.jinloes.prpilot.engine`. Each carries an `RPC_METHODS` map from Java method name to JSON-RPC wire name, and `StdioJsonRpcServer` registers a handler per entry (via a `Map<String, MethodHandler>` registry rather than a `switch`, so the registered set is introspectable).

Engine result statuses are also part of the host contract. In particular, `PrDiffResult` returns `not_found_or_inaccessible` for GitHub HTTP 404 because GitHub deliberately does not distinguish a missing resource from one hidden by authorization. IntelliJ preserves non-`ok` statuses in `GitHubOperationException`, and VS Code preserves them in `GitHubOperationError`; user-facing adapters select shared templates from those typed statuses rather than matching engine prose. The 404 template must retain both possible causes and make account switching conditional—never claim that access is the definite cause.

`EngineCapabilityCoverageTest` in the sidecar fails the build when a capability is declared without a wire name, exposed without being declared, declared without a registered handler, or registered without being advertised in the `initialize` handshake. This replaces the hand-maintained IntelliJ↔VS Code mapping table that previously lived in `AGENTS.md`, which could silently go stale. The rule it encodes: **hosts may lag in consuming a capability, but no host may re-implement one** — a capability the sidecar does not expose leaves a host no option but to fork the logic, which is the drift the boundary prevents.

`SidecarBootstrapService.CAPABILITY_METHODS` groups every wire method under the logical capability name the `initialize` handshake advertises, and the advertised map is *derived* from it rather than written out a second time. The handshake stays keyed by logical names (not raw wire names) because that is the existing client contract and because some capabilities are only meaningful as a set — `draftReviewMutations` covers save/submit/delete, and a client that started against a partially implemented mutation surface would fail mid-review rather than at startup. Grouping is mandatory: an ungrouped wire method fails the build, which is what stops a capability from being reachable over RPC yet invisible to every client.

`ReviewSessionService` lives in `review-engine`, not the sidecar, precisely so it is reachable in-process (IntelliJ, a future CLI) as well as over RPC. It has no transport, Spring, or IDE dependencies. `GitHubEngine` is a pure delegation composition root over the individual services, so behavior — and the existing per-service tests — stay where they were.

The client side is enforced separately by `vscode-extension/test/wireCatalog.test.ts`, which parses the engine interfaces as the source of truth and fails when `sidecar.ts` has no client method for a declared wire name, calls a wire method no engine declares, or lets `REQUIRED_CAPABILITIES` drift from `CAPABILITY_METHODS`. This exists because the Java-side test proves only that a capability is *reachable*: Phase 1's four context capabilities were exposed, coverage-tested, and never called by VS Code for months, so every VS Code review silently shipped with empty `<ci_status>`, `<commits>`, `<linked_issue>`, and `<repo_profile>` sections. What remains genuinely hand-mirrored is the notification shapes (which no interface declares) and the duplicated TypeScript logic (worktrees, binary probing, notification polling, prompt constants); see `AGENTS.md` "Remaining hand-mirrored logic". Guidance-doc reading used to be on that list and was retired by moving it behind the `reviews/readGuidelines` capability — the two copies had already diverged on the truncation marker, which is the concrete argument for preferring deletion over dual maintenance.

### Authenticated-user review freshness

`prs/list` keeps REST issue search as the discovery source, then enriches at most its 50 returned pull requests with the authenticated user's review freshness. `github-engine` resolves the viewer from the same `gh` token and performs one bounded GraphQL request using `headRefOid` and the latest `APPROVED`, `CHANGES_REQUESTED`, or `COMMENTED` review by that viewer. Matching commit OIDs map to `REVIEWED`, a different commit maps to `UPDATED_SINCE_REVIEW`, and no eligible review maps to `UNREVIEWED`; missing data, viewer lookup failure, GraphQL errors, or malformed responses map to `UNAVAILABLE`. The list still succeeds when enrichment fails and reports `reviewStatusAvailable=false` so the shared webview emits one degraded notice rather than mislabeling rows. Because enrichment is optional, viewer lookup and GraphQL enrichment each use one three-second request attempt instead of the required-call retry budget; a freshness outage therefore cannot hold an otherwise successful REST list for repeated 15-second timeouts.

GitHub.com uses `https://api.github.com/graphql`; GHES uses `https://<host>/api/graphql`, derived by `GitHubApiBase` from the validated origin. Hosts must forward these token-free states without querying GitHub themselves. Search-only notification paths intentionally use `UNAVAILABLE`, and notification activation must not overwrite an already known list status with that weaker state.

### Sidecar streaming: async requests plus server-push notifications
The base JSON-RPC loop in `StdioJsonRpcServer.run()` is still one blocking read → dispatch → optional single write per iteration, but `reviews/generate` and `reviews/chat` are the two methods that deviate: `generateReview`/`chatReview` submit the actual review-engine call to a dedicated `reviewExecutor` (a single-thread pool) and return `null` immediately, so the read loop stays free to accept a `reviews/cancel` request (or any other RPC) while a review is in flight. The eventual result is written asynchronously from the background thread once the provider CLI completes. Progress is reported via `reviews/status`/`reviews/chunk`/`reviews/chatChunk` — JSON-RPC notifications (no `id` field) carrying a `requestId` field that correlates them back to the originating request, sent from the background thread under the same `writeLock` used by the main loop so frames never interleave.

JSON-RPC request IDs remain transport correlations only. Every review/chat request also carries a bounded opaque `operationId`; a logical chunked review reuses one ID for every batch, and cancellation accepts only that ID. `ReviewSessionService` registers the exact provider cancellation callback and a durable `CancellationToken` under the ID, marks the token before invoking the provider, and removes the operation with identity-aware cleanup, so a stale completion cannot erase or cancel a replacement and late provider startup observes cancellation. `StdioJsonRpcServer` indexes scheduled operation slots by that ID; each slot owns the original request ID, start/cancel state, future, and exactly-once terminal response, so cancelling a queued request completes its original JSON-RPC request rather than leaking it. Unknown or completed IDs return `cancelled: false`; malformed IDs are `-32602` wire errors. Hosts register their active semantic ID before preflight/worktree awaits and retain local generation/chat revisions solely to suppress stale UI output.

On the VS Code side, `SidecarClient` correlates notifications to their request via a `notificationHandlers` map keyed by the same numeric `id` used for the request/response pending map; `requestRaw` accepts an optional per-call timeout (`REVIEW_REQUEST_TIMEOUT_MS`, 35 minutes, since a real review/chat CLI invocation can legitimately run far longer than the default 60s RPC timeout) and optional notification handlers, cleaned up on both success and failure paths. A request timeout is scoped to that request and must not stop the child or reject unrelated work; only transport-fatal events such as process exit, malformed framing, or a failed stdin write invalidate the process and fail every pending request.

The engine's repository detector reads local git metadata directly (no git process spawned) to resolve the owner/repo for a directory. `GitDirectoryResolver` understands linked-worktree `.git` files (a regular file containing `gitdir: <path>`, relative or absolute) in addition to a standard `.git` directory. `RemoteUrlParser` requires exactly two non-blank path segments (owner and repo), including for SCP-style URLs. Every non-`found` outcome (`not_git`, `config_missing`, `origin_missing`, `origin_url_malformed`, `gitdir_malformed`, `gitdir_unreadable`, etc.) is a typed, non-fatal `DetectStatus`; over JSON-RPC these are normal results, never protocol errors. `-32602` is reserved for malformed RPC params (missing/non-string `path`).

The sidecar's `github/checkAuth` capability validates an HTTPS GitHub origin, runs `gh auth token` with the matching Enterprise hostname when needed, and verifies the resulting token with the host's `/user` API. It returns token-free structured statuses (`authenticated`, `not_installed`, `not_authenticated`, `api_failed`, or `invalid_base_url`) as normal domain results; never log, serialize, or include the token in an error message. Invalid JSON-RPC request parameters remain protocol errors.

The sidecar's `prs/list` capability owns its `gh auth token` lookup and GitHub `/search/issues` call; hosts never pass a token over JSON-RPC. It uses the canonical `PrSearchQueryService` rules, fetches 51 rows but returns at most 50 with an explicit `limited` flag, and returns token-free domain statuses (`not_installed`, `not_authenticated`, `invalid_base_url`, `rate_limited`, `network_error`, or `api_failed`) rather than JSON-RPC errors. JSON-RPC errors remain reserved for malformed parameters and protocol failures.

The sidecar's `prs/getDetail` capability owns its `gh auth token` lookup and GitHub pull-request metadata request. It validates owner/repository path segments, maps malformed GitHub JSON to `api_failed`, and returns only title/body, merged status, and nullable head/base repository metadata needed for fork-aware worktrees. No token, HTTP response body, or raw exception becomes protocol output.

The sidecar's `prs/getDiff` supports both review mode (250,000-byte budget) and validation mode (1,000,000-byte budget, used for chunked review, IntelliJ-assisted deep review, inline-comment position validation, and draft anchoring). Both modes run the same `PrDiffService.bound` selection described under "Complete review diff coverage": whole files only, never a byte cut, with an engine-authored `DiffCoverage` trailer whenever a file is omitted. GitHub HTTP 406 maps to the non-retryable `diff_too_large` status. The stdio JSON-RPC frame ceiling is 8 MiB in both Java and TypeScript so an escaped validation diff remains bounded while fitting safely in a response.

The sidecar's `prs/getDraftReview` capability owns its `gh auth token` lookup and the GitHub pending-review lookup (`GET .../pulls/{number}/reviews` filtered to `state == PENDING`, plus that review's inline comments). `DraftReviewCodec` decodes the PR Pilot `claude-verdict`/`claude-summary`/`claude-comments` HTML-comment tags embedded in the review body, falling back to raw GitHub inline comments (with an `importedFromGitHub` flag) when those tags are absent or malformed. `none` (no pending review exists) is a normal domain result, not a failure; other token-free domain statuses (`invalid_request`, `invalid_base_url`, `not_installed`, `not_authenticated`, `rate_limited`, `network_error`, `api_failed`) are returned the same way `prs/getDetail` does. No token is ever logged, serialized, or included in an error message.

The sidecar's `prs/saveDraftReview`, `prs/submitReview`, and `prs/deleteDraftReview` capabilities expose `DraftReviewMutationService` over JSON-RPC. `save` deletes any existing pending review first (non-fatal on failure), fetches the PR's head SHA, and POSTs a new pending review; on a GitHub `422` (one or more inline comments reference an invalid path/line) it falls back to creating the review body-only, then POSTs each comment individually so only the bad ones are dropped, appending a "Comments not attached inline" section to the body via `DraftReviewCodec.buildDroppedSection` when any are dropped. Hidden metadata is regenerated from accepted inline comments only, so detached comments cannot be resurrected when the draft is decoded. `DraftReviewCodec` owns the stable metadata tag format and short JSON keys used by both hosts. `submit` first reads the pending review (`GET .../reviews/{id}`; a failed read returns that failure and posts nothing), then posts to `.../reviews/{id}/events` with an explicit body: the reviewer's dialog text followed by the pending body's visible text, with the hidden metadata removed by `DraftReviewCodec.visibleBody`. The per-event default body is used only when both are empty. `delete` removes a pending review. HTTP retries are method-aware: retry-safe reads/deletes keep bounded retries, but non-idempotent POST mutations receive one attempt because a lost response after server acceptance must not duplicate a review, comment, or submission. All three share the token-free status vocabulary (`invalid_request`, `invalid_base_url`, `not_installed`, `not_authenticated`, `rate_limited`, `network_error`, `api_failed`) plus `ok`. The host-side mutation-serialization guard (`state.mutationQueue`/`enqueueMutation` in `extension.ts`) is UI-state sequencing local to each host, not a GitHub API concern.

`PrSupplementalService` supplies the remaining token-safe read paths needed to remove host-local GitHub HTTP: bounded arbitrary PR searches (`prs/search`, query at most 8 KiB and limit at most 100), up to 200 starred repositories (`repos/listStarred`), and formatted submitted-review context (`prs/getExistingReviews`). Individual inline-comment lookup failures do not discard otherwise usable submitted-review context.

`PrCommitsService` also extracts a de-duplicated list of same-repository closing issue numbers from
raw commit messages before their display summary is truncated. `LinkedIssueService` resolves PR-body
closing references first, then uses those commit references to fill the existing three-issue cap.
Both hosts reuse the result of their single commit fetch when requesting linked-issue context; they
must not issue a second commit request. The resulting issue text remains untrusted prompt data.

### GitHub engine host wiring
GitHub behavior has one implementation in `github-engine`. IntelliJ consumes it in-process through `IntellijGitHubService`, which holds a single `GitHubEngineApi` and adds only host adaptation — reading the configured base URL, mapping engine records onto the shared models, and converting non-`ok` statuses to `IOException`. It previously instantiated eleven engine services itself and repeated `GitHubEngine`'s delegation, which meant a capability added to the interface was invisible to IntelliJ. The VS Code extension calls the same services through the sidecar's bounded Content-Length-framed JSON-RPC protocol. Hosts never receive, cache, or pass GitHub tokens. Authentication, retries, URL/API normalization, PR queries, repository detection, diffs, review context, and draft mutations belong only in `github-engine`.

For VS Code, `extension.ts` owns one process-wide `SidecarClient` created and initialized in `activate()` and disposed during extension teardown. Initialization verifies protocol version 1 and every required GitHub capability before requests proceed. The sidecar is mandatory for GitHub operations: missing Java 17+, a missing/corrupt jar, process failure, timeout, incompatible capabilities, or malformed protocol output is surfaced as a setup/runtime error and must never activate a TypeScript GitHub fallback. RPC requests have a 60-second bound for GitHub network operations. A user-facing **Retry** action restarts the stopped sidecar, clears only transport state, and re-runs initialization/capability validation; it never falls back to host-local GitHub behavior. `none` remains a valid `prs/getDraftReview` domain result; other token-free domain statuses flow into the existing setup/error UI.

`resolveSidecarJarPath` mirrors `resolveWebviewDistPath`'s dev/packaged fallback: it prefers a jar staged at `vscode-extension/sidecar/pr-pilot-sidecar.jar` (produced by `scripts/stage-sidecar.mjs` from the Gradle `:sidecar:bootJar` output, matching the webview's `stage-webview.mjs` pattern) and falls back to the sibling `sidecar/build/libs/pr-pilot-sidecar.jar` during local development. The release pipeline runs `:sidecar:bootJar` before packaging so shipped `.vsix` builds bundle the jar.

IntelliJ intentionally does not spawn the sidecar process: the plugin already runs in the same JVM and calls `github-engine` directly. This is a container difference, not a behavior fork.

### core: plain Java module
`core`'s shared models (`model/*.java`) are plain Java classes with JavaBean getters/setters (e.g. `getOwner()`, `isDraft()`) — no Kotlin-Java interop shims (`@JvmOverloads`, `@JvmStatic`, `KotlinModule`) are needed anywhere downstream, and the module has no Kotlin plugin/dependency at all.

### Threading model
`ClaudeService`/`CopilotService` (in `review-engine`) are synchronous services. IntelliJ's `IntellijClaudeService` adapter owns threading (pooled thread for I/O, EDT for UI callbacks); the sidecar's `StdioJsonRpcServer` owns threading for VS Code (single-thread `reviewExecutor` per the streaming design above, JSON-RPC notifications instead of a UI-toolkit callback).

### Provider toggle and prompt sharing
Copilot and Claude share prompt builders/parsing (`ClaudeService` static helpers in `review-engine`). Do not fork prompt constants by provider unless absolutely required. Full-review and regular-chat prompts are built server-side (inside `ClaudeService`/`CopilotService`) from raw PR/diff/context fields; only the small focused-chat prompt (`buildFocusedChatPrompt`) is still built by the caller (`IntellijClaudeService.chatFocused` on IntelliJ, `extension.ts`'s `handleAskClaude` using `claude.ts`'s copy on VS Code) before being sent as a raw prompt, matching how a focused question carries no PR metadata or history.

### Copilot SDK runtime
Both the IntelliJ plugin and the sidecar use the official Java Copilot SDK (`com.github:copilot-sdk-java`, wrapped by `review-engine`'s `CopilotService`) to control local `copilot`. Stream `assistant.message_delta` to text chunks, surface `tool.execution_start` names as status, and parse final `assistant.message` JSON with delta fallback. VS Code's `copilot.ts` additionally uses the TypeScript SDK (`@github/copilot-sdk`) directly, but only for model discovery (`listModels`) — there is no sidecar RPC endpoint for model discovery yet, so that one path still spawns the CLI in the extension process.


### Provider capability isolation
Claude review/chat processes are granted a **read-only tool allowlist** (`--tools "Read Grep Glob"`, pinned by `ClaudeService.SAFE_CLI_ARGS`), use `permission-mode=dontAsk`, pass a strict empty MCP configuration, and read user settings only. Mutating, shell, and network tools are withheld, so the process can read the PR-branch worktree but cannot act on it. The review prompt still embeds the bounded GitHub diff rather than asking the CLI to fetch it, and instructs the model to use the read tools only to confirm or attribute a finding.

The two providers have genuinely different capability surfaces, which is easy to miss:

| | Claude | Copilot |
|---|---|---|
| Tool gating | Hard allowlist of three read tools | No allowlist |
| Approval | N/A — anything off-list is unavailable | Permission callback approves any tool the CLI classifies as `kind == "read"` |

Copilot's surface is therefore whatever the CLI labels read-only, not a fixed set. Temporary stream output is owner-only and deleted in `finally`; raw model output is not retained in logs.

Copilot review/chat sessions reject all SDK permission requests by default. On-disk config discovery (`setEnableConfigDiscovery`) is **never** enabled — unconditionally `false`, not derived from any setting — because the session working directory is the untrusted PR-branch worktree, and discovery would scan it for a repo-local `.mcp.json`. A malicious PR could ship an `.mcp.json` whose server `command` is an arbitrary process, which the SDK could launch at discovery time before any tool-call permission gate runs. `copilotInheritMcp` is an explicit capability elevation, but it never touches the worktree: when enabled, `CopilotMcpConfig.loadTrustedServers` reads MCP server definitions only from the user's own trusted config (`<configDir>/mcp-config.json`, default `~/.copilot`) and injects them via `SessionConfig.setMcpServers`. The permission handler still approves only `read` (always) and `mcp` (when elevated) and keeps rejecting shell/write/url. The optional `copilotConfigDir` setting maps to `configDir`/`setConfigDirectory` and also scopes which trusted config file is read. Never enable config discovery against the worktree and never replace the explicit permission handler with blanket approval.

### Reasoning effort normalization
Persisted values are `none|low|medium|high|xhigh|max`; SDK accepts `low|medium|high|xhigh`. Normalize before session creation: `none -> low`, `max -> xhigh`, blank/unknown -> `medium`.

### Comment anchoring snaps within a hunk, never across one
`validateComments` (`webview/src/lib/validateComments.ts`) indexes valid new-file lines **per hunk**, not as one flat set per file. A comment whose line is already in some hunk passes through. Otherwise it snaps only when exactly one hunk has a line within `SNAP_RADIUS` (3); if two hunks qualify, the target sits in the gap between them and belongs to neither, so it is orphaned instead of attached to whichever is marginally nearer. A flat per-file set silently allowed a comment to snap from the end of one hunk into the start of the next — the exact misattribution the prompt's "a misattributed comment is worse than no comment" rule exists to prevent. Orphans are appended to the review body rather than posted inline.

### Three comment keys exist on purpose
They look like drift and are not; unifying them is a regression.

| Key | Where | Shape | Why |
|---|---|---|---|
| Chunk merge | `ReviewPane.mergeChunkResults` | `file\|line\|type\|body` | Dedupes model **findings** across batches; `type` is part of a finding's identity |
| Orphan match | `DraftReviewCodec.orphanKey` | `file\|line\|type\|body` | Identity check between two lists holding the same objects |
| POST dedupe | `DraftReviewCodec` `dedupeKey` | `normalizedFile\0line\0body` | Collapses comments that would post **identically**. The GitHub payload is only `path`/`line`/`side`/`body`, so including `type` would post two byte-identical comments; the path is normalized because `b/Foo.java` and `Foo.java` target the same place |

### Missing rationale drops a comment, never fabricates one
`applyReviewQualityRepairs`' `dropMissingRationale` removes comments whose evidence the model declined to state. An earlier `addMissingRationale` filled the gap with `Evidence needs verification in <file>:<line>.`, which cleared the trust warning and raised the reported quality score while adding no evidence — strictly worse than leaving the comment flagged.

### Verify verdicts apply to the comment that was verified, not the latest one
"Verify" builds a prompt for one comment and dispatches it through the chat pane. Because a reviewer can verify several comments before acting on any verdict, `ReviewPane` tags each request with an opaque token bound to that comment; `ChatPane` copies the token onto the reply it produces and hands it back with the parsed `VerifyResult`. Applying resolves the target via `resolveVerifyTarget` — object identity first, then `(file, line, body)` — and **no-ops when the comment was since deleted or its body edited**, because applying a stale verdict to a different comment is worse than dropping it. Only `delete`, and `revise` with non-blank replacement text, render an Apply button; `keep` changes nothing, so offering one would be misleading.

### Comments on deleted files orphan by design
A pure-deletion file has only `delete` changes, which carry no new-file line number, so it yields an empty line set and every comment on it becomes an orphan — rendered in the review body's "Comments not attached inline" section rather than posted inline. Anchoring these inline would require a `side: LEFT` field threaded through `bridge/types.ts` and both hosts' handlers, and the review prompt already instructs the model to comment only on changed (`+`) lines, so such a comment is a prompt violation to begin with. The comment still reaches the reviewer; only its placement degrades. Pinned by `validateComments.test.ts`.

### CI annotations are selected by whether they exist, not by whether the check failed
`CheckRunService` probes a check's annotations when it failed **or** when its `output.annotations_count` is non-zero. Static-analysis checks (Qodana, CodeQL, ktlint) are routinely configured as advisory and conclude `success` or `neutral` while still reporting file-anchored findings — the most review-comment-shaped evidence CI produces. Keying on the conclusion alone silently discarded them exactly when the build was green. `annotations_count` arrives in the check-runs list response that has already been fetched, so testing it costs no extra request. The condition is a union rather than a replacement so a provider that omits the field retains the failing-check behavior. Failing checks are still requested first, so advisory lint notes cannot crowd a broken build out of the bounded annotation budget.

### CI-duplicate suppression is conservative by design
`CiFindingSuppressor` drops a generated comment only when a CI annotation matches **both** its location (±2 lines — CI often anchors at a declaration while the comment lands on the statement below) **and** ≥60% of the annotation's distinctive words. The two error directions are not symmetric: a surviving duplicate costs one line of reading, while a wrongly-suppressed finding is *invisible* — the reviewer has no way to know it existed. An annotation with no distinctive words after short-token filtering (e.g. "Process completed with exit code 1") suppresses nothing, because it would otherwise match every comment on its line. This runs at both providers' `reviewPR` seams so Claude and Copilot behave identically, and it is the deterministic counterpart to the Phase 2 critique directive, which only *asks* the model to drop such findings.

### Review submission uses a synchronous webview lock
`ReviewPane` guards `handleSubmit` with `submitInFlightRef`, not only React's asynchronous `submitting` state. A second click can otherwise enter the save-then-submit path before React rerenders the first click's disabled button, producing a second GitHub draft and therefore a second approval. The lock spans both the initial `saveDraft` and the later `draftSaved` → `submitReview` handoff. It is released only on a submit/save error, watchdog recovery, PR change, or `reviewSubmitted`; do not replace it with a visual `disabled` check alone. The IntelliJ `draftMutationLock` and VS Code `enqueueMutation` serialize host calls, but cannot deduplicate two separately-created draft review IDs.

### The review worktree is pinned to the PR head commit, not the branch tip
`GitWorktreeService` resolves the worktree to the PR's `head.sha` (`git worktree add --detach <dir> <sha>`) rather than `origin/<branch>` or `FETCH_HEAD`. A branch tip is a moving target: if the contributor pushes between the diff being rendered and the worktree being built, the agent would Grep code that is not under review — which matters because the prompt's `Blast radius:` directive instructs it to search that tree and treat the result as evidence. Forks get the same treatment; `FETCH_HEAD` is just the fork branch's tip and drifts identically. Pinning fails when the SHA is blank, malformed, or unavailable after fetch (for example, after a force-push): neither a branch tip nor the user's checkout is an acceptable substitute. `head.sha` arrives from the GitHub API, so it is validated as a hex object name before reaching a git command line; a value like `HEAD`, `main`, or `--upload-pack=…` is refused rather than being passed as a revision.

### The review worktree is not indexed by the IDE
Reviews run against a detached git worktree at `$TMPDIR/pr-pilot-wt-<unique>` (`WebviewPanel.java`), deliberately isolated from the user's checkout. That path is **not a content root of the open IntelliJ project**, so it is in no module and no index. Any future IDE-native code intelligence must therefore query the **open project's** warm index (for "who calls this symbol?") rather than the worktree — PSI over an unindexed path yields a parse tree without resolution, which is no better than a textual search for far more complexity. The prompt's `Blast radius:` directive covers the same need portably, by having the model Grep the worktree it can already read.

### Review JSON parsing is self-healing, not all-or-nothing
`ClaudeService.parseReview` (in `review-engine`, shared by both the Claude CLI and Copilot SDK paths on both hosts — IntelliJ calls it in-process, VS Code via the sidecar's `reviews/generate`) tolerates and repairs common model schema deviations instead of rejecting the entire review, which previously surfaced as "The model returned an invalid review format" for otherwise-good output. Unknown top-level/comment fields are ignored; an over-long `summary` is truncated to 800 chars; a comment `body` with embedded newlines is collapsed to one line but otherwise preserved in full; a low-confidence `"issue"` is downgraded to `"suggestion"` rather than failing the review; the final `verdict` is derived from (and corrected to match) the surviving comments rather than trusting the model's stated verdict. Only individually-malformed line comments (missing/blank required field, invalid enum value) are dropped — one bad comment no longer discards the other 19. A hard parse failure remains only for genuinely non-JSON output or a missing `summary`/non-object root, since there is nothing to salvage in that case.

### Copilot model discovery
Both hosts discover the account's Copilot models from the runtime's live `models.list` RPC (Copilot SDK `listModels()`), not from `copilot help config`: the help output is a list baked into the installed CLI binary, so it misses newly rolled-out models and ignores account policy. Settings render the last successful list immediately and re-probe every time they open (stale-while-revalidate); a failed probe never replaces a previous good list, and failures are not cached, so the next open or **Refresh** retries. Only when nothing has ever loaded do hosts fall back to a short hardcoded suggestion list.

- **IntelliJ**: `CopilotModelDiscovery.refresh()` boots a headless SDK client and calls `listModels()`; if that fails it falls back to parsing the `` `model`: `` section of `copilot help config` (reported to the user as possibly stale). Concurrent refreshes share one probe. `PluginSettingsComponent` merges the result into the (editable) model combo in Settings → Tools → PR Pilot, shows a **Refresh** button, and explains the result's source or failure in the hint below the combo.
- **VS Code**: `copilot.ts` `listModels()` queries the SDK's `client.listModels()` directly (no CLI parsing) and `filterModelIds()` drops policy-`disabled`/blank IDs. The native Settings entries use static dropdowns for the supported Claude and Copilot fallback models. The **settings webview** (`settings.ts` + `settingsView.ts`) — opened via the gear in the PR Pilot view title or the `pr-pilot.openSettings` command — shows a live model dropdown and hides the non-active provider's model field. VS Code's declarative settings JSON cannot self-populate an enum or conditionally hide fields, which is why the webview is used for the account-specific picker. The underlying settings (`reviewProvider`, `reviewModel`, `reviewModelCopilot`, `reviewEffort`, `githubBaseUrl`, `copilotInheritMcp`, `copilotAutoEnableMcpOnReview`, `copilotConfigDir`) remain configurable in native Settings.
  The settings webview validates `githubBaseUrl` before saving, posts per-field saved/error feedback, exposes model-refresh status, and has a **Check connection** action that verifies `gh` authentication for the configured host.

### PR discovery scope
The shared PR list sends an explicit `searchScope` on `refreshPRs`: `currentRepo`, `reviewRequested`, `assigned`, or `authored`. `PrSearchQueryService` in `github-engine` builds the query for both hosts, and hosts return `listStatus` (`searchScope`, `currentRepo`, `resultLimit`, `limited`) with `prListLoaded` so the webview can explain what was searched and when additional PRs are hidden. To distinguish "exactly the limit" from "more exist", the search over-fetches one row beyond the display limit (`resultLimit` = 50, fetch 51): `limited` is true only when more than 50 match, and the list is sliced back to 50. `currentRepo` searches only the engine-detected repository; if no repo is detected it falls back to `author:@me`. Main-list discovery intentionally includes GitHub draft pull requests (the old `draft:false` filter was removed) so authored WIP PRs are discoverable; the shared PR DTO distinguishes `isDraft` (GitHub PR draft / `PR-DRAFT`) from `hasReviewDraft` (saved PR Pilot review draft / `REV-DRAFT`). Starred repositories are used only by optional notification polling, not by the main list's current-repo scope.

### Binary resolution
Probe known hard-coded paths for `gh`, `claude`, and `copilot` before falling back to command name, because GUI-launched IntelliJ often has incomplete `PATH`. `review-engine`'s `BinaryLocator` is the single JVM implementation of this probing logic (used by both `ClaudeService` and `CopilotService`, for both IntelliJ and the sidecar); `claude.ts`/`copilot.ts` keep an independent TypeScript copy for the same probing since the sidecar (Java) and the extension process (Node) are different runtimes — the sidecar is the one that actually spawns the CLI for VS Code, but the extension still preflight-checks binary availability locally before even asking the sidecar to start a review (see Provider preflight below). JVM provider subprocesses must prepend `~/.local/bin`, `~/.npm-global/bin`, and `~/.volta/bin` as well as Homebrew/local-bin paths: a resolved Node-backed CLI wrapper otherwise cannot find its runtime when Toolbox launches the IDE without shell initialization.

### Provider preflight
Before the PR list is first shown, both hosts check that the configured provider's CLI
(`claude`/`copilot`) is resolvable and publish binary/authentication states in the setup bridge
payload. Claude authentication uses the bounded, non-interactive `claude auth status --text`
probe; Copilot exposes no status-only command, so its authentication is explicitly `unverified`
and the checklist gives the manual `copilot login` action rather than claiming readiness. Missing
binaries and conclusively signed-out auth responses keep onboarding open with settings access.
Timeouts, execution errors, and unsupported or changed auth commands degrade to `unverified` so an
inconclusive probe cannot block onboarding. The same binary
preflight remains in the review path as defense in depth and produces actionable
`provider_not_installed` copy instead of attempting a doomed spawn. Availability checks:
`ClaudeService.isBinaryAvailable()`/`CopilotService.isBinaryAvailable()` (JVM, backed by
`BinaryLocator` and `ProviderSetupProbe` in `review-engine`);
`claudeBinaryAvailable()`/`copilotBinaryAvailable()` plus shared `existsOnPath`
(`claude.ts`/`copilot.ts`) in VS Code.

### Low confidence is a gate, not a label
Three parts of the pipeline previously treated `"confidence": "low"` as a way to *keep* an unconfirmed finding rather than a reason to discard it, and together they manufactured low-confidence comments:

- The prompt offered a downgrade as a peer of omission ("omit it **or** use a note with confidence low", and "drop to confidence low" for an inconclusive blast-radius search). Given that choice a model emits the comment, since something compliant beats nothing. Both now direct omission, and the prompt states that returning few comments — or none — is a correct outcome for a clean change.
- `repairLineComment` **downgraded** a low-confidence `"issue"` to `"suggestion"`. That converted the model's violation of "never report a low-confidence issue" into an accepted comment, so breaking the rule cost nothing. It is now dropped, and the prompt says so explicitly — a rule enforced only by invisible relabeling cannot change behavior.
- A low-confidence comment of any type must now carry a `rationale`. Previously the parser exempted `"note"`, and `reviewQuality.ts` exempts low-confidence comments from its own rationale check, so a bare low-confidence `"note"` was the cheapest comment the model could emit and nothing downstream removed it.

The self-critique directive is keyed on `confidence`, not on type, for a related reason: its input is `draftReviewJson` over an already-parsed draft, so by then no low-confidence `"issue"` exists and the old "drop a low-confidence issue" rule could never match anything. It now requires each surviving low-confidence comment to be confirmed and raised, or dropped.

`PROMPT_VERSION` is `2026-10-recall`. Outcome logging appends
`-supervisor-on` or `-supervisor-off`, so supervised and baseline results are not pooled.

### Prompt-injection hardening
When wrapping untrusted payloads in XML-like tags, escape matching closing tags inside payload to prevent tag breakout. Repository guidelines, PR metadata, descriptions, diffs, prior reviews, and chat context remain untrusted reference data even when tag escaping is applied; the latest `<user_message>` is the authorized request but cannot override persona or confidentiality constraints. Chat builders retain only ten history turns and bound each turn, PR context, focused context, and current request before provider execution. Provider capability isolation is the primary security boundary.

Review prompts permit only evidence supplied in the prompt and require a fixed JSON contract. Both provider parsers reject unknown fields, incomplete line comments, invalid enums, oversized values, low-confidence issues, low-confidence comments with no stated rationale, and verdict/comment mismatches before review data reaches the UI. Verify-comment and example-fix prompts use tagged reference data and strict JSON response shapes for the same reason.

### Diff acquisition model
Hosts fetch and bound the GitHub diff before provider execution and embed it in `<pr_diff>`. Review providers run with read-only tools scoped to the PR-branch worktree, so they *can* open files there to confirm a finding or resolve a symbol the diff omits — but they cannot reach the network or any path outside it, so the embedded diff remains the primary evidence. Single-pass review sends the 250 KB review diff. The separate 1 MB validation diff is retained host-side/webview-side for comment anchoring and is also the provider input for chunked review and IntelliJ-assisted deep review; it is not part of a single-pass prompt. When either diff omits files, `PRReviewRequest` strips its trailer into `DiffCoverage` so `<pr_diff>` never contains it, and review and critique prompts gain an escaped `<omitted_files>` section (see "Complete review diff coverage"). In VS Code, that validation diff is capped at 1 MB to match the webview bridge validator; a larger payload is rejected before the review pane receives `draftLoaded` or `reviewResult`. It is fetched only after the draft-status response so a slow large-diff download cannot leave the review pane stuck in draft loading.

### Complete review diff coverage
Single-pass review keeps its 250,000-byte diff budget, and the 1,000,000-byte validation budget is unchanged. What changed is how a diff over budget is bounded and disclosed. A byte-prefix cut could split a hunk or a UTF-8 sequence and silently dropped the files after it. Raising the limit would still be a silent cut, and would grow every single-pass prompt, the diff view, and the bridge payloads. Large PRs instead get explicit coverage disclosure plus the existing opt-in chunked path over the larger validation diff.

`PrDiffService.bound` streams the GitHub diff and splits it into per-file sections at `diff --git ` boundaries:
- In every mode, a section over 250,000 bytes is dropped, so it can never be anchored or partially reviewed.
- While the kept total exceeds the budget, the largest kept section is evicted, leaving the maximal smallest-first whole-file subset (ties keep original order). Smallest-first, unlike first-fit, is monotonic in the budget, so the review diff's kept files are always a subset of the validation diff's. That is what makes a chunked-review coverage-gain claim true.
- Reading stops at a 64 MiB scan ceiling, which marks the scan incomplete (`scan=incomplete`). When anything was omitted, the kept set is re-fitted to the budget minus a 16,384-byte trailer reserve, so body plus trailer never exceed the budget.
- Kept sections are emitted in original order, followed by the trailer.

The trailer (`DiffCoverage` in `core`, mirrored by `webview/src/lib/diffCoverage.ts` and pinned by the shared golden fixture `core/src/test/resources/diff-coverage/trailer.golden.txt`) is one `[pr-pilot:diff-coverage] omitted=… listed=… budget=… scan=…` line plus up to 200 `[pr-pilot:omitted] <path>` lines. Both parsers are strict and end-anchored and accept only a trailer the engine could have written; anything else is ordinary diff text with complete coverage. The trailer rides in-band so RPC, host, and bridge shapes stay unchanged. `PRReviewRequest` strips it into `diffCoverage`, and every request copy (semantic context, chunk batches, reconciliation, supervisor follow-up) preserves it. Review and critique prompts for both providers then add an escaped `<omitted_files>` section, listed as untrusted reference data. Its trusted preface states the count and budget, says those files were not reviewed, and forbids comments on them. It also requires the summary to state the unreviewed count; the posted review body is not edited deterministically. Comments the model still places on omitted files become orphans through the normal validation path.

GitHub answers HTTP 406 when a diff exceeds its own size limits. The engine attempts that request once and returns `diff_too_large`, which both hosts render as non-retryable "Pull request diff is too large" copy rather than a generic retry. There is no files-API or local `git diff` fallback. Chat keeps its 12,000-character excerpt, and the display keeps the 250 KB review diff; the banner and chat label describe those limits instead of claiming a full diff.

### Worktree-based PR context
When the PR's repo matches the open project/workspace and a git root is found, both hosts create a temporary git worktree checked out to the exact PR head commit and reuse it for both review and chat. This gives the model accurate local file context (correct branch state) for type lookups and cross-file references across the full PR session. Cleanup runs when the active PR changes or the view is disposed. If worktree creation fails or the PR is from an unrelated repo, review and PR chat stop with an actionable error; they never expose the open project/workspace directory to a provider. Fork PRs fetch the fork branch only to obtain the supplied exact head commit.

- **IntelliJ**: `WebviewPanel.resolvePrClaudeService` builds a per-PR `IntellijClaudeService` pointed at the worktree, using `review-engine`'s `GitWorktreeService`.
- **VS Code**: `extension.ts` `resolveWorkingDir`/`clearWorktree` own only the *lifecycle* — which directory belongs to the active PR, and when to tear it down. The git work goes through the sidecar's `worktrees` capability (`reviews/findGitRoot`, `reviews/createWorktree`, `reviews/removeWorktree`), so destination naming, the fork-versus-origin fetch decision, and head-SHA pinning have exactly one implementation. The resolved dir is passed as `projectDir` to `reviews/generate`/`reviews/chat`, which hands it to `review-engine`'s `ClaudeService`/`CopilotService` as the process working directory. Chat reuses an existing worktree and never requests GitHub credentials directly.

`reviews/createWorktree` reports `skipped` (no branch to check out) and `failed` (git could not produce one) as ordinary results rather than RPC errors, because every caller degrades to the user's own checkout — a worktree is an optimization, not a precondition. `reviews/findGitRoot` likewise answers `""` for a path that is not in a repository. Both keep the failure path free of exception handling that would otherwise be easy to get wrong in a way that fails the whole review.

### Cross-host parity
When host-specific logic changes in IntelliJ or VS Code, update the paired implementation in the other host. The mapping table and enforcement workflow live in `AGENTS.md`.

### User-facing error copy
Do not surface raw provider/HTTP exception strings directly to users in review/draft/chat flows. Both hosts map low-level errors to actionable guidance (`UserFacingErrors` in IntelliJ, `userFacingError.ts` in VS Code) to keep messaging consistent across providers. Provider errors must be mapped exactly once from the original exception; remapping rendered copy destroys the failure classification. Message strings live in shared YAML templates (`vscode-extension/shared/user-facing-errors.yaml`) and support `{placeholder}` substitution.

### Bridge payload validation
Bridge messages carry protocol version `1`. Both hosts validate webview-to-host messages before dispatching handlers (`BridgeMessageValidator` in IntelliJ, `bridgeValidation.ts` in VS Code), including PR identity, enums, nested review/comment shapes, booleans, collection sizes, and text bounds. The webview validates every host-to-webview payload in `bridge/validation.ts` before fan-out. Unknown versions/types and malformed nested payloads are rejected instead of reaching business logic. Optional wire fields are omitted rather than serialized as `null`; IntelliJ serializes bridge comments through strict MapStruct DTO mapping and omits absent rich metadata rather than emitting invalid empty enum values.

### GitHub API resilience policy
Both hosts apply a transient-failure policy on GitHub REST calls: 15s request/connect/socket timeout, retries on `429`/`5xx`, and retry of timeout-style transport errors. This keeps PR loading/review flows resilient to short-lived network or GitHub edge failures while preserving fast-fail behavior for permanent `4xx` errors.

### First-run onboarding path
When startup PR loading fails, hosts push a `setupRequired` bridge message with actionable detail instead of silently failing. Supported reasons are `gh_not_installed`, `gh_not_authenticated`, and `load_failed` (non-auth load errors). When this message arrives before the first successful PR-list load of the session, or with any reason other than `load_failed`, the app shows the full-pane setup/error screen with its checklist and Refresh. After a successful load, `load_failed` instead shows an inline, dismissible PR-list banner with Retry, keeping the last results, the review pane, chat, and submit controls usable; `App` and `PRList` apply the same rule. Shared-engine domain messages and each host's error mapper preserve actionable authentication guidance; VS Code additionally classifies setup-worthy auth failures in `classifySetupAuthError`. The VS Code host also triggers an initial `handleRefreshPRs` call immediately after the editor panel's webview is initialized (`initializeWebview`) so the webview never hangs on its initial loading state.

The setup screen is a guided in-app wizard with host detection. Both hosts expose status re-check, settings, and auth-guide actions; VS Code additionally supports one-click `gh auth login` automation via a `runAuthLogin` bridge action that opens an integrated terminal and runs the command.
After the first successful load (or a recovery from `setupRequired`), the shared PR list shows a one-time success coach banner that points users at scope switching and the `PR-DRAFT` vs `REV-DRAFT` mental model.

### PR chat scope
Chat is available after PR selection, before and after review generation. Hosts build chat context from the active PR title/body, the bounded 250 KB review diff, and the generated review when one exists; both hosts send the same diff so chat answers do not diverge by host. The engine then keeps a 12,000-character head-and-tail excerpt of that PR context (`MAX_CHAT_CONTEXT_CHARS` in `ClaudeService` and `vscode-extension/src/claude.ts`), so chat never sees a whole large diff. The webview displays which context buckets are attached, labels the diff bucket "diff excerpt" when the review diff omits files, and adds selected text when the user right-clicks or verifies a comment. Chat reuses the PR worktree when available. The VS Code host sources the active PR's title/body from `getPRDetail` on select (the webview `selectPR` message carries only number/owner/repo), so the review prompt and chat context always include the real PR description.

### DTO mapping in IntelliJ webview bridge
`WebviewPanel` model-to-DTO conversion uses MapStruct (`ReviewMapper`) instead of hand-rolled mappers so field drift fails at compile time.

### Webview bridge PR correlation
PR-scoped lifecycle messages (`draftLoaded`, review generation/chunks/results/errors, draft save/submit/delete, and chat responses) carry a `prKey` of `owner/repo#number`. The React webview drops keyed messages that do not match the active PR so late async results from a previously selected PR cannot repaint or submit against the current PR. When adding a new PR-scoped host message, include the same `prKey` in both hosts. Host-driven selection changes that originate outside the list (for example, background notifications) use a separate `activatePR` message carrying the full PR DTO so the app can honor its unsaved-review confirmation flow before sending the normal `selectPR` request back to the host.

Draft mutations are serialized per host view and bind the pending review ID to its full `prKey`. Selection revisions prevent late loads/saves/generation results from installing state after a PR switch; submit/delete reject IDs not owned by the active PR. Regeneration preserves the existing GitHub draft until a replacement is explicitly saved.

### Max-turns recovery for Claude
If stream-json returns `error_max_turns` with `session_id`, auto-resume via `claude --resume <session_id> --max-turns 3` and nudge for final JSON. `review-engine`'s `ClaudeService` is the single implementation of this behavior for both hosts (IntelliJ in-process, VS Code via the sidecar).

### Draft review storage semantics
Inline comment metadata is encoded in review body HTML comment for resilient draft reload. Pending review creation omits `event`. On 422 for inline comments, fallback to body-first creation then per-comment POST. When a pending draft lacks usable hidden metadata, hosts fall back to GitHub API review comments and set `importedFromGitHub`; the webview warns that recovered review details may be incomplete and offers a **Re-anchor from current diff** action that re-runs `validateComments` to snap comments back to valid positions, clears the imported flag, and (via autosave) re-encodes proper hidden metadata to GitHub so the draft reloads cleanly next time. GitHub replaces a pending review's body with an explicit submit `body` and keeps it when `body` is omitted (observed for COMMENT on 2026-09-25). Submission therefore always sends the composed body, never an omitted or empty body and never the hidden metadata, so General Notes and detached comments stored in the draft body are published; the webview publish dialog previews that composition (`publishBody.ts`).

### Draft autosave
GitHub's pending review remains the remote source of truth, while each host also persists a bounded,
token-free recovery snapshot before attempting a save (IntelliJ via `DraftRecoveryStore`, VS Code via
extension `globalState`). A snapshot is restored as dirty state after reopen and retried by the normal
autosave path; it is cleared only after a confirmed save, submit, delete, or merged-PR transition.
Replacing inline comments reads the head and existing pending review before deletion, adopts an exact
match, uses a documented body-only update when comments are unchanged, and otherwise creates the
replacement atomically with `comments[]`. If GitHub rejects an inline position, the fallback pending
review retains all comments in encoded metadata and a visible detached-comment section.

The webview autosaves from shared code in `ReviewPane.tsx` driven by `lib/autosave.ts`: a freshly
generated review saves immediately, and later edits are flushed on a 30s debounce plus panel hide and
PR switch. Dirty state is snapshot equality against the last acknowledged save; saves remain
correlated by `saveId`, and submit saves first when necessary.

### Large diff visibility
The review diff is bounded to 250 KB by whole files (see "Complete review diff coverage"). When its `DiffCoverage` trailer is present, the webview shows a coverage banner above the review and diff view, and in the no-draft state before generation. The banner states how many changed files were left out (or "at least" that many when the scan was incomplete) and the budget. It says the omitted files won't appear in the diff, the generated review, or chat. When chunked review would include some of them, it offers one action that turns chunked mode on for the next generation without starting a review; otherwise it suggests splitting the pull request. It lists the reported paths plus "+K more not listed". `DiffViewer` receives the diff body without the trailer and still lazily limits rendered changed lines for browser performance.

The shared diff viewer also owns file-level navigation: it keeps a sticky “currently viewing” file indicator visible while the review body scrolls and renders a GitHub-style changed-files tree for jumping between files. If a reviewer jumps to a file outside the initial 500 changed-line preview, `DiffViewer` expands the full diff before scrolling so navigation and comment focus never strand hidden files.

### Review quality gate and chunked review mode
The webview runs a `Review Quality Check` pass automatically over the current draft and validation diff to flag trust risks (unanchored comments, low-evidence high-severity findings, and missing rationale metadata). When risks are present it surfaces a non-blocking badge (`N trust risks detected — Review`) that expands to a panel with one-click in-memory repairs (`remove unanchored`, `add rationale placeholders`, `downgrade high-risk issues`); a clean draft shows no nag. The check remains non-blocking, but the submit dialog now requires an explicit reviewer acknowledgement checkbox whenever unresolved trust risks remain.

For larger PRs, reviewers can explicitly enable chunked mode in per-review overrides. The webview
sends the loaded validation diff (1 MB whole-file budget, or the review diff while the validation diff has not loaded) once with `chunkedReview: true`; `ChunkedReviewService` in the shared
review engine owns file batching, invokes the selected provider for every bounded batch, then performs
a mandatory final provider reconciliation over every batch result plus a bounded changed-file/contract
index. Every batch and reconciliation request copies the source `DiffCoverage`, so each prompt
names the files that even the validation diff omitted. Both IDE hosts therefore use the same global-synthesis behavior. A reconciliation I/O failure
returns complete, visibly marked degraded batch summaries rather than silently presenting a locally
sliced merge as globally synthesized output. The recommendation is honest about coverage: it cites
"Chunked review includes N changed file(s) that single-pass review omits." only when the validation
diff omits fewer files than the review diff, and says "Chunked review would not add coverage." when
the review diff is incomplete but chunking gains nothing. The unchanged file-count and changed-line
size heuristics can still recommend chunking. No recommendation ever enables chunked mode without
the reviewer selecting the option.

### Bounded review supervision

`ReviewPipelineService` owns the review pipeline for both hosts:

`primary/chunk batches -> reconciliation -> coverage analysis -> optional prioritization ->
one targeted follow-up -> final self-critique -> deterministic CI suppression`.

The persisted `reviewSupervisorEnabled` setting defaults to `true`. The base review emits an
engine-internal inspection ledger in both modes so the output contract stays stable; when the
setting is off, the engine does not analyze that ledger, make supervisor/follow-up calls, or filter
anchors. When enabled:

- `InspectionManifest` assigns stable IDs to changed files and hunks and records changed new-side
  lines. `ReviewPassParser` accepts only manifest IDs and repository-confined evidence paths.
- `ReviewCoverageAnalyzer` deterministically identifies uninspected high-risk hunks, plus changed
  files whose file ID and hunk IDs are all absent from the ledger (ranked below high-risk hunks). It
  does not infer gaps when the provider omitted its ledger and caps candidates at 12.
- Three or fewer gaps are prioritized deterministically. Larger sets get one tool-free,
  90-second provider call that sees only gap metadata and baseline finding locations and may select
  at most five supplied IDs.
- The engine authors the follow-up objectives and allows one six-minute, read-only worktree pass
  with MCP disabled. Baseline and follow-up findings are merged and deduplicated.
- `ReviewAnchorValidator` removes findings not attached to changed new-side lines before the
  existing final critique and CI suppression gates run once. Chunked reviews use their bounded
  contract index for the final critique rather than re-sending the full diff.

The primary pass remains terminal on failure. Supervisor selection, follow-up, and final critique
I/O or parse failures keep the best available review; interruption is always propagated. Aggregate
logs contain counts and elapsed time only, never paths, snippets, prompts, directives, or tool
arguments. User-visible activity reports generic stages (`Checking review coverage...`,
`Prioritizing missed areas...`, and `Inspecting missed areas...`) rather than model reasoning.

### Notification parity
Background PR notifications are available in both hosts and are off by default. The first poll seeds existing PRs silently. Both hosts support review-requested PR notifications and optional starred-repository PR notifications, using the persisted settings listed below. Each notification is labeled with its provenance (`Review requested` vs `★ Starred repo`) so a starred-repo PR — which need not appear in the main list's current-repo scope — is never mistaken for a review request; when a PR matches both sources, review-requested takes precedence. The labeling/merge logic is shared-shaped across hosts (`PRNotificationService.mergeCandidates`/`notificationTitle` in IntelliJ, `notifications.ts` `mergeBySource`/`notificationMessage` in VS Code). Notification actions now route into PR Pilot itself (`activatePR`) instead of straight to the browser: the shared list pins the opened PR into the list when needed and marks it as notification-opened even if it is outside the current discovery scope. The seen-PR set is persisted across reloads/restarts (IntelliJ via `SeenPRSet`, VS Code via extension `globalState`) so PRs that appear while the editor is closed are still announced on the next poll rather than silently absorbed by a re-seed. Changing the notification scope (enable/disable, review-requested, starred repos, or GitHub base URL) re-seeds silently so existing in-scope PRs are not announced retroactively.

### Comment anchoring policy
Client-side validation partitions comments: keep in-hunk, snap within +-3 lines, orphan otherwise. Orphans are excluded from inline POST and appended to review body section.

### Security constraints
`githubBaseUrl` must be an HTTPS origin without credentials, an explicit port, path, query, or fragment; external links must use HTTPS. GitHub/provider tokens are not persisted by PR Pilot. Provider review input is adversarial: do not enable shell/write permissions or broader environment capabilities by default. Primary review and the single supervisor follow-up may use only read-only tools in the detached PR worktree; supervisor prioritization has no tools, and both supervisor calls have MCP disabled. A detached worktree protects the active checkout but is not a machine sandbox, so explicit capability elevation must remain visible in settings.

### Repo detection and webview hosting
Repo detection walks upward to `.git/config` and reads the `[remote "origin"]` URL specifically (not the first `url=` in the file) so multi-remote/fork setups resolve to origin consistently across hosts, handling SCP and `ssh://` remotes correctly. Webview assets are served via loopback `HttpServer` for proper same-origin module loading; path normalization blocks traversal.

### VS Code webview surfaces
The VS Code host exposes PR Pilot as an editor-tab `WebviewPanel` opened by `pr-pilot.open`. The Activity Bar view (`pr-pilot.main`) is an empty native tree view whose `viewsWelcome` content offers an Open PR Pilot command button and a Settings link; it hosts no webview. Opening or revealing the editor panel never changes the sidebar's visibility. The full PR loading, review generation, chat, and worktree lifecycle run only in the editor-tab panel.

For development, the extension loads UI assets from the sibling `webview/dist` folder. For packaged `.vsix` builds, the release/package flow stages that same output into `vscode-extension/webview-dist`, and the extension resolves the bundled copy first so installed releases do not depend on the source repo layout.

All VS Code webview surfaces use restrictive Content Security Policy headers. The main Vite document rewrites only packaged local asset URIs and nonces scripts; the fallback error page nonces inline resources, and dynamic error text is HTML-escaped.

### IntelliJ webview surfaces
The IntelliJ `PR Pilot` tool window is the sole primary interactive surface. It owns one full `WebviewPanel` directly, so selecting the tool-window stripe always shows the real review UI without an editor-tab handoff, launcher, or duplicate webview lifecycle. Hiding the tool window preserves the session; removing its content or closing the project disposes the panel and its JCEF/loopback/worktree resources. This intentionally differs from VS Code's editor-panel container because IntelliJ tool windows can directly and reliably host the persistent JCEF Swing component; it is a container difference, not a feature difference. The plugin declares the `com.intellij.modules.jcef` bundled dependency in both `plugin.xml` and the IntelliJ Platform Gradle configuration; keep both declarations because Toolbox-based IDE distributions provide JCEF as a separate bundled module.

### Pinned IntelliJ SDK versus local sandbox

Compilation, ordinary tests, packaging, CI, and the original `runIde` resolve the
2026.1.4 SDK unconditionally. `ideaLocalPath` is sandbox-only: the separate
`intellijPlatformTesting.runIde` registration, `runIdeLocal`, owns independent
platform/runtime/sandbox configurations. Its installed-build selection is lazy, so
ordinary build/check must work without an eligible local installation and must never
launch an IDE. Selector fixture tests run under check without launching one.

The explicit override must be valid and at least build 262; it never silently falls
back. Automatic macOS discovery is limited to direct IntelliJ app bundles in the two
Applications directories and orders full numeric metadata builds, including EAPs.
Other platforms require an explicit path. Discovery may resolve aliases read-only,
but must not alter app bundles or normal IDE configuration/plugins. No remote
latest-version lookup or local-selection download fallback is permitted.

### VS Code extension development target repo
The `.vscode/launch.json` config `Run PR Pilot Extension Against Target Repo` prompts for an absolute repository path and passes it as `PR_PILOT_TARGET_REPO` to the Extension Development Host. Use it when the PR Pilot source repo is open in the main VS Code window but PR Pilot should inspect PRs for a different local checkout; `workspace.ts` makes repo detection, worktree creation, and CLI working directories resolve against the target repo instead of whichever folder VS Code opened in the dev host.

## Settings persistence

`PluginSettings` (`claudeReviews.xml`) stores:

- `githubBaseUrl` (default `https://github.com`)
- `notificationsEnabled` (default `false`)
- `notifyReviewRequested` (default `true`)
- `notifyStarredRepos` (default `false`)
- `notificationPollMinutes` (default `5`)
- `reviewModel` (default `""`)
- `reviewModelCopilot` (default `"claude-sonnet-4.6"`)
- `reviewProvider` (default `"claude"`; values `claude|copilot`)
- `reviewEffort` (default `"high"`; values `none|low|medium|high|xhigh|max`)
- `copilotInheritMcp` (default `false`) — explicit capability elevation that injects MCP servers read from the user's trusted Copilot config (`<configDir>/mcp-config.json`) via `setMcpServers`, while retaining the read/MCP-only permission allowlist. On-disk config discovery stays disabled, so the untrusted PR-branch worktree's repo-local `.mcp.json` is never loaded. Copilot-only.
- `copilotAutoEnableMcpOnReview` (default `false`) — review-only opt-in that forces MCP enablement for Copilot review generation even when `copilotInheritMcp` is off; chat still follows `copilotInheritMcp`. Copilot-only.
- `copilotConfigDir` (default `""`) — optional override of the Copilot config directory used to read trusted MCP servers; empty uses the CLI default (`~/.copilot`). Copilot-only.
- `reviewFocusAreas` (default `""`) — default reviewer focus areas; a non-empty per-review override takes precedence.
- `reviewCustomInstructions` (default `""`) — default extra review instructions; a non-empty per-review override takes precedence.
- `reviewGuidanceProfiles` (default `[]`) — named reusable bundles of focus areas and custom instructions. Profiles use stable IDs so renames preserve selection. Invalid, duplicate, or missing IDs fall back to the built-in legacy defaults rather than partially applying a profile. The legacy `guidanceGlobs` profile field remains readable and is preserved on edits for forward compatibility, but is not exposed in settings UI.
- `repositoryReviewInstructions` (default `{}`; VS Code `pr-pilot.repositoryReviewInstructions`) — review instructions remembered per repository, keyed by lowercase `owner/repo` (at most 200 repositories and 10,000 characters each; `RepositoryReviewInstructions` in IntelliJ, `repositoryInstructions.ts` in VS Code). At generation each host prepends the entry for the PR's repository to the engine's existing `customInstructions` input under an `Instructions remembered for owner/repo:` heading, ahead of the per-review override or the resolved profile/default, so no engine or wire field exists for it. Hosts send the entry on every `draftLoaded` as `repositoryInstructions`; the review pane's "Remember for this repository" posts `saveRepositoryInstructions` and receives `repositoryInstructionsSaved` (normalized text, empty when forgotten) or `repositoryInstructionsSaveError`. Both settings UIs list remembered repositories for editing and Forget. Keys ignore the GitHub host, so same-named repositories on github.com and an enterprise host share an entry.
- `activeReviewGuidanceProfileId` (default `""`) — selected profile ID; blank selects the built-in defaults stored in `reviewFocusAreas`, `reviewCustomInstructions`, and `reviewGuidanceGlobs`. Keeping those fields as the built-in profile makes upgrades migration-free.
- `reviewSelfCritique` (default `true`) — runs a second validation pass (`ClaudeService.buildCritiquePrompt`) that re-checks every finding against a contract index derived from the changed files and the same context sections the first pass saw, dropping misattributed, unsupported, and CI-duplicated comments. On by default because a misattributed comment costs the reviewer more than the extra pass does; disabling it roughly halves review latency. Shared by both providers.
- `reviewSupervisorEnabled` (default `true`; labeled "Re-inspect coverage gaps") — checks the primary
  inspection ledger for unreviewed high-risk hunks and uninspected files and may run one bounded,
  targeted read-only follow-up. Shared by both providers.
- `reviewSecondReviewerModel` (default `""`; VS Code `pr-pilot.reviewSecondReviewerModel`) — optional
  Copilot model run in parallel as a second reviewer whose findings are merged and cross-validated.
  Blank disables it. Uses the primary's effort when the primary is Copilot, otherwise Copilot's
  default effort. Sent to the engine as `secondReviewerModel`; ignored for IntelliJ-assisted deep
  review. Shared by both hosts.
- `experimentalIntellijAssistedReview` (default `false`; VS Code `pr-pilot.experimentalIntellijAssistedReview`) —
  experimental opt-in for IntelliJ-assisted review. Each host sends it to the webview as
  `intellijAssistedEnabled` on every `draftLoaded` and `prListLoaded` message. Anything other than
  `true` hides the IntelliJ-assisted checkbox and the Advanced-options retained-worktree button and
  resets a checked box; the `prListLoaded` value also hides the no-selection "Review maintenance"
  disclosure and the review-pane "Retained IntelliJ review worktrees" context-menu item unless an
  assisted setup is active. Retained worktrees are therefore removable again only after the setting
  is turned back on, which both hosts' setting hints state. While off, a `generateReview` request with
  `intellijAssisted: true` is rejected with a `reviewError` and never downgraded to an ordinary
  review. Shared by both hosts.
- `reviewGuidanceGlobs` (legacy, hidden) — retained in existing host settings and profiles for compatibility. It is not contributed as a public VS Code setting, rendered in either settings UI, or sent to a provider because the existing `reviews/readGuidelines` capability reads only the untrusted PR worktree.

Profiles are host-owned settings/UI state, not an engine capability. Each host resolves the active profile into the existing engine inputs; focus areas and custom instructions remain active, while guidance globs stay inactive — base-commit guidance is discovered by the engine, not selected by globs. Per-review non-empty focus/custom-instruction overrides still take precedence over the resolved active profile.

The VS Code equivalents live in `vscode-extension/package.json` under `contributes.configuration`. Each is declared twice — once as the contribution default, once as the fallback in `extension.ts`'s reader — and VS Code only honors the former, so a mismatch is silent. `vscode-extension/test/settingDefaults.test.ts` asserts the two agree for every boolean setting.

No API keys or tokens are written to disk.

## Local data files

The shared semantic runtime reads `~/.pr-pilot/semantic-review.json` (schema 1) as
machine-local launch configuration. It does not write that file or persist import baselines.
Owned extracted workers and per-call requests are temporary, outside the worktree, and removed
after their synchronous owned process calls finish. `semantic-worktrees.json` durably records
retained repository/worktree/head identities under this directory, guarded by
`semantic-worktrees.lock`; `semantic-leases/<id>.lock` holds the active execution lease.
Cancellation or restart releases process locks, not retention. Only explicit project-closed,
lease-free, identity-checked, clean non-force cleanup removes a retained worktree. The preparation
binding itself is memory-only: restarting preserves maintenance access, not authority to Continue.

IntelliJ-only (`intellij-plugin`'s `PendingReviewIndex`/`SeenPRSet`); VS Code persists the equivalent state via extension `globalState` instead (see Notification parity above).

| Path | Purpose |
|------|---------|
| `~/.pr-pilot/pending-prs.json` | Index of PRs with saved drafts (owner, repo, number, title, savedAt, headSha) |
| `~/.pr-pilot/pending-prs.json.corrupt-<timestamp>` | User-triggered quarantine copy of an unreadable draft index; GitHub drafts are unchanged |
| `~/.pr-pilot/seen-prs.json` | Set of `owner/repo#number` strings already notified about |
| `~/.pr-pilot/review-outcomes.jsonl` | Append-only outcome log: one JSON object per generated/submitted comment (`ReviewOutcomeLog`, engine-owned), including category but never prose or paths, written by both hosts on submit |

### Outcome logging is stateless because IntelliJ does not use `ReviewSessionService`
`reviews/recordOutcome` takes **both** the generated and the submitted comment sets from its caller rather than the engine remembering the generated review between calls. The obvious design — snapshot it in `ReviewSessionService.generate` — silently fails on IntelliJ, which calls `ClaudeService`/`CopilotService` in-process through `IntellijClaudeService` and never touches `ReviewSessionService` at all (that type is sidecar-only). Each host therefore retains the half it was previously discarding: IntelliJ keeps `generatedResult` because `handleSaveDraft` overwrites `lastResult` with the edits, and VS Code keeps `editedReviewResult` because it only ever sees the edits in the `saveDraft` message. Classification, fingerprinting, and the base `promptVersion` stay engine-owned, so the hosts hold UI state and implement no logic. The host supplies only the snapshotted supervisor boolean; the engine uses it to suffix its own version and keep the two pipeline modes separable. A draft **loaded from GitHub** is never logged: it was not generated in this session, so diffing against it would record every comment as `kept`.

### The outcome log stores hashes, never comment text or file paths
`ReviewOutcomeLog` writes only `(recordedAt, promptVersion, provider, model, commentFingerprint, outcome, type, severity, category, confidence)`. The fingerprint is a SHA-256 prefix over `file + line + whitespace-normalized body`, which is enough to correlate the same finding across prompt versions — the log's entire purpose — without persisting review prose or repository structure to disk. It is the only append-mode file under `~/.pr-pilot`: the whole-file JSON stores use tmp+`ATOMIC_MOVE`, but rewriting a growing log on every submit does not scale, so this one uses `O_APPEND` with a `MAX_LOG_BYTES` cap because nothing prunes it. Every failure is logged and swallowed — instrumentation must never break a review submission.
