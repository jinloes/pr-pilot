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
}
