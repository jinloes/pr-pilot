package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Provider-selection tests for {@link ReviewPipelineService#withSecondReviewer}. */
class ReviewPipelineSecondReviewerProviderTest {

    @Nested
    class WithSecondReviewer {

        @Test
        void claudePrimarySpawnsDedicatedClaudeSecondReviewerIgnoringCopilotOptions(
                @TempDir Path worktree) {
            ClaudeService primaryService = new ClaudeService(worktree.toString());
            ReviewPipelineService base =
                    ReviewPipelineService.forClaude(primaryService, "claude-opus-4-7");

            ReviewPipelineService configured =
                    base.withSecondReviewer(
                            "  claude-haiku-4-5-20251001  ", "low", "/ignored-copilot-dir");

            assertThat(configured.secondaryReviewer())
                    .isInstanceOfSatisfying(
                            ReviewProviderExecutor.ClaudeExecutor.class,
                            exec -> {
                                assertThat(exec.model()).isEqualTo("claude-haiku-4-5-20251001");
                                assertThat(exec.service()).isNotSameAs(primaryService);
                                assertThat(exec.projectDir()).isEqualTo(worktree.toFile());
                            });
        }

        @Test
        void antigravityPrimarySpawnsDedicatedAntigravitySecondReviewer(@TempDir Path worktree) {
            AntigravityService primaryService = new AntigravityService(worktree.toString());
            ReviewPipelineService base =
                    ReviewPipelineService.forAntigravity(primaryService, "gemini-3.8-flash-high");

            ReviewPipelineService configured =
                    base.withSecondReviewer("  claude-sonnet-4-6  ", "low", "/ignored-copilot-dir");

            assertThat(configured.secondaryReviewer())
                    .isInstanceOfSatisfying(
                            ReviewProviderExecutor.AntigravityExecutor.class,
                            exec -> {
                                assertThat(exec.model()).isEqualTo("claude-sonnet-4-6");
                                assertThat(exec.service()).isNotSameAs(primaryService);
                                assertThat(exec.projectDir()).isEqualTo(worktree.toFile());
                            });
        }

        @Test
        void copilotPrimarySpawnsDedicatedCopilotSecondReviewerWithEffortAndConfigDir(
                @TempDir Path worktree) {
            CopilotService primaryService = new CopilotService(worktree.toString());
            ReviewPipelineService base =
                    ReviewPipelineService.forCopilot(
                            primaryService, "claude-sonnet-4.6", "xhigh", true, "/trusted");

            ReviewPipelineService configured =
                    base.withSecondReviewer("  gpt-5.4  ", "low", "/trusted");

            assertThat(configured.secondaryReviewer())
                    .isInstanceOfSatisfying(
                            ReviewProviderExecutor.CopilotExecutor.class,
                            exec -> {
                                assertThat(exec.model()).isEqualTo("gpt-5.4");
                                assertThat(exec.effort()).isEqualTo("low");
                                assertThat(exec.inheritMcp()).isFalse();
                                assertThat(exec.configDir()).isEqualTo("/trusted");
                                assertThat(exec.service()).isNotSameAs(primaryService);
                                assertThat(exec.projectDir()).isEqualTo(worktree.toFile());
                            });
        }

        @Test
        void blankOrNullSecondReviewerModelLeavesPipelineUnchanged(@TempDir Path worktree) {
            ClaudeService primaryService = new ClaudeService(worktree.toString());
            ReviewPipelineService base =
                    ReviewPipelineService.forClaude(primaryService, "claude-sonnet-4-6");

            assertThat(base.withSecondReviewer(null, "low", "")).isSameAs(base);
            assertThat(base.withSecondReviewer("   ", "low", "")).isSameAs(base);
            assertThat(base.secondaryReviewer()).isNull();
        }
    }
}
