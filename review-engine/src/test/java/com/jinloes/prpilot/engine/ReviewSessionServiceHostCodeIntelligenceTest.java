package com.jinloes.prpilot.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinloes.prpilot.engine.ReviewEngineApi.ChatParams;
import com.jinloes.prpilot.engine.ReviewEngineApi.GenerateReviewParams;
import com.jinloes.prpilot.engine.ReviewEngineApi.HostCodeIntelligenceParams;
import com.jinloes.prpilot.review.HostCodeIntelligence;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for how review and chat requests carry the host's code-intelligence endpoint. */
class ReviewSessionServiceHostCodeIntelligenceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String URL = "http://127.0.0.1:41234/mcp";
    private static final String TOKEN = "t".repeat(43);

    @Nested
    class HostCodeIntelligenceMethod {

        @Test
        void absentParamsMeanNoServer() {
            assertThat(ReviewSessionService.hostCodeIntelligence(null)).isNull();
        }

        @Test
        void validParamsBecomeTheEndpoint() {
            assertThat(
                            ReviewSessionService.hostCodeIntelligence(
                                    new HostCodeIntelligenceParams(URL, TOKEN)))
                    .isEqualTo(new HostCodeIntelligence(URL, TOKEN));
        }

        @Test
        void aRemoteUrlIsDroppedRatherThanFailingTheReview() {
            assertThat(
                            ReviewSessionService.hostCodeIntelligence(
                                    new HostCodeIntelligenceParams(
                                            "http://example.com:80/mcp", TOKEN)))
                    .isNull();
        }
    }

    @Nested
    class Deserialization {

        @Test
        void generateParamsCarryTheEndpointFromTheWire() throws Exception {
            ObjectNode params = JSON.createObjectNode();
            params.put("operationId", "op-1");
            params.put("provider", "copilot");
            params.put("diff", "");
            params.putObject("hostCodeIntelligence").put("url", URL).put("token", TOKEN);

            GenerateReviewParams decoded = JSON.treeToValue(params, GenerateReviewParams.class);

            assertThat(decoded.hostCodeIntelligence())
                    .isEqualTo(new HostCodeIntelligenceParams(URL, TOKEN));
        }

        @Test
        void chatParamsCarryTheEndpointFromTheWire() throws Exception {
            ObjectNode params = JSON.createObjectNode();
            params.put("operationId", "op-1");
            params.put("provider", "copilot");
            params.put("rawPrompt", "q");
            params.putObject("hostCodeIntelligence").put("url", URL).put("token", TOKEN);

            ChatParams decoded = JSON.treeToValue(params, ChatParams.class);

            assertThat(decoded.hostCodeIntelligence())
                    .isEqualTo(new HostCodeIntelligenceParams(URL, TOKEN));
        }

        @Test
        void legacyConstructorsLeaveTheEndpointUnset() {
            ChatParams chat =
                    new ChatParams(
                            "op", "copilot", null, "high", false, null, "", List.of(), "q", null);

            assertThat(chat.hostCodeIntelligence()).isNull();
        }
    }
}
