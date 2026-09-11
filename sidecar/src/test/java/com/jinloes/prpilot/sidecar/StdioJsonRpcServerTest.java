package com.jinloes.prpilot.sidecar;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StdioJsonRpcServerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StdioFrameCodec frameCodec = new StdioFrameCodec();
    private final ExecutorService reviewExecutor = Executors.newSingleThreadExecutor();
    private StdioJsonRpcServer server;

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
    void detectsARepoFromAGitConfig(@TempDir java.nio.file.Path tempDir) throws IOException {
        java.nio.file.Path gitDir = tempDir.resolve(".git");
        java.nio.file.Files.createDirectories(gitDir);
        java.nio.file.Files.writeString(
                gitDir.resolve("config"),
                "[remote \"origin\"]\n\turl = https://github.com/acme/widgets.git\n");

        com.fasterxml.jackson.databind.node.ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", "repo-1");
        request.put("method", "repo/detect");
        request.putObject("params").put("path", tempDir.toString());

        JsonNode response = server.handle(objectMapper.writeValueAsBytes(request));

        assertThat(response.path("id").asText()).isEqualTo("repo-1");
        assertThat(response.path("result").path("status").asText()).isEqualTo("found");
        assertThat(response.path("result").path("repository").path("owner").asText())
                .isEqualTo("acme");
        assertThat(response.path("result").path("repository").path("repo").asText())
                .isEqualTo("widgets");
    }

    @Test
    void rejectsInvalidRepoDetectParams() {
        JsonNode missingField =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"repo/detect\",\"params\":{}}"
                                .getBytes(StandardCharsets.UTF_8));
        JsonNode nonTextField =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":13,\"method\":\"repo/detect\",\"params\":{\"path\":1}}"
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(missingField.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(nonTextField.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void returnsAStructuredResultForAnInvalidGitHubBaseUrl() {
        JsonNode response =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":\"auth-1\",\"method\":\"github/checkAuth\","
                                        + "\"params\":{\"githubBaseUrl\":\"http://github.com\"}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(response.path("id").asText()).isEqualTo("auth-1");
        assertThat(response.path("result").path("status").asText()).isEqualTo("invalid_base_url");
        assertThat(response.path("result").path("username").isNull()).isTrue();
    }

    @Test
    void rejectsInvalidGitHubAuthParams() {
        JsonNode missingField =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":14,\"method\":\"github/checkAuth\",\"params\":{}}"
                                .getBytes(StandardCharsets.UTF_8));
        JsonNode extraField =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":15,\"method\":\"github/checkAuth\","
                                        + "\"params\":{\"githubBaseUrl\":\"https://github.com\",\"extra\":\"x\"}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(missingField.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(extraField.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void acceptsBoundedCommitIssueNumbersForLinkedIssueResolution() {
        JsonNode response =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":16,\"method\":\"prs/getLinkedIssues\","
                                        + "\"params\":{\"githubBaseUrl\":\"http://github.com\","
                                        + "\"owner\":\"acme\",\"repo\":\"widgets\",\"prBody\":\"\","
                                        + "\"commitIssueNumbers\":[7]}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(response.path("error").isMissingNode()).isTrue();
        assertThat(response.path("result").path("status").asText()).isEqualTo("invalid_base_url");
    }

    @Test
    void rejectsMalformedCommitIssueNumberArrays() {
        for (String commitIssueNumbers :
                java.util.List.of(
                        "null",
                        "\"7\"",
                        "[1,\"2\"]",
                        "[0]",
                        "[7,7]",
                        "[1,2,3,4]",
                        "[1000000000]",
                        "[2147483648]")) {
            JsonNode response =
                    server.handle(
                            ("{\"jsonrpc\":\"2.0\",\"id\":17,\"method\":\"prs/getLinkedIssues\","
                                            + "\"params\":{\"githubBaseUrl\":\"https://github.com\","
                                            + "\"owner\":\"acme\",\"repo\":\"widgets\",\"prBody\":\"\","
                                            + "\"commitIssueNumbers\":"
                                            + commitIssueNumbers
                                            + "}}")
                                    .getBytes(StandardCharsets.UTF_8));

            assertThat(response.path("error").path("code").asInt())
                    .as(commitIssueNumbers)
                    .isEqualTo(-32602);
        }

        JsonNode missing =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":18,\"method\":\"prs/getLinkedIssues\","
                                        + "\"params\":{\"githubBaseUrl\":\"https://github.com\","
                                        + "\"owner\":\"acme\",\"repo\":\"widgets\",\"prBody\":\"\"}}")
                                .getBytes(StandardCharsets.UTF_8));
        assertThat(missing.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void returnsAStructuredResultForAnInvalidPrListBaseUrl() {
        JsonNode response =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":\"list-1\",\"method\":\"prs/list\","
                                        + "\"params\":{\"githubBaseUrl\":\"http://github.com\"}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(response.path("id").asText()).isEqualTo("list-1");
        assertThat(response.path("result").path("status").asText()).isEqualTo("invalid_base_url");
        assertThat(response.path("result").path("prs").isArray()).isTrue();
        assertThat(response.path("result").path("prs")).isEmpty();
    }

    @Test
    void rejectsInvalidPrListParams() {
        JsonNode missingBaseUrl =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":16,\"method\":\"prs/list\",\"params\":{}}"
                                .getBytes(StandardCharsets.UTF_8));
        JsonNode nonTextField =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":17,\"method\":\"prs/list\","
                                        + "\"params\":{\"githubBaseUrl\":\"https://github.com\",\"state\":true}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(missingBaseUrl.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(nonTextField.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void returnsAStructuredResultForAnInvalidPrDetailBaseUrl() {
        JsonNode response =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":\"detail-1\",\"method\":\"prs/getDetail\","
                                        + "\"params\":{\"githubBaseUrl\":\"http://github.com\",\"owner\":\"acme\",\"repo\":\"widgets\",\"number\":42}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(response.path("id").asText()).isEqualTo("detail-1");
        assertThat(response.path("result").path("status").asText()).isEqualTo("invalid_base_url");
        assertThat(response.path("result").path("detail").isNull()).isTrue();
    }

    @Test
    void rejectsInvalidPrDetailParams() {
        JsonNode missingNumber =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":18,\"method\":\"prs/getDetail\","
                                        + "\"params\":{\"githubBaseUrl\":\"https://github.com\",\"owner\":\"acme\",\"repo\":\"widgets\"}}")
                                .getBytes(StandardCharsets.UTF_8));
        JsonNode nonIntegralNumber =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":19,\"method\":\"prs/getDetail\","
                                        + "\"params\":{\"githubBaseUrl\":\"https://github.com\",\"owner\":\"acme\",\"repo\":\"widgets\",\"number\":1.5}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(missingNumber.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(nonIntegralNumber.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void returnsAStructuredResultForAnInvalidDraftReviewBaseUrl() {
        JsonNode response =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":\"draft-1\",\"method\":\"prs/getDraftReview\","
                                        + "\"params\":{\"githubBaseUrl\":\"http://github.com\",\"owner\":\"acme\",\"repo\":\"widgets\",\"number\":42}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(response.path("id").asText()).isEqualTo("draft-1");
        assertThat(response.path("result").path("status").asText()).isEqualTo("invalid_base_url");
        assertThat(response.path("result").path("review").isNull()).isTrue();
    }

    @Test
    void rejectsInvalidDraftReviewParams() {
        JsonNode missingNumber =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":20,\"method\":\"prs/getDraftReview\","
                                        + "\"params\":{\"githubBaseUrl\":\"https://github.com\",\"owner\":\"acme\",\"repo\":\"widgets\"}}")
                                .getBytes(StandardCharsets.UTF_8));
        JsonNode extraField =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":21,\"method\":\"prs/getDraftReview\","
                                        + "\"params\":{\"githubBaseUrl\":\"https://github.com\",\"owner\":\"acme\",\"repo\":\"widgets\",\"number\":1,\"extra\":\"x\"}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(missingNumber.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(extraField.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void returnsAStructuredResultForAnInvalidSaveDraftReviewBaseUrl() {
        JsonNode response =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":\"save-1\",\"method\":\"prs/saveDraftReview\","
                                        + "\"params\":{\"githubBaseUrl\":\"http://github.com\",\"owner\":\"acme\","
                                        + "\"repo\":\"widgets\",\"number\":42,\"summary\":\"s\",\"verdict\":\"APPROVE\","
                                        + "\"lineComments\":[]}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(response.path("id").asText()).isEqualTo("save-1");
        assertThat(response.path("result").path("status").asText()).isEqualTo("invalid_base_url");
    }

    @Test
    void rejectsInvalidSaveDraftReviewParams() {
        JsonNode missingLineComments =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":22,\"method\":\"prs/saveDraftReview\","
                                        + "\"params\":{\"githubBaseUrl\":\"https://github.com\",\"owner\":\"acme\","
                                        + "\"repo\":\"widgets\",\"number\":1,\"summary\":\"s\",\"verdict\":\"APPROVE\"}}")
                                .getBytes(StandardCharsets.UTF_8));
        JsonNode malformedComment =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":23,\"method\":\"prs/saveDraftReview\","
                                        + "\"params\":{\"githubBaseUrl\":\"https://github.com\",\"owner\":\"acme\","
                                        + "\"repo\":\"widgets\",\"number\":1,\"summary\":\"s\",\"verdict\":\"APPROVE\","
                                        + "\"lineComments\":[{\"file\":\"a.java\"}]}}")
                                .getBytes(StandardCharsets.UTF_8));
        JsonNode extraField =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":24,\"method\":\"prs/saveDraftReview\","
                                        + "\"params\":{\"githubBaseUrl\":\"https://github.com\",\"owner\":\"acme\","
                                        + "\"repo\":\"widgets\",\"number\":1,\"summary\":\"s\",\"verdict\":\"APPROVE\","
                                        + "\"lineComments\":[],\"extra\":true}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(missingLineComments.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(malformedComment.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(extraField.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void returnsAStructuredResultForAnInvalidSubmitReviewBaseUrl() {
        JsonNode response =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":\"submit-1\",\"method\":\"prs/submitReview\","
                                        + "\"params\":{\"githubBaseUrl\":\"http://github.com\",\"owner\":\"acme\","
                                        + "\"repo\":\"widgets\",\"number\":42,\"reviewId\":\"7\",\"event\":\"APPROVE\",\"body\":\"\"}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(response.path("id").asText()).isEqualTo("submit-1");
        assertThat(response.path("result").path("status").asText()).isEqualTo("invalid_base_url");
    }

    @Test
    void rejectsInvalidSubmitReviewParams() {
        JsonNode missingReviewId =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":25,\"method\":\"prs/submitReview\","
                                        + "\"params\":{\"githubBaseUrl\":\"https://github.com\",\"owner\":\"acme\","
                                        + "\"repo\":\"widgets\",\"number\":1,\"event\":\"APPROVE\",\"body\":\"\"}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(missingReviewId.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void returnsAStructuredResultForAnInvalidDeleteDraftReviewBaseUrl() {
        JsonNode response =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":\"delete-1\",\"method\":\"prs/deleteDraftReview\","
                                        + "\"params\":{\"githubBaseUrl\":\"http://github.com\",\"owner\":\"acme\","
                                        + "\"repo\":\"widgets\",\"number\":42,\"reviewId\":\"7\"}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(response.path("id").asText()).isEqualTo("delete-1");
        assertThat(response.path("result").path("status").asText()).isEqualTo("invalid_base_url");
    }

    @Test
    void rejectsInvalidDeleteDraftReviewParams() {
        JsonNode missingReviewId =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":26,\"method\":\"prs/deleteDraftReview\","
                                        + "\"params\":{\"githubBaseUrl\":\"https://github.com\",\"owner\":\"acme\","
                                        + "\"repo\":\"widgets\",\"number\":1}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(missingReviewId.path("error").path("code").asInt()).isEqualTo(-32602);
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

    /** Re-runs the request with a fresh id (43) against a server whose output is captured. */
    private JsonNode invokeGenerateDirectly() {
        return server.handle(generateRequest(43, "review-1"));
    }

    private JsonNode invokeGenerateNotification() {
        return server.handle(
                ("{\"jsonrpc\":\"2.0\",\"method\":\"reviews/generate\",\"params\":{"
                                + "\"operationId\":\"notification-1\",\"provider\":\"claude\",\"model\":\"\",\"effort\":\"\",\"inheritMcp\":false,"
                                + "\"pr\":{\"title\":\"T\",\"htmlUrl\":\"\",\"owner\":\"o\",\"repo\":\"r\","
                                + "\"number\":1,\"body\":\"\",\"author\":\"a\",\"createdAt\":\"2024-01-01\","
                                + "\"isDraft\":false},\"diff\":\"\",\"ciStatus\":\"\"}}")
                        .getBytes(StandardCharsets.UTF_8));
    }

    private byte[] generateRequest(int id, String operationId) {
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

    private Map<Integer, JsonNode> awaitResponses(
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

    private ObjectNode rpcRequest(int id, String method) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        return request;
    }

    private StdioJsonRpcServer serverWithOutput(ByteArrayOutputStream output) {
        return new StdioJsonRpcServer(
                objectMapper,
                frameCodec,
                new SidecarBootstrapService(),
                new GitHubEngine(),
                new ReviewSessionService(),
                reviewExecutor);
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
