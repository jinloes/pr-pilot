# Code map: `review-engine/`

Part of the [code map](../../CODEMAP.md), which holds the task-to-code index and cross-module paths.
Paths omit `src/main/java/com/jinloes/prpilot/` and equivalent test roots where the module context
makes them unambiguous; other paths are relative to the module directory.

Host-neutral provider invocation, prompt construction, review parsing, worktrees, and repository
guidance.

- `engine/ReviewEngineApi.java` - Complete review capability surface and JSON-RPC wire-name map.
- `engine/ReviewSessionService.java` - Provider dispatch and operation-scoped cancellation.
- `review/ClaudeService.java` - Claude CLI execution, tool-use status, and process lifecycle.
  Tests: `ClaudeServiceTest`.
- `review/ClaudeStreamParser.java` - Stateless parser for the CLI's `stream-json` output: final
  review text, the error subtype and session ID that drive `--resume`, and streamed status/chunk
  callbacks. Tests: `ClaudeStreamParserTest`.
- `review/ReviewPrompts.java` - First-pass review prompt assembly, `PROMPT_VERSION`, and the shared
  section builders (PR metadata, annotated diff, context and semantic sections, untrusted-content
  escaping and truncation) that every stage prompt reuses. Tests: `ReviewPromptsTest`,
  `ReviewPromptsSecurityTest`, `ReviewPromptsScopeTest`, and `ReviewPromptsRecallAndHistoryTest`,
  sharing `ReviewPromptsTestSupport`.
- `review/ReviewInstructions.java` and `ReviewOutputContract.java` - First-pass instruction text
  (preamble, bug-hunt checklist, Pass A/B, recall variant) and the JSON response contract appended
  by every finding-emitting stage.
- `review/ReviewHygienePrompts.java`, `ReviewRulePrompts.java`, and `ReviewCritiquePrompts.java` -
  Recall-mode hygiene pass, review-rule selection and per-rule review, and self-critique (with
  reviewer corroboration) prompts. Tests: `ReviewRulePromptsTest`, `ReviewCritiquePromptsTest`;
  hygiene coverage lives in `ReviewPromptsRecallAndHistoryTest`.
- `review/ReviewChatPrompts.java` - PR chat and focused-chat prompts; `CHAT_PERSONA` and
  `buildFocusedChatPrompt` are mirrored in `vscode-extension/src/claude.ts`. Tests:
  `ReviewChatPromptsTest`.
- `review/DiffLineAnnotator.java` - New-side line-number annotation of unified diffs for prompts.
  Tests: `DiffLineAnnotatorTest`.
- `review/ReviewResultParser.java` - Review JSON parsing, comment repair, and category validation
  including compatibility findings. Tests: `ReviewResultParserTest`.
- `review/CopilotService.java` - Copilot SDK execution with the same review API. Tests:
  `CopilotServiceTest` and `CopilotServiceCancellationTest`, sharing `CopilotServiceTestSupport`.
- `review/CopilotSdkRuntime.java` - Seam over the official Copilot Java SDK: client and session
  requests, the runtime interfaces tests fake, and the deny-by-default permission handler. Tests:
  `CopilotSdkRuntimeTest`.
- `review/PromptCompleter.java` - One-shot provider-neutral prompt completion used by development
  tools such as the recall benchmark.
- `review/ChunkedReviewService.java` - Shared diff batching, contract-index generation, and
  mandatory global reconciliation.
- `review/ReviewPipelineService.java` - Shared primary/chunked orchestration, base-commit context
  enrichment, optional parallel Copilot second reviewer, bounded supervision, the review-rules pass,
  the recall-mode hygiene pass, recall-candidate critique, opt-in dropped-finding statuses,
  cancellation checkpoints, fallback behavior, final CI suppression, and the final comment cap.
  Tests: `ReviewPipelineServiceTest`, `ReviewPipelineRecallTest`,
  `ReviewPipelineSecondReviewerTest`, `ReviewPipelineRulesAndHygieneTest`,
  `ReviewPipelineBaseCommitTest`, and `ReviewPipelineAuthorityTest`, sharing
  `ReviewPipelineTestSupport`.
- `review/ReviewProviderExecutor.java` - Provider seam that the pipeline and `ReviewRulesPass`
  drive (first pass, later-stage prompt completions, cooperative cancellation), with Claude and
  Copilot adapters.
- `review/ReviewPipelineFindings.java` - Post-validation bookkeeping: restores the hygiene and rule
  findings validation dropped, reports dropped findings when asked, and strips reviewer labels no
  candidate earned from rule findings. Tests: `ReviewPipelineFindingsTest`.
