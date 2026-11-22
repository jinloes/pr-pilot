package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.model.ReviewResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AntigravityServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Nested
    class PrepareCommand {

        @TempDir Path tempDir;

        @Test
        void includesIsolatedWorkspaceFlagsAndModelWithoutEffort() throws IOException {
            Path isolatedDir = tempDir.resolve("ws");
            Path settingsFile = isolatedDir.resolve(".agents").resolve("pr-pilot-settings.json");

            List<String> cmd =
                    AntigravityService.prepareCommand(
                            "/usr/local/bin/agy",
                            false,
                            "cmd.exe",
                            settingsFile,
                            " gemini-3.8-flash-high ");

            assertThat(cmd)
                    .containsExactly(
                            "/usr/local/bin/agy",
                            "--print=",
                            "--input-format",
                            "stream-json",
                            "--output-format",
                            "stream-json",
                            "--agent",
                            "pr-pilot-reviewer",
                            "--settings",
                            settingsFile.toAbsolutePath().toString(),
                            "--disable-slash-commands",
                            "--model",
                            "gemini-3.8-flash-high")
                    .doesNotContain("--effort", "--max-turns", "--cwd");
        }

        @Test
        void omitsModelFlagWhenBlankAndWrapsWindowsCmdShim() throws IOException {
            Path isolatedDir = tempDir.resolve("ws");
            Path settingsFile = isolatedDir.resolve(".agents").resolve("pr-pilot-settings.json");

            List<String> cmd =
                    AntigravityService.prepareCommand(
                            "C:\\Tools\\agy.cmd",
                            true,
                            "C:\\Windows\\System32\\cmd.exe",
                            settingsFile,
                            "  ");

            assertThat(cmd)
                    .startsWith(
                            "C:\\Windows\\System32\\cmd.exe",
                            "/d",
                            "/c",
                            "call",
                            "C:\\Tools\\agy.cmd",
                            "--print=")
                    .doesNotContain("--model", "--effort", "--max-turns", "--cwd");
        }

        @Test
        void rejectsOptionInjectionOrControlCharactersInModel() {
            assertThatThrownBy(() -> AntigravityService.validateCliOptionValue("--agent", "model"))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Invalid Antigravity CLI model value");
            assertThatThrownBy(
                            () ->
                                    AntigravityService.validateCliOptionValue(
                                            "gemini\nflash", "model"))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Invalid Antigravity CLI model value");
        }
    }

    @Nested
    class FormatExitFailure {

        @Test
        void combinesStreamErrorAndStderrWithoutDuplicatingIdenticalText() {
            assertThat(
                            AntigravityService.formatExitFailure(
                                    1,
                                    "unknown model: foo",
                                    new AntigravityStreamParser.ErrorInfo(
                                            "ERROR", "unknown model: foo", "c1")))
                    .isEqualTo("agy exited 1: unknown model: foo");
            assertThat(
                            AntigravityService.formatExitFailure(
                                    1,
                                    "fatal stderr",
                                    new AntigravityStreamParser.ErrorInfo(
                                            "ERROR", "quota exceeded", "c1")))
                    .isEqualTo("agy exited 1: quota exceeded (fatal stderr)");
            assertThat(AntigravityService.formatExitFailure(2, "", null)).isEqualTo("agy exited 2");
        }
    }

    @Nested
    class Execution {

        @TempDir Path tempDir;

        @Test
        void runsReviewPassAndChatInsideIsolatedWorkspaceAndCleansUp() throws Exception {
            Files.writeString(tempDir.resolve("Widget.java"), "class Widget {}\n");
            List<Path> seenIsolatedDirs = new ArrayList<>();
            ByteArrayOutputStream capturedStdin = new ByteArrayOutputStream();
            String reviewJson = reviewJson("Looks clean.", "APPROVE");
            String ndjsonLine = resultFrame("SUCCESS", reviewJson);

            AntigravityService service =
                    new AntigravityService(tempDir.toString()) {
                        @Override
                        Process buildProcess(
                                AntigravityIsolatedWorkspace workspace,
                                File stdoutFile,
                                String model)
                                throws IOException {
                            seenIsolatedDirs.add(workspace.isolatedDir());
                            assertThat(workspace.isolatedDir().resolve("Widget.java")).exists();
                            if (stdoutFile != null) {
                                Files.writeString(
                                        stdoutFile.toPath(), ndjsonLine, StandardCharsets.UTF_8);
                            }
                            return new StubProcess(0, ndjsonLine, "", capturedStdin);
                        }
                    };

            PullRequest pr =
                    new PullRequest(
                            "Add Widget",
                            "https://github.com/acme/widgets/pull/1",
                            "acme",
                            "widgets",
                            1,
                            "",
                            "alice",
                            "2026-01-01T00:00:00Z",
                            false);
            PRReviewRequest req =
                    PRReviewRequest.builder(
                                    pr,
                                    "diff --git a/Widget.java b/Widget.java\n"
                                            + "--- a/Widget.java\n"
                                            + "+++ b/Widget.java\n"
                                            + "@@ -1 +1 @@\n"
                                            + "+class Widget {}\n")
                            .build();

            ReviewResult result = service.reviewPR(req, "gemini-3.8-flash-high", ignored -> {});
            assertThat(result.getSummary()).isEqualTo("Looks clean.");
            assertThat(seenIsolatedDirs).hasSize(1);
            assertThat(seenIsolatedDirs.get(0)).doesNotExist();
            assertThat(tempDir.resolve("Widget.java")).exists();
            assertThat(capturedStdin.toString(StandardCharsets.UTF_8))
                    .startsWith("{\"event\":\"user\",\"message\":{\"content\":");

            List<String> chatChunks = new ArrayList<>();
            String chatStream =
                    agentDeltaFrame("Answer ") + "\n" + resultFrame("SUCCESS", "Answer text");
            AntigravityService chatService =
                    new AntigravityService(tempDir.toString()) {
                        @Override
                        Process buildProcess(
                                AntigravityIsolatedWorkspace workspace,
                                File stdoutFile,
                                String model) {
                            assertThat(model).isEqualTo("claude-sonnet-4-6");
                            return new StubProcess(0, chatStream, "", new ByteArrayOutputStream());
                        }
                    };
            String reply =
                    chatService.chatWithPrompt(
                            "Explain Widget", "claude-sonnet-4-6", chatChunks::add);
            assertThat(reply).isEqualTo("Answer text");
            assertThat(chatChunks).containsExactly("Answer ");
        }

        @Test
        void surfacesProcessExitFailureInsteadOfBrokenPipeWhenProcessExitsBeforeReadingStdin() {
            OutputStream closedPipe =
                    new OutputStream() {
                        @Override
                        public void write(int b) throws IOException {
                            throw new IOException("The pipe has been ended");
                        }
                    };
            AntigravityService failingService =
                    new AntigravityService(tempDir.toString()) {
                        @Override
                        Process buildProcess(
                                AntigravityIsolatedWorkspace workspace,
                                File stdoutFile,
                                String model) {
                            return new StubProcess(
                                    2, "", "flag provided but not defined: -bogus", closedPipe);
                        }
                    };

            assertThatThrownBy(
                            () ->
                                    failingService.completeReviewPrompt(
                                            "Review this", null, false, 5_000, ignored -> {}))
                    .isInstanceOf(IOException.class)
                    .hasMessage("agy exited 2: flag provided but not defined: -bogus");
        }
    }

    private static String reviewJson(String summary, String verdict) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("summary", summary);
        root.put("verdict", verdict);
        root.putArray("lineComments");
        return JSON.writeValueAsString(root);
    }

    private static String agentDeltaFrame(String textDelta) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("event", "step_update");
        ObjectNode step = root.putObject("step_update");
        step.put("state", "ACTIVE");
        step.put("step_type", "agent_response");
        step.put("text_delta", textDelta);
        return JSON.writeValueAsString(root);
    }

    private static String resultFrame(String status, String response) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("event", "result");
        ObjectNode res = root.putObject("result");
        res.put("status", status);
        res.put("response", response);
        return JSON.writeValueAsString(root);
    }

    private static final class StubProcess extends Process {
        private final int exitCode;
        private final ByteArrayInputStream stdout;
        private final ByteArrayInputStream stderr;
        private final OutputStream stdin;

        StubProcess(int exitCode, String stdoutText, String stderrText, OutputStream stdin) {
            this.exitCode = exitCode;
            this.stdout = new ByteArrayInputStream(stdoutText.getBytes(StandardCharsets.UTF_8));
            this.stderr = new ByteArrayInputStream(stderrText.getBytes(StandardCharsets.UTF_8));
            this.stdin = stdin;
        }

        @Override
        public OutputStream getOutputStream() {
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return stderr;
        }

        @Override
        public int waitFor() {
            return exitCode;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public int exitValue() {
            return exitCode;
        }

        @Override
        public void destroy() {}

        @Override
        public Process destroyForcibly() {
            return this;
        }

        @Override
        public boolean isAlive() {
            return false;
        }
    }
}
