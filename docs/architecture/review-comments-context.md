# Review comments and context

Key design decisions moved from [ARCHITECTURE.md](../../ARCHITECTURE.md). Each section encodes an
active constraint future code must respect.

### Comment anchoring snaps within a hunk, never across one
`validateComments` (`webview/src/lib/validateComments.ts`) indexes valid new-file lines **per
hunk**, not as one flat set per file. A comment whose line is already in some hunk passes through.
Otherwise it snaps only when exactly one hunk has a line within `SNAP_RADIUS` (3); if two hunks
qualify, the target sits in the gap between them and belongs to neither, so it is orphaned instead
of attached to whichever is marginally nearer. A flat per-file set silently allowed a comment to
snap from the end of one hunk into the start of the next — the exact misattribution the prompt's "a
misattributed comment is worse than no comment" rule exists to prevent. Orphans are appended to the
review body rather than posted inline.

### Three comment keys exist on purpose
They look like drift and are not; unifying them is a regression.

| Key | Where | Shape | Why |
|---|---|---|---|
| Chunk merge | `ReviewPane.mergeChunkResults` | `file\|line\|type\|body` | Dedupes model **findings** across batches; `type` is part of a finding's identity |
| Orphan match | `DraftReviewCodec.orphanKey` | `file\|line\|type\|body` | Identity check between two lists holding the same objects |
| POST dedupe | `DraftReviewCodec` `dedupeKey` | `normalizedFile\0line\0body` | Collapses comments that would post **identically**. The GitHub payload is only `path`/`line`/`side`/`body`, so including `type` would post two byte-identical comments; the path is normalized because `b/Foo.java` and `Foo.java` target the same place |

### Missing rationale drops a comment, never fabricates one
`applyReviewQualityRepairs`' `dropMissingRationale` removes comments whose evidence the model
declined to state. An earlier `addMissingRationale` filled the gap with `Evidence needs verification
in <file>:<line>.`, which cleared the trust warning and raised the reported quality score while
adding no evidence — strictly worse than leaving the comment flagged.

### Comments on deleted files orphan by design
A pure-deletion file has only `delete` changes, which carry no new-file line number, so it yields an
empty line set and every comment on it becomes an orphan — rendered in the review body's "Comments
not attached inline" section rather than posted inline. Anchoring these inline would require a
`side: LEFT` field threaded through `bridge/types.ts` and both hosts' handlers, and the review
prompt already instructs the model to comment only on changed (`+`) lines, so such a comment is a
prompt violation to begin with. The comment still reaches the reviewer; only its placement degrades.
Pinned by `validateComments.test.ts`.

### CI annotations are selected by whether they exist, not by whether the check failed
`CheckRunService` probes a check's annotations when it failed **or** when its
`output.annotations_count` is non-zero. Static-analysis checks (Qodana, CodeQL, ktlint) are
routinely configured as advisory and conclude `success` or `neutral` while still reporting
file-anchored findings — the most review-comment-shaped evidence CI produces. Keying on the
conclusion alone silently discarded them exactly when the build was green. `annotations_count`
arrives in the check-runs list response that has already been fetched, so testing it costs no extra
request. The condition is a union rather than a replacement so a provider that omits the field
retains the failing-check behavior. Failing checks are still requested first, so advisory lint notes
cannot crowd a broken build out of the bounded annotation budget.

### CI-duplicate suppression is conservative by design
`CiFindingSuppressor` drops a generated comment only when a CI annotation matches **both** its
location (±2 lines — CI often anchors at a declaration while the comment lands on the statement
below) **and** ≥60% of the annotation's distinctive words. The two error directions are not
symmetric: a surviving duplicate costs one line of reading, while a wrongly-suppressed finding is
*invisible* — the reviewer has no way to know it existed. An annotation with no distinctive words
after short-token filtering (e.g. "Process completed with exit code 1") suppresses nothing,
because it would otherwise match every comment on its line. This runs at both providers'
`reviewPR` seams so
Claude and Copilot behave identically, and it is the deterministic counterpart to the Phase 2
critique directive, which only *asks* the model to drop such findings.

### Existing review comments carry thread state
`PrSupplementalService.existingReviews` tags each inline comment in the `<existing_reviews>` summary
`[resolved]`, `[outdated]`, or `[resolved, outdated]`; open comments stay untagged. Outdated state
comes from REST alone: a comment with a null `line` and a positive `original_line` renders that
original line with `[outdated]`, so the tag survives any GraphQL failure. Resolved state needs one
GraphQL `reviewThreads` query (≤5 pages of 100) that returns only each thread's root comment
`fullDatabaseId`. The 32-bit `databaseId` overflows on current comment IDs, so the BigInt string is
compared to the REST `id` as a `long`, and replies inherit their root's state through REST
`in_reply_to_id`. Each POST is a single attempt with a 3-second timeout, so a slow GraphQL endpoint
cannot push `prs/getExistingReviews` past VS Code's 60-second request bound. The call is skipped
when there are no inline comments. Any failure, including a sixth page, is all-or-nothing: pages
already read are discarded, no `[resolved]` tag appears, and `(Thread resolution state was
unavailable.)` is inserted at the top so context truncation never removes it. Handling is
prompt-only: the review preface and the critique directive tell the model how to treat each tag, and
nothing deterministically suppresses generated comments against existing threads.

### Incremental review since the last submitted review
"Review changes since last review" sends `generateReview` with `incremental: true`. The host asks
the `prs/getIncrementalDiff` capability (`IncrementalDiffService`) for the diff of commits pushed
since the viewer's latest submitted review. The baseline is the same commit the list's
`UPDATED_SINCE_REVIEW` badge compares against (`PrReviewStatusService.reviewBaseline`), and it must
appear in the PR's current commit history (up to 3 backward pages of 100). The engine then calls
GitHub's `compare/{baseline}...{head}` and bounds the result with the same whole-file review budget
as `prs/getDiff`.

Anything that makes the incremental diff untrustworthy falls back to a full review with a
`fallbackReason` instead of failing:
- `no_prior_review` and `up_to_date`.
- `baseline_not_in_history`: a force-push or rebase dropped the reviewed commit.
- `baseline_unavailable`: compare returned 404 or 422.
- `empty_incremental_diff`.
- `incremental_diff_too_large`: compare returned 406.

Real GitHub failures (auth, rate limit, network, other API errors) return a non-`ok` status with no
scope. The host reports these as `reviewError`, just as it reports a failed PR diff fetch.

The incremental diff feeds only the model. The host still loads the PR review diff and validation
diff, and `activeDiff`, the published `reviewResult` `diff` and `validationDiff` are always PR
diffs, so anchors validate against the whole PR and the diff view is unchanged. When the scope is
incremental, `PRReviewRequest.incrementalBaselineSha` adds an engine-authored `<review_scope>`
section to the prompt. It says earlier changes were already reviewed and findings must anchor to
this diff. Chunked mode is ignored for an incremental review. Deep (IntelliJ-assisted) review never
asks for the incremental diff. `reviewResult` carries `reviewScope` only when incremental was
requested, and the webview shows it as a banner.

### Comment anchoring policy
Client-side validation partitions comments: keep in-hunk, snap within +-3 lines, orphan otherwise.
Orphans are excluded from inline POST and appended to review body section.
