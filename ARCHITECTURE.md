# PR Pilot Architecture

PR Pilot reviews GitHub pull requests in IntelliJ and VS Code using Claude Code, GitHub Copilot, or
Antigravity.

## Scope

- Architecture and active design constraints only; see `CODEMAP.md` for file paths and `AGENTS.md`
  for workflow rules.
- Keep volatile versions in build manifests unless a version is itself a constraint.

## System shape

Multi-module Java 21 project with two IDE hosts sharing one React webview. `core`, `github-engine`,
and `review-engine` own host-neutral logic: IntelliJ calls them in-process and VS Code reaches them
over the stdio JSON-RPC `sidecar`, leaving hosts with IDE lifecycle, settings, and bridge wiring.
GitHub tokens stay in `github-engine`; provider processes stay in `review-engine`.

Visual references:

- [System architecture diagram](diagrams/architecture.md)
- [PR discovery and review-freshness sequence](diagrams/pr-discovery-sequence.md)
- [PR review generation sequence](diagrams/review-generation-sequence.md)

## Key design decisions

Index non-obvious active constraints here; record each as a `###` section in the matching topic file.

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
- [Low confidence is a gate, not a label](docs/architecture/review-pipeline.md#low-confidence-is-a-gate-not-a-label)
- [Prompt-injection hardening](docs/architecture/review-pipeline.md#prompt-injection-hardening)

**Review findings** ([review-findings.md](docs/architecture/review-findings.md))

- [Reviewer attribution and corroboration](docs/architecture/review-findings.md#reviewer-attribution-and-corroboration)
- [Cross-pass rewordings are deduplicated by content](docs/architecture/review-findings.md#cross-pass-rewordings-are-deduplicated-by-content)
- [Suggested changes are literal, one-line, high-confidence replacements](docs/architecture/review-findings.md#suggested-changes-are-literal-one-line-high-confidence-replacements)
- [Review JSON parsing is self-healing, not all-or-nothing](docs/architecture/review-findings.md#review-json-parsing-is-self-healing-not-all-or-nothing)

**Review checklists** ([review-checklists.md](docs/architecture/review-checklists.md))

- [Security findings name their evidence](docs/architecture/review-checklists.md#security-findings-name-their-evidence)
- [Restrictive invariants require current contract evidence](docs/architecture/review-checklists.md#restrictive-invariants-require-current-contract-evidence)
- [Policy-aware reuse](docs/architecture/review-checklists.md#policy-aware-reuse)
- [Downstream context](docs/architecture/review-checklists.md#downstream-context)
- [Change-triggered checklists are deterministic and path-free](docs/architecture/review-checklists.md#change-triggered-checklists-are-deterministic-and-path-free)
- [Review rules are trigger-gated and applied one agent per rule](docs/architecture/review-checklists.md#review-rules-are-trigger-gated-and-applied-one-agent-per-rule)

**Protobuf review** ([proto-review.md](docs/architecture/proto-review.md))

- [Protobuf schema compatibility is computed, not inferred](docs/architecture/proto-review.md#protobuf-schema-compatibility-is-computed-not-inferred)
- [The schema delta compares against the diff's old side](docs/architecture/proto-review.md#the-schema-delta-compares-against-the-diffs-old-side)
- [Field comparisons follow the wire encoding](docs/architecture/proto-review.md#field-comparisons-follow-the-wire-encoding)
- [Regenerated protobuf code is left out of the reviewed diff](docs/architecture/proto-review.md#regenerated-protobuf-code-is-left-out-of-the-reviewed-diff)
- [Stale generated code is found by its generator comment](docs/architecture/proto-review.md#stale-generated-code-is-found-by-its-generator-comment)
- [Schema call sites include generated-code names](docs/architecture/proto-review.md#schema-call-sites-include-generated-code-names)

**Review benchmarks** ([review-benchmarks.md](docs/architecture/review-benchmarks.md))

- [Recall benchmark withholds the reference answers](docs/architecture/review-benchmarks.md#recall-benchmark-withholds-the-reference-answers)
- [Recall benchmark diffs against the base the reference reviewer saw](docs/architecture/review-benchmarks.md#recall-benchmark-diffs-against-the-base-the-reference-reviewer-saw)
- [ReviewBench runs never consult GitHub and delegate scoring](docs/architecture/review-benchmarks.md#reviewbench-runs-never-consult-github-and-delegate-scoring)
- [Layered provider canary and stage-isolated golden evals](docs/architecture/review-benchmarks.md#layered-provider-canary-and-stage-isolated-golden-evals)

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
- [GitHub size-limit fallback uses pinned Git objects](docs/architecture/review-diff-coverage.md#github-size-limit-fallback-uses-pinned-git-objects)
- [Large diff visibility](docs/architecture/review-diff-coverage.md#large-diff-visibility)
- [Review quality gate and chunked review mode](docs/architecture/review-diff-coverage.md#review-quality-gate-and-chunked-review-mode)
- [Bounded review supervision](docs/architecture/review-diff-coverage.md#bounded-review-supervision)
- [Hygiene pass gets a mechanical log inventory](docs/architecture/review-diff-coverage.md#hygiene-pass-gets-a-mechanical-log-inventory)

**Providers** ([providers.md](docs/architecture/providers.md))

- [Provider toggle and prompt sharing](docs/architecture/providers.md#provider-toggle-and-prompt-sharing)
- [Copilot SDK runtime](docs/architecture/providers.md#copilot-sdk-runtime)
- [Antigravity CLI runtime](docs/architecture/providers.md#antigravity-cli-runtime)
- [Provider capability isolation](docs/architecture/providers.md#provider-capability-isolation)
- [Host code intelligence over MCP](docs/architecture/providers.md#host-code-intelligence-over-mcp)
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
- [No silent review drops](docs/architecture/hosts-and-sidecar.md#no-silent-review-drops)
- [Diagnostics keep the cause behind a user-facing error](docs/architecture/hosts-and-sidecar.md#diagnostics-keep-the-cause-behind-a-user-facing-error)

**Sidecar capabilities** ([sidecar-capabilities.md](docs/architecture/sidecar-capabilities.md))

- [Engine capability boundary (test-enforced host parity)](docs/architecture/sidecar-capabilities.md#engine-capability-boundary-test-enforced-host-parity)
- [Sidecar streaming: async requests plus server-push notifications](docs/architecture/sidecar-capabilities.md#sidecar-streaming-async-requests-plus-server-push-notifications)

**GitHub RPC and host wiring** ([github-rpc.md](docs/architecture/github-rpc.md))

- [GitHub and repository RPC contracts](docs/architecture/github-rpc.md#github-and-repository-rpc-contracts)
- [GitHub engine host wiring](docs/architecture/github-rpc.md#github-engine-host-wiring)

**Module and runtime boundaries**
([module-runtime-boundaries.md](docs/architecture/module-runtime-boundaries.md))

- [Module boundaries](docs/architecture/module-runtime-boundaries.md#module-boundaries)
- [Sidecar owns its Jackson 2 mapper](docs/architecture/module-runtime-boundaries.md#sidecar-owns-its-jackson-2-mapper)
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
- [Chat pane structured verify/fix results](docs/architecture/webview.md#chat-pane-structured-verifyfix-results)
- [Verify verdicts apply to the comment that was verified, not the latest one](docs/architecture/webview.md#verify-verdicts-apply-to-the-comment-that-was-verified-not-the-latest-one)
- [Suggest fix patches attach as a collapsed diff, not a suggestion block](docs/architecture/webview.md#suggest-fix-patches-attach-as-a-collapsed-diff-not-a-suggestion-block)
- [Chat replies become comments by picking a line](docs/architecture/webview.md#chat-replies-become-comments-by-picking-a-line)
- [Incremental review button and scope banner](docs/architecture/webview.md#incremental-review-button-and-scope-banner)
- [Reviewer sources chip](docs/architecture/webview.md#reviewer-sources-chip)
- [Suggested change preview and removal](docs/architecture/webview.md#suggested-change-preview-and-removal)

**Webview bridge and host surfaces**
([webview-bridge-and-hosts.md](docs/architecture/webview-bridge-and-hosts.md))

- [Webview draft mutation watchdog](docs/architecture/webview-bridge-and-hosts.md#webview-draft-mutation-watchdog)
- [User-facing error copy](docs/architecture/webview-bridge-and-hosts.md#user-facing-error-copy)
- [Bridge payload validation](docs/architecture/webview-bridge-and-hosts.md#bridge-payload-validation)
- [PR chat scope](docs/architecture/webview-bridge-and-hosts.md#pr-chat-scope)
- [DTO mapping in IntelliJ webview bridge](docs/architecture/webview-bridge-and-hosts.md#dto-mapping-in-intellij-webview-bridge)
- [Webview bridge PR correlation](docs/architecture/webview-bridge-and-hosts.md#webview-bridge-pr-correlation)
- [Opening diff files and findings in the IDE editor](docs/architecture/webview-bridge-and-hosts.md#opening-diff-files-and-findings-in-the-ide-editor)
- [VS Code webview surfaces](docs/architecture/webview-bridge-and-hosts.md#vs-code-webview-surfaces)
- [IntelliJ webview surfaces](docs/architecture/webview-bridge-and-hosts.md#intellij-webview-surfaces)

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

IntelliJ persists settings in `PluginSettings` (`claudeReviews.xml`); VS Code declares each as
`pr-pilot.<name>` in `vscode-extension/package.json`. Full summary table, defaults, and behavioral
semantics live in [settings.md](docs/architecture/settings.md); no tokens or API keys are stored on
disk.

## Local data files

State under `~/.pr-pilot/` (`semantic-review.json`, `semantic-worktrees.json`, `pending-prs.json`,
`seen-prs.json`, and `review-outcomes.jsonl`) is owned per module as documented in
[local-data.md](docs/architecture/local-data.md).
