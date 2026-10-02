# PR Pilot — Review Quality Plan: completed work

Verbatim record of completed phases and resolved findings archived from [REVIEW_QUALITY_PLAN.md](../../REVIEW_QUALITY_PLAN.md).

### 2.3 Dead and mis-defaulted machinery — ✅ all resolved

| Item | Evidence | Problem | Resolution |
|---|---|---|---|
| `knownPatterns` | `WebviewPanel.java:954` → `""`, `extension.ts:890` → `''` | Field, prompt section, and log statements exist; **never populated**. `<known_patterns>` has never rendered | Retired in Phase 1, along with its whole RPC plumbing path |
| `selfCritique` | `PluginSettings.java:91`, `package.json:202` — both `false` | The main precision mechanism ships **off** | On by default in Phase 2, once the critique pass was no longer blind |
| Critique prompt | `ClaudeService.java:972-981` | Drops repo guidelines, focus areas, custom instructions, existing reviews — validates findings while blind to the standards that justified them | Shares `appendContextSections` with `buildPrompt`; also fixes the unrecorded `pr.getBody()` omission |

### 2.5 Anchoring has silent failure modes — ✅ resolved or reasoned-declined

In `validateComments.ts`:
- ~~**Deleted files → every comment orphaned**~~ — ✗ **declined, deliberately.** Orphans still reach the reviewer via the body section; the fix needs a `side: LEFT` wire-schema change across both hosts; and the prompt already forbids commenting on deleted lines. Pinned by tests. See Phase 2.
- **Ambiguous basenames → orphaned** (`:63-64`) — ✗ **declined, deliberately.** Guessing between two real candidates is exactly the misattribution this layer prevents.
- ~~**Cross-hunk snapping**~~ — ✅ fixed; the index is per-hunk and refuses to snap when two hunks qualify.
- ~~**Dedupe key mismatch**~~ — ✗ **not a defect.** The three keys serve different operations; see Phase 2. Unifying them would post duplicate GitHub comments.

### 2.6 Quality theater — ✅ resolved

~~`applyReviewQualityRepairs` → `addMissingRationale` (`reviewQuality.ts:148-159`) inserts literal
placeholder text~~ — ✅ fixed; replaced by `dropMissingRationale`, which removes the comments
instead of fabricating evidence for them.

~~`verifyPrompt.ts` returns strict JSON with `action: keep|revise|delete`, but `ReviewPane.tsx`
only renders it for a human to read~~ — ✅ fixed; the verdict card now applies `delete`/`revise`
to the exact comment that was verified.

### 2.8 Documentation is materially wrong — ✅ fixed

| `ARCHITECTURE.md` | Claim | Reality |
|---|---|---|
| Line 249 | "Claude review/chat processes **disable tools**" | `READ_ONLY_TOOLS = "Read Grep Glob"` passed on every spawn (`ClaudeService.java:63`, `717-733`) |
| Line 281 | "Review providers run **without repository tools**… cannot fetch additional context" | They can read the worktree |
| Lines 362-381 | Settings list | Missing `reviewSelfCritique` and `reviewGuidanceGlobs` |

Also undocumented: **Claude gets a hard 3-tool allowlist; Copilot has no allowlist** and approves any
tool the CLI classifies as `kind == "read"` (`CopilotService.java:584-594`). The providers have
genuinely different capability surfaces.

All corrected in Phase 2, and the allowlist literals are now pinned by tests
(`ClaudeService.SAFE_CLI_ARGS`) rather than only described in prose.

### Phase A — Engine capability boundary `[M]` · ✅ DONE

The modularity insurance policy. Everything after this is cheaper and VS Code stays revivable.

**Shipped:**

- `GitHubEngineApi` (12 capabilities) in `github-engine`, `ReviewEngineApi` (3) in `review-engine`,
  both in package `com.jinloes.prpilot.engine`. Each carries an `RPC_METHODS` map from Java method
  name to wire name.
- `GitHubEngine` — pure delegation composition root over the existing services, so behavior and
  their existing tests stay put. Collapses the sidecar's 7-service constructor to one bean.
- `ReviewSessionService` **moved** from `sidecar` to `review-engine` (it had zero sidecar
  dependencies) and now implements `ReviewEngineApi`. Its request records moved onto the interface,
  so any client can build a request without depending on a transport.
- `StdioJsonRpcServer` dispatches via an introspectable `Map<String, MethodHandler>` registry
  instead of a `switch`, and exposes `registeredMethodNames()`.
