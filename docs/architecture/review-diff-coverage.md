# Review diff coverage and quality

Key design decisions moved from [ARCHITECTURE.md](../../ARCHITECTURE.md). Each section encodes an
active constraint future code must respect.

### Diff acquisition model
Hosts fetch and bound the GitHub diff before provider execution and embed it in `<pr_diff>`. Review
providers run with read-only tools scoped to the PR-branch worktree, so they *can* open files there
to confirm a finding or resolve a symbol the diff omits — but they cannot reach the network or any
path outside it, so the embedded diff remains the primary evidence. Single-pass review sends the 250
KB review diff. The separate 1 MB validation diff is retained host-side/webview-side for comment
anchoring and is also the provider input for chunked review and IntelliJ-assisted deep review; it is
not part of a single-pass prompt. When either diff omits files, `PRReviewRequest` strips its trailer
into `DiffCoverage` so `<pr_diff>` never contains it, and review and critique prompts gain an
escaped `<omitted_files>` section (see "Complete review diff coverage"). In VS Code, that validation
diff is capped at 1 MB to match the webview bridge validator; a larger payload is rejected before
the review pane receives `draftLoaded` or `reviewResult`. It is fetched only after the draft-status
response so a slow large-diff download cannot leave the review pane stuck in draft loading.

### Complete review diff coverage
Single-pass review keeps its 250,000-byte diff budget, and the 1,000,000-byte validation budget is
unchanged. What changed is how a diff over budget is bounded and disclosed. A byte-prefix cut could
split a hunk or a UTF-8 sequence and silently dropped the files after it. Raising the limit would
still be a silent cut, and would grow every single-pass prompt, the diff view, and the bridge
payloads. Large PRs instead get explicit coverage disclosure plus the existing opt-in chunked path
over the larger validation diff.

`PrDiffService.bound` streams the GitHub diff and splits it into per-file sections at `diff --git `
boundaries:
- In every mode, a section over 250,000 bytes is dropped, so it can never be anchored or partially
  reviewed.
- While the kept total exceeds the budget, the largest kept section is evicted, leaving the maximal
  smallest-first whole-file subset (ties keep original order). Smallest-first, unlike first-fit, is
  monotonic in the budget, so the review diff's kept files are always a subset of the validation
  diff's. That is what makes a chunked-review coverage-gain claim true.
- Reading stops at a 64 MiB scan ceiling, which marks the scan incomplete (`scan=incomplete`). When
  anything was omitted, the kept set is re-fitted to the budget minus a 16,384-byte trailer reserve,
  so body plus trailer never exceed the budget.
- Kept sections are emitted in original order, followed by the trailer.

The trailer (`DiffCoverage` in `core`, mirrored by `webview/src/lib/diffCoverage.ts` and pinned by
the shared golden fixture `core/src/test/resources/diff-coverage/trailer.golden.txt`) is one
`[pr-pilot:diff-coverage] omitted=… listed=… budget=… scan=…` line plus up to 200
`[pr-pilot:omitted] <path>` lines. Both parsers are strict and end-anchored and accept only a
trailer the engine could have written; anything else is ordinary diff text with complete coverage.
The trailer rides in-band so RPC, host, and bridge shapes stay unchanged. `PRReviewRequest` strips
it into `diffCoverage`, and every request copy (semantic context, chunk batches, reconciliation,
supervisor follow-up) preserves it. Review and critique prompts for both providers then add an
escaped `<omitted_files>` section, listed as untrusted reference data. Its trusted preface states
the count and budget, says those files were not reviewed, and forbids comments on them. It also
requires the summary to state the unreviewed count; the posted review body is not edited
deterministically. Comments the model still places on omitted files become orphans through the
normal validation path.

GitHub answers HTTP 406 when a diff exceeds its own size limits. The engine attempts that request
once and returns `diff_too_large`, which both hosts render as non-retryable "Pull request diff is
too large" copy rather than a generic retry. There is no files-API or local `git diff` fallback.
Chat keeps its 12,000-character excerpt, and the display keeps the 250 KB review diff; the banner
and chat label describe those limits instead of claiming a full diff.

