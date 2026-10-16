# Sidecar capabilities

Key design decisions moved from [ARCHITECTURE.md](../../ARCHITECTURE.md). Each section encodes an
active constraint future code must respect.

### Engine capability boundary (test-enforced host parity)
Each engine declares its complete capability surface as one interface — `GitHubEngineApi` in
`github-engine`, `ReviewEngineApi` in `review-engine`, both in package `com.jinloes.prpilot.engine`.
Each carries an `RPC_METHODS` map from Java method name to JSON-RPC wire name, and
`StdioJsonRpcServer` registers a handler per entry (via a `Map<String, MethodHandler>` registry
rather than a `switch`, so the registered set is introspectable).

Engine result statuses are also part of the host contract. In particular, `PrDiffResult` returns
`not_found_or_inaccessible` for GitHub HTTP 404 because GitHub deliberately does not distinguish a
missing resource from one hidden by authorization. IntelliJ preserves non-`ok` statuses in
`GitHubOperationException`, and VS Code preserves them in `GitHubOperationError`; user-facing
adapters select shared templates from those typed statuses rather than matching engine prose. The
404 template must retain both possible causes and make account switching conditional—never claim
that access is the definite cause.

`EngineCapabilityCoverageTest` in the sidecar fails the build when a capability is declared without
a wire name, exposed without being declared, declared without a registered handler, or registered
without being advertised in the `initialize` handshake. This replaces the hand-maintained
IntelliJ↔VS Code mapping table that previously lived in `AGENTS.md`, which could silently go stale.
The rule it encodes: **hosts may lag in consuming a capability, but no host may re-implement one** —
a capability the sidecar does not expose leaves a host no option but to fork the logic, which is the
drift the boundary prevents.

`SidecarBootstrapService.CAPABILITY_METHODS` groups every wire method under the logical capability
name the `initialize` handshake advertises, and the advertised map is *derived* from it rather than
written out a second time. The handshake stays keyed by logical names (not raw wire names) because
that is the existing client contract and because some capabilities are only meaningful as a set —
`draftReviewMutations` covers save/submit/delete, and a client that started against a partially
implemented mutation surface would fail mid-review rather than at startup. Grouping is mandatory: an
ungrouped wire method fails the build, which is what stops a capability from being reachable over
RPC yet invisible to every client.

`ReviewSessionService` lives in `review-engine`, not the sidecar, precisely so it is reachable
in-process (IntelliJ, a future CLI) as well as over RPC. It has no transport, Spring, or IDE
dependencies. `GitHubEngine` is a pure delegation composition root over the individual services, so
behavior — and the existing per-service tests — stay where they were.

The client side is enforced separately by `vscode-extension/test/wireCatalog.test.ts`, which parses
the engine interfaces as the source of truth and fails when the `sidecar*.ts` clients have no method
for a declared wire name, calls a wire method no engine declares, or lets `REQUIRED_CAPABILITIES`
drift from `CAPABILITY_METHODS`. This exists because the Java-side test proves only that a
capability is *reachable*: Phase 1's four context capabilities were exposed, coverage-tested, and
never called by VS Code for months, so every VS Code review silently shipped with empty
`<ci_status>`, `<commits>`, `<linked_issue>`, and `<repo_profile>` sections. What remains genuinely
hand-mirrored is the notification shapes (which no interface declares) and the duplicated TypeScript
logic (worktrees, binary probing, notification polling, prompt constants); `MIRRORS` in
`scripts/host-mirrors.mjs` lists each pair, and `scripts/verify.mjs` reminds you of the other side
when a change touches one. Guidance-doc reading used to be on that list and was retired by
moving it behind the `reviews/readGuidelines` capability — the two copies had already diverged on
the truncation marker, which is the concrete argument for preferring deletion over dual maintenance.

