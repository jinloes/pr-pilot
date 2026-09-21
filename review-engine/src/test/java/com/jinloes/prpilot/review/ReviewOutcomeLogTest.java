package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.model.LineComment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReviewOutcomeLogTest {

    private static final ReviewOutcomeLog.Metadata META =
            new ReviewOutcomeLog.Metadata("v1", "claude", "sonnet");

    private static LineComment comment(String file, int line, String body) {
        return new LineComment(file, line, "issue", body);
    }

    private static List<String> outcomes(List<ReviewOutcomeLog.OutcomeRecord> records) {
        return records.stream().map(ReviewOutcomeLog.OutcomeRecord::outcome).toList();
    }

    @Nested
    class Classify {

        private final ReviewOutcomeLog log = new ReviewOutcomeLog(Path.of("unused"));

        @Test
        void reportsKeptWhenTheCommentSurvivedUnchanged() {
            List<LineComment> generated = List.of(comment("A.java", 10, "bug here"));
            List<LineComment> submitted = List.of(comment("A.java", 10, "bug here"));

            assertThat(outcomes(log.classify(generated, submitted, META))).containsExactly("kept");
        }

        @Test
        void reportsEditedWhenTheBodyChangedAtTheSameLocation() {
            List<LineComment> generated = List.of(comment("A.java", 10, "bug here"));
            List<LineComment> submitted = List.of(comment("A.java", 10, "actually a nit"));

            assertThat(outcomes(log.classify(generated, submitted, META)))
                    .containsExactly("edited");
        }

        @Test
        void reportsDeletedWhenTheCommentWasNotSubmitted() {
            List<LineComment> generated = List.of(comment("A.java", 10, "bug here"));

            assertThat(outcomes(log.classify(generated, List.of(), META)))
                    .containsExactly("deleted");
        }

        @Test
        void reportsAddedForACommentTheReviewerWrote() {
            List<LineComment> submitted = List.of(comment("B.java", 3, "human wrote this"));

            assertThat(outcomes(log.classify(List.of(), submitted, META))).containsExactly("added");
        }

        /** A comment moved to another line is a delete plus an add, not an edit. */
        @Test
        void treatsADifferentLineAsADeleteAndAnAdd() {
            List<LineComment> generated = List.of(comment("A.java", 10, "bug here"));
            List<LineComment> submitted = List.of(comment("A.java", 42, "bug here"));

            assertThat(outcomes(log.classify(generated, submitted, META)))
                    .containsExactlyInAnyOrder("deleted", "added");
        }

        @Test
        void ignoresWhitespaceReflowWhenDecidingIfABodyChanged() {
            List<LineComment> generated = List.of(comment("A.java", 10, "bug   here"));
            List<LineComment> submitted = List.of(comment("A.java", 10, "  bug here\n"));

            assertThat(outcomes(log.classify(generated, submitted, META))).containsExactly("kept");
        }

        @Test
        void treatsACapitalizationChangeAsAnEdit() {
            List<LineComment> generated = List.of(comment("A.java", 10, "bug here"));
            List<LineComment> submitted = List.of(comment("A.java", 10, "Bug here"));

            assertThat(outcomes(log.classify(generated, submitted, META)))
                    .containsExactly("edited");
        }

        /**
         * Two comments on one line, one kept verbatim and one rewritten: the exact match must be
         * paired with the kept one, or both report as edits.
         */
        @Test
        void pairsExactBodyMatchesBeforeFallingBackToPositionWithinALocation() {
            List<LineComment> generated =
                    List.of(comment("A.java", 10, "first"), comment("A.java", 10, "second"));
            List<LineComment> submitted =
                    List.of(comment("A.java", 10, "rewritten"), comment("A.java", 10, "second"));

            assertThat(outcomes(log.classify(generated, submitted, META)))
                    .containsExactlyInAnyOrder("kept", "edited");
        }

        /** Regression guard: consuming every identical match would under-count survivors. */
        @Test
        void consumesOnlyOneSubmittedCommentPerGeneratedComment() {
            List<LineComment> generated = List.of(comment("A.java", 10, "dup"));
            List<LineComment> submitted =
                    List.of(comment("A.java", 10, "dup"), comment("A.java", 10, "dup"));

            assertThat(outcomes(log.classify(generated, submitted, META)))
                    .containsExactlyInAnyOrder("kept", "added");
        }

        @Test
        void toleratesNullListsAndNullMetadata() {
            assertThat(log.classify(null, null, null)).isEmpty();

            List<ReviewOutcomeLog.OutcomeRecord> records =
                    log.classify(List.of(comment("A.java", 1, "x")), null, null);
            assertThat(records).hasSize(1);
            assertThat(records.get(0).provider()).isEmpty();
            assertThat(records.get(0).promptVersion()).isEmpty();
        }

        @Test
        void carriesMetadataAndSegmentationFieldsOntoEveryRecord() {
            LineComment generated = comment("A.java", 10, "bug");
            generated.setSeverity("major");
            generated.setCategory("compatibility");
            generated.setConfidence("high");

            ReviewOutcomeLog.OutcomeRecord record =
                    log.classify(List.of(generated), List.of(), META).get(0);

            assertThat(record.promptVersion()).isEqualTo("v1");
            assertThat(record.provider()).isEqualTo("claude");
            assertThat(record.model()).isEqualTo("sonnet");
            assertThat(record.type()).isEqualTo("issue");
            assertThat(record.severity()).isEqualTo("major");
            assertThat(record.category()).isEqualTo("compatibility");
            assertThat(record.confidence()).isEqualTo("high");
            assertThat(record.recordedAt()).isNotBlank();
        }
    }

    @Nested
    class Fingerprint {

        /** Correlating a finding across prompt versions only works if this is run-independent. */
        @Test
        void isStableForTheSameCommentAcrossCalls() {
            assertThat(ReviewOutcomeLog.fingerprint(comment("A.java", 10, "bug")))
                    .isEqualTo(ReviewOutcomeLog.fingerprint(comment("A.java", 10, "bug")));
        }

        @Test
        void ignoresWhitespaceReflow() {
            assertThat(ReviewOutcomeLog.fingerprint(comment("A.java", 10, "a  b")))
                    .isEqualTo(ReviewOutcomeLog.fingerprint(comment("A.java", 10, " a b ")));
        }

        @Test
        void categoryDoesNotAffectFingerprint() {
            LineComment first = comment("A.java", 10, "bug");
            first.setCategory("correctness");
            LineComment second = comment("A.java", 10, "bug");
            second.setCategory("compatibility");

            assertThat(ReviewOutcomeLog.fingerprint(first))
                    .isEqualTo(ReviewOutcomeLog.fingerprint(second));
        }

        @Test
        void differsForADifferentFileLineOrBody() {
            String base = ReviewOutcomeLog.fingerprint(comment("A.java", 10, "bug"));
            assertThat(ReviewOutcomeLog.fingerprint(comment("B.java", 10, "bug")))
                    .isNotEqualTo(base);
            assertThat(ReviewOutcomeLog.fingerprint(comment("A.java", 11, "bug")))
                    .isNotEqualTo(base);
            assertThat(ReviewOutcomeLog.fingerprint(comment("A.java", 10, "other")))
                    .isNotEqualTo(base);
        }
    }

    @Nested
    class Append {

        private Path tmpDir;
        private Path logFile;
        private ReviewOutcomeLog log;

        @BeforeEach
        void setUp() throws IOException {
            tmpDir = Files.createTempDirectory("outcome-log");
            logFile = tmpDir.resolve("nested").resolve("review-outcomes.jsonl");
            log = new ReviewOutcomeLog(logFile);
        }

        @AfterEach
        void tearDown() throws IOException {
            FileUtils.deleteDirectory(tmpDir.toFile());
        }

        @Test
        void writesOneJsonObjectPerLineAndCreatesMissingDirectories() throws IOException {
            log.record(
                    List.of(comment("A.java", 10, "bug"), comment("A.java", 20, "gone")),
                    List.of(comment("A.java", 10, "bug")),
                    META);

            List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            assertThat(lines).hasSize(2);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode first = mapper.readTree(lines.get(0));
            assertThat(first.path("outcome").asText()).isEqualTo("kept");
            assertThat(first.path("commentFingerprint").asText()).isNotBlank();
            assertThat(mapper.readTree(lines.get(1)).path("outcome").asText()).isEqualTo("deleted");
        }

        /** The log must never leak comment text or repository paths. */
        @Test
        void doesNotWriteCommentBodiesOrFilePaths() throws IOException {
            log.record(
                    List.of(comment("secret/Path.java", 10, "sensitive body text")),
                    List.of(),
                    META);

            String contents = Files.readString(logFile, StandardCharsets.UTF_8);
            assertThat(contents)
                    .doesNotContain("sensitive body text")
                    .doesNotContain("secret/Path");
        }

        @Test
        void appendsRatherThanReplacingPreviousRuns() throws IOException {
            log.record(List.of(comment("A.java", 1, "one")), List.of(), META);
            log.record(List.of(comment("B.java", 2, "two")), List.of(), META);

            assertThat(Files.readAllLines(logFile, StandardCharsets.UTF_8)).hasSize(2);
        }

        @Test
        void writesNothingWhenThereAreNoComments() {
            log.record(List.of(), List.of(), META);

            assertThat(logFile).doesNotExist();
        }

        @Test
        void stopsAppendingOnceTheLogReachesItsSizeCap() throws IOException {
            Files.createDirectories(logFile.getParent());
            Files.write(logFile, new byte[(int) ReviewOutcomeLog.MAX_LOG_BYTES]);

            log.record(List.of(comment("A.java", 1, "one")), List.of(), META);

            assertThat(Files.size(logFile)).isEqualTo(ReviewOutcomeLog.MAX_LOG_BYTES);
        }

        /** Instrumentation must never break a submit, so an unwritable path is swallowed. */
        @Test
        void doesNotThrowWhenTheLogCannotBeWritten() throws IOException {
            Path blocked = tmpDir.resolve("blocked");
            Files.createFile(blocked);
            ReviewOutcomeLog blockedLog = new ReviewOutcomeLog(blocked.resolve("child.jsonl"));

            blockedLog.record(List.of(comment("A.java", 1, "one")), List.of(), META);
        }
    }
}
