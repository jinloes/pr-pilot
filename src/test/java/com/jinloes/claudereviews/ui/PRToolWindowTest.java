package com.jinloes.claudereviews.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.claudereviews.model.LineComment;
import com.jinloes.claudereviews.model.PullRequest;
import com.jinloes.claudereviews.model.ReviewResult;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PRToolWindowTest {

    @Nested
    class FormatPriorReview {

        @Test
        void includesVerdict() {
            ReviewResult result = new ReviewResult("", "APPROVE", List.of());
            String formatted = PRToolWindow.formatPriorReview(result);
            assertThat(formatted).contains("Verdict: APPROVE");
        }

        @Test
        void includesSummaryWhenPresent() {
            ReviewResult result = new ReviewResult("Summary text here", "COMMENT", List.of());
            String formatted = PRToolWindow.formatPriorReview(result);
            assertThat(formatted).contains("Summary:");
            assertThat(formatted).contains("Summary text here");
        }

        @Test
        void omitsSummaryWhenBlank() {
            ReviewResult result = new ReviewResult("", "APPROVE", List.of());
            String formatted = PRToolWindow.formatPriorReview(result);
            assertThat(formatted).doesNotContain("Summary:");
        }

        @Test
        void includesCommentsWhenPresent() {
            LineComment comment = new LineComment("Foo.java", 10, "issue", "Null check missing");
            ReviewResult result = new ReviewResult("", "REQUEST_CHANGES", List.of(comment));
            String formatted = PRToolWindow.formatPriorReview(result);
            assertThat(formatted).contains("Comments:");
            assertThat(formatted).contains("[ISSUE]");
            assertThat(formatted).contains("Foo.java:10");
            assertThat(formatted).contains("Null check missing");
        }

        @Test
        void omitsCommentsWhenEmpty() {
            ReviewResult result = new ReviewResult("", "APPROVE", List.of());
            String formatted = PRToolWindow.formatPriorReview(result);
            assertThat(formatted).doesNotContain("Comments:");
        }

        @Test
        void commentWithBlankFile_omitsFilePrefix() {
            LineComment comment = new LineComment("", 0, "note", "General note");
            ReviewResult result = new ReviewResult("", "COMMENT", List.of(comment));
            String formatted = PRToolWindow.formatPriorReview(result);
            assertThat(formatted).contains("[NOTE]");
            assertThat(formatted).contains("General note");
            // No "file:line —" prefix when file is blank
            assertThat(formatted).doesNotContain(":0 —");
        }

        @Test
        void multipleCommentsAllIncluded() {
            List<LineComment> comments =
                    List.of(
                            new LineComment("A.java", 1, "issue", "Bug"),
                            new LineComment("B.java", 2, "suggestion", "Improve this"));
            ReviewResult result = new ReviewResult("", "REQUEST_CHANGES", comments);
            String formatted = PRToolWindow.formatPriorReview(result);
            assertThat(formatted).contains("A.java:1");
            assertThat(formatted).contains("B.java:2");
        }

        @Test
        void commentWithASlashPrefix_prefixStripped() {
            LineComment comment = new LineComment("a/src/Foo.java", 5, "issue", "body");
            ReviewResult result = new ReviewResult("", "REQUEST_CHANGES", List.of(comment));
            String formatted = PRToolWindow.formatPriorReview(result);
            assertThat(formatted).contains("src/Foo.java:5");
            assertThat(formatted).doesNotContain("a/src/Foo.java");
        }

        @Test
        void commentWithBSlashPrefix_prefixStripped() {
            LineComment comment = new LineComment("b/src/Bar.java", 12, "suggestion", "body");
            ReviewResult result = new ReviewResult("", "COMMENT", List.of(comment));
            String formatted = PRToolWindow.formatPriorReview(result);
            assertThat(formatted).contains("src/Bar.java:12");
            assertThat(formatted).doesNotContain("b/src/Bar.java");
        }
    }

    @Nested
    class ParseOwnerRepo {

        @Test
        void httpsUrl_ownerAndRepoExtracted() {
            assertThat(PRToolWindow.parseOwnerRepo("https://github.com/owner/repo"))
                    .isEqualTo("owner/repo");
        }

        @Test
        void httpsUrlWithDotGit_stripped() {
            assertThat(PRToolWindow.parseOwnerRepo("https://github.com/owner/repo.git"))
                    .isEqualTo("owner/repo");
        }

        @Test
        void sshUrl_ownerAndRepoExtracted() {
            assertThat(PRToolWindow.parseOwnerRepo("git@github.com:owner/repo.git"))
                    .isEqualTo("owner/repo");
        }

        @Test
        void sshUrlWithoutDotGit_ownerAndRepoExtracted() {
            assertThat(PRToolWindow.parseOwnerRepo("git@github.com:owner/repo"))
                    .isEqualTo("owner/repo");
        }

        @Test
        void gheHttpsUrl_ownerAndRepoExtracted() {
            assertThat(PRToolWindow.parseOwnerRepo("https://github.example.com/org/project.git"))
                    .isEqualTo("org/project");
        }

        @Test
        void nullInput_returnsNull() {
            assertThat(PRToolWindow.parseOwnerRepo(null)).isNull();
        }

        @Test
        void blankInput_returnsNull() {
            assertThat(PRToolWindow.parseOwnerRepo("  ")).isNull();
        }

        @Test
        void malformedUrl_returnsNull() {
            assertThat(PRToolWindow.parseOwnerRepo("not-a-url")).isNull();
        }
    }

    @Nested
    class DetectCurrentRepo {

        @Test
        void httpsRemote_ownerRepoReturned(@TempDir File tempDir) throws Exception {
            writeGitConfig(
                    tempDir,
                    "[remote \"origin\"]\n\turl = https://github.com/myorg/myrepo.git\n");
            assertThat(PRToolWindow.detectCurrentRepo(tempDir.getAbsolutePath()))
                    .isEqualTo("myorg/myrepo");
        }

        @Test
        void sshRemote_ownerRepoReturned(@TempDir File tempDir) throws Exception {
            writeGitConfig(
                    tempDir, "[remote \"origin\"]\n\turl = git@github.com:myorg/myrepo.git\n");
            assertThat(PRToolWindow.detectCurrentRepo(tempDir.getAbsolutePath()))
                    .isEqualTo("myorg/myrepo");
        }

        @Test
        void noOriginRemote_returnsNull(@TempDir File tempDir) throws Exception {
            writeGitConfig(tempDir, "[remote \"upstream\"]\n\turl = https://github.com/a/b.git\n");
            assertThat(PRToolWindow.detectCurrentRepo(tempDir.getAbsolutePath())).isNull();
        }

        @Test
        void noGitConfig_returnsNull(@TempDir File tempDir) {
            assertThat(PRToolWindow.detectCurrentRepo(tempDir.getAbsolutePath())).isNull();
        }

        @Test
        void nullBasePath_returnsNull() {
            assertThat(PRToolWindow.detectCurrentRepo(null)).isNull();
        }

        @Test
        void multipleRemotes_onlyOriginUsed(@TempDir File tempDir) throws Exception {
            writeGitConfig(
                    tempDir,
                    "[remote \"upstream\"]\n\turl = https://github.com/other/repo.git\n"
                            + "[remote \"origin\"]\n\turl = https://github.com/correct/repo.git\n");
            assertThat(PRToolWindow.detectCurrentRepo(tempDir.getAbsolutePath()))
                    .isEqualTo("correct/repo");
        }

        private void writeGitConfig(File baseDir, String content) throws Exception {
            File gitDir = new File(baseDir, ".git");
            Files.createDirectory(gitDir.toPath());
            FileUtils.writeStringToFile(
                    new File(gitDir, "config"), content, StandardCharsets.UTF_8);
        }
    }

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
