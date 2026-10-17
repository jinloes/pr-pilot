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
to resolve trusted guidance (files matching the configured guidance globs first, then root and
changed-directory-scoped `AGENTS.md`/`CLAUDE.md` and contributing/PR-template files, capped at 48
KB) the recent commit history of each changed file, and textual call sites of the declarations the
diff changes, reading only base-commit git objects through read-only plumbing (`cat-file`,
`ls-tree`, `log`, `grep <sha>`) with per-command and overall time bounds. A missing base commit is
fetched once from `origin` by SHA. Guidance is never read from the worktree or the head tree, so a
PR cannot inject policy by editing guidance files — those edits remain review evidence only. Any
failure (not a git work tree, malformed SHA, unreachable commit, timeout) degrades to an unenriched
review; only cancellation propagates. Call sites are whole-word name matches (`ChangedSymbols` picks
declarations from removed lines and hunk-header context, skipping test files) in files the PR does
not change; they can include unrelated same-name symbols, so the prompt labels them as candidates to
confirm, and they run last on their own sub-budget so a large repository cannot starve guidance or
history. Configured guidance globs (`PRReviewRequest.guidanceGlobs`, from the active profile's or
default `reviewGuidanceGlobs`) are honored and additive to the built-in defaults: each becomes a
literal pathspec prefix, so `..`, absolute, and wildcard-leading globs read nothing, and matches are
read only from the base commit like every other guidance file. No repository-specific path is built
in; a repo whose review rules live elsewhere (for example `.linkedin/ai-agent/*.md`) needs that glob
configured. Resolved base-commit guidance replaces host-supplied `repoGuidelines`; the host value
(always blank today) is kept only when the base commit yields none.

### Recall: second reviewer and candidate validation
Recall is traded for precision in one place only: the final critique. When self-critique is on or a
second reviewer is configured, the review prompt allows a bounded number of low-confidence
`candidate` findings that the critique must confirm or drop; unconfirmed candidates never reach the
user, even when critique itself fails. An optional second reviewer (`reviewSecondReviewerModel`,
always a Copilot model) runs the same request in parallel with the primary; its findings are merged
and deduplicated with the primary's before critique, it can never fail the review (its errors and
timeouts are logged and ignored), and cancelling the primary cancels it. Its progress (started,
still running, finished/failed) is reported only from the primary's thread, because host status
sinks such as the sidecar's stdout are not thread-safe. The final comment count is capped after
merging. The outcome log does not yet distinguish reviews that used a second reviewer.
Near-duplicate collapse and reviewer attribution are described in the next section.

### Reviewer attribution and corroboration
Before the second-reviewer merge, `ReviewerAttribution` collapses near-duplicates: each
second-reviewer comment pairs with at most one unconsumed primary comment in the same file with the
same non-blank category within two lines (`HYGIENE_COVER_LINES`; nearest line first, then list
order). The higher-priority comment is kept (ties keep the primary) and its `sources` become
`[primary, second]`; other comments get their own reviewer's label. Labels come from each provider's
display model, and an equal second label becomes `<label> (second)`. In the critique draft, a
comment with two or more sources is marked `"corroborated": true` (sources themselves are never
sent), and only then does the critique prompt add one sentence calling that supporting evidence that
still loses to a contradicting diff. Critique rewrites comments, so attribution is re-attached after
hygiene restoration and before final anchor validation, on both the critique-success and
critique-failure paths: each comment takes the union, primary first, of the sources of the
pre-critique candidates it matches by the collapse rule (captured as immutable evidence before
critique, so newly assigned sources never feed later matches), and a comment with no match
(critique-added, hygiene, or supervisor follow-up) gets the primary label. Without a successful
second reviewer, and always in deep review, every comment has empty `sources` and the prompts are
unchanged. Sources are display-only: the parser ignores them in model output, the sidecar accepts
and drops them when saving a draft, and VS Code draft recovery omits them.

### Review rules are trigger-gated and applied one agent per rule
`reviewRulesDirectory` rules never enter `repo_guidelines`. `LocalReviewRules.load` reads up to 50
`.md`/`.yaml`/`.yml` files (path order, depth 4, no links inside the folder, 32 KB per file, no
total cap). A YAML mapping with a valid `name` and non-blank `description`, `trigger` and `prompt`
is a structured rule (`enabled: false` skips it; `severity`, `owner` and `model` are ignored; a
duplicate name is skipped). Every other readable file is an unstructured rule named by its path
whose prompt is its content.