### Sidecar streaming: async requests plus server-push notifications
The base JSON-RPC loop in `StdioJsonRpcServer.run()` is still one blocking read → dispatch →
optional single write per iteration, but the methods in `StdioJsonRpcServerAsyncReviewHandlers`
deviate: `generateReview`/`chatReview` (and the deep-review prepare/list/cleanup handlers) hand
the actual review-engine call to `StdioJsonRpcServerOperationScheduler`, which runs it on the
server's dedicated `reviewExecutor` (a single-thread pool), and return `null` immediately, so the
read loop stays free to accept a `reviews/cancel` request (or any other RPC) while a review is in
flight. The eventual result is written asynchronously from the background thread once the work
completes. Progress is reported via `reviews/status`/`reviews/chunk`/`reviews/chatChunk` —
JSON-RPC notifications (no `id` field) carrying a `requestId` field that correlates them back to
the originating request, sent from the background thread under the same `writeLock` used by the
main loop so frames never interleave.

JSON-RPC request IDs remain transport correlations only. Every review/chat request also carries a
bounded opaque `operationId`; a logical chunked review reuses one ID for every batch, and
cancellation accepts only that ID. `ReviewSessionService` registers the exact provider cancellation
callback and a durable `CancellationToken` under the ID, marks the token before invoking the
provider, and removes the operation with identity-aware cleanup, so a stale completion cannot erase
or cancel a replacement and late provider startup observes cancellation.
`StdioJsonRpcServerOperationScheduler` indexes scheduled operation slots by that ID; each slot owns
the original request ID, start/cancel state, future, and exactly-once terminal response, so
cancelling a queued request completes its original JSON-RPC request rather than leaking it. Unknown
or completed IDs return `cancelled: false`; malformed IDs are `-32602` wire errors. Hosts register
their active semantic ID before preflight/worktree awaits and retain local generation/chat
revisions solely to suppress stale UI output.

On the VS Code side, `SidecarClient` correlates notifications to their request via a
`notificationHandlers` map keyed by the same numeric `id` used for the request/response pending map;
`requestRaw` accepts an optional per-call timeout (`REVIEW_REQUEST_TIMEOUT_MS`, 35 minutes, since a
real review/chat CLI invocation can legitimately run far longer than the default 60s RPC timeout)
and optional notification handlers, cleaned up on both success and failure paths. A request timeout
is scoped to that request and must not stop the child or reject unrelated work; only transport-fatal
events such as process exit, malformed framing, or a failed stdin write invalidate the process and
fail every pending request.

The engine's repository detector reads local git metadata directly (no git process spawned) to
resolve the owner/repo for a directory. `GitDirectoryResolver` understands linked-worktree `.git`
files (a regular file containing `gitdir: <path>`, relative or absolute) in addition to a standard
`.git` directory. `RemoteUrlParser` requires exactly two non-blank path segments (owner and repo),
including for SCP-style URLs. Every non-`found` outcome (`not_git`, `config_missing`,
`origin_missing`, `origin_url_malformed`, `gitdir_malformed`, `gitdir_unreadable`, etc.) is a typed,
non-fatal `DetectStatus`; over JSON-RPC these are normal results, never protocol errors. `-32602` is
reserved for malformed RPC params (missing/non-string `path`).

The sidecar's `github/checkAuth` capability validates an HTTPS GitHub origin, runs `gh auth token`
with the matching Enterprise hostname when needed, and verifies the resulting token with the host's
`/user` API. It returns token-free structured statuses (`authenticated`, `not_installed`,
`not_authenticated`, `api_failed`, or `invalid_base_url`) as normal domain results; never log,
serialize, or include the token in an error message. Invalid JSON-RPC request parameters remain
protocol errors.

