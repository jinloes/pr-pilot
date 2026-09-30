package com.jinloes.prpilot.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jinloes.prpilot.review.GitWorktreeService;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.file.PathUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LocalCheckoutTest {
    private Path root;
    private File clone;
    private String baseSha;
    private String prSha;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("local-checkout");
        Path work = root.resolve("work");
        Files.createDirectories(work);
        git(work, "init", "-q", "-b", "main");
        Files.writeString(work.resolve("A.java"), "class A {}\n");
        git(work, "add", "A.java");
        git(work, "commit", "-q", "-m", "base");
        baseSha = git(work, "rev-parse", "HEAD");
        Files.writeString(work.resolve("A.java"), "class A { int x; }\n");
        git(work, "commit", "-q", "-am", "change");
        prSha = git(work, "rev-parse", "HEAD");
        git(work, "reset", "-q", "--hard", baseSha);

        Path origin = root.resolve("origin.git");
        git(root, "clone", "-q", "--bare", work.toString(), origin.toString());
        git(root, "--git-dir=" + origin, "update-ref", "refs/pull/1/head", prSha);
        git(root, "clone", "-q", origin.toString(), root.resolve("clone").toString());
        clone = root.resolve("clone").toFile();
    }

    @AfterEach
    void tearDown() throws Exception {
        PathUtils.deleteDirectory(root);
    }

    @Nested
    class Open {
        @Test
        void checksOutTheReviewedCommitAndRendersItsDiff() throws Exception {
            GitWorktreeService worktrees = new GitWorktreeService();
            File worktree;
            try (LocalCheckout checkout = LocalCheckout.open(worktrees, clone, 1, baseSha, prSha)) {
                worktree = checkout.worktreeDir();
                assertThat(git(worktree.toPath(), "rev-parse", "HEAD")).isEqualTo(prSha);
                assertThat(checkout.diff().status()).isEqualTo("ok");
                assertThat(checkout.diff().truncated()).isFalse();
                assertThat(checkout.diff().diff())
                        .contains("diff --git a/A.java b/A.java")
                        .contains("+class A { int x; }");
            }
            assertThat(git(clone.toPath(), "worktree", "list")).doesNotContain(worktree.getPath());
        }

        @Test
        void rejectsAMalformedCommit() {
            assertThatThrownBy(
                            () ->
                                    LocalCheckout.open(
                                            new GitWorktreeService(), clone, 1, baseSha, "HEAD"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("reviewed commit");
        }

        @Test
        void failsWhenTheBaseCommitIsUnavailable() {
            String missing = "0".repeat(40);
            assertThatThrownBy(
                            () ->
                                    LocalCheckout.open(
                                            new GitWorktreeService(), clone, 1, missing, prSha))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("is not available");
        }
    }

    @Nested
    class CommitExists {
        @Test
        void reportsWhetherTheObjectIsACommit() throws Exception {
            assertThat(LocalCheckout.commitExists(clone, baseSha)).isTrue();
            assertThat(LocalCheckout.commitExists(clone, "f".repeat(40))).isFalse();
        }
    }

    private static String git(Path dir, String... args) throws Exception {
        List<String> command =
                new ArrayList<>(
                        List.of(
                                "git",
                                "-c",
                                "user.name=t",
                                "-c",
                                "user.email=t@example.com",
                                "-c",
                                "commit.gpgsign=false"));
        command.addAll(List.of(args));
        Process process =
                new ProcessBuilder(command)
                        .directory(dir.toFile())
                        .redirectErrorStream(true)
                        .start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IOException("git " + String.join(" ", args) + " failed: " + out);
        }
        return out.strip();
    }
}
