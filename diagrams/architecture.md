# PR Pilot Architecture

```mermaid
flowchart LR
    Reviewer(["Reviewer"])
    GitHub[("GitHub REST and<br/>GraphQL APIs")]
    GhCli["GitHub CLI<br/>token resolution"]
    Provider["Claude CLI or<br/>Copilot runtime"]
    Checkout[("Local repository<br/>and detached PR worktree")]
    OutcomeData[("~/.pr-pilot/<br/>review-outcomes.jsonl")]
    HostState[("Host persistence<br/>IntelliJ local files / VS Code globalState")]

    subgraph SharedUi["Shared UI - webview/"]
        App["App / ReviewPane"]
        Bridge["Typed host bridge"]
        App <--> Bridge
    end

    subgraph IntelliJ["IntelliJ host - intellij-plugin/"]
        ToolWindow["JCEF tool window<br/>WebviewPanel"]
        IntelliJAdapters["IntellijGitHubService<br/>IntellijClaudeService"]
        ToolWindow <--> IntelliJAdapters
    end

    subgraph VsCode["VS Code host - vscode-extension/"]
        EditorPanel["Editor webview panel<br/>extension.ts"]
        SidecarClient["sidecar.ts<br/>JSON-RPC client"]
        EditorPanel <--> SidecarClient
    end

    subgraph RpcAdapter["VS Code transport - sidecar/"]
        Sidecar["Stdio JSON-RPC adapter<br/>validation, dispatch, notifications"]
    end

    subgraph Engines["Host-neutral Java 17 engines"]
        subgraph GitHubEngine["github-engine/"]
            GitHubApi["GitHubEngineApi"]
            GitHubServices["PR discovery and review freshness,<br/>context, diff, draft, and submission services"]
            GitHubApi --> GitHubServices
        end

        subgraph ReviewEngine["review-engine/"]
            ReviewApi["ReviewEngineApi"]
            Session["ReviewSessionService"]
            Pipeline["ReviewPipelineService<br/>primary or chunked review,<br/>optional second reviewer,<br/>bounded supervision,<br/>critique, CI suppression"]
            BaseContext["BaseCommitContext<br/>base-commit guidance and history"]
            Worktrees["GitWorktreeService"]
            Outcomes["ReviewOutcomeLog"]
            ReviewApi --> Session
            Session --> Pipeline
            Pipeline --> BaseContext
            Session --> Worktrees
            Session --> Outcomes
        end

        Core["core/<br/>shared PR, review-status, comment,<br/>chat, and request models"]
        Core -. shared models .-> GitHubApi
        Core -. shared models .-> ReviewApi
    end

    Reviewer <--> App
    Bridge <--> ToolWindow
    Bridge <--> EditorPanel

    IntelliJAdapters --> GitHubApi
    IntelliJAdapters --> Pipeline
    IntelliJAdapters --> Worktrees
    IntelliJAdapters --> Outcomes

    SidecarClient <--> Sidecar
    Sidecar --> GitHubApi
    Sidecar --> ReviewApi

    GitHubServices --> GhCli
    GitHubServices <--> GitHub
    Worktrees <--> Checkout
    BaseContext -. read-only base-commit git objects .-> Checkout
    Pipeline <--> Provider
    Outcomes --> OutcomeData
    ToolWindow --> HostState
    EditorPanel --> HostState
```

## Boundary rules

- `core`, `github-engine`, and `review-engine` own host-neutral behavior.
- IntelliJ consumes GitHub capabilities through `GitHubEngineApi` and calls review-engine services
  in-process. VS Code reaches both capability surfaces only through the sidecar.
- The shared webview owns the user workflow and communicates through the typed host bridge.
- GitHub credentials remain inside `github-engine`; hosts receive only token-free results.
- Provider processes, worktrees, prompts, parsing, supervision, and review semantics remain inside
  `review-engine`.