- `EngineCapabilityCoverageTest` (6 tests) enforces **both** directions: no capability without a
  wire name, no wire name without a registered handler, and no registered handler that isn't a
  declared capability (`initialize` excepted). Verified by mutation — removing a handler
  registration and removing an `RPC_METHODS` entry each fail the build.
- `AGENTS.md`: 15-row parity table replaced by the capability rule, the "hosts may lag in consuming,
  never in re-implementing" distinction, the VS Code support floor, and a reduced table of the
  logic that genuinely *is* still hand-mirrored.
- `ARCHITECTURE.md`: new design-decision section + layout updates.

**Verified:** `:core:test`, `:github-engine:test`, `:review-engine:test`, `:sidecar:test` green;
sidecar `bootJar` + `smoke-sidecar.mjs` confirm the new Spring bean graph boots and answers
`initialize`; `tsc --noEmit` clean. No wire names changed, so the protocol is backward compatible.

#### Phase A follow-ups

| Item | Status |
|---|---|
| `SidecarBootstrapService.initialize()` capability map is still hand-maintained and can drift from the registry | ✅ **DONE.** Derived from a new `CAPABILITY_METHODS` grouping instead of fifteen free-floating literals, and `EngineCapabilityCoverageTest` gained four tests covering the map it previously ignored entirely. The payload is unchanged, so no client migration was needed — the earlier "changes a client-visible payload" concern was avoidable by keeping the handshake keyed by logical capability names and deriving only the values |
| Client-side `sidecar.ts` consumption is unenforced — the test covers the server only | ✅ **DONE.** `vscode-extension/test/wireCatalog.test.ts` parses `RPC_METHODS` from both engine interfaces as the source of truth and fails when `sidecar.ts` has no client method for a wire name, calls one no engine declares, or lets `REQUIRED_CAPABILITIES` drift from `CAPABILITY_METHODS`. Mutation-verified in both directions |
| `IntellijGitHubService` re-declares the GitHub surface instead of consuming `GitHubEngineApi` | ✅ **DONE.** Replaced eleven directly-instantiated services with one `GitHubEngineApi`. It now consumes **16/16** capabilities (`checkAuth` was the missing one; `PluginSettingsComponent` used `GitHubAuthService` directly and now goes through the engine, taking the origin as a parameter since the settings dialog checks an unsaved value). Zero direct engine-service instantiation remains anywhere in `intellij-plugin`. Tests went from 1 (a static mapper) to 11, now covering delegation, base-URL threading, `IOException` surfacing, and the deliberate context-read degradation; 3 mutations verified |
| Duplicated TypeScript (worktree, guidelines, binary probing, notifications, prompt constants) | ◐ **Partially done.** `guidelines.ts` (156 lines) and `worktree.ts` (170 lines) deleted with their tests, replaced by the `reviews/readGuidelines` capability and the three-method `worktrees` capability. **Remaining: binary probing, notifications, prompt constants** |

**A second live defect, found by doing the work.** `RepoGuidelinesReader.java` and `guidelines.ts`
had **already diverged**: the JVM truncation marker is `...(truncated)`, the TypeScript one
`…(truncated)` (U+2026). Cosmetic in isolation, but it is direct evidence that hand-mirrored
prompt-affecting logic does drift, and it drifted in the file that decides *what guidance reaches
the model*. Retiring the copy also deleted the duplicated `DEFAULT_GUIDANCE_GLOBS` list: an empty
glob list now means "engine defaults", so the default file list has exactly one owner.

**A third, in the worktree pair.** `GitWorktreeService.newWorktreePath` and `worktree.ts`'s
`worktreePath` had drifted to different temp-directory name formats — the JVM one carries a
timestamp plus a 63-bit random suffix, the TypeScript one `Date.now()` plus six base-36 characters.
That is not cosmetic: the name is what the cleanup path matches on via the `pr-pilot-wt-` prefix,
and the two formats have different collision behavior for rapid consecutive calls on the same PR.
Retiring the copy gives the naming, the fork-versus-origin fetch decision, and 3d's head-SHA
pinning exactly one implementation.

The worktree capability is deliberately **three narrow methods** (`findGitRoot`, `createWorktree`,
`removeWorktree`) rather than one "give me a working directory" call. The host still decides *when*
a worktree is warranted — same repo as the open workspace, not already cached for this PR — and
that decision needs host state the engine does not have. Moving it would have meant teaching the
engine about view lifecycles to save three RPC round trips.

Both `createWorktree` and `findGitRoot` return failure as a **domain result**, never an exception:
every caller degrades to the user's own checkout, so a thrown error would have to be caught at each
call site and converted back into exactly this shape. The VS Code client additionally coerces a
`created` status carrying a blank directory to `failed`, since passing `''` to the provider CLI as
its working directory is worse than falling back.

