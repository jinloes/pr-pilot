# Code map: `core/`

Part of the [code map](../../CODEMAP.md), which holds the task-to-code index and cross-module paths.
Paths omit `src/main/java/com/jinloes/prpilot/` and equivalent test roots where the module context
makes them unambiguous; other paths are relative to the module directory.

Plain Java 17 shared models with no host dependencies.

- `model/PullRequest.java` - Immutable PR identity, metadata, and review-freshness state.
- `model/ReviewStatus.java` - Authenticated-user review freshness (`UNREVIEWED`, `REVIEWED`,
  `UPDATED_SINCE_REVIEW`, or `UNAVAILABLE`).
- `model/ReviewResult.java` - Review summary, verdict, and line comments.
- `model/LineComment.java` - Inline comment anchor and quality metadata.
- `model/ChatMessage.java` - Immutable chat role and content.
- `model/PRReviewRequest.java` - Immutable review-generation parameter object; its builder strips a
  diff-coverage trailer from the diff into a never-null `diffCoverage`, which
  `withGeneratedCodeOmitted` keeps when it swaps in a diff without regenerated protobuf code.
- `model/DiffCoverage.java` - Engine-authored diff-coverage trailer: omitted/listed counts, budget,
  scan completeness, up to 200 omitted paths, strict end-anchored `split`, and escaped prompt text.
  Pinned with its webview mirror by `core/src/test/resources/diff-coverage/trailer.golden.txt`.
- `model/ReviewProvider.java` - Claude/Copilot provider enum.
- `model/SourceInventory.java` - Strict required/nullable wire shapes, canonical identities,
  source manifest digest and shared protocol limits; no READY state.
- Tests: `core/src/test/java/com/jinloes/prpilot/`.
