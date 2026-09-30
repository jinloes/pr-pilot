# PR Pilot — Review Quality Plan

Working plan for improving AI review quality and resolving the IDE-plugin strategy question.

Status: **Phases A, 0, 1, 2, and 4 complete; Phase 3 partial (3a, 3d done).** Next: 3c, but it is
**gated on outcome-log data** — see §6. That gate is still closed, and the instrument behind it was
found recording fabricated rows on IntelliJ and repaired (see Phase 0), so the usable baseline
starts from that fix. While it accumulates, the ungated work is the Phase A follow-ups (three done,
one partially).

> **Transient artifact.** Kept in-repo only until the plan is executed, then deleted. Deliberately
> *not* added to `ARCHITECTURE.md`'s project layout.

---

## 1. Problem statement

PR Pilot generates code reviews from **a truncated unified diff and almost nothing else**. Review
quality is bounded by that context gap, and there is currently **no way to measure whether any change
helps**.

Separately, the product is delivered as two IDE plugins that use **zero IDE intelligence APIs**, so it
pays a large dual-host cost without capturing any IDE-specific benefit.

---

## 2. Findings (evidence)

### 2.1 The IntelliJ plugin does not use the IDE

A full audit of `com.intellij.*` imports found **zero** usage of PSI, indexes, inspections, editor,
VCS, VFS, navigation, refactoring, or completion APIs.

What it does use: `JBCefBrowser`, `ToolWindowFactory`, `PersistentStateComponent`,
`NotificationGroupManager`, `AppExecutorUtil`, and `Project.getBasePath()` — immediately converted to
a `java.io.File` (`WebviewPanel.java:929-930`).

`plugin.xml` declares `depends com.intellij.java` while never touching Java PSI.

**The plugin is a Chromium window in a docked panel.**

### 2.2 The context gap is the dominant quality bottleneck

| Context | Provided today? |
|---|---|
| Linked issue / ticket | ❌ No fetch anywhere |
| Commit messages | ❌ No `/commits` call |
| CI status / failing checks | ❌ No `check-runs` or `statuses` call |
| Base-branch file contents | ❌ Diff only |
| Repo language / build system | ❌ Not collected |
| Static analysis results | ❌ None |
| Callers / callees of changed code | ✅ Textual base-commit call sites (`<call_sites>`); PSI enrichment not built |

### 2.3 Dead and mis-defaulted machinery — ✅ all resolved

| Item | Evidence | Problem | Resolution |
|---|---|---|---|
| `knownPatterns` | `WebviewPanel.java:954` → `""`, `extension.ts:890` → `''` | Field, prompt section, and log statements exist; **never populated**. `<known_patterns>` has never rendered | Retired in Phase 1, along with its whole RPC plumbing path |
| `selfCritique` | `PluginSettings.java:91`, `package.json:202` — both `false` | The main precision mechanism ships **off** | On by default in Phase 2, once the critique pass was no longer blind |
| Critique prompt | `ClaudeService.java:972-981` | Drops repo guidelines, focus areas, custom instructions, existing reviews — validates findings while blind to the standards that justified them | Shares `appendContextSections` with `buildPrompt`; also fixes the unrecorded `pr.getBody()` omission |

### 2.4 Chunked mode degrades quality

- `ReviewPane.tsx:244-251` instructs each batch to **ignore findings outside its file set** — structurally forbids cross-file bugs, the highest-value class.
- Auto-enables at ≥8 files or ≥300 changed lines (`:270-287`) — exactly the PRs needing global reasoning.
- Merge (`:289-314`): exact-string dedupe on `file|line|type|body`; summaries **concatenated then `.slice(0, 790)`**, silently truncating later batches.
- Batch priority is **descending changed-line count only** — pure churn, ignoring path or file type.

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

### 2.7 No feedback loop exists

Broad greps for telemetry / analytics / accepted / rejected / dismissed returned nothing. No record of
kept-vs-deleted comments, no GitHub resolution read-back, no persisted quality scores.

**Every prompt change is currently unfalsifiable.**

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

---

## 3. Decisions

### 3.1 The plugins are the review *surface*, not the engine

Quality comes from the engine. The plugins deliver the reviewing experience — inline comments,
navigation, chat with editor context. That is a legitimate product; it is just not where quality
originates.

Consequence: invest in the engine, and make both hosts thin clients of it.

### 3.2 No sandbox on the critical path

