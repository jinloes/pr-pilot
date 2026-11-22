# Sidecar capabilities

Key design decisions moved from [ARCHITECTURE.md](../../ARCHITECTURE.md). Each section encodes an
active constraint future code must respect.

### Engine capability boundary (test-enforced host parity)
Each engine declares its complete capability surface as one interface — `GitHubEngineApi` in
`github-engine`, `ReviewEngineApi` in `review-engine`, both in package `com.jinloes.prpilot.engine`.
Each carries an `RPC_METHODS` map from Java method name to JSON-RPC wire name, and
`StdioJsonRpcServer` registers a handler per entry (via a `Map<String, MethodHandler>` registry
rather than a `switch`, so the registered set is introspectable).

Engine result statuses are also part of the host contract. In particular, `PrDetailResult` and
`PrDiffResult` return `not_found_or_inaccessible` for GitHub HTTP 404 because GitHub deliberately
does not distinguish a missing resource from one hidden by authorization, while `GitHubFailure`
recognizes `X-GitHub-SSO: required` / SAML enforcement bodies (keeping only a sanitized `required`
marker) so hosts surface SSO token-authorization guidance instead of generic login copy. IntelliJ
preserves non-`ok` statuses in `GitHubOperationException`, and VS Code preserves them in
`GitHubOperationError`; user-facing adapters select shared templates from those typed statuses. The
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
Copilot model discovery and provider CLI/authentication probing were likewise moved behind
`reviews/listCopilotModels` and `reviews/probeProviderSetup` so VS Code no longer bundles
`@github/copilot-sdk`, duplicates `BinaryLocator`, or spawns `copilot`/`claude` locally.

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
fail every pending request. GitHub RPC contracts and host wiring live in
[github-rpc.md](github-rpc.md).
