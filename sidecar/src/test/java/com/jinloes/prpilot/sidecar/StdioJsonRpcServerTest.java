package com.jinloes.prpilot.sidecar;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jinloes.prpilot.engine.GitHubEngine;
import com.jinloes.prpilot.engine.ReviewEngineApi;
import com.jinloes.prpilot.engine.ReviewSessionService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class StdioJsonRpcServerTest extends StdioJsonRpcServerTestBase {
    @Test
    void handlesInitializeAndWritesOnlyAFramedResponse() throws IOException {
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        frameCodec.writeFrame(
                input,
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"initialize\"}"
                        .getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        server.run(new ByteArrayInputStream(input.toByteArray()), output);

        JsonNode response =
                objectMapper.readTree(
                        frameCodec.readFrame(new ByteArrayInputStream(output.toByteArray())));
        assertThat(response.path("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(response.path("id").asInt()).isEqualTo(7);
        assertThat(response.path("result").path("serviceName").asText())
                .isEqualTo("pr-pilot-sidecar");
        assertThat(response.path("result").path("protocolVersion").asInt()).isEqualTo(1);
        assertThat(response.path("result").path("capabilities").path("githubAuth").asBoolean())
                .isTrue();
        assertThat(response.path("result").path("capabilities").path("prList").asBoolean())
                .isTrue();
        assertThat(response.path("result").path("capabilities").path("prDetail").asBoolean())
                .isTrue();
        assertThat(
                        response.path("result")
                                .path("capabilities")
                                .path("draftReviewMutations")
                                .asBoolean())
                .isTrue();
        assertThat(response.path("result").path("capabilities").path("prSearch").asBoolean())
                .isTrue();
        assertThat(response.path("result").path("capabilities").path("starredRepos").asBoolean())
                .isTrue();
        assertThat(response.path("result").path("capabilities").path("existingReviews").asBoolean())
                .isTrue();
        assertThat(
                        response.path("result")
                                .path("capabilities")
                                .path("reviewGeneration")
                                .asBoolean())
                .isTrue();
    }

    @Test
    void blockedHandlerDoesNotDelayLaterFrameAndResponsesStayFramed() throws Exception {
        CountDownLatch blockedEntered = new CountDownLatch(1);
        CountDownLatch releaseBlocked = new CountDownLatch(1);
        CountDownLatch fastFinished = new CountDownLatch(1);
        ReviewSessionService blockingReview =
                new ReviewSessionService() {
                    @Override
                    public ReviewEngineApi.GuidelinesResult readGuidelines(
                            ReviewEngineApi.ReadGuidelinesParams params) {
                        if ("block".equals(params.projectDir())) {
                            blockedEntered.countDown();
                            try {
                                if (!releaseBlocked.await(5, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException(
                                            "Timed out waiting for test release");
                                }
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(exception);
                            }
                        } else {
                            fastFinished.countDown();
                        }
                        return new ReviewEngineApi.GuidelinesResult(params.projectDir());
                    }
                };
        StdioJsonRpcServer concurrentServer =
                new StdioJsonRpcServer(
                        objectMapper,
                        frameCodec,
                        new SidecarBootstrapService(),
                        new GitHubEngine(),
                        blockingReview,
                        reviewExecutor);
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        frameCodec.writeFrame(
                input,
                ("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"reviews/readGuidelines\","
                                + "\"params\":{\"projectDir\":\"block\",\"globs\":[]}}")
                        .getBytes(StandardCharsets.UTF_8));
        frameCodec.writeFrame(
                input,
                ("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"reviews/readGuidelines\","
                                + "\"params\":{\"projectDir\":\"fast\",\"globs\":[]}}")
                        .getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread runner =
                new Thread(
                        () ->
                                concurrentServer.run(
                                        new ByteArrayInputStream(input.toByteArray()), output));

        try {
            runner.start();
            assertThat(blockedEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(fastFinished.await(5, TimeUnit.SECONDS)).isTrue();

            JsonNode fastResponse = null;
            long responseDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (fastResponse == null && System.nanoTime() < responseDeadline) {
                try {
                    byte[] responseFrame =
                            frameCodec.readFrame(new ByteArrayInputStream(output.toByteArray()));
                    if (responseFrame != null) {
                        fastResponse = objectMapper.readTree(responseFrame);
                    }
                } catch (IOException incompleteFrame) {
                    // The writer emits the header and payload separately; retry the snapshot.
                }
                if (fastResponse == null) Thread.sleep(5);
            }
            assertThat(fastResponse).isNotNull();
            assertThat(fastResponse.path("id").asInt()).isEqualTo(2);

            releaseBlocked.countDown();
            runner.join(5_000);
            assertThat(runner.isAlive()).isFalse();

            ByteArrayInputStream responses = new ByteArrayInputStream(output.toByteArray());
            Set<Integer> ids = new HashSet<>();
            byte[] responseFrame;
            while ((responseFrame = frameCodec.readFrame(responses)) != null) {
                ids.add(objectMapper.readTree(responseFrame).path("id").asInt());
            }
            assertThat(ids).containsExactlyInAnyOrder(1, 2);
        } finally {
            releaseBlocked.countDown();
            runner.join(5_000);
        }
    }

    @Test
    void returnsParseErrorForMalformedJson() throws IOException {
        JsonNode response = server.handle("not-json".getBytes(StandardCharsets.UTF_8));

        assertThat(response.path("id").isNull()).isTrue();
        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32700);
        assertThat(response.path("error").path("message").asText()).isEqualTo("Parse error");
    }

    @Test
    void returnsMethodNotFoundAndPreservesTheRequestId() throws IOException {
        JsonNode response =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":\"request-1\",\"method\":\"review/generate\"}"
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(response.path("id").asText()).isEqualTo("request-1");
        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32601);
    }

    @Test
    void suppressesResponsesToNotifications() {
        JsonNode response =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"method\":\"initialize\"}"
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(response).isNull();
    }
}
