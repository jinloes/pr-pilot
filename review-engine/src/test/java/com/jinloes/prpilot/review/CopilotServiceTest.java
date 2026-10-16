package com.jinloes.prpilot.review;

import static com.jinloes.prpilot.review.CopilotServiceTestSupport.factoryFor;
import static com.jinloes.prpilot.review.CopilotServiceTestSupport.fakeRequest;
import static com.jinloes.prpilot.review.CopilotServiceTestSupport.reviewJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jinloes.prpilot.model.ReviewResult;
import com.jinloes.prpilot.review.CopilotServiceTestSupport.FakeRuntimeClient;
import com.jinloes.prpilot.review.CopilotServiceTestSupport.FakeRuntimeFactory;
import com.jinloes.prpilot.review.CopilotServiceTestSupport.FakeRuntimeSession;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Java port of the former core/jvmTest Kotest suite for CopilotService. */
class CopilotServiceTest {

    @Nested
    class NormalizeReasoningEffort {

        @Test
        void blankOrUnknownDefaultsToHigh() {
            assertThat(CopilotService.normalizeReasoningEffort("")).isEqualTo("high");
            assertThat(CopilotService.normalizeReasoningEffort("  ")).isEqualTo("high");
            assertThat(CopilotService.normalizeReasoningEffort(null)).isEqualTo("high");
            assertThat(CopilotService.normalizeReasoningEffort("turbo")).isEqualTo("high");
        }

        @Test
        void supportedSdkValuesPassThroughLowercased() {
            assertThat(CopilotService.normalizeReasoningEffort("LOW")).isEqualTo("low");
            assertThat(CopilotService.normalizeReasoningEffort("medium")).isEqualTo("medium");
            assertThat(CopilotService.normalizeReasoningEffort("High")).isEqualTo("high");
            assertThat(CopilotService.normalizeReasoningEffort("xhigh")).isEqualTo("xhigh");
        }

        @Test
        void legacyCliOnlyValuesMapToNearestSdkValue() {
            assertThat(CopilotService.normalizeReasoningEffort("none")).isEqualTo("low");
            assertThat(CopilotService.normalizeReasoningEffort("max")).isEqualTo("xhigh");
        }
    }

    @Nested
    class ReviewPr {

        @Test
        void buildsClientAndSessionRequestsFromSettingsAndProjectDir() throws Exception {
            FakeRuntimeFactory factory =
                    factoryFor(
                            () -> {
                                FakeRuntimeSession s = new FakeRuntimeSession();
                                s.sendAction =
                                        session -> session.emitMessage(reviewJson("APPROVE"));
                                return s;
                            });
            CopilotService svc = new CopilotService("/tmp/pr-pilot-repo", factory);

            svc.reviewPR(fakeRequest(), "claude-sonnet-4.6", "high", ignored -> {});

            CopilotSdkRuntime.ClientRequest clientRequest = factory.lastClientRequest;
            assertThat(clientRequest).isNotNull();
            assertThat(clientRequest.cliPath()).isEqualTo(CopilotService.findCopilotBinary());
            assertThat(clientRequest.workingDir()).isEqualTo(new File("/tmp/pr-pilot-repo"));
            assertThat(clientRequest.environment().get("HOME"))
                    .isEqualTo(System.getProperty("user.home", "/"));
            assertThat(clientRequest.environment().get("PATH"))
                    .isEqualTo(
                            BinaryLocator.providerPath(
                                    System.getProperty("user.home", "/"),
                                    System.getenv().getOrDefault("PATH", "")));

            FakeRuntimeClient client = factory.lastClient;
            assertThat(client.started).isTrue();
            assertThat(client.closed).isTrue();
            CopilotSdkRuntime.SessionRequest sessionRequest = client.lastSessionRequest;
            assertThat(sessionRequest).isNotNull();
            assertThat(sessionRequest.model()).isEqualTo("claude-sonnet-4.6");
            assertThat(sessionRequest.effort()).isEqualTo("high");
            assertThat(sessionRequest.workingDir()).isEqualTo(new File("/tmp/pr-pilot-repo"));
            assertThat(sessionRequest.inheritMcp()).isFalse();
            assertThat(sessionRequest.configDir()).isNull();
            assertThat(sessionRequest.allowReadTools()).isTrue();

            FakeRuntimeSession session = client.lastSession;
            assertThat(session).isNotNull();
            assertThat(session.lastPrompt).contains("<pr_diff>");
            assertThat(session.lastTimeoutMs).isEqualTo(30L * 60L * 1000L);
            assertThat(session.closeCount.get()).isEqualTo(1);
        }

