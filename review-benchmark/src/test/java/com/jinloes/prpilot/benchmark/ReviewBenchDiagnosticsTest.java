package com.jinloes.prpilot.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.commons.io.file.PathUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReviewBenchDiagnosticsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private Path dir;

    @BeforeEach
    void setUp() throws IOException {
        dir = Files.createTempDirectory("reviewbench-diagnostics");
    }

    @AfterEach
    void tearDown() throws IOException {
        PathUtils.deleteDirectory(dir);
    }

    @Nested
    class Of {
        @Test
        void normalizesDroppedPathsAndCopiesStages() {
            ReviewBenchDiagnostics diagnostics =
                    ReviewBenchDiagnostics.of(
                            List.of("Found 3 findings"),
                            List.of(new Finding("d1", "./src\\a.py", 12, "Unchecked index.")));

            assertThat(diagnostics.stages()).containsExactly("Found 3 findings");
            assertThat(diagnostics.dropped())
                    .containsExactly(
                            new ReviewBenchDiagnostics.Dropped("src/a.py", 12, "Unchecked index."));
        }

        @Test
        void acceptsAReviewWithNothingDropped() {
            ReviewBenchDiagnostics diagnostics = ReviewBenchDiagnostics.of(List.of(), List.of());

            assertThat(diagnostics.stages()).isEmpty();
            assertThat(diagnostics.dropped()).isEmpty();
        }
    }

    @Nested
    class Write {
        @Test
        void writesStagesAndDroppedFindingsCreatingParents() throws IOException {
            Path file = dir.resolve("diagnostics/round-1/key.json");

            ReviewBenchDiagnostics.of(
                            List.of("Validated 2 findings"),
                            List.of(new Finding("d1", "b.py", 7, "Leak.")))
                    .write(file, mapper);

            JsonNode json = mapper.readTree(file.toFile());
            assertThat(json.get("stages").get(0).asText()).isEqualTo("Validated 2 findings");
            JsonNode dropped = json.get("dropped").get(0);
            assertThat(dropped.get("file").asText()).isEqualTo("b.py");
            assertThat(dropped.get("line").asInt()).isEqualTo(7);
            assertThat(dropped.get("message").asText()).isEqualTo("Leak.");
            try (var siblings = Files.list(file.getParent())) {
                assertThat(siblings).containsExactly(file);
            }
        }
    }

    @Nested
    class DiagnosticsFile {
        @Test
        void livesOutsideTheFindingsTheJudgeReads() {
            ReviewBenchTask task =
                    new ReviewBenchTask(
                            "https://github.com/o/r",
                            4,
                            ReviewBenchTaskTest.BASE,
                            ReviewBenchTaskTest.HEAD,
                            "o/r",
                            "",
                            "");

            Path file = ReviewBenchRunner.diagnosticsFile(dir, 2, task);

            assertThat(file).isEqualTo(dir.resolve("diagnostics/round-2/" + task.key() + ".json"));
            assertThat(file.startsWith(ReviewBenchRunner.findingsRoot(dir))).isFalse();
        }
    }
}
