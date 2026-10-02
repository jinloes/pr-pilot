package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.model.ReviewResult;
import com.jinloes.prpilot.review.stream.ContentBlock;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Java port of the former core/jvmTest Kotest suite for ClaudeService; behavior unchanged. */
class ClaudeServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static PullRequest fakePr() {
        return new PullRequest(
                "T", "https://github.com/o/r/pull/1", "o", "r", 1, "", "a", "2024-01-01");
    }

    private static PRReviewRequest fakeRequest() {
        return new PRReviewRequest(fakePr(), "");
    }

    /** ClaudeService subclass that returns pre-canned processes instead of spawning real ones. */
    private static final class FakeClaudeService extends ClaudeService {
        private final List<ProcessStep> processSteps;
        private int callIndex = 0;
        final List<File> outputFiles = new ArrayList<>();
        final List<Integer> turnBudgets = new ArrayList<>();

        record ProcessStep(String ndjson, int exitCode) {}

        FakeClaudeService(List<ProcessStep> processSteps) {
            this.processSteps = processSteps;
        }

        @Override
        File createOutputFile(String prefix) throws IOException {
            File file = super.createOutputFile(prefix);
            outputFiles.add(file);
            return file;
        }

        @Override
        Process buildProcess(File stdoutFile, int maxTurns, String... extraArgs)
                throws IOException {
            turnBudgets.add(maxTurns);
            ProcessStep step = processSteps.get(callIndex++);
            if (stdoutFile != null) {
                Files.writeString(stdoutFile.toPath(), step.ndjson());
            }
            return new ProcessBuilder("sh", "-c", "cat > /dev/null; exit " + step.exitCode())
                    .start();
        }
    }

    private static final class TimeoutClaudeService extends ClaudeService {
        private Process spawnedProcess;

        @Override
        Process buildProcess(File stdoutFile, int maxTurns, String... extraArgs)
                throws IOException {
            return startHangingProcess();
        }

        @Override
        Process buildProcess(String... extraArgs) throws IOException {
            return startHangingProcess();
        }

        @Override
        long reviewTimeoutMillis() {
            return 25;
        }

        @Override
        long chatTimeoutMillis() {
            return 25;
        }

        private Process startHangingProcess() throws IOException {
            spawnedProcess = new ProcessBuilder("sh", "-c", "sleep 30").start();
            return spawnedProcess;
        }
    }

    private static final class ConcurrentChatClaudeService extends ClaudeService {
        ConcurrentChatClaudeService(Executor executor) {
            super(null, new CancellationToken(), executor);
        }

        @Override
        Process buildProcess(String... extraArgs) throws IOException {
            return new ProcessBuilder("sh", "-c", "cat").start();
        }
    }

    @Nested
    class Cancellation {

        @Test
        void cancellationBeforeProcessPublicationPreventsCliStartup() {
            CancellationToken token = new CancellationToken();
            token.cancel();
            ClaudeService service = new ClaudeService(null, token);

            assertThatThrownBy(() -> service.chatWithPrompt("question", ignored -> {}))
                    .isInstanceOf(InterruptedException.class);
        }
    }

    @Nested
    class ProcessTimeouts {

        @Test
        void reviewTimeoutTerminatesTheProcessBeforeAwaitingIo() {
            TimeoutClaudeService service = new TimeoutClaudeService();

            assertThatThrownBy(() -> service.reviewPR(fakeRequest(), "", false, status -> {}, null))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Review timed out");
            assertThat(service.spawnedProcess.isAlive()).isFalse();
        }

        @Test
        void chatTimeoutTerminatesAProcessThatKeepsStdoutOpen() {
            TimeoutClaudeService service = new TimeoutClaudeService();

            assertThatThrownBy(() -> service.chatWithPrompt("question", chunk -> {}))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Chat timed out");
            assertThat(service.spawnedProcess.isAlive()).isFalse();
        }
    }

    @Nested
    class BlockingIoExecutor {

        @Test
        void twoConcurrentChatsCanStartAllStreamTasksWithoutStarvation() throws Exception {
            ExecutorService ioThreads = Executors.newFixedThreadPool(6);
            ExecutorService callers = Executors.newFixedThreadPool(2);
            CountDownLatch allStreamTasksStarted = new CountDownLatch(6);
            Executor barrierExecutor =
                    task ->
                            ioThreads.execute(
                                    () -> {
                                        allStreamTasksStarted.countDown();
                                        try {
                                            if (!allStreamTasksStarted.await(2, TimeUnit.SECONDS)) {
                                                throw new AssertionError(
                                                        "stream tasks did not start concurrently");
                                            }
                                        } catch (InterruptedException exception) {
                                            Thread.currentThread().interrupt();
                                            return;
                                        }
                                        task.run();
                                    });
            try {
                ConcurrentChatClaudeService first =
                        new ConcurrentChatClaudeService(barrierExecutor);
                ConcurrentChatClaudeService second =
                        new ConcurrentChatClaudeService(barrierExecutor);

                Future<String> firstResult =
                        callers.submit(() -> first.chatWithPrompt("first", ignored -> {}));
                Future<String> secondResult =
                        callers.submit(() -> second.chatWithPrompt("second", ignored -> {}));

                assertThat(allStreamTasksStarted.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(firstResult.get(2, TimeUnit.SECONDS)).isEqualTo("first");
                assertThat(secondResult.get(2, TimeUnit.SECONDS)).isEqualTo("second");
            } finally {
                callers.shutdownNow();
                ioThreads.shutdownNow();
            }
        }
    }

    @Nested
    class RecallAndHistory {
        private String reviewJsonWith(int comments) throws Exception {
            ObjectNode root = JSON.createObjectNode();
            root.put("summary", "s");
            root.put("verdict", "COMMENT");
            var array = root.putArray("lineComments");
            for (int i = 1; i <= comments; i++) {
                ObjectNode comment = array.addObject();
                comment.put("file", "A.java");
                comment.put("line", i);
                comment.put("type", "suggestion");
                comment.put("severity", "minor");
                comment.put("category", "maintainability");
                comment.put("confidence", "medium");
                comment.put("body", "Fix " + i + ".");
                comment.put("rationale", "Line " + i + ".");
            }
            return JSON.writeValueAsString(root);
        }

        @Test
        void recallReviewPassKeepsThirtyComments() throws Exception {
            String escaped = reviewJsonWith(35).replace("\"", "\\\"");
            String ndjson =
                    "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,"
                            + "\"result\":\""
                            + escaped
                            + "\"}\n";
            FakeClaudeService svc =
                    new FakeClaudeService(List.of(new FakeClaudeService.ProcessStep(ndjson, 0)));
            PRReviewRequest request =
                    PRReviewRequest.builder(fakePr(), "").candidateRecall(true).build();

            ReviewPassResult pass = svc.reviewPass(request, "", ignored -> {}, null);

            assertThat(pass.review().getLineComments()).hasSize(30);
            assertThat(svc.turnBudgets).containsExactly(ClaudeService.REVIEW_MAX_TURNS);
            assertThat(ClaudeService.REVIEW_MAX_TURNS).isEqualTo(40);
        }

        @Test
        void chatKeepsTheDefaultTurnBudget() {
            FakeClaudeService svc =
                    new FakeClaudeService(List.of(new FakeClaudeService.ProcessStep("", 0)));

            try {
                svc.chatWithPrompt("question", ignored -> {});
            } catch (Exception ignored) {
                // Only the turn budget passed to the process builder matters here.
            }

            assertThat(svc.turnBudgets).containsExactly(ClaudeService.DEFAULT_MAX_TURNS);
            assertThat(ClaudeService.DEFAULT_MAX_TURNS).isEqualTo(15);
        }

        @Test
        void projectDirIsNullWhenBuiltWithoutOne() {
            assertThat(new ClaudeService().projectDir()).isNull();
            assertThat(new ClaudeService(" ").projectDir()).isNull();
            assertThat(new ClaudeService("/tmp/x").projectDir()).isEqualTo(new File("/tmp/x"));
            assertThat(new CopilotService().projectDir()).isNull();
            assertThat(new CopilotService("/tmp/x").projectDir()).isEqualTo(new File("/tmp/x"));
        }
    }

    @Nested
    class FindErrorInfo {

        @Test
        void fileDoesNotExistReturnsNulls() {
            ClaudeService svc = new ClaudeService();
            ClaudeService.ErrorInfo info =
                    svc.findErrorInfo(new File("/nonexistent/path/file.ndjson"));
            assertThat(info.subtype()).isNull();
            assertThat(info.sessionId()).isNull();
        }

        @Test
        void emptyFileReturnsNulls() throws Exception {
            File file = Files.createTempFile("test", ".ndjson").toFile();
            try {
                ClaudeService svc = new ClaudeService();
                ClaudeService.ErrorInfo info = svc.findErrorInfo(file);
                assertThat(info.subtype()).isNull();
                assertThat(info.sessionId()).isNull();
            } finally {
                file.delete();
            }
        }

        @Test
        void fileWithErrorMaxTurnsEventAndSessionIdReturnsBoth() throws Exception {
            File file = Files.createTempFile("test", ".ndjson").toFile();
            try {
                Files.writeString(
                        file.toPath(),
                        "{\"type\":\"result\",\"subtype\":\"error_max_turns\",\"is_error\":true,\"session_id\":\"sess-abc\"}\n");
                ClaudeService svc = new ClaudeService();
                ClaudeService.ErrorInfo info = svc.findErrorInfo(file);
                assertThat(info.subtype()).isEqualTo("error_max_turns");
                assertThat(info.sessionId()).isEqualTo("sess-abc");
            } finally {
                file.delete();
            }
        }

        @Test
        void fileWithNonErrorResultReturnsNulls() throws Exception {
            File file = Files.createTempFile("test", ".ndjson").toFile();
            try {
                Files.writeString(
                        file.toPath(),
                        "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false}\n");
                ClaudeService svc = new ClaudeService();
                ClaudeService.ErrorInfo info = svc.findErrorInfo(file);
                assertThat(info.subtype()).isNull();
                assertThat(info.sessionId()).isNull();
            } finally {
                file.delete();
            }
        }

        @Test
        void fileWithCorruptLineFollowedByValidErrorEventSkipsCorruptFindsError() throws Exception {
            File file = Files.createTempFile("test", ".ndjson").toFile();
            try {
                Files.writeString(
                        file.toPath(),
                        "NOT_JSON\n{\"type\":\"result\",\"subtype\":\"error_max_turns\",\"is_error\":true,\"session_id\":\"s1\"}\n");
                ClaudeService svc = new ClaudeService();
                ClaudeService.ErrorInfo info = svc.findErrorInfo(file);
                assertThat(info.subtype()).isEqualTo("error_max_turns");
            } finally {
                file.delete();
            }
        }
    }

    @Nested
    class ParseStdoutFileToResult {

        @Test
        void resultEventWithJsonParsedIntoReviewResult() throws Exception {
            File file = Files.createTempFile("test", ".ndjson").toFile();
            try {
                String reviewJson =
                        "{\"summary\":\"overview\",\"verdict\":\"APPROVE\",\"lineComments\":[]}";
                String escaped = reviewJson.replace("\"", "\\\"");
                Files.writeString(
                        file.toPath(),
                        "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\""
                                + escaped
                                + "\"}\n");
                ClaudeService svc = new ClaudeService();
                List<String> statuses = new ArrayList<>();
                ReviewResult result = svc.parseStdoutFileToResult(file, "", statuses::add, null);
                assertThat(result.getVerdict()).isEqualTo("APPROVE");
            } finally {
                file.delete();
            }
        }

        @Test
        void noResultEventThrowsIOExceptionWithDiagnosticDetail() throws Exception {
            File file = Files.createTempFile("test", ".ndjson").toFile();
            try {
                Files.writeString(file.toPath(), "{\"type\":\"assistant\",\"message\":null}\n");
                ClaudeService svc = new ClaudeService();
                assertThatThrownBy(() -> svc.parseStdoutFileToResult(file, "", ignored -> {}, null))
                        .isInstanceOf(IOException.class);
            } finally {
                file.delete();
            }
        }

        @Test
        void textBlockFallbackTextAccumulatedInTextBufferUsedWhenResultBufferEmpty()
                throws Exception {
            File file = Files.createTempFile("test", ".ndjson").toFile();
            try {
                String reviewJson =
                        "{\"summary\":\"fallback\",\"verdict\":\"COMMENT\",\"lineComments\":[]}";
                String escaped = reviewJson.replace("\"", "\\\"");
                Files.writeString(
                        file.toPath(),
                        "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\""
                                + escaped
                                + "\"}]}}\n");
                ClaudeService svc = new ClaudeService();
                ReviewResult result = svc.parseStdoutFileToResult(file, "", ignored -> {}, null);
                assertThat(result.getVerdict()).isEqualTo("COMMENT");
            } finally {
                file.delete();
            }
        }
    }

    @Nested
    class ReviewPrResumeOnErrorMaxTurns {

        private String successNdjson() {
            String json = "{\"summary\":\"s\",\"verdict\":\"APPROVE\",\"lineComments\":[]}";
            String escaped = json.replace("\"", "\\\"");
            return "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\""
                    + escaped
                    + "\"}\n";
        }

        @Test
        void exits1WithErrorMaxTurnsAndSessionIdResumesAndReturnsResult() throws Exception {
            String errorNdjson =
                    "{\"type\":\"result\",\"subtype\":\"error_max_turns\",\"is_error\":true,\"session_id\":\"sess-abc\"}\n";
            FakeClaudeService svc =
                    new FakeClaudeService(
                            List.of(
                                    new FakeClaudeService.ProcessStep(errorNdjson, 1),
                                    new FakeClaudeService.ProcessStep(successNdjson(), 0)));
            List<String> statuses = new ArrayList<>();
            ReviewResult result = svc.reviewPR(fakeRequest(), "", statuses::add);
            assertThat(result.getVerdict()).isEqualTo("APPROVE");
            assertThat(statuses).contains("Resuming review session…");
            assertThat(svc.outputFiles).hasSize(2);
            assertThat(svc.outputFiles).allMatch(f -> !f.exists());
        }

        @Test
        void exits1WithErrorMaxTurnsButNoSessionIdThrowsTurnLimitMessage() {
            String errorNdjson =
                    "{\"type\":\"result\",\"subtype\":\"error_max_turns\",\"is_error\":true}\n";
            FakeClaudeService svc =
                    new FakeClaudeService(
                            List.of(new FakeClaudeService.ProcessStep(errorNdjson, 1)));
            assertThatThrownBy(() -> svc.reviewPR(fakeRequest(), "", ignored -> {}))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("turn limit");
            assertThat(svc.outputFiles).allMatch(f -> !f.exists());
        }

        @Test
        void exits1WithNoErrorEventInStdoutThrowsGenericClaudeExitedMessage() {
            FakeClaudeService svc =
                    new FakeClaudeService(List.of(new FakeClaudeService.ProcessStep("\n", 1)));
            assertThatThrownBy(() -> svc.reviewPR(fakeRequest(), "", ignored -> {}))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("claude exited 1");
        }

        @Test
        void resumeFailsWithErrorMaxTurnsThrowsResumeTurnLimitMessage() {
            String errorNdjson =
                    "{\"type\":\"result\",\"subtype\":\"error_max_turns\",\"is_error\":true,\"session_id\":\"s1\"}\n";
            String resumeErrorNdjson =
                    "{\"type\":\"result\",\"subtype\":\"error_max_turns\",\"is_error\":true}\n";
            FakeClaudeService svc =
                    new FakeClaudeService(
                            List.of(
                                    new FakeClaudeService.ProcessStep(errorNdjson, 1),
                                    new FakeClaudeService.ProcessStep(resumeErrorNdjson, 1)));
            assertThatThrownBy(() -> svc.reviewPR(fakeRequest(), "", ignored -> {}))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("even after resume");
        }
    }

    @Nested
    class HandleContentBlock {

        private static ContentBlock textBlock(String text) {
            ContentBlock b = new ContentBlock();
            b.setType("text");
            b.setText(text);
            return b;
        }

        private static ContentBlock thinkingBlock(String thinking) {
            ContentBlock b = new ContentBlock();
            b.setType("thinking");
            b.setThinking(thinking);
            return b;
        }

        private static ContentBlock toolUseBlock(String name) {
            ContentBlock b = new ContentBlock();
            b.setType("tool_use");
            b.setName(name);
            b.setInput(Map.of());
            return b;
        }

        @Test
        void textBlockWithOnChunkCallsOnChunkNotOnStatus() {
            ClaudeService service = new ClaudeService();
            List<String> statuses = new ArrayList<>();
            List<String[]> chunks = new ArrayList<>();
            service.handleContentBlock(
                    textBlock("hello world"),
                    statuses::add,
                    (k, v) -> chunks.add(new String[] {k, v}));
            assertThat(statuses).isEmpty();
            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0)).containsExactly("text", "hello world");
        }

        @Test
        void textBlockWithoutOnChunkCallsOnStatusWithGenerating() {
            ClaudeService service = new ClaudeService();
            List<String> statuses = new ArrayList<>();
            service.handleContentBlock(textBlock("hello"), statuses::add, null);
            assertThat(statuses).containsExactly("Generating review…");
        }

        @Test
        void thinkingBlockWithOnChunkCallsOnChunkWithThinking() {
            ClaudeService service = new ClaudeService();
            List<String[]> chunks = new ArrayList<>();
            service.handleContentBlock(
                    thinkingBlock("deep thought"),
                    ignored -> {},
                    (k, v) -> chunks.add(new String[] {k, v}));
            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0)).containsExactly("thinking", "deep thought");
        }

        @Test
        void toolUseBlockAlwaysCallsOnStatus() {
            ClaudeService service = new ClaudeService();
            List<String> statuses = new ArrayList<>();
            service.handleContentBlock(toolUseBlock("my_tool"), statuses::add, (k, v) -> {});
            assertThat(statuses).containsExactly("my_tool()");
        }

        @Test
        void unknownBlockTypeNoExceptionAndNoCallback() {
            ClaudeService service = new ClaudeService();
            List<String> statuses = new ArrayList<>();
            ContentBlock block = new ContentBlock();
            block.setType("unknown_future_type");
            service.handleContentBlock(block, statuses::add, null);
            assertThat(statuses).isEmpty();
        }
    }

    @Nested
    class ToolUseStatus {

        @Test
        void simpleToolNameFormatsWithEmptyArgs() {
            assertThat(ClaudeService.toolUseStatus("my_tool", Map.of())).isEqualTo("my_tool()");
        }

        @Test
        void mcpPrefixStrippedAndDoubleUnderscoreReplacedWithSlash() {
            assertThat(ClaudeService.toolUseStatus("mcp__github__get_file", Map.of()))
                    .isEqualTo("github/get_file()");
        }

        @Test
        void primitiveStringArgsIncludedInOutput() {
            String result =
                    ClaudeService.toolUseStatus(
                            "mcp__github__search", Map.of("owner", "alice", "repo", "myrepo"));
            assertThat(result).contains("owner=alice").contains("repo=myrepo");
        }

        @Test
        void nonPrimitiveArgsExcluded() {
            Map<String, Object> input = new java.util.LinkedHashMap<>();
            input.put("nested", Map.of());
            input.put("list", List.of());
            input.put("scalar", "val");
            assertThat(ClaudeService.toolUseStatus("tool", input)).isEqualTo("tool(scalar=val)");
        }

        @Test
        void pathContainingClaudeDirReturnsNull() {
            assertThat(
                            ClaudeService.toolUseStatus(
                                    "tool", Map.of("path", "/home/user/.claude/tmp/abc")))
                    .isNull();
        }

        @Test
        void filePathContainingClaudeDirReturnsNull() {
            assertThat(
                            ClaudeService.toolUseStatus(
                                    "tool", Map.of("file_path", "/home/user/.claude/settings")))
                    .isNull();
        }

        @Test
        void pathOutsideClaudeDirNotSuppressed() {
            assertThat(
                            ClaudeService.toolUseStatus(
                                    "tool", Map.of("path", "/home/user/projects/src/Foo.java")))
                    .isNotNull();
        }

        @Test
        void numberArgIncludedAsScalar() {
            assertThat(ClaudeService.toolUseStatus("tool", Map.of("count", 42)))
                    .isEqualTo("tool(count=42)");
        }

        @Test
        void booleanArgIncludedAsScalar() {
            assertThat(ClaudeService.toolUseStatus("tool", Map.of("flag", true)))
                    .isEqualTo("tool(flag=true)");
        }
    }

    @Nested
    class SafeCliArgs {

        @Test
        void allowsOnlyReadOnlyToolsAndNoExternalMcpConfiguration() {
            assertThat(ClaudeService.SAFE_CLI_ARGS)
                    .containsExactly(
                            "--tools",
                            "Read Grep Glob",
                            "--permission-mode",
                            "dontAsk",
                            "--strict-mcp-config",
                            "--mcp-config",
                            "{\"mcpServers\":{}}",
                            "--setting-sources",
                            "user");
        }

        @Test
        void supervisorArgsDisableAllToolsAndExternalMcpConfiguration() {
            assertThat(ClaudeService.NO_TOOL_CLI_ARGS)
                    .containsExactly(
                            "--tools",
                            "",
                            "--permission-mode",
                            "dontAsk",
                            "--strict-mcp-config",
                            "--mcp-config",
                            "{\"mcpServers\":{}}",
                            "--setting-sources",
                            "user");
        }

        @Test
        void neverSkipsPermissionPrompts() {
            // --dangerously-skip-permissions would hand an untrusted PR the full tool surface.
            assertThat(ClaudeService.SAFE_CLI_ARGS)
                    .doesNotContain("--dangerously-skip-permissions");
        }

        @Test
        void readOnlyToolsExcludeMutatingTools() {
            assertThat(ClaudeService.READ_ONLY_TOOLS)
                    .doesNotContain("Bash")
                    .doesNotContain("Write")
                    .doesNotContain("Edit")
                    .doesNotContain("WebFetch");
        }
    }
}
