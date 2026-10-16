# Code map: `webview/`

Part of the [code map](../../CODEMAP.md), which holds the task-to-code index and cross-module paths.
Paths are relative to `webview/`.

Shared Vite/React/TypeScript UI used by both IDE hosts.

- `src/App.tsx` - Application state and top-level host workflow.
- `src/bridge/types.ts` - Cross-host message schemas.
- `src/components/` - PR discovery, diff, review, chat, settings-adjacent UI, and reusable controls.
- `src/components/PRList/PRList.tsx` - Pull-request discovery state, host-message ingestion,
  shared current-repository context, and local text/#number filtering.
- `src/components/PRList/PRListControls.tsx`, `PRListNotices.tsx` - Scope/filter controls and
  exception-only list notices; state controls wrap in narrow, expanded-text layouts.
- `src/components/PRList/PRListItem.tsx`, `PRStatusBadges.tsx` - Title-first rows and readable
  draft/notification/review-freshness badges; compact metadata only when shared repository context
  matches, with repository identity retained for accessibility and out-of-context rows.
- `src/components/PRList/ReadinessCoach.tsx` - Compact persisted first-success confirmation;
  unverified provider sign-in uses localized, neutral information styling.
- `src/components/Setup/` - App-level prerequisite recovery UI and the setup reason/action matrix.
- `src/components/ReviewPane/ReviewPane.tsx` - Review feature composition root and public component
  API.
- `src/components/ReviewPane/useReviewController.ts` - Review commands and the view-model/action
  contract, composed from `useReviewHostMessages` (PR-scoped bridge events), `useReviewAutosave`
  (autosave and mutation watchdogs), `useReviewGeneration` (normal/assisted/chunked generation), and
  `useReviewChatEffects` (chat sizing/selection); pure types and helpers in
  `reviewControllerState.ts`.
- `src/components/DiffViewer/DiffViewer.tsx` - Diff viewer composition; rows in
  `DiffViewerRows.tsx`, line mapping/comment grouping in `diffHelpers.ts`, highlighting in
  `diffSyntax.ts`.
- `src/components/ReviewPane/reviewState.ts` - Pure review lifecycle transitions and state
  selectors.
- `src/components/ReviewPane/reviewActivity.ts`, `ReviewActivityLog.tsx` - Timestamped
  review-generation lifecycle/tool activity state and its expandable, privacy-safe timeline.
- `src/components/ReviewPane/ReviewOverrides.tsx`, `ReviewQuality.tsx`, `ReviewContent.tsx`,
  `ReviewFooter.tsx`, and `OrphanComments.tsx` - Feature-private review presentation modules.
- `src/components/ReviewPane/publishBody.ts` - Publish-dialog preview model (general notes,
  unanchored comments, inline count) and the fallback bodies mirrored from
  `DraftReviewMutationService`; tested by `publishBody.test.ts`.
- `src/lib/reviewQuality.ts` - Quality heuristics and in-memory repair suggestions.
- `src/lib/diffCoverage.ts` - Strict mirror of `DiffCoverage.split` plus coverage-gain and budget
  formatting for the coverage banner and chunked-review recommendation.
- `src/lib/autosave.ts` - Draft dirty-check, snapshot, and debounce decisions.
- `src/lib/validateComments.ts` - Inline-comment validation.
- `src/lib/keyboard.ts`, `layout.ts`, `motion.ts` - Shared interaction/layout policies.
- `src/components/DiffViewer/fileNavigation.ts`, `findingNavigation.ts` - Changed-file and
  severity-sorted review-finding navigation.
- `src/components/ReviewPane/` helpers - Chat sizing, comment navigation, and verify prompts.
- `src/components/a11y/LiveStatus.tsx` - Screen-reader announcements.
- `src/i18n/` - Typed English catalog and test pseudo-localization.
- `src/theme/hostTheme.ts` - Host theme application.
- `a11y/` - Playwright and axe end-to-end accessibility scenarios.
- `visual/` - Deterministic visual-regression scenarios: whole-page baselines plus locator-level
  pane baselines with a tighter per-locator tolerance and text assertions for text-critical states.
- Tests: colocated `*.test.ts`/`*.test.tsx` files.
- `scripts/run-logic-tests.mjs` - Portable `test:logic` runner: expands the logic-test globs itself
  so the command also works under `cmd`; Stryker's command runner.
- `scripts/prefix-lcov-paths.mjs` - Rewrites LCOV `SF:` paths to repository-relative `webview/...`
  paths for diff-cover (`test:coverage`).
- `stryker.config.mjs` - Nightly report-only mutation testing of `src/lib` (`test:mutation`): in
  place and incremental.
