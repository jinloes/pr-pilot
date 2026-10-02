# Semantic review

Key design decisions moved from [ARCHITECTURE.md](../../ARCHITECTURE.md). Each section encodes an active constraint future code must respect.

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
