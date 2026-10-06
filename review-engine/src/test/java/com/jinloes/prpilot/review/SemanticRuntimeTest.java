package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinloes.prpilot.model.SourceInventory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SemanticRuntimeTest {
    private Path root;

    @BeforeEach
    void setup() throws Exception {
        root = Files.createTempDirectory(fixtureBase(), "semantic-runtime-test-").toRealPath();
    }

    /**
     * Production {@link SemanticRuntime#trusted} rejects launch assets beneath any group- or
     * world-writable ancestor, so the fixture must not live under Linux's sticky world-writable
     * {@code /tmp}. The Gradle test task supplies an owner-only directory under build output; other
     * runners fall back to {@code java.io.tmpdir}.
     */
    private static Path fixtureBase() throws IOException {
        String configured = System.getProperty("semantic.testRoot");
        if (configured == null || configured.isBlank()) {
            return Path.of(System.getProperty("java.io.tmpdir"));
        }
        Path base = Files.createDirectories(Path.of(configured));
        Files.setPosixFilePermissions(base, PosixFilePermissions.fromString("rwx------"));
        return base;
    }

    @AfterEach
    void cleanup() throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    @Nested
    class PackagedWorker {
        @Test
        void isolatedEngineJarHasMatchingWorkerAndManifest() throws Exception {
            try (var loader =
                    new URLClassLoader(
                            new URL[] {
                                Path.of(System.getProperty("semantic.engineJar")).toUri().toURL()
                            },
                            null)) {
                SemanticRuntime.extract(loader, root);
                Path worker = root.resolve("pr-pilot-source-inventory-worker.jar");
                assertThat(Files.readAllBytes(worker))
                        .isEqualTo(
                                Files.readAllBytes(
                                        Path.of(System.getProperty("sourceInventory.workerJar"))));
                assertThat(Files.getPosixFilePermissions(worker))
                        .isEqualTo(PosixFilePermissions.fromString("rw-------"));
                assertThatThrownBy(() -> SemanticRuntime.extract(loader, root))
                        .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
            }
        }

        @Test
        void missingTamperedAndMixedVersionResourcesFailBeforeExtraction() throws Exception {
            assertThatThrownBy(() -> SemanticRuntime.extract(new ClassLoader(null) {}, root))
                    .hasMessageContaining("Missing packaged worker");
            byte[] manifest =
                    new ObjectMapper()
                            .writeValueAsBytes(
                                    Map.of("schemaVersion", 2, "sha256", "0".repeat(64)));
            ClassLoader tampered = resources(manifest);
            assertThatThrownBy(() -> SemanticRuntime.extract(tampered, root))
                    .hasMessageContaining("digest mismatch");
            assertThatThrownBy(
                            () ->
                                    SemanticRuntime.extract(
                                            resources(
                                                    new ObjectMapper()
                                                            .writeValueAsBytes(
                                                                    Map.of(
                                                                            "schemaVersion",
                                                                            1,
                                                                            "sha256",
                                                                            "0".repeat(64)))),
                                            root))
                    .hasMessageContaining("version mismatch");
            try (var entries = Files.list(root)) {
                assertThat(entries).isEmpty();
            }
        }

        private ClassLoader resources(byte[] manifest) {
            return new ClassLoader(null) {
                @Override
                public InputStream getResourceAsStream(String name) {
                    return new ByteArrayInputStream(
                            name.endsWith("manifest.json") ? manifest : new byte[] {1, 2, 3});
                }
            };
        }
    }

    @Nested
    class TrustedConfiguration {
        private Path config(String nodeVersion, String ijctlVersion) throws Exception {
            Path home = Files.createDirectories(root.resolve("home"));
            Path bin = Files.createDirectories(root.resolve("bin"));
            executable(bin.resolve("node"), nodeVersion);
            Path ijctl = executable(bin.resolve("ijctl"), ijctlVersion);
            Path git = executable(bin.resolve("git"), "git version 2.50.0");
            Path ijctlConfig = Files.writeString(root.resolve("ijctl.json"), "{}");
            var values = new HashMap<String, Object>();
            values.put("schemaVersion", 1);
            values.put(
                    "java",
                    Path.of(System.getProperty("java.home"), "bin", "java")
                            .toRealPath()
                            .toString());
            values.put("git", git.toString());
            values.put("ijctl", ijctl.toString());
            values.put("ijctlConfig", ijctlConfig.toString());
            values.put("home", home.toString());
            values.put("path", List.of(bin.toString()));
            values.put("servers", List.of("isolated-fixture"));
            Path launch = root.resolve("launch.json");
            new ObjectMapper().writeValue(launch.toFile(), values);
            return launch;
        }

        private Path executable(Path path, String version) throws Exception {
            Files.writeString(path, "#!/bin/sh\nprintf '%s\\n' '" + version + "'\n");
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
            return path;
        }

        private URLClassLoader engineLoader() throws Exception {
            return new URLClassLoader(
                    new URL[] {Path.of(System.getProperty("semantic.engineJar")).toUri().toURL()},
                    null);
        }

        private Set<Path> runtimeDirectories() throws Exception {
            try (var paths =
                    Files.list(Path.of(System.getProperty("java.io.tmpdir")).toRealPath())) {
                return paths.filter(
                                path ->
                                        path.getFileName()
                                                .toString()
                                                .startsWith("pr-pilot-semantic-"))
                        .collect(Collectors.toSet());
            }
        }

        @Test
        void openUsesOnlyTrustedConfigurationAndCloseRemovesOwnedExtraction() throws Exception {
            Path config = config("v20.20.2", "0.3.0");
            Path worktree = Files.createDirectory(root.resolve("project"));
            try (var loader = engineLoader()) {
                SemanticRuntime runtime = SemanticRuntime.open(worktree, config, loader);
                Path worker;
                try (runtime) {
                    assertThat(runtime.servers()).containsExactly("isolated-fixture");
                    var launch = runtime.launch("isolated-fixture");
                    worker = launch.workerJar();
                    assertThat(Files.exists(worker)).isTrue();
                    assertThat(worker.startsWith(worktree)).isFalse();
                    assertThat(Files.getPosixFilePermissions(worker.getParent()))
                            .isEqualTo(PosixFilePermissions.fromString("rwx------"));
                    assertThat(launch.ijctl()).isEqualTo(root.resolve("bin/ijctl"));
                    assertThat(launch.config()).isEqualTo(root.resolve("ijctl.json"));
                    assertThatThrownBy(() -> runtime.launch("unconfigured"))
                            .hasMessageContaining("unknown server");
                }
                assertThat(Files.exists(worker.getParent())).isFalse();
                runtime.close();
                assertThatThrownBy(() -> runtime.launch("isolated-fixture"))
                        .hasMessageContaining("Runtime closed");
                assertThat(Files.exists(config)).isTrue();
                assertThat(Files.exists(root.resolve("bin/ijctl"))).isTrue();
            }
        }

        @Test
        void versionFailuresCleanExtractionAndNeverFallBackToProjectAssets() throws Exception {
            Path worktree = Files.createDirectory(root.resolve("project"));
            for (var versions :
                    List.of(
                            List.of("v19.9.0", "0.3.0"),
                            List.of("v20.20.2", "0.3.1"),
                            List.of("not-node", "0.3.0"))) {
                Path config = config(versions.get(0), versions.get(1));
                Set<Path> before = runtimeDirectories();
                try (var loader = engineLoader()) {
                    assertThatThrownBy(() -> SemanticRuntime.open(worktree, config, loader))
                            .hasMessageContaining("required");
                }
                assertThat(runtimeDirectories()).isEqualTo(before);
            }
        }

        @RequiresSecureTraversal
        @Test
        void packagedRuntimeOwnsWorkerUntilActualSettingsForkTerminates() throws Exception {
            Path config = config("v20.20.2", "0.3.0");
            Path worktree = Files.createDirectory(root.resolve("project"));
            Path setting = Files.writeString(worktree.resolve("build.gradle"), "// fixture\n");
            Path storage;
            try (var loader = engineLoader();
                    var runtime = SemanticRuntime.open(worktree, config, loader)) {
                var launch = runtime.launch("isolated-fixture");
                storage = launch.workerJar().getParent();
                var captured =
                        new SourceInventoryClient(launch)
                                .captureSettings(worktree, List.of(setting.toString()));
                assertThat(captured.files()).hasSize(1);
                assertThat(captured.files().get(0).present()).isTrue();
                assertThat(captured.files().get(0).sha256())
                        .isEqualTo(
                                HexFormat.of()
                                        .formatHex(
                                                SourceInventory.digest("SHA-256")
                                                        .digest(Files.readAllBytes(setting))));
                assertThat(Files.exists(launch.workerJar())).isTrue();
            }
            assertThat(Files.exists(storage)).isFalse();
            assertThat(Files.readString(setting)).isEqualTo("// fixture\n");
        }

        @Test
        void unsupportedMacJava17FailsClosedThroughConfiguredPackagedRuntime() throws Exception {
            assumeTrue(System.getProperty("os.name").startsWith("Mac"), "macOS negative control");
            Path java17 =
                    Path.of(
                            "/Library/Java/JavaVirtualMachines/jdk17.0.5-msft.jdk/Contents/Home/bin/java");
            assumeTrue(
                    Files.isExecutable(java17), "Optional installed macOS JDK17 negative control");
            Path config = config("v20.20.2", "0.3.0");
            var json = new ObjectMapper();
            var settings = (ObjectNode) json.readTree(Files.readAllBytes(config));
            settings.put("java", java17.toString());
            json.writeValue(config.toFile(), settings);
            Path worktree = Files.createDirectory(root.resolve("project"));
            Path setting = Files.writeString(worktree.resolve("build.gradle"), "// fixture\n");
            Path storage;
            try (var loader = engineLoader();
                    var runtime = SemanticRuntime.open(worktree, config, loader)) {
                var launch = runtime.launch("isolated-fixture");
                storage = launch.workerJar().getParent();
                assertThatThrownBy(
                                () ->
                                        new SourceInventoryClient(launch)
                                                .captureSettings(
                                                        worktree, List.of(setting.toString())))
                        .hasMessageContaining("UNSUPPORTED_API");
            }
            assertThat(Files.exists(storage)).isFalse();
        }

        @Test
        void openRejectsSchemaOverridesDuplicateServersAndWritableAssets() throws Exception {
            Path worktree = Files.createDirectory(root.resolve("project"));
            Path config = config("v20.20.2", "0.3.0");
            var json = new ObjectMapper();
            var valid = json.readTree(Files.readAllBytes(config));
            for (String field : List.of("workerJar", "schemaVersion", "servers", "java")) {
                var invalid = valid.deepCopy();
                var object = (ObjectNode) invalid;
                switch (field) {
                    case "workerJar" -> object.put(field, "/untrusted.jar");
                    case "schemaVersion" -> object.put(field, 2);
                    case "servers" -> object.set(field, json.valueToTree(List.of("same", "same")));
                    case "java" -> object.put(field, worktree.resolve("java").toString());
                    default -> throw new AssertionError(field);
                }
                json.writeValue(config.toFile(), invalid);
                try (var loader = engineLoader()) {
                    assertThatThrownBy(() -> SemanticRuntime.open(worktree, config, loader))
                            .isInstanceOf(java.io.IOException.class);
                }
            }
            json.writeValue(config.toFile(), valid);
            Files.setPosixFilePermissions(
                    root.resolve("bin/ijctl"), PosixFilePermissions.fromString("rwxrwx---"));
            try (var loader = engineLoader()) {
                assertThatThrownBy(() -> SemanticRuntime.open(worktree, config, loader))
                        .hasMessageContaining("Writable launch path");
            }
        }

        @Test
        void nestedEngineResourceStreamsSupportOpenWithoutSiblingBuildLookup() throws Exception {
            Path config = config("v20.20.2", "0.3.0");
            Path outer = root.resolve("fixture-boot.jar");
            try (var output = new ZipOutputStream(Files.newOutputStream(outer))) {
                output.putNextEntry(new ZipEntry("BOOT-INF/lib/review-engine.jar"));
                Files.copy(Path.of(System.getProperty("semantic.engineJar")), output);
                output.closeEntry();
            }
            // Exercise the stream-only boundary with physically nested JAR bytes. Actual
            // Spring Boot launch/installed-host loading remains a separate distribution check.
            Map<String, byte[]> resources = new HashMap<>();
            try (var outerZip = new ZipInputStream(Files.newInputStream(outer))) {
                assertThat(outerZip.getNextEntry().getName())
                        .isEqualTo("BOOT-INF/lib/review-engine.jar");
                try (var inner =
                        new ZipInputStream(new ByteArrayInputStream(outerZip.readAllBytes()))) {
                    for (ZipEntry entry; (entry = inner.getNextEntry()) != null; ) {
                        if (entry.getName().startsWith("semantic-worker/"))
                            resources.put(entry.getName(), inner.readAllBytes());
                    }
                }
            }
            ClassLoader nested =
                    new ClassLoader(null) {
                        @Override
                        public InputStream getResourceAsStream(String name) {
                            byte[] bytes = resources.get(name);
                            return bytes == null ? null : new ByteArrayInputStream(bytes);
                        }
                    };
            try (var runtime = SemanticRuntime.open(root.resolve("project"), config, nested)) {
                assertThat(Files.readAllBytes(runtime.launch("isolated-fixture").workerJar()))
                        .isEqualTo(
                                Files.readAllBytes(
                                        Path.of(System.getProperty("sourceInventory.workerJar"))));
            }
        }

        @Test
        void rejectsWorktreePathsAliasesAndWrongTypes() throws Exception {
            Path file = Files.writeString(root.resolve("config.json"), "{}");
            assertThatThrownBy(() -> SemanticRuntime.trusted(file, root, false, true))
                    .hasMessageContaining("Worktree-local");
            Path alias = Files.createSymbolicLink(root.resolve("alias"), file);
            assertThatThrownBy(
                            () ->
                                    SemanticRuntime.trusted(
                                            alias, root.resolve("project"), false, true))
                    .hasMessageContaining("Symlink");
            assertThatThrownBy(
                            () -> SemanticRuntime.trusted(Path.of("relative"), root, false, true))
                    .hasMessageContaining("Canonical absolute");
        }
    }
}
