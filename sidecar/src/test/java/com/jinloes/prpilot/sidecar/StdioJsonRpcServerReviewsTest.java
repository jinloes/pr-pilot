package com.jinloes.prpilot.sidecar;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinloes.prpilot.engine.GitHubEngine;
import com.jinloes.prpilot.engine.ReviewEngineApi;
import com.jinloes.prpilot.engine.ReviewSessionService;
import com.jinloes.prpilot.model.ReviewResult;
import com.jinloes.prpilot.review.ReviewOutcomeLog;
import com.jinloes.prpilot.review.SemanticReviewService;
import com.jinloes.prpilot.review.SemanticWorktreeStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StdioJsonRpcServerReviewsTest extends StdioJsonRpcServerTestBase {
    @Test
    void deepLifecycleDispatchesExactIdentityAndNeverStartsAProvider() throws Exception {
        String retained = "11111111-1111-4111-8111-111111111111";
        String head = "a".repeat(40);
        AtomicReference<ReviewEngineApi.PrepareDeepReviewParams> preparation =
                new AtomicReference<>();
        AtomicReference<ReviewEngineApi.CleanupDeepReviewParams> cleanup = new AtomicReference<>();
        ReviewSessionService fake =
                new ReviewSessionService() {
                    @Override
                    public SemanticReviewService.Preparation prepareDeepReview(
                            ReviewEngineApi.PrepareDeepReviewParams p) {
                        preparation.set(p);
                        return new SemanticReviewService.Preparation(
                                retained, p.headSha(), "/fixture/retained", List.of("private"));
                    }

                    @Override
                    public List<SemanticWorktreeStore.Retained> listDeepReviews() {
                        return List.of(
                                new SemanticWorktreeStore.Retained(
                                        retained, "/fixture", "/fixture/retained", head, 1));
                    }

                    @Override
                    public ReviewEngineApi.WorktreeRemovalResult cleanupDeepReview(
                            ReviewEngineApi.CleanupDeepReviewParams p) {
                        cleanup.set(p);
                        return new ReviewEngineApi.WorktreeRemovalResult(p.projectClosed());
                    }

                    @Override
                    public ReviewResult generate(
                            ReviewEngineApi.GenerateReviewParams p,
                            Consumer<String> status,
                            BiConsumer<String, String> chunks) {
                        throw new AssertionError(
                                "Preparation/maintenance must not start providers");
                    }
                };
        server =
                new StdioJsonRpcServer(
                        objectMapper,
                        frameCodec,
                        new SidecarBootstrapService(),
                        new GitHubEngine(),
                        fake,
                        reviewExecutor);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        server.run(new ByteArrayInputStream(new byte[0]), output);
        ObjectNode prepare = rpcRequest(101, "reviews/prepareDeepReview");
        prepare.putObject("params")
                .put("operationId", "deep-1")
                .put("gitRoot", "/fixture")
                .put("prNumber", 1)
                .put("branch", "main")
                .put("headSha", head)
                .put("forkCloneUrl", "")
                .put("prIdentity", "a/b#1")
                .put("diffDigest", "b".repeat(64));
        assertThat(server.handle(objectMapper.writeValueAsBytes(prepare))).isNull();
        assertThat(
                        awaitResponses(output, Set.of(101), 2, TimeUnit.SECONDS)
                                .get(101)
                                .path("result")
                                .path("retainedId")
                                .asText())
                .isEqualTo(retained);
        assertThat(preparation.get().prIdentity()).isEqualTo("a/b#1");
        assertThat(preparation.get().headSha()).isEqualTo(head);
        ObjectNode list = rpcRequest(102, "reviews/listDeepReviews");
        list.putObject("params");
        assertThat(server.handle(objectMapper.writeValueAsBytes(list))).isNull();
        assertThat(
                        awaitResponses(output, Set.of(102), 2, TimeUnit.SECONDS)
                                .get(102)
                                .path("result")
                                .get(0)
                                .path("id")
                                .asText())
                .isEqualTo(retained);
        ObjectNode remove = rpcRequest(103, "reviews/cleanupDeepReview");
        remove.putObject("params").put("retainedId", retained).put("projectClosed", false);
        assertThat(server.handle(objectMapper.writeValueAsBytes(remove))).isNull();
        assertThat(
                        awaitResponses(output, Set.of(103), 2, TimeUnit.SECONDS)
                                .get(103)
                                .path("result")
                                .path("removed")
                                .asBoolean())
                .isFalse();
        assertThat(cleanup.get())
                .isEqualTo(new ReviewEngineApi.CleanupDeepReviewParams(retained, false));
    }

    @Test
    void rejectsMalformedAndUnknownDeepLifecycleFieldsBeforeDispatch() throws Exception {
        for (String method :
                List.of(
                        "reviews/prepareDeepReview",
                        "reviews/listDeepReviews",
                        "reviews/cleanupDeepReview")) {
            ObjectNode request = rpcRequest(104, method);
            request.putObject("params").put("command", "unapproved");
            assertThat(
                            server.handle(objectMapper.writeValueAsBytes(request))
                                    .path("error")
                                    .path("code")
                                    .asInt())
                    .isEqualTo(-32602);
        }
    }

    @Test
    void immediateGenerateThenCancelRegistersBeforeQueuedRequestWork() throws Exception {
        int requestThreads = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
        CountDownLatch requestWorkersStarted = new CountDownLatch(requestThreads);
        CountDownLatch releaseRequestWorkers = new CountDownLatch(1);
        CountDownLatch reviewWorkerStarted = new CountDownLatch(1);
        CountDownLatch releaseReviewWorker = new CountDownLatch(1);
        ReviewSessionService blockingReview =
                new ReviewSessionService() {
                    @Override
                    public ReviewEngineApi.GuidelinesResult readGuidelines(
                            ReviewEngineApi.ReadGuidelinesParams params) {
                        requestWorkersStarted.countDown();
                        try {
                            if (!releaseRequestWorkers.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException(
                                        "Timed out waiting for request-worker release");
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(exception);
                        }
                        return new ReviewEngineApi.GuidelinesResult(params.projectDir());
                    }

                    @Override
                    public ReviewResult generate(
                            ReviewEngineApi.GenerateReviewParams params,
                            Consumer<String> onStatus,
                            BiConsumer<String, String> onChunk) {
                        return new ReviewResult("summary", "APPROVE", java.util.List.of());
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
        reviewExecutor.submit(
                () -> {
                    reviewWorkerStarted.countDown();
                    try {
                        releaseReviewWorker.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                });
        assertThat(reviewWorkerStarted.await(1, TimeUnit.SECONDS)).isTrue();

        ByteArrayOutputStream input = new ByteArrayOutputStream();
        for (int index = 0; index < 8; index++) {
            frameCodec.writeFrame(
                    input,
                    ("{\"jsonrpc\":\"2.0\",\"id\":"
                                    + (index + 1)
                                    + ",\"method\":\"reviews/readGuidelines\","
                                    + "\"params\":{\"projectDir\":\"block-"
                                    + index
                                    + "\",\"globs\":[]}}")
                            .getBytes(StandardCharsets.UTF_8));
        }
        frameCodec.writeFrame(input, generateRequest(43, "immediate-cancel"));
        frameCodec.writeFrame(
                input,
                ("{\"jsonrpc\":\"2.0\",\"id\":44,\"method\":\"reviews/cancel\","
                                + "\"params\":{\"operationId\":\"immediate-cancel\"}}")
                        .getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread runner =
                new Thread(
                        () ->
                                concurrentServer.run(
                                        new ByteArrayInputStream(input.toByteArray()), output));

        try {
            runner.start();
            assertThat(requestWorkersStarted.await(5, TimeUnit.SECONDS)).isTrue();

            Map<Integer, JsonNode> cancelResponse =
                    awaitResponses(output, Set.of(44), 5, TimeUnit.SECONDS);
            assertThat(cancelResponse.get(44).path("result").path("cancelled").asBoolean())
                    .isTrue();

            Map<Integer, JsonNode> terminalResponses =
                    awaitResponses(output, Set.of(43, 44), 5, TimeUnit.SECONDS);
            assertThat(terminalResponses.get(43).path("error").path("message").asText())
                    .isEqualTo("Review interrupted.");
        } finally {
            releaseRequestWorkers.countDown();
            releaseReviewWorker.countDown();
            runner.join(5_000);
            assertThat(runner.isAlive()).isFalse();
        }
    }

    @Test
    void asyncReviewNotificationsCompleteWithoutResponsesOrOperationLeaks() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicInteger generations = new AtomicInteger();
        ReviewSessionService fakeReview =
                new ReviewSessionService() {
                    @Override
                    public ReviewResult generate(
                            ReviewEngineApi.GenerateReviewParams params,
                            Consumer<String> onStatus,
                            BiConsumer<String, String> onChunk) {
                        generations.incrementAndGet();
                        return new ReviewResult("summary", "APPROVE", java.util.List.of());
                    }
                };
        server =
                new StdioJsonRpcServer(
                        objectMapper,
                        frameCodec,
                        new SidecarBootstrapService(),
                        new GitHubEngine(),
                        fakeReview,
                        reviewExecutor);
        server.run(new ByteArrayInputStream(new byte[0]), output);

        assertThat(invokeGenerateNotification()).isNull();
        reviewExecutor.submit(() -> {}).get(1, TimeUnit.SECONDS);
        assertThat(invokeGenerateNotification()).isNull();
        reviewExecutor.submit(() -> {}).get(1, TimeUnit.SECONDS);

        assertThat(generations).hasValue(2);
        assertThat(output.size()).isZero();
    }

    @Test
    void reviewsGenerateForwardsTheSupervisorSetting() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicReference<ReviewEngineApi.GenerateReviewParams> received = new AtomicReference<>();
        CountDownLatch generated = new CountDownLatch(1);
        ReviewSessionService fakeReview =
                new ReviewSessionService() {
                    @Override
                    public ReviewResult generate(
                            ReviewEngineApi.GenerateReviewParams params,
                            Consumer<String> onStatus,
                            BiConsumer<String, String> onChunk) {
                        received.set(params);
                        generated.countDown();
                        return new ReviewResult("summary", "APPROVE", java.util.List.of());
                    }
                };
        server =
                new StdioJsonRpcServer(
                        objectMapper,
                        frameCodec,
                        new SidecarBootstrapService(),
                        new GitHubEngine(),
                        fakeReview,
                        reviewExecutor);
        server.run(new ByteArrayInputStream(new byte[0]), output);

        JsonNode response =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"method\":\"reviews/generate\",\"params\":{"
                                        + "\"operationId\":\"supervised\",\"provider\":\"claude\","
                                        + "\"model\":\"\",\"effort\":\"\",\"inheritMcp\":false,"
                                        + "\"reviewSupervisorEnabled\":true,"
                                        + "\"pr\":{\"title\":\"T\",\"htmlUrl\":\"\",\"owner\":\"o\","
                                        + "\"repo\":\"r\",\"number\":1,\"body\":\"\",\"author\":\"a\","
                                        + "\"createdAt\":\"2024-01-01\",\"isDraft\":false},"
                                        + "\"diff\":\"\",\"ciStatus\":\"\"}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(response).isNull();
        assertThat(generated.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(received.get().reviewSupervisorEnabled()).isTrue();
    }

    @Test
    void reviewsGenerateReturnsNullSynchronouslyWithoutBlockingTheReadLoop() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        server = serverWithOutput(output);
        // Priming run() with empty input sets the server's output stream and returns immediately.
        server.run(new ByteArrayInputStream(new byte[0]), output);

        // handle() must dispatch to a background thread and return null immediately rather than
        // blocking the read loop on the provider CLI (which this test does not depend on being
        // installed, to stay deterministic across environments).
        JsonNode syncResponse = invokeGenerateDirectly();
        assertThat(syncResponse).isNull();
    }

    @Test
    void reviewsGenerateRejectsMissingRequiredParams() {
        JsonNode response =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"reviews/generate\",\"params\":{}}"
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void reviewsChatRejectsInvalidParams() {
        JsonNode response =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"reviews/chat\",\"params\":\"nope\"}"
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void reviewsChatRejectsBothPromptForms() throws IOException {
        ObjectNode request = rpcRequest(2, "reviews/chat");
        request.putObject("params")
                .put("operationId", "chat-1")
                .put("provider", "claude")
                .put("userMessage", "question")
                .put("rawPrompt", "prompt");
        JsonNode response = server.handle(objectMapper.writeValueAsBytes(request));

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void reviewsCancelIsSynchronousAndAlwaysSucceeds() {
        JsonNode response =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"reviews/cancel\","
                                        + "\"params\":{\"operationId\":\"review-1\"}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(response.path("result").path("cancelled").asBoolean()).isFalse();
    }

    @Test
    void reviewsCancelAcknowledgesAnOperationStillQueuedInTheExecutor() throws Exception {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        server.run(new ByteArrayInputStream(new byte[0]), output);
        reviewExecutor.submit(
                () -> {
                    workerStarted.countDown();
                    try {
                        releaseWorker.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                });
        assertThat(workerStarted.await(1, TimeUnit.SECONDS)).isTrue();

        JsonNode queued = invokeGenerateDirectly();
        JsonNode cancelled =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"reviews/cancel\","
                                        + "\"params\":{\"operationId\":\"review-1\"}}")
                                .getBytes(StandardCharsets.UTF_8));
        releaseWorker.countDown();

        assertThat(queued).isNull();
        assertThat(cancelled.path("result").path("cancelled").asBoolean()).isTrue();
        ByteArrayInputStream frames = new ByteArrayInputStream(output.toByteArray());
        JsonNode originalResponse = objectMapper.readTree(frameCodec.readFrame(frames));
        assertThat(originalResponse.path("id").asInt()).isEqualTo(43);
        assertThat(originalResponse.path("error").path("message").asText())
                .isEqualTo("Review interrupted.");
        assertThat(frameCodec.readFrame(frames)).isNull();
    }

    @Test
    void reviewsCancelRejectsMissingOperationId() {
        JsonNode response =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"reviews/cancel\","
                                        + "\"params\":{}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void reviewsCancelRejectsBlankOrControlCharacterOperationIds() throws IOException {
        ObjectNode blankRequest = rpcRequest(5, "reviews/cancel");
        blankRequest.putObject("params").put("operationId", " ");
        ObjectNode controlRequest = rpcRequest(6, "reviews/cancel");
        controlRequest.putObject("params").put("operationId", "\n");
        JsonNode blank = server.handle(objectMapper.writeValueAsBytes(blankRequest));
        JsonNode control = server.handle(objectMapper.writeValueAsBytes(controlRequest));

        assertThat(blank.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(control.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Nested
    class RecordOutcome {

        private Path logFile;
        private StdioJsonRpcServer outcomeServer;

        @BeforeEach
        void setUp(@TempDir Path tmpDir) {
            logFile = tmpDir.resolve("review-outcomes.jsonl");
            outcomeServer =
                    new StdioJsonRpcServer(
                            objectMapper,
                            frameCodec,
                            new SidecarBootstrapService(),
                            new GitHubEngine(),
                            new ReviewSessionService(new ReviewOutcomeLog(logFile)),
                            reviewExecutor);
        }

        private JsonNode call(String params) {
            return outcomeServer.handle(
                    ("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"reviews/recordOutcome\","
                                    + "\"params\":"
                                    + params
                                    + "}")
                            .getBytes(StandardCharsets.UTF_8));
        }

        @Test
        void classifiesTheSuppliedCommentsAndWritesThemToTheLog() throws IOException {
            JsonNode response =
                    call(
                            "{\"provider\":\"claude\",\"model\":\"sonnet\","
                                    + "\"generated\":[{\"file\":\"A.java\",\"line\":1,\"body\":\"kept\"},"
                                    + "{\"file\":\"A.java\",\"line\":2,\"body\":\"gone\"}],"
                                    + "\"submitted\":[{\"file\":\"A.java\",\"line\":1,\"body\":\"kept\"}]}");

            assertThat(response.path("result").path("recorded").asInt()).isEqualTo(2);
            assertThat(Files.readAllLines(logFile, StandardCharsets.UTF_8)).hasSize(2);
        }

        /** An all-deleted review is meaningful, so an absent array is empty rather than invalid. */
        @Test
        void treatsAnAbsentCommentArrayAsEmpty() {
            JsonNode response =
                    call(
                            "{\"provider\":\"claude\",\"model\":\"m\","
                                    + "\"generated\":[{\"file\":\"A.java\",\"line\":1,\"body\":\"x\"}]}");

            assertThat(response.path("result").path("recorded").asInt()).isEqualTo(1);
        }

        @Test
        void rejectsUnknownFieldsAndMalformedComments() {
            assertThat(
                            call("{\"provider\":\"c\",\"model\":\"m\",\"unexpected\":1}")
                                    .path("error")
                                    .path("code")
                                    .asInt())
                    .isEqualTo(-32602);
            assertThat(
                            call("{\"provider\":\"c\",\"model\":\"m\",\"generated\":"
                                            + "[{\"file\":\"A.java\",\"line\":\"NaN\",\"body\":\"x\"}]}")
                                    .path("error")
                                    .path("code")
                                    .asInt())
                    .isEqualTo(-32602);
            assertThat(
                            call("{\"provider\":\"c\",\"model\":\"m\",\"generated\":\"not-an-array\"}")
                                    .path("error")
                                    .path("code")
                                    .asInt())
                    .isEqualTo(-32602);
        }

        @Test
        void rejectsNonTextualProviderOrModel() {
            assertThat(call("{\"provider\":5,\"model\":\"m\"}").path("error").path("code").asInt())
                    .isEqualTo(-32602);
        }
    }

    @Nested
    class ReadGuidelines {

        private JsonNode call(String params) {
            return server.handle(
                    ("{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"reviews/readGuidelines\","
                                    + "\"params\":"
                                    + params
                                    + "}")
                            .getBytes(StandardCharsets.UTF_8));
        }

        @Test
        void readsGuidanceFromTheRequestedDirectory(@TempDir Path tmpDir) throws IOException {
            Files.writeString(tmpDir.resolve("AGENTS.md"), "Prefer small PRs.");

            JsonNode response =
                    call("{\"projectDir\":\"" + tmpDir.toString().replace("\\", "\\\\") + "\"}");

            assertThat(response.path("result").path("guidelines").asText())
                    .contains("## AGENTS.md")
                    .contains("Prefer small PRs.");
        }

        /** Omitting globs must select the engine defaults, not match nothing. */
        @Test
        void treatsAnOmittedGlobArrayAsTheEngineDefaults(@TempDir Path tmpDir) throws IOException {
            Files.writeString(tmpDir.resolve("CONTRIBUTING.md"), "Sign your commits.");

            JsonNode response =
                    call("{\"projectDir\":\"" + tmpDir.toString().replace("\\", "\\\\") + "\"}");

            assertThat(response.path("result").path("guidelines").asText())
                    .contains("Sign your commits.");
        }

        @Test
        void rejectsAMissingOrNonTextualProjectDir() {
            assertThat(call("{}").path("error").path("code").asInt()).isEqualTo(-32602);
            assertThat(call("{\"projectDir\":5}").path("error").path("code").asInt())
                    .isEqualTo(-32602);
        }

        @Test
        void rejectsUnknownFieldsAndMalformedGlobs() {
            assertThat(
                            call("{\"projectDir\":\"/tmp\",\"unexpected\":1}")
                                    .path("error")
                                    .path("code")
                                    .asInt())
                    .isEqualTo(-32602);
            assertThat(
                            call("{\"projectDir\":\"/tmp\",\"globs\":\"not-an-array\"}")
                                    .path("error")
                                    .path("code")
                                    .asInt())
                    .isEqualTo(-32602);
            assertThat(
                            call("{\"projectDir\":\"/tmp\",\"globs\":[5]}")
                                    .path("error")
                                    .path("code")
                                    .asInt())
                    .isEqualTo(-32602);
        }
    }
}
