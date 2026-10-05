package com.jinloes.prpilot.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReviewBenchOptionsTest {
    private static final List<String> REQUIRED = List.of("--corpus", "/c", "--out", "/o");

    private static List<String> with(String... extra) {
        return Stream.concat(REQUIRED.stream(), Stream.of(extra)).toList();
    }

    private static ReviewBenchTask task(String nwo, int number) {
        return new ReviewBenchTask(
                "https://github.com/" + nwo,
                number,
                ReviewBenchTaskTest.BASE,
                ReviewBenchTaskTest.HEAD,
                nwo,
                "",
                "");
    }

    @Nested
    class Parse {
        @Test
        void appliesDefaults() {
            ReviewBenchOptions options = ReviewBenchOptions.parse(REQUIRED);

            assertThat(options.corpus()).isEqualTo(Path.of("/c"));
            assertThat(options.out()).isEqualTo(Path.of("/o"));
            assertThat(options.set()).isEqualTo("test");
            assertThat(options.rounds()).isEqualTo(1);
            assertThat(options.limit()).isZero();
            assertThat(options.provider()).isEqualTo("copilot");
            assertThat(options.reposDir()).isEqualTo(Path.of("build", "reviewbench", "repos"));
            assertThat(options.selfCritique()).isTrue();
            assertThat(options.callSites()).isTrue();
            assertThat(options.includeNotes()).isFalse();
            assertThat(options.help()).isFalse();
        }

        @Test
        void readsEveryOption() {
            ReviewBenchOptions options =
                    ReviewBenchOptions.parse(
                            with(
                                    "--set",
                                    "FULL",
                                    "--rounds",
                                    "3",
                                    "--limit",
                                    "5",
                                    "--only",
                                    "a",
                                    "--only",
                                    "b",
                                    "--repos-dir",
                                    "/r",
                                    "--provider",
                                    "Claude",
                                    "--model",
                                    "m",
                                    "--effort",
                                    "high",
                                    "--config-dir",
                                    "/cfg",
                                    "--second-reviewer",
                                    "s",
                                    "--no-self-critique",
                                    "--supervisor",
                                    "--chunked",
                                    "--no-call-sites",
                                    "--include-notes",
                                    "--verbose"));

            assertThat(options.set()).isEqualTo("full");
            assertThat(options.rounds()).isEqualTo(3);
            assertThat(options.limit()).isEqualTo(5);
            assertThat(options.only()).containsExactly("a", "b");
            assertThat(options.reposDir()).isEqualTo(Path.of("/r"));
            assertThat(options.provider()).isEqualTo("claude");
            assertThat(options.model()).isEqualTo("m");
            assertThat(options.effort()).isEqualTo("high");
            assertThat(options.configDir()).isEqualTo("/cfg");
            assertThat(options.secondReviewerModel()).isEqualTo("s");
            assertThat(options.selfCritique()).isFalse();
            assertThat(options.supervisor()).isTrue();
            assertThat(options.chunked()).isTrue();
            assertThat(options.callSites()).isFalse();
            assertThat(options.includeNotes()).isTrue();
            assertThat(options.verbose()).isTrue();
        }

        @Test
        void helpNeedsNoRequiredOptions() {
            assertThat(ReviewBenchOptions.parse(List.of("--help")).help()).isTrue();
        }

        @Test
        void rejectsInvalidInput() {
            assertThatThrownBy(() -> ReviewBenchOptions.parse(List.of("--out", "/o")))
                    .hasMessageContaining("--corpus is required");
            assertThatThrownBy(() -> ReviewBenchOptions.parse(List.of("--corpus", "/c")))
                    .hasMessageContaining("--out is required");
            assertThatThrownBy(() -> ReviewBenchOptions.parse(with("--set", "all")))
                    .hasMessageContaining("--set must be test or full");
            assertThatThrownBy(() -> ReviewBenchOptions.parse(with("--provider", "gpt")))
                    .hasMessageContaining("--provider");
            assertThatThrownBy(() -> ReviewBenchOptions.parse(with("--rounds", "0")))
                    .hasMessageContaining("positive integer");
            assertThatThrownBy(() -> ReviewBenchOptions.parse(with("--limit")))
                    .hasMessageContaining("needs a value");
            assertThatThrownBy(() -> ReviewBenchOptions.parse(with("--bogus")))
                    .hasMessageContaining("Unknown option: --bogus");
        }
    }

    @Nested
    class Select {
        private final List<ReviewBenchTask> tasks =
                List.of(task("o/a", 1), task("o/b", 2), task("o/c", 3));

        @Test
        void keepsEverythingByDefault() {
            assertThat(ReviewBenchOptions.parse(REQUIRED).select(tasks)).isEqualTo(tasks);
        }

        @Test
        void limitsToTheFirstTasks() {
            assertThat(ReviewBenchOptions.parse(with("--limit", "2")).select(tasks))
                    .extracting(ReviewBenchTask::prNumber)
                    .containsExactly(1, 2);
        }

        @Test
        void filtersByKeyInCorpusOrder() {
            ReviewBenchOptions options =
                    ReviewBenchOptions.parse(
                            with("--only", tasks.get(2).key(), "--only", tasks.get(0).key()));

            assertThat(options.select(tasks))
                    .extracting(ReviewBenchTask::prNumber)
                    .containsExactly(1, 3);
        }

        @Test
        void toleratesARepeatedKey() {
            String key = tasks.get(1).key();
            assertThat(ReviewBenchOptions.parse(with("--only", key, "--only", key)).select(tasks))
                    .extracting(ReviewBenchTask::prNumber)
                    .containsExactly(2);
        }

        @Test
        void rejectsAnUnknownKey() {
            assertThatThrownBy(
                            () ->
                                    ReviewBenchOptions.parse(with("--only", "o_z_9-deadbeef"))
                                            .select(tasks))
                    .hasMessageContaining("o_z_9-deadbeef");
        }
    }
}
