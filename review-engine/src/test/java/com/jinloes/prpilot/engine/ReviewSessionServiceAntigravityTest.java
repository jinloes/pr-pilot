package com.jinloes.prpilot.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.review.AntigravityModelDiscovery;
import com.jinloes.prpilot.review.AntigravityService;
import com.jinloes.prpilot.review.ClaudeService;
import com.jinloes.prpilot.review.CopilotService;
import com.jinloes.prpilot.review.GitWorktreeService;
import com.jinloes.prpilot.review.ProviderSetupProbe;
import com.jinloes.prpilot.review.ReviewOutcomeLog;
import com.jinloes.prpilot.review.ReviewPipelineService;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewSessionServiceAntigravityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Nested
    class ProbeProviderSetup {

        @Test
        void dispatchesAntigravityProviderCaseInsensitively() {
            ReviewSessionService service =
                    new ReviewSessionService(
                            new ReviewOutcomeLog(),
                            new GitWorktreeService(),
                            new ProviderSetupProbe());

            ProviderSetupProbe.Result result =
                    service.probeProviderSetup(
                            new ReviewEngineApi.ProbeProviderSetupParams(" Antigravity ", true));

            assertThat(result.authCommand()).isEqualTo("agy");
            assertThat(result.authenticationStatus()).isIn("unverified", "unavailable");
        }
    }

    @Nested
    class ListAntigravityModels {

        @Test
        void servesCachedModelsUnlessForceRefreshOrEmptyCache() {
            var cached =
                    new AntigravityModelDiscovery.Result(
                            List.of("gemini-3.8-flash-high", "claude-sonnet-4-6"),
                            AntigravityModelDiscovery.Source.ACCOUNT,
                            "");
            var refreshCalls = new AtomicInteger();

            ReviewEngineApi.AntigravityModelsResult fromCache =
                    ReviewSessionService.listAntigravityModels(
                            new ReviewEngineApi.ListAntigravityModelsParams(false),
                            () -> cached,
                            () -> {
                                refreshCalls.incrementAndGet();
                                return AntigravityModelDiscovery.Result.none("unexpected");
                            });

            assertThat(fromCache.models())
                    .containsExactly("gemini-3.8-flash-high", "claude-sonnet-4-6");
            assertThat(fromCache.source()).isEqualTo("ACCOUNT");
            assertThat(refreshCalls.get()).isZero();

            ReviewEngineApi.AntigravityModelsResult forced =
                    ReviewSessionService.listAntigravityModels(
                            new ReviewEngineApi.ListAntigravityModelsParams(true),
                            () -> cached,
                            () -> {
                                refreshCalls.incrementAndGet();
                                return new AntigravityModelDiscovery.Result(
                                        List.of("claude-opus-4-6-thinking"),
                                        AntigravityModelDiscovery.Source.ACCOUNT,
                                        "");
                            });

            assertThat(forced.models()).containsExactly("claude-opus-4-6-thinking");
            assertThat(refreshCalls.get()).isEqualTo(1);
        }
    }

    @Nested
    class SecondReviewerAndChatParams {

        @Test
        void antigravityPrimaryDoesNotLeakEffortIntoCopilotSecondReviewer() {
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("operationId", "op-1");
            raw.put("provider", "antigravity");
            raw.put("effort", "low");
            raw.put("diff", "");
            raw.put(
                    "pr",
                    Map.of(
                            "title", "T",
                            "htmlUrl", "https://github.com/acme/widgets/pull/1",
                            "owner", "acme",
                            "repo", "widgets",
                            "number", 1,
                            "body", "",
                            "author", "alice",
                            "createdAt", "2026-01-01T00:00:00Z",
                            "isDraft", false));

            ReviewEngineApi.GenerateReviewParams decoded =
                    MAPPER.convertValue(raw, ReviewEngineApi.GenerateReviewParams.class);
            assertThat(ReviewSessionService.secondReviewerEffort(decoded))
                    .isEqualTo(CopilotService.DEFAULT_REASONING_EFFORT);
        }

        @Test
        void withSecondReviewerAttachesProviderMatchingExecutorOnlyWhenConfigured(
                @TempDir Path worktree) {
            Map<String, Object> basePr =
                    Map.of(
                            "title", "T",
                            "htmlUrl", "https://github.com/acme/widgets/pull/1",
                            "owner", "acme",
                            "repo", "widgets",
                            "number", 1,
                            "body", "",
                            "author", "alice",
                            "createdAt", "2026-01-01T00:00:00Z",
                            "isDraft", false);
            ReviewPipelineService claudeBase =
                    ReviewPipelineService.forClaude(
                            new ClaudeService(worktree.toString()), "claude-opus-4-7");
            ReviewEngineApi.GenerateReviewParams claudeParams =
                    MAPPER.convertValue(
                            Map.of(
                                    "operationId", "op-claude",
                                    "provider", "claude",
                                    "diff", "",
                                    "secondReviewerModel", "claude-haiku-4-5-20251001",
                                    "secondReviewerEffort", "low",
                                    "configDir", "/copilot-only",
                                    "pr", basePr),
                            ReviewEngineApi.GenerateReviewParams.class);
            assertThat(ReviewSessionService.withSecondReviewer(claudeBase, claudeParams))
                    .isNotSameAs(claudeBase);

            ReviewPipelineService agyBase =
                    ReviewPipelineService.forAntigravity(
                            new AntigravityService(worktree.toString()), "gemini-3.8-flash-high");
            ReviewEngineApi.GenerateReviewParams disabledParams =
                    MAPPER.convertValue(
                            Map.of(
                                    "operationId", "op-agy",
                                    "provider", "antigravity",
                                    "diff", "",
                                    "secondReviewerModel", "  ",
                                    "pr", basePr),
                            ReviewEngineApi.GenerateReviewParams.class);
            assertThat(ReviewSessionService.withSecondReviewer(agyBase, disabledParams))
                    .isSameAs(agyBase);
        }

        @Test
        void chatParamsSupportsOptionalModelAndBackwardsCompatibleConstructors() {
            ReviewEngineApi.ChatParams legacy =
                    new ReviewEngineApi.ChatParams(
                            "op-1", "antigravity", "/repo", "", false, "", null, List.of(), "Hi");
            assertThat(legacy.model()).isNull();

            ReviewEngineApi.ChatParams decoded =
                    MAPPER.convertValue(
                            Map.of(
                                    "operationId", "op-2",
                                    "provider", "antigravity",
                                    "userMessage", "Explain hunk",
                                    "model", "gemini-3.8-flash-high"),
                            ReviewEngineApi.ChatParams.class);
            assertThat(decoded.model()).isEqualTo("gemini-3.8-flash-high");
        }
    }
}
