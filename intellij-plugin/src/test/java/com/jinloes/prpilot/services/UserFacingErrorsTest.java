package com.jinloes.prpilot.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.ReviewProvider;
import com.jinloes.prpilot.sidecar.pr.PrDiffResult;
import java.io.IOException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class UserFacingErrorsTest {

    @Nested
    class ForGitHub {

        @Test
        void authErrorsMapToGhLoginGuidance() {
            String msg =
                    UserFacingErrors.forGitHub(
                            new IOException("401 Unauthorized: bad credentials"), "save draft");
            assertThat(msg).contains("gh auth login");
        }

        @Test
        void timeoutErrorsMapToRetryMessage() {
            String msg =
                    UserFacingErrors.forGitHub(
                            new IOException("request timed out after 30s"), "submit review");
            assertThat(msg).contains("timed out").contains("Retry");
        }

        @Test
        void ambiguousNotFoundStatusPreservesBothPossibleCauses() {
            String msg =
                    UserFacingErrors.forGitHub(
                            new IntellijGitHubService.GitHubOperationException(
                                    PrDiffResult.STATUS_NOT_FOUND_OR_INACCESSIBLE,
                                    "Pull request not found or inaccessible."),
                            "load the PR diff");

            assertThat(msg)
                    .contains(
                            "may not exist",
                            "may not have access",
                            "Verify the PR URL",
                            "gh auth status");
        }

        @Test
        void ambiguousNotFoundCopyIsNotSelectedFromUntypedProse() {
            String msg =
                    UserFacingErrors.forGitHub(
                            new IOException(
                                    "Pull request not found or inaccessible to the active gh account."),
                            "load the PR diff");

            assertThat(msg).isEqualTo("Couldn't load the PR diff. Please retry.");
        }

        @Test
        void diffTooLargeStatusUsesTheSizeLimitCopyInsteadOfRetry() {
            String msg =
                    UserFacingErrors.forGitHub(
                            new IntellijGitHubService.GitHubOperationException(
                                    PrDiffResult.STATUS_DIFF_TOO_LARGE,
                                    "GitHub declined to return this pull request's diff (HTTP"
                                            + " 406); it likely exceeds GitHub's diff size limits."),
                            "load the PR diff");

            assertThat(msg)
                    .isEqualTo(
                            "Pull request diff is too large. GitHub will not return this pull"
                                    + " request's diff because it exceeds GitHub's size limits."
                                    + " Review smaller pull requests or split this one.");
            assertThat(msg).doesNotContainIgnoringCase("retry");
        }

        @Test
        void apiFailedStatusStillUsesTheGenericRetryCopy() {
            String msg =
                    UserFacingErrors.forGitHub(
                            new IntellijGitHubService.GitHubOperationException(
                                    "api_failed", "GitHub API request failed."),
                            "load the PR diff");

            assertThat(msg).isEqualTo("Couldn't load the PR diff. Please retry.");
        }
    }

    @Nested
    class ForProvider {

        @Test
        void missingBinaryMentionsInstallAction() {
            String msg =
                    UserFacingErrors.forProvider(
                            ReviewProvider.COPILOT,
                            new IOException("Cannot run program \"copilot\": error=2"),
                            "generate a review");
            assertThat(msg).contains("copilot").contains("Install");
        }

        @Test
        void parseErrorsMapToStructuredOutputGuidance() {
            String msg =
                    UserFacingErrors.forProvider(
                            ReviewProvider.CLAUDE,
                            new IOException("Failed to parse review JSON: unexpected token"),
                            "generate a review");
            assertThat(msg).contains("invalid review format").contains("Retry");
        }
    }

    @Nested
    class ForProviderNotInstalled {

        @Test
        void copilotGuidanceNamesTheCopilotCli() {
            String msg = UserFacingErrors.forProviderNotInstalled(ReviewProvider.COPILOT);
            assertThat(msg)
                    .contains("copilot")
                    .contains("GitHub Copilot CLI")
                    .contains("try again");
        }

        @Test
        void claudeGuidanceNamesTheClaudeCli() {
            String msg = UserFacingErrors.forProviderNotInstalled(ReviewProvider.CLAUDE);
            assertThat(msg).contains("claude").contains("Claude Code CLI").contains("try again");
        }
    }
}
