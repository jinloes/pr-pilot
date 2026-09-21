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
    GitHubEngine-->>Host: Token-free results
    Host->>Worktree: Find repository root and create PR-head worktree
    Worktree-->>Host: Exact or failed worktree result
    Note over Host,Worktree: IntelliJ calls in-process while VS Code routes these calls through the sidecar.

    Reviewer->>UI: Generate review with per-review overrides
    UI->>Host: generateReview(prKey, options)
    Host->>Host: Snapshot generation ID, settings, provider, and guidance
    opt Explicit IntelliJ-assisted opt-in
        Host->>GitHubEngine: Refetch remote head and bounded diff
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

    alt Direct review
        Pipeline->>Provider: Primary review with read-only worktree tools
        Provider-->>Pipeline: Review JSON and inspection ledger
    else Chunked review
        loop Each bounded file batch
            Pipeline->>Provider: Review batch and record contract signals
            Provider-->>Pipeline: Batch review and inspection ledger
        end
        Pipeline->>Provider: Mandatory global reconciliation
        Provider-->>Pipeline: Complete reconciled review
    end

    Note over Pipeline,Provider: Deep execution brackets every stage with authority checks.<br/>All request copies retain evidence and separate pinned skill instructions.<br/>Invalid authority fails the whole deep result, including earlier candidates.
    opt Supervisor enabled and high-risk gaps remain
        Pipeline->>Pipeline: Validate anchors and analyze inspection coverage
        opt More than three candidate gaps
            Pipeline->>Provider: Tool-free prioritization of supplied gap IDs
            Provider-->>Pipeline: At most three selected targets
        end
        Pipeline->>Provider: One targeted read-only follow-up with MCP disabled
        Provider-->>Pipeline: Follow-up review and inspection ledger
        Pipeline->>Pipeline: Merge and deduplicate findings
    end

    opt Self-critique enabled
        Pipeline->>Pipeline: Build contract index from changed files, even for direct/single-batch reviews
        Pipeline->>Provider: Validate findings against bounded context and contract index
        Provider-->>Pipeline: Refined review
    end

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

    Host-->>UI: reviewResult(prKey, generation ID)
    UI-->>Reviewer: Editable review, findings, verdict, and activity history

    Note over Pipeline,UI: Lifecycle status is reduced to safe phase labels before display.<br/>Provider text and private thinking are never persisted or rendered.
    Note over Reviewer,ReviewEngine: Stop invalidates/cancels the matching operation.<br/>Deep worktree remains retained until explicit closed-project, lease-free, non-force cleanup.
```

## Failure behavior

- Missing optional GitHub context omits that prompt section without failing the review.
- Primary provider failure is terminal.
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
