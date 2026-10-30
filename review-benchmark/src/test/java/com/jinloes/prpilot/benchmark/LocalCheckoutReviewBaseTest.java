package com.jinloes.prpilot.benchmark;

import static com.jinloes.prpilot.benchmark.LocalCheckoutTest.git;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.apache.commons.io.file.PathUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LocalCheckout#reviewBase} against a stacked PR whose parent was squash-merged
 * with different content, so the parent's commits never reach the default branch.
 */
class LocalCheckoutReviewBaseTest {
    private Path root;
    private File repo;
    private String parentTip;
    private String reviewed;
    private String mainTip;
    private String laterTip;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("review-base");
        Path work = root.resolve("work");
        Files.createDirectories(work);
        git(work, "init", "-q", "-b", "main");
        Files.writeString(work.resolve("A.java"), "class A {}\n");
        git(work, "add", "A.java");
        git(work, "commit", "-q", "-m", "base");

        git(work, "checkout", "-q", "-b", "stack");
        Files.writeString(work.resolve("B.java"), "class B {}\n");
        git(work, "add", "B.java");
        git(work, "commit", "-q", "-m", "parent PR");
        parentTip = git(work, "rev-parse", "HEAD");
        Files.writeString(work.resolve("A.java"), "class A { int x; }\n");
        git(work, "commit", "-q", "-am", "this PR");
        reviewed = git(work, "rev-parse", "HEAD");
        Files.writeString(work.resolve("C.java"), "class C {}\n");
        git(work, "add", "C.java");
        git(work, "commit", "-q", "-m", "after review");
        laterTip = git(work, "rev-parse", "HEAD");

        git(work, "checkout", "-q", "main");
        Files.writeString(work.resolve("B.java"), "class B { int revised; }\n");
        git(work, "add", "B.java");
        git(work, "commit", "-q", "-m", "squash-merged parent PR");
        mainTip = git(work, "rev-parse", "HEAD");
        repo = work.toFile();
    }

    @AfterEach
    void tearDown() throws Exception {
        PathUtils.deleteDirectory(root);
    }

    @Nested
    class ReviewBase {
        @Test
        void prefersTheParentTipOverASquashMergedDefaultBranch() throws Exception {
            LocalCheckout.Base base =
                    LocalCheckout.reviewBase(repo, reviewed, List.of(mainTip, parentTip));

            assertThat(base).isEqualTo(new LocalCheckout.Base(parentTip, 1));
        }

        @Test
        void keepsTheCurrentBaseWhenItIsTheOnlyCandidate() throws Exception {
            assertThat(LocalCheckout.reviewBase(repo, reviewed, List.of(mainTip)))
                    .isEqualTo(new LocalCheckout.Base(mainTip, 2));
        }

        @Test
        void skipsCandidatesThatAlreadyContainTheReviewedCommit() throws Exception {
            assertThat(LocalCheckout.reviewBase(repo, reviewed, List.of(laterTip, mainTip)))
                    .isEqualTo(new LocalCheckout.Base(mainTip, 2));
        }

        @Test
        void skipsMalformedAndUnavailableCandidates() throws Exception {
            List<String> candidates =
                    Arrays.asList(null, "HEAD", "0".repeat(40), mainTip, parentTip);

            assertThat(LocalCheckout.reviewBase(repo, reviewed, candidates))
                    .isEqualTo(new LocalCheckout.Base(parentTip, 1));
        }

        @Test
        void returnsNullWhenNoCandidateQualifies() throws Exception {
            assertThat(LocalCheckout.reviewBase(repo, reviewed, List.of(laterTip, "f".repeat(40))))
                    .isNull();
        }

        @Test
        void rejectsAMalformedReviewedCommit() {
            assertThatThrownBy(() -> LocalCheckout.reviewBase(repo, "HEAD", List.of(mainTip)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("reviewed commit");
        }
    }

    @Nested
    class Output {
        @Test
        void returnsTrimmedStdoutOrNullOnFailure() throws Exception {
            assertThat(LocalCheckout.output(repo, 15, "rev-parse", "HEAD")).isEqualTo(mainTip);
            assertThat(LocalCheckout.output(repo, 15, "rev-parse", "--verify", "nope^{commit}"))
                    .isNull();
        }
    }
}