        @Test
        void toolFreeCompletionUsesItsBoundedTimeout() throws Exception {
            FakeRuntimeFactory factory =
                    factoryFor(
                            () -> {
                                FakeRuntimeSession session = new FakeRuntimeSession();
                                session.sendAction = current -> current.emitMessage("{}");
                                return session;
                            });
            CopilotService service = new CopilotService(null, factory);

            service.completeReviewPrompt(
                    "supervise", "", "high", false, null, false, 90_000, ignored -> {});

            assertThat(factory.lastClient.lastSessionRequest.allowReadTools()).isFalse();
            assertThat(factory.lastClient.lastSession.lastTimeoutMs).isEqualTo(90_000);
        }

        @Test
        void threadsMcpInheritanceFlagAndConfigDirOverrideIntoTheSessionRequest() throws Exception {
            FakeRuntimeFactory factory =
                    factoryFor(
                            () -> {
                                FakeRuntimeSession s = new FakeRuntimeSession();
                                s.sendAction =
                                        session -> session.emitMessage(reviewJson("APPROVE"));
                                return s;
                            });
            CopilotService svc = new CopilotService(null, factory);

            svc.reviewPR(
                    fakeRequest(),
                    "",
                    "medium",
                    ignored -> {},
                    null,
                    false,
                    "  /custom/.copilot  ");

            CopilotSdkRuntime.SessionRequest sessionRequest = factory.lastClient.lastSessionRequest;
            assertThat(sessionRequest.inheritMcp()).isFalse();
            assertThat(sessionRequest.configDir()).isEqualTo("/custom/.copilot");
        }

        @Test
        void blankConfigDirOverrideResolvesToNull() throws Exception {
            FakeRuntimeFactory factory =
                    factoryFor(
                            () -> {
                                FakeRuntimeSession s = new FakeRuntimeSession();
                                s.sendAction =
                                        session -> session.emitMessage(reviewJson("APPROVE"));
                                return s;
                            });
            CopilotService svc = new CopilotService(null, factory);

            svc.reviewPR(fakeRequest(), "", "medium", ignored -> {}, null, false, "   ");

            assertThat(factory.lastClient.lastSessionRequest.configDir()).isNull();
        }

        @Test
        void selfCritiqueRunsSecondPassEmbeddingTheFirstReview() throws Exception {
            FakeRuntimeFactory factory =
                    factoryFor(
                            () -> {
                                FakeRuntimeSession s = new FakeRuntimeSession();
                                s.sendAction =
                                        session -> session.emitMessage(reviewJson("APPROVE"));
                                return s;
                            });
            CopilotService svc = new CopilotService(null, factory);

            svc.reviewPR(fakeRequest(), "", "high", ignored -> {}, null, false, null, true);

            // The second (critique) pass is the last client/session; its prompt embeds the draft.
            assertThat(factory.lastClient.lastSession.lastPrompt).contains("<draft_review>");
        }

        @Test
        void selfCritiqueDisabledRunsSinglePassWithoutDraftReview() throws Exception {
            FakeRuntimeFactory factory =
                    factoryFor(
                            () -> {
                                FakeRuntimeSession s = new FakeRuntimeSession();
                                s.sendAction =
                                        session -> session.emitMessage(reviewJson("APPROVE"));
                                return s;
                            });
            CopilotService svc = new CopilotService(null, factory);

            svc.reviewPR(fakeRequest(), "", "high", ignored -> {}, null, false, null, false);

            assertThat(factory.lastClient.lastSession.lastPrompt).doesNotContain("<draft_review>");
        }

