# PR Pilot Architecture

IntelliJ and VS Code extension that lists GitHub Pull Requests and generates AI-powered code reviews
using a local AI CLI/runtime (Claude Code or GitHub Copilot, selected per-host in settings).

## Scope

- Keep this file focused on stable architecture and design constraints.
- See `CODEMAP.md` for implementation locations, task entry points, and related tests.
- Put workflow/checklist instructions in `AGENTS.md`.
- Put volatile version inventory in build manifests unless a version is itself an architecture
  constraint.

## System shape

PR Pilot is a multi-module Java 17 project with two IDE hosts sharing one React webview. `core`,
`github-engine`, and `review-engine` own host-neutral behavior. IntelliJ calls the engines
in-process; VS Code reaches the same capabilities through the stdio JSON-RPC `sidecar`. Host modules
own only IDE lifecycle, settings integration, and bridge wiring.

The shared webview owns the user workflow from PR discovery through review editing and submission.
GitHub credentials remain inside `github-engine`, while provider processes and review semantics
remain inside `review-engine`. Every engine capability must remain reachable from both hosts through
the parity boundary described below.

Visual references:

- [System architecture diagram](diagrams/architecture.md)
- [PR discovery and review-freshness sequence](diagrams/pr-discovery-sequence.md)
- [PR review generation sequence](diagrams/review-generation-sequence.md)

## Key design decisions

Only decisions that encode active constraints future code must respect and are not obvious from
source.

Add a new decision as a `###` section in the matching `docs/architecture/*.md` file and an index
line here.

**Semantic review** ([semantic-review.md](docs/architecture/semantic-review.md))

