# Review findings and post-processing

Key design decisions moved from [ARCHITECTURE.md](../../ARCHITECTURE.md). Each section encodes an
active constraint future code must respect.

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

### Cross-pass rewordings are deduplicated by content
Two passes (primary and second reviewer, rule or hygiene pass, follow-up) often report one defect a
few lines apart in different words, which the two-line window and exact-body key miss.
`FindingSimilarity.duplicates` also treats two comments as one finding when they share file, type
and category, sit within `NEAR_LINES` (10) lines, and their bodies match: equal after normalizing
case and whitespace, at least three shared code identifiers (camelCase or underscored tokens) that
cover 60% of the smaller set, or 60% Jaccard overlap of words of four or more characters. It
applies only across passes, in `ReviewResultMerger.merge` (a follow-up comment against baseline
comments; the higher-priority comment is kept, ties keep the baseline, sources are unioned),
`ReviewerAttribution.collapse`, and `restoreHygieneFindings` (against validated comments only).
Never apply it within one pass: a reviewer repeats a finding at each call site on purpose, and
those comments share identifiers and wording. `Evidence.matches` and rule-site matching keep the
two-line window, since the evidence snapshot carries no body.

### Suggested changes are literal, one-line, high-confidence replacements
A comment may carry `suggestedChange`, the literal full replacement for its anchored line that
GitHub renders as a committable suggestion. `OUTPUT_CONTRACT` allows it only when the fix is
complete, local to that one line and the comment's confidence is `high`, with indentation preserved
and without the `N| ` prefix, the diff marker, code fences or prose; a model re-emitting an existing
comment repeats it unchanged, and the critique keeps it only when it is still a correct, complete
replacement. The parser reads it as an optional string, and `draftReviewJson` passes it to the
critique.

The model is not trusted with this field, because a wrong suggestion is one click from being
committed. `CiFindingSuppressor.suppress` runs before `restoreHygieneFindings` (so a doomed CI
duplicate cannot shadow a nearby hygiene or rule finding) and before `ReviewResultMerger.capFinal`
(so CI duplicates never consume any of the 20 comment slots); `ReviewSuggestionGuard.apply` then
runs as the last step of every `ReviewPipelineService.review`. It converts CRLF to LF and
strips trailing newlines, then clears the suggestion (always keeping the comment) unless all hold:
non-blank; at most 6 lines and 1,000 characters; no "```"; no line starting with a `N| ` prefix;
type `issue` or `suggestion`; confidence `high`; the anchored line is a new-side (added or context)
line of the file's `FileTarget.diff`; the text differs from that line; and its first line's leading
whitespace equals that line's. The anchored line text comes from
`InspectionManifest.newSideLineText`, built from the same diff parse as the anchor set, not a second
parser. The cleared count is logged.

Hosts treat a blank value as absent everywhere, and the webview clears the suggestion whenever the
comment's text or line changes (see [webview.md](webview.md#suggested-change-preview-and-removal)).

### Review JSON parsing is self-healing, not all-or-nothing
`ReviewResultParser.parseReview` (in `review-engine`, shared by both the Claude CLI and Copilot SDK
paths on both hosts — IntelliJ calls it in-process, VS Code via the sidecar's `reviews/generate`)
tolerates and repairs common model schema deviations instead of rejecting the entire review, which
previously surfaced as "The model returned an invalid review format" for otherwise-good output.
Unknown top-level/comment fields are ignored; an over-long `summary` is truncated to 800 chars; a
comment `body` with embedded newlines is collapsed to one line but otherwise preserved in full; a
low-confidence `"issue"` (or any low-confidence comment missing a `rationale`) is dropped rather
than failing the review, while `"issue"` + `"nit"` normalizes to `"suggestion"`; the final
`verdict` is derived from (and corrected to match) the surviving comments rather than trusting the
model's stated verdict. Only individually-malformed line comments (missing/blank required field,
invalid enum value) are dropped — one bad comment no longer discards the other 19. A hard parse
failure remains only for genuinely non-JSON output or a missing `summary`/non-object root, since
there is nothing to salvage in that case.
