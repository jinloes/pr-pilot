package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.model.ReviewResult;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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

    /** Hangs on the first chat (or every chat when {@code allHang}); later chats echo stdin. */
    private static final class TrackedChatClaudeService extends ClaudeService {
        final List<Process> processes = new CopyOnWriteArrayList<>();
        final CountDownLatch hanging;
        private final boolean allHang;

        TrackedChatClaudeService(int hangingChats, boolean allHang) {
            this.hanging = new CountDownLatch(hangingChats);
            this.allHang = allHang;
        }

        @Override
        Process buildProcess(String... extraArgs) throws IOException {
            boolean hang = allHang || processes.isEmpty();
            Process process = new ProcessBuilder("sh", "-c", hang ? "sleep 30" : "cat").start();
            processes.add(process);
            if (hang) hanging.countDown();
            return process;
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

        @Test
        void oneCancelStopsEveryConcurrentRun() throws Exception {
            TrackedChatClaudeService service = new TrackedChatClaudeService(2, true);
            ExecutorService callers = Executors.newFixedThreadPool(2);
            try {
                Future<String> first =
                        callers.submit(() -> service.chatWithPrompt("first", ignored -> {}));
                Future<String> second =
                        callers.submit(() -> service.chatWithPrompt("second", ignored -> {}));
                assertThat(service.hanging.await(5, TimeUnit.SECONDS)).isTrue();

                service.cancelCurrentRequest();

                assertThatThrownBy(() -> first.get(5, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(InterruptedException.class);
                assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(InterruptedException.class);
                assertThat(service.processes).hasSize(2);
                for (Process process : service.processes) {
                    assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
                }
            } finally {
                callers.shutdownNow();
            }
        }

        @Test
        void aFinishedRunDoesNotUntrackAnotherRun() throws Exception {
            TrackedChatClaudeService service = new TrackedChatClaudeService(1, false);
            ExecutorService callers = Executors.newSingleThreadExecutor();
            try {
                Future<String> slow =
                        callers.submit(() -> service.chatWithPrompt("slow", ignored -> {}));
                assertThat(service.hanging.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(service.chatWithPrompt("fast", ignored -> {})).isEqualTo("fast");

                service.cancelCurrentRequest();

                assertThatThrownBy(() -> slow.get(5, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(InterruptedException.class);
                assertThat(service.processes.get(0).waitFor(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                callers.shutdownNow();
            }
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
