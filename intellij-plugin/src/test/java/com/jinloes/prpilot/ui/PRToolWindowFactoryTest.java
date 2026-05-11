package com.jinloes.prpilot.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PRToolWindowFactoryTest {

    @Nested
    class BuildQuery {

        // ── Default (open, no user filters) ──────────────────────────────────

        @Test
        void noRepos_fallsBackToAuthorMe() {
            assertThat(buildQuery("", List.of())).isEqualTo("is:pr is:open draft:false author:@me");
        }

        @Test
        void nullCurrentRepo_withNoStarred_fallsBackToAuthorMe() {
            assertThat(buildQuery(null, List.of()))
                    .isEqualTo("is:pr is:open draft:false author:@me");
        }

        @Test
        void currentRepoOnly_buildsRepoQuery() {
            assertThat(buildQuery("acme/platform", List.of()))
                    .isEqualTo("is:pr is:open draft:false repo:acme/platform");
        }

        @Test
        void starredOnly_noCurrentRepo_includesAllStarred() {
            String q = buildQuery("", List.of("alice/foo", "bob/bar"));
            assertThat(q).contains("repo:alice/foo").contains("repo:bob/bar");
            assertThat(q).doesNotContain("author:@me");
        }

        @Test
        void currentRepoPrefixedBeforeStarred() {
            String q = buildQuery("acme/platform", List.of("acme/infra", "alice/foo"));
            assertThat(q.indexOf("repo:acme/platform")).isLessThan(q.indexOf("repo:acme/infra"));
        }

        @Test
        void currentRepoNotDuplicatedWhenInStarredList() {
            String q = buildQuery("acme/platform", List.of("acme/platform", "acme/infra"));
            assertThat(countOccurrences(q, "repo:acme/platform")).isEqualTo(1);
        }

        @Test
        void cappedAtTwentyRepos() {
            List<String> many = new java.util.ArrayList<>();
            for (int i = 0; i < 25; i++) many.add("user/repo" + i);
            assertThat(countOccurrences(buildQuery("", many), "repo:")).isEqualTo(20);
        }

        @Test
        void currentRepoCountsTowardCap() {
            List<String> starred = new java.util.ArrayList<>();
            for (int i = 0; i < 20; i++) starred.add("user/repo" + i);
            assertThat(countOccurrences(buildQuery("acme/platform", starred), "repo:"))
                    .isEqualTo(20);
        }

        // ── State filter ──────────────────────────────────────────────────────

        @Test
        void closedState_usesIsClosedQualifier() {
            String q = buildQuery("acme/platform", List.of(), "closed", false, false);
            assertThat(q).contains("is:closed");
            assertThat(q).doesNotContain("is:open");
        }

        @Test
        void allState_omitsStateQualifier() {
            String q = buildQuery("acme/platform", List.of(), "all", false, false);
            assertThat(q).doesNotContain("is:open");
            assertThat(q).doesNotContain("is:closed");
        }

        @Test
        void openState_usesIsOpenQualifier() {
            String q = buildQuery("acme/platform", List.of(), "open", false, false);
            assertThat(q).contains("is:open");
            assertThat(q).doesNotContain("is:closed");
        }

        // ── Assigned-to-me filter ─────────────────────────────────────────────

        @Test
        void assignedToMe_withRepo_appendsAssigneeQualifier() {
            String q = buildQuery("acme/platform", List.of(), "open", true, false);
            assertThat(q).contains("assignee:@me");
            assertThat(q).doesNotContain("author:@me");
        }

        @Test
        void assignedToMe_noRepo_replacesAuthorMe() {
            String q = buildQuery("", List.of(), "open", true, false);
            assertThat(q).contains("assignee:@me");
            assertThat(q).doesNotContain("author:@me");
        }

        // ── Review-requested filter ───────────────────────────────────────────

        @Test
        void reviewRequested_withRepo_appendsQualifier() {
            String q = buildQuery("acme/platform", List.of(), "open", false, true);
            assertThat(q).contains("review-requested:@me");
        }

        @Test
        void reviewRequested_noRepo_replacesAuthorMe() {
            String q = buildQuery("", List.of(), "open", false, true);
            assertThat(q).contains("review-requested:@me");
            assertThat(q).doesNotContain("author:@me");
        }

        @Test
        void bothUserFilters_canCombine() {
            String q = buildQuery("acme/platform", List.of(), "open", true, true);
            assertThat(q).contains("assignee:@me").contains("review-requested:@me");
        }

        // ── Helpers ──────────────────────────────────────────────────────────

        private static String buildQuery(String currentRepo, List<String> starred) {
            return PRToolWindowFactory.buildQuery(currentRepo, starred, "open", false, false);
        }

        private static String buildQuery(
                String currentRepo,
                List<String> starred,
                String state,
                boolean assignedToMe,
                boolean reviewRequested) {
            return PRToolWindowFactory.buildQuery(
                    currentRepo, starred, state, assignedToMe, reviewRequested);
        }

        private static long countOccurrences(String haystack, String needle) {
            int count = 0;
            int idx = 0;
            while ((idx = haystack.indexOf(needle, idx)) != -1) {
                count++;
                idx += needle.length();
            }
            return count;
        }
    }
}
