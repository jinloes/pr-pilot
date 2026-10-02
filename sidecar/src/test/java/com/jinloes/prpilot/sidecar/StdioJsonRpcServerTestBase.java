package com.jinloes.prpilot.sidecar;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinloes.prpilot.engine.GitHubEngine;
import com.jinloes.prpilot.engine.ReviewSessionService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/** Shared server fixture and JSON-RPC helpers for the sidecar server tests. */
abstract class StdioJsonRpcServerTestBase {
    final ObjectMapper objectMapper = new ObjectMapper();
    final StdioFrameCodec frameCodec = new StdioFrameCodec();
    final ExecutorService reviewExecutor = Executors.newSingleThreadExecutor();
    StdioJsonRpcServer server;

    @BeforeEach
    void setUp() {
        server =
                new StdioJsonRpcServer(
                        objectMapper,
                        frameCodec,
                        new SidecarBootstrapService(),
                        new GitHubEngine(),
                        new ReviewSessionService(),
                        reviewExecutor);
    }

    @AfterEach
    void tearDown() {
        reviewExecutor.shutdownNow();
    }

    /** Re-runs the request with a fresh id (43) against a server whose output is captured. */
    JsonNode invokeGenerateDirectly() {
        return server.handle(generateRequest(43, "review-1"));
    }

    JsonNode invokeGenerateNotification() {
        return server.handle(
                ("{\"jsonrpc\":\"2.0\",\"method\":\"reviews/generate\",\"params\":{"
                                + "\"operationId\":\"notification-1\",\"provider\":\"claude\",\"model\":\"\",\"effort\":\"\",\"inheritMcp\":false,"
                                + "\"pr\":{\"title\":\"T\",\"htmlUrl\":\"\",\"owner\":\"o\",\"repo\":\"r\","
                                + "\"number\":1,\"body\":\"\",\"author\":\"a\",\"createdAt\":\"2024-01-01\","
                                + "\"isDraft\":false},\"diff\":\"\",\"ciStatus\":\"\"}}")
                        .getBytes(StandardCharsets.UTF_8));
    }

    byte[] generateRequest(int id, String operationId) {
        return ("{\"jsonrpc\":\"2.0\",\"id\":"
                        + id
                        + ",\"method\":\"reviews/generate\",\"params\":{"
                        + "\"operationId\":\""
                        + operationId
                        + "\",\"provider\":\"claude\",\"model\":\"\",\"effort\":\"\",\"inheritMcp\":false,"
                        + "\"pr\":{\"title\":\"T\",\"htmlUrl\":\"\",\"owner\":\"o\",\"repo\":\"r\","
                        + "\"number\":1,\"body\":\"\",\"author\":\"a\",\"createdAt\":\"2024-01-01\","
                        + "\"isDraft\":false},\"diff\":\"\",\"ciStatus\":\"\"}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    Map<Integer, JsonNode> awaitResponses(
            ByteArrayOutputStream output, Set<Integer> requiredIds, long timeout, TimeUnit unit)
            throws Exception {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        Map<Integer, JsonNode> responses = new HashMap<>();
        while (!responses.keySet().containsAll(requiredIds) && System.nanoTime() < deadline) {
            responses.clear();
            try {
                ByteArrayInputStream frames = new ByteArrayInputStream(output.toByteArray());
                byte[] responseFrame;
                while ((responseFrame = frameCodec.readFrame(frames)) != null) {
                    JsonNode response = objectMapper.readTree(responseFrame);
                    responses.put(response.path("id").asInt(), response);
                }
            } catch (IOException incompleteFrame) {
                responses.clear();
            }
            if (!responses.keySet().containsAll(requiredIds)) Thread.sleep(5);
        }
        assertThat(responses.keySet()).containsAll(requiredIds);
        return responses;
    }

    ObjectNode rpcRequest(int id, String method) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        return request;
    }

    StdioJsonRpcServer serverWithOutput(ByteArrayOutputStream output) {
        return new StdioJsonRpcServer(
                objectMapper,
                frameCodec,
                new SidecarBootstrapService(),
                new GitHubEngine(),
                new ReviewSessionService(),
                reviewExecutor);
    }
}