**Reversed from an earlier draft of this plan.** The sandbox was justified by test execution,
compiler diagnostics, and linter output — but CI already produces all three and exposes them over
REST. Building a sandbox to re-derive them is redundant compute plus a new RCE surface.

### 3.3 Code intelligence: three strategies, differing mainly in *reach*

> **Naming correction.** These were originally "Tier 1/2/3", which reads as a quality ladder you
> climb — implying Tier 3 supersedes Tier 1 and you could simply skip to it. That is wrong and the
> numbering caused real confusion. They are **not substitutes**. The decisive axis is which clients
> can use them at all.

| Strategy | Mechanism | Reach | Cost | Executes PR code? |
|---|---|---|---|---|
| **Textual** | `ripgrep` / tree-sitter symbol search | **Every client** — IntelliJ, VS Code, CLI, Action | Milliseconds, no project config | **No** |
| **Language-server** | Headless LSP (`gopls`, `pyright`, `tsserver`) | Every client, but **requires the sandbox** | Seconds–minutes, cold per worktree | **Yes** |
| **Platform-native** | IntelliJ PSI | **IntelliJ only** | Cheapest queries once warm | No |

Because reach differs, platform-native cannot *replace* textual — it can only *add* precision for
one host. Any client that lacks it still needs the textual path, so the textual path gets built
regardless. That is the whole argument for starting there.

Rationale:

- **The consumer is an LLM, not a refactoring engine.** This inverts the usual precision/recall
  preference. PSI's advantage is precision — exact symbol resolution, no false candidates. But an
  over-broad candidate list costs a few tokens, while a *missing* candidate costs a missed bug. PSI's
  strength is worth the most to a refactoring engine and the least to a reviewer.
- **The agent can already grep.** `READ_ONLY_TOOLS = "Read Grep Glob"` is granted on every Claude
  spawn and Copilot approves read-kind tools, yet **nothing in the prompt mentions callers or blast
  radius** (verified: zero directives). So a large part of the textual strategy is not new capability
  at all — it is *direction*. That makes it nearly free, which dominates the cost comparison.
- **LSP on an untrusted worktree is RCE** — `jdtls` triggers Gradle/Maven import (executes build
  logic), `rust-analyzer` executes `build.rs`, `tsserver` can load tsconfig-declared plugins. LSP is
  therefore *not* a cheaper alternative to the sandbox; it requires one (§3.2 removed the sandbox
  from the critical path, so this strategy is gated behind reversing that decision).
- **LSP is slower than PSI**, not faster. PSI queries a warm index in milliseconds; language servers
  are cold on every PR worktree (`jdtls` on a large repo: minutes).
