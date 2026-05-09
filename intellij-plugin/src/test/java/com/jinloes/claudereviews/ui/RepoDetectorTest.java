package com.jinloes.claudereviews.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepoDetectorTest {

    @Nested
    class ParseOwnerRepo {

        @Test
        void httpsUrl_ownerAndRepoExtracted() {
            assertThat(RepoDetector.parseOwnerRepo("https://github.com/owner/repo"))
                    .isEqualTo("owner/repo");
        }

        @Test
        void httpsUrlWithDotGit_stripped() {
            assertThat(RepoDetector.parseOwnerRepo("https://github.com/owner/repo.git"))
                    .isEqualTo("owner/repo");
        }

        @Test
        void sshUrl_ownerAndRepoExtracted() {
            assertThat(RepoDetector.parseOwnerRepo("git@github.com:owner/repo.git"))
                    .isEqualTo("owner/repo");
        }

        @Test
        void sshUrlWithoutDotGit_ownerAndRepoExtracted() {
            assertThat(RepoDetector.parseOwnerRepo("git@github.com:owner/repo"))
                    .isEqualTo("owner/repo");
        }

        @Test
        void gheHttpsUrl_ownerAndRepoExtracted() {
            assertThat(RepoDetector.parseOwnerRepo("https://github.example.com/org/project.git"))
                    .isEqualTo("org/project");
        }

        @Test
        void sshUriWithPort_ownerAndRepoExtracted() {
            assertThat(
                            RepoDetector.parseOwnerRepo(
                                    "ssh://git@github.example.com:7999/org/project.git"))
                    .isEqualTo("org/project");
        }

        @Test
        void nullInput_returnsNull() {
            assertThat(RepoDetector.parseOwnerRepo(null)).isNull();
        }

        @Test
        void blankInput_returnsNull() {
            assertThat(RepoDetector.parseOwnerRepo("  ")).isNull();
        }

        @Test
        void malformedUrl_returnsNull() {
            assertThat(RepoDetector.parseOwnerRepo("not-a-url")).isNull();
        }
    }

    @Nested
    class DetectCurrentRepo {

        @Test
        void httpsRemote_ownerRepoReturned(@TempDir File tempDir) throws Exception {
            writeGitConfig(
                    tempDir, "[remote \"origin\"]\n\turl = https://github.com/myorg/myrepo.git\n");
            assertThat(RepoDetector.detectCurrentRepo(tempDir.getAbsolutePath()))
                    .isEqualTo("myorg/myrepo");
        }

        @Test
        void sshRemote_ownerRepoReturned(@TempDir File tempDir) throws Exception {
            writeGitConfig(
                    tempDir, "[remote \"origin\"]\n\turl = git@github.com:myorg/myrepo.git\n");
            assertThat(RepoDetector.detectCurrentRepo(tempDir.getAbsolutePath()))
                    .isEqualTo("myorg/myrepo");
        }

        @Test
        void noOriginRemote_returnsNull(@TempDir File tempDir) throws Exception {
            writeGitConfig(tempDir, "[remote \"upstream\"]\n\turl = https://github.com/a/b.git\n");
            assertThat(RepoDetector.detectCurrentRepo(tempDir.getAbsolutePath())).isNull();
        }

        @Test
        void noGitConfig_returnsNull(@TempDir File tempDir) {
            assertThat(RepoDetector.detectCurrentRepo(tempDir.getAbsolutePath())).isNull();
        }

        @Test
        void nullBasePath_returnsNull() {
            assertThat(RepoDetector.detectCurrentRepo(null)).isNull();
        }

        @Test
        void multipleRemotes_onlyOriginUsed(@TempDir File tempDir) throws Exception {
            writeGitConfig(
                    tempDir,
                    "[remote \"upstream\"]\n\turl = https://github.com/other/repo.git\n"
                            + "[remote \"origin\"]\n\turl = https://github.com/correct/repo.git\n");
            assertThat(RepoDetector.detectCurrentRepo(tempDir.getAbsolutePath()))
                    .isEqualTo("correct/repo");
        }

        @Test
        void subdirectoryOfGitRepo_walksUpToFindConfig(@TempDir File tempDir) throws Exception {
            writeGitConfig(
                    tempDir, "[remote \"origin\"]\n\turl = https://github.com/org/repo.git\n");
            File subDir = new File(tempDir, "module/src");
            subDir.mkdirs();
            assertThat(RepoDetector.detectCurrentRepo(subDir.getAbsolutePath()))
                    .isEqualTo("org/repo");
        }

        @Test
        void sshUriWithPort_ownerRepoReturned(@TempDir File tempDir) throws Exception {
            writeGitConfig(
                    tempDir,
                    "[remote \"origin\"]\n\turl = ssh://git@ghe.example.com:7999/org/repo.git\n");
            assertThat(RepoDetector.detectCurrentRepo(tempDir.getAbsolutePath()))
                    .isEqualTo("org/repo");
        }

        private void writeGitConfig(File baseDir, String content) throws Exception {
            File gitDir = new File(baseDir, ".git");
            Files.createDirectory(gitDir.toPath());
            FileUtils.writeStringToFile(
                    new File(gitDir, "config"), content, StandardCharsets.UTF_8);
        }
    }
}