**A live defect found and fixed while doing this.** `REQUIRED_CAPABILITIES` in `sidecar.ts` listed
**11** of the sidecar's **15** advertised capabilities. The four missing were exactly Phase 1's
context capabilities — `checkStatus`, `prCommits`, `linkedIssues`, `repoProfile` — which VS Code
began calling only after the late Phase 1 wiring. A sidecar lacking them therefore **passed the
handshake** and failed at request time, and because all four are deliberately best-effort, that
failure degraded silently to four empty prompt sections with nothing surfaced to the user: the same
invisible-failure shape as the original months-long gap, in the code meant to prevent it.

The `sidecar.test.ts` fixture had the same 11-entry list hardcoded, which is why nothing caught it —
every test in that file was transparently exercising a *failed* handshake. Both lists are now
derived rather than written out, and the drift is test-enforced.

#### ✅ Pre-existing environment blockers — RESOLVED

Found during Phase A, fixed as a follow-up maintenance change. None were caused by Phase A (all
three reproduced on a clean tree), but together they meant `AGENTS.md`'s "required verification
commands" could not be run in full. **All now pass.**

| Command | Was failing with | Root cause | Fix |
|---|---|---|---|
| `./gradlew spotlessApply` / `spotlessCheck` / `check` | `NoClassDefFoundError: JCTree$JCAnyPattern` | google-java-format **1.35.0** references javac internals absent from every locally installed JDK — verified identical failure on 17/21/25, so a broken artifact, **not** a JDK-25 incompatibility as first assumed. Bumping the Spotless *plugin* to 8.8.0 does **not** help | Pin `googleJavaFormat('1.22.0')` (newest that runs with no extra JVM args) |
| `./gradlew :intellij-plugin:unitTest` | `bad class file` on every `com.intellij.*` import | `org.jetbrains.intellij.platform` **2.13.1** could not read IDEA **2026.2.0.1** platform jars | Bump the platform plugin to **2.18.1** |
| `(cd vscode-extension && npm run lint)` | `util.styleText is not a function` | `vscode-extension/package.json` had **no Volta pin** (unlike `webview/`), so it fell back to the global default Node **16.19.0**; ESLint 10.7 needs ≥20.19 | Add `volta.node = 24.15.0` + `.nvmrc`, raise `engines.node` |

Also done while here: Spotless plugin 6.25.0 → **8.8.0**, Spring Boot 3.5.0 → **3.5.16**, and
removed three zero-width spaces (U+200B) in `vscode-extension/src/guidelines.ts` that had been
hiding `*/` inside JSDoc — invisible, and the only thing lint flagged once it could run.

Spring Boot **4.1.0 was tried and rejected**: the sidecar compiled and unit-tested fine but failed
to boot (`No qualifying bean of type ObjectMapper` — `spring-boot-starter-json` auto-config changed
in Boot 4). Caught only by the runtime smoke test, not by `check`. Needs a real migration, not a
version bump.

**Now green:** `spotlessCheck`, `check` (all 7 modules, including `:intellij-plugin:unitTest`),
`vscode-extension` lint / `tsc` / 154 unit tests, `webview` lint / `tsc`, and the sidecar
`bootJar` + `smoke-sidecar.mjs` runtime handshake.

### Phase 0 — Minimal outcome logging `[S]` · ✅ DONE

**Scope deliberately reduced.** This is instrumentation, not a feature — it improves no single
review. Its only job is to answer *later* questions about ambiguous changes (does ripgrep caller
context help? does self-critique justify 2× latency? does chunking hurt?).

Changes that are obviously good a priori — CI status, commit messages, linked issue — **do not need
an A/B and are not gated on this phase.**

In scope:

- On submit, diff the originally generated review against the submitted one to derive
  `kept | deleted | edited` per comment
- Append `(promptVersion, provider, model, commentFingerprint, outcome)` to
  `~/.pr-pilot/review-outcomes.jsonl`
- **Owned by the engine, not the hosts** — host-neutral by construction, and avoids the
  IntelliJ-file / VS Code-`globalState` split entirely

**Shipped:** `review-engine/ReviewOutcomeLog` (classification + JSONL append) and
`ClaudeService.PROMPT_VERSION`. 20 tests. Two deviations from the spec above, both deliberate:

- A fourth outcome, **`added`**, for comments the reviewer wrote. It falls out of the same diff for
  free and is the only signal in this log for what the model *missed*.
- Records also carry `type`/`severity`/`confidence`. Without them the log cannot be segmented, and
  segmentation is the point. They cost nothing — no comment text or file path is stored, only a
  fingerprint (see `ARCHITECTURE.md`).

