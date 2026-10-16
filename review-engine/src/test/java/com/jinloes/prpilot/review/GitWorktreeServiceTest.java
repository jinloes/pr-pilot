package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Java port of the former core/jvmTest Kotest suite for GitWorktreeService. */
class GitWorktreeServiceTest {

    private final GitWorktreeService service = new GitWorktreeService();

    private static void git(File dir, String... args) throws IOException, InterruptedException {
        List<String> cmd = new java.util.ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Process process = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes(); // drain
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("git " + args[0] + " failed in " + dir);
        }
    }

    /** Returns the resolved HEAD commit of a repository or worktree. */
    private static String headSha(File dir) throws IOException, InterruptedException {
        Process process =
                new ProcessBuilder("git", "rev-parse", "HEAD")
                        .directory(dir)
                        .redirectErrorStream(true)
                        .start();
        String output =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git rev-parse failed in " + dir + ": " + output);
        }
        return output;
    }

    private static String worktreeList(File dir) throws IOException, InterruptedException {
        Process process =
                new ProcessBuilder("git", "worktree", "list", "--porcelain")
                        .directory(dir)
                        .redirectErrorStream(true)
                        .start();
        String output =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git worktree list failed in " + dir + ": " + output);
        }
        return output;
    }

    /**
     * Returns the canonical paths of the worktrees registered in {@code dir}'s repository. git
     * prints forward-slash paths on Windows and resolved symlinks on macOS, so both sides of a
     * comparison must go through {@link File#getCanonicalFile()}.
     */
    private static List<File> registeredWorktrees(File dir)
            throws IOException, InterruptedException {
        List<File> worktrees = new ArrayList<>();
        for (String line : worktreeList(dir).split("\\R")) {
            if (line.startsWith("worktree ")) {
                worktrees.add(new File(line.substring("worktree ".length())).getCanonicalFile());
            }
        }
        return worktrees;
    }

    private static String headShaUnchecked(File dir) {
        try {
            return headSha(dir);
        } catch (IOException | InterruptedException exception) {
            throw new AssertionError(exception);
        }
    }

    /** Initializes a minimal git repository with one commit on `main`. */
    private static void initBareRepo(File dir) throws IOException, InterruptedException {
        git(dir, "init", "-b", "main");
        git(dir, "config", "user.email", "test@test.com");
        git(dir, "config", "user.name", "Test");
        Files.writeString(new File(dir, "hello.txt").toPath(), "hello");
        git(dir, "add", ".");
        git(dir, "commit", "-m", "initial");
        // Point origin at the repo itself so `git fetch origin main` works locally.
        git(dir, "remote", "add", "origin", dir.getAbsolutePath());
    }

    @Nested
    class FindGitRoot {

        private File tmpDir;

        @BeforeEach
        void setUp() throws IOException {
            tmpDir = Files.createTempDirectory("gw-test-root").toFile();
        }

        @AfterEach
        void tearDown() throws IOException {
            FileUtils.deleteDirectory(tmpDir);
        }

        @Test
        void returnsNullForDirectoryWithNoGitAncestor() {
            assertThat(service.findGitRoot(tmpDir)).isNull();
        }

        @Test
        void returnsTheDirectoryItselfWhenGitIsAChild() throws IOException {
            new File(tmpDir, ".git").mkdirs();
            assertThat(service.findGitRoot(tmpDir)).isEqualTo(tmpDir.getCanonicalFile());
        }

        @Test
        void findsGitInAParentDirectory() throws IOException {
            new File(tmpDir, ".git").mkdirs();
            File nested = new File(tmpDir, "a/b/c");
            nested.mkdirs();
            assertThat(service.findGitRoot(nested)).isEqualTo(tmpDir.getCanonicalFile());
        }

        @Test
        void acceptsGitAsAFileGitWorktreeMetadata() throws IOException {
            Files.writeString(
                    new File(tmpDir, ".git").toPath(), "gitdir: ../../.git/worktrees/foo");
            assertThat(service.findGitRoot(tmpDir)).isEqualTo(tmpDir.getCanonicalFile());
        }
    }

    @Nested
    class CreateWorktree {

        private File repoDir;
        private File worktreeDir;

        @BeforeEach
        void setUp() throws Exception {
            repoDir = Files.createTempDirectory("gw-repo").toFile();
            worktreeDir = Files.createTempDirectory("gw-wt").toFile();
            worktreeDir.delete();
            initBareRepo(repoDir);
        }

        @AfterEach
        void tearDown() throws IOException {
            FileUtils.deleteDirectory(worktreeDir);
            FileUtils.deleteDirectory(repoDir);
        }

        @Test
        void createsAWorktreeOnAnExistingBranch() throws IOException {
            service.createWorktree(repoDir, "main", headShaUnchecked(repoDir), worktreeDir);
            assertThat(worktreeDir).exists();
            assertThat(new File(worktreeDir, "hello.txt")).exists();
        }

        @Test
        void throwsIOExceptionForANonExistentBranch() {
            assertThatThrownBy(
                            () ->
                                    service.createWorktree(
                                            repoDir,
                                            "does-not-exist",
                                            headShaUnchecked(repoDir),
                                            worktreeDir))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("git fetch");
        }

        /**
         * The point of pinning: the reviewed commit is checked out even after the branch moves on,
         * so the agent greps the code the diff was rendered from.
         */
        @Test
        void checksOutTheReviewedCommitEvenAfterTheBranchTipMoves() throws Exception {
            String reviewed = headSha(repoDir);
            Files.writeString(new File(repoDir, "pushed-later.txt").toPath(), "later");
            git(repoDir, "add", ".");
            git(repoDir, "commit", "-m", "pushed after review started");

            service.createWorktree(repoDir, "main", reviewed, worktreeDir);

            assertThat(new File(worktreeDir, "hello.txt")).exists();
            assertThat(new File(worktreeDir, "pushed-later.txt")).doesNotExist();
            assertThat(headSha(worktreeDir)).isEqualTo(reviewed);
        }

        @Test
        void failsWhenTheReviewedCommitIsNotAvailable() {
            assertThatThrownBy(
                            () ->
                                    service.createWorktree(
                                            repoDir,
                                            "main",
                                            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                            worktreeDir))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unavailable");
        }

        @Test
        void failsWhenNoShaIsSupplied() {
            assertThatThrownBy(() -> service.createWorktree(repoDir, "main", "  ", worktreeDir))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("missing");
        }

        @Test
        void passesOptionShapedBranchesAfterTheOptionTerminator() throws IOException {
            class CapturingGitService extends GitWorktreeService {
                private List<String> arguments;

                @Override
                void runGit(File dir, long timeoutSeconds, String... args) {
                    arguments = List.of(args);
                }
            }
            CapturingGitService capturingService = new CapturingGitService();

            capturingService.fetch(repoDir, 60, "origin", "--upload-pack=/bin/false");
            assertThat(capturingService.arguments)
                    .containsExactly("fetch", "origin", "--", "--upload-pack=/bin/false");

            capturingService.fetch(repoDir, 60, "origin", "--depth=1");
            assertThat(capturingService.arguments)
                    .containsExactly("fetch", "origin", "--", "--depth=1");
        }

        @Test
        void rejectsBlankOrControlCharacterBranchesBeforeInvokingGit() {
            assertThatThrownBy(() -> service.fetch(repoDir, 60, "origin", " "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("branch is invalid");
            assertThatThrownBy(() -> service.fetch(repoDir, 60, "origin", "feature\nmain"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("branch is invalid");
        }
    }

    @Nested
    class PinnedCommitish {

        private File repoDir;

        @BeforeEach
        void setUp() throws Exception {
            repoDir = Files.createTempDirectory("gw-pin").toFile();
            initBareRepo(repoDir);
        }

        @AfterEach
        void tearDown() throws IOException {
            FileUtils.deleteDirectory(repoDir);
        }

        @Test
        void returnsTheShaWhenTheCommitIsPresent() throws Exception {
            String sha = headSha(repoDir);
            assertThat(service.pinnedCommitish(repoDir, sha)).isEqualTo(sha);
        }

        @Test
        void acceptsAnAbbreviatedSha() throws Exception {
            String abbreviated = headSha(repoDir).substring(0, 8);
            assertThat(service.pinnedCommitish(repoDir, abbreviated)).isEqualTo(abbreviated);
        }

        @Test
        void failsWhenTheShaIsBlank() {
            assertThatThrownBy(() -> service.pinnedCommitish(repoDir, ""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("missing");
            assertThatThrownBy(() -> service.pinnedCommitish(repoDir, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("missing");
        }

        @Test
        void failsWhenTheCommitIsUnknownLocally() {
            assertThatThrownBy(
                            () ->
                                    service.pinnedCommitish(
                                            repoDir, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unavailable");
        }

        /**
         * headSha arrives from the GitHub API, so a value that git could read as an option or a
         * different revision must never reach the command line.
         */
        @Test
        void refusesRevisionsThatAreNotHexObjectNames() throws Exception {
            // A real ref that resolves, but is not an object name — must still be rejected.
            assertThatThrownBy(() -> service.pinnedCommitish(repoDir, "main"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("invalid");
            assertThatThrownBy(() -> service.pinnedCommitish(repoDir, "--upload-pack=touch /tmp/x"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("invalid");
            assertThatThrownBy(() -> service.pinnedCommitish(repoDir, "HEAD"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("invalid");
            assertThatThrownBy(() -> service.pinnedCommitish(repoDir, "abc"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("invalid");
        }
    }

    @Nested
    class RemoveWorktree {

        private File repoDir;
        private File worktreeDir;

        @BeforeEach
        void setUp() throws Exception {
            repoDir = Files.createTempDirectory("gw-repo-rm").toFile();
            worktreeDir = Files.createTempDirectory("gw-wt-rm").toFile();
            worktreeDir.delete();
            initBareRepo(repoDir);
        }

        @AfterEach
        void tearDown() throws IOException {
            FileUtils.deleteDirectory(worktreeDir);
            FileUtils.deleteDirectory(repoDir);
        }

        @Test
        void removesAWorktreeThatWasCreated() throws IOException {
            service.createWorktree(repoDir, "main", headShaUnchecked(repoDir), worktreeDir);
            assertThat(worktreeDir).exists();
            assertThat(service.removeWorktree(repoDir, worktreeDir)).isTrue();
            assertThat(worktreeDir).doesNotExist();
        }

        @Test
        void reportsAnAlreadyAbsentWorktreeAsRemoved() {
            File nonExistent = new File(repoDir, "no-such-wt");
            assertThat(service.removeWorktree(repoDir, nonExistent)).isTrue();
        }

        @Test
        void removesRegistrationWhenTheWorktreeDirectoryWasDeleted() throws Exception {
            service.createWorktree(repoDir, "main", headSha(repoDir), worktreeDir);
            File registration = worktreeDir.getCanonicalFile();
            assertThat(registeredWorktrees(repoDir)).contains(registration);
            FileUtils.deleteDirectory(worktreeDir);

            assertThat(service.removeWorktree(repoDir, worktreeDir)).isTrue();

            assertThat(registeredWorktrees(repoDir)).doesNotContain(registration);
        }

        @Test
        void reportsGitRemovalFailure() {
            class FailingGitService extends GitWorktreeService {
                @Override
                void runGit(File dir, long timeoutSeconds, String... args) throws IOException {
                    throw new IOException("removal failed");
                }
            }
            assertThat(worktreeDir.mkdir()).isTrue();

            assertThat(new FailingGitService().removeWorktree(repoDir, worktreeDir)).isFalse();
        }
    }

    @Nested
    class RunGit {

        private File tmpDir;

        @BeforeEach
        void setUp() throws IOException {
            tmpDir = Files.createTempDirectory("gw-run").toFile();
        }

        @AfterEach
        void tearDown() throws IOException {
            FileUtils.deleteDirectory(tmpDir);
        }

        @Test
        void throwsIOExceptionWithExitCodeOnNonZeroGitCommand() {
            assertThatThrownBy(
                            () ->
                                    service.runGit(
                                            tmpDir,
                                            10,
                                            "rev-parse",
                                            "--verify",
                                            "nonexistent-ref-xyz"))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("git rev-parse");
        }

        @Test
        void timeoutTerminatesAProcessThatKeepsOutputOpen() {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Process spawnedProcess;
            try {
                spawnedProcess =
                        new ProcessBuilder(
                                        Path.of(System.getProperty("java.home"), "bin", "java")
                                                .toString(),
                                        "-cp",
                                        System.getProperty("java.class.path"),
                                        SleepFixture.class.getName())
                                .start();
            } catch (IOException exception) {
                throw new AssertionError(exception);
            }
            GitWorktreeService hangingService =
                    new GitWorktreeService(
                            new BoundedProcessRunner(
                                    ignored -> spawnedProcess,
                                    executor,
                                    BoundedProcessRunner.DEFAULT_MAX_OUTPUT_BYTES));
            try {
                assertThatThrownBy(() -> hangingService.runGit(tmpDir, 1, "status"))
                        .isInstanceOf(IOException.class)
                        .hasMessageContaining("git status timed out after 1s");
                assertThat(spawnedProcess.isAlive()).isFalse();
            } finally {
                executor.shutdownNow();
                if (spawnedProcess.isAlive()) {
                    spawnedProcess.destroyForcibly();
                }
            }
        }
    }

    /** Child process that keeps its output open until killed, without needing a POSIX shell. */
    public static class SleepFixture {
        public static void main(String[] args) throws InterruptedException {
            Thread.sleep(30_000);
        }
    }
}
