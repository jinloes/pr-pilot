package com.jinloes.prpilot.sidecar;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jinloes.prpilot.engine.GitHubEngine;
import com.jinloes.prpilot.engine.ReviewEngineApi;
import com.jinloes.prpilot.engine.ReviewSessionService;
import com.jinloes.prpilot.model.ReviewResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class StdioJsonRpcServerErrorLoggingTest extends StdioJsonRpcServerTestBase {

    @Nested
    class SafeMessage {

        @Test
        void fallsBackToExceptionTypeWhenMessageIsNullOrBlank() {
            assertThat(
                            StdioJsonRpcServerAsyncReviewHandlers.safeMessage(
                                    new NullPointerException()))
                    .isEqualTo("NullPointerException");
            assertThat(StdioJsonRpcServerAsyncReviewHandlers.safeMessage(new IOException("   ")))
                    .isEqualTo("IOException");
        }

        @Test
        void redactsCredentialsFromWireErrorMessage() {
            String token = "ghp_" + "k".repeat(36);
            String rendered =
                    StdioJsonRpcServerAsyncReviewHandlers.safeMessage(
                            new IOException("upstream failed for " + token));

            assertThat(rendered).isEqualTo("upstream failed for <redacted>").doesNotContain(token);
        }
    }

    @Nested
    class GenerateAndRequestFailures {

        @Test
        void blankProviderExceptionReturnsClassNameInErrorFrame() throws Exception {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ReviewSessionService failing =
                    new ReviewSessionService() {
                        @Override
                        public ReviewResult generate(
                                ReviewEngineApi.GenerateReviewParams params,
                                Consumer<String> onStatus,
                                BiConsumer<String, String> onChunk)
                                throws IOException {
                            throw new IOException("");
                        }
                    };
            StdioJsonRpcServer failingServer =
                    new StdioJsonRpcServer(
                            objectMapper,
                            frameCodec,
                            new SidecarBootstrapService(),
                            new GitHubEngine(),
                            failing,
                            reviewExecutor);
            failingServer.run(new ByteArrayInputStream(new byte[0]), output);

            assertThat(failingServer.handle(generateRequest(51, "blank-failure"))).isNull();
            JsonNode response = awaitResponses(output, Set.of(51), 5, TimeUnit.SECONDS).get(51);

            assertThat(response.path("error").path("code").asInt()).isEqualTo(-32000);
            assertThat(response.path("error").path("message").asText()).isEqualTo("IOException");
        }

        @Test
        void nonVirtualMachineErrorInSynchronousHandlerReturnsInternalErrorInsteadOfHanging() {
            ReviewSessionService broken =
                    new ReviewSessionService() {
                        @Override
                        public ReviewEngineApi.GitRootResult findGitRoot(String startDir) {
                            throw new AssertionError("boom");
                        }
                    };
            StdioJsonRpcServer brokenServer =
                    new StdioJsonRpcServer(
                            objectMapper,
                            frameCodec,
                            new SidecarBootstrapService(),
                            new GitHubEngine(),
                            broken,
                            reviewExecutor);

            JsonNode response =
                    brokenServer.handle(
                            ("{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"reviews/findGitRoot\","
                                            + "\"params\":{\"startDir\":\"/tmp\"}}")
                                    .getBytes(StandardCharsets.UTF_8));

            assertThat(response.path("error").path("code").asInt()).isEqualTo(-32603);
            assertThat(response.path("error").path("message").asText()).isEqualTo("Internal error");
        }
    }
}
