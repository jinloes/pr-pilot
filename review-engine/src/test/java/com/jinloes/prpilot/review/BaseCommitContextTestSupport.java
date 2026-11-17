package com.jinloes.prpilot.review;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/** Shared git-fixture setup and diff builders for {@code BaseCommitContext*Test} suites. */
abstract class BaseCommitContextTestSupport {

    File repo;
    final BaseCommitContext context = new BaseCommitContext();

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

    String git(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Process process = new ProcessBuilder(cmd).directory(repo).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git " + args[0] + " failed: " + output);
        }
        return output.trim();
    }

    void write(String path, String content) throws IOException {
        Path file = repo.toPath().resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    String commit(String message) throws Exception {
        git("add", "-A");
        git("commit", "-q", "-m", message);
        return git("rev-parse", "HEAD");
    }

    static InspectionManifest manifest(String... paths) {
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

    static InspectionManifest changedDeclaration(String path, String removedLine) {
        return InspectionManifest.fromDiff(
                "diff --git a/"
                        + path
                        + " b/"
                        + path
                        + "\n--- a/"
                        + path
                        + "\n+++ b/"
                        + path
                        + "\n@@ -1,1 +1,1 @@\n-"
                        + removedLine
                        + "\n+changed\n");
    }

    static List<String> headings(String rendered) {
        return rendered.lines()
                .filter(line -> line.startsWith("## "))
                .map(line -> line.substring(3))
                .toList();
    }
}
