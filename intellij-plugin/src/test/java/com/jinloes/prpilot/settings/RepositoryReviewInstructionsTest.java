package com.jinloes.prpilot.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RepositoryReviewInstructionsTest {

    @Nested
    class RepositoryKey {
        @Test
        void lowercasesAndTrimsValidGitHubNames() {
            assertThat(RepositoryReviewInstructions.repositoryKey("Acme", "My.Repo_1"))
                    .isEqualTo("acme/my.repo_1");
            assertThat(RepositoryReviewInstructions.repositoryKey(" acme ", " widget "))
                    .isEqualTo("acme/widget");
        }

        @Test
        void rejectsInvalidNames() {
            assertThat(RepositoryReviewInstructions.repositoryKey("a b", "widget")).isNull();
            assertThat(RepositoryReviewInstructions.repositoryKey("acme", "wid/get")).isNull();
            assertThat(RepositoryReviewInstructions.repositoryKey("", "widget")).isNull();
            assertThat(RepositoryReviewInstructions.repositoryKey("-acme", "widget")).isNull();
            assertThat(RepositoryReviewInstructions.repositoryKey(null, null)).isNull();
        }
    }

    @Nested
    class Normalize {
        @Test
        void keepsValidTrimmedEntriesAndTheFirstOfCollidingKeys() {
            Map<String, String> raw = new LinkedHashMap<>();
            raw.put("Acme/Widget", "  First  ");
            raw.put("acme/widget", "Second");
            raw.put("bad key", "x");
            raw.put("acme/blank", "   ");
            raw.put("acme/null", null);
            raw.put("acme/huge", "x".repeat(10_001));
            raw.put(null, "orphan");

            assertThat(RepositoryReviewInstructions.normalize(raw))
                    .containsExactly(Map.entry("acme/widget", "First"));
        }

        @Test
        void treatsNullAsEmptyAndCapsRepositoryCount() {
            assertThat(RepositoryReviewInstructions.normalize(null)).isEmpty();
            Map<String, String> raw = new LinkedHashMap<>();
            for (int i = 0; i < RepositoryReviewInstructions.MAX_REPOSITORIES + 5; i++) {
                raw.put("owner/repo-" + i, "Rule");
            }
            assertThat(RepositoryReviewInstructions.normalize(raw))
                    .hasSize(RepositoryReviewInstructions.MAX_REPOSITORIES);
        }
    }

    @Nested
    class With {
        @Test
        void setsReplacesAndRemovesWithoutMutatingTheInput() {
            Map<String, String> current = Map.of("acme/widget", "Old");

            assertThat(RepositoryReviewInstructions.with(current, "acme/widget", " New "))
                    .containsExactly(Map.entry("acme/widget", "New"));
            assertThat(RepositoryReviewInstructions.with(current, "acme/widget", "  ")).isEmpty();
            assertThat(RepositoryReviewInstructions.with(current, "acme/widget", null)).isEmpty();
            assertThat(current).containsExactly(Map.entry("acme/widget", "Old"));
        }

        @Test
        void rejectsOversizedTextAndNewKeysPastTheCap() {
            assertThat(
                            RepositoryReviewInstructions.with(
                                    Map.of(), "acme/widget", "x".repeat(10_001)))
                    .isNull();
            Map<String, String> full = new LinkedHashMap<>();
            for (int i = 0; i < RepositoryReviewInstructions.MAX_REPOSITORIES; i++) {
                full.put("owner/repo-" + i, "Rule");
            }
            assertThat(RepositoryReviewInstructions.with(full, "acme/new", "Rule")).isNull();
            assertThat(RepositoryReviewInstructions.with(full, "owner/repo-0", "Updated"))
                    .containsEntry("owner/repo-0", "Updated");
        }
    }

    @Nested
    class Compose {
        @Test
        void placesRememberedTextAheadOfOtherInstructions() {
            assertThat(RepositoryReviewInstructions.compose("acme/widget", "", "Per review"))
                    .isEqualTo("Per review");
            assertThat(RepositoryReviewInstructions.compose("acme/widget", null, null)).isEmpty();
            assertThat(RepositoryReviewInstructions.compose("acme/widget", " Rule ", ""))
                    .isEqualTo("Instructions remembered for acme/widget:\nRule");
            assertThat(RepositoryReviewInstructions.compose("acme/widget", "Rule", " Per review "))
                    .isEqualTo("Instructions remembered for acme/widget:\nRule\n\nPer review");
        }
    }
}
