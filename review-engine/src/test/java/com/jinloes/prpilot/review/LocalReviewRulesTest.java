package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.review.LocalReviewRules.Rule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
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

    private static String yamlRule(String name, String trigger) {
        return "name: "
                + name
                + "\n"
                + "description: Checks "
                + name
                + "\n"
                + "trigger: "
                + trigger
                + "\n"
                + "prompt: |\n  Apply "
                + name
                + ".\n";
    }

    private List<Rule> load() {
        return LocalReviewRules.load(rules.toString());
    }

    @Nested
    class Load {
        @Test
        void returnsEmptyForUnsetRelativeOrMissingDirectories() {
            assertThat(LocalReviewRules.load(null)).isEmpty();
            assertThat(LocalReviewRules.load("  ")).isEmpty();
            assertThat(LocalReviewRules.load("relative/rules")).isEmpty();
            assertThat(LocalReviewRules.load(tempDir.resolve("absent").toString())).isEmpty();
        }

        @Test
        void parsesAStructuredYamlRule() throws IOException {
            write(
                    "team/null-safety.yaml",
                    "name: null-safety\n"
                            + "description: Flags nullable returns\n"
                            + "trigger: Java files changed\n"
                            + "prompt: |\n  Check null handling.\n"
                            + "severity: high\n"
                            + "model: ignored\n");

            assertThat(load())
                    .containsExactly(
                            new Rule(
                                    "null-safety",
                                    "team/null-safety.yaml",
                                    "Flags nullable returns",
                                    "Java files changed",
                                    "Check null handling."));
            assertThat(load().get(0).structured()).isTrue();
        }

        @Test
        void skipsDisabledRules() throws IOException {
            write("inactive.yaml", yamlRule("inactive", "always") + "enabled: false\n");
            write("off-text.yml", "enabled: \"false\"\nnotes: x\n");
            write("active.yaml", yamlRule("active", "always") + "enabled: true\n");

            assertThat(load()).extracting(Rule::name).containsExactly("active");
        }

        @Test
        void treatsIncompleteOrInvalidYamlAsUnstructuredByPath() throws IOException {
            write("a-no-trigger.yaml", "name: a\ndescription: d\nprompt: p\n");
            write("b-blank-prompt.yaml", "name: b\ndescription: d\ntrigger: t\nprompt: \"  \"\n");
            write("c-bad-name.yaml", yamlRule("-bad", "t"));
            write("d-long-name.yaml", yamlRule("n".repeat(65), "t"));
            write("e-broken.yaml", "name: [unclosed\n");
            write("f-list.yaml", "- one\n- two\n");
            write("g-notes.md", "  Prefer Optional.  \n");

            List<Rule> loaded = load();

            assertThat(loaded)
                    .extracting(Rule::name)
                    .containsExactly(
                            "a-no-trigger.yaml",
                            "b-blank-prompt.yaml",
                            "c-bad-name.yaml",
                            "d-long-name.yaml",
                            "e-broken.yaml",
                            "f-list.yaml",
                            "g-notes.md");
            assertThat(loaded).allSatisfy(rule -> assertThat(rule.structured()).isFalse());
            assertThat(loaded.get(6))
                    .isEqualTo(
                            new Rule("g-notes.md", "g-notes.md", null, null, "Prefer Optional."));
            assertThat(loaded.get(4).prompt()).isEqualTo("name: [unclosed");
        }

        @Test
        void acceptsASixtyFourCharacterName() throws IOException {
            String name = "n".repeat(64);
            write("long.yaml", yamlRule(name, "t"));

            assertThat(load()).extracting(Rule::name).containsExactly(name);
        }

        @Test
        void skipsADuplicateStructuredName() throws IOException {
            write("a.yaml", yamlRule("same", "first"));
            write("b.yaml", yamlRule("same", "second"));

            assertThat(load()).extracting(Rule::trigger).containsExactly("first");
        }

        @Test
        void ignoresOtherExtensionsBlankAndNonUtf8Files() throws IOException {
            write("notes.txt", "ignored");
            write("Rule.java", "ignored");
            write("blank.md", "   \n");
            Files.write(rules.resolve("binary.md"), new byte[] {(byte) 0xff, (byte) 0xfe, 0});
            write("kept.md", "kept");

            assertThat(load()).extracting(Rule::name).containsExactly("kept.md");
        }

        @Test
        void skipsSymbolicLinksInsideTheFolder() throws IOException {
            Path outside = Files.writeString(tempDir.resolve("secret.md"), "secret");
            Files.createSymbolicLink(rules.resolve("link.md"), outside);
            Path outsideDir = Files.createDirectory(tempDir.resolve("elsewhere"));
            Files.writeString(outsideDir.resolve("deep.md"), "deep");
            Files.createSymbolicLink(rules.resolve("dir"), outsideDir);
            write("real.md", "real");

            assertThat(load()).extracting(Rule::name).containsExactly("real.md");
        }

        @Test
        void followsALinkedRootFolder() throws IOException {
            write("a.md", "a");
            Path link = Files.createSymbolicLink(tempDir.resolve("linked"), rules);

            assertThat(LocalReviewRules.load(link.toString()))
                    .extracting(Rule::name)
                    .containsExactly("a.md");
        }

        @Test
        void keepsA20KbFileAndSkipsA33KbFile() throws IOException {
            write("big.md", "x".repeat(33 * 1024));
            write("medium.md", "m".repeat(20 * 1024));

            assertThat(load()).extracting(Rule::name).containsExactly("medium.md");
        }

        @Test
        void hasNoTotalSizeCap() throws IOException {
            String body = "y".repeat(LocalReviewRules.MAX_FILE_BYTES - 100);
            for (int i = 1; i <= 5; i++) write(i + ".md", body);

            assertThat(load()).hasSize(5);
        }

        @Test
        void keepsAtMostTheFileLimitInPathOrder() throws IOException {
            for (int i = 0; i < LocalReviewRules.MAX_FILES + 2; i++) {
                write(String.format("%03d.md", i), "rule " + i);
            }

            List<Rule> loaded = load();

            assertThat(loaded).hasSize(LocalReviewRules.MAX_FILES);
            assertThat(loaded.get(0).name()).isEqualTo("000.md");
        }

        @Test
        void ignoresFilesDeeperThanTheDepthLimit() throws IOException {
            write("a/b/c/d/deep.md", "too deep");
            write("a/b/c/shallow.md", "ok");

            assertThat(load()).extracting(Rule::name).containsExactly("a/b/c/shallow.md");
        }
    }
}