**Correction to this plan's stated design.** It claimed *"the webview already holds both — no new UI
required."* No UI is indeed required, but **neither host has both halves at submit time, and they
are missing opposite ones**: IntelliJ overwrites the generated review with the edited one
(`WebviewPanel.handleSaveDraft`), while VS Code never records the edits at all
(`extension.ts:913-947`). The `submitReview` bridge message carries no comments whatsoever. That the
two hosts lost *opposite* halves is the strongest available argument for this phase's own
"owned by the engine" instruction.

**Remaining wiring**, therefore, is: have `ReviewSessionService.generate` snapshot the generated
review (the one host-neutral point where provider *and* model are both known), expose a
`reviews/recordOutcome` capability on `ReviewEngineApi` + `StdioJsonRpcServer` — enforced by
`EngineCapabilityCoverageTest` — and call it from both submit handlers. VS Code additionally needs to
retain the edited result it already receives in `saveDraft`. No bridge schema change is needed under
this design.

**Second correction, found while wiring.** The engine-snapshot design above is wrong: **IntelliJ
never routes generation through `ReviewSessionService`** — it calls `ClaudeService`/`CopilotService`
in-process via `IntellijClaudeService`, and `ReviewSessionService` is sidecar-only. An engine-held
snapshot would have populated for VS Code and silently recorded nothing on the host with most of the
users.

`recordOutcome` is therefore **stateless**: the caller passes both sets, and each host retains the
half it was already dropping. Classification, fingerprinting, and the prompt version stay
engine-owned, so the hosts hold UI state (which they already had) and implement no logic.

**Shipped, end to end:**

- `reviews/recordOutcome` on `ReviewEngineApi` + `StdioJsonRpcServer`, enforced by
  `EngineCapabilityCoverageTest`; `sidecar.ts` client method.
- IntelliJ keeps `generatedResult` alongside `lastResult`; logs off the EDT after a successful
  submit.
- VS Code keeps `generatedReviewResult` (set **only** on generation) and `editedReviewResult`
  (captured from `saveDraft`, the only message carrying it).
- `promptVersion` is stamped engine-side, not accepted from the caller — a host cannot know which
  prompt the engine build ships.
- A draft *loaded from GitHub* is deliberately not logged on either host: it was not generated in
  this session, so diffing it would record every comment as `kept` and poison the very statistic
  this phase exists to produce.
- 28 tests (20 log, 4 engine, 4 sidecar wire-contract).

Explicitly deferred until a question needs them: GitHub comment-resolution read-back, keep-rate UI,
per-repo dashboards.

Risk acknowledged: metrics work that nobody acts on is waste. Kept minimal for that reason.

#### ⚠️ The instrument was recording fabricated data on the primary host — now fixed

Found while checking whether 3c's gate had opened. The log held **one record** after a month, which
prompted a check of whether logging worked at all. It did — but `WebviewPanel` had a state-lifetime
bug that made its records untrustworthy.

`handleSelectPR` clears `lastResult` but **not** `generatedResult`. That asymmetry is reachable and
silent:

| Step | `generatedResult` | `lastResult` |
|---|---|---|
| Generate on PR A | A's comments | A's comments |
| Switch to PR B | **A's comments** (not cleared) | `null` |
| B's draft loads from GitHub | **A's comments** | B's draft |
| Submit B | → logged as the "before" side | → logged as the "after" side |

Every one of A's comments is then recorded `deleted` and every one of B's `added`. The reviewer did
neither. `handleDeleteDraft` had the same asymmetry.

This matters more than an ordinary bug because **nothing downstream can distinguish a fabricated row
from a real one**, and the whole point of this log is to adjudicate changes nobody can judge by
intuition. It is also the third instance of this plan's recurring failure shape — silent degradation
in a path with no user-visible symptom — and this time it was in the measuring instrument itself.

Fixed by pairing the retained review with the PR it came from (`generatedResultKey`) and gating the
write on a match, rather than by clearing on switch: clearing would also discard the legitimate
generate → switch away → switch back → submit case, and data scarcity is already the problem.
Extracted as `WebviewPanel.shouldRecordOutcome` so the decision is testable; 5 tests, mutation-
verified (removing the key comparison fails 3).

**A known lossy path remains, deliberately unfixed.** VS Code clears *both* halves on PR switch
(`extension.ts` `handleSelectPR`), so the same switch-away-and-return sequence records nothing
there. That is safe — it under-records rather than fabricating — so it is a data-volume issue, not a
correctness one, and it is the minority host. Worth revisiting if VS Code volume ever matters to the
baseline. Noted rather than fixed to keep the correctness fix isolated.

