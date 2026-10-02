package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReviewCoverageAnalyzerTest {
    private final ReviewCoverageAnalyzer analyzer = new ReviewCoverageAnalyzer();

    @Nested
    class FindGaps {
        @Test
        void findsOnlyUninspectedHighRiskHunks() {
            InspectionManifest manifest =
                    InspectionManifest.fromDiff(
                            """
                            diff --git a/src/Api.java b/src/Api.java
                            --- a/src/Api.java
                            +++ b/src/Api.java
                            @@ -1 +1 @@
                            -private void oldApi() {}
                            +public void newApi() {}
                            @@ -10 +10 @@
                            -int value = 1;
                            +int value = 2;
                            """);
            String inspected = manifest.files().get(0).hunks().get(1).id();

            List<CoverageGap> gaps =
                    analyzer.findGaps(
                            manifest, new InspectionLedger(true, Set.of(inspected), List.of()));

            assertThat(gaps)
                    .singleElement()
                    .extracting(CoverageGap::targetId)
                    .isEqualTo(manifest.files().get(0).hunks().get(0).id());
        }

        @Test
        void reportsAChangedFileTheLedgerNeverMentionedAtLowerPriority() {
            InspectionManifest manifest =
                    InspectionManifest.fromDiff(
                            """
                            diff --git a/src/Api.java b/src/Api.java
                            --- a/src/Api.java
                            +++ b/src/Api.java
                            @@ -1 +1 @@
                            -private void oldApi() {}
                            +public void newApi() {}
                            diff --git a/src/Util.java b/src/Util.java
                            --- a/src/Util.java
                            +++ b/src/Util.java
                            @@ -5 +7 @@
                            -int value = 1;
                            +int value = 2;
                            """);
            InspectionManifest.FileTarget api = manifest.files().get(0);
            InspectionManifest.FileTarget util = manifest.files().get(1);

            List<CoverageGap> gaps =
                    analyzer.findGaps(
                            manifest,
                            new InspectionLedger(true, Set.of(api.hunks().get(0).id()), List.of()));

            assertThat(gaps)
                    .singleElement()
                    .satisfies(
                            gap -> {
                                assertThat(gap.targetId()).isEqualTo(util.id());
                                assertThat(gap.path()).isEqualTo("src/Util.java");
                                assertThat(gap.newStart()).isEqualTo(7);
                                assertThat(gap.priority()).isEqualTo(50);
                                assertThat(gap.reason())
                                        .isEqualTo("Changed file was not recorded as inspected.");
                            });
        }

        @Test
        void aFileWhoseHunkWasInspectedIsNotAFileGap() {
            InspectionManifest manifest =
                    InspectionManifest.fromDiff(
                            """
                            diff --git a/src/Util.java b/src/Util.java
                            --- a/src/Util.java
                            +++ b/src/Util.java
                            @@ -5 +5 @@
                            -int value = 1;
                            +int value = 2;
                            @@ -20 +20 @@
                            -int other = 1;
                            +int other = 2;
                            """);
            String inspected = manifest.files().get(0).hunks().get(1).id();

            assertThat(
                            analyzer.findGaps(
                                    manifest,
                                    new InspectionLedger(true, Set.of(inspected), List.of())))
                    .isEmpty();
        }

        @Test
        void aFileMentionedByIdIsNotAFileGap() {
            InspectionManifest manifest =
                    InspectionManifest.fromDiff(
                            """
                            diff --git a/src/Util.java b/src/Util.java
                            --- a/src/Util.java
                            +++ b/src/Util.java
                            @@ -5 +5 @@
                            -int value = 1;
                            +int value = 2;
                            """);

            assertThat(
                            analyzer.findGaps(
                                    manifest,
                                    new InspectionLedger(
                                            true, Set.of(manifest.files().get(0).id()), List.of())))
                    .isEmpty();
        }

        @Test
        void rankHighRiskHunksAboveFileGaps() {
            InspectionManifest manifest =
                    InspectionManifest.fromDiff(
                            """
                            diff --git a/src/Api.java b/src/Api.java
                            --- a/src/Api.java
                            +++ b/src/Api.java
                            @@ -1 +1 @@
                            -private void oldApi() {}
                            +public void newApi() {}
                            """);

            List<CoverageGap> gaps =
                    analyzer.findGaps(manifest, new InspectionLedger(true, Set.of(), List.of()));

            assertThat(gaps).extracting(CoverageGap::priority).containsExactly(100, 50);
        }

        @Test
        void capsHunkGapsButReturnsEveryUnmentionedFile() {
            StringBuilder diff = new StringBuilder();
            for (int index = 0; index < 20; index++) {
                diff.append(
                        """
                        diff --git a/src/Api%1$02d.java b/src/Api%1$02d.java
                        --- a/src/Api%1$02d.java
                        +++ b/src/Api%1$02d.java
                        @@ -1 +1 @@
                        -private void oldApi() {}
                        +public void newApi() {}
                        """
                                .formatted(index));
            }

            List<CoverageGap> gaps =
                    analyzer.findGaps(
                            InspectionManifest.fromDiff(diff.toString()),
                            new InspectionLedger(true, Set.of(), List.of()));

            assertThat(gaps).filteredOn(gap -> !gap.wholeFile()).hasSize(12);
            assertThat(gaps).filteredOn(CoverageGap::wholeFile).hasSize(20);
            assertThat(gaps.subList(0, 12)).noneMatch(CoverageGap::wholeFile);
        }

        @Test
        void doesNotGuessCoverageWhenTheProviderOmittedTheLedger() {
            InspectionManifest manifest =
                    InspectionManifest.fromDiff(
                            """
                            diff --git a/api.proto b/api.proto
                            --- a/api.proto
                            +++ b/api.proto
                            @@ -1 +1 @@
                            -string old = 1;
                            +string current = 2;
                            """);

            assertThat(analyzer.findGaps(manifest, InspectionLedger.missing())).isEmpty();
        }

        @Test
        void doesNotCreateGapsForDeletedFiles() {
            InspectionManifest manifest =
                    InspectionManifest.fromDiff(
                            """
                            diff --git a/schema.sql b/schema.sql
                            --- a/schema.sql
                            +++ /dev/null
                            @@ -1 +0,0 @@
                            -CREATE TABLE users (id BIGINT);
                            """);

            assertThat(analyzer.findGaps(manifest, new InspectionLedger(true, Set.of(), List.of())))
                    .isEmpty();
        }
    }
}