- [Source inventory is evidence, not semantic readiness](docs/architecture/semantic-review.md#source-inventory-is-evidence-not-semantic-readiness)
- [Successful import authority is memory-only and pre-armed](docs/architecture/semantic-review.md#successful-import-authority-is-memory-only-and-pre-armed)
- [Opt-in semantic-review lifecycle](docs/architecture/semantic-review.md#opt-in-semantic-review-lifecycle)
- [Bundled skill bytes are pinned](docs/architecture/semantic-review.md#bundled-skill-bytes-are-pinned)

**Review pipeline** ([review-pipeline.md](docs/architecture/review-pipeline.md))

- [Boundary compatibility findings are evidence-gated](docs/architecture/review-pipeline.md#boundary-compatibility-findings-are-evidence-gated)
- [Repository guidance confinement](docs/architecture/review-pipeline.md#repository-guidance-confinement)
- [Review guidance, file history and call sites come from the base commit](docs/architecture/review-pipeline.md#review-guidance-file-history-and-call-sites-come-from-the-base-commit)
- [Recall: second reviewer and candidate validation](docs/architecture/review-pipeline.md#recall-second-reviewer-and-candidate-validation)
- [Reviewer attribution and corroboration](docs/architecture/review-pipeline.md#reviewer-attribution-and-corroboration)
- [Review rules are trigger-gated and applied one agent per rule](docs/architecture/review-pipeline.md#review-rules-are-trigger-gated-and-applied-one-agent-per-rule)
- [Suggested changes are literal, one-line, high-confidence replacements](docs/architecture/review-pipeline.md#suggested-changes-are-literal-one-line-high-confidence-replacements)
- [Review JSON parsing is self-healing, not all-or-nothing](docs/architecture/review-pipeline.md#review-json-parsing-is-self-healing-not-all-or-nothing)
- [Low confidence is a gate, not a label](docs/architecture/review-pipeline.md#low-confidence-is-a-gate-not-a-label)
- [Prompt-injection hardening](docs/architecture/review-pipeline.md#prompt-injection-hardening)

**Review checklists** ([review-checklists.md](docs/architecture/review-checklists.md))

- [Security findings name their evidence](docs/architecture/review-checklists.md#security-findings-name-their-evidence)
- [Change-triggered checklists are deterministic and path-free](docs/architecture/review-checklists.md#change-triggered-checklists-are-deterministic-and-path-free)

**Protobuf review** ([proto-review.md](docs/architecture/proto-review.md))

- [Protobuf schema compatibility is computed, not inferred](docs/architecture/proto-review.md#protobuf-schema-compatibility-is-computed-not-inferred)
- [The schema delta compares against the diff's old side](docs/architecture/proto-review.md#the-schema-delta-compares-against-the-diffs-old-side)
- [Field comparisons follow the wire encoding](docs/architecture/proto-review.md#field-comparisons-follow-the-wire-encoding)
- [Regenerated protobuf code is left out of the reviewed diff](docs/architecture/proto-review.md#regenerated-protobuf-code-is-left-out-of-the-reviewed-diff)
- [Stale generated code is found by its generator comment](docs/architecture/proto-review.md#stale-generated-code-is-found-by-its-generator-comment)
- [Schema call sites include generated-code names](docs/architecture/proto-review.md#schema-call-sites-include-generated-code-names)

**Review benchmarks** ([review-benchmarks.md](docs/architecture/review-benchmarks.md))

- [Recall benchmark withholds the reference answers](docs/architecture/review-benchmarks.md#recall-benchmark-withholds-the-reference-answers)
- [ReviewBench runs never consult GitHub and delegate scoring](docs/architecture/review-benchmarks.md#reviewbench-runs-never-consult-github-and-delegate-scoring)

**Review comments and context**
([review-comments-context.md](docs/architecture/review-comments-context.md))

- [Comment anchoring snaps within a hunk, never across one](docs/architecture/review-comments-context.md#comment-anchoring-snaps-within-a-hunk-never-across-one)
- [Three comment keys exist on purpose](docs/architecture/review-comments-context.md#three-comment-keys-exist-on-purpose)
- [Missing rationale drops a comment, never fabricates one](docs/architecture/review-comments-context.md#missing-rationale-drops-a-comment-never-fabricates-one)
- [Comments on deleted files orphan by design](docs/architecture/review-comments-context.md#comments-on-deleted-files-orphan-by-design)
- [CI annotations are selected by whether they exist, not by whether the check failed](docs/architecture/review-comments-context.md#ci-annotations-are-selected-by-whether-they-exist-not-by-whether-the-check-failed)
- [CI-duplicate suppression is conservative by design](docs/architecture/review-comments-context.md#ci-duplicate-suppression-is-conservative-by-design)
- [Existing review comments carry thread state](docs/architecture/review-comments-context.md#existing-review-comments-carry-thread-state)
- [Incremental review since the last submitted review](docs/architecture/review-comments-context.md#incremental-review-since-the-last-submitted-review)
- [Comment anchoring policy](docs/architecture/review-comments-context.md#comment-anchoring-policy)

**Review diff coverage and quality**
([review-diff-coverage.md](docs/architecture/review-diff-coverage.md))

- [Diff acquisition model](docs/architecture/review-diff-coverage.md#diff-acquisition-model)
- [Complete review diff coverage](docs/architecture/review-diff-coverage.md#complete-review-diff-coverage)
- [Large diff visibility](docs/architecture/review-diff-coverage.md#large-diff-visibility)
- [Review quality gate and chunked review mode](docs/architecture/review-diff-coverage.md#review-quality-gate-and-chunked-review-mode)
- [Bounded review supervision](docs/architecture/review-diff-coverage.md#bounded-review-supervision)
- [Hygiene pass gets a mechanical log inventory](docs/architecture/review-diff-coverage.md#hygiene-pass-gets-a-mechanical-log-inventory)

**Providers** ([providers.md](docs/architecture/providers.md))

- [Provider toggle and prompt sharing](docs/architecture/providers.md#provider-toggle-and-prompt-sharing)
- [Copilot SDK runtime](docs/architecture/providers.md#copilot-sdk-runtime)
- [Provider capability isolation](docs/architecture/providers.md#provider-capability-isolation)
- [Reasoning effort normalization](docs/architecture/providers.md#reasoning-effort-normalization)
- [Copilot model discovery](docs/architecture/providers.md#copilot-model-discovery)
- [Binary resolution](docs/architecture/providers.md#binary-resolution)
- [Provider preflight](docs/architecture/providers.md#provider-preflight)
- [Max-turns recovery for Claude](docs/architecture/providers.md#max-turns-recovery-for-claude)

**Hosts and sidecar** ([hosts-and-sidecar.md](docs/architecture/hosts-and-sidecar.md))

- [Host operation correlation and acknowledged state](docs/architecture/hosts-and-sidecar.md#host-operation-correlation-and-acknowledged-state)
- [Cross-host parity](docs/architecture/hosts-and-sidecar.md#cross-host-parity)
- [First-run onboarding path](docs/architecture/hosts-and-sidecar.md#first-run-onboarding-path)
- [Notification parity](docs/architecture/hosts-and-sidecar.md#notification-parity)
- [Security constraints](docs/architecture/hosts-and-sidecar.md#security-constraints)
- [Repo detection and webview hosting](docs/architecture/hosts-and-sidecar.md#repo-detection-and-webview-hosting)
- [Pinned IntelliJ SDK versus local sandbox](docs/architecture/hosts-and-sidecar.md#pinned-intellij-sdk-versus-local-sandbox)
- [VS Code extension development target repo](docs/architecture/hosts-and-sidecar.md#vs-code-extension-development-target-repo)

**Sidecar capabilities** ([sidecar-capabilities.md](docs/architecture/sidecar-capabilities.md))

- [Engine capability boundary (test-enforced host parity)](docs/architecture/sidecar-capabilities.md#engine-capability-boundary-test-enforced-host-parity)
- [Sidecar streaming: async requests plus server-push notifications](docs/architecture/sidecar-capabilities.md#sidecar-streaming-async-requests-plus-server-push-notifications)
- [GitHub engine host wiring](docs/architecture/sidecar-capabilities.md#github-engine-host-wiring)

**Module and runtime boundaries**
([module-runtime-boundaries.md](docs/architecture/module-runtime-boundaries.md))

- [Module boundaries](docs/architecture/module-runtime-boundaries.md#module-boundaries)
- [core: plain Java module](docs/architecture/module-runtime-boundaries.md#core-plain-java-module)
- [Modules compile with --release](docs/architecture/module-runtime-boundaries.md#modules-compile-with---release)
- [Java sources compile as UTF-8](docs/architecture/module-runtime-boundaries.md#java-sources-compile-as-utf-8)
- [Threading model](docs/architecture/module-runtime-boundaries.md#threading-model)

**GitHub, drafts and worktrees**
([github-drafts-worktrees.md](docs/architecture/github-drafts-worktrees.md))

- [Pending review index coordination](docs/architecture/github-drafts-worktrees.md#pending-review-index-coordination)
- [Authenticated-user review freshness](docs/architecture/github-drafts-worktrees.md#authenticated-user-review-freshness)
- [Review submission uses a synchronous webview lock](docs/architecture/github-drafts-worktrees.md#review-submission-uses-a-synchronous-webview-lock)
- [The review worktree is pinned to the PR head commit, not the branch tip](docs/architecture/github-drafts-worktrees.md#the-review-worktree-is-pinned-to-the-pr-head-commit-not-the-branch-tip)
- [The review worktree is not indexed by the IDE](docs/architecture/github-drafts-worktrees.md#the-review-worktree-is-not-indexed-by-the-ide)
- [PR discovery scope](docs/architecture/github-drafts-worktrees.md#pr-discovery-scope)
- [Worktree-based PR context](docs/architecture/github-drafts-worktrees.md#worktree-based-pr-context)
- [GitHub API resilience policy](docs/architecture/github-drafts-worktrees.md#github-api-resilience-policy)
- [Draft review storage semantics](docs/architecture/github-drafts-worktrees.md#draft-review-storage-semantics)
- [Suggested changes are posted as GitHub suggestion blocks](docs/architecture/github-drafts-worktrees.md#suggested-changes-are-posted-as-github-suggestion-blocks)
- [Draft autosave](docs/architecture/github-drafts-worktrees.md#draft-autosave)

**Webview** ([webview.md](docs/architecture/webview.md))

- [Webview styling](docs/architecture/webview.md#webview-styling)
- [Webview accessibility tooling](docs/architecture/webview.md#webview-accessibility-tooling)
- [Webview draft mutation watchdog](docs/architecture/webview.md#webview-draft-mutation-watchdog)
- [Chat pane structured verify/fix results](docs/architecture/webview.md#chat-pane-structured-verifyfix-results)
- [Verify verdicts apply to the comment that was verified, not the latest one](docs/architecture/webview.md#verify-verdicts-apply-to-the-comment-that-was-verified-not-the-latest-one)
- [User-facing error copy](docs/architecture/webview.md#user-facing-error-copy)
- [Bridge payload validation](docs/architecture/webview.md#bridge-payload-validation)
- [PR chat scope](docs/architecture/webview.md#pr-chat-scope)
- [DTO mapping in IntelliJ webview bridge](docs/architecture/webview.md#dto-mapping-in-intellij-webview-bridge)
- [Webview bridge PR correlation](docs/architecture/webview.md#webview-bridge-pr-correlation)
- [Incremental review button and scope banner](docs/architecture/webview.md#incremental-review-button-and-scope-banner)
- [Reviewer sources chip](docs/architecture/webview.md#reviewer-sources-chip)
- [Suggested change preview and removal](docs/architecture/webview.md#suggested-change-preview-and-removal)
- [VS Code webview surfaces](docs/architecture/webview.md#vs-code-webview-surfaces)
- [IntelliJ webview surfaces](docs/architecture/webview.md#intellij-webview-surfaces)

**Local data** ([local-data.md](docs/architecture/local-data.md))

- [Outcome logging is stateless because IntelliJ does not use ReviewSessionService](docs/architecture/local-data.md#outcome-logging-is-stateless-because-intellij-does-not-use-reviewsessionservice)
- [The outcome log stores hashes, never comment text or file paths](docs/architecture/local-data.md#the-outcome-log-stores-hashes-never-comment-text-or-file-paths)

**CI and releases** ([ci.md](docs/architecture/ci.md))

- [Required checks are matched by job name](docs/architecture/ci.md#required-checks-are-matched-by-job-name)
- [Gradle jobs run on owner-only JDK copies](docs/architecture/ci.md#gradle-jobs-run-on-owner-only-jdk-copies)
- [Changed-line coverage is report-only](docs/architecture/ci.md#changed-line-coverage-is-report-only)
- [Mutation testing is nightly and report-only](docs/architecture/ci.md#mutation-testing-is-nightly-and-report-only)
- [Playwright container image](docs/architecture/ci.md#playwright-container-image)
- [Windows parity is measured, not gated](docs/architecture/ci.md#windows-parity-is-measured-not-gated)
- [Actions are pinned by commit SHA](docs/architecture/ci.md#actions-are-pinned-by-commit-sha)
- [Dependency updates wait a week and arrive grouped](docs/architecture/ci.md#dependency-updates-wait-a-week-and-arrive-grouped)
- [Dependency review covers npm; Gradle comes from submission](docs/architecture/ci.md#dependency-review-covers-npm-gradle-comes-from-submission)
- [Transitive security fixes are temporary floors](docs/architecture/ci.md#transitive-security-fixes-are-temporary-floors)
- [CodeQL runs as advanced setup](docs/architecture/ci.md#codeql-runs-as-advanced-setup)
- [Workflow lint](docs/architecture/ci.md#workflow-lint)
- [Release builds restore no caches and publish separately](docs/architecture/ci.md#release-builds-restore-no-caches-and-publish-separately)

## Settings persistence

IntelliJ stores these in `PluginSettings` (`claudeReviews.xml`); VS Code contributes the same names
as `pr-pilot.<name>` in `vscode-extension/package.json`. Field semantics, guidance profiles, and
the VS Code default-mirroring rule are in [settings.md](docs/architecture/settings.md). No API keys
or tokens are written to disk.

| Setting | Default | Purpose |
|---|---|---|
| `githubBaseUrl` | `https://github.com` | GitHub.com or Enterprise Server origin for API calls |
| `notificationsEnabled` | `false` | Background PR notifications |
| `notifyReviewRequested` | `true` | Notify when the user's review is requested |
| `notifyStarredRepos` | `false` | Notify about new PRs in starred repositories |
| `notificationPollMinutes` | `5` | Notification poll interval in minutes |
| `reviewModel` | `""` | Claude model; blank uses the CLI default |
| `reviewModelCopilot` | `"claude-sonnet-4.6"` | Copilot model |
| `reviewProvider` | `"claude"` | `claude` or `copilot` |
| `reviewEffort` | `"high"` | Reasoning effort, `none` through `max` |
| `copilotInheritMcp` | `false` | Inject MCP servers from the trusted Copilot config (Copilot only) |
| `copilotAutoEnableMcpOnReview` | `false` | Force MCP for Copilot review generation only |
| `copilotConfigDir` | `""` | Copilot config directory override; blank uses `~/.copilot` |
| `reviewFocusAreas` | `""` | Default focus areas; a per-review override wins |
| `reviewCustomInstructions` | `""` | Default extra instructions; a per-review override wins |
| `reviewGuidanceProfiles` | `[]` | Named focus/instruction bundles with stable IDs |
| `activeReviewGuidanceProfileId` | `""` | Selected profile; blank uses the built-in defaults |
| `repositoryReviewInstructions` | `{}` | Instructions remembered per `owner/repo` |
| `reviewSelfCritique` | `true` | Validation pass that drops unsupported findings |
| `reviewSupervisorEnabled` | `true` | Bounded "Re-inspect coverage gaps" follow-ups |
| `reviewRulesDirectory` | `""` | Local folder of the reviewer's own rules |
| `reviewBuiltInRules` | `true` | Runs the review rules bundled with PR Pilot |
| `reviewSecondReviewerModel` | `""` | Parallel Copilot second reviewer; blank disables it |
| `experimentalIntellijAssistedReview` | `false` | IntelliJ-assisted review opt-in |
| `reviewGuidanceGlobs` | `""` (hidden) | Base-commit guidance globs; in neither settings UI |

## Local data files

The shared semantic runtime reads `~/.pr-pilot/semantic-review.json` (schema 1) as
machine-local launch configuration. It does not write that file or persist import baselines.
Owned extracted workers and per-call requests are temporary, outside the worktree, and removed
after their synchronous owned process calls finish. `semantic-worktrees.json` durably records
retained repository/worktree/head identities under this directory, guarded by
`semantic-worktrees.lock`; `semantic-leases/<id>.lock` holds the active execution lease.
Cancellation or restart releases process locks, not retention. Only explicit project-closed,
lease-free, identity-checked, clean non-force cleanup removes a retained worktree. The preparation
binding itself is memory-only: restarting preserves maintenance access, not authority to Continue.

IntelliJ-only (`intellij-plugin`'s `PendingReviewIndex`/`SeenPRSet`); VS Code persists the
equivalent state via extension `globalState` instead (see
[Notification parity](docs/architecture/hosts-and-sidecar.md#notification-parity)).

| Path | Purpose |
|------|---------|
| `~/.pr-pilot/pending-prs.json` | Index of PRs with saved drafts (owner, repo, number, title, savedAt, headSha) |
| `~/.pr-pilot/pending-prs.json.corrupt-<timestamp>` | User-triggered quarantine copy of an unreadable draft index; GitHub drafts are unchanged |
| `~/.pr-pilot/seen-prs.json` | Set of `owner/repo#number` strings already notified about |
| `~/.pr-pilot/review-outcomes.jsonl` | Append-only outcome log: one JSON object per generated/submitted comment (`ReviewOutcomeLog`, engine-owned), including category but never prose or paths, written by both hosts on submit |
