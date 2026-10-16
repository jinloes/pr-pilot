# Module and runtime boundaries

Key design decisions moved from [ARCHITECTURE.md](../../ARCHITECTURE.md). Each section encodes an
active constraint future code must respect.

### Module boundaries
`core` is a plain Java 17 JVM module (Kotlin Multiplatform was removed once the `js` target and
every shared model class had no reason to stay Kotlin — the review-engine extraction moved the JS
target's last real consumers to the sidecar, and none of `PullRequest`/ `ReviewResult`/
`LineComment`/ `ChatMessage`/ `PRReviewRequest`/ `ReviewProvider` ever exercised
kotlinx.serialization's JSON encode/decode at runtime, so they were converted to plain Java classes
with JavaBean-style getters/setters matching their prior Kotlin-generated method names exactly — no
downstream call sites changed). The module's last Kotlin file (a unified diff parser) was later
converted to Java too for the same reason, then removed entirely once it was found to have no
production callers — actual diff parsing/rendering happens client-side in the webview via
`react-diff-view`. `core` has zero IntelliJ dependencies. `intellij-plugin` also owns two
IntelliJ-only local-file-index services (`PendingReviewIndex`, `SeenPRSet`, plain Java
records/classes using Jackson) rather than `core`, since they are not shared with VS Code — the VS
Code equivalent (`globalState`, see [Notification parity](hosts-and-sidecar.md#notification-parity))
is a different persistence mechanism entirely, so there is no cross-host code-sharing benefit to
keeping them in `core`. `github-engine` is a plain Java 17 library with no Spring or IDE APIs; both
`intellij-plugin` and `sidecar` depend on it. `review-engine` is a plain Java 17 library owning
Claude/Copilot CLI invocation, prompt building, and review-JSON parsing (`ClaudeService`,
`CopilotService`, `CopilotModelDiscovery`, `GitWorktreeService`); both `intellij-plugin` and
`sidecar` depend on it, so AI review generation has exactly one JVM implementation rather than being
duplicated per host. `github-engine` and `review-engine` depend on `core` for shared models.
`intellij-plugin` also depends on `core` and no longer needs `jackson-module-kotlin`/`KotlinModule`
now that `core`'s models are plain Java.

`sidecar` is a Java 17 Spring Boot application configured with `WebApplicationType.NONE`; it is not
an HTTP server. Its protocol uses bounded `Content-Length`-framed UTF-8 JSON-RPC over standard
input/output. Standard output must contain protocol frames only—diagnostics belong on standard
error. GitHub, PR, repository, and review behavior belongs in `github-engine`/`review-engine`;
Spring remains only the sidecar composition and lifecycle layer.

### core: plain Java module
`core`'s shared models (`model/*.java`) are plain Java classes with JavaBean getters/setters (e.g.
`getOwner()`, `isDraft()`) — no Kotlin-Java interop shims (`@JvmOverloads`, `@JvmStatic`,
`KotlinModule`) are needed anywhere downstream, and the module has no Kotlin plugin/dependency at
all.

### Modules compile with --release
The sidecar runs on the user's Java 17 or newer, and it bundles `core`, `github-engine` and
`review-engine`, so those modules may only call Java 17 APIs. `targetCompatibility` alone sets only
the bytecode version: on the JDK 21 that builds the project, a call such as `List.getFirst()` still
compiles and then throws `NoSuchMethodError` on Java 17. The root `build.gradle` sets every compile
task's `options.release` from the module's declared `targetCompatibility`, so javac rejects JDK APIs
newer than each module's target on every build, including the required `Gradle check`. Raise a
module's floor by changing its `targetCompatibility`; do not set `options.release` separately.

### Threading model
`ClaudeService`/`CopilotService` (in `review-engine`) are synchronous services. IntelliJ's
`IntellijClaudeService` adapter owns threading (pooled thread for I/O, EDT for UI callbacks); the
sidecar's `StdioJsonRpcServer` owns threading for VS Code (single-thread `reviewExecutor` per the
[streaming design](sidecar-capabilities.md#sidecar-streaming-async-requests-plus-server-push-notifications),
JSON-RPC notifications instead of a UI-toolkit callback).
