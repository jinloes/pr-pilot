# Code map: `sidecar/`

Part of the [code map](../../CODEMAP.md), which holds the task-to-code index and cross-module paths.
Paths omit `src/main/java/com/jinloes/prpilot/` and equivalent test roots where the module context
makes them unambiguous; other paths are relative to the module directory.

Thin Java 17, non-web Spring Boot stdio JSON-RPC adapter used only by VS Code.

- `sidecar/PrPilotSidecarApplication.java` - Process entry point and stdio lifecycle.
- `sidecar/SidecarConfiguration.java` - Engine composition.
- `sidecar/StdioFrameCodec.java` - Bounded Content-Length UTF-8 framing.
- `sidecar/StdioJsonRpcServer.java` - Read loop, handler registry, dispatch, errors, the review
  executor, and the write lock that serializes frames from background threads.
- `sidecar/StdioJsonRpcServerGitHubHandlers.java` and `StdioJsonRpcServerReviewHandlers.java` -
  Per-engine handler registration and parameter mapping.
- `sidecar/StdioJsonRpcServerAsyncReviewHandlers.java` - Backgrounded review methods: generate,
  chat, cancel, and deep-review prepare/list/cleanup. Builds the
  `reviews/status`/`reviews/chunk`/`reviews/chatChunk` notification params mirrored in
  `vscode-extension/src/sidecarTransport.ts`.
- `sidecar/StdioJsonRpcServerOperationScheduler.java` - Operation slots keyed by operation ID on the
  single-thread review executor: duplicate-ID rejection, cancellation of queued or running work,
  exactly-once terminal responses, and no progress notifications after cancellation.
- `sidecar/StdioJsonRpcServerSupport.java` - Shared JSON-RPC response, notification, and
  parameter-shape helpers.
  Tests: `StdioJsonRpcServerTest` (protocol), `StdioJsonRpcServerGitHubParamsTest`,
  `StdioJsonRpcServerReviewsTest` (including streamed notifications), sharing
  `StdioJsonRpcServerTestBase`; `StdioJsonRpcServerOperationSchedulerTest` drives the scheduler
  directly.
- `sidecar/SidecarBootstrapService.java` - Initialize response and advertised capability groups.
- `src/main/resources/logback-spring.xml` - Stderr logging; stdout remains protocol-only.
- `EngineCapabilityCoverageTest.java` - Enforces declaration, registration, and advertisement
  parity.
- Other tests mirror the frame codec and RPC server.
