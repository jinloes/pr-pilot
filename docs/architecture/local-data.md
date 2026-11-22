# Local data and outcome logging

Inventory and design constraints for files PR Pilot reads and writes under `~/.pr-pilot`
(referenced from [ARCHITECTURE.md](../../ARCHITECTURE.md#local-data-files)).

## Files

| Path | Owner / Scope | Purpose |
|------|---------------|---------|
| `~/.pr-pilot/semantic-review.json` | User-authored | Machine-local semantic launch config (schema 1; read-only) |
| `~/.pr-pilot/semantic-worktrees.json` | `review-engine` | Retained worktree/head identities (`semantic-worktrees.lock`, `semantic-leases/<id>.lock`) |
| `~/.pr-pilot/pending-prs.json` | IntelliJ only | Saved-draft PR index (VS Code uses `globalState`; `.corrupt-<timestamp>` on quarantine) |
| `~/.pr-pilot/seen-prs.json` | IntelliJ only | Notified `owner/repo#number` set (VS Code uses `globalState`) |
| `~/.pr-pilot/review-outcomes.jsonl` | Both hosts | Append-only outcome hashes/metadata per submitted review |

## Semantic runtime and host state

The shared semantic runtime reads `~/.pr-pilot/semantic-review.json` (schema 1) as machine-local
launch configuration; it never writes that file or persists import baselines. Owned extracted
workers and per-call requests live in temporary directories outside the worktree and are removed
after their synchronous process calls finish. `semantic-worktrees.json` durably records retained
repository/worktree/head identities under `~/.pr-pilot`, guarded by `semantic-worktrees.lock`, while
`semantic-leases/<id>.lock` holds the active execution lease. Cancellation or restart releases
process locks, not retention: only explicit project-closed, lease-free, identity-checked, clean
non-force cleanup removes a retained worktree. The preparation binding itself is memory-only, so
restarting preserves maintenance access without granting authority to Continue.

`pending-prs.json` (with user-triggered `.corrupt-<timestamp>` quarantine copies that leave GitHub
drafts untouched) and `seen-prs.json` are IntelliJ-only (`PendingReviewIndex` and `SeenPRSet`); VS
Code persists the equivalent state via extension `globalState` (see
[Notification parity](hosts-and-sidecar.md#notification-parity)).

### Outcome logging is stateless because IntelliJ does not use ReviewSessionService
`reviews/recordOutcome` takes **both** the generated and the submitted comment sets from its caller
rather than the engine remembering the generated review between calls. The obvious design — snapshot
it in `ReviewSessionService.generate` — silently fails on IntelliJ, which calls
`ClaudeService`/`CopilotService` in-process through `IntellijClaudeService` and never touches
`ReviewSessionService` at all (that type is sidecar-only). Each host therefore retains the half it
was previously discarding: IntelliJ keeps `generatedResult` because `handleSaveDraft` overwrites
`lastResult` with the edits, and VS Code keeps `editedReviewResult` because it only ever sees the
edits in the `saveDraft` message. Classification, fingerprinting, and the base `promptVersion` stay
engine-owned, so the hosts hold UI state and implement no logic. The host supplies only the
snapshotted supervisor boolean; the engine uses it to suffix its own version and keep the two
pipeline modes separable. A draft **loaded from GitHub** is never logged: it was not generated in
this session, so diffing against it would record every comment as `kept`.

### The outcome log stores hashes, never comment text or file paths
`ReviewOutcomeLog` writes only `recordedAt`, `promptVersion`, `provider`, `model`,
`commentFingerprint`, `outcome`, `type`, `severity`, `category`, and `confidence`.
The fingerprint is a SHA-256 prefix over `file + line + whitespace-normalized body`, which is enough
to correlate the same finding across prompt versions — the log's entire purpose — without persisting
review prose or repository structure to disk. It is the only append-mode file under `~/.pr-pilot`:
the whole-file JSON stores use tmp+`ATOMIC_MOVE`, but rewriting a growing log on every submit does
not scale, so this one uses `O_APPEND` with a `MAX_LOG_BYTES` cap because nothing prunes it. Every
failure is logged and swallowed — instrumentation must never break a review submission.
