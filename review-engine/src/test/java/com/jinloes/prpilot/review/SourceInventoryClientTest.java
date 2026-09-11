package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.model.SourceInventory;
import com.jinloes.prpilot.model.SourceInventory.Content;
import com.jinloes.prpilot.model.SourceInventory.Coverage;
import com.jinloes.prpilot.model.SourceInventory.Discovery;
import com.jinloes.prpilot.model.SourceInventory.Entry;
import com.jinloes.prpilot.model.SourceInventory.Epochs;
import com.jinloes.prpilot.model.SourceInventory.Hash;
import com.jinloes.prpilot.model.SourceInventory.Location;
import com.jinloes.prpilot.model.SourceInventory.Membership;
import com.jinloes.prpilot.model.SourceInventory.Model;
import com.jinloes.prpilot.model.SourceInventory.Operation;
import com.jinloes.prpilot.model.SourceInventory.Response;
import com.jinloes.prpilot.model.SourceInventory.Scope;
import com.jinloes.prpilot.model.SourceInventory.Source;
import com.jinloes.prpilot.model.SourceInventory.Status;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Real collect/process/Git tests. The subprocess simulates MCP, not a live native index. */
public class SourceInventoryClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private Path fixture, root, config, executable, log;
    private String fixtureClasspath;
    private String head;

    @BeforeEach
    void setup() throws Exception {
        fixture = Files.createTempDirectory("client-inventory-").toRealPath();
        root = Files.createDirectory(fixture.resolve("repo"));
        config = fixture.resolve("config.json");
        log = fixture.resolve("calls");
        executable = fixture.resolve("ijctl");
        String classpath =
                Stream.of(
                                SourceInventoryClientTest.class,
                                SourceInventoryClient.class,
                                SourceInventory.class,
                                ObjectMapper.class,
                                JsonFactory.class,
                                JsonProperty.class,
                                Test.class,
                                org.assertj.core.api.Assertions.class)
                        .map(
                                type ->
                                        Path.of(
                                                        type.getProtectionDomain()
                                                                .getCodeSource()
                                                                .getLocation()
                                                                .getPath())
                                                .toString())
                        .distinct()
                        .collect(Collectors.joining(java.io.File.pathSeparator));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        fixtureClasspath = classpath;
        Files.writeString(
                executable,
                "#!/bin/sh\nexec "
                        + quote(java)
                        + " -cp "
                        + quote(classpath)
                        + " "
                        + quote(Fixture.class.getName())
                        + " \"$@\"\n");
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        git(root, "init", "--quiet");
        git(root, "config", "user.email", "fixture@example.invalid");
        git(root, "config", "user.name", "Inventory fixture");
        git(root, "config", "commit.gpgsign", "false");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/extensionless"), "tracked source");
        Files.writeString(root.resolve(".gitignore"), "generated\n");
        git(root, "add", ".");
        git(root, "commit", "--quiet", "-m", "fixture");
        head = git(root, "rev-parse", "HEAD").strip();
        Files.createDirectories(root.resolve(".idea"));
        Files.writeString(root.resolve(".idea/workspace.xml"), "untracked non-source metadata");
        scenario("baseline");
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private void scenario(String name) throws IOException {
        Files.writeString(
                config, JSON.writeValueAsString(Map.of("scenario", name, "log", log.toString())));
    }

    private SourceInventoryClient client() throws IOException {
        return new SourceInventoryClient(launch(config));
    }

    private SourceInventoryClient.Launch launch(Path configuration) {
        return new SourceInventoryClient.Launch(
                Path.of(System.getProperty("java.home"), "bin", "java"),
                Path.of(System.getProperty("sourceInventory.workerJar")),
                Path.of("/usr/bin/git"),
                executable,
                configuration,
                "fixture-server",
                fixture,
                "/usr/bin:/bin");
    }

    /** Shared fixture for the actual SDK-wrapper regression, without packaging any test code. */
    public static void exerciseWrappedCaller(java.util.function.Function<Path, Path> wrap)
            throws Exception {
        var test = new SourceInventoryClientTest();
        test.setup();
        try {
            Path wrapped = wrap.apply(test.root);
            assertThat(wrapped.getFileSystem().provider())
                    .isNotEqualTo(test.root.getFileSystem().provider());
            var result = test.client().collect(wrapped, test.head);
            assertThat(result.files())
                    .extracting(Hash::getPath)
                    .containsExactly("src/extensionless");
            assertThat(result.files().get(0).getSha256())
                    .isEqualTo(
                            SourceInventoryFiles.hash(
                                            test.root,
                                            "src/extensionless",
                                            new SourceInventoryFiles.Budget(),
                                            null)
                                    .sha256());
            test.scenario("omitted");
            assertThatThrownBy(() -> test.client().collect(wrapped, test.head))
                    .hasMessageContaining("INVENTORY_CHANGED");
        } finally {
            test.cleanup();
        }
    }

    @AfterEach
    void cleanup() throws IOException {
        if (Files.exists(log)) {
            for (String path : Files.readAllLines(log)) {
                assertThat(Files.exists(Path.of(path)))
                        .as("request temporary deleted on every outcome")
                        .isFalse();
                assertThat(Files.exists(Path.of(path).getParent())).isFalse();
            }
        }
        try (var paths = Files.walk(fixture)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static String git(Path root, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().keySet().removeIf(key -> key.startsWith("GIT_"));
        Process process = builder.start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Fixture Git timeout");
        }
        String output =
                new String(
                        process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IOException("Fixture Git: " + output);
        }
        return output;
    }

    @Nested
    class Collect {
        @Test
        void settingsAreForkedWithoutAnyMcpCallAndBindAbsentPaths() throws Exception {
            Path setting =
                    Files.writeString(fixture.resolve("external-build.gradle"), "plugins {}");
            Path absent = fixture.resolve("missing.properties");
            Path wrapper = root.resolve("gradle/wrapper/gradle-wrapper.properties");
            Path homeSetting = fixture.resolve("private-home/.gradle/gradle.properties");
            List<String> paths =
                    java.util.stream.Stream.of(
                                    setting.toString(),
                                    absent.toString(),
                                    wrapper.toString(),
                                    homeSetting.toString())
                            .sorted(SourceInventory.UTF8)
                            .toList();
            var result = client().captureSettings(root, paths);
            assertThat(result.projectPath()).isEqualTo(root.toString());
            assertThat(result.files())
                    .extracting(SourceInventoryFiles.Setting::path)
                    .containsExactlyElementsOf(paths);
            assertThat(result.files()).filteredOn(SourceInventoryFiles.Setting::present).hasSize(1);
            assertThat(result.files())
                    .filteredOn(file -> !file.present())
                    .allSatisfy(
                            file -> {
                                assertThat(file.sha256()).isNull();
                                assertThat(file.physicalIdentity()).matches("[a-f0-9]{64}");
                            });
            assertThat(root.resolve("gradle")).doesNotExist();
            assertThat(fixture.resolve("private-home")).doesNotExist();
            assertThat(Files.exists(log)).isFalse();
            assertThat(client().captureSettings(root, paths)).isEqualTo(result);
            Files.createDirectory(root.resolve("gradle"));
            var ancestorCreated = client().captureSettings(root, paths);
            assertThat(ancestorCreated).isNotEqualTo(result);
            assertThat(client().captureSettings(root, paths)).isEqualTo(ancestorCreated);
            Files.delete(root.resolve("gradle"));
            var ancestorRemoved = client().captureSettings(root, paths);
            assertThat(ancestorRemoved).isNotEqualTo(ancestorCreated).isNotEqualTo(result);
            assertThat(client().captureSettings(root, paths)).isEqualTo(ancestorRemoved);
            Files.writeString(setting, "changed");
            Files.writeString(setting, "plugins {}");
            assertThat(client().captureSettings(root, paths)).isNotEqualTo(result);
            Files.delete(setting);
            Files.createSymbolicLink(setting, root.resolve("src/extensionless"));
            assertThatThrownBy(() -> client().captureSettings(root, paths))
                    .hasMessageContaining("UNSAFE_PATH");
            assertThat(Files.exists(log)).isFalse();
        }

        @Test
        void crossCollectionIdentityIgnoresMetadataContentsButDetectsSourceReplacement()
                throws Exception {
            var first = client().collect(root, head);
            Files.writeString(root.resolve(".idea/workspace.xml"), "different metadata");
            assertThat(client().collect(root, head).physicalFingerprint())
                    .isEqualTo(first.physicalFingerprint());
            Path source = root.resolve("src/extensionless");
            String bytes = Files.readString(source);
            Files.delete(source);
            Files.writeString(source, bytes);
            assertThat(client().collect(root, head).physicalFingerprint())
                    .isNotEqualTo(first.physicalFingerprint());
        }

        @Test
        void strictWorkerEnvelopeRejectsEveryUnboundOrPartialSuccess() throws Exception {
            var coverage = client().collect(root, head);
            String nonce = UUID.randomUUID().toString();
            var envelope = JSON.createObjectNode();
            envelope.put("schemaVersion", 2);
            envelope.put("nonce", nonce);
            envelope.put("status", "COVERED");
            envelope.putNull("code");
            envelope.set("result", JSON.valueToTree(coverage));
            var good =
                    new BoundedProcessRunner.ProcessResult(
                            0, JSON.writeValueAsString(envelope), false);
            assertThat(SourceInventoryClient.decodeWorker(good, root, head, nonce).files())
                    .hasSize(1);
            var mutations = new ArrayList<com.fasterxml.jackson.databind.node.ObjectNode>();
            mutations.add(envelope.deepCopy().put("unknown", 1));
            mutations.add(envelope.deepCopy().put("schemaVersion", "1"));
            mutations.add(envelope.deepCopy().put("schemaVersion", 1));
            mutations.add(envelope.deepCopy().put("nonce", UUID.randomUUID().toString()));
            mutations.add(envelope.deepCopy().put("status", "VFS_VERIFIED"));
            mutations.add(envelope.deepCopy().put("code", "unexpected"));
            var missing = envelope.deepCopy();
            missing.remove("result");
            mutations.add(missing);
            var badHead = envelope.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) badHead.get("result"))
                    .put("head", "0".repeat(40));
            mutations.add(badHead);
            var badCount = envelope.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) badCount.at("/result/coverage"))
                    .put("fileCount", 2);
            mutations.add(badCount);
            var badDigest = envelope.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) badDigest.at("/result/coverage"))
                    .put("sourceManifestSha256", "0".repeat(64));
            mutations.add(badDigest);
            var badRoot = envelope.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) badRoot.at("/result/discovery"))
                    .put("projectPath", root.resolve("wrong").toString());
            mutations.add(badRoot);
            for (var mutation : mutations) {
                assertThatThrownBy(
                                () ->
                                        SourceInventoryClient.decodeWorker(
                                                new BoundedProcessRunner.ProcessResult(
                                                        0,
                                                        JSON.writeValueAsString(mutation),
                                                        false),
                                                root,
                                                head,
                                                nonce))
                        .isInstanceOf(IOException.class);
            }
            for (String malformed :
                    List.of(
                            good.output() + "{}",
                            good.output()
                                    .replace(
                                            "\"schemaVersion\":2",
                                            "\"schemaVersion\":2,\"schemaVersion\":2"),
                            "\ufffd",
                            "x".repeat(SourceInventory.MAX_JSON_BYTES + 1))) {
                assertThatThrownBy(
                                () ->
                                        SourceInventoryClient.decodeWorker(
                                                new BoundedProcessRunner.ProcessResult(
                                                        0, malformed, false),
                                                root,
                                                head,
                                                nonce))
                        .isInstanceOf(IOException.class);
            }
            assertThatThrownBy(
                            () ->
                                    SourceInventoryClient.decodeWorker(
                                            new BoundedProcessRunner.ProcessResult(
                                                    1, good.output(), false),
                                            root,
                                            head,
                                            nonce))
                    .isInstanceOf(IOException.class);
            assertThatThrownBy(
                            () ->
                                    SourceInventoryClient.decodeWorker(
                                            new BoundedProcessRunner.ProcessResult(
                                                    0, good.output(), true),
                                            root,
                                            head,
                                            nonce))
                    .isInstanceOf(IOException.class);
        }

        @Test
        void jarContainsOnlyApprovedWorkerClassesAndJackson() throws Exception {
            try (var jar =
                    new java.util.jar.JarFile(System.getProperty("sourceInventory.workerJar"))) {
                assertThat(jar.getManifest().getMainAttributes().getValue("Main-Class"))
                        .isEqualTo(SourceInventoryClient.class.getName());
                assertThat(jar.getManifest().getMainAttributes().getValue("Class-Path")).isNull();
                for (var entry : jar.stream().filter(e -> !e.isDirectory()).toList()) {
                    assertThat(entry.getName())
                            .matches(
                                    "META-INF/MANIFEST.MF|com/fasterxml/jackson/.*|com/jinloes/prpilot/"
                                            + "(model/SourceInventory[^/]*|review/(SourceInventoryClient|SourceInventoryFiles|BoundedProcessRunner)[^/]*)\\.class");
                }
            }
        }

        @Test
        void launchCannotUseRelativeOrWorktreeExecutablePath() throws Exception {
            var valid = launch(config);
            for (String path : List.of("", ".", "/usr/bin:", root.toString())) {
                var invalid =
                        new SourceInventoryClient.Launch(
                                valid.java(),
                                valid.workerJar(),
                                valid.git(),
                                valid.ijctl(),
                                valid.config(),
                                valid.server(),
                                valid.home(),
                                path);
                assertThatThrownBy(() -> new SourceInventoryClient(invalid).collect(root, head))
                        .isInstanceOf(Exception.class);
            }
            var relative =
                    new SourceInventoryClient.Launch(
                            Path.of("java"),
                            valid.workerJar(),
                            valid.git(),
                            valid.ijctl(),
                            valid.config(),
                            valid.server(),
                            valid.home(),
                            valid.executablePath());
            assertThatThrownBy(() -> new SourceInventoryClient(relative))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void poisonedAmbientOptionsNeverReachWorkerOrTool() throws Exception {
            var valid = launch(config);
            ProcessBuilder parent =
                    new ProcessBuilder(
                            valid.java().toString(),
                            "-cp",
                            fixtureClasspath,
                            Fixture.ParentFixture.class.getName(),
                            root.toString(),
                            head,
                            valid.java().toString(),
                            valid.workerJar().toString(),
                            valid.git().toString(),
                            executable.toString(),
                            config.toString(),
                            fixture.toString());
            parent.environment().put("JAVA_TOOL_OPTIONS", "-DsourceInventory.poison=true");
            parent.environment().put("JDK_JAVA_OPTIONS", "-DsourceInventory.poison=true");
            parent.environment().put("_JAVA_OPTIONS", "-DsourceInventory.poison=true");
            parent.environment().put("NODE_OPTIONS", "--this-must-never-reach-node");
            parent.environment().put("GIT_DIR", fixture.resolve("nonexistent").toString());
            parent.environment().put("PATH", root.toString());
            var result = new BoundedProcessRunner().run(parent, 20, TimeUnit.SECONDS);
            assertThat(result.exitCode()).isZero();
            assertThat(result.output()).contains("SANITIZED_COVERAGE");
        }

        @Test
        void unsupportedInstalledJava17FailsClosedWithoutARequest() throws Exception {
            Path java17 =
                    Path.of(
                            "/Library/Java/JavaVirtualMachines/jdk17.0.5-msft.jdk/Contents/Home/bin/java");
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    Files.isExecutable(java17), "Optional macOS JDK17 negative control");
            var valid = launch(config);
            var old =
                    new SourceInventoryClient.Launch(
                            java17,
                            valid.workerJar(),
                            valid.git(),
                            valid.ijctl(),
                            valid.config(),
                            valid.server(),
                            valid.home(),
                            valid.executablePath());
            assertThatThrownBy(() -> new SourceInventoryClient(old).collect(root, head))
                    .hasMessageContaining("UNSUPPORTED_API");
            assertThat(Files.exists(log)).isFalse();
        }

        @Test
        void reconcilesVirtualOnlyAndUnsafeOrMissingEmptyRoots() throws Exception {
            for (String name :
                    List.of(
                            "virtual-only",
                            "unknown-membership",
                            "missing-root",
                            "external-root")) {
                scenario(name);
                assertThatThrownBy(() -> client().collect(root, head))
                        .as(name)
                        .isInstanceOf(IOException.class);
            }
            Files.createDirectory(root.resolve("empty"));
            scenario("empty-root");
            assertThat(client().collect(root, head).files()).hasSize(1);
            Files.delete(root.resolve("empty"));
            assertThatThrownBy(() -> client().collect(root, head)).isInstanceOf(IOException.class);
            Files.createSymbolicLink(root.resolve("empty"), root.resolve("src"));
            assertThatThrownBy(() -> client().collect(root, head))
                    .hasMessageContaining("UNSAFE_PATH");
        }

        @Test
        void rejectsGitlinksAndFilterTransformedSourceBytes() throws Exception {
            Files.writeString(root.resolve(".gitattributes"), "src/extensionless text\n");
            Files.writeString(root.resolve("src/extensionless"), "line\r\n");
            git(root, "add", ".gitattributes", "src/extensionless");
            git(root, "commit", "--quiet", "-m", "normalized fixture");
            head = git(root, "rev-parse", "HEAD").strip();
            assertThatThrownBy(() -> client().collect(root, head))
                    .hasMessageContaining("CONTENT_MISMATCH");
            git(root, "update-index", "--add", "--cacheinfo", "160000," + head + ",nested");
            git(root, "commit", "--quiet", "-m", "gitlink fixture");
            head = git(root, "rev-parse", "HEAD").strip();
            assertThatThrownBy(() -> client().collect(root, head))
                    .hasMessageContaining("UNSUPPORTED_GIT_ENTRY");
        }

        @Test
        void cancellationTerminatesTheChildAndDeletesPrivateRequest() throws Exception {
            scenario("wait");
            var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                var future = executor.submit(() -> client().collect(root, head));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!Files.exists(log) && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                assertThat(Files.exists(log)).isTrue();
                future.cancel(true);
            } finally {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
        }

        @Test
        void hashesExtensionlessSourceAndAllowsProvenNonSourceIdeaMetadata() throws Exception {
            var coverage = client().collect(root, head);
            assertThat(coverage.head()).isEqualTo(head);
            assertThat(coverage.files())
                    .extracting(Hash::getPath)
                    .containsExactly("src/extensionless");
            assertThat(coverage.files().get(0).getSha256())
                    .isEqualTo(
                            SourceInventoryFiles.hash(
                                            root,
                                            "src/extensionless",
                                            new SourceInventoryFiles.Budget(),
                                            null)
                                    .sha256());
            assertThat(coverage.coverage().getFileCount()).isEqualTo(1);
            assertThat(Files.readAllLines(log)).hasSize(3);
        }

        @Test
        void rejectsTheSameIdeaPathWhenNativeMarksItSource() throws Exception {
            scenario("idea-source");
            assertThatThrownBy(() -> client().collect(root, head))
                    .hasMessageContaining("SOURCE_CONTAMINATION");
        }

        @Test
        void rejectsIgnoredGeneratedAndOtherUntrackedSource() throws Exception {
            Files.writeString(root.resolve("generated"), "ignored generated source");
            assertThatThrownBy(() -> client().collect(root, head))
                    .hasMessageContaining("SOURCE_CONTAMINATION");
            Files.delete(root.resolve("generated"));
            Files.writeString(root.resolve("src/untracked"), "source");
            assertThatThrownBy(() -> client().collect(root, head))
                    .hasMessageContaining("SOURCE_CONTAMINATION");
        }

        @Test
        void rejectsOmissionExtraManifestPathAndChangedSourceBytes() throws Exception {
            scenario("omitted");
            assertThatThrownBy(() -> client().collect(root, head))
                    .hasMessageContaining("INVENTORY_CHANGED");
            scenario("extra-hash");
            assertThatThrownBy(() -> client().collect(root, head))
                    .hasMessageContaining("MANIFEST_MISMATCH");
            scenario("baseline");
            Files.writeString(root.resolve("src/extensionless"), "dirty");
            assertThatThrownBy(() -> client().collect(root, head))
                    .hasMessageContaining("CONTENT_MISMATCH");
        }

        @Test
        void rejectsMissingTrackedLeavesAndIndexChangesBeforeDiscovery() throws Exception {
            Files.delete(root.resolve("src/extensionless"));
            assertThatThrownBy(() -> client().collect(root, head))
                    .hasMessageContaining("UNSUPPORTED_GIT_ENTRY");
            Files.writeString(root.resolve("src/extensionless"), "staged");
            git(root, "add", "src/extensionless");
            assertThatThrownBy(() -> client().collect(root, head))
                    .hasMessageContaining("GIT_CHANGED");
        }

        @Test
        void rejectsHeadIndexAndBytesChangedByVerificationProcess() throws Exception {
            scenario("change-bytes");
            assertThatThrownBy(() -> client().collect(root, head))
                    .hasMessageContaining("INVENTORY_CHANGED");
            Files.writeString(root.resolve("src/extensionless"), "tracked source");
            scenario("change-index");
            assertThatThrownBy(() -> client().collect(root, head))
                    .hasMessageContaining("GIT_CHANGED");
            git(root, "add", "src/extensionless");
            scenario("change-head");
            assertThatThrownBy(() -> client().collect(root, head))
                    .hasMessageContaining("GIT_CHANGED");
        }

        @Test
        void rejectsNonceProjectAndSchemaMismatches() throws Exception {
            for (String name :
                    List.of(
                            "nonce",
                            "project",
                            "version",
                            "wrong-server",
                            "daemon",
                            "unknown",
                            "partial")) {
                scenario(name);
                assertThatThrownBy(() -> client().collect(root, head))
                        .as(name)
                        .isInstanceOf(IOException.class);
            }
        }

        @Test
        void rejectsMalformedTruncatedDisagreeingAndPollutedEnvelopes() throws Exception {
            for (String name :
                    List.of(
                            "malformed",
                            "truncated",
                            "stderr",
                            "disagree",
                            "duplicate",
                            "trailing",
                            "error",
                            "nonzero")) {
                scenario(name);
                assertThatThrownBy(() -> client().collect(root, head))
                        .as(name)
                        .isInstanceOf(IOException.class);
            }
        }

        @Test
        void textOnlyEnvelopeUsesTheSameStrictConsumer() throws Exception {
            scenario("text");
            assertThat(client().collect(root, head).coverage().getFileCount()).isEqualTo(1);
        }

        @Test
        void rejectsExecutableAndConfigInsideWorktreeAndNonRootPaths() throws Exception {
            Path inside = Files.copy(config, root.resolve("config"));
            assertThatThrownBy(() -> new SourceInventoryClient(launch(inside)).collect(root, head))
                    .hasMessageContaining("UNSAFE_PATH");
            assertThatThrownBy(() -> client().collect(root.resolve("src"), head))
                    .hasMessageContaining("WRONG_PROJECT");
        }
    }

    public static final class Fixture {
        private static final String ID = "00000000-0000-0000-0000-000000000001";

        public static void main(String[] args) throws Exception {
            for (String key :
                    List.of(
                            "JAVA_TOOL_OPTIONS",
                            "JDK_JAVA_OPTIONS",
                            "_JAVA_OPTIONS",
                            "NODE_OPTIONS")) {
                if (System.getenv(key) != null) throw new IOException("Inherited " + key);
            }
            if (System.getProperty("sourceInventory.poison") != null)
                throw new IOException("Inherited JVM flags");
            if (ProcessHandle.current().parent().orElseThrow().pid()
                            == ProcessHandle.current().pid()
                    || !ProcessHandle.current()
                            .parent()
                            .orElseThrow()
                            .info()
                            .commandLine()
                            .orElse("")
                            .contains("pr-pilot-source-inventory-worker.jar")) {
                throw new IOException("Tool not launched by packaged external worker");
            }
            if (args.length != 13
                    || !args[0].equals("--config")
                    || !args[2].equals("--project")
                    || !args[4].equals("--server")
                    || !args[6].equals("--no-daemon")
                    || !args[7].equals("--timeout")
                    || !args[8].equals("30000")
                    || !args[9].equals("call")
                    || !args[10].equals(SourceInventory.TOOL)
                    || !args[11].equals("--args-file")) {
                throw new IOException("Unexpected command shape");
            }
            if (!List.of(args)
                    .subList(6, 12)
                    .equals(
                            List.of(
                                    "--no-daemon",
                                    "--timeout",
                                    "30000",
                                    "call",
                                    SourceInventory.TOOL,
                                    "--args-file"))) {
                throw new IOException("Wrong fixed command");
            }
            Map<?, ?> config = JSON.readValue(Files.readString(Path.of(args[1])), Map.class);
            String scenario = (String) config.get("scenario");
            Path root = Path.of(args[3]), requestPath = Path.of(args[12]);
            if (requestPath.startsWith(root)
                    || !Files.getPosixFilePermissions(requestPath)
                            .equals(PosixFilePermissions.fromString("rw-------"))
                    || !Files.getPosixFilePermissions(requestPath.getParent())
                            .equals(PosixFilePermissions.fromString("rwx------"))) {
                throw new IOException("Request confinement/permissions");
            }
            Files.writeString(
                    Path.of((String) config.get("log")),
                    requestPath + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            if (scenario.equals("wait")) {
                Thread.sleep(60_000);
            }
            Map<?, ?> request = JSON.readValue(Files.readString(requestPath), Map.class);
            boolean discover = request.get("operation").equals("DISCOVER");
            Response response = new Response();
            response.setSchemaVersion(scenario.equals("version") ? 1 : 2);
            response.setOperation(discover ? Operation.DISCOVER : Operation.VERIFY);
            response.setNonce(
                    scenario.equals("nonce")
                            ? UUID.randomUUID().toString()
                            : (String) request.get("nonce"));
            response.setReasons(List.of());
            if (discover) {
                response.setStatus(Status.DISCOVERED);
                response.setDiscovery(discovery(root, scenario));
            } else {
                if (scenario.equals("change-bytes")) {
                    Files.writeString(root.resolve("src/extensionless"), "edited during verify");
                }
                if (scenario.equals("change-index")) {
                    git(root, "update-index", "--force-remove", "src/extensionless");
                }
                if (scenario.equals("change-head")) {
                    git(root, "commit", "--quiet", "--allow-empty", "-m", "new head");
                }
                List<Hash> hashes = new ArrayList<>();
                for (Object file : (List<?>) request.get("files")) {
                    hashes.add(JSON.convertValue(file, Hash.class));
                }
                Coverage coverage = new Coverage();
                coverage.setDiscoveryId((String) request.get("discoveryId"));
                coverage.setProjectPath(root.toString());
                coverage.setProjectInstanceId(ID);
                coverage.setIdeBuild("IU-262.1");
                coverage.setEpochs(epochs());
                coverage.setFileCount(hashes.size() + (scenario.equals("extra-hash") ? 1 : 0));
                coverage.setSourceManifestSha256(SourceInventory.manifest(hashes));
                response.setStatus(Status.VFS_VERIFIED);
                response.setCoverage(coverage);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            Map<String, Object> payload =
                    JSON.convertValue(
                            response, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            if (scenario.equals("unknown")) {
                payload.put("unexpected", 1);
            }
            if (scenario.equals("partial")) {
                payload.remove("coverage");
            }
            if (!scenario.equals("text")) {
                result.put("structuredContent", payload);
            }
            result.put(
                    "content",
                    scenario.equals("text") || scenario.equals("disagree")
                            ? List.of(
                                    Map.of(
                                            "type",
                                            "text",
                                            "text",
                                            JSON.writeValueAsString(
                                                    scenario.equals("disagree")
                                                            ? Map.of()
                                                            : payload)))
                            : List.of());
            result.put("isError", scenario.equals("error"));
            Map<String, Object> envelope =
                    Map.of(
                            "ok",
                            true,
                            "command",
                            "call",
                            "durationMs",
                            1,
                            "server",
                            scenario.equals("wrong-server") ? "other" : args[5],
                            "connectionMode",
                            scenario.equals("daemon") ? "daemon" : "direct",
                            "tool",
                            SourceInventory.TOOL,
                            "result",
                            result);
            String text = JSON.writeValueAsString(envelope);
            if (scenario.equals("malformed")) {
                text = "not JSON";
            }
            if (scenario.equals("truncated")) {
                text = "x".repeat(SourceInventory.MAX_JSON_BYTES + 1);
            }
            if (scenario.equals("duplicate")) {
                text = text.replace("\"ok\":true", "\"ok\":true,\"ok\":true");
            }
            if (scenario.equals("trailing")) {
                text += "{}";
            }
            if (scenario.equals("stderr")) {
                System.err.print("stderr pollution\n");
            }
            System.out.print(text);
            if (scenario.equals("nonzero")) {
                System.exit(3);
            }
        }

        private static Epochs epochs() {
            Epochs result = new Epochs();
            result.setRoots("0");
            result.setModules("0");
            result.setVfs("0");
            result.setFileTypes("0");
            return result;
        }

        private static Discovery discovery(Path root, String scenario) throws IOException {
            Location location = new Location();
            location.setUrl("file://" + root);
            location.setNativePath(root.toString());
            location.setScope(Scope.WORKTREE);
            Source source = new Source();
            source.setLocation(location);
            source.setTypeClass("fixture.source");
            source.setGenerated(false);
            source.setTest(false);
            Content content = new Content();
            content.setModule("main");
            content.setLocation(location);
            content.setSources(List.of(source));
            content.setExclusions(List.of());
            content.setExcludePatterns(List.of());
            Model model = new Model();
            model.setContents(List.of(content));
            model.setDependencySources(List.of());
            model.setDependencyClasses(List.of());
            model.setIgnoredPatterns("");
            model.setUnloadedModules(List.of());
            if (List.of("missing-root", "empty-root", "external-root").contains(scenario)) {
                Location other = new Location();
                Path path =
                        scenario.equals("external-root")
                                ? root.getParent()
                                : root.resolve(scenario.equals("empty-root") ? "empty" : "missing");
                other.setUrl("file://" + path);
                other.setNativePath(path.toString());
                other.setScope(scenario.equals("external-root") ? Scope.EXTERNAL : Scope.WORKTREE);
                Source additional = new Source();
                additional.setLocation(other);
                additional.setTypeClass("generated.resource");
                additional.setGenerated(true);
                additional.setTest(false);
                content.setSources(SourceInventory.sorted(List.of(source, additional)));
            }
            List<Entry> entries = new ArrayList<>();
            for (var leaf : SourceInventoryFiles.scan(root, new SourceInventoryFiles.Budget())) {
                if (scenario.equals("omitted") && leaf.path().equals("src/extensionless")) {
                    continue;
                }
                boolean isSource =
                        leaf.path().startsWith("src/")
                                || leaf.path().equals("generated")
                                || scenario.equals("idea-source")
                                        && leaf.path().equals(".idea/workspace.xml");
                Entry entry = new Entry();
                entry.setPath(leaf.path());
                entry.setKind(leaf.kind());
                entry.setMembership(isSource ? Membership.SOURCE : Membership.OUTSIDE_SOURCE);
                entry.setSourceRootUrl(isSource ? location.getUrl() : null);
                entry.setModules(isSource ? List.of("main") : List.of());
                entry.setGenerated(false);
                entry.setTest(false);
                entries.add(entry);
            }
            if (scenario.equals("virtual-only")) {
                Entry virtual =
                        JSON.convertValue(
                                entries.stream()
                                        .filter(e -> e.getMembership() == Membership.SOURCE)
                                        .findFirst()
                                        .orElseThrow(),
                                Entry.class);
                virtual.setPath("src/virtual-only");
                entries.add(virtual);
            }
            if (scenario.equals("unknown-membership")) {
                Entry entry = entries.get(0);
                entry.setMembership(Membership.UNKNOWN);
                entry.setSourceRootUrl(null);
                entry.setGenerated(false);
                entry.setTest(false);
            }
            Discovery result = new Discovery();
            result.setDiscoveryId(ID);
            result.setProjectPath(
                    scenario.equals("project")
                            ? root.resolve("wrong").toString()
                            : root.toString());
            result.setProjectInstanceId(ID);
            result.setIdeBuild("IU-262.1");
            result.setEpochs(epochs());
            result.setModel(model);
            result.setEntries(SourceInventory.sorted(entries));
            return result;
        }

        public static final class ParentFixture {
            public static void main(String[] args) throws Exception {
                if (!"true".equals(System.getProperty("sourceInventory.poison"))) {
                    throw new IOException("Parent was not poisoned");
                }
                var launch =
                        new SourceInventoryClient.Launch(
                                Path.of(args[2]),
                                Path.of(args[3]),
                                Path.of(args[4]),
                                Path.of(args[5]),
                                Path.of(args[6]),
                                "fixture-server",
                                Path.of(args[7]),
                                "/usr/bin:/bin");
                var coverage = new SourceInventoryClient(launch).collect(Path.of(args[0]), args[1]);
                if (coverage.files().size() != 1) throw new IOException("Incomplete coverage");
                System.out.print("SANITIZED_COVERAGE");
            }
        }
    }
}