- `review/BaseCommitContext.java` - Trusted guidance files (configured globs, then defaults),
  changed-file commit history and textual call sites read only from the PR base commit's git
  objects, time-bounded and fail-open. Tests: `BaseCommitContextTest`.
- `review/LanguageChecklists.java` - Per-language Pass B checks (Java, Kotlin, JS/TS, Python, Go,
  Rust) added only for languages in the diff. Tests: `LanguageChecklistsTest`.
- `review/LocalReviewRules.java` - Bounded loader for the reviewer's `reviewRulesDirectory`:
  structured YAML rules (name, description, trigger, prompt) and always-selected unstructured files.
  Tests: `LocalReviewRulesTest`.
- `review/ReviewRulesPass.java` - Trigger selection call plus one read-only agent per selected rule
  (3 concurrent, 25 cap, 12-minute deadline); findings get a ` (rule: <name>)` rationale suffix.
  Tests: `ReviewRulesPassTest`; pipeline wiring in `ReviewPipelineServiceTest` and
  `ReviewPipelineRulesAndHygieneTest`.
- `review/ChangedSymbols.java` - Extracts the existing declarations a diff changes (removed-line
  declarations, then hunk-header context) for the call-site search. Tests: `ChangedSymbolsTest`.
- `review/ChangedLogStatements.java` - Mechanically extracts the production log statements a diff
  adds or edits (multi-line calls included) into the hygiene pass's `<changed_log_statements>`
  inventory. Tests: `ChangedLogStatementsTest`.
- `review/InspectionManifest.java`, `ReviewPassParser.java`, `InspectionLedger.java`, and
  `EvidenceRef.java` - Stable changed targets plus validated inspection/evidence accounting.
- `review/ReviewCoverageAnalyzer.java`, `CoverageGap.java`, `ReviewSupervisorPrompts.java`, and
  `FollowUpDirective.java` - High-risk hunk and uncovered-file gap detection, hunk follow-up
  selection, and batched whole-file re-review directives.
- `review/ReviewAnchorValidator.java` and `ReviewResultMerger.java` - Changed-line filtering,
  baseline/follow-up/second-reviewer deduplication, low-confidence candidate removal, and the final
  cap.
- `review/ReviewerAttribution.java` - Second-reviewer near-duplicate collapse and reviewer `sources`
  re-attachment after critique. Tests: `ReviewerAttributionTest`,
  `ReviewPipelineSecondReviewerTest`, `ReviewPipelineFindingsTest`.
- `review/ReviewSuggestionGuard.java` - Final pipeline step that clears unsafe `suggestedChange`
  values against the anchored new-side line (`InspectionManifest.newSideLineText`). Tests:
  `ReviewSuggestionGuardTest`, `ReviewPipelineServiceTest`.
- `review/CancellationToken.java` - Shared cancellation state.
- `review/BoundedProcessRunner.java` - Bounded subprocess lifecycle and output draining;
  opt-in owned-tree termination/waiting and separate stderr rejection for inventory launches.
- `review/SourceInventoryFiles.java` - Secure no-follow full-leaf enumeration and bounded
  source/Git-object hashing; unavailable secure traversal blocks.
- `review/SourceInventoryClient.java` - Explicit trusted `Launch`, public
  `collect(root, expectedHead)` parent launcher and `--worker`/`--capture-settings` main; fixed
  ijctl transport, strict v2 native/worker envelopes, physical identity and settings fingerprints,
  independent physical/model reconciliation and repeated Git/source hashing. Not exposed over engine
  RPC or connected to review generation.
- `:review-engine:sourceInventoryWorkerJar` - Minimal plain runnable Jar at
  `review-engine/build/libs/pr-pilot-source-inventory-worker.jar`; inventory/runner plus Jackson
  only. Client and actual SDK-wrapper tests depend on this packaged artifact, not a worker test
  classpath.
- `review/SemanticRuntime.java` - Owner-controlled schema-1 launch configuration, Node/ijctl
  version enforcement, classloader-resource digest verification and owned worker extraction.
  `SemanticRuntimeTest` exercises open/close, forked settings, unsupported macOS JDK17,
  ordinary JAR and physically nested resource streams; actual installed Boot loading is separate.
- `review/CopilotModelDiscovery.java` - Copilot model discovery from the live account catalog
  (help-config fallback), cached with refresh-on-open.
- `review/GitWorktreeService.java` - Temporary PR-head worktree lifecycle.
- `review/RepoGuidelinesReader.java` - Bounded repository-guidance discovery.
- `review/BinaryLocator.java` - Provider binary-path probing.
- `review/ProviderSetupProbe.java` - Bounded provider authentication readiness for onboarding.
- `review/stream/` - Jackson DTOs for Claude stream-json events.
- Tests: `review-engine/src/test/java/com/jinloes/prpilot/`.
