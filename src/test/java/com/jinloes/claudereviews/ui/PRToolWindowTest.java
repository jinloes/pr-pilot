package com.jinloes.claudereviews.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.claudereviews.model.PullRequest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PRToolWindowTest {

    @Nested
    class PrHeaderHtml {

        private PullRequest pr(String title) {
            // PullRequest(title, htmlUrl, owner, repo, number, body, author, createdAt)
            return new PullRequest(
                    title,
                    "https://github.com/owner/repo/pull/42",
                    "owner",
                    "repo",
                    42,
                    "",
                    "author",
                    "2024-01-01T00:00:00Z");
        }

        @Test
        void titleExactly80CharsNotTruncated() {
            String title = "A".repeat(80);
            PullRequest pullRequest = pr(title);
            String html = PRToolWindow.prHeaderHtml(pullRequest);
            // The title should appear verbatim (no ellipsis — neither Unicode nor HTML entity)
            assertThat(html).contains(title);
            assertThat(html).doesNotContain("&hellip;");
            assertThat(html).doesNotContain("…");
        }

        @Test
        void title81CharsTruncatedTo77PlusEllipsis() {
            String title = "B".repeat(81);
            PullRequest pullRequest = pr(title);
            String html = PRToolWindow.prHeaderHtml(pullRequest);
            // The ellipsis "…" (U+2026) is HTML4-escaped to &hellip; by StringEscapeUtils
            assertThat(html).contains("B".repeat(77) + "&hellip;");
            // The full 78-char run should not appear
            assertThat(html).doesNotContain("B".repeat(78));
        }

        @Test
        void shortTitleNotTruncated() {
            String title = "Fix NPE in UserService";
            PullRequest pullRequest = pr(title);
            String html = PRToolWindow.prHeaderHtml(pullRequest);
            assertThat(html).contains(title);
            assertThat(html).doesNotContain("&hellip;");
        }
    }
}
