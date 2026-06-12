package com.jinloes.prpilot.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.settings.PluginSettings;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PRToolWindowFactoryTest {

    @Nested
    class SetupReason {

        @Test
        void notInstalledMapsToGhNotInstalled() {
            assertThat(PRToolWindowFactory.setupReason(PluginSettings.AuthDiagnosis.NOT_INSTALLED))
                    .isEqualTo("gh_not_installed");
        }

        @Test
        void nonInstalledMapsToGhNotAuthenticated() {
            assertThat(PRToolWindowFactory.setupReason(PluginSettings.AuthDiagnosis.OK))
                    .isEqualTo("gh_not_authenticated");
            assertThat(
                            PRToolWindowFactory.setupReason(
                                    PluginSettings.AuthDiagnosis.NOT_AUTHENTICATED))
                    .isEqualTo("gh_not_authenticated");
        }
    }

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
        void starredOnly_noCurrentRepo_doesNotAffectCurrentRepoScope() {
            String q = buildQuery("", List.of("alice/foo", "bob/bar"));
            assertThat(q).isEqualTo("is:pr is:open draft:false author:@me");
        }

        @Test
        void currentRepoScopeIgnoresStarredRepos() {
            String q = buildQuery("acme/platform", List.of("acme/infra", "alice/foo"));
            assertThat(q).isEqualTo("is:pr is:open draft:false repo:acme/platform");
            assertThat(q).doesNotContain("acme/infra").doesNotContain("alice/foo");
        }

        @Test
        void currentRepoNotDuplicatedWhenInStarredList() {
            String q = buildQuery("acme/platform", List.of("acme/platform", "acme/infra"));
            assertThat(countOccurrences(q, "repo:acme/platform")).isEqualTo(1);
        }

        // ── State filter ──────────────────────────────────────────────────────

        @Test
        void closedState_usesIsClosedQualifier() {
            String q = buildQuery("acme/platform", List.of(), "closed", "currentRepo");
            assertThat(q).contains("is:closed");
            assertThat(q).doesNotContain("is:open");
        }

        @Test
        void allState_omitsStateQualifier() {
            String q = buildQuery("acme/platform", List.of(), "all", "currentRepo");
            assertThat(q).doesNotContain("is:open");
            assertThat(q).doesNotContain("is:closed");
        }

        @Test
        void openState_usesIsOpenQualifier() {
            String q = buildQuery("acme/platform", List.of(), "open", "currentRepo");
            assertThat(q).contains("is:open");
            assertThat(q).doesNotContain("is:closed");
        }

        // ── Assigned-to-me filter ─────────────────────────────────────────────

        @Test
        void assignedToMe_scopeUsesAssigneeQualifier() {
            String q = buildQuery("acme/platform", List.of(), "open", "assigned");
            assertThat(q).contains("assignee:@me");
            assertThat(q).doesNotContain("author:@me");
            assertThat(q).doesNotContain("repo:acme/platform");
        }

        @Test
        void assignedToMe_noRepo_replacesAuthorMe() {
            String q = buildQuery("", List.of(), "open", "assigned");
            assertThat(q).contains("assignee:@me");
            assertThat(q).doesNotContain("author:@me");
        }

        // ── Review-requested filter ───────────────────────────────────────────

        @Test
        void reviewRequested_scopeUsesQualifier() {
            String q = buildQuery("acme/platform", List.of(), "open", "reviewRequested");
            assertThat(q).contains("review-requested:@me");
            assertThat(q).doesNotContain("repo:acme/platform");
        }

        @Test
        void reviewRequested_noRepo_replacesAuthorMe() {
            String q = buildQuery("", List.of(), "open", "reviewRequested");
            assertThat(q).contains("review-requested:@me");
            assertThat(q).doesNotContain("author:@me");
        }

        @Test
        void authored_scopeUsesAuthorMe() {
            String q = buildQuery("acme/platform", List.of(), "open", "authored");
            assertThat(q).contains("author:@me");
            assertThat(q).doesNotContain("repo:acme/platform");
        }

        @Test
        void unknownScopeFallsBackToCurrentRepo() {
            assertThat(buildQuery("acme/platform", List.of(), "open", "surprise"))
                    .isEqualTo("is:pr is:open draft:false repo:acme/platform");
        }

        // ── Helpers ──────────────────────────────────────────────────────────

        private static String buildQuery(String currentRepo, List<String> starred) {
            return PRToolWindowFactory.buildQuery(currentRepo, starred, "open", "currentRepo");
        }

        private static String buildQuery(
                String currentRepo, List<String> starred, String state, String searchScope) {
            return PRToolWindowFactory.buildQuery(currentRepo, starred, state, searchScope);
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
