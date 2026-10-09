# PR Review Generation Sequence

```mermaid
sequenceDiagram
    autonumber
    actor Reviewer
    participant UI as Shared webview
    participant Host as IDE host
    participant Sidecar as VS Code sidecar
    participant GitHubEngine as GitHubEngineApi
    participant GitHub as GitHub API
    participant ReviewEngine as ReviewEngineApi
    participant Worktree as GitWorktreeService
    participant Pipeline as ReviewPipelineService
    participant Provider as Claude or Copilot

    Reviewer->>UI: Select pull request
    UI->>Host: selectPR(prKey)
    Host->>GitHubEngine: Load detail, review diff, validation diff, and draft
    GitHubEngine->>GitHub: Authenticated read requests
    GitHub-->>GitHubEngine: PR metadata, diffs, and draft
    GitHubEngine->>GitHubEngine: Bound each diff by whole files (review 250 KB, validation 1 MB, 250 KB per file)
    GitHubEngine-->>Host: Token-free results, with a DiffCoverage trailer appended only when a diff omits files or its scan is incomplete
    Note over GitHubEngine,Host: A complete diff is returned byte-identical with no trailer.<br/>The trailer declares omitted/listed counts, the budget, scan completeness, and up to 200 omitted paths.<br/>HTTP 406 from GitHub becomes diff_too_large with non-retryable size-limit copy.
    Host->>Worktree: Find repository root and create PR-head worktree
    Worktree-->>Host: Exact or failed worktree result
    Note over Host,Worktree: IntelliJ calls in-process while VS Code routes these calls through the sidecar.

    Reviewer->>UI: Generate review with per-review overrides
    UI->>Host: generateReview(prKey, options)
    Host->>Host: Snapshot generation ID, settings, provider, and guidance
    opt Explicit IntelliJ-assisted opt-in
        Host->>GitHubEngine: Refetch remote head and bounded 1 MB validation diff
        Host->>ReviewEngine: prepareDeepReview(exact head)
        ReviewEngine->>Worktree: Retain exact-head worktree
        ReviewEngine-->>UI: Correlated manual setup instructions
        Note over Reviewer,Provider: No provider process runs during this pause.<br/>Trust/import can execute PR build code and is manual.
        Reviewer->>Reviewer: Open/trust exact IntelliJ project, sync and index
        Reviewer->>UI: Select server and Continue (or Cancel / ordinary fallback)
        UI->>Host: continueDeepReview(unique operation)
        Host->>Host: Recheck selection, remote head, saved settings and operation
        Host->>ReviewEngine: Begin engine-owned semantic execution
        ReviewEngine->>ReviewEngine: Acquire OS lease; physical coverage plus native snapshot READY
        Note over Host,ReviewEngine: Readiness failure preserves setup for explicit Retry.<br/>Request evidence alone never grants execution authority.
    end

    opt Review changes since last review (incremental, non-deep)
        Host->>GitHubEngine: getIncrementalDiff(prKey)
        GitHubEngine->>GitHub: Viewer's latest review commit, PR history, compare baseline...head
        GitHub-->>GitHubEngine: Baseline and compare diff
        alt Baseline trusted and diff non-empty
            GitHubEngine-->>Host: scope incremental (baselineSha, bounded diff)
            Host->>Host: Use incremental diff as model diff and set incrementalBaselineSha (chunked mode off)
        else Fallback reason
            GitHubEngine-->>Host: scope full (fallbackReason)
        else GitHub failure
            GitHubEngine-->>Host: Non-ok status
            Host-->>UI: reviewError
        end
        Note over Host,GitHubEngine: activeDiff, the published diff and validationDiff stay PR diffs.<br/>incrementalBaselineSha adds a review_scope prompt section.
    end

    par Load additive review context
        Host->>GitHubEngine: Get checks and CI annotations
        GitHubEngine->>GitHub: Read check runs and annotations
        GitHub-->>GitHubEngine: CI state
        GitHubEngine-->>Host: Bounded CI summary and annotations
    and
        Host->>GitHubEngine: Get commits and linked issues
        GitHubEngine->>GitHub: Read commits and referenced issues
        GitHub-->>GitHubEngine: Commit and issue context
        GitHubEngine-->>Host: Bounded prompt context
    and
        Host->>GitHubEngine: Detect repository profile
        GitHubEngine-->>Host: Languages and build tools
    end

    alt IntelliJ
        Host->>Pipeline: review(request, options) in-process
    else VS Code
        Host->>Sidecar: reviews/generate JSON-RPC request
        Sidecar->>ReviewEngine: generate(params)
        ReviewEngine->>Pipeline: review(request, options)
    end
    Note over Host,Pipeline: PRReviewRequest strips any trailer into DiffCoverage, so pr_diff never contains it.<br/>Incomplete coverage adds an escaped omitted_files section to review and critique prompts.

    opt baseSha supplied
        Pipeline->>Pipeline: BaseCommitContext reads guidance (configured guidance globs first, then built-in defaults), changed-file history and call sites of changed symbols from base-commit git objects only (fetch by SHA if missing; fail-open)
    end

    opt reviewRulesDirectory configured
        Pipeline->>Pipeline: LocalReviewRules loads bounded local rule files (fail-open)
    end

    alt Direct review
        Pipeline->>Provider: Primary review with read-only worktree tools, of the 250 KB review diff for single-pass or the 1 MB validation diff for IntelliJ-assisted deep review
        Provider-->>Pipeline: Review JSON and inspection ledger
    else Chunked review
        Note over Pipeline,Provider: Chunked review uses the 1 MB validation diff, and every batch copy keeps its coverage.
        loop Each bounded file batch
            Pipeline->>Provider: Review batch and record contract signals
            Provider-->>Pipeline: Batch review and inspection ledger
        end
        Pipeline->>Provider: Mandatory global reconciliation
        Provider-->>Pipeline: Complete reconciled review
    end

    opt Second reviewer model configured (non-deep)
        Note over Pipeline,Provider: Started in parallel with the primary; cancelled with it.
        Pipeline->>Provider: Same request to the second Copilot model
        Provider-->>Pipeline: Second review (errors and timeouts are ignored)
        Pipeline->>Pipeline: Merge and deduplicate with the primary findings
    end

    Note over Pipeline,Provider: Deep execution brackets every stage with authority checks.<br/>All request copies retain evidence and separate pinned skill instructions.<br/>Invalid authority fails the whole deep result, including earlier candidates.
    opt Supervisor enabled and coverage gaps remain
        Pipeline->>Pipeline: Validate anchors and analyze inspection coverage
        opt More than three high-risk hunk gaps outside uncovered files
            Pipeline->>Provider: Tool-free prioritization of supplied gap IDs
            Provider-->>Pipeline: At most five selected targets (failure skips hunk follow-up only)
        end
        loop Hunk follow-up, then uncovered files in batches of six (at most five file batches)
            Pipeline->>Provider: Read-only follow-up with MCP disabled
            Provider-->>Pipeline: Follow-up review (a failed batch is skipped)
            Pipeline->>Pipeline: Merge and deduplicate findings
        end
    end

    opt Review rules loaded
        opt Any structured rule
            Pipeline->>Provider: Tool-free trigger selection over metadata, changed files and rule triggers
            Provider-->>Pipeline: Triggered rule names (failure or bad output applies every rule)
        end
        loop Each selected rule (at most 25, 3 concurrently, 12-minute phase deadline)
            Pipeline->>Provider: Read-only rule agent with MCP disabled, one rule only
            Provider-->>Pipeline: Rule findings tagged "(rule: name)" (a failed rule is skipped)
        end
        Pipeline->>Pipeline: Merge rule findings into the draft
    end

    opt Recall mode (self-critique or a second reviewer)
        Pipeline->>Provider: Read-only hygiene pass with MCP disabled (logging, comments, removed protobuf fields)
        Provider-->>Pipeline: Hygiene findings (failure is reported and skipped)
        Pipeline->>Pipeline: Merge hygiene findings into the draft
    end

    opt Self-critique enabled
        Pipeline->>Pipeline: Build contract index from changed files, even for direct/single-batch reviews
        Pipeline->>Provider: Validate findings against bounded context and contract index; confirm or drop recall candidates
        Provider-->>Pipeline: Refined review
        Pipeline->>Pipeline: Restore anchored hygiene and rule findings the critique dropped (dedup by file, ±2 lines, category)
        opt prpilot.review.reportDropped=true
            Pipeline-->>Host: One status per draft finding validation dropped
        end
    end
    Pipeline->>Pipeline: Drop unconfirmed low-confidence candidates and cap the final comment count

    Pipeline->>Pipeline: Validate changed-line anchors and suppress CI duplicates
    opt IntelliJ-assisted execution
        Pipeline->>ReviewEngine: Final physical/native authority validation
        Host->>GitHubEngine: Recheck remote head before publication
        Note over Host,ReviewEngine: Lease remains held through delivery; stale or disposed host cannot publish success.
    end

    alt IntelliJ
        Pipeline-->>Host: Completion callback with final ReviewResult
    else VS Code
        Pipeline-->>ReviewEngine: Final ReviewResult
        ReviewEngine-->>Sidecar: JSON-RPC result
        Sidecar-->>Host: Parsed ReviewResult
    end

    Host-->>UI: reviewResult(prKey, generation ID, reviewScope only when incremental was requested)
    UI-->>Reviewer: Editable review, findings, verdict, and activity history

    Note over Pipeline,UI: Lifecycle status is reduced to safe phase labels before display.<br/>Provider text and private thinking are never persisted or rendered.
    Note over Reviewer,ReviewEngine: Stop invalidates/cancels the matching operation.<br/>Deep worktree remains retained until explicit closed-project, lease-free, non-force cleanup.
```