The sidecar's `prs/list` capability owns its `gh auth token` lookup and GitHub `/search/issues`
call; hosts never pass a token over JSON-RPC. It uses the canonical `PrSearchQueryService` rules,
fetches 51 rows but returns at most 50 with an explicit `limited` flag, and returns token-free
domain statuses (`not_installed`, `not_authenticated`, `invalid_base_url`, `rate_limited`,
`network_error`, or `api_failed`) rather than JSON-RPC errors. JSON-RPC errors remain reserved for
malformed parameters and protocol failures.

The sidecar's `prs/getDetail` capability owns its `gh auth token` lookup and GitHub pull-request
metadata request. It validates owner/repository path segments, maps malformed GitHub JSON to
`api_failed`, and returns only title/body, merged status, and nullable head/base repository metadata
needed for fork-aware worktrees. No token, HTTP response body, or raw exception becomes protocol
output.

The sidecar's `prs/getDiff` supports both review mode (250,000-byte budget) and validation mode
(1,000,000-byte budget, used for chunked review, IntelliJ-assisted deep review, inline-comment
position validation, and draft anchoring). Both modes run the same `PrDiffService.bound` selection
described under "Complete review diff coverage": whole files only, never a byte cut, with an
engine-authored `DiffCoverage` trailer whenever a file is omitted. GitHub HTTP 406 maps to the
non-retryable `diff_too_large` status. The stdio JSON-RPC frame ceiling is 8 MiB in both Java and
TypeScript so an escaped validation diff remains bounded while fitting safely in a response.

