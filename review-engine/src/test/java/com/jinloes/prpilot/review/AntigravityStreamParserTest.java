package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinloes.prpilot.model.ReviewResult;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AntigravityStreamParserTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Nested
    class EncodeUserPromptLine {

        @Test
        void encodesPromptAsSingleLineNdjsonUserEvent() throws IOException {
            String encoded =
                    AntigravityStreamParser.encodeUserPromptLine("Review line 1\n\"quoted\"");

            assertThat(encoded).endsWith("\n");
            assertThat(encoded.substring(0, encoded.length() - 1)).doesNotContain("\n");
            JsonNode parsed = JSON.readTree(encoded);
            assertThat(parsed.path("event").asText()).isEqualTo("user");
            assertThat(parsed.path("message").path("content").asText())
                    .isEqualTo("Review line 1\n\"quoted\"");
        }
    }

    @Nested
    class ParseStdoutFile {

        @TempDir Path tempDir;

        @Test
        void parsesStreamEventsRelativizesToolPathsAndExtractsReviewResult() throws IOException {
            Path isolatedRoot = Files.createDirectories(tempDir.resolve("isolated"));
            Path inspectedPath = isolatedRoot.resolve("src").resolve("Widget.java");
            Path internalAgentPath =
                    isolatedRoot
                            .resolve(".agents")
                            .resolve("agents")
                            .resolve("pr-pilot-reviewer.md");
            String reviewJson = sampleReviewJson("Solid change.", "APPROVE");

            List<String> lines =
                    List.of(
                            initEvent(isolatedRoot.toString()),
                            toolStepEvent("ACTIVE", "read_file", inspectedPath.toString()),
                            toolStepEvent("DONE", "read_file", inspectedPath.toString()),
                            toolStepEvent("ACTIVE", "read_file", internalAgentPath.toString()),
                            agentResponseEvent("ACTIVE", "Checking widget...", "{\"summary\":"),
                            agentResponseEvent("DONE", "", "\"Solid change.\"}"),
                            resultEvent("SUCCESS", reviewJson, null));
            Path stdoutFile =
                    Files.writeString(tempDir.resolve("out.ndjson"), String.join("\n", lines));

            List<String> statuses = new ArrayList<>();
            List<String> chunks = new ArrayList<>();
            ReviewResult result =
                    AntigravityStreamParser.parseStdoutFileToResult(
                            stdoutFile.toFile(),
                            isolatedRoot,
                            "",
                            statuses::add,
                            (kind, text) -> chunks.add(kind + ":" + text));

            assertThat(result.getSummary()).isEqualTo("Solid change.");
            assertThat(result.getVerdict()).isEqualTo("APPROVE");
            assertThat(statuses)
                    .containsExactly("read_file(Path=src/Widget.java)", "Parsing review…");
            assertThat(chunks)
                    .containsExactly(
                            "thinking:Checking widget...",
                            "text:{\"summary\":",
                            "text:\"Solid change.\"}");
        }

        @Test
        void fallsBackToAccumulatedTextDeltasWhenResultResponseIsEmpty() throws IOException {
            String reviewJson = sampleReviewJson("From deltas.", "COMMENT");
            List<String> lines =
                    List.of(
                            agentResponseEvent("ACTIVE", "", reviewJson),
                            resultEvent("SUCCESS", "", null));
            Path stdoutFile =
                    Files.writeString(tempDir.resolve("deltas.ndjson"), String.join("\n", lines));

            ReviewResult result =
                    AntigravityStreamParser.parseStdoutFileToResult(
                            stdoutFile.toFile(), null, "", ignored -> {}, null);

            assertThat(result.getSummary()).isEqualTo("From deltas.");
            assertThat(result.getVerdict()).isEqualTo("COMMENT");
        }

        @Test
        void throwsStructuredFailureWhenResultReportsErrorStatus() throws IOException {
            Path stdoutFile =
                    Files.writeString(
                            tempDir.resolve("err.ndjson"),
                            resultEvent("ERROR", "", "Authentication required"),
                            StandardCharsets.UTF_8);

            assertThatThrownBy(
                            () ->
                                    AntigravityStreamParser.parseStdoutFileToRaw(
                                            stdoutFile.toFile(), null, "", ignored -> {}, null))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("agy failed (ERROR): Authentication required");

            AntigravityStreamParser.ErrorInfo info =
                    AntigravityStreamParser.findErrorInfo(stdoutFile.toFile());
            assertThat(info.status()).isEqualTo("ERROR");
            assertThat(info.message()).isEqualTo("Authentication required");
        }

        @Test
        void throwsWithEventSummaryWhenOutputIsEmpty() throws IOException {
            Path stdoutFile =
                    Files.writeString(
                            tempDir.resolve("empty.ndjson"),
                            initEvent("/tmp/ws"),
                            StandardCharsets.UTF_8);

            assertThatThrownBy(
                            () ->
                                    AntigravityStreamParser.parseStdoutFileToRaw(
                                            stdoutFile.toFile(), null, "", ignored -> {}, null))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("agy produced no output (events: init×1");
        }
    }

    @Nested
    class ParseChatStream {

        @Test
        void forwardsIncrementalTextDeltasAndReturnsFinalResponse() throws IOException {
            String stream =
                    String.join(
                            "\n",
                            agentResponseEvent("ACTIVE", "reasoning", "Hello "),
                            agentResponseEvent("DONE", "", "world!"),
                            resultEvent("SUCCESS", "Hello world!", null));

            List<String> deltas = new ArrayList<>();
            String full =
                    AntigravityStreamParser.parseChatStream(
                            new StringReader(stream), null, deltas::add);

            assertThat(deltas).containsExactly("Hello ", "world!");
            assertThat(full).isEqualTo("Hello world!");
        }
    }

    private static String sampleReviewJson(String summary, String verdict) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("summary", summary);
        root.put("verdict", verdict);
        ArrayNode comments = root.putArray("lineComments");
        assertThat(comments).isEmpty();
        return JSON.writeValueAsString(root);
    }

    private static String initEvent(String cwd) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("event", "init");
        root.put("conversation_id", "conv-1");
        root.putObject("init").put("cwd", cwd);
        return JSON.writeValueAsString(root);
    }

    private static String toolStepEvent(String state, String toolName, String path)
            throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("event", "step_update");
        ObjectNode step = root.putObject("step_update");
        step.put("state", state);
        step.put("step_type", "tool");
        step.put("tool_name", toolName);
        ObjectNode toolInfo = step.putObject("tool_info");
        toolInfo.put("name", toolName);
        toolInfo.putObject("parameters").put("Path", path);
        return JSON.writeValueAsString(root);
    }

    private static String agentResponseEvent(String state, String thinkingDelta, String textDelta)
            throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("event", "step_update");
        ObjectNode step = root.putObject("step_update");
        step.put("state", state);
        step.put("step_type", "agent_response");
        if (!thinkingDelta.isEmpty()) {
            step.put("thinking_delta", thinkingDelta);
        }
        if (!textDelta.isEmpty()) {
            step.put("text_delta", textDelta);
        }
        return JSON.writeValueAsString(root);
    }

    private static String resultEvent(String status, String response, String error)
            throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("event", "result");
        ObjectNode res = root.putObject("result");
        res.put("conversation_id", "conv-1");
        res.put("status", status);
        if (response != null) {
            res.put("response", response);
        }
        if (error != null) {
            res.put("error", error);
        }
        return JSON.writeValueAsString(root);
    }
}