- **PSI's blocker is cost and module boundaries, not impossibility.** An earlier draft of this plan
  claimed PSI "can never be exposed over RPC". That is **overstated** — headless IntelliJ is a real,
  shipped configuration (JetBrains' own Qodana runs IntelliJ inspections in CI this way). The honest
  blockers:
  - The IntelliJ platform is currently a **leaf dependency** held only by `intellij-plugin`.
    `core`, `github-engine`, `review-engine`, and `sidecar` are all plain Java. Exposing PSI over
    RPC means `review-engine` depends on the IDE platform and the sidecar ships it: **~18 MB today
    vs. ~1–2 GB**.
  - Cold indexing cost per PR worktree, which is exactly the cost that rules out LSP.
  - It would violate modularity guardrails #1/#2 (§3.7).

  So "just do PSI" is not blocked by physics; it is a ~100× distribution cost and a boundary
  violation, bought for a precision gain the consumer barely values.

#### ⚠️ PSI cannot see the PR code — the review runs outside the project

Found while re-evaluating PSI after learning usage is IntelliJ-primary. This is the constraint that
actually determines what a PSI provider can and cannot do, and it is easy to miss:

**The review does not run against the open project.** `WebviewPanel` creates a detached git worktree
at `$TMPDIR/pr-pilot-wt-<unique>` (`WebviewPanel.java:1434`) and points the provider at it. That path
is **not a content root of the open project**, so it is not in any module and not in the index.

Consequence: PSI over the PR worktree gives a **parse tree without resolution**. `PsiManager` will
happily return a `PsiFile` for a file outside the project, but `resolve()` has no module classpath
and `ReferencesSearch` over `projectScope` does not include it. Parsing-without-resolution is roughly
what tree-sitter already provides, for vastly more complexity — so *that* version of the PSI plan is
not worth building.

**The version that does work inverts the query.** Do not ask PSI about the PR code; ask it about the
*existing* code:

| Question | Best source |
|---|---|
| What changed? | The diff — already in the prompt |
| **Who calls the symbol whose signature changed?** | **PSI over the open project's warm index** |
| What does the changed code look like in full? | Read tool over the worktree — already granted |

Blast radius is a question about the **base** codebase, and the open project is already indexed for
exactly that. Resolve the changed symbol's name from the diff, then run `ReferencesSearch` in the
project's scope. New callers introduced *by the PR* are visible in the diff anyway.

Caveats to keep honest: the open project sits at whatever branch/commit the user has checked out, so
the answer is approximate w.r.t. the PR base; it is unavailable during indexing (dumb mode) and for
languages the installed IDE cannot parse. All three argue for the textual strategy remaining the
floor and PSI being an *enrichment* — which is exactly the provider model above.

One incidental benefit: `plugin.xml` declares `depends com.intellij.java` while using no Java PSI
(§2.1). A PSI provider would finally justify that dependency, or prove it should be dropped.

#### Headless IntelliJ / Qodana — evaluated, and it splits into two very different ideas

**(A) Bundling a headless IntelliJ in the sidecar to answer PSI queries — no.** It is dominated at
every point, which is a stronger claim than "expensive":

| Who | Better option | Why headless loses |
|---|---|---|
| IntelliJ users (the majority, §8.1) | **In-process PSI** | The IDE is already running and the project already indexed — zero extra bytes, zero indexing wait |
| Everyone else | **Textual strategy** | Costs nothing and needs no runtime |
| CI / Action | **Run Qodana as a CI step** and ingest its annotations (B) | Same analysis, no runtime for us to own |

It would cost ~18 MB → ~1–2 GB plus minutes of cold indexing *per review* to serve a middle case
that does not exist. Note the indexing cost is the same objection that rules out the language-server
strategy — a headless IDE does not escape it, it pays it on every PR worktree.

**Re-evaluated when the drift objection was raised against 3c, and the answer held — for sharper
reasons.** If in-process PSI indexes the wrong tree (§Phase 3, 3c), headless-over-the-worktree looks
attractive because the worktree *is* the right tree. Two measured facts and one design fact close it:

| | Measured |
|---|---|
| Shipped sidecar jar | **18 MB** |
| Plugin distribution | **6.4 MB** |
| Qodana-class headless runtime | **1–2 GB** — ~100× the entire artifact |

The runtime cost is also **worse than "per review" implies**: the worktree is a full checkout (no
`--depth`, no sparse-checkout) created per PR and destroyed on PR switch (`WebviewPanel.java:559`,
`:1473-1489`). A different PR is different content, so nothing amortizes — there is no caching path
that a smarter implementation could exploit.

And the design fact that makes it unnecessary: the correct tree is **already on disk and already
readable** by the agent. Confirming PSI's candidates against the worktree recovers the ground truth
headless would have provided, for zero extra bytes. Headless buys correctness the repo can get for
free.

Headless remains the right tool for exactly one case — repo-wide inspection with no IDE present,
i.e. CI — and there (B) is already the better answer.

**(B) Ingesting CI's file-anchored annotations — yes, and it mostly worked already.** GitHub
surfaces **check-run annotations** (`path` + `start_line` + `message` — literally review-comment
shaped) and Phase 1's `CheckRunService` already fetches them. Qodana reaches them via SARIF →
code scanning, but SARIF is only one producer: Actions workflow commands (`::error file=,line=`),
problem matchers registered by setup actions, and any app writing check runs with
`output.annotations` (reviewdog, JUnit reporters) all land in the same place. So the ingestion is
**not** gated on a repo running SARIF-based analysis — it benefits any repo whose CI emits
file-anchored annotations.

**The gap, now fixed.** Annotations were fetched only for checks where `isFailing()` — but
static-analysis checks are routinely configured as **advisory**, concluding `success` or `neutral`
while still reporting findings. Qodana, CodeQL, and ktlint all commonly run that way, so the most
review-shaped evidence CI produces was being discarded precisely when the build was green.

The fix keys on `output.annotations_count`, which arrives in the check-runs list response already
fetched — so it costs **no extra request** to test. Deliberately a **union** with `isFailing()`
rather than a replacement, so a provider that omits the field keeps today's behavior and cannot
regress. Failing checks still take priority for the bounded annotation budget, so advisory lint
notes cannot crowd out a broken build. Mutation-verified: reverting to failure-only selection fails
two tests.

Scope of the *marginal* gain, stated honestly: failing checks were already covered, so this fix
only adds checks that annotate while concluding `success`/`neutral`. That it costs no extra request
is verified; how common such checks are is **not measured**. The claim below is a cost argument,
not an impact measurement.

This needs no IDE, benefits every host, and gets stronger the more analysis a team already runs in
CI — at a build cost far below any PSI work. Whether it *outranks* PSI on delivered review quality
is untested.

#### Strategies are *providers*, not tiers

The framing that actually resolves this: define **one host-neutral `CodeIntel` model in `core`** with
pluggable providers.

- `review-engine` supplies the textual provider → every client gets code intelligence.
- IntelliJ *may later* supply a PSI-backed provider that populates the **same** model in-process,
  where the platform is already loaded and the index already warm — no sidecar, no 1–2 GB jar.
- The prompt consumes `CodeIntel` and does not care which provider produced it.

This is legitimate under the capability rule: the *capability* lives in the engine and is reachable
by every host. A PSI provider is an optional **enrichment**, which is the documented-gap case in
`AGENTS.md`, not a host re-implementing engine logic.

It also means the choice is **not** "textual now, throw it away for PSI later" — the textual provider
remains the floor for every non-IntelliJ client permanently.

### 3.4 Self-executed tests deferred indefinitely

CI answers "which tests fail." Self-execution only adds **agent-authored hypothesis tests** ("write a
failing test to prove this bug"). Real, but speculative and expensive. Revisit only after Phases 0–5
are measured.

### 3.5 Reversed decisions, recorded

| Originally proposed | Revised to | Why |
|---|---|---|
| Deep PSI/inspection integration in IntelliJ | Textual strategy first; PSI only as a later in-process provider | PSI over RPC costs ~18 MB → ~1–2 GB for precision an LLM barely values (§3.3) |
| "Headless LSP dominates PSI" | PSI is faster and cheaper; the language-server strategy is deferred | Cold-start cost + LSP requires the sandbox |
| "PSI **cannot** sit behind the engine interface" | PSI is cost-prohibitive there, not impossible | Headless IntelliJ is a shipped configuration (Qodana). Stating it as impossible hid the real tradeoff and invited "why not just do PSI?" |
| Numbering the strategies Tier 1/2/3 | Named strategies with an explicit **reach** column | The numbering read as a quality ladder, implying Tier 3 supersedes Tier 1 and could be jumped to |
| Sandbox as the gating Phase 2 | Removed from critical path | CI already provides diagnostics, tests, lint |
| Hard feature parity across hosts | Capability parity at the engine boundary (§3.7) | Forced dual implementation; a coverage test enforces the real goal better |
| CI-annotation ingestion framed as "Qodana/SARIF" | Framed as **check-run annotations**, of which SARIF is one producer (§3.3(B)) | The SARIF framing implied the payoff was gated on running Qodana/CodeQL. `CheckRunService` reads `/check-runs/{id}/annotations`, which Actions workflow commands, problem matchers, and test reporters populate too — the reachable set is far wider than SARIF users |

Recurring error in both reversals: reaching for a heavyweight mechanism when cheaper data was already
available.

### 3.6 VS Code has no PSI equivalent

A recurring misconception worth recording, because it drives the tiering in §3.3.

| | IntelliJ PSI | VS Code |
|---|---|---|
| Semantic model | **Built-in** — in-process, synchronous, fully resolved, index-backed | **None** — VS Code core is a text editor |
| Query mechanism | Direct API (`PsiMethod`, `ReferencesSearch`, type hierarchy) | RPC facade (`vscode.executeReferenceProvider`, `languages.getDiagnostics`) |
| Who answers | The IDE itself | Whatever **language extension the user happens to have installed** |
| Guarantees | Complete, synchronous, readiness knowable | Async, eventually consistent, no readiness signal, quality varies by extension |

VS Code's API is a thin shim over an *optional third-party language server*. No Java extension
installed → no Java semantics, silently.

This is the core argument for the textual strategy: **ripgrep works identically in both hosts with zero dependency
on installed extensions.**

### 3.7 Parity model: capability parity at the engine boundary

**Supersedes the hard feature-parity rule in `AGENTS.md`.**

The old invariant — implement every user-facing feature in both hosts, in the same change, policed by
a 15-row mapping table — forces simultaneous dual implementation and is the single largest tax on
velocity. The *goal* behind it (don't fork logic per host) is better served by a strict boundary than
by a checklist.

New invariant:

> Every capability is defined on a host-neutral engine interface, and the sidecar must expose **100%**
> of that interface over RPC. **Hosts may lag in consuming it.**

Consequences:

- IntelliJ leads; VS Code wiring is deferrable per capability without forking anything
- Reviving VS Code = write a `sidecar.ts` client method + UI wiring. The engine logic, the RPC method,
  and their tests already exist and are enforced
- Reimplementing engine logic in TypeScript becomes structurally impossible, not merely discouraged
- CLI and GitHub Action clients fall out of the same surface for free

**IntelliJ does not move onto the sidecar.** It keeps calling the engine interface in-process — no
extra process, no added latency. The sidecar is an RPC *adapter* over that same interface. This
replaces most of the old Phase 6.

#### VS Code support floor

To prevent silent rot, VS Code must always: build, activate, initialize the sidecar, list PRs, select
a PR, generate a review, and submit. Capabilities beyond that floor may lag. Enforced by the existing
CI sidecar smoke test plus the packaged-VSIX assertion.

#### Modularity guardrails (non-negotiable)

1. No IntelliJ types in `core`, `github-engine`, `review-engine` — true today, must stay true
2. Every new capability lands on the engine interface **first**, host wiring second
3. `webview/` stays host-neutral and shared — it is UI, not host code
4. `webview/src/bridge/types.ts` remains the host↔UI contract
5. Deleting a TypeScript reimplementation is always preferred over updating it

---

## 4. Phases

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

### Phase 3 — Textual code intelligence `[M]` · ◐ IN PROGRESS (3a, 3d done · **3c next**)

**Zero host wiring** for 3a/3b. Computed in `review-engine` from the worktree dir both hosts already
pass in, so no new RPC methods and no `IntellijGitHubService` changes. 3c is IntelliJ-in-process and
is the one step that does touch a host.

Split into steps. **Re-ordered after learning usage is IntelliJ-primary** (§8.1) — see the note
below on why 3c overtook 3b.

**3a — Direct the agent · ✅ DONE.** `READ_ONLY_TOOLS = "Read Grep Glob"` was already granted on
every spawn and Copilot already approves read-kind tools, but the prompt contained **zero** mentions
of callers or blast radius — the capability was granted and unused. Added a `Blast radius:` directive
to `REVIEW_INSTRUCTIONS` telling the model to Grep for call sites before flagging a signature,
contract, serialized-shape, config-key, or removed-symbol change.

Crucially it also states **what the result means**, since a directive to search is useless without
one: unupdated callers make it a confirmed `issue`; a diff that already updates every caller is
usually not worth reporting at all; an inconclusive search must be declared and drop to
`confidence: low`. That last clause is what keeps the directive from *manufacturing* findings.

Both providers share `ClaudeService.buildPrompt` (`CopilotService.java:102`), so this reached Claude
and Copilot in one change. No new code path, no new dependency, no host wiring.

**3c — PSI blast-radius provider (IntelliJ, in-process) · hold for an outcome baseline.** Resolve
the changed symbol from
the diff, then `ReferencesSearch` over the **open project's** warm index — not the worktree, which is
not indexed (see the constraint above). In-process only, so no sidecar growth and no `review-engine`
dependency on the IDE platform. Must degrade cleanly during indexing (dumb mode) and for unsupported
languages.

**What PSI adds over 3a's Grep, given the agent must confirm anyway.** Grep matches *names*; PSI
resolves *symbols*. It distinguishes `close()` on the changed type from the twenty unrelated
`close()` calls in the repo, follows overrides and interface implementations to call sites that never
mention the changed type's name, and knows an import alias or static import still refers to the
symbol. Those are the cases a textual search either drowns in or misses entirely. So PSI's
contribution is a **precise, short candidate list** — the expensive part — and confirmation is cheap
verification of specific locations, not a second search.

**3c is a candidate generator, not an oracle — this is a correctness requirement, not polish.** The
open project sits at whatever the user has checked out, so PSI answers about the *wrong tree*. The
realistic failure is not staleness but a reviewer sitting on their **own feature branch**:
`ReferencesSearch` then returns call sites that exist only in local WIP and attributes them to
someone else's PR. That manufactures findings, which is precisely what 3a's directive was written to
prevent — so shipping 3c as an authority would regress the guarantee 3a bought.

The resolution: PSI **proposes** call sites; the agent **confirms** each against the worktree it can
already Read (3a's capability). Drift-induced false positives die at the confirmation step. Residual
false negatives — a caller in the PR base but absent locally — are also missed by 3a today, so this
is not a regression. This buys a headless IDE's ground truth at zero extra distributed bytes, because
the correct code is already on disk.

Two prerequisites, both absent today and both nearly free:

- **Capture the base SHA.** `PrDetailService.parseBaseRepo` reads only `base.repo.full_name`; a
  repo-wide search for `baseSha`/`merge-base` returns nothing. It is already in the PR detail JSON.
- **Read the open project's HEAD.** Never read today (only `.git/config` is, for the repo slug). One
  `git rev-parse` makes drift *measurable* and lets 3c abstain when divergence is large, instead of
  degrading silently.

**3d — Pin the worktree to the reviewed head SHA `[S]` · ✅ DONE.** Not new capability — a
correctness fix to shipped behavior. `GitWorktreeService` ran `git worktree add --detach <dir>
origin/<branch>`, tracking the **branch tip** rather than the `head.sha` the diff was rendered from,
so a mid-review push left **3a grepping code that was not under review**. `head.sha` was already
fetched for CI staleness and simply not passed here.

Shipped: `createWorktree`/`createWorktreeFromFork` take `headSha` and resolve through
`pinnedCommitish`. Forks were **not** already exact — `FETCH_HEAD` is the fork branch's tip and
drifts identically, so they get the same pinning. Pinning falls back to the tip when the SHA is
blank or absent after fetch (force-push), because callers degrade worktree failure all the way to
the user's own checkout, which is a worse tree than a stale one. `head.sha` comes from the GitHub
API, so it is validated as a hex object name first — `HEAD`, `main`, and `--upload-pack=…` are
refused rather than handed to git as a revision. `PRHeadInfo` gained `sha`; mirrored into
`vscode-extension/src/worktree.ts` per the `AGENTS.md` parity table. 8 new Java tests, 5 new VS Code
tests, both covering the tip-moved case that fails without the fix. *(That mirror has since been
retired — the whole worktree path now runs through the `worktrees` capability, so the pinning has
one implementation. See the Phase A follow-ups.)*

**3b — Pre-computed ripgrep sketch (only if still needed).** Host-side ripgrep over the worktree →
`<code_intelligence>`. **Demoted below 3c**: it largely pre-computes what 3a already lets the agent
do itself, so its marginal value is the smallest of the three, and it serves the host with the fewest
users. Build it when a non-IntelliJ client actually needs parity, or when 3a proves insufficient
there.

3b and 3c feed a host-neutral **`CodeIntel` model in `core`** behind a provider seam (§3.3), so the
prompt consumes one shape and never learns which provider produced it. **That model does not exist
yet** — no `CodeIntel` type is present in any module today. 3a deliberately did not create it: a
prompt directive produces no structured data, so building the seam before 3c would have been
speculative. **3c is therefore the change that introduces `CodeIntel`**, and it should define the
model against a second (3b) consumer in mind rather than shaping it to PSI alone.

Measurement note: 3a vs. 3c is exactly the kind of ambiguous change Phase 0's outcome log exists to
adjudicate. Worth logging before 3c, not before 3a.

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

### Phase 5 — Agentic verification loop `[L]` · depends on Phases 1, 3

The CLIs are already agents; PR Pilot currently uses them as completion APIs (`--max-turns 15`,
one-shot JSON).

- Let the agent iterate: gather evidence per finding until confirmed or dropped
- Findings carry provenance (which evidence supported them)
- **Retire chunked mode** — the agent scopes itself; batching that forbids cross-file findings is
  actively harmful
- Retire heuristic trust-scoring in favour of evidence

### Phase 6 — Additional clients `[L]` · ⏸ SPECULATIVE — do not build on current evidence

Most of the old Phase 6 is absorbed into Phase A — the capability boundary, the coverage test, and
retiring the parity table all happen up front now.

**Demoted** after §8.1 resolved: usage is primarily IntelliJ. The CLI and Action were justified by
clients that do not exist for users who are not asking for them. Building them now is speculative
work that also raises the cost of every future engine change.

Deferred until there is demand:

- **CLI client** over the sidecar's RPC surface — review-on-demand without an editor
- **GitHub Action** using the same client — review-on-push without a human opening an editor
- **VS Code catch-up**: wire the deferred client methods

Worth noting: Phase A already did the expensive part. The capability boundary and coverage test mean
these stay *cheap to add later* rather than needing to be pre-built — which is precisely why
deferring them is safe rather than a bet.

One item survives the demotion, because it pays off independently of any new client:

- Delete VS Code's duplicated prompt/worktree/binary-probe TypeScript as each capability moves behind
  RPC (guardrail #5). This reduces maintenance on code that already exists, and Phase 1 already
  removed the review/chat prompt duplication this way.

### Phase 7 — Close the loop `[M]`  depends on Phases 0, 6

- Feed the outcome store back into `knownPatterns` — patterns the team demonstrably accepts
- Per-repo prompt tuning driven by keep-rate

---

## 5. Deferred

| Item | Why deferred | Revisit when |
|---|---|---|
| Execution sandbox | CI provides diagnostics/tests/lint; sandbox is redundant compute + RCE surface | Only if hypothesis-testing proves necessary |
| Language-server strategy | Requires the sandbox; slower than PSI; high per-language maintenance | After textual-strategy measurement shows it is insufficient |
| IntelliJ PSI provider | **Not impossible — too expensive for the gain.** Over RPC it means `review-engine` depends on the IDE platform and the sidecar grows ~18 MB → ~1–2 GB, plus cold indexing per worktree, for a precision gain an LLM barely values (§3.3) | Two separate triggers: (a) as an **in-process IntelliJ-only provider** behind the shared `CodeIntel` model — viable as soon as measurement shows textual recall is the bottleneck, and it needs no sidecar change; (b) as an **RPC capability** — only if PR Pilot becomes IntelliJ-exclusive *and* abandons the CLI/Action goal |
| Agent-authored tests | Speculative, expensive | After Phases 0–5 measured |

---

## 5.1 Host work summary

Under §3.7, engine + RPC work is mandatory; **IntelliJ wiring leads and VS Code wiring is deferred.**

| Phase | Engine (`*-engine`) | Sidecar RPC | IntelliJ | VS Code |
|---|---|---|---|---|
| A Capability boundary | Interfaces | Full dispatch + coverage test | none | none |
| 0 Outcome logging | **All of it** | exposed | none | deferred |
| 1 Context / CI | **All of it** | exposed | pass-through | **deferred** |
| 2 Trust fixes | — | — | 1 line | 1 line |
| 3 Textual code intel | **All of it** | exposed | none | none |
| 4 CI suppression | **All of it** | exposed | none | none |

Phase 2 is almost entirely shared `webview/` code, so it lands in both hosts regardless of parity
policy. Phases 3–4 need no host wiring at all.

Effort saved by dropping parity: roughly the `sidecar.ts` client + `extension.ts` UI wiring per
capability — real, but the larger win is **velocity**, not line count.

---

## 6. Sequencing summary

```
Phase A  Capability boundary  ─────► ✅ DONE; VS Code is revivable
         Environment blockers ─────► ✅ DONE; all required verification commands pass
         Shared REST transport ────► ✅ DONE; prerequisite for Phase 1
Phase 1  Context (CI!)        ─────► ✅ DONE; VS Code consumption wired later — it had shipped
                                     capability-complete but unconsumed on that host
Phase 2  Trust fixes          ─────► ✅ DONE
Phase 3  Code intelligence    ─┬───► 3a agent grep directive  ✅ DONE — shipped in REVIEW_INSTRUCTIONS
                               ├───► 3d pin worktree to head  ✅ DONE — 3a now greps the reviewed code
                               ├───► 3c PSI candidates        [M] IntelliJ; hold for outcome baseline
                               └───► 3b ripgrep precompute    demoted; smallest marginal value
Phase 4  CI suppression       ─────► ✅ DONE; deterministic dedupe + evidence-based calibration.
                                     Escalate/triage deferred — prompt changes, need Phase 0 data
Phase 0  Outcome logging      ─────► ✅ DONE; both hosts log on submit. IntelliJ was recording
                                     fabricated rows on a cross-PR submit — fixed; baseline
                                     restarts from that fix
Phase 5  Agentic loop         ─────► needs 1 + 3
Phase 6  CLI + Action clients ─────► ⏸ SPECULATIVE — usage is IntelliJ-primary (§8.1)
Phase 7  Feedback → patterns  ─────► needs 0; no longer gated on 6
```

**Now: still not 3c — the gate has not opened, and the instrument needed repair first.**
`~/.pr-pilot/review-outcomes.jsonl` holds **one record**. Checking why surfaced a state-lifetime bug
in `WebviewPanel` that recorded *fabricated* outcomes whenever a reviewer generated on one PR and
submitted another (see Phase 0). That is now fixed, but it means the trustworthy baseline starts
from the fix, not from Phase 0's original ship date. Building the `[M]` 3c change now would still
waste the instrument Phase 0 exists to be.

The ungated work in the meantime is the **Phase A follow-ups**, which depend on no data at all.
Three are now done (derived capability map; TS-side wire-catalog enforcement; `IntellijGitHubService`
consuming `GitHubEngineApi`) and the fourth is in progress (duplicated TypeScript: `guidelines.ts`
and `worktree.ts` retired behind new capabilities; binary probing, notifications, and prompt
constants remain). Each has paid for itself by exposing a live defect — a `REQUIRED_CAPABILITIES`
gap that reproduced Phase 1's silent-degradation failure, a truncation-marker divergence between the
two guidance readers, and two different worktree temp-directory name formats in a path whose cleanup
matches on that name.

**Then 3c**, decided with evidence rather than intuition. It comes before 3b because usage is
IntelliJ-primary and PSI resolves symbols where Grep only matches names. Note 3c does **not** read
the worktree for its search — the worktree is not indexed; it queries the open project's warm index
(§3.3) and then *confirms* candidates against the worktree, which 3d made a sound assumption. It is
also the change that first introduces the `CodeIntel` model, which does not exist yet.

Note that Phase 4's *suppression* idea partly landed inside Phase 2: because the critique pass now
receives `<ci_status>`, the directive already tells it to drop findings CI reports. What remains of
Phase 4 is the structured dedupe against CI **annotations** and confidence calibration.

Phase A first is the change dropping parity introduces. Without it, deferring VS Code means drift.
With it, deferring VS Code is safe and reversible — which the IntelliJ-primary usage data now makes
the actively correct call rather than merely a tolerable one.

---

## 7. Resolved decisions

| # | Question | Decision |
|---|---|---|
| 1 | Keep this doc in-repo? | **Keep until the plan is executed, then delete.** Not added to `ARCHITECTURE.md` layout |
| 2 | Host work / does VS Code have PSI? | **No PSI equivalent** — see §3.6. Host work is small; see §5.1 |
| 3 | Purpose of the outcome store | Instrumentation only; **does not improve reviews directly**. Reduced to minimal logging and **no longer gates Phase 1** |
| 4 | Parse raw CI logs? | **No.** Tiered A/B/C; Tier C hands a bounded log *tail* to the model rather than parsing. Opt-in per repo |
| 5 | Is CI completion required? | **No.** Purely additive, never blocking; degradation table in Phase 1 |

## 8. Open questions

1. ~~**Where are the users?**~~ — ✅ **ANSWERED: usage is primarily IntelliJ.** Consequences, in
   order of how much they change:
   - **Phase 3 re-ordered.** The PSI blast-radius provider (3c) now outranks the pre-computed
     ripgrep sketch (3b). 3a still leads — it is nearly free and helps every host.
   - **Phase 6 (CLI + GitHub Action) demoted to speculative.** It was justified by clients that do
     not exist yet, for users who are not asking for them. Do not build it on the current evidence.
   - **§3.7 parity model is validated, not undermined.** "IntelliJ leads, VS Code may lag in
     consuming" is exactly right for this distribution. The capability boundary was the correct
     investment: it is what makes leading with IntelliJ safe rather than a fork.
   - **VS Code stays on the support floor** — build, activate, initialize, list, select, review,
     submit — and no further. It is already built and test-enforced, so holding the floor is cheap,
     whereas deleting it is irreversible.

   **Caveat worth re-testing before acting on this too hard:** does "primarily IntelliJ" measure
   *preference* or *availability*? VS Code support is newer and its capabilities were deliberately
   deferred, so current usage may reflect where PR Pilot was shipped and promoted rather than where
   users would choose to be. Treat it as sufficient to re-order Phase 3 (low regret, reversible) and
   to pause Phase 6 (avoids speculative work), but **not** as grounds to delete VS Code.
2. **Outcome-log privacy.** Comment bodies in a local JSONL are fine; anything aggregated or uploaded
   needs a separate decision.
3. **CI log-tail opt-in mechanism.** Per-repo setting, or global off-by-default?
4. ~~**When does VS Code catch up?**~~ — deferred indefinitely by (1). Hold the support floor;
   revisit only if the availability-vs-preference caveat resolves toward real VS Code demand.


