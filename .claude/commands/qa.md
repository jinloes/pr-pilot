---
description: Run thorough QA on this project — mechanical checks, IntelliJ↔VS Code sync audit, and code review for bugs
allowed-tools: ["Bash", "Read", "Grep", "Glob", "Agent", "AskUserQuestion"]
---

# QA

Run a thorough QA pass on PR Pilot. Read-only — don't fix anything. If the user wants fixes, they'll ask after seeing the report.

## Phase 0 — Ask scope

Before any other work, use `AskUserQuestion` to ask:

> Should the bug-hunting phase scope to changed files or the whole codebase?
>
> - **Diff vs main only** — review files changed on the current branch (fast, focused)
> - **Whole codebase** — review every source file (thorough, slow, surfaces pre-existing issues)

Remember the answer for phase 4. The other phases run the same either way.

## Phase 1 — Mechanical checks (run in parallel)

Kick off these four Bash calls in a single message:

1. `./gradlew spotlessCheck :core:jvmTest :intellij-plugin:unitTest` — Java formatting + all JVM tests
2. `(cd webview && npm run lint && npx tsc --noEmit)` — webview ESLint + TypeScript
3. `(cd vscode-extension && npm run lint && npx tsc --noEmit)` — VS Code extension ESLint + TypeScript
4. `git status --short && git diff --stat origin/main...HEAD` — what's changed since main (informs phases 3 and 4)

If any command fails, capture the failing test name / lint rule / type error verbatim for the report. Don't retry.

## Phase 2 — IntelliJ ↔ VS Code sync audit

CLAUDE.md lists pairs of files that must stay in lockstep. Spawn an Explore agent with this brief:

> Audit the IntelliJ↔VS Code sync table in CLAUDE.md (search for "## IntelliJ ↔ VS Code sync obligations"). For each row, read both files and check whether the named symbol/logic actually matches across hosts. Specifically verify:
>
> - `REVIEW_INSTRUCTIONS` and `CHAT_PERSONA` constants in `core/src/jvmMain/kotlin/com/jinloes/prpilot/services/ClaudeService.kt` vs `vscode-extension/src/claude.ts` — strings should be character-identical
> - `buildPrompt` / `buildChatPrompt` / `buildFocusedChatPrompt` — same structure and template tags
> - stream-json parsing — both hosts handle `result.subtype == "success"`, `is_error`, `session_id`, and the max-turns → resume recovery
> - `encodeBody` / `decodeReview` / `buildCommentArray` / `effectiveBody` / `buildOrphanSection` in `core/src/commonMain/kotlin/com/jinloes/prpilot/services/GitHubService.kt` vs `vscode-extension/src/github.ts`
> - GitHub API call shapes (endpoints, headers, 422 fallback flow)
> - `searchPRs` query construction — JVM `PRToolWindowFactory.buildQuery` and TS `searchPRs` should produce equivalent `q=` strings
> - `findGhBinary` known paths — same list on both sides
> - Webview bridge message shapes in `webview/src/bridge/types.ts` — verify both `WebviewPanel.java` and `vscode-extension/src/extension.ts` handle every `IncomingMessage` variant
> - `PluginSettings` (JVM) vs `vscode-extension/package.json` contributes.configuration + reader in `extension.ts` — every setting present on one host should exist on the other (or have a documented reason not to)
>
> Report a punch list: for each drift, name both files, the symbol that differs, and a 1-line description of the divergence. Skip pairs that are in sync — only report drift. Under 400 words.

## Phase 3 — Test coverage on changed code

CLAUDE.md mandates 100% branch coverage for new/modified non-UI methods. Using the diff from phase 1:

- For each modified `.java` / `.kt` file outside `intellij-plugin/src/main/java/com/jinloes/prpilot/ui/` and outside the webview/vscode-extension TypeScript trees, check whether a corresponding test file under `core/src/jvmTest/` or `intellij-plugin/src/test/` was modified in the same diff.
- Flag any non-UI source file changed without a matching test change.

This is a heuristic — a pure refactor with no behavior change is fine. Note it in the report so the user can decide.

If the working tree is clean and the branch matches `origin/main`, skip this phase.

## Phase 4 — Bug-hunting code review

Spawn the relevant review skills in parallel. The set depends on the phase 0 answer:

**If "Diff vs main only":**

Use `git diff --name-only origin/main...HEAD` (plus uncommitted changes from phase 1) to build the file list, then group by language:

- Kotlin files (`*.kt`) → invoke `kotlin-review` skill on the changed files
- Java files (`*.java`) → invoke `java-review` skill on the changed files
- TypeScript/JavaScript files (`*.ts`, `*.tsx`, `*.js`) → invoke `js-review` skill on the changed files
- Always also invoke `security-review` on the full diff

Skip a language entirely if no files of that type changed.

**If "Whole codebase":**

- `kotlin-review` on `core/src/commonMain/kotlin` + `core/src/jvmMain/kotlin` + `core/src/jsMain/kotlin`
- `java-review` on `intellij-plugin/src/main/java`
- `js-review` on `webview/src` + `vscode-extension/src`
- `security-review` is diff-only by design — run it on `git diff origin/main...HEAD` if there are changes, otherwise skip it with a note

Run all four (or however many apply) as parallel Agent calls in a single message. Each review skill returns a punch list of findings.

## Phase 5 — Report

Output one consolidated punch list, grouped:

```
## QA Report

### Mechanical
- [PASS|FAIL] spotlessCheck
- [PASS|FAIL] :core:jvmTest
- [PASS|FAIL] :intellij-plugin:unitTest
- [PASS|FAIL] webview lint
- [PASS|FAIL] webview tsc
- [PASS|FAIL] vscode-extension lint
- [PASS|FAIL] vscode-extension tsc

### Sync drift
- <file A> ↔ <file B>: <what differs>

### Untested changes
- <source file> — no matching test changes in this diff

### Bugs / review findings
**Kotlin:**
- <file:line> — <issue>

**Java:**
- <file:line> — <issue>

**TypeScript:**
- <file:line> — <issue>

**Security:**
- <file:line> — <issue>

### Next steps
<1-2 sentences pointing at the highest-priority item>
```

If a section has no findings, write "None." under it rather than omitting it — the user should be able to tell at a glance which phases ran clean. If everything passes across all phases, lead with that in one line and skip the empty grid.