## Failure behavior

- Missing optional GitHub context omits that prompt section without failing the review.
- A diff over the review budget is not cut mid-file: whole files are omitted, the trailer and
  `<omitted_files>` name them, and the webview banner states the review coverage.
- GitHub HTTP 406 for a diff is attempted once and surfaces as `diff_too_large`, not a retryable
  API failure.
- An incremental request whose diff cannot be trusted reviews the full PR and reports the fallback
  reason. A GitHub failure while fetching the incremental diff becomes `reviewError`.
- Primary provider failure is terminal.
- Base-commit enrichment failures (unreachable commit, timeout, non-git directory) review without
  base guidance or history. Second-reviewer and hygiene-pass failures are ignored.
- Ordinary supervisor selection, targeted follow-up, and final critique failures keep the best
  valid review. Deep fallback candidates additionally require current engine-owned authority;
  stale native/physical evidence never becomes a success-shaped deep response.
- Chunk reconciliation failure returns a visibly marked degraded batch-only result.
- Cancellation and interruption propagate rather than returning a success-shaped fallback.
- Retention persists through cancellation, disposal and restart. Cleanup requires explicit
  confirmation that the exact project is closed, and rejects active leases or dirty/tampered trees.

## Evidence boundary

Automated tests exercise both actual provider adapters with fake IO across primary, chunk copies,
reconciliation, supervisor selection/follow-up and final critique, including single-batch contract-index
coverage and invalidation after best-effort provider failure. Raw-native-input tests separately exercise service orchestration and
scheduled MCP dispatch. Neither proves this installed-host sequence: private IntelliJ and VS Code,
each provider, clean/defect ordinary/deep runs, delayed generation and cleanup remain live gates.
