# GitHub RPC and host wiring

Key design decisions moved from [ARCHITECTURE.md](../../ARCHITECTURE.md). Each section encodes an
active constraint future code must respect.

### GitHub and repository RPC contracts
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
described under [Complete review diff coverage](review-diff-coverage.md#complete-review-diff-coverage):
whole files only, never a byte cut, with an engine-authored `DiffCoverage` trailer whenever a file
is omitted. GitHub HTTP 406 maps to the non-retryable `diff_too_large` status. For ordinary review,
hosts can then send the same method with `localSource: {worktreeDir, baseSha, headSha}` to use the
verified local commit comparison instead of requesting the GitHub diff again. The stdio JSON-RPC
frame ceiling is 8 MiB in both Java and TypeScript so an escaped validation diff remains bounded
while fitting safely in a response.

The worktree capability also exposes `reviews/readReviewedBlob`. The host supplies the matching
checkout root, its own verified commit, a repository-relative path, and a one-based line. The
engine reads a regular UTF-8 Git blob (up to 1 MiB) without consulting mutable files, fetching, or
running content filters. Invalid, unavailable, or unsupported locations return an error.

The sidecar's `prs/getIncrementalDiff` capability (logical capability `prIncrementalDiff`) returns
the bounded diff of commits pushed since the viewer's latest submitted review. It returns `ok` with
`scope: "incremental"` (with `baselineSha`, `headSha` and `diff`) or `scope: "full"` (with a
`fallbackReason` when the incremental diff cannot be trusted, including `pr_scope_unavailable`
when the PR diff needed to scope the comparison cannot be used). On baseline or compare failures
that do not fall back, it returns a token-free failure status (`invalid_request`, `invalid_base_url`,
`not_installed`, `not_authenticated`, `rate_limited`, `network_error`, `api_failed`,
`not_found_or_inaccessible`) with no scope. See [Incremental review since the last submitted
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
body-only, carrying the detached comments in a visible section via
`DraftReviewCodec.buildOrphanSection`. Hidden metadata is regenerated from accepted inline comments
only, so detached comments cannot be resurrected when the draft is decoded. `DraftReviewCodec` owns
the stable metadata tag format and short JSON keys used by both hosts. `submit` first reads the
pending review (`GET .../reviews/{id}`; a failed read returns that failure and posts nothing), then
posts to `.../reviews/{id}/events` with an explicit body: the reviewer's dialog text followed by the
pending body's visible text, with the hidden metadata removed by `DraftReviewCodec.visibleBody`. The
per-event default body is used only when both are empty. `delete` removes a pending review. HTTP
retries are method-aware: idempotent `GET` reads and `PUT` body updates keep bounded retries (and
rate-limited `403` responses with `x-ratelimit-remaining: 0` or `Retry-After` map to `rate_limited`
rather than `not_authenticated`), whereas non-idempotent `POST` and `DELETE` mutations receive one
attempt because a lost response after server acceptance must not duplicate a review/submission or
turn a completed delete into a synthetic `404`. All three share the token-free status vocabulary
(`invalid_request`, `invalid_base_url`, `not_installed`, `not_authenticated`, `rate_limited`,
`network_error`, `api_failed`) plus `ok`. The host-side mutation-serialization guard
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
non-`ok` statuses to `IOException`. The VS Code extension calls the same services through the
sidecar's bounded Content-Length-framed JSON-RPC protocol. Hosts never receive, cache, or pass
GitHub tokens. Authentication, retries, URL/API normalization, PR queries, repository detection,
diffs, review context, and draft mutations belong only in `github-engine`.

For VS Code, `extension.ts` owns one process-wide `SidecarClient` created and initialized in
`activate()` and disposed during extension teardown. Initialization verifies protocol version 1 and
every required GitHub capability before requests proceed. The sidecar is mandatory for GitHub
operations: missing Java 21+, a missing/corrupt jar, process failure, timeout, incompatible
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

Certificate failures keep the network status. `GitHubHttpClient` reports an `IOException` whose
cause chain holds a certificate-validation error (`GitHubFailure.isCertificateFailure`) as
`GitHubResponse.TLS_ERROR` and does not retry it, because an untrusted root or mismatched
certificate fails the same way every time. `isNetworkError()` stays true for it and the wire status
stays `network_error`, so no host status union or protocol validator changes; only the message
differs (`GitHubFailure.TLS_UNTRUSTED`). Both hosts pick the `tls_certificate_untrusted` template in
`vscode-extension/shared/user-facing-errors.yaml` by matching "TLS certificate" in that message
(`userFacingError.ts`, IntelliJ `UserFacingErrors.forGitHub`), so keep that phrase when rewording
it. The usual cause is a JDK whose `lib/security/cacerts` was replaced by a store without public
roots; `curl` and `gh` still work because they trust the macOS keychain. The diff paths
(`PrDiffService`, `IncrementalDiffService`) keep their own transport enum and still report the plain
network message.