**The gate is still closed**, and now for an additionally good reason: records written before this
fix cannot be trusted, so the baseline effectively starts now.

### Phase 1 — Context service `[M]` · ✅ DONE (VS Code consumption landed later — see below)

The largest single quality win. All read-only REST; no new security surface.

**Shipped**, in `github-engine` following the `PrSupplementalService` convention:

- `CheckRunService` + `CheckRunSummary` / `CheckAnnotation` / `CheckStatusResult`. Fetches check
  runs for the head SHA, then annotations for a check that **failed or reports having any**
  (`output.annotations_count`), failing checks first for the bounded budget — see §3.3(B). Falls
  back to the legacy commit-status API when the Checks API returns nothing, so Jenkins/Buildkite-style
  integrations still register.
- `PrCommitsService` + `PrCommitsResult`, `LinkedIssueService` + `LinkedIssueResult`,
  `RepoFingerprint` + `RepoProfileResult` (local file detection, no network call).
- `PromptContext` — shared validation and bounding. Owner/repo/SHA are interpolated into request
  paths, so they are validated against a strict allowlist rather than escaped; a segment of only
  dots is rejected so `.`/`..` cannot traverse.
- Bounds applied in the service, not at the prompt layer: 30 check runs, 20 annotations, 5
  annotated checks, 200-char messages, 300-char outputs. CI output is attacker-influenceable — a
  contributor controls the code that produces it — so it is capped where it enters the system.
- `PRReviewRequest` gained `ciStatus`, `commits`, `linkedIssue`, `repoProfile` and moved to a
  **builder**: with eleven mostly-String fields, positional construction would silently accept a
  wrong argument order. Every context field is optional and a blank value omits its section, so a
  caller that cannot supply one degrades to exactly the previous behavior.
- Prompt sections `<ci_status>`, `<commits>`, `<linked_issue>`, `<repo_profile>` render in
  `ClaudeService.buildPrompt`. The CI preface explicitly tells the model not to repeat a finding CI
  already reports, and to justify a claim that contradicts a passing check.
- Four new capabilities on `GitHubEngineApi` (`getCheckStatus`, `getCommits`, `getLinkedIssues`,
  `getRepoProfile`), all exposed over RPC and enforced by `EngineCapabilityCoverageTest`.

**Phase 1 was capability-complete but only half *delivered*, for months.** Found while scoping
Phase 4. The four capabilities existed, were exposed over RPC, and were coverage-test enforced — but
`SidecarClient` had **no client method for any of them**, and nothing in `extension.ts` ever
populated `ciStatus`/`commits`/`linkedIssue`/`repoProfile`. The fields were declared in
`SidecarGenerateParams` with doc comments naming the exact wire methods, so the intent was recorded;
only the calls were missing. Every VS Code review therefore shipped with all four prompt sections
empty — the plan's "largest single quality win" was IntelliJ-only in practice.

This is the failure mode the coverage test **cannot** catch, and the plan should not have read
`✅ DONE` unqualified. `AGENTS.md` permits a host to lag in consuming a capability; it does not
excuse recording that lag as completion.

Now wired: `getCheckStatus`/`getCommits`/`getLinkedIssues`/`getRepoProfile` on `SidecarClient`,
fetched in parallel in the review path and passed through to `generateReview`. All four are
best-effort — a failure degrades to an omitted prompt section, matching the deliberate
"do NOT call requireOk" decision on the IntelliJ side. `getCheckStatus` returns the structured
annotations alongside the rendered summary, which is what Phase 4's dedupe needs. 3 tests.

**`knownPatterns` retired.** Removing it deleted a whole plumbing path — `ReviewEngineApi` →
`ReviewSessionService` → `StdioJsonRpcServer` → `sidecar.ts` → the prompt section — not just a
prompt slot.

That retirement surfaced dead TypeScript, removed under guardrail #5:

- `claude.ts` `buildPrompt`, `buildChatPrompt`, `annotateDiffWithLineNumbers`,
  `REVIEW_INSTRUCTIONS`, `SAFE_CLAUDE_TOOL_ARGS` had **no production caller** — review and chat
  route through the sidecar, so only tests still referenced them. Deleted, shrinking two
  `AGENTS.md` mirror rows to just `CHAT_PERSONA` / `buildFocusedChatPrompt` (still host-built, to
  match `IntellijClaudeService.chatFocused`).
- The deleted `SAFE_CLAUDE_TOOL_ARGS` test was the **only** guard on the Claude sandboxing
  arguments, and the Java side had none. Rather than lose it, the literals were extracted into
  `ClaudeService.SAFE_CLI_ARGS` and pinned by three tests at the site that actually spawns the
  process.

