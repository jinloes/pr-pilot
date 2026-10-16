# Local data and outcome logging

Design constraints for files PR Pilot writes under `~/.pr-pilot`. The file inventory is in
[ARCHITECTURE.md](../../ARCHITECTURE.md#local-data-files).

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
