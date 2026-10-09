# Review pipeline

Key design decisions moved from [ARCHITECTURE.md](../../ARCHITECTURE.md). Each section encodes an active constraint future code must respect.

### Boundary compatibility findings are evidence-gated
The engine-owned review prompt treats service/module boundary compatibility as a first-class category, but only for located callers or consumers that still use an old contract, schema, field, config key, exported type, or behavior. Review providers must search the local worktree first and may use a read-only cross-repo MCP search only when the active provider/session exposes one. If all located callers are updated, or no caller can be found through available search, the finding is dropped rather than reported speculatively. `compatibility` is therefore part of the engine category set and mirrored by host/webview validators only so engine output can round-trip safely.

### Repository guidance confinement

`RepoGuidelinesReader` treats configured paths and globs as untrusted input. It resolves from the repository's real root, rejects absolute and escaping paths, never follows symbolic-link path segments, and accepts only physically contained regular files. Its prompt cap is measured in UTF-8 bytes and truncation stops at a Unicode code-point boundary; host code must not replace these checks with `File.isFile`, string-prefix containment, or UTF-16 `String.length()` accounting. Guidance from a PR-head worktree is also author-controlled, so hosts never read it and always send `repoGuidelines` blank.

### Review guidance, file history and call sites come from the base commit
Hosts pass the GitHub-reported `baseSha` (`PrDetail.baseSha`, validated as a full hex object ID) on `PRReviewRequest`. Before the first provider call, `ReviewPipelineService` asks `BaseCommitContext` to resolve trusted guidance (files matching the configured guidance globs first, then root and changed-directory-scoped `AGENTS.md`/`CLAUDE.md` and contributing/PR-template files, capped at 48 KB) the recent commit history of each changed file, and textual call sites of the declarations the diff changes, reading only base-commit git objects through read-only plumbing (`cat-file`, `ls-tree`, `log`, `grep <sha>`) with per-command and overall time bounds. A missing base commit is fetched once from `origin` by SHA. Guidance is never read from the worktree or the head tree, so a PR cannot inject policy by editing guidance files — those edits remain review evidence only. Any failure (not a git work tree, malformed SHA, unreachable commit, timeout) degrades to an unenriched review; only cancellation propagates. Call sites are whole-word name matches (`ChangedSymbols` picks declarations from removed lines and hunk-header context, skipping test files) in files the PR does not change; they can include unrelated same-name symbols, so the prompt labels them as candidates to confirm, and they run last on their own sub-budget so a large repository cannot starve guidance or history. Configured guidance globs (`PRReviewRequest.guidanceGlobs`, from the active profile's or default `reviewGuidanceGlobs`) are honored and additive to the built-in defaults: each becomes a literal pathspec prefix, so `..`, absolute, and wildcard-leading globs read nothing, and matches are read only from the base commit like every other guidance file. No repository-specific path is built in; a repo whose review rules live elsewhere (for example `.linkedin/ai-agent/*.md`) needs that glob configured. Resolved base-commit guidance replaces host-supplied `repoGuidelines`; the host value (always blank today) is kept only when the base commit yields none.

### Recall: second reviewer and candidate validation
Recall is traded for precision in one place only: the final critique. When self-critique is on or a second reviewer is configured, the review prompt allows a bounded number of low-confidence `candidate` findings that the critique must confirm or drop; unconfirmed candidates never reach the user, even when critique itself fails. An optional second reviewer (`reviewSecondReviewerModel`, always a Copilot model) runs the same request in parallel with the primary; its findings are merged and deduplicated with the primary's before critique, it can never fail the review (its errors and timeouts are logged and ignored), and cancelling the primary cancels it. Its progress (started, still running, finished/failed) is reported only from the primary's thread, because host status sinks such as the sidecar's stdout are not thread-safe. The final comment count is capped after merging. The outcome log does not yet distinguish reviews that used a second reviewer.

### Recall benchmark withholds the reference answers
`review-benchmark` is a developer tool, not an engine capability, so it has no RPC, sidecar, or host wiring. It reviews the exact commit Mae first commented on, rendering the diff locally from `base...commit` rather than fetching GitHub's current PR diff, and it passes blank existing reviews, prior review, and commit messages so Mae's comments (or replies to them) cannot leak into the prompt. Matching requires the same file and a nearby line before the judge may pair two findings, so the judge can only reject location matches, never invent distant ones.

### ReviewBench runs never consult GitHub and delegate scoring
`ReviewBenchRunner` builds the review from the corpus entry only: its title, body, a diff rendered locally from `base...head`, and a repository profile read from the worktree. Existing reviews, CI, commits, and linked issues stay blank because the corpus PRs are public and their review threads could leak golden findings. `note` comments are dropped unless `--include-notes`, since the judge scores every submitted finding as a true or false positive. Scoring is never reimplemented here: `scripts/reviewbench.mjs` runs ReviewBench's own judge at a pinned commit, once per round, because the judge merges every findings file that names the same pull request. Copilot judge models need `compat.supportsDeveloperRole: false` in the generated pi `models.json`; otherwise pi sends the judge's instructions as a `developer` message that Copilot's Claude endpoint ignores, and the matcher returns prose instead of JSON. Per-review diagnostics (critique-dropped findings and stage lines) go to `<run>/diagnostics/`, never `<run>/findings/`, because the judge reads every file in the findings directory and would score dropped findings as submitted ones.

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

### Comments on deleted files orphan by design
A pure-deletion file has only `delete` changes, which carry no new-file line number, so it yields an empty line set and every comment on it becomes an orphan — rendered in the review body's "Comments not attached inline" section rather than posted inline. Anchoring these inline would require a `side: LEFT` field threaded through `bridge/types.ts` and both hosts' handlers, and the review prompt already instructs the model to comment only on changed (`+`) lines, so such a comment is a prompt violation to begin with. The comment still reaches the reviewer; only its placement degrades. Pinned by `validateComments.test.ts`.

### CI annotations are selected by whether they exist, not by whether the check failed
`CheckRunService` probes a check's annotations when it failed **or** when its `output.annotations_count` is non-zero. Static-analysis checks (Qodana, CodeQL, ktlint) are routinely configured as advisory and conclude `success` or `neutral` while still reporting file-anchored findings — the most review-comment-shaped evidence CI produces. Keying on the conclusion alone silently discarded them exactly when the build was green. `annotations_count` arrives in the check-runs list response that has already been fetched, so testing it costs no extra request. The condition is a union rather than a replacement so a provider that omits the field retains the failing-check behavior. Failing checks are still requested first, so advisory lint notes cannot crowd a broken build out of the bounded annotation budget.

### CI-duplicate suppression is conservative by design
`CiFindingSuppressor` drops a generated comment only when a CI annotation matches **both** its location (±2 lines — CI often anchors at a declaration while the comment lands on the statement below) **and** ≥60% of the annotation's distinctive words. The two error directions are not symmetric: a surviving duplicate costs one line of reading, while a wrongly-suppressed finding is *invisible* — the reviewer has no way to know it existed. An annotation with no distinctive words after short-token filtering (e.g. "Process completed with exit code 1") suppresses nothing, because it would otherwise match every comment on its line. This runs at both providers' `reviewPR` seams so Claude and Copilot behave identically, and it is the deterministic counterpart to the Phase 2 critique directive, which only *asks* the model to drop such findings.

### Existing review comments carry thread state
`PrSupplementalService.existingReviews` tags each inline comment in the `<existing_reviews>` summary `[resolved]`, `[outdated]`, or `[resolved, outdated]`; open comments stay untagged. Outdated state comes from REST alone: a comment with a null `line` and a positive `original_line` renders that original line with `[outdated]`, so the tag survives any GraphQL failure. Resolved state needs one GraphQL `reviewThreads` query (≤5 pages of 100) that returns only each thread's root comment `fullDatabaseId`. The 32-bit `databaseId` overflows on current comment IDs, so the BigInt string is compared to the REST `id` as a `long`, and replies inherit their root's state through REST `in_reply_to_id`. Each POST is a single attempt with a 3-second timeout, so a slow GraphQL endpoint cannot push `prs/getExistingReviews` past VS Code's 60-second request bound. The call is skipped when there are no inline comments. Any failure, including a sixth page, is all-or-nothing: pages already read are discarded, no `[resolved]` tag appears, and `(Thread resolution state was unavailable.)` is inserted at the top so context truncation never removes it. Handling is prompt-only: the review preface and the critique directive tell the model how to treat each tag, and nothing deterministically suppresses generated comments against existing threads.

### Incremental review since the last submitted review
"Review changes since last review" sends `generateReview` with `incremental: true`. The host asks the `prs/getIncrementalDiff` capability (`IncrementalDiffService`) for the diff of commits pushed since the viewer's latest submitted review. The baseline is the same commit the list's `UPDATED_SINCE_REVIEW` badge compares against (`PrReviewStatusService.reviewBaseline`), and it must appear in the PR's current commit history (up to 3 backward pages of 100). The engine then calls GitHub's `compare/{baseline}...{head}` and bounds the result with the same whole-file review budget as `prs/getDiff`.

Anything that makes the incremental diff untrustworthy falls back to a full review with a `fallbackReason` instead of failing:
- `no_prior_review` and `up_to_date`.
- `baseline_not_in_history`: a force-push or rebase dropped the reviewed commit.
- `baseline_unavailable`: compare returned 404 or 422.
- `empty_incremental_diff`.
- `incremental_diff_too_large`: compare returned 406.

Real GitHub failures (auth, rate limit, network, other API errors) return a non-`ok` status with no scope. The host reports these as `reviewError`, just as it reports a failed PR diff fetch.

The incremental diff feeds only the model. The host still loads the PR review diff and validation diff, and `activeDiff`, the published `reviewResult` `diff` and `validationDiff` are always PR diffs, so anchors validate against the whole PR and the diff view is unchanged. When the scope is incremental, `PRReviewRequest.incrementalBaselineSha` adds an engine-authored `<review_scope>` section to the prompt. It says earlier changes were already reviewed and findings must anchor to this diff. Chunked mode is ignored for an incremental review. Deep (IntelliJ-assisted) review never asks for the incremental diff. `reviewResult` carries `reviewScope` only when incremental was requested, and the webview shows it as a banner.

### Review JSON parsing is self-healing, not all-or-nothing
`ReviewResultParser.parseReview` (in `review-engine`, shared by both the Claude CLI and Copilot SDK paths on both hosts — IntelliJ calls it in-process, VS Code via the sidecar's `reviews/generate`) tolerates and repairs common model schema deviations instead of rejecting the entire review, which previously surfaced as "The model returned an invalid review format" for otherwise-good output. Unknown top-level/comment fields are ignored; an over-long `summary` is truncated to 800 chars; a comment `body` with embedded newlines is collapsed to one line but otherwise preserved in full; a low-confidence `"issue"` is downgraded to `"suggestion"` rather than failing the review; the final `verdict` is derived from (and corrected to match) the surviving comments rather than trusting the model's stated verdict. Only individually-malformed line comments (missing/blank required field, invalid enum value) are dropped — one bad comment no longer discards the other 19. A hard parse failure remains only for genuinely non-JSON output or a missing `summary`/non-object root, since there is nothing to salvage in that case.

### Low confidence is a gate, not a label
Three parts of the pipeline previously treated `"confidence": "low"` as a way to *keep* an unconfirmed finding rather than a reason to discard it, and together they manufactured low-confidence comments:

- The prompt offered a downgrade as a peer of omission ("omit it **or** use a note with confidence low", and "drop to confidence low" for an inconclusive blast-radius search). Given that choice a model emits the comment, since something compliant beats nothing. Both now direct omission, and the prompt states that returning few comments — or none — is a correct outcome for a clean change.
- `repairLineComment` **downgraded** a low-confidence `"issue"` to `"suggestion"`. That converted the model's violation of "never report a low-confidence issue" into an accepted comment, so breaking the rule cost nothing. It is now dropped, and the prompt says so explicitly — a rule enforced only by invisible relabeling cannot change behavior.
- A low-confidence comment of any type must now carry a `rationale`. Previously the parser exempted `"note"`, and `reviewQuality.ts` exempts low-confidence comments from its own rationale check, so a bare low-confidence `"note"` was the cheapest comment the model could emit and nothing downstream removed it.

The self-critique directive is keyed on `confidence`, not on type, for a related reason: its input is `draftReviewJson` over an already-parsed draft, so by then no low-confidence `"issue"` exists and the old "drop a low-confidence issue" rule could never match anything. It now requires each surviving low-confidence comment to be confirmed and raised, or dropped.

`PROMPT_VERSION` is `2026-10-thread-state-incremental-scope`. Pass B appends `BUG_HUNT_CHECKLIST` (failure
disposition, swallowed failures, removed safeguards, unchecked absent (protobuf default) inputs, mixed versions, and the other defect classes a
generic "look for bugs" instruction skips) plus `LanguageChecklists` entries for only the languages
the diff changes. Two scope rules close gaps the evidence policy used to open: a finding caused by a
deleted line anchors on the nearest added line in the same hunk or file and quotes the removed code
(omitted when the file has no added line), and a compatibility finding about a persisted, cached,
queued, or cross-process shape needs named storage or transport evidence instead of a located
caller, because its consumer is the previous deploy or already-written data. The critique has
matching keep rules so it does not drop either kind as misplaced or unsupported.

The non-recall review prompt has a third pass of built-in
hygiene rules (sensitive or per-request logging, failure logs that omit the failing identifier or
drop the exception, history-narrating or misplaced doc comments, unreserved removed protobuf fields) with fixed
type, category and severity. Pass A also compares each new type against the existing sibling it
mirrors, because a missing annotation or serialization convention is invisible from the diff alone. It
also flags new or changed code that re-implements logic an existing base class or shared helper
already provides, naming the type to reuse.
On the Mae benchmark these were most of the misses, and the self-critique pass is told they are not style findings so it keeps them.
In recall mode (self-critique or a second reviewer) the review prompt runs only passes A and B and
tells the model to read every changed file in full and look up each new or changed symbol's
definition; the hygiene rules move to a dedicated read-only hygiene pass
(`ReviewPrompts.buildHygienePrompt`) that runs after the review/supervisor passes and before the
critique. Bundling them into the main prompt let the semantic passes crowd them out. Its findings are
anchor-validated against the diff and merged into the draft, but the critique's judgement does not
remove them: after validation the pipeline restores every anchored, non-low-confidence hygiene finding
the critique dropped unless a kept finding of the same category sits within two lines of it
(`ReviewPipelineService.restoreHygieneFindings`). On the Mae benchmark the critic discarded
confirmed hot-path and exception-logging findings in every run despite being told to keep them, so
the critique can only reword or deduplicate them. A failed hygiene pass reports a
status and the review continues without it. Deep reviews carry the pinned semantic sections into the
hygiene prompt too, because every deep-review provider call must see the same trusted evidence. With
the JVM property `prpilot.review.reportDropped=true`, the pipeline emits one `Validation dropped
finding at <file>:<line> — <body>` status per draft finding the critique removed; the recall
benchmark sets it to tell a never-raised finding from one validation discarded.
Both the merge instruction and the self-critique dedupe rule treat the same problem at separate code
sites as separate findings; without that, a repeated rule violation collapsed to one comment.
The output contract tells the model to anchor each comment on the exact offending statement rather
than the enclosing method or hunk start, because inline comments are matched and shown by line.
Outcome logging appends
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
targeted hunk follow-up and whole-file re-reviews -> final self-critique -> deterministic CI suppression`.

The persisted `reviewSupervisorEnabled` setting defaults to `true`. The base review emits an
engine-internal inspection ledger in both modes so the output contract stays stable; when the
setting is off, the engine does not analyze that ledger, make supervisor/follow-up calls, or filter
anchors. When enabled:

- `InspectionManifest` assigns stable IDs to changed files and hunks and records changed new-side
  lines. `ReviewPassParser` accepts only manifest IDs and repository-confined evidence paths.
- `ReviewCoverageAnalyzer` deterministically identifies uninspected high-risk hunks, plus changed
  files whose file ID and hunk IDs are all absent from the ledger (ranked below high-risk hunks). It
  does not infer gaps when the provider omitted its ledger. Hunk gaps are capped at 12; file gaps
  are not, because a file the reviewer never opened is the likeliest place for a missed finding.
- Every uncovered file is re-reviewed in full, six files per follow-up and at most five such
  follow-ups. Hunk gaps inside those files are dropped as redundant.
- Three or fewer remaining hunk gaps are prioritized deterministically. Larger sets get one
  tool-free, 90-second provider call that sees only gap metadata and baseline finding locations and
  may select at most five supplied IDs; if it fails, hunk follow-up is skipped but file re-reviews
  still run.
- The engine authors the follow-up objectives and allows six-minute, read-only worktree passes with
  MCP disabled. A failed batch is logged and skipped without discarding other batches. Baseline and
  follow-up findings are merged and deduplicated. The supervisor always reports a status naming
  how many files and hunks it re-reviewed, the findings found, and any failed calls (or that no
  follow-up was needed); each mentions "finding" so the recall benchmark records it as a stage.
- `ReviewAnchorValidator` removes findings not attached to changed new-side lines before the
  existing final critique and CI suppression gates run once. Chunked reviews use their bounded
  contract index for the final critique rather than re-sending the full diff.

The primary pass remains terminal on failure. Supervisor selection, follow-up, and final critique
I/O or parse failures keep the best available review; interruption is always propagated. Aggregate
logs contain counts and elapsed time only, never paths, snippets, prompts, directives, or tool
arguments. User-visible activity reports generic stages (`Checking review coverage...`,
`Prioritizing missed areas...`, and `Inspecting missed areas...`) rather than model reasoning.

### Comment anchoring policy
Client-side validation partitions comments: keep in-hunk, snap within +-3 lines, orphan otherwise. Orphans are excluded from inline POST and appended to review body section.

### Hygiene pass gets a mechanical log inventory

`ReviewPipelineService.hygieneFindings` passes `ChangedLogStatements.extract(manifest)` into the
hygiene prompt as `<changed_log_statements>`, and the prompt treats that list as the complete log
inventory to judge entry by entry. Asking the model to build the inventory itself proved erratic: on
the same PR, repeated runs reported between 0 and 4 log findings. It also missed multi-line calls
whose opening `logger.info(` line was unchanged context but whose arguments changed. Rules to keep:

- Build the inventory from the manifest of the **full** diff. In chunked mode the hygiene request
  carries only the condensed final-validation index, so a diff-derived inventory would be empty.
- A statement counts as changed if any of its lines is added. Anchor it on its first added line so
  the finding lands on a commentable line.
- Keep the list bounded (`MAX_STATEMENTS`, `MAX_STATEMENT_LINES`, `MAX_TEXT_CHARS`). When it is
  capped, the prompt tells the model to inventory the remainder itself.
- The inventory is untrusted PR content and goes through `escapeClosingTag` like every other section.