**Verified:** full `spotlessCheck check` green across all 7 modules; `github-engine` and
`review-engine` suites green; sidecar `bootJar` + `smoke-sidecar.mjs` handshake passes;
`webview` and `vscode-extension` lint / `tsc` / tests green.

#### Landing zone (verified, so Phase 1 starts without re-discovery)

- **Service convention** — `PrSupplementalService.java:27-40`: public no-arg constructor delegating
  to a package-private one taking `(GitHubAuthService.TokenResolver, ApiClient, ObjectMapper)` for
  test injection. Representative call: `existingReviews` (`:154-208`).
- **Token** — `GitHubAuthService.ProcessTokenResolver.resolve(hostname)` (`:120-165`) shells out to
  `gh auth token [--hostname <host>]`. Hosts never see the token; keep it that way.
- **`PRReviewRequest`** (`core/.../PRReviewRequest.java:11-18`) currently has 8 fields: `pr`, `diff`,
  `knownPatterns`, `priorReview`, `existingReviews`, `repoGuidelines`, `focusAreas`,
  `customInstructions`. It is an immutable value object, so adding fields touches its constructors.
- **`knownPatterns` is confirmed dead** — declared and plumbed through
  `ReviewEngineApi.java:54` → `ReviewSessionService.java:55` → `StdioJsonRpcServer.java:154` →
  `sidecar.ts:202` → `claude.ts:188,194`, rendered at `ClaudeService.java:894-900`, but **every**
  call site passes empty (`WebviewPanel.java:951-959`, `extension.ts:890`). No producer exists.
  Retiring it removes a whole plumbing path, not just a prompt section.

#### ✅ Prerequisite: shared REST transport — DONE

The audit found the duplication was worse than "no shared client": **7 copies** of GitHub base-URL
parsing *and* **7 hand-rolled HTTP clients**, each with its own timeout, headers, retry loop, and
`backoff()`. Phase 1 adds 3–4 more services, so this was extracted first.

Shipped in `github-engine`, package `com.jinloes.prpilot.sidecar.github`:

- **`GitHubApiBase`** — one validated origin → `(apiBaseUrl, hostnameArgument)`. Replaces all 7
  copies. Offers `parse` (null on invalid) and `require` (throws) so both pre-existing caller
  conventions migrate without a behavior change. **22 tests.**
- **`GitHubHttpClient`** — the single authenticated transport: headers, timeout, and retry policy in
  one place. A package-private `Transport` seam makes retry behavior testable without sockets, and
  `stream(...)` serves the diff path's bounded-byte read. **15 tests.**
- **`GitHubResponse`** — uniform `(statusCode, body)` outcome with `isSuccess` / `isUnauthenticated`
  / `isRateLimited` / `isNetworkError`. Transport failure is status `0`, not an exception, so every
  caller maps one shape.

Migrated: `GitHubAuthService`, `PrSupplementalService`, `PrDetailService`, `PrListService`,
`PrDiffService`, `DraftReviewService`, `DraftReviewMutationService` (base URL only — see below).

Inconsistencies this **fixed**, all previously silent:

| Was | Now |
|---|---|
| `PrSupplementalService` sent `Accept: application/vnd.github+json`; the other six sent `...v3+json` | One media type |
| `PrSupplementalService` did **not** retry `429`; the others did | Uniform retry on `429` + `5xx` |
| `GitHubAuthService` did **not** retry **at all** — a single blip failed the auth check | Retries like everything else |
| Two `User-Agent` strings (`pr-pilot-sidecar/0.1`, `pr-pilot-engine/0.1`) | One |

**Deliberate exception:** `DraftReviewMutationService` keeps its own transport for POST/PUT/DELETE.
The shared client's retry policy is only safe for idempotent reads — retrying a review-submitting
POST could duplicate a submitted review. Its base-URL parsing *was* unified. Documented at the class.
Making mutation retries idempotent (request key) is follow-up work, not a Phase 1 blocker.

**Verified:** `github-engine` 107 tests green (37 of them new), full `./gradlew check` green across
all 7 modules, sidecar `bootJar` + `smoke-sidecar.mjs` runtime handshake passes. Zero remaining
duplicate base-URL records; the only raw `HttpClient` instantiations left are `GitHubHttpClient`
itself and the documented mutation client.

#### CI ingestion tiers

Raw log *parsing* is explicitly rejected — gzipped archives behind redirects, unbounded size, ANSI
codes, interleaved parallel output, and a different format per test framework. That is unbounded
per-tool maintenance, the same failure mode as per-language LSP init.

