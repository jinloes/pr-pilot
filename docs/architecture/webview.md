# Webview

Key design decisions moved from [ARCHITECTURE.md](../../ARCHITECTURE.md). Each section encodes an
active constraint future code must respect.

### Webview styling

All webview UI uses shadcn/ui + Tailwind CSS. Avoid ad-hoc CSS modules/inline layout styles.
`DiffViewer.css` is the only hand-crafted CSS exception for diff-table specifics. Use semantic
status tokens (`text-status-*`, `bg-status-*/10`, `border-status-*/50`) rather than hardcoded
palette classes. Use `text-status-issue` for destructive or problem text; `destructive` is a fill
color and fails as text on dark neutrals. `src/theme/contrast.test.ts` pins the red status text
tokens at 4.5:1 or more on every surface where the webview places them (flat surfaces, `accent`
highlights, `muted`/`primary` state tints, and their own 5–10% chip tints), so a new state tint or
token value must keep that matrix passing. Theme colors are semantic CSS variables selected by
host-provided `light`, `dark`, `highContrastLight`, or `highContrastDark` bridge state; do not infer
the IDE theme from browser media queries alone.

The shared webview switches from split panes to explicit list/review navigation below 640px, while
`DiffViewer` sizes its side-by-side file/finding rail from its own `@container diff-viewer
(min-width: 760px)` width (collapsing the stacked list by default below 760px until a tab is
clicked). Pane separators must remain operable by pointer and keyboard, keep ARIA values within
viewport-derived bounds, and honor reduced-motion preferences. Both `<main>` and the pane shells
use `overflow: clip` (and `review-scroll-body` uses `overflow-x: clip`), and pane contents grow with
`flex: 1` plus `min-height: 0`; do not replace this with `overflow: hidden` or a nested `height:
100%` chain because browsers still programmatically scroll `overflow: hidden` ancestors horizontally
on wide diff tables, and IntelliJ's JCEF Chromium can resolve a flex-stretched parent's
percentage-height child to content height. Descendant auto-scroll must update its local scroll
container directly rather than call `scrollIntoView`. Empty `ChatPane` collapses to a compact
single-row composer (`EMPTY_CHAT_HEIGHT`) until messages or streaming content arrive, and draft
regeneration overrides live inside the `Regenerate` confirmation dialog rather than below the diff.

Radix menu actions that launch an alert dialog must defer opening the dialog until the menu's
`onCloseAutoFocus` phase. Mounting the second modal directly from `DropdownMenuItem.onSelect` can
leave the closing menu's focus/pointer guards active in embedded JCEF, making the new dialog appear
unresponsive.

Embedded webviews must not use browser-native `alert`, `confirm`, or `prompt` dialogs. The shared
React surface uses the existing Radix dialog primitives, while the standalone VS Code settings
webview uses in-DOM dialogs with explicit focus entry, trapping, restoration, and keyboard
cancellation.

The IntelliJ host uses JCEF off-screen rendering (OSR) and attaches its layout-managed component
directly to the editor-tab container. Do not replace this with heavyweight native rendering or call
`CefBrowser.wasResized` during ordinary Swing layout: on macOS, the native child window can retain
an intermediate height and clip the webview. JCEF OSR can also retain stale pixels after DOM
elements move without changing the browser viewport; dynamic webview geometry changes must send
`webviewLayoutChanged` so IntelliJ can debounce a targeted `CefBrowser.invalidate` after React
commits the layout. VS Code validates and consumes the same parity message but needs no host
repaint.

### Webview accessibility tooling
Webview development runs dev-only runtime accessibility scans via `@axe-core/react` in `main.tsx` so
missing labels/roles and semantic issues surface early in local runs. ESLint also applies
`eslint-plugin-jsx-a11y` rules.

CI runs full-page Playwright + axe scenarios (`npm run test:a11y`) using `playwright.a11y.config.ts`
and fails on any reported violation. Deterministic screenshot scenarios use
`playwright.visual.config.ts` and gate both CI and release. Text-critical states (discovery with no
selection, no draft, wide generation, and narrow selected review) also take a locator-level
screenshot of the relevant pane with a tighter per-locator tolerance plus explicit text assertions,
because the whole-page 1% tolerance cannot detect copy changes; never raise the global tolerance.
Their committed baselines are canonical Ubuntu/Chromium artifacts generated with the pinned
Playwright Docker image documented in `AGENTS.md`; native runs on other operating systems are not
comparable because font rasterization is platform-sensitive. Pseudo-localization is test-only and
enabled with `?locale=pseudo` to expose narrow-layout overflow.