Built-in rules are structured YAML files in review-engine's `review-rules/` resources, listed in
`LocalReviewRules.BUILT_IN_RULE_FILES` (a test fails when the list and the folder disagree, since a
jar cannot be listed reliably). With `builtInRules` on (the default; a null wire value means on),
`load(dir, true)` puts them first and the folder's rules after. A built-in is dropped when a folder
rule declares the same name, either as a valid structured rule (which replaces it) or with
`enabled: false` (which turns it off). Unstructured `.md` rules are named by path, so they never
override one. Built-ins do not count toward the 50-file folder cap, but they do count toward the
25-rule agent cap. Their sources start with `built-in/`.

`ReviewRulesPass` runs after the candidate is built and after the attribution snapshot, and before
the hygiene pass. When structured rules exist, one tool-free selection call (90 s) sees the PR
metadata, description, changed paths with added/removed counts, and each rule's name, description
and trigger, and returns `{"triggered":[...]}`. Unknown names are ignored. Any failure or
unparseable output fails open to every structured rule, because a missed rule is worse than an extra
agent. Unstructured rules are always selected. At most 25 rules run (the rest are named in a
status), each in its own read-only agent with MCP off (10 min each, 3 concurrently on daemon
threads). An agent sees only its rule, `OUTPUT_CONTRACT`, PR metadata and the diff (the condensed
index for chunked reviews, as the hygiene pass does). Its findings are anchor-validated, end their
rationale with ` (rule: <name>)`, and are merged into the candidate only when non-empty, so a review
without rules is unchanged. A failed agent is reported and skipped.

Status lines come only from the calling thread, which polls `checkCancelled` while it waits;
providers receive a no-op status consumer because host status sinks are not thread-safe. The whole
phase has a 12-minute deadline. At expiry, queued rules are skipped and running agents are cancelled
with `Future.cancel(true)`. That interrupt makes Claude's `waitFor` and Copilot's `get` destroy or
close their run, without calling `cancelCurrentRequest`, whose token is one-way and would abort the
whole review. Findings already collected are kept. User cancellation cancels every agent and
propagates; `ClaudeService` and `CopilotService` track concurrent runs in sets so one cancel stops
them all. In deep review, the authority check runs before the selection call and before each agent
submission, and both prompts carry the semantic sections.

In recall mode, rule findings the critique dropped are restored by the hygiene restore rule (not low
confidence, anchored, not covered by a same-category finding within `HYGIENE_COVER_LINES`). Rule
findings carry no `sources` of their own and are not in the attribution snapshot. After critique,
`ReviewerAttribution.reattach` runs unchanged, and `unattributeUnmatchedRuleFindings` then clears
the primary-label fallback it gives a rule finding that no reviewer candidate matched, so a rule
finding is labeled only through a match (same file, same category, within two lines). Rule identity
is engine-owned, not read from the critique's text: before critique the pipeline snapshots each rule
finding's file, category and line as `RuleSite` values, and a final comment at such a site counts as
a rule finding even when the critique reworded it and dropped its ` (rule: <name>)` tag. A comment
that still carries the tag for a loaded rule also counts. Other unmatched comments keep the
fallback.

### Suggested changes are literal, one-line, high-confidence replacements
A comment may carry `suggestedChange`, the literal full replacement for its anchored line that
GitHub renders as a committable suggestion. `OUTPUT_CONTRACT` allows it only when the fix is
complete, local to that one line and the comment's confidence is `high`, with indentation preserved
and without the `N| ` prefix, the diff marker, code fences or prose; a model re-emitting an existing
comment repeats it unchanged, and the critique keeps it only when it is still a correct, complete
replacement. The parser reads it as an optional string, and `draftReviewJson` passes it to the
critique.

The model is not trusted with this field, because a wrong suggestion is one click from being
committed. `ReviewSuggestionGuard.apply` runs as the last step of every
`ReviewPipelineService.review`, after `CiFindingSuppressor.suppress`. It converts CRLF to LF and
strips trailing newlines, then clears the suggestion (always keeping the comment) unless all hold:
non-blank; at most 6 lines and 1,000 characters; no "```"; no line starting with a `N| ` prefix;
type `issue` or `suggestion`; confidence `high`; the anchored line is a new-side (added or context)
line of the file's `FileTarget.diff`; the text differs from that line; and its first line's leading
whitespace equals that line's. The anchored line text comes from
`InspectionManifest.newSideLineText`, built from the same diff parse as the anchor set, not a second
parser. The cleared count is logged.

Hosts treat a blank value as absent everywhere, and the webview clears the suggestion whenever the
comment's text or line changes (see `webview.md`).