### Large diff visibility
The review diff is bounded to 250 KB by whole files (see "Complete review diff coverage"). When its
`DiffCoverage` trailer is present, the webview shows a coverage banner above the review and diff
view, and in the no-draft state before generation. The banner states how many changed files were
left out (or "at least" that many when the scan was incomplete) and the budget. It says the omitted
files won't appear in the diff, the generated review, or chat. When chunked review would include
some of them, it offers one action that turns chunked mode on for the next generation without
starting a review; otherwise it suggests splitting the pull request. It lists the reported paths
plus "+K more not listed". `DiffViewer` receives the diff body without the trailer and still lazily
limits rendered changed lines for browser performance.

The shared diff viewer also owns file-level navigation: it keeps a sticky “currently viewing” file
indicator visible while the review body scrolls and renders a GitHub-style changed-files tree for
jumping between files. If a reviewer jumps to a file outside the initial 500 changed-line preview,
`DiffViewer` expands the full diff before scrolling so navigation and comment focus never strand
hidden files.

### Review quality gate and chunked review mode
The webview runs a `Review Quality Check` pass automatically over the current draft and validation
diff to flag trust risks (unanchored comments, low-evidence high-severity findings, and missing
rationale metadata). When risks are present it surfaces a non-blocking badge (`N trust risks
detected — Review`) that expands to a panel with one-click in-memory repairs (`remove unanchored`,
`add rationale placeholders`, `downgrade high-risk issues`); a clean draft shows no nag. The check
remains non-blocking, but the submit dialog now requires an explicit reviewer acknowledgement
checkbox whenever unresolved trust risks remain.

For larger PRs, reviewers can explicitly enable chunked mode in per-review overrides. The webview
sends the loaded validation diff (1 MB whole-file budget, or the review diff while the validation
diff has not loaded) once with `chunkedReview: true`; `ChunkedReviewService` in the shared review
engine owns file batching, invokes the selected provider for every bounded batch, then performs a
mandatory final provider reconciliation over every batch result plus a bounded changed-file/contract
index. Every batch and reconciliation request copies the source `DiffCoverage`, so each prompt names
the files that even the validation diff omitted. Both IDE hosts therefore use the same
global-synthesis behavior. A reconciliation I/O failure returns complete, visibly marked degraded
batch summaries rather than silently presenting a locally sliced merge as globally synthesized
output. The recommendation is honest about coverage: it cites "Chunked review includes N changed
file(s) that single-pass review omits." only when the validation diff omits fewer files than the
review diff, and says "Chunked review would not add coverage." when the review diff is incomplete
but chunking gains nothing. The unchanged file-count and changed-line size heuristics can still
recommend chunking. No recommendation ever enables chunked mode without the reviewer selecting the
option.

### Bounded review supervision

`ReviewPipelineService` owns the review pipeline for both hosts:

`primary/chunk batches -> reconciliation -> coverage analysis -> optional prioritization -> targeted
hunk follow-up and whole-file re-reviews -> final self-critique -> deterministic CI suppression`.

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
  follow-up findings are merged and deduplicated. The supervisor always reports a status naming how
  many files and hunks it re-reviewed, the findings found, and any failed calls (or that no
  follow-up was needed); each mentions "finding" so the recall benchmark records it as a stage.
- `ReviewAnchorValidator` removes findings not attached to changed new-side lines before the
  existing final critique and CI suppression gates run once. Chunked reviews use their bounded
  contract index for the final critique rather than re-sending the full diff.

The primary pass remains terminal on failure. Supervisor selection, follow-up, and final critique
I/O or parse failures keep the best available review; interruption is always propagated. Aggregate
logs contain counts and elapsed time only, never paths, snippets, prompts, directives, or tool
arguments. User-visible activity reports generic stages (`Checking review coverage...`,
`Prioritizing missed areas...`, and `Inspecting missed areas...`) rather than model reasoning.

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
- The inventory is untrusted PR content and goes through `escapeClosingTag` like every other
  section.