| Tier | Source | Structure | Notes |
|---|---|---|---|
| A | `check_run.conclusion` + `output.summary` / `output.text` | Structured | Cheap, always available |
| B | **Annotations** — `path`, `start_line`, `message` | Structured | Best; already review-comment shaped |
| C | Log **tail** (~8–16 KB) → `<ci_output>` | Unstructured | **Hand to the model, do not parse** |

Tier C rationale: reading messy test output is what LLMs are good at. Writing a
JUnit/pytest/jest/go-test parser is work with no ceiling.

Tier C constraints: untrusted content (tag-escape as with every other payload), hard size bound, and
**opt-in per repo** since logs may contain unmasked secrets.

#### CI timing policy

CI context is **purely additive** — absent it, behavior is exactly today's. **Never block a review on
CI**; a run can take 20+ minutes.

| CI state | Provided | Lost |
|---|---|---|
| Complete | Suppression, calibration, triage | — |
| In progress | Completed checks; pending ones labeled | Suppression may duplicate a finding CI would catch (noise) |
| Not started / absent | — | All three; **no regression vs. today** |

Pending checks must be surfaced to the model explicitly so it does not assume tests pass. Reviewer
sees a "CI still running" hint; the existing regenerate path covers re-running once CI lands.

### Phase 2 — Trust and correctness fixes `[M]` · ✅ DONE

Cheap, high trust-value, mostly bug fixes. **Almost entirely shared webview code** — negligible
per-host cost.

#### ✅ Shipped

- **Self-critique is on by default.** Flipped in all three places (`PluginSettings.java`,
  `package.json`, the `extension.ts` reader). The two VS Code declarations are independent and VS
  Code only honors the contribution, so a mismatch was silent — `settingDefaults.test.ts` now
  asserts every boolean setting's reader fallback matches its contribution. Mutation-verified: all
  three assertions fail when the contribution is flipped.
- **The critique pass now sees what the first pass saw.** `buildPrompt` and `buildCritiquePrompt`
  share one `appendContextSections` helper, so they cannot drift. This was a prerequisite for the
  flip, not a parallel nicety: a validator blind to the repo guideline behind a finding reads that
  finding as unsupported and drops it, so turning self-critique on while it stayed blind would have
  *lowered* recall. It also fixes the previously-unrecorded omission of `pr.getBody()`.
  - `CRITIQUE_PREAMBLE` was widened to classify the newly-included tags as untrusted vs preference
    data — adding context sections without extending the injection guard would have opened a hole.
  - `CRITIQUE_DIRECTIVE` now tells the validator that a guideline-justified finding *is* supported,
    and to drop findings `<ci_status>` shows CI already reports. That is Phase 4's suppression idea
    arriving early and for free, since the CI context is already in the prompt.
- **Quality theater deleted.** `addMissingRationale` (which wrote
  `Evidence needs verification in <file>:<line>.`) is replaced by `dropMissingRationale`, which
  removes the comments. The old repair cleared the trust warning and raised the reported score
  while adding no evidence. The *detection* is unchanged — only the fake remedy is gone.
- **Snapping is hunk-scoped.** `buildLineIndex` now indexes per hunk with spans instead of
  flattening a file into one `Set<number>`. A comment snaps only when exactly one hunk has a line
  within `SNAP_RADIUS`; when two qualify it sits in the gap between them and is orphaned. Covered
  by a test using two hunks four lines apart, where the old flat set snapped both comments into
  hunks they did not belong to.
- **Doc corrections.** `ARCHITECTURE.md` claimed review processes "disable tools" and "cannot fetch
  additional context" — both false; they get a read-only `Read Grep Glob` allowlist over the
  worktree. Corrected, with the Claude-allowlist vs Copilot-permission-callback asymmetry now
  documented, plus the two missing settings.

#### ✗ Corrected: the dedupe-key "mismatch" is not a defect

§2.5 recorded that the webview uses `file|line|type|body` while `DraftReviewCodec` uses
`file\0line\0body`, and called for unifying them. **Unifying them would be a regression.** The keys
serve different operations:

| Key | Operation | `type` included? |
|---|---|---|
| `ReviewPane.mergeChunkResults` | dedupe model **findings** across batches | yes — type is part of a finding's identity |
| `DraftReviewCodec.orphanKey` | identity match against the orphan list | yes — same objects on both sides |
| `DraftReviewCodec` `dedupeKey` | dedupe the **GitHub POST payload** | **no, deliberately** |

