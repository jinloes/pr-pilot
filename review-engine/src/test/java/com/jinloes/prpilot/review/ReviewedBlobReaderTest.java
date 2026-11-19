package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewedBlobReaderTest {
    @TempDir Path repo;

    @Test
    void opensCommittedTextAtTheReviewedLineDespiteLaterEdits() throws Exception {
        git("init");
        git("config", "core.autocrlf", "false");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "Test");
        git("config", "commit.gpgsign", "false");
        Files.writeString(repo.resolve("source.txt"), "first\nreviewed\n");
        Files.write(repo.resolve("invalid.txt"), new byte[] {(byte) 0xff});
        Files.write(repo.resolve("binary"), new byte[] {0, 1, 2});
        Files.writeString(repo.resolve("large.txt"), "x".repeat(1024 * 1024 + 1));
        git("add", "--", "source.txt", "invalid.txt", "binary", "large.txt");
        git("commit", "-m", "Reviewed");
        String reviewed = git("rev-parse", "HEAD");
        Files.writeString(repo.resolve("source.txt"), "new\nfirst\nreviewed\n");
        git("commit", "-am", "Move the reviewed line");
        Files.writeString(repo.resolve("source.txt"), "dirty\n");

        var reader = new ReviewedBlobReader();
        assertThat(reader.read(repo.toString(), reviewed, "source.txt", 2))
                .isEqualTo("first\nreviewed\n");
        assertThat(reader.read(repo.toString(), git("rev-parse", "HEAD"), "source.txt", 3))
                .isEqualTo("new\nfirst\nreviewed\n");
        for (String file :
                List.of("../source.txt", "not-present", "invalid.txt", "binary", "large.txt")) {
            assertThatThrownBy(() -> reader.read(repo.toString(), reviewed, file, 1))
                    .isInstanceOf(IOException.class);
        }
        assertThatThrownBy(() -> reader.read(repo.toString(), reviewed, "source.txt", 4))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("line is unavailable");
    }

    private String git(String... args) throws Exception {
        var command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process =
                new ProcessBuilder(command)
                        .directory(repo.toFile())
                        .redirectErrorStream(true)
                        .start();
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).withFailMessage(output).isZero();
        return output.stripTrailing();
    }
}