### Chat pane structured verify/fix results
The "Verify" and "Suggest fix" per-comment actions
(`buildVerifyCommentPrompt`/`buildExampleFixPrompt` in `ReviewPane/verifyPrompt.ts`) instruct the
model to return only a bare JSON object (no prose) matching one of two fixed schemas. Both treat the
supplied diff excerpt as primary evidence but may use provider read-only file tools against the
detached PR-head worktree when the excerpt is insufficient; they must not use shell, write, network,
or paths outside that worktree. The excerpt is a single hunk trimmed to ±6 changes around the
comment, so restricting "Suggest fix" to it made the model return `examplePatch: null` whenever the
fix sat in an unseen part of the method. Verification records inspected repository-relative
files/lines or symbols in `evidence`, which the UI renders so the reviewer can see what was checked
without exposing tool arguments or file contents; the example fix cites them in `why`. `ChatPane` treats every assistant reply as
potentially structured: `structuredResult.ts`'s `parseStructuredResult` tries to parse the content
(tolerating a stray ```` ```json ```` fence some models add despite instructions) against the verify
schema (`verdict`/`why`/`evidence`/`action`/`replacementComment`) and the example-fix schema
(`approach`/`examplePatch`/`why`/`risks`/`testUpdates`/`missingContext`); a match renders a
dedicated card (verdict badge, why, evidence, suggested replacement, etc.) instead of the raw JSON
string being passed through the Markdown renderer, which previously showed the reviewer a wall of
escaped-quote JSON text. Ordinary free-form chat replies simply fail both schema checks and fall
back to the normal Markdown bubble. `parseStructuredResult` is applied both to finalized
`chatResponse` messages and to the live `streaming` buffer (accumulated from `chatChunk`
notifications) — without the latter, a completed structured JSON reply would render as raw escaped
JSON text (with the streaming cursor still attached) for the entire duration the chunks are
arriving, since the buffer only becomes valid JSON once the last chunk lands. If either prompt's
JSON schema changes, `structuredResult.ts`'s parser and field rendering must be updated in lockstep.

### Verify verdicts apply to the comment that was verified, not the latest one
"Verify" builds a prompt for one comment and dispatches it through the chat pane. Because a reviewer
can verify several comments before acting on any verdict, `ReviewPane` tags each request with an
opaque token bound to that comment; `ChatPane` copies the token onto the reply it produces and hands
it back with the parsed `VerifyResult`. Applying resolves the target via `resolveVerifyTarget` —
object identity first, then `(file, line, body)` — and **no-ops when the comment was since deleted
or its body edited**, because applying a stale verdict to a different comment is worse than dropping
it. Only `delete`, and `revise` with non-blank replacement text, render an Apply button; `keep`
changes nothing, so offering one would be misleading.

### Suggest fix patches attach as a collapsed diff, not a suggestion block
"Add patch to comment" on a Suggest fix card appends the example patch to the comment it was
requested for, located with the same token and `resolveVerifyTarget` rules as verify verdicts. A
GitHub ```` ```suggestion ```` block replaces only the anchored line range, and AI patches usually
span several hunks, so `lib/attachPatch.ts` appends a `<details><summary>Suggested patch</summary>`
block with the fenced diff instead; re-attaching replaces it. The single-line `suggestedChange` is
kept. The webview's Markdown renderer drops raw HTML (adding `rehype-raw` would let model text inject
markup), so `DiffViewerRows` splits the block off with `splitAttachedPatch` and renders a real
`<details>` element; the header string is the split key and must not change. Patches over
`MAX_ATTACHED_PATCH_CHARS` (12,000) are refused with an explanation because every comment body is
also base64-encoded into the draft review body, which GitHub caps at 65,536 characters.

### Chat replies become comments by picking a line
A chat reply carries no file or line, so "Add to review" never guesses an anchor. It puts the diff
in placement mode (`chatCommentDraft` in `useReviewController`, `commentPlacement` on `DiffViewer`):
a banner asks for a line, the next gutter click opens the new-comment editor prefilled with the
reply, and adding the comment ends placement. Cancelling that editor keeps placement active so a
mis-click costs nothing; the banner's "Cancel placement" or Escape (with no editor open) ends it.
The button is offered only in `draftPresent` and `reviewUnsaved`, the states whose diff is
editable, and never on errors or structured verify/fix cards, which have their own actions.

### Incremental review button and scope banner
The no-draft generation card shows a secondary "Review changes since last review" button
(`generate-incremental-review`) only when the selected PR's `reviewStatus` is
`UPDATED_SINCE_REVIEW`. It is disabled when the provider is unavailable. It sends `generateReview`
with `incremental: true` and no `diff`, `chunkedReview` or `intellijAssisted`, regardless of chunked
mode. Bridge validation drops a malformed `reviewScope` but keeps the rest of the `reviewResult`.
The scope is stored with the result, carried through a draft save, and cleared on a new generation
or a PR change. `ReviewScopeBanner` states either that only changes since the given short SHA were
reviewed, or why the review fell back to the full PR.

### Reviewer sources chip
A finding with `sources` shows a `finding-sources` chip in the comment header naming its reviewers
joined by " + ", with the tooltip "Found by both reviewers" when there are two or more. No chip
appears when `sources` is absent, `null` or empty. Bridge validation accepts absent, `null`, or at
most 4 strings of at most 120 characters, and rejects any other shape like other invalid comment
fields. Sources are display-only and are not saved to GitHub drafts.

### Suggested change preview and removal
A finding with `suggestedChange` shows a read-only `suggested-change` region under the comment: the
anchored line's current text from the rendered diff as "before" and the replacement as "after", in
monospace with diff colors, plus a "Remove suggestion" button hidden when the pane is read-only.
Removing runs `EditCommentHandlers.onRemoveSuggestion(index)`, clearing the field and marking the
draft dirty. Because a suggestion replaces one specific line, `withoutSuggestion` clears it whenever
that pairing may change: a body edit, a verifier "revise", a `validateComments` snap, or
`reanchorDraft`. Both bridges bound it at 1,000 characters to match the codec limit. See
[webview-bridge-and-hosts.md](webview-bridge-and-hosts.md) for bridge contracts and host surfaces.