- Engine capabilities are declared once and exposed to every host; hosts may not reimplement them.
# Semantic collection authorities

```mermaid
flowchart LR
    Manual["User: manually open / trust / import / index"]
    Consumer["Internal SourceInventoryClient(Launch).collect\nexplicit trusted Java / Jar / tools / environment"]
    Runtime["SemanticRuntime\ntrusted schema-1 machine config"]
    Bundle["Engine JAR: embedded worker\nschema-2 digest manifest"]
    Worker["Fresh ordinary JVM worker\nminimal Jar; bounded owned process tree"]
    Git["Git root / HEAD / index / blob identity"]
    CLI["Fixed ijctl call: no daemon, private args"]
    Native["Optional 262+ native inventory tool"]
    Index["Native roots / ProjectFileIndex / epochs"]
    Files["Worker-only secure no-follow full walk + disk/Git hashes"]
    VFS["Independent VFS children + source roots + stream hashes"]
    Evidence["Native DISCOVERED / VFS_VERIFIED\nnot disk / Git / READY"]
    Coverage["Worker COVERED\nreconciled source evidence, not deep review"]
    Manual -. "prerequisite, never automated" .-> Native
    Bundle --> Runtime
    Runtime --> Consumer
    Consumer --> Worker
    Worker --> Git
    Worker --> CLI
    CLI --> Native
    Native --> Index
    Native --> VFS
    Worker --> Files
    Native --> Evidence
    Evidence --> Worker
    Worker --> Coverage
    Coverage --> Consumer
```

The shared `SemanticReviewService` consumes this physical coverage before authorizing either
host/provider's deep pipeline. Library/SDK roots are metadata; external module
sources block. Native readiness is a separate tool below;
collection, provider-stage and delivery checks remain separate gates. Ordinary 261/MCP-absent
loading stays independent.

```mermaid
flowchart LR
    Status["Snapshot STATUS"] --> Arm["Pooled pre-import arming"]
    Arm --> Settings["Native settings plus fresh physical settings fork"]
    Settings --> Armed["ARMED: request manual sync"]
    Armed --> Start["Synchronous START freezes completed receipt"]
    Start --> Success["Paired SUCCESS plus unchanged finish receipt"]
    Success --> Baseline["Memory-only project baseline"]
    Changes["Settings document / VFS / list changes\nfailure / cancellation / disposal"] --> Invalid["Invalidate; late finish cannot restore"]
    Baseline --> Capture["CAPTURE / VERIFY\ncurrent settings and native inventory VERIFY"]
    Capture --> Ready["Native READY + bounded declaration limitations\nNOT physical source authority or future-stage lease"]
```

Production observations come from the strict real-platform adapter. Tests substitute raw
`NativeAccess` observations, not these states or results, and drain queued arm/finish work.
Successful service-entry tests are distinct from real-262 tracker/import/timing evidence.

```mermaid
flowchart LR
    UI["Shared opt-in / manual pause / Continue"]
    Hosts["IntelliJ callbacks or VS Code sidecar"]
    Lifecycle["Shared prepare / list / cleanup"]
    Retained["Durable exact-head worktree and OS lease"]
    Collector["SemanticReviewService: physical coverage + native READY"]
    Skills["Pinned full skills; trusted instructions"]
    Pipeline["Both providers and every pipeline stage"]
    Final["Revalidate before final delivery"]
    UI --> Hosts --> Lifecycle --> Retained
    Retained --> Collector --> Pipeline --> Final
    Skills --> Pipeline
    Final --> UI
    UI -. "explicit closed-project confirmation" .-> Lifecycle
```

No provider runs during manual setup. The engine keeps the lease through final delivery;
cancellation releases execution but preserves retention. Cleanup rejects active, dirty or
uncertain trees and never forces removal. Installed-host/provider matrices and native lifecycle
controls remain execution evidence, not conclusions inferred from this diagram or archive tests.
