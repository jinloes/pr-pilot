# Module and runtime boundaries

Key design decisions moved from [ARCHITECTURE.md](../../ARCHITECTURE.md). Each section encodes an
active constraint future code must respect.

### Module boundaries
`core` is a plain Java 21 JVM module (Kotlin Multiplatform was removed once the `js` target and
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
keeping them in `core`. `github-engine` is a plain Java 21 library with no Spring or IDE APIs; both
`intellij-plugin` and `sidecar` depend on it. `review-engine` is a plain Java 21 library owning
Claude/Copilot CLI invocation, prompt building, and review-JSON parsing (`ClaudeService`,
`CopilotService`, `CopilotModelDiscovery`, `GitWorktreeService`); both `intellij-plugin` and
`sidecar` depend on it, so AI review generation has exactly one JVM implementation rather than being
duplicated per host. `github-engine` and `review-engine` depend on `core` for shared models.
`intellij-plugin` also depends on `core` and no longer needs `jackson-module-kotlin`/`KotlinModule`
now that `core`'s models are plain Java.

`sidecar` is a Java 21 Spring Boot application configured with `WebApplicationType.NONE`; it is not
an HTTP server. Its protocol uses bounded `Content-Length`-framed UTF-8 JSON-RPC over standard
input/output. Standard output must contain protocol frames only—diagnostics belong on standard
error. GitHub, PR, repository, and review behavior belongs in `github-engine`/`review-engine`;
Spring remains only the sidecar composition and lifecycle layer.

### Sidecar owns its Jackson 2 mapper
Spring Boot 4 auto-configures only Jackson 3 (`tools.jackson`), while `core`, both engines, and the
IntelliJ plugin speak Jackson 2 (`com.fasterxml.jackson`). `sidecar` therefore depends on
`spring-boot-starter` without `spring-boot-starter-json`, and `SidecarConfiguration` declares the
`ObjectMapper` that `StdioJsonRpcServer` uses. It keeps the Jackson 2 defaults Boot 3 applied, chiefly
ignoring unknown properties, so a host sending a field an older sidecar does not know still
succeeds. Moving to Jackson 3 is a repository-wide migration, not a sidecar change.

### core: plain Java module
`core`'s shared models (`model/*.java`) are plain Java classes with JavaBean getters/setters (e.g.
`getOwner()`, `isDraft()`) — no Kotlin-Java interop shims (`@JvmOverloads`, `@JvmStatic`,
`KotlinModule`) are needed anywhere downstream, and the module has no Kotlin plugin/dependency at
all.

### Modules compile with --release
The sidecar runs on the user's Java 21 or newer, and it bundles `core`, `github-engine` and
`review-engine`, so those modules may only call Java 21 APIs. `targetCompatibility` alone sets only
the bytecode version: on a newer JDK building the project, a call to an API added after Java 21
still compiles and then throws `NoSuchMethodError` on Java 21. The root `build.gradle` sets every compile
task's `options.release` from the module's `targetCompatibility`, so javac rejects JDK APIs
newer than each module's target on every build, including the required `Gradle check`.

Each module declares a Java toolchain (`java.toolchain.languageVersion`) rather than
`sourceCompatibility`/`targetCompatibility`; Gradle derives the target from the toolchain, and
compiles and tests on a detected JDK of that version whatever JDK runs the Gradle daemon (for
example, a user-wide `org.gradle.java.home`). Raise a module's floor by changing its toolchain
`languageVersion`; do not set `options.release` or the compatibility properties separately.

### Java sources compile as UTF-8
The root `build.gradle` sets `options.encoding = 'UTF-8'` on every `JavaCompile` task, test sources
included. Without it javac reads sources in the JDK's default charset, which JDKs before 18 on
Windows take from the code page: a test literal such as `…` then decodes differently from the main code it
checks, and the test fails only there. Do not set the encoding per module or only on `compileJava`.

### Threading model
`ClaudeService`/`CopilotService` (in `review-engine`) are synchronous services. IntelliJ's
`IntellijClaudeService` adapter owns threading (pooled thread for I/O, EDT for UI callbacks); the
sidecar's `StdioJsonRpcServer` owns threading for VS Code (single-thread `reviewExecutor` per the
[streaming design](sidecar-capabilities.md#sidecar-streaming-async-requests-plus-server-push-notifications),
JSON-RPC notifications instead of a UI-toolkit callback).
