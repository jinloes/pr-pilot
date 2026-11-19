package com.jinloes.prpilot.sidecar.pr;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.DiffCoverage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LocalPrDiffTest {
    private Path root;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("pr-pilot-local-diff");
        git(root, "init", "-q");
        git(root, "config", "user.name", "Test");
        git(root, "config", "user.email", "test@example.test");
        git(root, "config", "commit.gpgsign", "false");
        git(root, "config", "core.autocrlf", "false");
        git(root, "commit", "-qm", "root", "--allow-empty");
    }

    @AfterEach
    void tearDown() throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                path.toFile().setWritable(true);
                Files.delete(path);
            }
        }
    }

    @Nested
    class Render {
        @Test
        void reviewsOnlyCommittedPrChangesAndDisclosesOmittedFiles() throws Exception {
            String forkPoint = git(root, "rev-parse", "HEAD");
            Files.writeString(root.resolve("base-only.txt"), "base branch\n");
            git(root, "add", "base-only.txt");
            git(root, "commit", "-qm", "advance base");
            String base = git(root, "rev-parse", "HEAD");

            git(root, "checkout", "--detach", forkPoint);
            Files.writeString(root.resolve("feature.txt"), "committed change\n");
            Files.writeString(root.resolve("large.txt"), "large change\n".repeat(25_000));
            git(root, "add", "feature.txt", "large.txt");
            git(root, "commit", "-qm", "PR changes");
            String head = git(root, "rev-parse", "HEAD");
            Files.writeString(root.resolve("feature.txt"), "dirty checkout\n");

            var source = new PrDiffService.LocalSource(root.toString(), base, head);
            for (String mode : List.of("review", "validation")) {
                var result = LocalPrDiff.render(source, mode);
                assertThat(result.status()).isEqualTo("ok");
                assertThat(result.limitBytes())
                        .isEqualTo(
                                mode.equals("review")
                                        ? PrDiffService.REVIEW_LIMIT_BYTES
                                        : PrDiffService.VALIDATION_LIMIT_BYTES);
                var split = DiffCoverage.split(result.diff());
                assertThat(split.body())
                        .contains("+committed change")
                        .doesNotContain("base-only.txt", "dirty checkout");
                assertThat(split.coverage().paths()).containsExactly("large.txt");
            }
        }

        @Test
        void rejectsMismatchedHeadAndNonCommitArguments() throws Exception {
            String base = git(root, "rev-parse", "HEAD");
            git(root, "commit", "-qm", "different head", "--allow-empty");
            assertThat(
                            LocalPrDiff.render(
                                            new PrDiffService.LocalSource(
                                                    root.toString(), base, base),
                                            "review")
                                    .status())
                    .isEqualTo("api_failed");
            assertThat(
                            LocalPrDiff.render(
                                            new PrDiffService.LocalSource(
                                                    root.toString(), "HEAD", base),
                                            "review")
                                    .status())
                    .isEqualTo("invalid_request");
        }

        @Test
        void fetchesTheExactMissingBaseWithoutChangingHeadOrFetchHead() throws Exception {
            Path origin = root.resolve("origin");
            git(root, "clone", "-q", ".", origin.toString());
            Files.writeString(origin.resolve("base-only.txt"), "not part of the PR\n");
            git(origin, "add", "base-only.txt");
            git(
                    origin,
                    "-c",
                    "user.name=Test",
                    "-c",
                    "user.email=test@example.test",
                    "-c",
                    "commit.gpgsign=false",
                    "commit",
                    "-qm",
                    "advance base");
            String base = git(origin, "rev-parse", "HEAD");
            git(root, "remote", "add", "origin", origin.toString());
            Files.writeString(root.resolve("pr.txt"), "PR change\n");
            git(root, "add", "pr.txt");
            git(root, "commit", "-qm", "advance head");
            String head = git(root, "rev-parse", "HEAD");

            var result =
                    LocalPrDiff.render(
                            new PrDiffService.LocalSource(root.toString(), base, head), "review");

            assertThat(result.status()).isEqualTo("ok");
            assertThat(result.diff()).contains("+PR change").doesNotContain("base-only.txt");
            assertThat(git(root, "rev-parse", "HEAD")).isEqualTo(head);
            assertThat(root.resolve(".git").resolve("FETCH_HEAD")).doesNotExist();
        }
    }

    private static String git(Path dir, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process =
                new ProcessBuilder(command)
                        .directory(dir.toFile())
                        .redirectErrorStream(true)
                        .start();
        try {
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            String output =
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.exitValue()).withFailMessage(output).isZero();
            return output.stripTrailing();
        } finally {
            process.destroyForcibly();
        }
    }
}
