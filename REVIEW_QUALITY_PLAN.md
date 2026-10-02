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

Status: ✅ all resolved. Full record archived in [review-quality-plan-completed.md](docs/archive/review-quality-plan-completed.md#23-dead-and-mis-defaulted-machinery---all-resolved).

### 2.4 Chunked mode degrades quality

- `ReviewPane.tsx:244-251` instructs each batch to **ignore findings outside its file set** — structurally forbids cross-file bugs, the highest-value class.
- Auto-enables at ≥8 files or ≥300 changed lines (`:270-287`) — exactly the PRs needing global reasoning.
- Merge (`:289-314`): exact-string dedupe on `file|line|type|body`; summaries **concatenated then `.slice(0, 790)`**, silently truncating later batches.
- Batch priority is **descending changed-line count only** — pure churn, ignoring path or file type.

### 2.5 Anchoring has silent failure modes — ✅ resolved or reasoned-declined

Status: ✅ resolved or reasoned-declined. Full record archived in [review-quality-plan-completed.md](docs/archive/review-quality-plan-completed.md#25-anchoring-has-silent-failure-modes---resolved-or-reasoned-declined).

### 2.6 Quality theater — ✅ resolved

Status: ✅ resolved. Full record archived in [review-quality-plan-completed.md](docs/archive/review-quality-plan-completed.md#26-quality-theater---resolved).

### 2.7 No feedback loop exists

Broad greps for telemetry / analytics / accepted / rejected / dismissed returned nothing. No record of
kept-vs-deleted comments, no GitHub resolution read-back, no persisted quality scores.

**Every prompt change is currently unfalsifiable.**

### 2.8 Documentation is materially wrong — ✅ fixed

Status: ✅ fixed. Full record archived in [review-quality-plan-completed.md](docs/archive/review-quality-plan-completed.md#28-documentation-is-materially-wrong---fixed).

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

Status: ✅ DONE. Full record archived in [review-quality-plan-completed.md](docs/archive/review-quality-plan-completed.md#phase-a--engine-capability-boundary-m---done).

### Phase 0 — Minimal outcome logging `[S]` · ✅ DONE

Status: ✅ DONE. Full record archived in [review-quality-plan-completed.md](docs/archive/review-quality-plan-completed.md#phase-0--minimal-outcome-logging-s---done).

### Phase 1 — Context service `[M]` · ✅ DONE (VS Code consumption landed later — see below)

Status: ✅ DONE (VS Code consumption landed later). Full record archived in [review-quality-plan-completed.md](docs/archive/review-quality-plan-completed.md#phase-1--context-service-m---done-vs-code-consumption-landed-later--see-below).

### Phase 2 — Trust and correctness fixes `[M]` · ✅ DONE

Status: ✅ DONE. Full record archived in [review-quality-plan-completed.md](docs/archive/review-quality-plan-completed.md#phase-2--trust-and-correctness-fixes-m---done).

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

Both providers share `ReviewPrompts.buildPrompt` (`CopilotService.java:102`), so this reached Claude
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

Status: ✅ DONE (escalate/triage deferred). Full record archived in [review-quality-plan-completed.md](docs/archive/review-quality-plan-completed.md#phase-4--ci-grounded-suppression-and-calibration-s---done-escalatetriage-deferred).

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


