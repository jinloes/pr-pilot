# Settings

Full semantics of the persisted settings summarized in
[ARCHITECTURE.md](../../ARCHITECTURE.md#settings-persistence). IntelliJ stores them in
`PluginSettings` (`claudeReviews.xml`). VS Code contributes each as `pr-pilot.<name>` in
`vscode-extension/package.json`, except the hidden `reviewGuidanceGlobs`. No API keys or tokens
are written to disk.

## Fields

- `githubBaseUrl` (default `https://github.com`)
- `notificationsEnabled` (default `false`)
- `notifyReviewRequested` (default `true`)
- `notifyStarredRepos` (default `false`)
- `notificationPollMinutes` (default `5`)
- `reviewModel` (default `""`)
- `reviewModelCopilot` (default `"claude-sonnet-4.6"`)
- `reviewProvider` (default `"claude"`; values `claude|copilot`)
- `reviewEffort` (default `"high"`; values `none|low|medium|high|xhigh|max`)
- `copilotInheritMcp` (default `false`) — explicit capability elevation that injects MCP servers
  read from the user's trusted Copilot config (`<configDir>/mcp-config.json`) via `setMcpServers`,
  while retaining the read/MCP-only permission allowlist. On-disk config discovery stays disabled,
  so the untrusted PR-branch worktree's repo-local `.mcp.json` is never loaded. Copilot-only.
- `copilotAutoEnableMcpOnReview` (default `false`) — review-only opt-in that forces MCP enablement
  for Copilot review generation even when `copilotInheritMcp` is off; chat still follows
  `copilotInheritMcp`. Copilot-only.
- `copilotConfigDir` (default `""`) — optional override of the Copilot config directory used to read
  trusted MCP servers; empty uses the CLI default (`~/.copilot`). Copilot-only.
- `reviewFocusAreas` (default `""`) — default reviewer focus areas; a non-empty per-review override
  takes precedence.
- `reviewCustomInstructions` (default `""`) — default extra review instructions; a non-empty
  per-review override takes precedence.
- `reviewGuidanceProfiles` (default `[]`) — named reusable bundles of focus areas and custom
  instructions. Profiles use stable IDs so renames preserve selection. Invalid, duplicate, or
  missing IDs fall back to the built-in legacy defaults rather than partially applying a profile.
  The `guidanceGlobs` profile field remains readable, is preserved on edits, and is sent to the
  engine, but is not exposed in settings UI.
- `repositoryReviewInstructions` (default `{}`; VS Code `pr-pilot.repositoryReviewInstructions`) —
  review instructions remembered per repository, keyed by lowercase `owner/repo` (at most 200
  repositories and 10,000 characters each; `RepositoryReviewInstructions` in IntelliJ,
  `repositoryInstructions.ts` in VS Code). At generation each host prepends the entry for the PR's
  repository to the engine's existing `customInstructions` input under an
  `Instructions remembered for owner/repo:` heading, ahead of the per-review override or the
  resolved profile/default, so no engine or wire field exists for it. Hosts send the entry on every
  `draftLoaded` as `repositoryInstructions`; the review pane's "Remember for this repository" posts
  `saveRepositoryInstructions` and receives `repositoryInstructionsSaved` (normalized text, empty
  when forgotten) or `repositoryInstructionsSaveError`. Both settings UIs list remembered
  repositories for editing and Forget. Keys ignore the GitHub host, so same-named repositories on
  github.com and an enterprise host share an entry.
- `activeReviewGuidanceProfileId` (default `""`) — selected profile ID; blank selects the built-in
  defaults stored in `reviewFocusAreas`, `reviewCustomInstructions`, and `reviewGuidanceGlobs`.
  Keeping those fields as the built-in profile makes upgrades migration-free.
- `reviewSelfCritique` (default `true`) — runs a second validation pass
  (`ReviewCritiquePrompts.buildCritiquePrompt`) that re-checks every finding against a contract
  index derived from the changed files and the same context sections the first pass saw, dropping
  misattributed, unsupported, and CI-duplicated comments. On by default because a misattributed
  comment costs the reviewer more than the extra pass does; disabling it roughly halves review
  latency. Shared by both providers.
- `reviewSupervisorEnabled` (default `true`; labeled "Re-inspect coverage gaps") — checks the
  primary inspection ledger for unreviewed high-risk hunks and uninspected files, then runs bounded
  read-only follow-ups: one for selected hunks and up to five whole-file re-reviews. Shared by both
  providers.
- `reviewRulesDirectory` (default `""`; VS Code `pr-pilot.reviewRulesDirectory`; benchmark
  `--rules-dir`) — an absolute local folder of the reviewer's own rules. The engine
  (`LocalReviewRules`) reads `.md`/`.yaml`/`.yml` regular files up to four levels deep, skipping
  symbolic links inside the folder, files over 32 KB, and non-UTF-8 files, and keeps at most 50
  files sorted by path. Structured YAML rules (`name`, `description`, `trigger`, `prompt`) are
  selected by one trigger-matching call; every other file is always selected. `ReviewRulesPass`
  applies each selected rule in its own read-only agent (3 at a time, at most 25 per review) and
  merges the findings into the candidate; rules never enter the repository guidance. Unreadable or
  relative paths add nothing. It is carried on
  `GenerateReviewParams.rulesDirectory` / `PRReviewRequest.rulesDirectory`.
- `reviewSecondReviewerModel` (default `""`; VS Code `pr-pilot.reviewSecondReviewerModel`) —
  optional Copilot model run in parallel as a second reviewer whose findings are merged and
  cross-validated. Blank disables it. Uses the primary's effort when the primary is Copilot,
  otherwise Copilot's default effort. Sent to the engine as `secondReviewerModel`; ignored for
  IntelliJ-assisted deep review. Shared by both hosts.
- `experimentalIntellijAssistedReview` (default `false`; VS Code
  `pr-pilot.experimentalIntellijAssistedReview`) — experimental opt-in for IntelliJ-assisted review.
  Each host sends it to the webview as `intellijAssistedEnabled` on every `draftLoaded` and
  `prListLoaded` message. Anything other than `true` hides the IntelliJ-assisted checkbox and the
  Advanced-options retained-worktree button and resets a checked box; the `prListLoaded` value also
  hides the no-selection "Review maintenance" disclosure and the review-pane "Retained IntelliJ
  review worktrees" context-menu item unless an assisted setup is active. Retained worktrees are
  therefore removable again only after the setting is turned back on, which both hosts' setting
  hints state. While off, a `generateReview` request with `intellijAssisted: true` is rejected with
  a `reviewError` and never downgraded to an ordinary review. Shared by both hosts.
- `reviewGuidanceGlobs` (hidden) — retained in host settings and profiles. It is not contributed as
  a public VS Code setting or rendered in either settings UI, but the resolved value is sent to the
  engine as `guidanceGlobs`, which reads matching files from the PR's base commit only (never the PR
  worktree).

## Guidance profiles

Profiles are host-owned settings/UI state, not an engine capability. Each host resolves the active
profile into the existing engine inputs; focus areas, custom instructions and guidance globs are all
sent; the engine reads glob matches from the base commit in addition to the guidance it discovers
itself. Per-review non-empty focus/custom-instruction overrides still take precedence over the
resolved active profile.

## VS Code defaults

The VS Code equivalents live in `vscode-extension/package.json` under `contributes.configuration`.
Each is declared twice — once as the contribution default, once as the fallback in `extension.ts`'s
reader — and VS Code only honors the former, so a mismatch is silent.
`vscode-extension/test/settingDefaults.test.ts` asserts the two agree for every boolean setting.
