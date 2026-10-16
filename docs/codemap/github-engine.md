# Code map: `github-engine/`

Part of the [code map](../../CODEMAP.md), which holds the task-to-code index and cross-module paths.
Paths omit `src/main/java/com/jinloes/prpilot/` and equivalent test roots where the module context
makes them unambiguous; other paths are relative to the module directory.

Host-neutral GitHub, repository detection, PR context, and draft-review behavior. IDE hosts never
receive GitHub tokens.

- `engine/GitHubEngineApi.java` - Complete GitHub capability surface and JSON-RPC wire-name map.
- `engine/GitHubEngine.java` - Composition root delegating to GitHub services.
- `sidecar/github/GitHubAuthService.java` - `gh` and GitHub API authentication checks.
- `sidecar/github/GitHubApiBase.java` - Validated GitHub.com/GHES REST and GraphQL endpoints.
- `sidecar/github/GitHubHttpClient.java` - Shared authenticated GET/POST transport with the default
  retry policy and optional single-attempt deadlines.
- `sidecar/github/CheckAuthResult.java` - Stable authentication diagnosis.
- `sidecar/pr/PrSearchQueryService.java` - Normalized PR search construction.
- `sidecar/pr/PrListService.java` - Authenticated PR search, truncation, and bounded freshness
  enrichment.
- `sidecar/pr/PrReviewStatusService.java` - Viewer lookup plus one GraphQL freshness query for at
  most 50 list results.
- `sidecar/pr/PrDetailService.java` - PR metadata and worktree-head lookup.
- `sidecar/pr/IncrementalDiffService.java` - Diff of commits since the viewer's last submitted
  review (`prs/getIncrementalDiff`), using `PrReviewStatusService.reviewBaseline` and
  `PrDiffService.compare`, with typed full-review fallback reasons.
- `sidecar/pr/PrDiffService.java` - Whole-file, UTF-8-safe review/validation diff bounding
  (smallest-first, 250 KB per file, coverage trailer) and HTTP 406 as `diff_too_large`.
- `sidecar/pr/DraftReviewService.java` - Pending-review lookup and decoding.
- `sidecar/pr/DraftReviewCodec.java` - PR Pilot review metadata encoding/decoding, and
  `visibleBody`, which strips hidden metadata from a pending body before publishing.
- `sidecar/pr/DraftReviewMutationService.java` - Save, submit, and delete orchestration; `submit`
  reads the pending review and publishes the reviewer's text plus its visible sections
  (`composeSubmitBody`).
- Draft mutation tests are split by area into `sidecar/pr/DraftReviewMutationService<Area>Test`
  files (`SaveLifecycle`, `SaveFallback`, `SaveSuggestedChange`, `SubmitBody`, `SubmitBasics`,
  `DeleteValidation`, `HttpRetry`) sharing `DraftReviewMutationServiceTestSupport`;
  `DraftReviewMutationServiceSubmitBodyTest` pins the fallback bodies mirrored in `publishBody.ts`.
- `sidecar/pr/PrSupplementalService.java` - Raw search, starred repositories, and prompt context,
  including `[resolved]`/`[outdated]` thread-state tagging of existing review comments.
- `sidecar/pr/*Result.java` and DTOs - Token-free engine outcomes.
- `sidecar/repo/RepoDetector.java` - Repository detection orchestration.
- `sidecar/repo/GitDirectoryResolver.java` - Git metadata/worktree resolution.
- `sidecar/repo/GitConfigOriginReader.java` - Origin URL reading.
- `sidecar/repo/RemoteUrlParser.java` - HTTPS/SSH/SCP owner/repository parsing.
- Tests: `github-engine/src/test/java/com/jinloes/prpilot/sidecar/`, mirroring service packages.
