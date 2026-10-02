package com.jinloes.prpilot.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReviewBenchmarkTest {

    @Nested
    class IsStage {
        @Test
        void keepsStatusesThatCarryAFindingCount() {
            assertThat(ReviewBenchmark.isStage("Draft review has 4 findings before validation"))
                    .isTrue();
            assertThat(ReviewBenchmark.isStage("Validation kept 1 of 1 finding")).isTrue();
        }

        @Test
        void dropsToolAndProgressStatuses() {
            assertThat(ReviewBenchmark.isStage("view")).isFalse();
            assertThat(ReviewBenchmark.isStage("Refining review…")).isFalse();
            assertThat(ReviewBenchmark.isStage(null)).isFalse();
        }
    }

    @Nested
    class ParseDropped {
        @Test
        void parsesTheFileLineAndBody() {
            Finding finding =
                    ReviewBenchmark.parseDropped(
                            "Validation dropped finding at src/a/B.java:42 — Demote this log.");

            assertThat(finding)
                    .isEqualTo(new Finding(null, "src/a/B.java", 42, "Demote this log."));
        }

        @Test
        void returnsNullForOtherStatuses() {
            assertThat(ReviewBenchmark.parseDropped("Validation kept 1 of 2 findings")).isNull();
            assertThat(ReviewBenchmark.parseDropped("Validation dropped finding at nowhere"))
                    .isNull();
            assertThat(ReviewBenchmark.parseDropped(null)).isNull();
        }
    }
}
