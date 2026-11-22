# Review pipeline

Key design decisions moved from [ARCHITECTURE.md](../../ARCHITECTURE.md). Each section encodes an
active constraint future code must respect.

### Boundary compatibility findings are evidence-gated
The engine-owned review prompt treats service/module boundary compatibility as a first-class
category, but only for located callers or consumers that still use an old contract, schema, field,
config key, exported type, or behavior. Review providers must search the local worktree first and
may use a read-only cross-repo MCP search only when the active provider/session exposes one. If all
located callers are updated, or no caller can be found through available search, the finding is
dropped rather than reported speculatively. `compatibility` is therefore part of the engine category
set and mirrored by host/webview validators only so engine output can round-trip safely.

### Repository guidance confinement

`RepoGuidelinesReader` treats configured paths and globs as untrusted input. It resolves from the
repository's real root, rejects absolute and escaping paths, never follows symbolic-link path
segments, and accepts only physically contained regular files. Its prompt cap is measured in UTF-8
bytes and truncation stops at a Unicode code-point boundary; host code must not replace these checks
with `File.isFile`, string-prefix containment, or UTF-16 `String.length()` accounting. Guidance from
a PR-head worktree is also author-controlled, so hosts never read it and always send
`repoGuidelines` blank.

### Review guidance, file history and call sites come from the base commit
Hosts pass the GitHub-reported `baseSha` (`PrDetail.baseSha`, validated as a full hex object ID) on
`PRReviewRequest`. Before the first provider call, `ReviewPipelineService` asks `BaseCommitContext`
to resolve trusted guidance (configured guidance globs first, then root and changed-directory-scoped
`AGENTS.md`/`CLAUDE.md` and contributing/PR-template files, capped at 48 KB), recent commit history
of each changed file, and textual call sites of changed declarations, reading only base-commit git
objects via read-only plumbing (`cat-file`, `ls-tree`, `log`, `grep <sha>`) with per-command and
overall time bounds. A missing base commit is fetched once from `origin` by SHA. Guidance is never
read from the worktree or head tree, so a PR cannot inject policy by editing guidance files — those
edits remain review evidence only. Any failure (not a git work tree, malformed SHA, unreachable
commit, timeout) degrades to an unenriched review; only cancellation propagates. Call sites are
whole-word matches (`ChangedSymbols` picks declarations from removed lines and hunk-header context,
skipping test files) in unchanged files; they are labeled as candidates to confirm and run last on
their own sub-budget so large repositories cannot starve guidance or history. When the diff adds an
enum, TypeScript union, or sealed-hierarchy variant (`ChangedEnumVariants`), `BaseCommitContext`
also queries unchanged files mentioning that type for `switch`/`when`/`match` lines so unhandled or
default dispatch arms stay visible even past `MAX_LISTED_REFERENCES`. Changed `.proto` files get a
computed schema delta; see [proto-review.md](proto-review.md). Configured guidance globs
(`PRReviewRequest.guidanceGlobs`, from the active profile's or default `reviewGuidanceGlobs`) are
additive to the defaults: each becomes a literal pathspec prefix (`..`, absolute, and
wildcard-leading globs read nothing) read only from `baseSha`. No repository-specific path is built
in; a repo whose rules live elsewhere (for example `.review/ai-agent/*.md`) configures that glob.
Resolved base-commit guidance replaces host-supplied `repoGuidelines` unless the base commit yields
none.

### Recall: second reviewer and candidate validation
Recall is traded for precision in one place only: the final critique. When self-critique is on or a
second reviewer is configured, the review prompt allows a bounded number of low-confidence
`candidate` findings that the critique must confirm or drop; unconfirmed candidates never reach the
user, even when critique itself fails. An optional second reviewer (`reviewSecondReviewerModel`,
always a Copilot model) runs the same request in parallel with the primary; its findings are merged
and deduplicated with the primary's before critique, it can never fail the review (its errors and
timeouts are logged and ignored), and cancelling the primary cancels it. Once the primary completes,
the pipeline waits at most `DEFAULT_SECONDARY_POST_PRIMARY_TIMEOUT_MS` (3 minutes) for the second
reviewer before cancelling it and continuing with the primary's findings. Its progress (started,
still running, any tool calls while waiting, finished/timed out/failed) is reported only from the
primary's thread, because host status sinks such as the sidecar's stdout are not thread-safe. The
final comment count is capped after merging. `ReviewOutcomeLog` records `secondReviewerModel` and
per-comment `corroborated` status so outcome analysis can segment second-reviewer runs; see
[review-findings.md](review-findings.md#reviewer-attribution-and-corroboration) for near-duplicate
collapse and reviewer attribution.

### Low confidence is a gate, not a label
Three parts of the pipeline previously treated `"confidence": "low"` as a way to *keep* an
unconfirmed finding rather than a reason to discard it, and together they manufactured
low-confidence comments:

- The prompt offered a downgrade as a peer of omission ("omit it **or** use a note with confidence
  low", and "drop to confidence low" for an inconclusive blast-radius search). Given that choice a
  model emits the comment, since something compliant beats nothing. Both now direct omission, and
  the prompt states that returning few comments — or none — is a correct outcome for a clean change.
- `repairLineComment` **downgraded** a low-confidence `"issue"` to `"suggestion"`. That converted
  the model's violation of "never report a low-confidence issue" into an accepted comment, so
  breaking the rule cost nothing. It is now dropped, and the prompt says so explicitly — a rule
  enforced only by invisible relabeling cannot change behavior.
- A low-confidence comment of any type must now carry a `rationale`. Previously the parser exempted
  `"note"`, and `reviewQuality.ts` exempts low-confidence comments from its own rationale check, so
  a bare low-confidence `"note"` was the cheapest comment the model could emit and nothing
  downstream removed it.

The self-critique directive is keyed on `confidence`, not on type, for a related reason: its input
is `draftReviewJson` over an already-parsed draft, so by then no low-confidence `"issue"` exists and
the old "drop a low-confidence issue" rule could never match anything. It now requires each
surviving low-confidence comment to be confirmed and raised, or dropped.

`ReviewPrompts.PROMPT_VERSION` is derived automatically by SHA-256 hashing every packaged prompt
resource (`src/main/resources/review-prompts/`), section preface, language and triggered checklist,
and bundled review rule, so any prompt edit segments `review-outcomes.jsonl` without manual bumps.
Pass B appends `BUG_HUNT_CHECKLIST` (the defect classes a generic "look for bugs" instruction skips,
including evidence-gated security classes), `LanguageChecklists` entries for only the languages the
diff changes, and
`TriggeredChecklists` entries for only the kinds of file it changes (see
[review-checklists.md](review-checklists.md)). Two scope rules close gaps the evidence policy used to
open: a finding caused by a deleted line anchors on the nearest added line in the same hunk or file
and quotes the removed code (omitted when the file has no added line, unless it is a deleted test or
assertion), and a compatibility finding about a persisted, cached, queued, or cross-process shape
needs named storage or transport evidence instead of a located caller, because its consumer is the
previous deploy or already-written data. The critique has matching keep rules so it does not drop
either kind as misplaced or unsupported.

The non-recall review prompt has a third pass of built-in hygiene rules (sensitive or per-request
logging, failure logs that omit the failing identifier or drop the exception, history-narrating or
misplaced doc comments, doc comments that present a conditionally set field or behavior as always
present, comments or Markdown docs that record the PR's pending status instead of a
lasting contract, unreserved removed protobuf fields) with fixed type, category and severity.
The overstated-doc-comment rule tells the model to read the referenced member's own comment, even in
another changed file, because API-contract PRs often have no implementation code to check against.
Comment hygiene covers documentation files as well as code comments, because a README section that
says what is "not implemented as of <date>" or what a later change should add goes stale the same way.
Pass A also compares each new type against the existing sibling it mirrors, because a missing
annotation or serialization convention is invisible from the diff alone. It also flags new or
changed code that re-implements logic an existing base class or shared helper already provides,
naming the type to reuse. On the recall benchmark these were most of the misses, and the self-critique
pass is told they are not style findings so it keeps them. In recall mode (self-critique or a second
reviewer) the review prompt runs only passes A and B and tells the model to read every changed file
in full and look up each new or changed symbol's definition; the hygiene rules move to a dedicated
read-only hygiene pass (`ReviewHygienePrompts`) that runs after the review/supervisor
passes and before the critique. Bundling them into the main prompt let the semantic passes crowd
them out. Its findings are anchor-validated against the diff and merged into the draft, but the
critique's judgement does not remove them: after validation the pipeline restores every anchored,
non-low-confidence hygiene finding the critique dropped unless a kept finding of the same category
sits within two lines of it (`ReviewPipelineFindings.restoreHygieneFindings`). On the recall benchmark
the critic discarded confirmed hot-path and exception-logging findings in every run despite being
told to keep them, so the critique can only reword or deduplicate them. A failed hygiene pass
reports a status and the review continues without it. Deep reviews carry the pinned semantic
sections into the hygiene prompt too, because every deep-review provider call must see the same
trusted evidence. With the JVM property `prpilot.review.reportDropped=true`, the pipeline emits one
`Validation dropped finding at <file>:<line> — <body> — Reason: <reason>` status per draft finding
the critique removed; the recall benchmark sets it to tell a never-raised finding from one
validation discarded, and why. Only then does the critique prompt also ask for a top-level
`droppedFindings` array of `{file, line, reason}`, so production reviews keep the output contract
and token cost unchanged. The reason is the validator's own when it gave one, otherwise that the
comment stayed at low confidence (or was an unconfirmed candidate when validation failed), and
otherwise `validation gave no reason`. Parsing it never fails the review.
Both the merge instruction and the self-critique dedupe rule treat the same problem at separate code
sites as separate findings; without that, a repeated rule violation collapsed to one comment. The
output contract tells the model to anchor each comment on the exact offending statement rather than
the enclosing method or hunk start, because inline comments are matched and shown by line. Outcome
logging appends `-supervisor-on` or `-supervisor-off`, so supervised and baseline results are not
pooled.

### Prompt-injection hardening
When wrapping untrusted payloads in XML-like tags (including JSON-serialized sections such as
`<untrusted_semantic_evidence>`, `<inspection_manifest>`, `<coverage_state>`, and
`<follow_up_directives>`), escape closing tags case- and whitespace-insensitively and neutralize
forged reserved structural/trusted tags inside the payload to prevent tag breakout. Supervisor
`<follow_up_directives>` is rendered as an engine-owned section outside `<custom_instructions>`, and
chat `<turn>` tags are neutralized inside `<conversation_history>` turns without altering generic
`<Turn>` types in diffs. PR titles in `<pr_metadata>` are normalized to a single line and
null-guarded. Repository guidelines, PR metadata, descriptions, diffs, prior reviews, and chat
context remain untrusted reference data even when tag escaping is applied; the latest
`<user_message>` is the authorized request but cannot override persona or confidentiality
constraints. The chat builder retains only ten history turns and bounds each turn, the PR
description, review comments, diff, attachment, and current request before provider execution. Provider capability isolation is the primary
security boundary.

Review prompts permit only evidence supplied in the prompt and require a fixed JSON contract. Both
provider parsers reject unknown fields, incomplete line comments, invalid enums, oversized values,
low-confidence issues, low-confidence comments with no stated rationale, and verdict/comment
mismatches before review data reaches the UI. Verify-comment and example-fix prompts use tagged
reference data and strict JSON response shapes for the same reason.
