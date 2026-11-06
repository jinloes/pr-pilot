package com.jinloes.prpilot.review;

import static com.jinloes.prpilot.review.CopilotServiceTestSupport.factoryFor;
import static com.jinloes.prpilot.review.CopilotServiceTestSupport.fakeRequest;
import static com.jinloes.prpilot.review.CopilotServiceTestSupport.reviewJson;
import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.review.CopilotServiceTestSupport.FakeRuntimeFactory;
import com.jinloes.prpilot.review.CopilotServiceTestSupport.FakeRuntimeSession;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for attaching the host's code-intelligence MCP server to Copilot sessions. */
class CopilotServiceHostCodeIntelligenceTest {

    private static final HostCodeIntelligence HOST =
            new HostCodeIntelligence("http://127.0.0.1:41234/mcp", "t".repeat(43));

    private static FakeRuntimeFactory approvingFactory() {
        return factoryFor(
                () -> {
                    FakeRuntimeSession s = new FakeRuntimeSession();
                    s.sendAction = session -> session.emitMessage(reviewJson("APPROVE"));
                    return s;
                });
    }

    @Nested
    class WithHostCodeIntelligence {

        @Test
        void attachesTheServerAndAppendsTheToolHintToReviewPrompts() throws Exception {
            FakeRuntimeFactory factory = approvingFactory();
            CopilotService service =
                    new CopilotService(null, factory).withHostCodeIntelligence(HOST);

            service.reviewPR(fakeRequest(), "", "medium", ignored -> {});

            assertThat(factory.lastClient.lastSessionRequest.hostCodeIntelligence())
                    .isEqualTo(HOST);
            assertThat(factory.lastClient.lastSession.lastPrompt)
                    .endsWith(ReviewInstructions.HOST_CODE_INTELLIGENCE)
                    .contains(HostCodeIntelligence.SERVER_NAME);
        }

        @Test
        void appliesToChatSessionsToo() throws Exception {
            FakeRuntimeFactory factory = approvingFactory();
            CopilotService service =
                    new CopilotService(null, factory).withHostCodeIntelligence(HOST);

            service.chatWithPrompt("question", "medium", ignored -> {});

            assertThat(factory.lastClient.lastSessionRequest.attachesHostCodeIntelligence())
                    .isTrue();
            assertThat(factory.lastClient.lastSession.lastPrompt)
                    .isEqualTo("question\n\n" + ReviewInstructions.HOST_CODE_INTELLIGENCE);
        }

        @Test
        void toolFreeSessionsGetNeitherTheServerNorTheHint() throws Exception {
            FakeRuntimeFactory factory = approvingFactory();
            CopilotService service =
                    new CopilotService(null, factory).withHostCodeIntelligence(HOST);

            service.completeReviewPrompt(
                    "supervise", "", "high", false, null, false, true, 90_000, ignored -> {});

            assertThat(factory.lastClient.lastSessionRequest.attachesHostCodeIntelligence())
                    .isFalse();
            assertThat(factory.lastClient.lastSession.lastPrompt).isEqualTo("supervise");
        }

        @Test
        void readOnlyPassesWithMcpDisabledDoNotGetTheServer() throws Exception {
            FakeRuntimeFactory factory = approvingFactory();
            CopilotService service =
                    new CopilotService(null, factory).withHostCodeIntelligence(HOST);

            service.completeReviewPrompt(
                    "hygiene", "", "high", false, null, true, 90_000, ignored -> {});

            assertThat(factory.lastClient.lastSessionRequest.allowReadTools()).isTrue();
            assertThat(factory.lastClient.lastSessionRequest.hostCodeIntelligence()).isNull();
            assertThat(factory.lastClient.lastSessionRequest.allowsMcp()).isFalse();
            assertThat(factory.lastClient.lastSession.lastPrompt).isEqualTo("hygiene");
        }

        @Test
        void theCopilotExecutorForwardsEachPassesMcpPermission() throws Exception {
            FakeRuntimeFactory factory = approvingFactory();
            CopilotService service =
                    new CopilotService(null, factory).withHostCodeIntelligence(HOST);
            ReviewProviderExecutor.CopilotExecutor executor =
                    new ReviewProviderExecutor.CopilotExecutor(service, "", "high", false, null);

            executor.complete("rule", 90_000, true, false, ignored -> {});
            assertThat(factory.lastClient.lastSessionRequest.hostCodeIntelligence()).isNull();

            executor.complete("critique", 90_000, true, true, ignored -> {});
            assertThat(factory.lastClient.lastSessionRequest.hostCodeIntelligence())
                    .isEqualTo(HOST);
            assertThat(factory.lastClient.lastSessionRequest.inheritMcp()).isFalse();
        }

        @Test
        void withoutAnEndpointThePromptIsUnchanged() throws Exception {
            FakeRuntimeFactory factory = approvingFactory();
            CopilotService service =
                    new CopilotService(null, factory).withHostCodeIntelligence(null);

            service.chatWithPrompt("question", "medium", ignored -> {});

            assertThat(factory.lastClient.lastSessionRequest.hostCodeIntelligence()).isNull();
            assertThat(factory.lastClient.lastSession.lastPrompt).isEqualTo("question");
        }
    }
}
