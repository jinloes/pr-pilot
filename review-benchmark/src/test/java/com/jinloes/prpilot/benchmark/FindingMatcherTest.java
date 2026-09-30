package com.jinloes.prpilot.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class FindingMatcherTest {
    private static final Finding M1 = new Finding("M1", "src/A.java", 10, "null deref");
    private static final Finding M2 = new Finding("M2", "src/A.java", 40, "leak");
    private static final Finding M3 = new Finding("M3", "src/B.java", 5, "race");
    private static final Finding P1 = new Finding("P1", "./src/A.java", 12, "may be null");
    private static final Finding P2 = new Finding("P2", "src/A.java", 13, "naming");
    private static final Finding P3 = new Finding("P3", "src/C.java", 5, "other");

    @Nested
    class Candidates {
        @Test
        void pairsFindingsInTheSameFileWithinTheWindow() {
            assertThat(FindingMatcher.candidates(List.of(M1, M2, M3), List.of(P1, P2, P3), 10))
                    .containsExactly(
                            new FindingMatcher.Pair(M1, P1), new FindingMatcher.Pair(M1, P2));
        }

        @Test
        void aFileLevelFindingMatchesAnyLineInItsFile() {
            Finding fileLevel = new Finding("M9", "src/A.java", 0, "design");
            assertThat(FindingMatcher.candidates(List.of(fileLevel), List.of(P1), 0)).hasSize(1);
        }
    }

    @Nested
    class Match {
        @Test
        void assignsEachFindingAtMostOnceInJudgeOrder() throws Exception {
            Finding m1b = new Finding("M4", "src/A.java", 11, "also null");
            FindingMatcher.Outcome outcome =
                    FindingMatcher.match(
                            List.of(M1, m1b), List.of(P1), 10, FindingMatcher.LOCATION_ONLY);

            assertThat(outcome.matches()).containsExactly(new FindingMatcher.Pair(m1b, P1));
            assertThat(outcome.misses())
                    .containsExactly(new FindingMatcher.Miss(M1, FindingMatcher.JUDGED_DIFFERENT));
        }

        @Test
        void recordsWhyEachMaeFindingWasMissedAndKeepsExtras() throws Exception {
            FindingMatcher.Outcome outcome =
                    FindingMatcher.match(
                            List.of(M1, M3), List.of(P1, P2, P3), 10, candidates -> List.of());

            assertThat(outcome.matches()).isEmpty();
            assertThat(outcome.misses())
                    .containsExactly(
                            new FindingMatcher.Miss(M1, FindingMatcher.JUDGED_DIFFERENT),
                            new FindingMatcher.Miss(M3, FindingMatcher.NO_NEARBY_FINDING));
            assertThat(outcome.extras()).containsExactly(P1, P2, P3);
        }

        @Test
        void ignoresJudgedPairsOutsideTheCandidateSet() throws Exception {
            FindingMatcher.Outcome outcome =
                    FindingMatcher.match(
                            List.of(M3),
                            List.of(P3),
                            10,
                            candidates -> List.of(new FindingMatcher.Pair(M3, P3)));
            assertThat(outcome.matches()).isEmpty();
        }

        @Test
        void locationJudgePrefersTheNearestFinding() throws Exception {
            FindingMatcher.Outcome outcome =
                    FindingMatcher.match(
                            List.of(M1), List.of(P2, P1), 10, FindingMatcher.LOCATION_ONLY);
            assertThat(outcome.matches()).containsExactly(new FindingMatcher.Pair(M1, P1));
            assertThat(outcome.extras()).containsExactly(P2);
        }
    }

    @Nested
    class NormalizePath {
        @Test
        void stripsLeadingDotSlashAndBackslashes() {
            assertThat(FindingMatcher.normalizePath(".\\src\\A.java")).isEqualTo("src/A.java");
            assertThat(FindingMatcher.normalizePath("/src/A.java")).isEqualTo("src/A.java");
            assertThat(FindingMatcher.normalizePath(null)).isEmpty();
        }
    }
}
