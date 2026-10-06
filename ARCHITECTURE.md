# PR Pilot Architecture

IntelliJ and VS Code extension that lists GitHub Pull Requests and generates AI-powered code reviews using a local AI CLI/runtime (Claude Code or GitHub Copilot, selected per-host in settings).

## Scope

- Keep this file focused on stable architecture and design constraints.
- See `CODEMAP.md` for implementation locations, task entry points, and related tests.
- Put workflow/checklist instructions in `AGENTS.md`.
- Put volatile version inventory in build manifests unless a version is itself an architecture constraint.

## System shape

PR Pilot is a multi-module Java 17 project with two IDE hosts sharing one React webview. `core`,
`github-engine`, and `review-engine` own host-neutral behavior. IntelliJ calls the engines in-process;
VS Code reaches the same capabilities through the stdio JSON-RPC `sidecar`. Host modules own only
IDE lifecycle, settings integration, and bridge wiring.

The shared webview owns the user workflow from PR discovery through review editing and submission.
GitHub credentials remain inside `github-engine`, while provider processes and review semantics
remain inside `review-engine`. Every engine capability must remain reachable from both hosts through
the parity boundary described below.

Visual references:

- [System architecture diagram](diagrams/architecture.md)
- [PR discovery and review-freshness sequence](diagrams/pr-discovery-sequence.md)
- [PR review generation sequence](diagrams/review-generation-sequence.md)

## Key design decisions

Only decisions that encode active constraints future code must respect and are not obvious from source.

Add a new decision as a `###` section in the matching `docs/architecture/*.md` file and an index line here.

**Semantic review** ([semantic-review.md](docs/architecture/semantic-review.md))

