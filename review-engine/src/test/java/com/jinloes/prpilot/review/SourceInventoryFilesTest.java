package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jinloes.prpilot.model.SourceInventory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SourceInventoryFilesTest {
    private Path root;

    @BeforeEach
    void setup() throws IOException {
        root = Files.createTempDirectory("inventory-").toRealPath();
    }

    @AfterEach
    void cleanup() throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private Path write(String path, String value) throws IOException {
        Path target = root.resolve(path);
        Files.createDirectories(target.getParent());
        return Files.writeString(target, value);
    }

    @Nested
    class Settings {
        private SourceInventoryFiles.Setting capture(Path path) throws IOException {
            var first =
                    SourceInventoryFiles.captureSettings(
                            List.of(path.toString()), new SourceInventoryFiles.Budget());
            assertThat(
                            SourceInventoryFiles.captureSettings(
                                    List.of(path.toString()), new SourceInventoryFiles.Budget()))
                    .isEqualTo(first);
            return first.get(0);
        }

        @Test
        void absentLeavesAndAncestorsRemainInTheExactSetWithoutCreatingDirectories()
                throws Exception {
            Path project = Files.createDirectory(root.resolve("project"));
            Path home = Files.createDirectory(root.resolve("home"));
            List<String> paths =
                    List.of(
                            home.resolve(".gradle/gradle.properties").toString(),
                            project.resolve(".gradle/config/settings").toString(),
                            project.resolve("gradle/wrapper/gradle-wrapper.properties").toString(),
                            project.resolve("missing-leaf").toString(),
                            project.resolve("one/settings").toString());
            var first =
                    SourceInventoryFiles.captureSettings(paths, new SourceInventoryFiles.Budget());
            assertThat(first)
                    .extracting(SourceInventoryFiles.Setting::path)
                    .containsExactlyElementsOf(paths);
            assertThat(first)
                    .allSatisfy(
                            setting -> {
                                assertThat(setting.present()).isFalse();
                                assertThat(setting.sha256()).isNull();
                                assertThat(setting.physicalIdentity()).matches("[a-f0-9]{64}");
                            });
            assertThat(first)
                    .extracting(SourceInventoryFiles.Setting::physicalIdentity)
                    .doesNotHaveDuplicates();
            assertThat(
                            SourceInventoryFiles.captureSettings(
                                    paths, new SourceInventoryFiles.Budget()))
                    .isEqualTo(first);
            try (var tree = Files.walk(root)) {
                assertThat(tree.toList()).containsExactlyInAnyOrder(root, project, home);
            }
        }

        @Test
        void fingerprintsBindAncestorCreationDeletionReplacementAndLeafEditRevert()
                throws Exception {
            Path setting = root.resolve("gradle/wrapper/gradle-wrapper.properties");
            List<SourceInventoryFiles.Setting> stages = new ArrayList<>();
            stages.add(capture(setting));
            Files.createDirectory(root.resolve("gradle"));
            stages.add(capture(setting));
            Files.createDirectory(setting.getParent());
            stages.add(capture(setting));
            Files.writeString(setting, "before");
            stages.add(capture(setting));
            Files.writeString(setting, "changed bytes");
            stages.add(capture(setting));
            Files.writeString(setting, "before");
            Files.setLastModifiedTime(setting, FileTime.fromMillis(1234567890000L));
            stages.add(capture(setting));
            assertThat(stages.get(5).sha256()).isEqualTo(stages.get(3).sha256());
            Files.delete(setting);
            stages.add(capture(setting));
            Files.move(setting.getParent(), root.resolve("old-wrapper"));
            stages.add(capture(setting));
            Files.createDirectory(setting.getParent());
            stages.add(capture(setting));
            Files.delete(setting.getParent());
            Files.delete(root.resolve("gradle"));
            stages.add(capture(setting));
            assertThat(stages)
                    .extracting(SourceInventoryFiles.Setting::physicalIdentity)
                    .doesNotHaveDuplicates();
            assertThat(stages)
                    .extracting(SourceInventoryFiles.Setting::present)
                    .containsExactly(
                            false, false, false, true, true, true, false, false, false, false);
        }

        @Test
        void rejectsLinksAndNonDirectoryComponentsIncludingDanglingLinks() throws Exception {
            write("existing/settings", "value");
            Files.createSymbolicLink(root.resolve("prefix-link"), root.resolve("existing"));
            Files.createSymbolicLink(root.resolve("dangling"), root.resolve("nonexistent"));
            Files.createSymbolicLink(
                    root.resolve("existing/leaf-link"), root.resolve("nonexistent"));
            write("regular-parent", "");
            for (String relative :
                    List.of(
                            "prefix-link/absent/settings",
                            "dangling/settings",
                            "dangling",
                            "existing/leaf-link",
                            "regular-parent/settings",
                            "existing")) {
                assertThatThrownBy(() -> capture(root.resolve(relative)))
                        .hasMessageContaining("UNSAFE_PATH");
            }
            assertThat(capture(root.resolve("safe/absent")).present()).isFalse();
        }

        @Test
        void racedAppearanceReversalAndParentSubstitutionFailAndReleaseDescriptors()
                throws Exception {
            for (String race : List.of("ancestor", "leaf", "reversal", "symlink", "parent-link")) {
                Path parent = Files.createDirectory(root.resolve(race));
                Path setting =
                        parent.resolve(race.equals("leaf") ? "settings" : "missing/settings");
                var before = Files.getLastModifiedTime(parent);
                assertThatThrownBy(
                                () ->
                                        SourceInventoryFiles.captureSettings(
                                                List.of(setting.toString()),
                                                new SourceInventoryFiles.Budget(),
                                                () -> {
                                                    try {
                                                        switch (race) {
                                                            case "ancestor" ->
                                                                    Files.createDirectory(
                                                                            parent.resolve(
                                                                                    "missing"));
                                                            case "leaf" ->
                                                                    Files.writeString(
                                                                            setting, "appeared");
                                                            case "reversal" -> {
                                                                Files.createDirectory(
                                                                        parent.resolve("missing"));
                                                                Files.delete(
                                                                        parent.resolve("missing"));
                                                                Files.setLastModifiedTime(
                                                                        parent,
                                                                        FileTime.fromMillis(
                                                                                before.toMillis()
                                                                                        + 2000));
                                                            }
                                                            case "symlink" ->
                                                                    Files.createSymbolicLink(
                                                                            parent.resolve(
                                                                                    "missing"),
                                                                            root.resolve("absent"));
                                                            case "parent-link" -> {
                                                                Files.move(
                                                                        parent,
                                                                        root.resolve(
                                                                                "moved-parent"));
                                                                Files.createSymbolicLink(
                                                                        parent,
                                                                        root.resolve(
                                                                                "moved-parent"));
                                                            }
                                                            default ->
                                                                    throw new AssertionError(race);
                                                        }
                                                    } catch (IOException e) {
                                                        throw new IllegalStateException(e);
                                                    }
                                                }))
                        .isInstanceOf(SourceInventoryFiles.Failure.class)
                        .hasMessageContaining(
                                race.equals("parent-link") ? "UNSAFE_PATH" : "INVENTORY_CHANGED");
                if (race.equals("parent-link")) {
                    Files.delete(parent);
                    Files.move(root.resolve("moved-parent"), parent);
                } else if (race.equals("symlink")) {
                    Files.delete(parent.resolve("missing"));
                }
                capture(setting);
            }
        }

        @Test
        void settingsDoNotSwallowAccessErrorsOrBudgetCancellation() throws Exception {
            Path locked = Files.createDirectory(root.resolve("locked"));
            var permissions = Files.getPosixFilePermissions(locked);
            try {
                Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
                assertThatThrownBy(() -> capture(locked.resolve("missing/settings")))
                        .isInstanceOf(java.nio.file.AccessDeniedException.class);
            } finally {
                Files.setPosixFilePermissions(locked, permissions);
            }
            List<String> paths = List.of(root.resolve("absent/settings").toString());
            assertThatThrownBy(
                            () ->
                                    SourceInventoryFiles.captureSettings(
                                            paths,
                                            new SourceInventoryFiles.Budget(Duration.ZERO, 10, 10)))
                    .hasMessageContaining("LIMIT");
            assertThatThrownBy(
                            () ->
                                    SourceInventoryFiles.captureSettings(
                                            paths,
                                            new SourceInventoryFiles.Budget(),
                                            () -> {
                                                throw new IllegalStateException("callback error");
                                            }))
                    .hasMessageContaining("callback error");
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(
                                () ->
                                        SourceInventoryFiles.captureSettings(
                                                paths, new SourceInventoryFiles.Budget()))
                        .hasMessageContaining("LIMIT");
            } finally {
                Thread.interrupted();
            }
            assertThat(capture(root.resolve("absent/settings")).present()).isFalse();
        }
    }

    @Nested
    class Walk {
        @Test
        void settingsRequireExactUniquePathsAndNoFollowParents() throws Exception {
            Path setting = write("build.gradle", "plugins {}");
            var paths = java.util.List.of(setting.toString());
            var first =
                    SourceInventoryFiles.captureSettings(paths, new SourceInventoryFiles.Budget());
            assertThat(first.get(0).present()).isTrue();
            assertThat(first.get(0).sha256())
                    .isEqualTo(
                            SourceInventoryFiles.hash(
                                            root,
                                            "build.gradle",
                                            new SourceInventoryFiles.Budget(),
                                            null)
                                    .sha256());
            assertThatThrownBy(
                            () ->
                                    SourceInventoryFiles.captureSettings(
                                            java.util.List.of(
                                                    setting.toString(), setting.toString()),
                                            new SourceInventoryFiles.Budget()))
                    .isInstanceOf(IllegalArgumentException.class);
            var absentPaths = java.util.List.of(root.resolve("absent/settings").toString());
            var absent =
                    SourceInventoryFiles.captureSettings(
                            absentPaths, new SourceInventoryFiles.Budget());
            assertThat(absent.get(0).path()).isEqualTo(absentPaths.get(0));
            assertThat(absent.get(0).present()).isFalse();
            assertThat(absent.get(0).sha256()).isNull();
            assertThat(absent.get(0).physicalIdentity()).matches("[a-f0-9]{64}");
            assertThat(
                            SourceInventoryFiles.captureSettings(
                                    absentPaths, new SourceInventoryFiles.Budget()))
                    .isEqualTo(absent);
            assertThat(root.resolve("absent")).doesNotExist();
            Files.createSymbolicLink(root.resolve("alias"), root);
            assertThatThrownBy(
                            () ->
                                    SourceInventoryFiles.captureSettings(
                                            java.util.List.of(
                                                    root.resolve("alias/build.gradle").toString()),
                                            new SourceInventoryFiles.Budget()))
                    .hasMessageContaining("UNSAFE_PATH");
            assertThatThrownBy(
                            () ->
                                    SourceInventoryFiles.captureSettings(
                                            paths,
                                            new SourceInventoryFiles.Budget(
                                                    Duration.ofSeconds(1), 0, 100)))
                    .hasMessageContaining("LIMIT");
        }

        @Test
        void emptyLogicalRootsStillNeedPhysicalNoFollowDirectories() throws IOException {
            Path empty = Files.createDirectory(root.resolve("empty"));
            SourceInventoryFiles.requireDirectory(empty, new SourceInventoryFiles.Budget());
            Files.createSymbolicLink(root.resolve("alias"), empty);
            assertThatThrownBy(
                            () ->
                                    SourceInventoryFiles.requireDirectory(
                                            root.resolve("alias"),
                                            new SourceInventoryFiles.Budget()))
                    .hasMessageContaining("UNSAFE_PATH");
            assertThatThrownBy(
                            () ->
                                    SourceInventoryFiles.requireDirectory(
                                            root.resolve("missing"),
                                            new SourceInventoryFiles.Budget()))
                    .isInstanceOf(IOException.class);
        }

        @Test
        void includesHiddenExcludedAndNestedGitLeaves() throws IOException {
            write(".git/objects/admin", "");
            write(".hidden/excluded/reincluded/source", "x");
            write("nested/.git/config", "");
            write(".idea/workspace.xml", "");
            assertThat(SourceInventoryFiles.scan(root, new SourceInventoryFiles.Budget()))
                    .extracting(SourceInventoryFiles.Leaf::path)
                    .containsExactly(
                            ".hidden/excluded/reincluded/source",
                            ".idea/workspace.xml",
                            "nested/.git/config");
        }

        @Test
        void neverTraversesLinksAndRejectsGitSymlink() throws IOException {
            Files.createSymbolicLink(root.resolve("outside"), Path.of("/"));
            assertThat(SourceInventoryFiles.scan(root, new SourceInventoryFiles.Budget()))
                    .singleElement()
                    .extracting(SourceInventoryFiles.Leaf::kind)
                    .isEqualTo(SourceInventory.Kind.SYMLINK);
            Files.createSymbolicLink(root.resolve(".git"), Path.of("/"));
            assertThatThrownBy(
                            () ->
                                    SourceInventoryFiles.scan(
                                            root, new SourceInventoryFiles.Budget()))
                    .hasMessageContaining("UNSAFE_PATH");
        }

        @Test
        void rejectsUnsupportedProviderAndNodeLimits() throws IOException {
            write("one", "");
            write("two", "");
            assertThatThrownBy(
                            () ->
                                    SourceInventoryFiles.scan(
                                            root,
                                            new SourceInventoryFiles.Budget(
                                                    Duration.ofSeconds(2), 1, 100)))
                    .hasMessageContaining("LIMIT");
            Path zip = root.resolve("test.zip");
            try (var fs =
                    java.nio.file.FileSystems.newFileSystem(
                            java.net.URI.create("jar:" + zip.toUri()),
                            java.util.Map.of("create", "true"))) {
                assertThatThrownBy(
                                () ->
                                        SourceInventoryFiles.scan(
                                                fs.getPath("/"), new SourceInventoryFiles.Budget()))
                        .hasMessageContaining("UNSUPPORTED_API");
            }
        }
    }

    @Nested
    class Hash {
        @Test
        void hashesExtensionlessBytesAndGitHeader() throws IOException {
            write("source", "hello");
            var result =
                    SourceInventoryFiles.hash(
                            root, "source", new SourceInventoryFiles.Budget(), "SHA-1");
            assertThat(result.sha256())
                    .isEqualTo(
                            HexFormat.of()
                                    .formatHex(
                                            SourceInventory.digest("SHA-256")
                                                    .digest(SourceInventory.utf8("hello"))));
            assertThat(result.gitObjectId()).isEqualTo("b6fc4c620b67d95f953a5c1c1230aaab5db5a1b0");
            assertThat(result.size()).isEqualTo(5);
        }

        @Test
        void rejectsSymlinkComponentsLeafLinksAndMissingFiles() throws IOException {
            write("actual/source", "x");
            Files.createSymbolicLink(root.resolve("alias"), root.resolve("actual"));
            Files.createSymbolicLink(root.resolve("link"), root.resolve("actual/source"));
            for (String path : java.util.List.of("alias/source", "link", "missing")) {
                assertThatThrownBy(
                                () ->
                                        SourceInventoryFiles.hash(
                                                root,
                                                path,
                                                new SourceInventoryFiles.Budget(),
                                                null))
                        .isInstanceOf(IOException.class);
            }
        }

        @Test
        void enforcesBytesDeadlineAndCancellation() throws IOException {
            write("source", "too much");
            assertThatThrownBy(
                            () ->
                                    SourceInventoryFiles.hash(
                                            root,
                                            "source",
                                            new SourceInventoryFiles.Budget(
                                                    Duration.ofSeconds(2), 10, 2),
                                            null))
                    .hasMessageContaining("LIMIT");
            assertThatThrownBy(
                            () ->
                                    SourceInventoryFiles.scan(
                                            root,
                                            new SourceInventoryFiles.Budget(
                                                    Duration.ZERO, 10, 100)))
                    .hasMessageContaining("LIMIT");
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(
                                () ->
                                        SourceInventoryFiles.scan(
                                                root, new SourceInventoryFiles.Budget()))
                        .hasMessageContaining("LIMIT");
            } finally {
                Thread.interrupted();
            }
        }

        @Test
        void boundedStreamPropagatesReadErrors() {
            assertThatThrownBy(
                            () ->
                                    SourceInventoryFiles.hashStream(
                                            new ByteArrayInputStream(new byte[3]),
                                            new SourceInventoryFiles.Budget(
                                                    Duration.ofSeconds(2), 10, 2)))
                    .hasMessageContaining("LIMIT");
            assertThatThrownBy(
                            () ->
                                    SourceInventoryFiles.hashStream(
                                            new java.io.InputStream() {
                                                @Override
                                                public int read() throws IOException {
                                                    throw new IOException("read failed");
                                                }
                                            },
                                            new SourceInventoryFiles.Budget()))
                    .hasMessageContaining("read failed");
        }

        @Test
        void detectsComponentReplacementAfterOpeningWithoutReadingTheReplacement()
                throws IOException {
            write("parent/source", "trusted");
            write("replacement/source", "outside");
            assertThatThrownBy(
                            () ->
                                    SourceInventoryFiles.hash(
                                            root,
                                            "parent/source",
                                            new SourceInventoryFiles.Budget(),
                                            null,
                                            () -> {
                                                try {
                                                    Files.move(
                                                            root.resolve("parent"),
                                                            root.resolve("old-parent"));
                                                    Files.createSymbolicLink(
                                                            root.resolve("parent"),
                                                            root.resolve("replacement"));
                                                } catch (IOException e) {
                                                    throw new IllegalStateException(e);
                                                }
                                            }))
                    .hasMessageContaining("UNSAFE_PATH");
        }

        @Test
        void detectsContentMutationAfterOpening() throws IOException {
            write("source", "before");
            assertThatThrownBy(
                            () ->
                                    SourceInventoryFiles.hash(
                                            root,
                                            "source",
                                            new SourceInventoryFiles.Budget(),
                                            null,
                                            () -> {
                                                try {
                                                    Files.writeString(
                                                            root.resolve("source"),
                                                            "after - different size");
                                                } catch (IOException e) {
                                                    throw new IllegalStateException(e);
                                                }
                                            }))
                    .hasMessageContaining("CONTENT_MISMATCH");
        }
    }
}
