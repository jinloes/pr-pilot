package com.jinloes.claudereviews.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PRToolWindowFactoryTest {

    @Nested
    class BuildQuery {

        @Test
        void noRepos_fallsBackToAuthorMe() {
            assertThat(PRToolWindowFactory.buildQuery("", List.of()))
                    .isEqualTo("is:pr is:open draft:false author:@me");
        }

        @Test
        void nullCurrentRepo_withNoStarred_fallsBackToAuthorMe() {
            assertThat(PRToolWindowFactory.buildQuery(null, List.of()))
                    .isEqualTo("is:pr is:open draft:false author:@me");
        }

        @Test
        void currentRepoOnly_buildsRepoQuery() {
            String q = PRToolWindowFactory.buildQuery("acme/platform", List.of());
            assertThat(q).isEqualTo("is:pr is:open draft:false repo:acme/platform");
        }

        @Test
        void starredOnly_noCurrentRepo_includesAllStarred() {
            String q = PRToolWindowFactory.buildQuery("", List.of("alice/foo", "bob/bar"));
            assertThat(q).contains("repo:alice/foo").contains("repo:bob/bar");
            assertThat(q).doesNotContain("author:@me");
        }

        @Test
        void currentRepoPrefixedBeforeStarred() {
            String q =
                    PRToolWindowFactory.buildQuery(
                            "acme/platform", List.of("acme/infra", "alice/foo"));
            int currentIdx = q.indexOf("repo:acme/platform");
            int starredIdx = q.indexOf("repo:acme/infra");
            assertThat(currentIdx).isLessThan(starredIdx);
        }

        @Test
        void currentRepoNotDuplicatedWhenInStarredList() {
            String q =
                    PRToolWindowFactory.buildQuery(
                            "acme/platform", List.of("acme/platform", "acme/infra"));
            long count = countOccurrences(q, "repo:acme/platform");
            assertThat(count).isEqualTo(1);
        }

        @Test
        void cappedAtTwentyRepos() {
            List<String> many = new java.util.ArrayList<>();
            for (int i = 0; i < 25; i++) many.add("user/repo" + i);
            String q = PRToolWindowFactory.buildQuery("", many);
            long repoCount = countOccurrences(q, "repo:");
            assertThat(repoCount).isEqualTo(20);
        }

        @Test
        void currentRepoCountsTowardCap() {
            List<String> starred = new java.util.ArrayList<>();
            for (int i = 0; i < 20; i++) starred.add("user/repo" + i);
            String q = PRToolWindowFactory.buildQuery("acme/platform", starred);
            long repoCount = countOccurrences(q, "repo:");
            assertThat(repoCount).isEqualTo(20);
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