The posted payload is only `path`/`line`/`side`/`body`. Including `type` there would post two
byte-identical comments on the same line whenever a finding appeared as both an `issue` and a
`suggestion`. Resolved by documenting the three keys at the class and in `ARCHITECTURE.md`, and
pinning the behavior with three tests, so the next reader does not re-file it as drift.

- **`verifyPrompt`'s `action` is wired to one-click apply.** The verdict card now renders an Apply
  button for `delete` and for `revise` with non-blank replacement text; `keep` gets none, since an
  Apply that does nothing is worse than no button.
  - The non-obvious part was **not** the button. A reviewer can verify several comments before
    acting on any verdict, so `ReviewPane` tags each request with an opaque token bound to that
    comment and `ChatPane` copies it onto the reply. Without that, "apply" would have hit whichever
    comment was verified most recently.
  - `resolveVerifyTarget` resolves by identity then by `(file, line, body)`, and **no-ops** when the
    comment was deleted or reworded while verification was in flight. Applying a stale verdict to a
    different comment is worse than dropping it.

#### ✗ Corrected: deleted-file anchoring is a deliberate degradation, not a bug

§2.5 called for indexing deleted files' `oldPath` lines and anchoring via GitHub `side: LEFT`.
Assessed and **declined**, because the cost/benefit does not hold up:

- Orphaned comments are **not lost** — `DraftReviewCodec.buildOrphanSection` renders them into the
  review body under "Comments not attached inline". Only placement degrades.
- `side` does not exist in `bridge/types.ts`, so this is a **wire-schema change** across the host↔UI
  contract (guardrail #4) plus both hosts' handlers and the codec.
- The review prompt already says *"Only comment on changed ('+') lines"*, so a comment on a deleted
  file is a prompt violation to begin with. Building three layers of plumbing to place a finding the
  model was told not to produce is the wrong trade.

Pinned by two tests so the behavior is intentional and observed rather than assumed, and recorded in
`ARCHITECTURE.md`. Revisit only if the prompt is changed to solicit deletion findings.

#### Remaining

- **Ambiguous basenames** (`findValidLinesForFile` returns `null` unless exactly one suffix match)
  is left as-is **on purpose**: guessing between two real candidates is precisely the misattribution
  this layer exists to prevent. Listed here so it is not re-filed as an oversight.

### Phase 4 — CI-grounded suppression and calibration `[S]` · ✅ DONE (escalate/triage deferred)

- **Suppress** findings already reported by CI annotations (dedupe) — the author already sees them
- **Calibrate confidence**: green CI on a file is evidence *against* a speculative finding; replaces
  the `body.length < 35` heuristic in `reviewQuality.ts:55-64`
- **Escalate**: "CI passes but this is still wrong" as a distinct, higher-value claim
- **Triage**: explain *why* a CI failure happened, using the diff. CI says what; the model says why

**Shipped — suppression.** `CiFindingSuppressor` (review-engine) drops a generated comment when a CI
annotation covers the same location *and* the wording substantially overlaps. Phase 2 delivered the
soft version — the critique prompt asks the model to drop these — but a prompt request is not a
guarantee; this is the deterministic pass. Applied at both providers' `reviewPR` seams, so Claude
and Copilot behave identically.

Deliberately conservative, because the two error directions are not symmetric: a surviving duplicate
costs a line of reading, while a wrongly-dropped finding is *invisible* — the reviewer cannot tell it
ever existed. Hence both a location match (±2 lines, since CI often anchors a line or two off) and
≥0.6 word overlap are required, and an annotation with no distinctive words ("Process completed with
exit code 1") suppresses nothing at all.

Plumbing: `CiAnnotation` in `core`, `PRReviewRequest.ciAnnotations` (builder, so additive),
`GenerateReviewParams.ciAnnotations` (Jackson-only construction, so also additive), plus both hosts.
IntelliJ's `getCheckStatusSummary` became `getCheckContext` returning the summary *and* annotations
from the one request it was already making.

**Shipped — calibration.** `isHighRiskLowEvidence` no longer treats `body.length < 35` as a proxy for
"unjustified". That rule failed in both directions: a precise one-liner ("Deadlock: B locks A here")
was flagged, while a verbose but baseless paragraph passed. It now uses the signals that actually
exist — a high-severity claim is low-evidence when the model rates its own confidence `low` or states
no `rationale`.

**Deferred: escalate and triage.** Both are prompt changes, and prompt changes are exactly what
Phase 0's outcome log exists to adjudicate. Unlike dedupe (obviously good a priori — the author can
already see the CI annotation), "CI passes but this is still wrong" could plausibly *increase* noise.
Revisit once the log has data.

13 Java tests (10 suppressor, 3 builder), 5 webview tests.
