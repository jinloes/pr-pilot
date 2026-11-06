# Settings

Full semantics of the persisted settings summarized in
[ARCHITECTURE.md](../../ARCHITECTURE.md#settings-persistence). IntelliJ stores them in
`PluginSettings` (`claudeReviews.xml`). VS Code contributes each as `pr-pilot.<name>` in
`vscode-extension/package.json`. No API keys or tokens are written to disk.

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
  Each profile also carries its own `guidanceGlobs`, which the VS Code settings view edits in the
  same "Repository guidance files" field as the defaults.
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
- `reviewBuiltInRules` (default `true`; VS Code `pr-pilot.reviewBuiltInRules`; benchmark
  `--no-builtin-rules` turns it off) — also runs the structured rules bundled in review-engine's
  `review-rules/` resources. A rules-folder rule with the same name replaces a built-in, and a
  disabled one (`enabled: false`) turns it off. It is carried on the nullable
  `GenerateReviewParams.builtInRules` (omitted means on, so older hosts keep the built-ins) /
  `PRReviewRequest.builtInRules`.
- `reviewSecondReviewerModel` (default `""`; VS Code `pr-pilot.reviewSecondReviewerModel`) —
  optional Copilot model run in parallel as a second reviewer whose findings are merged and
  cross-validated. Blank disables it. Runs at `reviewSecondReviewerEffort`. Sent to the engine as
  `secondReviewerModel`; ignored for IntelliJ-assisted deep review. Shared by both hosts.
- `reviewSecondReviewerEffort` (default `"medium"`; VS Code `pr-pilot.reviewSecondReviewerEffort`,
  values `none|low|medium|high|xhigh|max`) — Copilot reasoning effort for the second reviewer,
  independent of `reviewEffort` and of the primary provider. The pipeline waits for both reviewers
  before merging, so the default sits below `reviewEffort`'s `high` to keep a slower second model
  from holding up the review. Sent as `secondReviewerEffort` only when a second reviewer is set.
  When a host omits it, the engine keeps the earlier rule: a Copilot primary's effort, otherwise
  Copilot's default. Shared by both hosts.
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
- `experimentalVsCodeCodeIntelligence` (default `false`; VS Code
  `pr-pilot.experimentalVsCodeCodeIntelligence`, also in the settings view's Copilot tools section) —
  experimental, Copilot-only. For each review and chat request, VS Code starts a loopback MCP server
  that answers definition, reference, implementation, type, and symbol lookups through VS Code's
  language services over the user's checkout, and sends it to the engine as `hostCodeIntelligence`.
  Read only when the request starts. Ignored for Claude and IntelliJ-assisted deep review. **VS
  Code only:** IntelliJ has no equivalent setting yet. See
  [providers.md](providers.md#host-code-intelligence-over-mcp).
- `reviewGuidanceGlobs` (default `[]`; VS Code `pr-pilot.reviewGuidanceGlobs`, at most 100 entries
  of 500 characters) — extra guidance paths or globs, for repositories whose review rules live
  outside the built-in locations (for example `.review/ai-agent/*.md`). The resolved value (the
  active profile's list, else this default) is sent to the engine as `guidanceGlobs`, which reads
  matching files from the PR's base commit only, never the PR worktree. The VS Code settings view
  edits it as "Repository guidance files", one entry per line. **IntelliJ lag:** `PluginSettings`
  stores and sends it, but the IntelliJ settings page does not lay out its text area yet
  (`ReviewGuidanceEditor.guidanceGlobsArea()` has no caller), so IntelliJ users cannot edit it.

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