### Review JSON parsing is self-healing, not all-or-nothing
`ReviewResultParser.parseReview` (in `review-engine`, shared by both the Claude CLI and Copilot SDK
paths on both hosts — IntelliJ calls it in-process, VS Code via the sidecar's `reviews/generate`)
tolerates and repairs common model schema deviations instead of rejecting the entire review, which
previously surfaced as "The model returned an invalid review format" for otherwise-good output.
Unknown top-level/comment fields are ignored; an over-long `summary` is truncated to 800 chars; a
comment `body` with embedded newlines is collapsed to one line but otherwise preserved in full; a
low-confidence `"issue"` is downgraded to `"suggestion"` rather than failing the review; the final
`verdict` is derived from (and corrected to match) the surviving comments rather than trusting the
model's stated verdict. Only individually-malformed line comments (missing/blank required field,
invalid enum value) are dropped — one bad comment no longer discards the other 19. A hard parse
failure remains only for genuinely non-JSON output or a missing `summary`/non-object root, since
there is nothing to salvage in that case.

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

`PROMPT_VERSION` is
`2026-10-thread-state-incremental-scope-corroboration-rule-gating-suggested-changes-stable-pagination-discarded-records-security-reliability-test-integrity-claims-gated-checklists`.
Pass B appends `BUG_HUNT_CHECKLIST` (failure disposition, swallowed failures, fail-open checks,
partial failure, removed safeguards, unchecked absent (protobuf default) inputs, unchecked internal
input limits, mixed versions, check-then-act races, unguarded rollouts, library contract misuse,
placeholder logic, evidence-gated security classes, and the other defect classes a generic "look for
bugs" instruction skips), `LanguageChecklists` entries for only the languages the diff changes, and
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
misplaced doc comments, unreserved removed protobuf fields) with fixed type, category and severity.
Pass A also compares each new type against the existing sibling it mirrors, because a missing
annotation or serialization convention is invisible from the diff alone. It also flags new or
changed code that re-implements logic an existing base class or shared helper already provides,
naming the type to reuse. On the Mae benchmark these were most of the misses, and the self-critique
pass is told they are not style findings so it keeps them. In recall mode (self-critique or a second
reviewer) the review prompt runs only passes A and B and tells the model to read every changed file
in full and look up each new or changed symbol's definition; the hygiene rules move to a dedicated
read-only hygiene pass (`ReviewHygienePrompts`) that runs after the review/supervisor
passes and before the critique. Bundling them into the main prompt let the semantic passes crowd
them out. Its findings are anchor-validated against the diff and merged into the draft, but the
critique's judgement does not remove them: after validation the pipeline restores every anchored,
non-low-confidence hygiene finding the critique dropped unless a kept finding of the same category
sits within two lines of it (`ReviewPipelineFindings.restoreHygieneFindings`). On the Mae benchmark
the critic discarded confirmed hot-path and exception-logging findings in every run despite being
told to keep them, so the critique can only reword or deduplicate them. A failed hygiene pass
reports a status and the review continues without it. Deep reviews carry the pinned semantic
sections into the hygiene prompt too, because every deep-review provider call must see the same
trusted evidence. With the JVM property `prpilot.review.reportDropped=true`, the pipeline emits one
`Validation dropped finding at <file>:<line> — <body>` status per draft finding the critique
removed; the recall benchmark sets it to tell a never-raised finding from one validation discarded.
Both the merge instruction and the self-critique dedupe rule treat the same problem at separate code
sites as separate findings; without that, a repeated rule violation collapsed to one comment. The
output contract tells the model to anchor each comment on the exact offending statement rather than
the enclosing method or hunk start, because inline comments are matched and shown by line. Outcome
logging appends `-supervisor-on` or `-supervisor-off`, so supervised and baseline results are not
pooled.

### Prompt-injection hardening
When wrapping untrusted payloads in XML-like tags, escape matching closing tags inside payload to
prevent tag breakout. Repository guidelines, PR metadata, descriptions, diffs, prior reviews, and
chat context remain untrusted reference data even when tag escaping is applied; the latest
`<user_message>` is the authorized request but cannot override persona or confidentiality
constraints. Chat builders retain only ten history turns and bound each turn, PR context, focused
context, and current request before provider execution. Provider capability isolation is the primary
security boundary.

Review prompts permit only evidence supplied in the prompt and require a fixed JSON contract. Both
provider parsers reject unknown fields, incomplete line comments, invalid enums, oversized values,
low-confidence issues, low-confidence comments with no stated rationale, and verdict/comment
mismatches before review data reaches the UI. Verify-comment and example-fix prompts use tagged
reference data and strict JSON response shapes for the same reason.
