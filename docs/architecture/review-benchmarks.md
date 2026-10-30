# Review benchmarks

Key design decisions moved from [ARCHITECTURE.md](../../ARCHITECTURE.md). Each section encodes an
active constraint future code must respect.

### Recall benchmark withholds the reference answers
`review-benchmark` is a developer tool, not an engine capability, so it has no RPC, sidecar, or host
wiring. It reviews the exact commit Mae first commented on, rendering the diff locally from
`base...commit` rather than fetching GitHub's current PR diff, and it passes blank existing reviews,
prior review, and commit messages so the reference reviewer's comments (or replies to them) cannot leak into the
prompt. Matching requires the same file and a nearby line before the judge may pair two findings, so
the judge can only reject location matches, never invent distant ones.

### Recall benchmark diffs against the base the reference reviewer saw
GitHub reports only a PR's current base. For a stacked PR whose parent was squash-merged, that is
the default branch, and diffing it against the reference reviewer's commit re-reviews the whole stack: one 2-file PR
became a 38-file, 200k-token prompt that exceeded the model limit. The benchmark therefore reads the
base branch's `BaseRefForcePushedEvent` history from the PR's GraphQL timeline and, among those tips
and the current base, uses the one whose three-dot diff to the reviewed commit spans the fewest
commits, skipping tips that already contain that commit. A `base=<sha>` entry after the URL in the
PRs file overrides this. If the history is unavailable, the current base is used, so a GraphQL
failure never fails a PR. Patch-id matching (`git cherry`) was rejected because a stack revised
after review squash-merges different content.

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
