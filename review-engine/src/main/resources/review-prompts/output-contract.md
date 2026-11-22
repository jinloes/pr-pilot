Respond ONLY with a JSON object — no markdown fences, no prose before or after.

Line numbering: every line in <pr_diff> is prefixed with its new-file line number followed by "| " (deleted lines have no number, just "| "). Use that prefixed number directly as the "line" value; do not recompute it from @@ headers. Anchor each comment on the exact line of the offending statement, call, or expression, not on the enclosing method signature, block opening, or the first line of the hunk.

Schema (emit exactly this structure — no extra fields, no comments, no trailing text):
{
  "summary": "## Overview\n...\n## Key Changes\n- ...",
  "lineComments": [],
  "inspection": {"inspectedTargets": [], "evidence": []},
  "verdict": "APPROVE"
}

Required fields: "summary", "lineComments", and "verdict". The engine-internal "inspection" field is required when an <inspection_manifest> is present and optional otherwise. Each line comment requires "file", "line", "type", "severity", "category", "confidence", and "body". "rationale" is required for "issue" and "suggestion", and for any comment whose "confidence" is "low"; it is optional only for a "note" you rate "medium" or "high". "suggestedChange" is optional. Do not emit other fields.

Example line comments (they illustrate the field shape — do not copy the content):
[
  {"file": "src/auth/Session.java", "line": 42, "type": "issue", "severity": "major", "category": "security", "confidence": "high", "body": "Token is compared with equals(), which is not constant-time; use MessageDigest.isEqual to close the timing side channel.", "rationale": "Line 42 compares the secret token with String.equals."},
  {"file": "src/api/Errors.java", "line": 58, "type": "suggestion", "severity": "minor", "category": "maintainability", "confidence": "medium", "body": "Reuse ErrorCauses.unwrap to remove the duplicate cause-chain traversal while keeping this API's status mapping local.", "rationale": "src/shared/ErrorCauses.java implements the same cycle-safe traversal; only the mapping policy differs."}
]

Field constraints:
- "summary": markdown. Required sections: ## Overview (2-3 sentences on what and why), ## Key Changes (up to 8 one-line bullets prioritized by risk, then add "- ... and N more files" if needed), ## Risk Areas (omit if none). Keep it tight; if it runs long, trim Key Changes first, then omit Risk Areas.
- "body": 1-2 sentences. State the problem, why it matters, and what to do — no preamble, no 'consider', use imperatives. Must be a single-line JSON string (no literal newlines).
- "severity": one of "blocker" | "major" | "minor" | "nit". blocker = ship-stopping (data loss, security, crash); major = a real bug or risk that should be fixed; minor = small correctness/clarity fix; nit = trivial.
- "category": one of "correctness" | "security" | "performance" | "tests" | "maintainability" | "compatibility".
- "confidence": one of "low" | "medium" | "high". Never report a low-confidence "issue" — omit the finding instead. A low-confidence "issue" is discarded, not downgraded, so emitting one loses the finding entirely. Every low-confidence comment must still state its "rationale".
- "rationale": one sentence citing concrete evidence from supplied context.
- "suggestedChange": optional. The literal full replacement for the anchored line, with its indentation preserved, without the "N| " prefix or the "+"/space diff marker; at most 6 lines; no code fences and no prose. Emit it only when the fix is complete, local to that one line, and your "confidence" is "high". When re-emitting an existing comment, repeat its "suggestedChange" unchanged.
- "lineComments": at most 20. Keep highest priority by severity (blocker > major > minor > nit), then confidence.

"inspection" is an audit ledger, not prose. Copy only IDs from <inspection_manifest>. Put every file or hunk you substantively inspected in "inspectedTargets". For each emitted line comment, add one "evidence" item with its zero-based "findingIndex", supporting "targetIds", and any read-only worktree paths in "relatedFiles". Never invent IDs or paths and never include snippets, tool arguments, or reasoning in this field.

Type and severity must agree: an "issue" is "blocker", "major", or "minor" (never "nit"); anything you would rate "nit" must be type "suggestion" or "note".

"type" values:
- "issue" — a confirmed bug, security flaw, or test gap directly supported by supplied context. For test coverage, flag only a non-trivial new public method or conditional branch with no test in this diff; exclude infrastructure, configuration, and refactoring. A test deleted, disabled, or weakened while the behavior it covered remains is also a test gap.
- "suggestion" — a concrete improvement worth making but not blocking
- "note" — a localized, evidence-limited question

Only comment on changed ('+') lines — those whose content after the line prefix begins with '+'. Do not flag pre-existing issues in unchanged context lines. A deleted ('-') line is in scope when removing it creates the defect — a dropped guard, null check, validation, authorization check, lock, cleanup, required call, or test assertion. Anchor such a finding on the nearest added ('+') line in the same hunk, or failing that in the same file, and quote the removed code in "rationale"; if the file has no added line, omit it, except that a deleted test or assertion may anchor on an added line of the production change whose behavior it covered. If a changed line needs more context than the diff shows, read the relevant working-directory file before deciding. Return verdict="COMMENT" with lineComments=[] only when the change is genuinely unreviewable even after reading (for example generated, vendored, or binary content).

"verdict" must be one of: "APPROVE" | "REQUEST_CHANGES" | "COMMENT"
"type" must be one of: "issue" | "suggestion" | "note"
"line" must be a positive integer (new-file line number per the numbering rules above)

Verdict criteria:
- APPROVE: no issues found, or only suggestions/notes
- REQUEST_CHANGES: at least one "issue" with severity "blocker" or "major" that must be resolved
- COMMENT: only minor issues, suggestions, notes, or questions about intent or approach — nothing blocking
