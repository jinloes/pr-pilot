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

class ReviewBenchCheckoutTest {
    private Path root;
    private Path mirror;
    private Path upstream;
    private String baseSha;
    private String headSha;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("reviewbench-checkout");
        Path work = root.resolve("work");
        Files.createDirectories(work);
        git(work, "init", "-q", "-b", "main");
        Files.writeString(work.resolve("a.py"), "x = 1\n");
        git(work, "add", "a.py");
        git(work, "commit", "-q", "-m", "base");
        baseSha = git(work, "rev-parse", "HEAD");
        Files.writeString(work.resolve("a.py"), "x = 2\n");
        git(work, "commit", "-q", "-am", "change");
        headSha = git(work, "rev-parse", "HEAD");

        // The upstream has the head commit; the mirror only has base, like a mirror whose PR
        // branch was force-pushed away.
        upstream = root.resolve("upstream.git");
        git(root, "clone", "-q", "--bare", work.toString(), upstream.toString());
        git(work, "reset", "-q", "--hard", baseSha);
        mirror = root.resolve("mirror.git");
        git(root, "clone", "-q", "--bare", "--no-local", work.toString(), mirror.toString());
        git(root, "--git-dir=" + mirror, "reflog", "expire", "--expire=now", "--all");
        git(root, "--git-dir=" + mirror, "gc", "-q", "--prune=now");
    }

    @AfterEach
    void tearDown() throws Exception {
        PathUtils.deleteDirectory(root);
    }

    private String url(Path repo) {
        return repo.toUri().toString();
    }

    @Nested
    class EnsureClone {
        @Test
        void clonesOnceAndReusesTheClone() throws Exception {
            Path repos = root.resolve("repos");

            File first = ReviewBenchCheckout.ensureClone(repos, "o_r", url(mirror));
            File second = ReviewBenchCheckout.ensureClone(repos, "o_r", "file:///does/not/exist");

            assertThat(first).isEqualTo(second).isEqualTo(repos.resolve("o_r").toFile());
            assertThat(git(first.toPath(), "cat-file", "-t", baseSha)).isEqualTo("commit");
            assertThat(repos.resolve("o_r.partial")).doesNotExist();
        }

        @Test
        void refusesToReuseAnInterruptedClone() throws Exception {
            Path repos = root.resolve("repos");
            Files.createDirectories(repos.resolve("o_r.partial"));

            assertThatThrownBy(() -> ReviewBenchCheckout.ensureClone(repos, "o_r", url(mirror)))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("interrupted");
        }

        @Test
        void failsWhenTheCloneFails() {
            Path repos = root.resolve("repos");

            assertThatThrownBy(
                            () ->
                                    ReviewBenchCheckout.ensureClone(
                                            repos, "o_r", url(root.resolve("missing.git"))))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("git clone");
        }
    }

    @Nested
    class Open {
        @Test
        void fetchesAMissingCommitFromUpstreamAndChecksOutHead() throws Exception {
            File clone = ReviewBenchCheckout.ensureClone(root.resolve("repos"), "o_r", url(mirror));
            File worktree;
            try (ReviewBenchCheckout checkout =
                    ReviewBenchCheckout.open(
                            new GitWorktreeService(),
                            clone,
                            7,
                            baseSha,
                            headSha,
                            url(upstream),
                            false)) {
                worktree = checkout.worktreeDir();
                assertThat(git(worktree.toPath(), "rev-parse", "HEAD")).isEqualTo(headSha);
                assertThat(Files.readString(worktree.toPath().resolve("a.py")))
                        .isEqualTo("x = 2\n");
                assertThat(checkout.diff().diff()).contains("-x = 1").contains("+x = 2");
            }
            assertThat(git(clone.toPath(), "worktree", "list")).doesNotContain(worktree.getPath());
        }

        @Test
        void failsWhenNoRemoteHasTheCommit() throws Exception {
            File clone = ReviewBenchCheckout.ensureClone(root.resolve("repos"), "o_r", url(mirror));

            assertThatThrownBy(
                            () ->
                                    ReviewBenchCheckout.open(
                                            new GitWorktreeService(),
                                            clone,
                                            7,
                                            baseSha,
                                            headSha,
                                            url(mirror),
                                            false))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining(headSha);
        }
    }

    static String git(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-c");
        command.add("user.name=Test");
        command.add("-c");
        command.add("user.email=test@example.com");
        command.add("-c");
        command.add("commit.gpgsign=false");
        command.addAll(List.of(args));
        Process process =
                new ProcessBuilder(command)
                        .directory(dir.toFile())
                        .redirectErrorStream(true)
                        .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IOException("git " + String.join(" ", args) + " failed: " + output);
        }
        return output.strip();
    }
}
