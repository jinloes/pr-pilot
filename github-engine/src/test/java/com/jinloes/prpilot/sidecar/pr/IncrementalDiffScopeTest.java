package com.jinloes.prpilot.sidecar.pr;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.DiffCoverage;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class IncrementalDiffScopeTest {

    private static String section(String path, String... hunks) {
        StringBuilder text =
                new StringBuilder("diff --git a/" + path + " b/" + path + "\n")
                        .append("index 1111111..2222222 100644\n")
                        .append("--- a/")
                        .append(path)
                        .append("\n+++ b/")
                        .append(path)
                        .append('\n');
        for (String hunk : hunks) text.append(hunk);
        return text.toString();
    }

    private static String hunk(int newStart, int newCount, String line) {
        return "@@ -"
                + newStart
                + ","
                + newCount
                + " +"
                + newStart
                + ","
                + newCount
                + " @@\n"
                + " context\n+"
                + line
                + "\n";
    }

    @Nested
    class Restrict {

        @Test
        void dropsFilesThePullRequestDoesNotChange() {
            String pr = section("src/Feature.java", hunk(10, 3, "feature"));
            String incremental =
                    section("src/Feature.java", hunk(10, 3, "fix"))
                            + section("src/FromMaster.java", hunk(1, 2, "master"));

            IncrementalDiffScope.Restricted result =
                    IncrementalDiffScope.restrict(incremental, false, pr);

            assertThat(result.diff()).isEqualTo(section("src/Feature.java", hunk(10, 3, "fix")));
            assertThat(result.droppedFiles()).isEqualTo(1);
            assertThat(result.truncated()).isFalse();
        }

        @Test
        void keepsOnlyHunksOverlappingThePullRequestsHunksInASharedFile() {
            String pr = section("src/Shared.java", hunk(40, 4, "author"));
            String incremental =
                    section("src/Shared.java", hunk(5, 3, "master"), hunk(42, 3, "author fix"));

            IncrementalDiffScope.Restricted result =
                    IncrementalDiffScope.restrict(incremental, false, pr);

            assertThat(result.diff())
                    .isEqualTo(section("src/Shared.java", hunk(42, 3, "author fix")));
            assertThat(result.droppedFiles()).isZero();
        }

        @Test
        void dropsASharedFileWhenNoneOfItsHunksOverlap() {
            String pr = section("src/Shared.java", hunk(40, 4, "author"));
            String incremental = section("src/Shared.java", hunk(5, 3, "master"));

            IncrementalDiffScope.Restricted result =
                    IncrementalDiffScope.restrict(incremental, false, pr);

            assertThat(result.diff()).isEmpty();
            assertThat(result.droppedFiles()).isEqualTo(1);
        }

        @Test
        void matchesAPureDeletionHunkToThePullRequestHunkAtTheSameLine() {
            String pr = section("src/A.java", "@@ -20,2 +20,0 @@\n-gone\n-also gone\n");
            String incremental = section("src/A.java", "@@ -20,1 +20,0 @@\n-gone\n");

            assertThat(IncrementalDiffScope.restrict(incremental, false, pr).diff())
                    .isEqualTo(incremental);
        }

        @Test
        void keepsAHunklessSectionForAFileThePullRequestChanges() {
            String rename =
                    "diff --git a/old.txt b/new.txt\nsimilarity index 100%\n"
                            + "rename from old.txt\nrename to new.txt\n";
            String pr = rename;

            assertThat(IncrementalDiffScope.restrict(rename, false, pr).diff()).isEqualTo(rename);
        }

        @Test
        void keepsFilesThePullRequestDiffMayHaveOmitted() {
            DiffCoverage listed = new DiffCoverage(1, List.of("src/Big.java"), 1000, true);
            DiffCoverage unlisted = new DiffCoverage(1, List.of(), 1000, true);
            DiffCoverage partialScan = new DiffCoverage(0, List.of(), 1000, false);
            String body = section("src/Feature.java", hunk(10, 3, "feature"));
            String incremental = section("src/Big.java", hunk(1, 2, "change"));

            for (DiffCoverage coverage : List.of(listed, unlisted, partialScan)) {
                assertThat(
                                IncrementalDiffScope.restrict(
                                                incremental, false, body + coverage.trailer())
                                        .diff())
                        .as(coverage.toString())
                        .isEqualTo(incremental);
            }
        }

        @Test
        void removesBaseOnlyPathsFromTheIncrementalCoverageTrailer() {
            String pr = section("src/Feature.java", hunk(10, 3, "feature"));
            DiffCoverage coverage =
                    new DiffCoverage(
                            2, List.of("src/Feature.java", "src/FromMaster.java"), 500, true);
            String incremental =
                    section("src/Feature.java", hunk(10, 3, "fix")) + coverage.trailer();

            IncrementalDiffScope.Restricted result =
                    IncrementalDiffScope.restrict(incremental, true, pr);

            assertThat(result.diff())
                    .isEqualTo(
                            section("src/Feature.java", hunk(10, 3, "fix"))
                                    + new DiffCoverage(1, List.of("src/Feature.java"), 500, true)
                                            .trailer());
            assertThat(result.truncated()).isTrue();
        }

        @Test
        void clearsTruncationWhenOnlyBaseOnlyFilesWereOmitted() {
            String pr = section("src/Feature.java", hunk(10, 3, "feature"));
            DiffCoverage coverage = new DiffCoverage(1, List.of("src/FromMaster.java"), 500, true);
            String body = section("src/Feature.java", hunk(10, 3, "fix"));

            IncrementalDiffScope.Restricted result =
                    IncrementalDiffScope.restrict(body + coverage.trailer(), true, pr);

            assertThat(result.diff()).isEqualTo(body);
            assertThat(result.truncated()).isFalse();
        }

        @Test
        void returnsAnEmptyDiffWhenEveryChangeCameFromTheBase() {
            String pr = section("src/Feature.java", hunk(10, 3, "feature"));
            String incremental = section("src/FromMaster.java", hunk(1, 2, "master"));

            IncrementalDiffScope.Restricted result =
                    IncrementalDiffScope.restrict(incremental, true, pr);

            assertThat(result.diff()).isEmpty();
            assertThat(result.truncated()).isFalse();
            assertThat(result.droppedFiles()).isEqualTo(1);
        }
    }
}