The sidecar's `prs/getIncrementalDiff` capability (logical capability `prIncrementalDiff`) returns
the bounded diff of commits pushed since the viewer's latest submitted review. It returns `ok` with
`scope: "incremental"` (with `baselineSha`, `headSha` and `diff`) or `scope: "full"` (with a
`fallbackReason` when the incremental diff cannot be trusted). On real GitHub failures it returns a
token-free failure status (`invalid_request`, `invalid_base_url`, `not_installed`,
`not_authenticated`, `rate_limited`, `network_error`, `api_failed`, `not_found_or_inaccessible`)
with no scope. See [Incremental review since the last submitted
review](review-comments-context.md#incremental-review-since-the-last-submitted-review).

The sidecar's `prs/getDraftReview` capability owns its `gh auth token` lookup and the GitHub
pending-review lookup (`GET .../pulls/{number}/reviews` filtered to `state == PENDING`, plus that
review's inline comments). `DraftReviewCodec` decodes the PR Pilot
`claude-verdict`/`claude-summary`/`claude-comments` HTML-comment tags embedded in the review body,
falling back to raw GitHub inline comments (with an `importedFromGitHub` flag) when those tags are
absent or malformed. `none` (no pending review exists) is a normal domain result, not a failure;
other token-free domain statuses (`invalid_request`, `invalid_base_url`, `not_installed`,
`not_authenticated`, `rate_limited`, `network_error`, `api_failed`) are returned the same way
`prs/getDetail` does. No token is ever logged, serialized, or included in an error message.

The sidecar's `prs/saveDraftReview`, `prs/submitReview`, and `prs/deleteDraftReview` capabilities
expose `DraftReviewMutationService` over JSON-RPC. `save` deletes any existing pending review first
(non-fatal on failure), fetches the PR's head SHA, and POSTs a new pending review; on a GitHub `422`
(one or more inline comments reference an invalid path/line) it falls back to creating the review
body-only, then POSTs each comment individually so only the bad ones are dropped, appending a
"Comments not attached inline" section to the body via `DraftReviewCodec.buildDroppedSection` when
any are dropped. Hidden metadata is regenerated from accepted inline comments only, so detached
comments cannot be resurrected when the draft is decoded. `DraftReviewCodec` owns the stable
metadata tag format and short JSON keys used by both hosts. `submit` first reads the pending review
(`GET .../reviews/{id}`; a failed read returns that failure and posts nothing), then posts to
`.../reviews/{id}/events` with an explicit body: the reviewer's dialog text followed by the pending
body's visible text, with the hidden metadata removed by `DraftReviewCodec.visibleBody`. The
per-event default body is used only when both are empty. `delete` removes a pending review. HTTP
retries are method-aware: retry-safe reads/deletes keep bounded retries, but non-idempotent POST
mutations receive one attempt because a lost response after server acceptance must not duplicate a
review, comment, or submission. All three share the token-free status vocabulary (`invalid_request`,
`invalid_base_url`, `not_installed`, `not_authenticated`, `rate_limited`, `network_error`,
`api_failed`) plus `ok`. The host-side mutation-serialization guard
(`state.mutationQueue`/`enqueueMutation` in `extension.ts`) is UI-state sequencing local to each
host, not a GitHub API concern.

`PrSupplementalService` supplies the remaining token-safe read paths needed to remove host-local
GitHub HTTP: bounded arbitrary PR searches (`prs/search`, query at most 8 KiB and limit at most
100), up to 200 starred repositories (`repos/listStarred`), and formatted submitted-review context
(`prs/getExistingReviews`). Individual inline-comment lookup failures do not discard otherwise
usable submitted-review context. A thread-state (GraphQL) failure likewise keeps the summary, drops
only the `[resolved]` tags, and adds a `(Thread resolution state was unavailable.)` marker.

`PrCommitsService` also extracts a de-duplicated list of same-repository closing issue numbers from
raw commit messages before their display summary is truncated. `LinkedIssueService` resolves PR-body
closing references first, then uses those commit references to fill the existing three-issue cap.
Both hosts reuse the result of their single commit fetch when requesting linked-issue context; they
must not issue a second commit request. The resulting issue text remains untrusted prompt data.

### GitHub engine host wiring
GitHub behavior has one implementation in `github-engine`. IntelliJ consumes it in-process through
`IntellijGitHubService`, which holds a single `GitHubEngineApi` and adds only host adaptation —
reading the configured base URL, mapping engine records onto the shared models, and converting
non-`ok` statuses to `IOException`. It previously instantiated eleven engine services itself and
repeated `GitHubEngine`'s delegation, which meant a capability added to the interface was invisible
to IntelliJ. The VS Code extension calls the same services through the sidecar's bounded
Content-Length-framed JSON-RPC protocol. Hosts never receive, cache, or pass GitHub tokens.
Authentication, retries, URL/API normalization, PR queries, repository detection, diffs, review
context, and draft mutations belong only in `github-engine`.

For VS Code, `extension.ts` owns one process-wide `SidecarClient` created and initialized in
`activate()` and disposed during extension teardown. Initialization verifies protocol version 1 and
every required GitHub capability before requests proceed. The sidecar is mandatory for GitHub
operations: missing Java 17+, a missing/corrupt jar, process failure, timeout, incompatible
capabilities, or malformed protocol output is surfaced as a setup/runtime error and must never
activate a TypeScript GitHub fallback. RPC requests have a 60-second bound for GitHub network
operations. A user-facing **Retry** action restarts the stopped sidecar, clears only transport
state, and re-runs initialization/capability validation; it never falls back to host-local GitHub
behavior. `none` remains a valid `prs/getDraftReview` domain result; other token-free domain
statuses flow into the existing setup/error UI.

`resolveSidecarJarPath` mirrors `resolveWebviewDistPath`'s dev/packaged fallback: it prefers a jar
staged at `vscode-extension/sidecar/pr-pilot-sidecar.jar` (produced by `scripts/stage-sidecar.mjs`
from the Gradle `:sidecar:bootJar` output, matching the webview's `stage-webview.mjs` pattern) and
falls back to the sibling `sidecar/build/libs/pr-pilot-sidecar.jar` during local development. The
release pipeline runs `:sidecar:bootJar` before packaging so shipped `.vsix` builds bundle the jar.

IntelliJ intentionally does not spawn the sidecar process: the plugin already runs in the same JVM
and calls `github-engine` directly. This is a container difference, not a behavior fork.