- [Source inventory is evidence, not semantic readiness](docs/architecture/semantic-review.md#source-inventory-is-evidence-not-semantic-readiness)
- [Successful import authority is memory-only and pre-armed](docs/architecture/semantic-review.md#successful-import-authority-is-memory-only-and-pre-armed)
- [Opt-in semantic-review lifecycle](docs/architecture/semantic-review.md#opt-in-semantic-review-lifecycle)

**Review pipeline** ([review-pipeline.md](docs/architecture/review-pipeline.md))

- [Boundary compatibility findings are evidence-gated](docs/architecture/review-pipeline.md#boundary-compatibility-findings-are-evidence-gated)
- [Repository guidance confinement](docs/architecture/review-pipeline.md#repository-guidance-confinement)
- [Review guidance, file history and call sites come from the base commit](docs/architecture/review-pipeline.md#review-guidance-file-history-and-call-sites-come-from-the-base-commit)
- [Recall: second reviewer and candidate validation](docs/architecture/review-pipeline.md#recall-second-reviewer-and-candidate-validation)
- [Recall benchmark withholds the reference answers](docs/architecture/review-pipeline.md#recall-benchmark-withholds-the-reference-answers)
- [ReviewBench runs never consult GitHub and delegate scoring](docs/architecture/review-pipeline.md#reviewbench-runs-never-consult-github-and-delegate-scoring)
- [Comment anchoring snaps within a hunk, never across one](docs/architecture/review-pipeline.md#comment-anchoring-snaps-within-a-hunk-never-across-one)
- [Three comment keys exist on purpose](docs/architecture/review-pipeline.md#three-comment-keys-exist-on-purpose)
- [Missing rationale drops a comment, never fabricates one](docs/architecture/review-pipeline.md#missing-rationale-drops-a-comment-never-fabricates-one)
- [Comments on deleted files orphan by design](docs/architecture/review-pipeline.md#comments-on-deleted-files-orphan-by-design)
- [CI annotations are selected by whether they exist, not by whether the check failed](docs/architecture/review-pipeline.md#ci-annotations-are-selected-by-whether-they-exist-not-by-whether-the-check-failed)
- [CI-duplicate suppression is conservative by design](docs/architecture/review-pipeline.md#ci-duplicate-suppression-is-conservative-by-design)
- [Review JSON parsing is self-healing, not all-or-nothing](docs/architecture/review-pipeline.md#review-json-parsing-is-self-healing-not-all-or-nothing)
- [Low confidence is a gate, not a label](docs/architecture/review-pipeline.md#low-confidence-is-a-gate-not-a-label)
- [Prompt-injection hardening](docs/architecture/review-pipeline.md#prompt-injection-hardening)
- [Diff acquisition model](docs/architecture/review-pipeline.md#diff-acquisition-model)
- [Complete review diff coverage](docs/architecture/review-pipeline.md#complete-review-diff-coverage)
- [Large diff visibility](docs/architecture/review-pipeline.md#large-diff-visibility)
- [Review quality gate and chunked review mode](docs/architecture/review-pipeline.md#review-quality-gate-and-chunked-review-mode)
- [Bounded review supervision](docs/architecture/review-pipeline.md#bounded-review-supervision)
- [Comment anchoring policy](docs/architecture/review-pipeline.md#comment-anchoring-policy)
- [Hygiene pass gets a mechanical log inventory](docs/architecture/review-pipeline.md#hygiene-pass-gets-a-mechanical-log-inventory)

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
- [Module boundaries](docs/architecture/hosts-and-sidecar.md#module-boundaries)
- [Engine capability boundary (test-enforced host parity)](docs/architecture/hosts-and-sidecar.md#engine-capability-boundary-test-enforced-host-parity)
- [Sidecar streaming: async requests plus server-push notifications](docs/architecture/hosts-and-sidecar.md#sidecar-streaming-async-requests-plus-server-push-notifications)
- [GitHub engine host wiring](docs/architecture/hosts-and-sidecar.md#github-engine-host-wiring)
- [core: plain Java module](docs/architecture/hosts-and-sidecar.md#core-plain-java-module)
- [Threading model](docs/architecture/hosts-and-sidecar.md#threading-model)
- [Cross-host parity](docs/architecture/hosts-and-sidecar.md#cross-host-parity)
- [First-run onboarding path](docs/architecture/hosts-and-sidecar.md#first-run-onboarding-path)
- [Notification parity](docs/architecture/hosts-and-sidecar.md#notification-parity)
- [Security constraints](docs/architecture/hosts-and-sidecar.md#security-constraints)
- [Repo detection and webview hosting](docs/architecture/hosts-and-sidecar.md#repo-detection-and-webview-hosting)
- [Pinned IntelliJ SDK versus local sandbox](docs/architecture/hosts-and-sidecar.md#pinned-intellij-sdk-versus-local-sandbox)
- [VS Code extension development target repo](docs/architecture/hosts-and-sidecar.md#vs-code-extension-development-target-repo)

**GitHub, drafts and worktrees** ([github-drafts-worktrees.md](docs/architecture/github-drafts-worktrees.md))

- [Pending review index coordination](docs/architecture/github-drafts-worktrees.md#pending-review-index-coordination)
- [Authenticated-user review freshness](docs/architecture/github-drafts-worktrees.md#authenticated-user-review-freshness)
- [Review submission uses a synchronous webview lock](docs/architecture/github-drafts-worktrees.md#review-submission-uses-a-synchronous-webview-lock)
- [The review worktree is pinned to the PR head commit, not the branch tip](docs/architecture/github-drafts-worktrees.md#the-review-worktree-is-pinned-to-the-pr-head-commit-not-the-branch-tip)
- [The review worktree is not indexed by the IDE](docs/architecture/github-drafts-worktrees.md#the-review-worktree-is-not-indexed-by-the-ide)
- [PR discovery scope](docs/architecture/github-drafts-worktrees.md#pr-discovery-scope)
- [Worktree-based PR context](docs/architecture/github-drafts-worktrees.md#worktree-based-pr-context)
- [GitHub API resilience policy](docs/architecture/github-drafts-worktrees.md#github-api-resilience-policy)
- [Draft review storage semantics](docs/architecture/github-drafts-worktrees.md#draft-review-storage-semantics)
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
- [VS Code webview surfaces](docs/architecture/webview.md#vs-code-webview-surfaces)
- [IntelliJ webview surfaces](docs/architecture/webview.md#intellij-webview-surfaces)

## Settings persistence

`PluginSettings` (`claudeReviews.xml`) stores:

- `githubBaseUrl` (default `https://github.com`)
- `notificationsEnabled` (default `false`)
- `notifyReviewRequested` (default `true`)
- `notifyStarredRepos` (default `false`)
- `notificationPollMinutes` (default `5`)
- `reviewModel` (default `""`)
- `reviewModelCopilot` (default `"claude-sonnet-4.6"`)
- `reviewProvider` (default `"claude"`; values `claude|copilot`)
- `reviewEffort` (default `"high"`; values `none|low|medium|high|xhigh|max`)
- `copilotInheritMcp` (default `false`) — explicit capability elevation that injects MCP servers read from the user's trusted Copilot config (`<configDir>/mcp-config.json`) via `setMcpServers`, while retaining the read/MCP-only permission allowlist. On-disk config discovery stays disabled, so the untrusted PR-branch worktree's repo-local `.mcp.json` is never loaded. Copilot-only.
- `copilotAutoEnableMcpOnReview` (default `false`) — review-only opt-in that forces MCP enablement for Copilot review generation even when `copilotInheritMcp` is off; chat still follows `copilotInheritMcp`. Copilot-only.
- `copilotConfigDir` (default `""`) — optional override of the Copilot config directory used to read trusted MCP servers; empty uses the CLI default (`~/.copilot`). Copilot-only.
- `reviewFocusAreas` (default `""`) — default reviewer focus areas; a non-empty per-review override takes precedence.
- `reviewCustomInstructions` (default `""`) — default extra review instructions; a non-empty per-review override takes precedence.
- `reviewGuidanceProfiles` (default `[]`) — named reusable bundles of focus areas and custom instructions. Profiles use stable IDs so renames preserve selection. Invalid, duplicate, or missing IDs fall back to the built-in legacy defaults rather than partially applying a profile. The `guidanceGlobs` profile field remains readable, is preserved on edits, and is sent to the engine, but is not exposed in settings UI.
- `repositoryReviewInstructions` (default `{}`; VS Code `pr-pilot.repositoryReviewInstructions`) — review instructions remembered per repository, keyed by lowercase `owner/repo` (at most 200 repositories and 10,000 characters each; `RepositoryReviewInstructions` in IntelliJ, `repositoryInstructions.ts` in VS Code). At generation each host prepends the entry for the PR's repository to the engine's existing `customInstructions` input under an `Instructions remembered for owner/repo:` heading, ahead of the per-review override or the resolved profile/default, so no engine or wire field exists for it. Hosts send the entry on every `draftLoaded` as `repositoryInstructions`; the review pane's "Remember for this repository" posts `saveRepositoryInstructions` and receives `repositoryInstructionsSaved` (normalized text, empty when forgotten) or `repositoryInstructionsSaveError`. Both settings UIs list remembered repositories for editing and Forget. Keys ignore the GitHub host, so same-named repositories on github.com and an enterprise host share an entry.
- `activeReviewGuidanceProfileId` (default `""`) — selected profile ID; blank selects the built-in defaults stored in `reviewFocusAreas`, `reviewCustomInstructions`, and `reviewGuidanceGlobs`. Keeping those fields as the built-in profile makes upgrades migration-free.
- `reviewSelfCritique` (default `true`) — runs a second validation pass (`ClaudeService.buildCritiquePrompt`) that re-checks every finding against a contract index derived from the changed files and the same context sections the first pass saw, dropping misattributed, unsupported, and CI-duplicated comments. On by default because a misattributed comment costs the reviewer more than the extra pass does; disabling it roughly halves review latency. Shared by both providers.
- `reviewSupervisorEnabled` (default `true`; labeled "Re-inspect coverage gaps") — checks the primary
  inspection ledger for unreviewed high-risk hunks and uninspected files, then runs bounded
  read-only follow-ups: one for selected hunks and up to five whole-file re-reviews. Shared by both
  providers.
- `reviewRulesDirectory` (default `""`; VS Code `pr-pilot.reviewRulesDirectory`; benchmark
  `--rules-dir`) — an absolute local folder of the reviewer's own rules. The engine
  (`LocalReviewRules`) reads `.md`/`.yaml`/`.yml` regular files up to four levels deep, skipping
  symbolic links inside the folder, files over 16 KB, and non-UTF-8 files, keeps at most 50 files and
  32 KB sorted by path, and appends them to the repository guidance as `## local-rules/<path>`
  sections so the critique can cite them. Unreadable or relative paths add nothing. It is carried on
  `GenerateReviewParams.rulesDirectory` / `PRReviewRequest.rulesDirectory`.
- `reviewSecondReviewerModel` (default `""`; VS Code `pr-pilot.reviewSecondReviewerModel`) — optional
  Copilot model run in parallel as a second reviewer whose findings are merged and cross-validated.
  Blank disables it. Uses the primary's effort when the primary is Copilot, otherwise Copilot's
  default effort. Sent to the engine as `secondReviewerModel`; ignored for IntelliJ-assisted deep
  review. Shared by both hosts.
- `experimentalIntellijAssistedReview` (default `false`; VS Code `pr-pilot.experimentalIntellijAssistedReview`) —
  experimental opt-in for IntelliJ-assisted review. Each host sends it to the webview as
  `intellijAssistedEnabled` on every `draftLoaded` and `prListLoaded` message. Anything other than
  `true` hides the IntelliJ-assisted checkbox and the Advanced-options retained-worktree button and
  resets a checked box; the `prListLoaded` value also hides the no-selection "Review maintenance"
  disclosure and the review-pane "Retained IntelliJ review worktrees" context-menu item unless an
  assisted setup is active. Retained worktrees are therefore removable again only after the setting
  is turned back on, which both hosts' setting hints state. While off, a `generateReview` request with
  `intellijAssisted: true` is rejected with a `reviewError` and never downgraded to an ordinary
  review. Shared by both hosts.
- `reviewGuidanceGlobs` (hidden) — retained in host settings and profiles. It is not contributed as a public VS Code setting or rendered in either settings UI, but the resolved value is sent to the engine as `guidanceGlobs`, which reads matching files from the PR's base commit only (never the PR worktree).

Profiles are host-owned settings/UI state, not an engine capability. Each host resolves the active profile into the existing engine inputs; focus areas, custom instructions and guidance globs are all sent; the engine reads glob matches from the base commit in addition to the guidance it discovers itself. Per-review non-empty focus/custom-instruction overrides still take precedence over the resolved active profile.

The VS Code equivalents live in `vscode-extension/package.json` under `contributes.configuration`. Each is declared twice — once as the contribution default, once as the fallback in `extension.ts`'s reader — and VS Code only honors the former, so a mismatch is silent. `vscode-extension/test/settingDefaults.test.ts` asserts the two agree for every boolean setting.

No API keys or tokens are written to disk.

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

IntelliJ-only (`intellij-plugin`'s `PendingReviewIndex`/`SeenPRSet`); VS Code persists the equivalent state via extension `globalState` instead (see Notification parity above).

| Path | Purpose |
|------|---------|
| `~/.pr-pilot/pending-prs.json` | Index of PRs with saved drafts (owner, repo, number, title, savedAt, headSha) |
| `~/.pr-pilot/pending-prs.json.corrupt-<timestamp>` | User-triggered quarantine copy of an unreadable draft index; GitHub drafts are unchanged |
| `~/.pr-pilot/seen-prs.json` | Set of `owner/repo#number` strings already notified about |
| `~/.pr-pilot/review-outcomes.jsonl` | Append-only outcome log: one JSON object per generated/submitted comment (`ReviewOutcomeLog`, engine-owned), including category but never prose or paths, written by both hosts on submit |

### Outcome logging is stateless because IntelliJ does not use `ReviewSessionService`
`reviews/recordOutcome` takes **both** the generated and the submitted comment sets from its caller rather than the engine remembering the generated review between calls. The obvious design — snapshot it in `ReviewSessionService.generate` — silently fails on IntelliJ, which calls `ClaudeService`/`CopilotService` in-process through `IntellijClaudeService` and never touches `ReviewSessionService` at all (that type is sidecar-only). Each host therefore retains the half it was previously discarding: IntelliJ keeps `generatedResult` because `handleSaveDraft` overwrites `lastResult` with the edits, and VS Code keeps `editedReviewResult` because it only ever sees the edits in the `saveDraft` message. Classification, fingerprinting, and the base `promptVersion` stay engine-owned, so the hosts hold UI state and implement no logic. The host supplies only the snapshotted supervisor boolean; the engine uses it to suffix its own version and keep the two pipeline modes separable. A draft **loaded from GitHub** is never logged: it was not generated in this session, so diffing against it would record every comment as `kept`.

### The outcome log stores hashes, never comment text or file paths
`ReviewOutcomeLog` writes only `(recordedAt, promptVersion, provider, model, commentFingerprint, outcome, type, severity, category, confidence)`. The fingerprint is a SHA-256 prefix over `file + line + whitespace-normalized body`, which is enough to correlate the same finding across prompt versions — the log's entire purpose — without persisting review prose or repository structure to disk. It is the only append-mode file under `~/.pr-pilot`: the whole-file JSON stores use tmp+`ATOMIC_MOVE`, but rewriting a growing log on every submit does not scale, so this one uses `O_APPEND` with a `MAX_LOG_BYTES` cap because nothing prunes it. Every failure is logged and swallowed — instrumentation must never break a review submission.
