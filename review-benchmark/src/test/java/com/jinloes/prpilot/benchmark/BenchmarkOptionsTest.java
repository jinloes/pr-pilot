package com.jinloes.prpilot.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class BenchmarkOptionsTest {
    private static final List<String> REQUIRED =
            List.of("--prs", "prs.txt", "--repos-root", "/src", "--provider", "Copilot");

    private static BenchmarkOptions parse(String... extra) {
        List<String> args = new java.util.ArrayList<>(REQUIRED);
        args.addAll(List.of(extra));
        return BenchmarkOptions.parse(args);
    }

    @Nested
    class Parse {
        @Test
        void appliesDefaults() {
            BenchmarkOptions options = parse();
            assertThat(options.provider()).isEqualTo("copilot");
            assertThat(options.prsFile()).isEqualTo(Path.of("prs.txt"));
            assertThat(options.selfCritique()).isTrue();
            assertThat(options.chunked()).isFalse();
            assertThat(options.callSites()).isTrue();
            assertThat(options.judge()).isEqualTo(BenchmarkOptions.JUDGE_LLM);
            assertThat(options.lineWindow()).isEqualTo(10);
            assertThat(options.maeLoginPrefix()).isEqualTo("svc-mae");
            assertThat(options.outDir()).isEqualTo(Path.of("build", "review-benchmark"));
        }

        @Test
        void judgeModelDefaultsToTheReviewModel() {
            assertThat(parse("--model", "gpt-x").judgeModel()).isEqualTo("gpt-x");
            assertThat(parse("--model", "gpt-x", "--judge-model", "j").judgeModel()).isEqualTo("j");
        }

        @Test
        void readsFlagsAndValues() {
            BenchmarkOptions options =
                    parse(
                            "--no-self-critique",
                            "--supervisor",
                            "--verbose",
                            "--chunked",
                            "--no-call-sites",
                            "--judge",
                            "location",
                            "--line-window",
                            "3",
                            "--second-reviewer",
                            "m2");
            assertThat(options.selfCritique()).isFalse();
            assertThat(options.supervisor()).isTrue();
            assertThat(options.verbose()).isTrue();
            assertThat(options.chunked()).isTrue();
            assertThat(options.callSites()).isFalse();
            assertThat(options.judge()).isEqualTo("location");
            assertThat(options.lineWindow()).isEqualTo(3);
            assertThat(options.secondReviewerModel()).isEqualTo("m2");
        }

        @Test
        void helpNeedsNoOtherOptions() {
            assertThat(BenchmarkOptions.parse(List.of("--help")).help()).isTrue();
        }

        @Test
        void rejectsMissingRequiredOptions() {
            assertThatThrownBy(() -> BenchmarkOptions.parse(List.of("--prs", "x")))
                    .hasMessageContaining("--repos-root");
        }

        @Test
        void rejectsInvalidValues() {
            assertThatThrownBy(() -> parse("--line-window", "-1"))
                    .hasMessageContaining("non-negative");
            assertThatThrownBy(() -> parse("--judge", "vibes")).hasMessageContaining("--judge");
            assertThatThrownBy(
                            () ->
                                    BenchmarkOptions.parse(
                                            List.of(
                                                    "--prs",
                                                    "p",
                                                    "--repos-root",
                                                    "r",
                                                    "--provider",
                                                    "gemini")))
                    .hasMessageContaining("--provider");
        }

        @Test
        void rejectsUnknownOptionsAndMissingValues() {
            assertThatThrownBy(() -> parse("--bogus")).hasMessageContaining("Unknown option");
            assertThatThrownBy(() -> parse("--model")).hasMessageContaining("needs a value");
        }
    }
}
