package com.jinloes.prpilot.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.io.file.PathUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReviewBenchTaskTest {
    static final String BASE = "e7f558c7360abac335af2df22f4ed376992b30ab";
    static final String HEAD = "a1978cb75950a879df8805e4a5f373eb6678f6f9";

    private final ObjectMapper mapper = new ObjectMapper();
    private Path corpus;

    @BeforeEach
    void setUp() throws IOException {
        corpus = Files.createTempDirectory("reviewbench-corpus");
    }

    @AfterEach
    void tearDown() throws IOException {
        PathUtils.deleteDirectory(corpus);
    }

    static ReviewBenchTask task() {
        return new ReviewBenchTask(
                "https://github.com/AA-Factory/aafactory-prototype",
                17,
                BASE,
                HEAD,
                "AA-Factory/aafactory-prototype",
                "Mock test",
                "Body");
    }

    static Map<String, Object> entry(String nwo, int number, String base, String head) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("repo", "https://github.com/" + nwo);
        entry.put("pr_number", number);
        entry.put("pr_url", "https://github.com/" + nwo + "/pull/" + number);
        entry.put("base", base);
        entry.put("head", head);
        entry.put("title", "Title " + number);
        entry.put("body", "Body " + number);
        entry.put("nwo", nwo);
        entry.put("language", "Python");
        return entry;
    }

    private void write(String relative, Object value) throws IOException {
        Path file = corpus.resolve(relative);
        Files.createDirectories(file.getParent());
        mapper.writeValue(file.toFile(), value);
    }

    @Nested
    class Load {
        @Test
        void readsTheTestSetAndIgnoresUnknownFields() throws IOException {
            write("corpus/test/test.json", List.of(entry("o/r", 3, BASE, HEAD)));

            List<ReviewBenchTask> tasks =
                    ReviewBenchTask.load(corpus, ReviewBenchTask.SET_TEST, mapper);

            assertThat(tasks)
                    .containsExactly(
                            new ReviewBenchTask(
                                    "https://github.com/o/r",
                                    3,
                                    BASE,
                                    HEAD,
                                    "o/r",
                                    "Title 3",
                                    "Body 3"));
        }

        @Test
        void readsTheFullManifest() throws IOException {
            write(
                    "corpus/manifest.json",
                    List.of(entry("o/r", 1, BASE, HEAD), entry("o/s", 2, BASE, HEAD)));

            assertThat(ReviewBenchTask.load(corpus, ReviewBenchTask.SET_FULL, mapper))
                    .extracting(ReviewBenchTask::prNumber)
                    .containsExactly(1, 2);
        }

        @Test
        void failsWhenTheCorpusIsMissing() {
            assertThatThrownBy(() -> ReviewBenchTask.load(corpus, ReviewBenchTask.SET_TEST, mapper))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("No ReviewBench corpus");
        }

        @Test
        void rejectsAPathTraversingNwo() throws IOException {
            write("corpus/test/test.json", List.of(entry("../evil", 1, BASE, HEAD)));

            assertThatThrownBy(() -> ReviewBenchTask.load(corpus, ReviewBenchTask.SET_TEST, mapper))
                    .hasMessageContaining("invalid nwo");
        }

        @Test
        void rejectsAnAbbreviatedSha() throws IOException {
            write("corpus/test/test.json", List.of(entry("o/r", 1, BASE, "a1978cb7")));

            assertThatThrownBy(() -> ReviewBenchTask.load(corpus, ReviewBenchTask.SET_TEST, mapper))
                    .hasMessageContaining("invalid head SHA");
        }

        @Test
        void rejectsANonHttpsRepoUrl() throws IOException {
            Map<String, Object> entry = entry("o/r", 1, BASE, HEAD);
            entry.put("repo", "--upload-pack=evil");
            write("corpus/test/test.json", List.of(entry));

            assertThatThrownBy(() -> ReviewBenchTask.load(corpus, ReviewBenchTask.SET_TEST, mapper))
                    .hasMessageContaining("invalid pull request number or repo URL");
        }
    }

    @Nested
    class Naming {
        @Test
        void keyMatchesTheGoldenFileStem() {
            assertThat(task().key()).isEqualTo("AA-Factory_aafactory-prototype_17-a1978cb7");
        }

        @Test
        void mirrorLivesInTheReviewBenchOrganization() {
            assertThat(task().mirrorUrl())
                    .isEqualTo("https://github.com/review-bench/AA-Factory_aafactory-prototype");
            assertThat(task().cloneName()).isEqualTo("AA-Factory_aafactory-prototype");
        }

        @Test
        void splitsOwnerAndName() {
            assertThat(task().owner()).isEqualTo("AA-Factory");
            assertThat(task().name()).isEqualTo("aafactory-prototype");
        }
    }
}
