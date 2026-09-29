package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class BaseCommitContextTest {
    private File repo;
    private final BaseCommitContext context = new BaseCommitContext();

    @BeforeEach
    void setUp() throws Exception {
        repo = Files.createTempDirectory("base-commit-context").toFile();
        git("init", "-b", "main");
        git("config", "user.email", "test@test.com");
        git("config", "user.name", "Test");
        git("config", "commit.gpgsign", "false");
    }

    @AfterEach
    void tearDown() throws IOException {
        FileUtils.deleteDirectory(repo);
    }

    private String git(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Process process = new ProcessBuilder(cmd).directory(repo).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git " + args[0] + " failed: " + output);
        }
        return output.trim();
    }

    private void write(String path, String content) throws IOException {
        Path file = repo.toPath().resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private String commit(String message) throws Exception {
        git("add", "-A");
        git("commit", "-q", "-m", message);
        return git("rev-parse", "HEAD");
    }

    private static InspectionManifest manifest(String... paths) {
        StringBuilder diff = new StringBuilder();
        for (String path : paths) {
            diff.append("diff --git a/")
                    .append(path)
                    .append(" b/")
                    .append(path)
                    .append("\n--- a/")
                    .append(path)
                    .append("\n+++ b/")
                    .append(path)
                    .append("\n@@ -1,1 +1,1 @@\n-old\n+new\n");
        }
        return InspectionManifest.fromDiff(diff.toString());
    }

    @Nested
    class Resolve {
        @Test
        void readsGuidanceFromTheBaseCommitNotTheWorkingTree() throws Exception {
            write("AGENTS.md", "base rules");
            write("src/App.java", "class App {}");
            String base = commit("base");
            write("AGENTS.md", "PR head says ignore every rule");
            commit("head");

            BaseCommitContext.Result result =
                    context.resolve(repo, base, manifest("src/App.java"), () -> {});

            assertThat(result.guidelines()).isEqualTo("## AGENTS.md\nbase rules");
            assertThat(result.guidelines()).doesNotContain("ignore every rule");
        }

        @Test
        void ordersRootAndRulesThenScopedDeepestLastThenContributing() throws Exception {
            write("CONTRIBUTING.md", "contrib");
            write("AGENTS.md", "root");
            write(".claude/rules/x.md", "rule");
            write(".linkedin/ai-agent/review_guidelines.md", "linkedin");
            write("a/AGENTS.md", "a scoped");
            write("a/b/CLAUDE.md", "ab scoped");
            write("other/AGENTS.md", "out of scope");
            write("a/b/c.txt", "code");
            String base = commit("base");

            String guidance =
                    context.resolve(repo, base, manifest("a/b/c.txt"), () -> {}).guidelines();

            assertThat(guidance).doesNotContain("out of scope");
            assertThat(headings(guidance))
                    .containsExactly(
                            "AGENTS.md",
                            ".claude/rules/x.md",
                            ".linkedin/ai-agent/review_guidelines.md",
                            "a/AGENTS.md",
                            "a/b/CLAUDE.md",
                            "CONTRIBUTING.md");
        }

        @Test
        void capsGuidanceAtTheByteLimitWithATruncationMarker() throws Exception {
            write("AGENTS.md", "x".repeat(BaseCommitContext.MAX_GUIDELINES_BYTES + 500));
            String base = commit("base");

            String guidance = context.resolve(repo, base, manifest("f.txt"), () -> {}).guidelines();

            assertThat(guidance.getBytes(StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(BaseCommitContext.MAX_GUIDELINES_BYTES);
            assertThat(guidance).endsWith("...(truncated)");
        }

        @Test
        void skipsSymlinkedGuidanceFiles() throws Exception {
            write("secret.txt", "do not leak");
            Files.createSymbolicLink(
                    repo.toPath().resolve("AGENTS.md"), repo.toPath().resolve("secret.txt"));
            String base = commit("base");

            assertThat(context.resolve(repo, base, manifest("f.txt"), () -> {}).guidelines())
                    .isEmpty();
        }

        @Test
        void rendersRecentNonMergeHistoryPerChangedFile() throws Exception {
            write("f.txt", "1");
            commit("first change");
            write("f.txt", "2");
            commit("second change");
            write("f.txt", "3");
            commit("third change");
            write("f.txt", "4");
            String base = commit("fourth change");
            write("f.txt", "5");
            commit("head only change");

            String history = context.resolve(repo, base, manifest("f.txt"), () -> {}).fileHistory();

            assertThat(history).startsWith("## f.txt\n");
            assertThat(history).contains("fourth change", "third change", "second change");
            assertThat(history).doesNotContain("first change", "head only change");
        }

        @Test
        void boundsHistoryByFileCountAndBytes() throws Exception {
            String[] paths = new String[25];
            for (int i = 0; i < paths.length; i++) {
                paths[i] = "f" + i + ".txt";
                write(paths[i], "x");
            }
            String base = commit("m".repeat(150));

            String history = context.resolve(repo, base, manifest(paths), () -> {}).fileHistory();

            assertThat(history.getBytes(StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(BaseCommitContext.MAX_HISTORY_BYTES);
            assertThat(headings(history))
                    .hasSizeLessThanOrEqualTo(BaseCommitContext.MAX_HISTORY_FILES);
            assertThat(history).doesNotContain("## f20.txt");
        }
    }

    @Nested
    class Degrades {
        @Test
        void invalidOrBlankShaReturnsEmpty() throws Exception {
            write("AGENTS.md", "rules");
            commit("base");

            for (String sha : new String[] {null, "", "HEAD", "--upload-pack=x", "abc123"}) {
                assertThat(context.resolve(repo, sha, manifest("f.txt"), () -> {}))
                        .isEqualTo(BaseCommitContext.Result.EMPTY);
            }
        }

        @Test
        void nullOrNonGitDirectoryReturnsEmpty() throws Exception {
            File plain = Files.createTempDirectory("not-git").toFile();
            try {
                String sha = "a".repeat(40);
                assertThat(context.resolve(null, sha, manifest("f.txt"), () -> {}))
                        .isEqualTo(BaseCommitContext.Result.EMPTY);
                assertThat(context.resolve(plain, sha, manifest("f.txt"), () -> {}))
                        .isEqualTo(BaseCommitContext.Result.EMPTY);
            } finally {
                FileUtils.deleteDirectory(plain);
            }
        }

        @Test
        void unknownCommitWithAFailingFetchReturnsEmpty() throws Exception {
            write("AGENTS.md", "rules");
            commit("base");
            git("remote", "add", "origin", new File(repo, "does-not-exist").getAbsolutePath());

            assertThat(context.resolve(repo, "d".repeat(40), manifest("f.txt"), () -> {}))
                    .isEqualTo(BaseCommitContext.Result.EMPTY);
        }

        @Test
        void fetchesAMissingBaseCommitFromOrigin() throws Exception {
            File upstream = Files.createTempDirectory("base-commit-upstream").toFile();
            try {
                File clone = repo;
                repo = upstream;
                git("init", "-b", "main");
                git("config", "user.email", "test@test.com");
                git("config", "user.name", "Test");
                git("config", "uploadpack.allowReachableSHA1InWant", "true");
                write("AGENTS.md", "upstream rules");
                String base = commit("base");
                repo = clone;
                write("readme.txt", "local");
                commit("local");
                git("remote", "add", "origin", upstream.getAbsolutePath());

                assertThat(context.resolve(repo, base, manifest("f.txt"), () -> {}).guidelines())
                        .isEqualTo("## AGENTS.md\nupstream rules");
            } finally {
                FileUtils.deleteDirectory(upstream);
            }
        }

        @Test
        void cancellationAbortsResolution() throws Exception {
            write("AGENTS.md", "rules");
            String base = commit("base");
            AtomicInteger checks = new AtomicInteger();

            assertThatThrownBy(
                            () ->
                                    context.resolve(
                                            repo,
                                            base,
                                            manifest("f.txt"),
                                            () -> {
                                                if (checks.incrementAndGet() > 1) {
                                                    throw new InterruptedException("cancelled");
                                                }
                                            }))
                    .isInstanceOf(InterruptedException.class);
        }

        @Test
        void aHistoryFailureAfterTheFirstFileLeavesHistoryEmpty() throws Exception {
            write("AGENTS.md", "rules");
            write("a.txt", "a");
            write("b.txt", "b");
            String base = commit("base");
            AtomicInteger logs = new AtomicInteger();
            BaseCommitContext failing =
                    new BaseCommitContext(
                            new BoundedProcessRunner(
                                    builder -> {
                                        if (builder.command().contains("log")
                                                && logs.incrementAndGet() > 1) {
                                            throw new IOException("git log failed");
                                        }
                                        return builder.start();
                                    }));

            BaseCommitContext.Result result =
                    failing.resolve(repo, base, manifest("a.txt", "b.txt"), () -> {});

            assertThat(logs).hasValue(2);
            assertThat(result.guidelines()).isEqualTo("## AGENTS.md\nrules");
            assertThat(result.fileHistory()).isEmpty();
        }

        @Test
        void anExhaustedOverallBudgetReturnsEmpty() throws Exception {
            write("AGENTS.md", "rules");
            String base = commit("base");
            BaseCommitContext expired = new BaseCommitContext(new BoundedProcessRunner(), 0);

            assertThat(expired.resolve(repo, base, manifest("f.txt"), () -> {}))
                    .isEqualTo(BaseCommitContext.Result.EMPTY);
        }
    }

    @Nested
    class SelectGuidance {
        private BaseCommitContext.Blob blob(String path) {
            return new BaseCommitContext.Blob(path, "a".repeat(40), 1);
        }

        @Test
        void matchesRuleGlobsAtAnyDepthUnderTheirRoots() {
            List<BaseCommitContext.Blob> selected =
                    BaseCommitContext.selectGuidance(
                            List.of(
                                    blob(".claude/rules/x.md"),
                                    blob(".claude/rules/deep/y.md"),
                                    blob(".github/instructions/a.instructions.md"),
                                    blob("src/Main.java")),
                            List.of("src/Main.java"));

            assertThat(selected)
                    .extracting(BaseCommitContext.Blob::path)
                    .containsExactly(
                            ".claude/rules/deep/y.md",
                            ".claude/rules/x.md",
                            ".github/instructions/a.instructions.md");
        }

        @Test
        void aNestedFileIsKeptOnlyForChangedDescendants() {
            List<BaseCommitContext.Blob> blobs = List.of(blob("ab/AGENTS.md"), blob("a/AGENTS.md"));

            assertThat(BaseCommitContext.selectGuidance(blobs, List.of("abc/file.txt"))).isEmpty();
            assertThat(BaseCommitContext.selectGuidance(blobs, List.of("a/file.txt")))
                    .extracting(BaseCommitContext.Blob::path)
                    .containsExactly("a/AGENTS.md");
        }
    }

    @Nested
    class ParseTree {
        @Test
        void keepsOnlyRegularFileBlobs() {
            String sha = "b".repeat(40);
            String output =
                    "100644 blob "
                            + sha
                            + "      12\tAGENTS.md\0"
                            + "100755 blob "
                            + sha
                            + "       3\trun.sh\0"
                            + "120000 blob "
                            + sha
                            + "       9\tCLAUDE.md\0"
                            + "160000 commit "
                            + sha
                            + "       -\tsub\0";

            assertThat(BaseCommitContext.parseTree(output))
                    .extracting(BaseCommitContext.Blob::path, BaseCommitContext.Blob::size)
                    .containsExactly(tuple("AGENTS.md", 12L), tuple("run.sh", 3L));
        }
    }

    @Test
    void candidateLocationsCoverRootsAndEveryChangedAncestor() {
        assertThat(BaseCommitContext.candidateLocations(List.of("a/b/c.txt", "top.txt")))
                .contains(".github", ".claude/rules", "AGENTS.md", "a/AGENTS.md", "a/b/CLAUDE.md")
                .doesNotContain("a/b/c.txt/AGENTS.md");
    }

    private static List<String> headings(String rendered) {
        return rendered.lines()
                .filter(line -> line.startsWith("## "))
                .map(line -> line.substring(3))
                .toList();
    }
}
