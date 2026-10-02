package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LocalReviewRulesTest {
    private Path tempDir;
    private Path rules;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("local-review-rules");
        rules = Files.createDirectory(tempDir.resolve("rules"));
    }

    @AfterEach
    void tearDown() throws IOException {
        try (Stream<Path> walk = Files.walk(tempDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    private Path write(String relative, String content) throws IOException {
        Path file = rules.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }

    @Nested
    class Read {
        @Test
        void returnsEmptyForUnsetRelativeOrMissingDirectories() {
            assertThat(LocalReviewRules.read(null)).isEmpty();
            assertThat(LocalReviewRules.read("  ")).isEmpty();
            assertThat(LocalReviewRules.read("relative/rules")).isEmpty();
            assertThat(LocalReviewRules.read(tempDir.resolve("absent").toString())).isEmpty();
        }

        @Test
        void readsRuleFilesSortedByPathUnderLocalRulesHeadings() throws IOException {
            write("b.yaml", "rule: b\n");
            write("a.md", "  Rule A  \n");
            write("nested/c.yml", "rule: c");

            String result = LocalReviewRules.read(rules.toString());

            assertThat(result)
                    .isEqualTo(
                            "## local-rules/a.md\nRule A\n\n"
                                    + "## local-rules/b.yaml\nrule: b\n\n"
                                    + "## local-rules/nested/c.yml\nrule: c");
        }

        @Test
        void ignoresOtherExtensionsBlankAndNonUtf8Files() throws IOException {
            write("notes.txt", "ignored");
            write("Rule.java", "ignored");
            write("blank.md", "   \n");
            Files.write(rules.resolve("binary.md"), new byte[] {(byte) 0xff, (byte) 0xfe, 0});
            write("kept.md", "kept");

            assertThat(LocalReviewRules.read(rules.toString()))
                    .isEqualTo("## local-rules/kept.md\nkept");
        }

        @Test
        void skipsSymbolicLinksInsideTheFolder() throws IOException {
            Path outside = Files.writeString(tempDir.resolve("secret.md"), "secret");
            Files.createSymbolicLink(rules.resolve("link.md"), outside);
            Path outsideDir = Files.createDirectory(tempDir.resolve("elsewhere"));
            Files.writeString(outsideDir.resolve("deep.md"), "deep");
            Files.createSymbolicLink(rules.resolve("dir"), outsideDir);
            write("real.md", "real");

            assertThat(LocalReviewRules.read(rules.toString()))
                    .isEqualTo("## local-rules/real.md\nreal");
        }

        @Test
        void followsALinkedRootFolder() throws IOException {
            write("a.md", "a");
            Path link = Files.createSymbolicLink(tempDir.resolve("linked"), rules);

            assertThat(LocalReviewRules.read(link.toString())).isEqualTo("## local-rules/a.md\na");
        }

        @Test
        void skipsFilesOverTheSizeLimit() throws IOException {
            write("big.md", "x".repeat(LocalReviewRules.MAX_FILE_BYTES + 1));
            write("small.md", "small");

            assertThat(LocalReviewRules.read(rules.toString()))
                    .isEqualTo("## local-rules/small.md\nsmall");
        }

        @Test
        void stopsAddingFilesOnceTheTotalBudgetIsSpent() throws IOException {
            String body = "y".repeat(LocalReviewRules.MAX_FILE_BYTES - 100);
            write("1.md", body);
            write("2.md", body);
            write("3.md", body);

            String result = LocalReviewRules.read(rules.toString());

            assertThat(result).contains("## local-rules/1.md", "## local-rules/2.md");
            assertThat(result).doesNotContain("## local-rules/3.md");
            assertThat(result.length()).isLessThanOrEqualTo(LocalReviewRules.MAX_TOTAL_BYTES);
        }

        @Test
        void ignoresFilesDeeperThanTheDepthLimit() throws IOException {
            write("a/b/c/d/deep.md", "too deep");
            write("a/b/c/shallow.md", "ok");

            assertThat(LocalReviewRules.read(rules.toString()))
                    .isEqualTo("## local-rules/a/b/c/shallow.md\nok");
        }
    }

    @Nested
    class AppendTo {
        @Test
        void joinsWithABlankLineAndHandlesEmptySides() {
            assertThat(LocalReviewRules.appendTo("g", "r")).isEqualTo("g\n\nr");
            assertThat(LocalReviewRules.appendTo("", "r")).isEqualTo("r");
            assertThat(LocalReviewRules.appendTo(null, "r")).isEqualTo("r");
            assertThat(LocalReviewRules.appendTo("g", "")).isEqualTo("g");
            assertThat(LocalReviewRules.appendTo(null, null)).isNull();
        }
    }
}
