# Code map: `review-benchmark/`

Part of the [code map](../../CODEMAP.md), which holds the task-to-code index and cross-module paths.
Paths omit `src/main/java/com/jinloes/prpilot/` and equivalent test roots where the module context
makes them unambiguous; other paths are relative to the module directory.

Developer-only CLIs: `./gradlew :review-benchmark:reviewBenchmark` measures PR Pilot's recall
against Mae's GitHub review comments, and `reviewBench` (driven by `scripts/reviewbench.mjs`)
produces findings for ReviewBench's judge. It is not shipped with either host.

- `benchmark/ReviewBenchmark.java` - Entry point and per-PR orchestration.
- `benchmark/BenchmarkOptions.java` - Argument parsing (including repeatable `--guidance-glob`),
  defaults, and usage.
- `benchmark/PrRef.java` - `owner/repo#N` and PR-URL parsing.
- `benchmark/MaeComments.java` - Paginated review-comment fetch, Mae filtering, and
  first-reviewed-commit baseline.
- `benchmark/LocalCheckout.java` - Worktree at the reviewed commit and a locally rendered diff,
  bounded at the review limit or, for `--chunked`, the validation limit.
- `benchmark/FindingMatcher.java` - Same-file/nearby-line candidate pairing and one-to-one
  assignment.
- `benchmark/LlmJudge.java` - Model judge prompt and JSON verdict parsing for candidate pairs.
- `benchmark/BenchmarkReport.java` - Totals (including complete-diff recall), dropped-by-validation
  findings with near-miss marking, and Markdown/JSON report rendering.
- `benchmark/RepeatSummary.java` - Per-run recall spread and per-PR matched counts for `--repeat`.
- `benchmark/ReviewBenchRunner.java` - ReviewBench entry point (`:review-benchmark:reviewBench`):
  reviews each corpus PR per round and writes judge-ready findings, resuming past existing files.
- `benchmark/ReviewBenchOptions.java` - ReviewBench runner arguments, `--only`/`--limit` selection,
  and usage.
- `benchmark/ReviewBenchTask.java` - Corpus entry loading and validation, golden-file key, and
  mirror URL.
- `benchmark/ReviewBenchCheckout.java` - Cached partial clone of a `review-bench` mirror,
  fetch-by-SHA with upstream fallback, and the head worktree and diff.
- `benchmark/ReviewBenchFindings.java` - Conversion of line comments to ReviewBench's judging input
  format.
- `benchmark/ReviewBenchDiagnostics.java` - Per-review stage lines and critique-dropped findings,
  written to `<run>/diagnostics/` for the report.
- Tests mirror each class; `LocalCheckoutTest` and `ReviewBenchCheckoutTest` use real git in temp
  directories.
