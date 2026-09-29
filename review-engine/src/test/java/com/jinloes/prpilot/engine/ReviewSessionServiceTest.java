package com.jinloes.prpilot.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.review.CancellationToken;
import com.jinloes.prpilot.review.ClaudeService;
import com.jinloes.prpilot.review.CopilotService;
import com.jinloes.prpilot.review.GitWorktreeService;
import com.jinloes.prpilot.review.ReviewOutcomeLog;
import com.jinloes.prpilot.review.ReviewPipelineService;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReviewSessionServiceTest {

    @Nested
    class OperationRegistry {

        private final ReviewSessionService.OperationRegistry registry =
                new ReviewSessionService.OperationRegistry();

        @Test
        void cancelsOnlyTheOperationWithTheMatchingId() {
            java.util.concurrent.atomic.AtomicInteger firstCancelled =
                    new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger secondCancelled =
                    new java.util.concurrent.atomic.AtomicInteger();
            registry.start("first", firstCancelled::incrementAndGet);
            registry.start("second", secondCancelled::incrementAndGet);

            ReviewEngineApi.CancelResult result =
                    registry.cancel(new ReviewEngineApi.CancelParams("first"));

            assertThat(result.cancelled()).isTrue();
            assertThat(firstCancelled).hasValue(1);
            assertThat(secondCancelled).hasValue(0);
            assertThat(registry.cancel(new ReviewEngineApi.CancelParams("second")).cancelled())
                    .isTrue();
            assertThat(secondCancelled).hasValue(1);
        }

        @Test
        void ignoresUnknownOrMalformedOperationIds() {
            java.util.concurrent.atomic.AtomicInteger cancelled =
                    new java.util.concurrent.atomic.AtomicInteger();
            registry.start("active", cancelled::incrementAndGet);

            assertThat(registry.cancel(new ReviewEngineApi.CancelParams("unknown")).cancelled())
                    .isFalse();
            assertThat(registry.cancel(new ReviewEngineApi.CancelParams(" \n")).cancelled())
                    .isFalse();
            assertThat(cancelled).hasValue(0);
        }

        @Test
        void rejectsDuplicateOrInvalidActiveIds() {
            registry.start("active", () -> {});

            assertThatIllegalStateException().isThrownBy(() -> registry.start("active", () -> {}));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> registry.start("\u0000", () -> {}));
        }

        @Test
        void cancellationMarksTheOperationBeforeCallingItsProvider() {
            CancellationToken token = new CancellationToken();
            registry.start("active", token, () -> assertThat(token.isCancelled()).isTrue());

            assertThat(registry.cancel(new ReviewEngineApi.CancelParams("active")).cancelled())
                    .isTrue();
            assertThat(token.isCancelled()).isTrue();
        }
    }

    @Nested
    class RequestValidation {

        private final ReviewSessionService service = new ReviewSessionService();

        @Test
        void rejectsInvalidGenerationParamsBeforeStartingAProvider() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.generate(null, ignored -> {}, (kind, text) -> {}));
        }

        @Test
        void requiresExactlyOneChatPromptFormBeforeStartingAProvider() {
            ReviewEngineApi.ChatParams bothForms =
                    new ReviewEngineApi.ChatParams(
                            "chat-1",
                            "claude",
                            "",
                            "",
                            false,
                            "",
                            "",
                            List.of(),
                            "question",
                            "prompt");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.chat(bothForms, ignored -> {}));
        }
    }

    @Nested
    class ToReviewRequest {
        private final ObjectMapper json = new ObjectMapper();

        private Map<String, Object> params() {
            Map<String, Object> pr = new LinkedHashMap<>();
            pr.put("title", "T");
            pr.put("htmlUrl", "https://github.com/o/r/pull/1");
            pr.put("owner", "o");
            pr.put("repo", "r");
            pr.put("number", 1);
            pr.put("body", "");
            pr.put("author", "a");
            pr.put("createdAt", "2024-01-01");
            pr.put("isDraft", false);
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("operationId", "op-1");
            params.put("provider", "claude");
            params.put("projectDir", "");
            params.put("pr", pr);
            params.put("diff", "diff --git a/A b/A");
            params.put("repoGuidelines", "host guidance");
            return params;
        }

        private ReviewEngineApi.GenerateReviewParams decode(Map<String, Object> params) {
            return json.convertValue(params, ReviewEngineApi.GenerateReviewParams.class);
        }

        @Test
        void carriesTheBaseShaIntoTheReviewRequest() {
            Map<String, Object> params = params();
            params.put("baseSha", "b".repeat(40));
            params.put("secondReviewerModel", "gpt-5");

            ReviewEngineApi.GenerateReviewParams decoded = decode(params);
            PRReviewRequest request = ReviewSessionService.toReviewRequest(decoded);

            assertThat(decoded.secondReviewerModel()).isEqualTo("gpt-5");
            assertThat(request.getBaseSha()).isEqualTo("b".repeat(40));
            assertThat(request.getRepoGuidelines()).isEqualTo("host guidance");
            assertThat(request.getPr().getNumber()).isEqualTo(1);
        }

        @Test
        void olderHostsWithoutTheNewFieldsStillDeserialize() {
            ReviewEngineApi.GenerateReviewParams decoded = decode(params());

            assertThat(decoded.baseSha()).isNull();
            assertThat(decoded.secondReviewerModel()).isNull();
            assertThat(ReviewSessionService.toReviewRequest(decoded).getBaseSha()).isNullOrEmpty();
        }

        @Test
        void secondReviewerEffortOnlyFollowsACopilotPrimary() {
            Map<String, Object> copilot = params();
            copilot.put("provider", "copilot");
            copilot.put("effort", "low");
            Map<String, Object> claude = params();
            claude.put("effort", "max");

            assertThat(ReviewSessionService.secondReviewerEffort(decode(copilot))).isEqualTo("low");
            assertThat(ReviewSessionService.secondReviewerEffort(decode(claude)))
                    .isEqualTo(CopilotService.DEFAULT_REASONING_EFFORT);
        }

        @Test
        void aBlankSecondReviewerModelLeavesThePipelineUnchanged() {
            ReviewPipelineService pipeline =
                    ReviewPipelineService.forClaude(new ClaudeService(""), "");
            Map<String, Object> params = params();
            params.put("secondReviewerModel", "  ");

            assertThat(ReviewSessionService.withSecondReviewer(pipeline, decode(params)))
                    .isSameAs(pipeline);
        }
    }

    @Nested
    class RecordOutcome {

        private Path tmpDir;
        private Path logFile;
        private ReviewSessionService service;

        @BeforeEach
        void setUp() throws IOException {
            tmpDir = Files.createTempDirectory("session-outcome");
            logFile = tmpDir.resolve("review-outcomes.jsonl");
            service = new ReviewSessionService(new ReviewOutcomeLog(logFile));
        }

        @AfterEach
        void tearDown() throws IOException {
            FileUtils.deleteDirectory(tmpDir.toFile());
        }

        private static ReviewEngineApi.OutcomeCommentParam comment(String body) {
            return new ReviewEngineApi.OutcomeCommentParam(
                    "A.java", 10, "issue", body, "major", "high");
        }

        @Test
        void classifiesAndWritesOneRecordPerComment() throws IOException {
            ReviewEngineApi.RecordOutcomeResult result =
                    service.recordOutcome(
                            new ReviewEngineApi.RecordOutcomeParams(
                                    "claude",
                                    "sonnet",
                                    false,
                                    List.of(comment("kept"), comment("dropped")),
                                    List.of(comment("kept"))));

            assertThat(result.recorded()).isEqualTo(2);
            assertThat(Files.readAllLines(logFile, StandardCharsets.UTF_8)).hasSize(2);
        }

        /** The host cannot know which prompt this engine build ships, so the engine supplies it. */
        @Test
        void stampsTheEnginesOwnPromptVersionRatherThanTrustingTheCaller() throws IOException {
            service.recordOutcome(
                    new ReviewEngineApi.RecordOutcomeParams(
                            "copilot", "gpt-5", true, List.of(comment("x")), List.of()));

            String line = Files.readAllLines(logFile, StandardCharsets.UTF_8).get(0);
            assertThat(line)
                    .contains(
                            "\"promptVersion\":\""
                                    + ClaudeService.reviewPipelineVersion(true)
                                    + "\"")
                    .contains("\"provider\":\"copilot\"")
                    .contains("\"model\":\"gpt-5\"");
        }

        @Test
        void toleratesNullParamsAndNullCommentLists() {
            assertThat(service.recordOutcome(null).recorded()).isZero();
            assertThat(
                            service.recordOutcome(
                                            new ReviewEngineApi.RecordOutcomeParams(
                                                    "claude", "sonnet", false, null, null))
                                    .recorded())
                    .isZero();
        }

        @Test
        void carriesSeverityAndConfidenceThroughToTheRecord() throws IOException {
            service.recordOutcome(
                    new ReviewEngineApi.RecordOutcomeParams(
                            "claude", "sonnet", false, List.of(comment("x")), List.of()));

            String line = Files.readAllLines(logFile, StandardCharsets.UTF_8).get(0);
            assertThat(line)
                    .contains("\"severity\":\"major\"")
                    .contains("\"confidence\":\"high\"")
                    .contains("\"outcome\":\"deleted\"");
        }
    }

    @Nested
    class ReadGuidelines {

        private Path repoDir;
        private final ReviewSessionService service = new ReviewSessionService();

        @BeforeEach
        void setUp() throws IOException {
            repoDir = Files.createTempDirectory("session-guidelines");
        }

        @AfterEach
        void tearDown() {
            FileUtils.deleteQuietly(repoDir.toFile());
        }

        private void write(String relative, String content) throws IOException {
            Path file = repoDir.resolve(relative);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        }

        @Test
        void readsTheDefaultFilesWhenNoGlobsAreSupplied() throws IOException {
            write("AGENTS.md", "Always add tests.");

            String guidelines =
                    service.readGuidelines(
                                    new ReviewEngineApi.ReadGuidelinesParams(
                                            repoDir.toString(), List.of()))
                            .guidelines();

            // An empty glob list must mean "engine defaults", not "match nothing" — that fallback
            // is what lets a host avoid carrying its own copy of the default file list.
            assertThat(guidelines).contains("## AGENTS.md").contains("Always add tests.");
        }

        @Test
        void treatsNullGlobsTheSameAsAnEmptyList() throws IOException {
            write("CONTRIBUTING.md", "Squash your commits.");

            String guidelines =
                    service.readGuidelines(
                                    new ReviewEngineApi.ReadGuidelinesParams(
                                            repoDir.toString(), null))
                            .guidelines();

            assertThat(guidelines).contains("Squash your commits.");
        }

        @Test
        void addsExplicitGlobsToTheDefaults() throws IOException {
            write("AGENTS.md", "default file");
            write("docs/style.md", "custom file");

            String guidelines =
                    service.readGuidelines(
                                    new ReviewEngineApi.ReadGuidelinesParams(
                                            repoDir.toString(), List.of("**/style.md")))
                            .guidelines();

            assertThat(guidelines).contains("custom file").contains("default file");
            assertThat(guidelines.indexOf("custom file"))
                    .isLessThan(guidelines.indexOf("default file"));
        }

        @Test
        void returnsEmptyForABlankOrMissingDirectoryRatherThanThrowing() {
            assertThat(service.readGuidelines(null).guidelines()).isEmpty();
            assertThat(
                            service.readGuidelines(
                                            new ReviewEngineApi.ReadGuidelinesParams("", List.of()))
                                    .guidelines())
                    .isEmpty();
            assertThat(
                            service.readGuidelines(
                                            new ReviewEngineApi.ReadGuidelinesParams(
                                                    repoDir.resolve("nope").toString(), List.of()))
                                    .guidelines())
                    .isEmpty();
        }

        @Test
        void returnsEmptyWhenNothingMatches() {
            assertThat(
                            service.readGuidelines(
                                            new ReviewEngineApi.ReadGuidelinesParams(
                                                    repoDir.toString(), List.of()))
                                    .guidelines())
                    .isEmpty();
        }
    }

    @Nested
    class RemoveWorktree {

        @Test
        void propagatesCleanupFailureAsADomainResult() {
            GitWorktreeService failingService =
                    new GitWorktreeService() {
                        @Override
                        public boolean removeWorktree(File repoDir, File worktreeDir) {
                            return false;
                        }
                    };
            ReviewSessionService service =
                    new ReviewSessionService(new ReviewOutcomeLog(), failingService);

            ReviewEngineApi.WorktreeRemovalResult result =
                    service.removeWorktree(
                            new ReviewEngineApi.RemoveWorktreeParams("/repo", "/worktree"));

            assertThat(result.removed()).isFalse();
        }

        @Test
        void rejectsMalformedCleanupRequestsWithoutCallingGit() {
            ReviewSessionService service = new ReviewSessionService();

            assertThat(service.removeWorktree(null).removed()).isFalse();
            assertThat(
                            service.removeWorktree(
                                            new ReviewEngineApi.RemoveWorktreeParams(
                                                    "", "/worktree"))
                                    .removed())
                    .isFalse();
        }
    }
}
