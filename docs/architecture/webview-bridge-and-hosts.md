# Webview bridge and host surfaces

Key design decisions moved from [ARCHITECTURE.md](../../ARCHITECTURE.md). Each section encodes an
active constraint future code must respect.

### Webview draft mutation watchdog
`ReviewPane`'s `saveDraft`/`submitReview`/`deleteDraft` round trip (webview → host → GitHub → host →
webview) has no host-side timeout of its own — a dropped bridge message, a wedged host-side mutation
queue, or a crashed sidecar process leaves the corresponding `saving`/`submitting`/`deleting`
spinner state stuck forever with no recovery path, since the webview only clears those flags in
response to a message it may never receive. `ReviewPane` arms a client-side watchdog
(`MUTATION_WATCHDOG_MS`, 45s) whenever it dispatches one of these three requests and clears it when
the matching success/error response arrives; if the watchdog fires first, it synthesizes a
`saveError`/`submitError`/generic `error` state so the user can retry instead of the UI hanging
indefinitely. Do not raise this window to match `REVIEW_REQUEST_TIMEOUT_MS`/`reviewExecutor` —
GitHub draft mutations are simple bounded REST calls with 15-second requests, unlike AI review
generation which can legitimately run for minutes.

### User-facing error copy
Do not surface raw provider/HTTP exception strings directly to users in review/draft/chat flows.
Both hosts map low-level errors to actionable guidance (`UserFacingErrors` in IntelliJ,
`userFacingError.ts` in VS Code) to keep messaging consistent across providers. Provider errors must
be mapped exactly once from the original exception; remapping rendered copy destroys the failure
classification. Message strings live in shared YAML templates
(`vscode-extension/shared/user-facing-errors.yaml`) and support `{placeholder}` substitution.

### Bridge payload validation
Bridge messages carry protocol version `1`. Both hosts validate webview-to-host messages before
dispatching handlers (`BridgeMessageValidator` in IntelliJ, `bridgeValidation.ts` in VS Code),
including PR identity, enums, nested review/comment shapes, booleans, collection sizes, and text
bounds. The webview validates every host-to-webview payload in `bridge/validation.ts` before
fan-out. Unknown versions/types and malformed nested payloads are rejected instead of reaching
business logic. Optional wire fields are omitted rather than serialized as `null`; IntelliJ
serializes bridge comments through strict MapStruct DTO mapping and omits absent rich metadata
rather than emitting invalid empty enum values.

### PR chat scope
Chat is available after PR selection, before and after review generation. Hosts send a structured
`ChatContext` (core): PR title, author, and description, the bounded 250 KB review diff, and the
generated review's verdict, summary, and line comments. Neither host builds prompt text; the engine
(`ReviewChatPrompts`) owns every budget so chat answers do not diverge by host.

The engine lists every changed file with its line counts and whether its diff is shown, truncated,
or left out (including files the fetched diff omitted), then fills a 60,000-character diff budget
per file: the attached file first, then files the review commented on, then diff order. The provider
always runs in the PR-head worktree (both hosts fail closed without one), and the prompt tells it to
read files it has not seen and cite `path:line`, so a large PR is answered from the code rather
than from a truncated excerpt.

Text the reviewer selects, or a draft comment they verify or ask for a fix on, travels as an
attachment with its file and line range (`contextLocation` on `askClaude`; diff rows carry
`data-line`/`data-line-side` for this). An attachment adds to the PR context and history rather than
replacing them, and the recorded history turn names its location so follow-ups still resolve. The
webview lists the attached context buckets, labeling the diff "diff excerpt" when the review diff
omits files. The VS Code host sources title/body from `getPRDetail` on select and the author from
the `selectPR` message.

### DTO mapping in IntelliJ webview bridge
`WebviewPanel` model-to-DTO conversion uses MapStruct (`ReviewMapper`) instead of hand-rolled
mappers so field drift fails at compile time.

### Webview bridge PR correlation
PR-scoped lifecycle messages (`draftLoaded`, review generation/chunks/results/errors, draft
save/submit/delete, and chat responses) carry a `prKey` of `owner/repo#number`. The React webview
drops keyed messages that do not match the active PR so late async results from a previously
selected PR cannot repaint or submit against the current PR. When adding a new PR-scoped host
message, include the same `prKey` in both hosts. Host-driven selection changes that originate
outside the list (for example, background notifications) use a separate `activatePR` message
carrying the full PR DTO so the app can honor its unsaved-review confirmation flow before sending
the normal `selectPR` request back to the host.

Draft mutations are serialized per host view and bind the pending review ID to its full `prKey`.
Selection revisions prevent late loads/saves/generation results from installing state after a PR
switch; submit/delete reject IDs not owned by the active PR. Regeneration preserves the existing
GitHub draft until a replacement is explicitly saved.

### Opening diff files and findings in the IDE editor
`openFile` requires active PR identity, a matching checkout and a confined repository-relative
path. File headers open editable workspace files first, with a read-only worktree fallback.
Finding lines require a token issued by the host for the displayed result, never a SHA chosen
by the webview. The engine reads the verified commit's regular UTF-8 blob for a read-only
preview identified by commit and path. Unverified results (including unversioned recovered
drafts), missing blobs and invalid lines never fall back to another revision.

### VS Code webview surfaces
The VS Code host exposes PR Pilot as an editor-tab `WebviewPanel` opened by `pr-pilot.open`. The
Activity Bar view (`pr-pilot.main`) is an empty native tree view whose `viewsWelcome` content offers
an Open PR Pilot command button and a Settings link; it hosts no webview. Opening or revealing the
editor panel never changes the sidebar's visibility. The full PR loading, review generation, chat,
and worktree lifecycle run only in the editor-tab panel.

For development, the extension loads UI assets from the sibling `webview/dist` folder. For packaged
`.vsix` builds, the release/package flow stages that same output into
`vscode-extension/webview-dist`, and the extension resolves the bundled copy first so installed
releases do not depend on the source repo layout.

All VS Code webview surfaces use restrictive Content Security Policy headers. The main Vite document
rewrites only packaged local asset URIs and nonces scripts; the fallback error page nonces inline
resources, and dynamic error text is HTML-escaped.

### IntelliJ webview surfaces
Like VS Code, the IntelliJ host runs the full shared webview (PR discovery, review, diff, and chat)
inside a main-window editor tab rather than a docked side tool window. `PrReviewFileEditorProvider`
opens a non-persistent `PrReviewVirtualFile` (`LightVirtualFile` implementing
`VirtualFileWithoutContent`), and `PrReviewEditorTabTitleProvider` renders `"PR Pilot"` when idle
or `"PR Pilot · #<number>"` when a PR is selected. The `PR Pilot` tool window
(`PRToolWindowFactory`) is a lightweight Swing stripe launcher that hosts no JCEF browser; showing
it (`PrPilotToolWindowListener.toolWindowShown` or `createToolWindowContent`) hides the side tool
window first—so persisted side-dock width neither steals editor space nor reclaims focus—and then
opens or focuses the main editor tab (`OpenPrPilotAction` adds **Tools > Open PR Pilot**).
`PrPilotUiCoordinator` wires initial `loadPRs` on `onPageReady`, queues notification `activatePR`
until `prListLoaded` succeeds, avoids re-activating an already-active PR, seeds split tabs, and
resets tab title state when the last tab closes. Keep `com.intellij.modules.jcef` declared in both
`plugin.xml` and Gradle for Toolbox IDE distributions.
