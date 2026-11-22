package com.jinloes.prpilot.sidecar;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinloes.prpilot.engine.GitHubEngine;
import com.jinloes.prpilot.engine.ReviewEngineApi;
import com.jinloes.prpilot.engine.ReviewSessionService;
import com.jinloes.prpilot.review.ProviderSetupProbe;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class StdioJsonRpcServerAntigravityTest extends StdioJsonRpcServerTestBase {

    @Test
    void listAntigravityModelsValidatesParamsAndDelegatesToEngine() throws Exception {
        AtomicReference<Boolean> seenForceRefresh = new AtomicReference<>();
        ReviewSessionService fakeReview =
                new ReviewSessionService() {
                    @Override
                    public ReviewEngineApi.AntigravityModelsResult listAntigravityModels(
                            ReviewEngineApi.ListAntigravityModelsParams params) {
                        seenForceRefresh.set(params.forceRefresh());
                        return new ReviewEngineApi.AntigravityModelsResult(
                                List.of("gemini-3.8-flash-high", "claude-sonnet-4-6"),
                                "ACCOUNT",
                                "");
                    }
                };
        StdioJsonRpcServer customServer =
                new StdioJsonRpcServer(
                        objectMapper,
                        frameCodec,
                        new SidecarBootstrapService(),
                        new GitHubEngine(),
                        fakeReview,
                        reviewExecutor);

        ObjectNode forceReq = rpcRequest(51, "reviews/listAntigravityModels");
        forceReq.putObject("params").put("forceRefresh", true);
        JsonNode ok = customServer.handle(objectMapper.writeValueAsBytes(forceReq));
        assertThat(seenForceRefresh.get()).isTrue();
        assertThat(ok.path("result").path("source").asText()).isEqualTo("ACCOUNT");
        assertThat(ok.path("result").path("models"))
                .extracting(JsonNode::asText)
                .containsExactly("gemini-3.8-flash-high", "claude-sonnet-4-6");

        ObjectNode defaultReq = rpcRequest(52, "reviews/listAntigravityModels");
        JsonNode defaulted = customServer.handle(objectMapper.writeValueAsBytes(defaultReq));
        assertThat(seenForceRefresh.get()).isFalse();
        assertThat(defaulted.path("result").path("models")).hasSize(2);

        ObjectNode badReq = rpcRequest(53, "reviews/listAntigravityModels");
        badReq.putObject("params").put("forceRefresh", "yes");
        JsonNode bad = customServer.handle(objectMapper.writeValueAsBytes(badReq));
        assertThat(bad.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void probeProviderSetupAcceptsAntigravityAndRejectsUnknownProvider() throws Exception {
        AtomicReference<ReviewEngineApi.ProbeProviderSetupParams> seenParams =
                new AtomicReference<>();
        ReviewSessionService fakeReview =
                new ReviewSessionService() {
                    @Override
                    public ProviderSetupProbe.Result probeProviderSetup(
                            ReviewEngineApi.ProbeProviderSetupParams params) {
                        seenParams.set(params);
                        return new ProviderSetupProbe.Result(true, "ready", "unverified", "agy");
                    }
                };
        StdioJsonRpcServer customServer =
                new StdioJsonRpcServer(
                        objectMapper,
                        frameCodec,
                        new SidecarBootstrapService(),
                        new GitHubEngine(),
                        fakeReview,
                        reviewExecutor);

        ObjectNode agyReq = rpcRequest(61, "reviews/probeProviderSetup");
        agyReq.putObject("params").put("provider", "antigravity").put("checkAuthentication", true);
        JsonNode ok = customServer.handle(objectMapper.writeValueAsBytes(agyReq));
        assertThat(seenParams.get())
                .isEqualTo(new ReviewEngineApi.ProbeProviderSetupParams("antigravity", true));
        assertThat(ok.path("result").path("binaryStatus").asText()).isEqualTo("ready");
        assertThat(ok.path("result").path("authCommand").asText()).isEqualTo("agy");

        ObjectNode invalidReq = rpcRequest(62, "reviews/probeProviderSetup");
        invalidReq.putObject("params").put("provider", "unknown");
        JsonNode bad = customServer.handle(objectMapper.writeValueAsBytes(invalidReq));
        assertThat(bad.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(bad.toString().getBytes(StandardCharsets.UTF_8)).isNotEmpty();
    }
}
