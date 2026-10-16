# Review benchmarks

Key design decisions moved from [ARCHITECTURE.md](../../ARCHITECTURE.md). Each section encodes an
active constraint future code must respect.

### Recall benchmark withholds the reference answers
`review-benchmark` is a developer tool, not an engine capability, so it has no RPC, sidecar, or host
wiring. It reviews the exact commit Mae first commented on, rendering the diff locally from
`base...commit` rather than fetching GitHub's current PR diff, and it passes blank existing reviews,
prior review, and commit messages so Mae's comments (or replies to them) cannot leak into the
prompt. Matching requires the same file and a nearby line before the judge may pair two findings, so
the judge can only reject location matches, never invent distant ones.

### ReviewBench runs never consult GitHub and delegate scoring
`ReviewBenchRunner` builds the review from the corpus entry only: its title, body, a diff rendered
locally from `base...head`, and a repository profile read from the worktree. Existing reviews, CI,
commits, and linked issues stay blank because the corpus PRs are public and their review threads
could leak golden findings. `note` comments are dropped unless `--include-notes`, since the judge
scores every submitted finding as a true or false positive. Scoring is never reimplemented here:
`scripts/reviewbench.mjs` runs ReviewBench's own judge at a pinned commit, once per round, because
the judge merges every findings file that names the same pull request. Copilot judge models need
`compat.supportsDeveloperRole: false` in the generated pi `models.json`; otherwise pi sends the
judge's instructions as a `developer` message that Copilot's Claude endpoint ignores, and the
matcher returns prose instead of JSON. Per-review diagnostics (critique-dropped findings and stage
lines) go to `<run>/diagnostics/`, never `<run>/findings/`, because the judge reads every file in
the findings directory and would score dropped findings as submitted ones.