        @Test
        void streamsTextDeltasAsChunksToolNamesAsStatusesAndParsesFinalAssistantMessage()
                throws Exception {
            FakeRuntimeFactory factory =
                    factoryFor(
                            () -> {
                                FakeRuntimeSession s = new FakeRuntimeSession();
                                s.sendAction =
                                        session -> {
                                            session.emitDelta("{\"summary\":\"draft\"");
                                            session.emitTool("view");
                                            session.emitMessage(reviewJson("APPROVE"));
                                        };
                                return s;
                            });
            CopilotService svc = new CopilotService(null, factory);
            List<String> statuses = new ArrayList<>();
            List<String[]> chunks = new ArrayList<>();

            ReviewResult result =
                    svc.reviewPR(
                            fakeRequest(),
                            "",
                            "medium",
                            statuses::add,
                            (kind, chunk) -> chunks.add(new String[] {kind, chunk}),
                            false,
                            null);

            assertThat(result.getVerdict()).isEqualTo("APPROVE");
            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0)).containsExactly("text", "{\"summary\":\"draft\"");
            assertThat(statuses).containsExactly("Generating review…", "view", "Parsing review…");
        }

        @Test
        void fallsBackToAccumulatedDeltasWhenNoFinalAssistantMessageArrives() throws Exception {
            FakeRuntimeFactory factory =
                    factoryFor(
                            () -> {
                                FakeRuntimeSession s = new FakeRuntimeSession();
                                s.sendAction =
                                        session -> {
                                            session.emitDelta("{\"summary\":\"s\",");
                                            session.emitDelta("\"verdict\":\"COMMENT\",");
                                            session.emitDelta("\"lineComments\":[]}");
                                        };
                                return s;
                            });
            CopilotService svc = new CopilotService(null, factory);
            List<String> chunks = new ArrayList<>();

            ReviewResult result =
                    svc.reviewPR(
                            fakeRequest(),
                            "",
                            "medium",
                            ignored -> {},
                            (kind, chunk) -> chunks.add(chunk),
                            false,
                            null);

            assertThat(result.getVerdict()).isEqualTo("COMMENT");
            assertThat(chunks)
                    .containsExactly(
                            "{\"summary\":\"s\",",
                            "\"verdict\":\"COMMENT\",",
                            "\"lineComments\":[]}");
        }

        @Test
        void surfacesSessionErrorWhenTheRuntimeEmitsNoOutput() {
            FakeRuntimeFactory factory =
                    factoryFor(
                            () -> {
                                FakeRuntimeSession s = new FakeRuntimeSession();
                                s.sendAction =
                                        session ->
                                                session.emitError(
                                                        "Copilot account is not authorized for this model");
                                return s;
                            });
            CopilotService svc = new CopilotService(null, factory);

            assertThatThrownBy(() -> svc.reviewPR(fakeRequest(), "", "medium", ignored -> {}))
                    .isInstanceOf(IOException.class)
                    .hasMessage("Copilot account is not authorized for this model");
        }

        @Test
        void emptyOutputWithNoSessionErrorProducedNoOutputMessage() {
            FakeRuntimeFactory factory = new FakeRuntimeFactory();
            CopilotService svc = new CopilotService(null, factory);

            assertThatThrownBy(() -> svc.reviewPR(fakeRequest(), "", "medium", ignored -> {}))
                    .isInstanceOf(IOException.class)
                    .hasMessage("copilot produced no output.");
        }

        @Test
        void runtimeFailureWrappedInExecutionExceptionUnwrapsCauseMessage() {
            FakeRuntimeFactory factory =
                    factoryFor(
                            () -> {
                                FakeRuntimeSession s = new FakeRuntimeSession();
                                s.sendFailure =
                                        new ExecutionException(
                                                new IOException("policy denied tool access"));
                                return s;
                            });
            CopilotService svc = new CopilotService(null, factory);

            assertThatThrownBy(() -> svc.reviewPR(fakeRequest(), "", "medium", ignored -> {}))
                    .isInstanceOf(IOException.class)
                    .hasMessage("policy denied tool access");
        }

        @Test
        void runtimeFailureClosesAllSdkEventSubscriptions() {
            AtomicReference<FakeRuntimeSession> sessionRef = new AtomicReference<>();
            FakeRuntimeFactory factory =
                    factoryFor(
                            () -> {
                                FakeRuntimeSession s = new FakeRuntimeSession();
                                sessionRef.set(s);
                                s.sendFailure = new IOException("send failed");
                                return s;
                            });
            CopilotService svc = new CopilotService(null, factory);

            assertThatThrownBy(() -> svc.reviewPR(fakeRequest(), "", "medium", ignored -> {}))
                    .isInstanceOf(IOException.class);

            assertThat(sessionRef.get().listenerCount()).isEqualTo(0);
        }

        @Test
        void nonJsonOutputParseError() {
            String sensitiveOutput = "not even close to JSON";
            FakeRuntimeFactory factory =
                    factoryFor(
                            () -> {
                                FakeRuntimeSession s = new FakeRuntimeSession();
                                s.sendAction = session -> session.emitMessage(sensitiveOutput);
                                return s;
                            });
            CopilotService svc = new CopilotService(null, factory);

            assertThatThrownBy(() -> svc.reviewPR(fakeRequest(), "", "medium", ignored -> {}))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Failed to parse review JSON")
                    .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain(sensitiveOutput));
        }

        @Test
        void legacyEffortValuesNormalizeBeforeCreatingTheSession() throws Exception {
            FakeRuntimeFactory lowFactory =
                    factoryFor(
                            () -> {
                                FakeRuntimeSession s = new FakeRuntimeSession();
                                s.sendAction =
                                        session -> session.emitMessage(reviewJson("APPROVE"));
                                return s;
                            });
            FakeRuntimeFactory xhighFactory =
                    factoryFor(
                            () -> {
                                FakeRuntimeSession s = new FakeRuntimeSession();
                                s.sendAction =
                                        session -> session.emitMessage(reviewJson("APPROVE"));
                                return s;
                            });

            new CopilotService(null, lowFactory).reviewPR(fakeRequest(), "", "none", ignored -> {});
            new CopilotService(null, xhighFactory)
                    .reviewPR(fakeRequest(), "", "max", ignored -> {});

            assertThat(lowFactory.lastClient.lastSessionRequest.effort()).isEqualTo("low");
            assertThat(xhighFactory.lastClient.lastSessionRequest.effort()).isEqualTo("xhigh");
        }
    }

    @Nested
    class Chat {

        @Test
        void streamsDeltaChunksAndReturnsTheFinalAssistantMessage() throws Exception {
            FakeRuntimeFactory factory =
                    factoryFor(
                            () -> {
                                FakeRuntimeSession s = new FakeRuntimeSession();
                                s.sendAction =
                                        session -> {
                                            session.emitDelta("Hello ");
                                            session.emitDelta("there");
                                            session.emitMessage("Hello there.");
                                        };
                                return s;
                            });
            CopilotService svc = new CopilotService(null, factory);
            StringBuilder collected = new StringBuilder();
            String result = svc.chat("", List.of(), "hi", "medium", collected::append);
            assertThat(result).isEqualTo("Hello there.");
            assertThat(collected.toString()).isEqualTo("Hello there");
        }

        @Test
        void fallsBackToDeltaBufferWhenTheSdkNeverEmitsAFinalMessage() throws Exception {
            FakeRuntimeFactory factory =
                    factoryFor(
                            () -> {
                                FakeRuntimeSession s = new FakeRuntimeSession();
                                s.sendAction =
                                        session -> {
                                            session.emitDelta("Hello ");
                                            session.emitDelta("again");
                                        };
                                return s;
                            });
            CopilotService svc = new CopilotService(null, factory);
            StringBuilder collected = new StringBuilder();

            String result = svc.chat("", List.of(), "q", "medium", collected::append);

            assertThat(result).isEqualTo("Hello again");
            assertThat(collected.toString()).isEqualTo("Hello again");
        }

        @Test
        void runtimeFailureSurfacesTheUnderlyingMessage() {
            FakeRuntimeFactory factory =
                    factoryFor(
                            () -> {
                                FakeRuntimeSession s = new FakeRuntimeSession();
                                s.sendFailure = new IOException("tool sandbox startup failed");
                                return s;
                            });
            CopilotService svc = new CopilotService(null, factory);

            assertThatThrownBy(() -> svc.chat("", List.of(), "q", "medium", ignored -> {}))
                    .isInstanceOf(IOException.class)
                    .hasMessage("tool sandbox startup failed");
        }
    }

    @Nested
    class FindCopilotBinary {

        @Test
        void returnsANonBlankPath() {
            assertThat(CopilotService.findCopilotBinary()).isNotBlank();
        }
    }

    @Nested
    class DefaultReasoningEffort {

        @Test
        void isHighForDeeperReviewReasoning() {
            assertThat(CopilotService.DEFAULT_REASONING_EFFORT).isEqualTo("high");
        }
    }
}
