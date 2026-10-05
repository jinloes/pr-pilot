package com.jinloes.prpilot.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.model.LineComment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.commons.io.file.PathUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReviewBenchFindingsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private Path dir;

    @BeforeEach
    void setUp() throws IOException {
        dir = Files.createTempDirectory("reviewbench-findings");
    }

    @AfterEach
    void tearDown() throws IOException {
        PathUtils.deleteDirectory(dir);
    }

    @Nested
    class Of {
        @Test
        void mapsEachCommentToASingleLineFinding() {
            ReviewBenchFindings findings =
                    ReviewBenchFindings.of(
                            ReviewBenchTaskTest.task(),
                            List.of(new LineComment("src/a.py", 12, "issue", " Race here. ")),
                            1500,
                            false);

            assertThat(findings.findings())
                    .containsExactly(
                            new ReviewBenchFindings.Item(
                                    "pr-pilot", "src/a.py", 12, 12, "Race here."));
            assertThat(findings.agent()).isEqualTo("pr-pilot");
            assertThat(findings.pr().prNumber()).isEqualTo(17);
            assertThat(findings.pr().head()).isEqualTo(ReviewBenchTaskTest.HEAD);
            assertThat(findings.usage().timeInMs()).isEqualTo(1500);
        }

        @Test
        void dropsNotesUnlessIncluded() {
            List<LineComment> comments =
                    List.of(
                            new LineComment("a.py", 1, "note", "Nice."),
                            new LineComment("a.py", 2, "suggestion", "Rename."));

            assertThat(
                            ReviewBenchFindings.of(ReviewBenchTaskTest.task(), comments, 0, false)
                                    .findings())
                    .extracting(ReviewBenchFindings.Item::message)
                    .containsExactly("Rename.");
            assertThat(
                            ReviewBenchFindings.of(ReviewBenchTaskTest.task(), comments, 0, true)
                                    .findings())
                    .hasSize(2);
        }

        @Test
        void anchorsFileLevelCommentsToLineOne() {
            ReviewBenchFindings findings =
                    ReviewBenchFindings.of(
                            ReviewBenchTaskTest.task(),
                            List.of(new LineComment("a.py", 0, "issue", "Whole file.")),
                            0,
                            false);

            assertThat(findings.findings().get(0).startLine()).isEqualTo(1);
            assertThat(findings.findings().get(0).endLine()).isEqualTo(1);
        }

        @Test
        void skipsCommentsWithoutAFileOrBody() {
            List<LineComment> comments =
                    List.of(
                            new LineComment("", 1, "issue", "No file."),
                            new LineComment("a.py", 1, "issue", "  "),
                            new LineComment(null, 1, "issue", null));

            assertThat(
                            ReviewBenchFindings.of(ReviewBenchTaskTest.task(), comments, 0, false)
                                    .findings())
                    .isEmpty();
        }

        @Test
        void treatsMissingCommentsAsACompletedEmptyReview() {
            ReviewBenchFindings findings =
                    ReviewBenchFindings.of(ReviewBenchTaskTest.task(), null, -5, false);

            assertThat(findings.findings()).isEmpty();
            assertThat(findings.usage().timeInMs()).isZero();
        }
    }

    @Nested
    class NormalizePath {
        @Test
        void makesPathsRepositoryRelativeWithForwardSlashes() {
            assertThat(ReviewBenchFindings.normalizePath("./src/a.py")).isEqualTo("src/a.py");
            assertThat(ReviewBenchFindings.normalizePath("/src/a.py")).isEqualTo("src/a.py");
            assertThat(ReviewBenchFindings.normalizePath(".//src\\b.py")).isEqualTo("src/b.py");
            assertThat(ReviewBenchFindings.normalizePath(null)).isEmpty();
        }
    }

    @Nested
    class Write {
        @Test
        void writesTheJudgingInputFormat() throws IOException {
            Path file = dir.resolve("round-1").resolve("key.json");
            ReviewBenchFindings.of(
                            ReviewBenchTaskTest.task(),
                            List.of(new LineComment("a.py", 4, "issue", "Bug.")),
                            42,
                            false)
                    .write(file, mapper);

            JsonNode json = mapper.readTree(file.toFile());
            assertThat(json.path("pr").path("repo").asText())
                    .isEqualTo("https://github.com/AA-Factory/aafactory-prototype");
            assertThat(json.path("pr").path("pr_number").asInt()).isEqualTo(17);
            assertThat(json.path("pr").path("base").asText()).isEqualTo(ReviewBenchTaskTest.BASE);
            assertThat(json.path("agent").asText()).isEqualTo("pr-pilot");
            JsonNode finding = json.path("findings").get(0);
            assertThat(finding.path("producer").asText()).isEqualTo("pr-pilot");
            assertThat(finding.path("file").asText()).isEqualTo("a.py");
            assertThat(finding.path("start_line").asInt()).isEqualTo(4);
            assertThat(finding.path("end_line").asInt()).isEqualTo(4);
            assertThat(finding.path("message").asText()).isEqualTo("Bug.");
            assertThat(json.path("usage").path("time_in_ms").asLong()).isEqualTo(42);
            assertThat(json.path("pr").has("prNumber")).isFalse();
            try (var files = Files.list(file.getParent())) {
                assertThat(files).containsExactly(file);
            }
        }
    }
}
