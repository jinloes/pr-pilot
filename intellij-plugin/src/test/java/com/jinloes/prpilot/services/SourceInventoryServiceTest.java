package com.jinloes.prpilot.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jinloes.prpilot.model.SourceInventory;
import com.jinloes.prpilot.model.SourceInventory.Content;
import com.jinloes.prpilot.model.SourceInventory.DiscoverRequest;
import com.jinloes.prpilot.model.SourceInventory.Entry;
import com.jinloes.prpilot.model.SourceInventory.Epochs;
import com.jinloes.prpilot.model.SourceInventory.Hash;
import com.jinloes.prpilot.model.SourceInventory.Location;
import com.jinloes.prpilot.model.SourceInventory.Membership;
import com.jinloes.prpilot.model.SourceInventory.Model;
import com.jinloes.prpilot.model.SourceInventory.Operation;
import com.jinloes.prpilot.model.SourceInventory.ReasonCode;
import com.jinloes.prpilot.model.SourceInventory.Response;
import com.jinloes.prpilot.model.SourceInventory.Scope;
import com.jinloes.prpilot.model.SourceInventory.Source;
import com.jinloes.prpilot.model.SourceInventory.Status;
import com.jinloes.prpilot.model.SourceInventory.VerifyRequest;
import com.jinloes.prpilot.review.SourceInventoryFiles;
import com.jinloes.prpilot.review.SourceInventoryFiles.Budget;
import com.jinloes.prpilot.review.SourceInventoryFiles.Leaf;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Algorithm seam only: these tests are not evidence of a live IDE index or project import. */
class SourceInventoryServiceTest {
    private Path root;
    private FakeAuthority authority;
    private SourceInventoryService service;
    private AtomicLong clock;

    @Nested
    class NativeVfsWalk {
        private final Map<String, VfsNode> nodes = new HashMap<>();
        private boolean freshHandles, freshDirectories;
        private final com.intellij.openapi.vfs.DeprecatedVirtualFileSystem fileSystem =
                new com.intellij.openapi.vfs.DeprecatedVirtualFileSystem() {
                    @Override
                    public String getProtocol() {
                        return "file";
                    }

                    @Override
                    public com.intellij.openapi.vfs.VirtualFile findFileByPath(String path) {
                        return nodes.get(path);
                    }

                    @Override
                    public void refresh(boolean asynchronous) {
                        throw new AssertionError("No refresh");
                    }

                    @Override
                    public com.intellij.openapi.vfs.VirtualFile refreshAndFindFileByPath(
                            String path) {
                        throw new AssertionError("No refresh");
                    }
                };

        private VfsNode node(String path, boolean directory) {
            VfsNode node = new VfsNode(path, directory);
            nodes.put(node.getPath(), node);
            return node;
        }

        private SourceInventoryService.NativeAuthority nativeAuthority() {
            return new SourceInventoryService.NativeAuthority(
                    root, path -> handle(nodes.get(path)));
        }

        private VfsNode handle(VfsNode node) {
            return node == null || !freshHandles || node.directory && !freshDirectories
                    ? node
                    : new VfsNode(node);
        }

        @Test
        void equivalentHandlesAllowRepeatedIndependentWalksAndSourceRootDeduplication()
                throws Exception {
            node("", true);
            node("src", true);
            var file = node("src/Example.java", false);
            freshHandles = true;
            assertThat(handle(file)).isNotSameAs(file).isEqualTo(file);
            var nativeVfs = nativeAuthority();
            var model = authority.model(new Budget());
            var leaves = nativeVfs.leaves(model, new Budget());
            assertThat(leaves).extracting(Leaf::path).containsExactly("src/Example.java");
            assertThat(nativeVfs.leaves(model, new Budget())).isEqualTo(leaves);
            freshDirectories = true;
            assertThat(nativeVfs.leaves(model, new Budget())).isEqualTo(leaves);
        }

        @Test
        void equivalentDirectoryHandlesResolveTheSameSourceRoot() throws Exception {
            node("", true);
            node("src", true);
            freshHandles = true;
            freshDirectories = true;
            Location location = authority.location();
            location.setUrl("file://" + root.resolve("src"));
            location.setNativePath(root.resolve("src").toString());
            nativeAuthority().requireSourceDirectory(location, new Budget());
        }

        @Test
        void equivalentFileHandlesHashTheSameNativeBytes() throws Exception {
            node("", true);
            var file = node("src", false);
            freshHandles = true;
            assertThat(nativeAuthority().vfsHash("src", new Budget()))
                    .isEqualTo(
                            java.util.HexFormat.of()
                                    .formatHex(
                                            SourceInventory.digest("SHA-256").digest(file.bytes)));
        }

        @Test
        void samePathDifferentIdentityDuringWalkIsRejected() {
            node("", true);
            var file = node("src", false);
            var replacement = new VfsNode("src", false);
            assertThat(replacement.getPath()).isEqualTo(file.getPath());
            assertThat(replacement).isNotEqualTo(file);
            var nativeVfs =
                    new SourceInventoryService.NativeAuthority(
                            root,
                            path -> path.equals(file.getPath()) ? replacement : nodes.get(path));
            assertThatThrownBy(() -> nativeVfs.leaves(authority.model(new Budget()), new Budget()))
                    .hasMessageContaining("INVENTORY_CHANGED");
        }

        @Test
        void equivalentHandlesWithConflictingPathsAreRejected() {
            node("", true);
            var file = node("one", false);
            var alias = new VfsNode(file);
            alias.path = "two";
            nodes.put(alias.getPath(), alias);
            assertThat(alias).isEqualTo(file);
            assertThatThrownBy(
                            () ->
                                    nativeAuthority()
                                            .leaves(authority.model(new Budget()), new Budget()))
                    .hasMessageContaining("INVENTORY_CHANGED");
        }

        @Test
        void hashRejectsReplacementAndChangedEquivalentHandlesAndClosesStream() {
            for (String mutation :
                    List.of(
                            "replacement",
                            "stamp",
                            "length",
                            "directory",
                            "symlink",
                            "special",
                            "url",
                            "path",
                            "invalid")) {
                nodes.clear();
                node("", true);
                var file = node("src", false);
                var replacement =
                        mutation.equals("replacement")
                                ? new VfsNode("src", false)
                                : new VfsNode(file);
                switch (mutation) {
                    case "stamp" -> replacement.stamp++;
                    case "length" -> replacement.bytes = new byte[0];
                    case "directory" -> replacement.directory = true;
                    case "symlink" -> replacement.symlink = true;
                    case "special" -> replacement.special = true;
                    case "url" -> replacement.urlOverride = "file://" + root.resolve("other");
                    case "path" -> replacement.path = "other";
                    case "invalid" -> replacement.valid = false;
                    default -> {}
                }
                file.openHook = () -> nodes.put(root.resolve("src").toString(), replacement);
                assertThatThrownBy(() -> nativeAuthority().vfsHash("src", new Budget()))
                        .as(mutation)
                        .hasMessageContaining("CONTENT_MISMATCH");
                assertThat(file.streamClosed).as(mutation).isTrue();
            }
        }

        @Test
        void hashRejectsUrlOrDirectoryChangesOnTheOriginalHandle() {
            for (String mutation : List.of("url", "directory")) {
                nodes.clear();
                node("", true);
                var file = node("src", false);
                file.openHook =
                        () -> {
                            if (mutation.equals("url")) {
                                file.urlOverride = "file://" + root.resolve("other");
                            } else {
                                file.directory = true;
                            }
                        };
                assertThatThrownBy(() -> nativeAuthority().vfsHash("src", new Budget()))
                        .as(mutation)
                        .hasMessageContaining("CONTENT_MISMATCH");
                assertThat(file.streamClosed).as(mutation).isTrue();
            }
        }

        @Test
        void walksVirtualChildrenIncludingHiddenReincludedResourcesWithoutPhysicalEnumeration()
                throws Exception {
            node("", true);
            node(".git", true);
            node(".git/admin", false);
            node(".hidden", true);
            node(".hidden/excluded", true);
            node(".hidden/excluded/reincluded", true);
            node(".hidden/excluded/reincluded/extensionless", false);
            node("empty-generated", true);
            assertThat(nativeAuthority().leaves(authority.model(new Budget()), new Budget()))
                    .extracting(Leaf::path)
                    .containsExactly(".hidden/excluded/reincluded/extensionless");
            assertThat(Files.exists(root.resolve(".hidden"))).isFalse();
            // The real disk's extensionless leaf must not seed native enumeration.
        }

        @Test
        void independentlyWalksEveryResolvedSourceRootEvenWhenParentChildrenOmitIt()
                throws Exception {
            node("", true).omitChildren = true;
            node("src", true);
            node("src/resource", false);
            authority.sourceUrl = "file://" + root.resolve("src");
            var model = authority.model(new Budget());
            model.getContents()
                    .get(0)
                    .getSources()
                    .get(0)
                    .getLocation()
                    .setNativePath(root.resolve("src").toString());
            assertThat(nativeAuthority().leaves(model, new Budget()))
                    .extracting(Leaf::path)
                    .containsExactly("src/resource");
        }

        @Test
        void rejectsAliasedIdentitiesInvalidChildrenAndSourceSymlinkComponents() throws Exception {
            node("", true);
            var child = node("child", false);
            child.valid = false;
            assertThatThrownBy(
                            () ->
                                    nativeAuthority()
                                            .leaves(authority.model(new Budget()), new Budget()))
                    .hasMessageContaining("INVENTORY_CHANGED");
            child.valid = true;
            nodes.put(root.resolve("child").toString(), new VfsNode("different", false));
            assertThatThrownBy(
                            () ->
                                    nativeAuthority()
                                            .leaves(authority.model(new Budget()), new Budget()))
                    .hasMessageContaining("INVENTORY_CHANGED");
            nodes.clear();
            node("", true);
            node("link", true).symlink = true;
            Location location = new Location();
            location.setUrl("file://" + root.resolve("link"));
            location.setNativePath(root.resolve("link").toString());
            location.setScope(Scope.WORKTREE);
            assertThatThrownBy(
                            () -> nativeAuthority().requireSourceDirectory(location, new Budget()))
                    .hasMessageContaining("UNSAFE_PATH");
        }

        @Test
        void hashesVfsStreamAndRechecksIdentityStampAndLength() throws Exception {
            node("", true);
            var file = node("vfs-only", false);
            String hash = nativeAuthority().vfsHash("vfs-only", new Budget());
            assertThat(hash)
                    .isEqualTo(
                            java.util.HexFormat.of()
                                    .formatHex(
                                            SourceInventory.digest("SHA-256").digest(file.bytes)));
            assertThat(Files.exists(root.resolve("vfs-only"))).isFalse();
            file.openHook = () -> file.stamp++;
            assertThatThrownBy(() -> nativeAuthority().vfsHash("vfs-only", new Budget()))
                    .hasMessageContaining("CONTENT_MISMATCH");
        }

        private final class VfsNode extends com.intellij.openapi.vfs.VirtualFile {
            private final UUID id;
            private String path;
            private boolean directory;
            private byte[] bytes = SourceInventory.utf8("virtual source bytes");
            private long stamp;
            private boolean valid = true, symlink, special, omitChildren, streamClosed;
            private String urlOverride;
            private Runnable openHook = () -> {};

            VfsNode(String path, boolean directory) {
                id = UUID.randomUUID();
                this.path = path;
                this.directory = directory;
            }

            VfsNode(VfsNode original) {
                id = original.id;
                path = original.path;
                directory = original.directory;
                bytes = original.bytes;
                stamp = original.stamp;
                valid = original.valid;
                symlink = original.symlink;
                special = original.special;
                omitChildren = original.omitChildren;
                urlOverride = original.urlOverride;
                openHook = original.openHook;
            }

            // Native VirtualFileSystemEntry equality is persistent-file-ID based, not path based.
            @Override
            public boolean equals(Object other) {
                return other instanceof VfsNode node && id.equals(node.id);
            }

            @Override
            public int hashCode() {
                return id.hashCode();
            }

            @Override
            public String getUrl() {
                return urlOverride == null ? super.getUrl() : urlOverride;
            }

            @Override
            public String getName() {
                return path.isEmpty()
                        ? root.getFileName().toString()
                        : Path.of(path).getFileName().toString();
            }

            @Override
            public com.intellij.openapi.vfs.VirtualFileSystem getFileSystem() {
                return fileSystem;
            }

            @Override
            public String getPath() {
                return path.isEmpty() ? root.toString() : root.resolve(path).toString();
            }

            @Override
            public boolean isWritable() {
                return false;
            }

            @Override
            public boolean isDirectory() {
                return directory;
            }

            @Override
            public boolean isValid() {
                return valid;
            }

            @Override
            public boolean is(com.intellij.openapi.vfs.VFileProperty property) {
                return property == com.intellij.openapi.vfs.VFileProperty.SYMLINK && symlink
                        || property == com.intellij.openapi.vfs.VFileProperty.SPECIAL && special;
            }

            @Override
            public VfsNode getParent() {
                return nodes.get(Path.of(getPath()).getParent().toString());
            }

            @Override
            public VfsNode[] getChildren() {
                return omitChildren
                        ? new VfsNode[0]
                        : nodes.values().stream()
                                .filter(n -> equals(n.getParent()))
                                .sorted(Comparator.comparing(VfsNode::getName))
                                .map(NativeVfsWalk.this::handle)
                                .toArray(VfsNode[]::new);
            }

            @Override
            public VfsNode findChild(String name) {
                return handle(nodes.get(Path.of(getPath()).resolve(name).toString()));
            }

            @Override
            public java.io.OutputStream getOutputStream(
                    Object requestor, long stamp, long timestamp) {
                throw new AssertionError("No write");
            }

            @Override
            public byte[] contentsToByteArray() {
                throw new AssertionError("Use bounded stream");
            }

            @Override
            public long getTimeStamp() {
                return stamp;
            }

            @Override
            public long getModificationStamp() {
                return stamp;
            }

            @Override
            public long getLength() {
                return bytes.length;
            }

            @Override
            public void refresh(boolean async, boolean recursive, Runnable callback) {
                throw new AssertionError("No refresh");
            }

            @Override
            public java.io.InputStream getInputStream() {
                openHook.run();
                return new java.io.ByteArrayInputStream(bytes) {
                    @Override
                    public void close() throws IOException {
                        streamClosed = true;
                        super.close();
                    }
                };
            }
        }
    }

    @Test
    void sdkWrappedFilesystemMustNotBeThePhysicalVerificationBoundary() throws Exception {
        var provider =
                new com.intellij.platform.core.nio.fs.MultiRoutingFileSystemProvider(
                        root.getFileSystem().provider());
        Path wrapped = provider.wrapDelegatePath(root);
        assertThatThrownBy(() -> SourceInventoryFiles.scan(wrapped, new Budget()))
                .isInstanceOf(SourceInventoryFiles.Failure.class)
                .hasMessageContaining("UNSUPPORTED_API");
        com.jinloes.prpilot.review.SourceInventoryClientTest.exerciseWrappedCaller(
                provider::wrapDelegatePath);
    }

    @Test
    void installed262WrapperAlsoRequiresAndPassesExternalWorker() throws Exception {
        Path sdk =
                Path.of(
                        System.getProperty(
                                "sourceInventory.sdk262",
                                System.getProperty("user.home")
                                        + "/Applications/IntelliJ IDEA.app/Contents"));
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.isDirectory(sdk.resolve("lib")),
                "Set sourceInventory.sdk262 for the separately installed SDK262 regression");
        java.net.URL[] jars;
        try (var paths = Files.list(sdk.resolve("lib"))) {
            jars =
                    paths.filter(p -> p.toString().endsWith(".jar"))
                            .map(
                                    p -> {
                                        try {
                                            return p.toUri().toURL();
                                        } catch (java.net.MalformedURLException e) {
                                            throw new IllegalStateException(e);
                                        }
                                    })
                            .toArray(java.net.URL[]::new);
        }
        try (var loader = new java.net.URLClassLoader(jars, ClassLoader.getPlatformClassLoader())) {
            Class<?> type =
                    loader.loadClass(
                            "com.intellij.platform.core.nio.fs.MultiRoutingFileSystemProvider");
            assertThat(type.getClassLoader()).isSameAs(loader);
            Object provider =
                    type.getConstructor(java.nio.file.spi.FileSystemProvider.class)
                            .newInstance(root.getFileSystem().provider());
            var method = type.getMethod("wrapDelegatePath", Path.class);
            java.util.function.Function<Path, Path> wrap =
                    path -> {
                        try {
                            return (Path) method.invoke(provider, path);
                        } catch (ReflectiveOperationException e) {
                            throw new IllegalStateException(e);
                        }
                    };
            assertThatThrownBy(() -> SourceInventoryFiles.scan(wrap.apply(root), new Budget()))
                    .hasMessageContaining("UNSUPPORTED_API");
            com.jinloes.prpilot.review.SourceInventoryClientTest.exerciseWrappedCaller(wrap);
        }
    }

    @BeforeEach
    void setup() throws IOException {
        root = Files.createTempDirectory("native-inventory-").toRealPath();
        Files.writeString(root.resolve("extensionless"), "source");
        authority = new FakeAuthority();
        clock = new AtomicLong();
        service = new SourceInventoryService(authority, clock::get);
    }

    @AfterEach
    void cleanup() throws IOException {
        service.dispose();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private DiscoverRequest discoverRequest() {
        DiscoverRequest request = new DiscoverRequest();
        request.setSchemaVersion(2);
        request.setOperation(Operation.DISCOVER);
        request.setProjectPath(root.toString());
        request.setNonce(UUID.randomUUID().toString());
        return request;
    }

    private VerifyRequest verifyRequest(Response discovered) throws IOException {
        assertThat(discovered.getStatus()).isEqualTo(Status.DISCOVERED);
        VerifyRequest request = new VerifyRequest();
        request.setSchemaVersion(2);
        request.setOperation(Operation.VERIFY);
        request.setProjectPath(root.toString());
        request.setNonce(UUID.randomUUID().toString());
        request.setDiscoveryId(discovered.getDiscovery().getDiscoveryId());
        java.util.ArrayList<Hash> files = new java.util.ArrayList<>();
        for (Entry entry : discovered.getDiscovery().getEntries()) {
            if (entry.getMembership() == Membership.SOURCE) {
                files.add(
                        new Hash(
                                entry.getPath(),
                                SourceInventoryFiles.hash(root, entry.getPath(), new Budget(), null)
                                        .sha256()));
            }
        }
        request.setFiles(files);
        return request;
    }

    private void reason(Response response, ReasonCode reason) {
        assertThat(response.getStatus()).isIn(Status.BLOCKED, Status.UNSUPPORTED);
        assertThat(response.getReasons())
                .extracting(SourceInventory.Reason::getCode)
                .contains(reason);
        assertThat(response.getCoverage()).isNull();
        assertThat(response.getDiscovery()).isNull();
    }

    @Nested
    class Discovery {
        @Test
        void completeLeavesAndExplicitRootsAreNotReadiness() throws IOException {
            Files.createDirectories(root.resolve("excluded/reincluded"));
            Files.writeString(root.resolve("excluded/reincluded/generated"), "generated");
            Files.createDirectories(root.resolve(".idea"));
            Files.writeString(root.resolve(".idea/workspace.xml"), "metadata");
            authority.memberships.put(".idea/workspace.xml", Membership.OUTSIDE_SOURCE);
            Response response = service.execute(discoverRequest());
            assertThat(response.getStatus()).isEqualTo(Status.DISCOVERED);
            assertThat(response.getDiscovery().getEntries())
                    .extracting(Entry::getPath)
                    .containsExactly(
                            ".idea/workspace.xml",
                            "excluded/reincluded/generated",
                            "extensionless");
            assertThat(response.getDiscovery().getModel().getContents()).hasSize(1);
            assertThat(service.execute(verifyRequest(response)).getStatus())
                    .isEqualTo(Status.VFS_VERIFIED);
        }

        @Test
        void unknownMembershipBlocksRatherThanGuessing() {
            authority.memberships.put("extensionless", Membership.UNKNOWN);
            reason(service.execute(discoverRequest()), ReasonCode.UNKNOWN_MEMBERSHIP);
        }

        @Test
        void unsupportedVersionsAndWrongProject() {
            authority.build = "IU-261.1";
            reason(service.execute(discoverRequest()), ReasonCode.UNSUPPORTED_IDE);
            authority.build = "IU-262.1";
            DiscoverRequest request = discoverRequest();
            request.setProjectPath(root.resolve("other").toString());
            reason(service.execute(request), ReasonCode.WRONG_PROJECT);
        }

        @Test
        void sourceSymlinksBlockButNonSourceLinksAreNotRead() throws IOException {
            Files.createSymbolicLink(root.resolve("link"), Path.of("/missing"));
            reason(service.execute(discoverRequest()), ReasonCode.UNSAFE_PATH);
            authority.memberships.put("link", Membership.EXCLUDED);
            assertThat(service.execute(discoverRequest()).getStatus()).isEqualTo(Status.DISCOVERED);
        }

        @Test
        void epochChangesDuringCaptureBlock() {
            authority.classifyHook = () -> authority.epoch++;
            reason(service.execute(discoverRequest()), ReasonCode.MODEL_CHANGED);
        }
    }

    @Nested
    class Verification {
        @Test
        void emptyModelSourceRootLinksAndOversizedResponsesFailClosed() throws IOException {
            authority.emptyModel = true;
            authority.memberships.put("extensionless", Membership.OUTSIDE_SOURCE);
            VerifyRequest request = verifyRequest(service.execute(discoverRequest()));
            request.setFiles(List.of(new Hash("extensionless", "0".repeat(64))));
            reason(service.execute(request), ReasonCode.EMPTY_SOURCE_MODEL);
            authority.emptyModel = false;
            authority.memberships.clear();
            Files.createSymbolicLink(root.resolve("alias"), root);
            authority.memberships.put("alias", Membership.EXCLUDED);
            authority.sourceUrl = "file://" + root.resolve("alias");
            request = verifyRequest(service.execute(discoverRequest()));
            reason(service.execute(request), ReasonCode.UNSAFE_PATH);
            authority.sourceUrl = "file://" + root;
            authority.ignoredPatterns = "x".repeat(SourceInventory.MAX_JSON_BYTES);
            reason(service.execute(discoverRequest()), ReasonCode.LIMIT);
        }

        @Test
        void exactManifestAndIndependentVfsBytesAreRequired() throws IOException {
            VerifyRequest request = verifyRequest(service.execute(discoverRequest()));
            request.setFiles(List.of(new Hash("extra", request.getFiles().get(0).getSha256())));
            reason(service.execute(request), ReasonCode.MANIFEST_MISMATCH);
            request = verifyRequest(service.execute(discoverRequest()));
            authority.staleVfs = "0".repeat(64);
            reason(service.execute(request), ReasonCode.CONTENT_MISMATCH);
        }

        @Test
        void omittedAndExtraHashesFail() throws IOException {
            Files.writeString(root.resolve("second"), "second");
            VerifyRequest request = verifyRequest(service.execute(discoverRequest()));
            request.setFiles(List.of(request.getFiles().get(0)));
            reason(service.execute(request), ReasonCode.MANIFEST_MISMATCH);
            request = verifyRequest(service.execute(discoverRequest()));
            var hashes = new java.util.ArrayList<>(request.getFiles());
            hashes.add(new Hash("third", "0".repeat(64)));
            request.setFiles(SourceInventory.sorted(hashes));
            reason(service.execute(request), ReasonCode.MANIFEST_MISMATCH);
        }

        @Test
        void externalUnresolvedEmptyAndUnloadedModelsAreDiscoverableButNotCovered()
                throws IOException {
            for (Scope scope : List.of(Scope.EXTERNAL, Scope.NON_LOCAL, Scope.UNRESOLVED)) {
                authority.scope = scope;
                VerifyRequest request = verifyRequest(service.execute(discoverRequest()));
                reason(
                        service.execute(request),
                        scope == Scope.UNRESOLVED
                                ? ReasonCode.UNRESOLVED_SOURCE_ROOT
                                : ReasonCode.EXTERNAL_SOURCE_ROOT);
            }
            authority.scope = Scope.WORKTREE;
            authority.unloaded = List.of("unloaded");
            reason(
                    service.execute(verifyRequest(service.execute(discoverRequest()))),
                    ReasonCode.UNLOADED_MODULE);
        }

        @Test
        void editRevertEpochAndMembershipChangesInvalidateDiscovery() throws IOException {
            VerifyRequest request = verifyRequest(service.execute(discoverRequest()));
            authority.epoch += 2;
            reason(service.execute(request), ReasonCode.MODEL_CHANGED);
            request = verifyRequest(service.execute(discoverRequest()));
            authority.memberships.put("extensionless", Membership.EXCLUDED);
            reason(service.execute(request), ReasonCode.INVENTORY_CHANGED);
        }

        @Test
        void physicalContentChangeAndMidHashMutationBlock() throws IOException {
            VerifyRequest request = verifyRequest(service.execute(discoverRequest()));
            Files.writeString(root.resolve("extensionless"), "changed");
            reason(service.execute(request), ReasonCode.CONTENT_MISMATCH);
            request = verifyRequest(service.execute(discoverRequest()));
            authority.hashHook =
                    () -> {
                        try {
                            Files.writeString(root.resolve("new-leaf"), "new");
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    };
            reason(service.execute(request), ReasonCode.INVENTORY_CHANGED);
        }

        @Test
        void expiryCloseReopenReplacementAndConcurrentReplacementInvalidate() throws IOException {
            VerifyRequest request = verifyRequest(service.execute(discoverRequest()));
            clock.addAndGet(Duration.ofSeconds(180).toNanos());
            reason(service.execute(request), ReasonCode.STALE_DISCOVERY);
            request = verifyRequest(service.execute(discoverRequest()));
            service.execute(discoverRequest());
            reason(service.execute(request), ReasonCode.STALE_DISCOVERY);
            request = verifyRequest(service.execute(discoverRequest()));
            authority.hashHook = () -> service.execute(discoverRequest());
            reason(service.execute(request), ReasonCode.STALE_DISCOVERY);
            authority.hashHook = () -> {};
            request = verifyRequest(service.execute(discoverRequest()));
            service.dispose();
            service = new SourceInventoryService(authority, clock::get);
            reason(service.execute(request), ReasonCode.STALE_DISCOVERY);
        }

        @Test
        void retainedDiscoveryIsDeepCopied() throws IOException {
            Response response = service.execute(discoverRequest());
            VerifyRequest request = verifyRequest(response);
            response.getDiscovery().getModel().getContents().get(0).setModule("mutated");
            assertThat(service.execute(request).getStatus()).isEqualTo(Status.VFS_VERIFIED);
        }
    }

    private final class FakeAuthority implements SourceInventoryService.Authority {
        private long epoch;
        private String build = "IU-262.1";
        private Scope scope = Scope.WORKTREE;
        private List<String> unloaded = List.of();
        private final Map<String, Membership> memberships = new HashMap<>();
        private String staleVfs;
        private boolean emptyModel;
        private String sourceUrl = "file://" + root;
        private String ignoredPatterns = "*.generated";
        private Runnable hashHook = () -> {};
        private Runnable classifyHook = () -> {};

        @Override
        public Path root() {
            return root;
        }

        @Override
        public String build() {
            return build;
        }

        @Override
        public boolean closed() {
            return false;
        }

        @Override
        public Epochs epochs() {
            Epochs result = new Epochs();
            result.setRoots(Long.toString(epoch));
            result.setModules("0");
            result.setVfs("0");
            result.setFileTypes("0");
            return result;
        }

        private Location location() {
            Location result = new Location();
            result.setUrl(sourceUrl);
            result.setScope(scope);
            result.setNativePath(
                    scope == Scope.WORKTREE || scope == Scope.EXTERNAL ? root.toString() : null);
            return result;
        }

        @Override
        public Model model(Budget budget) {
            Source source = new Source();
            source.setLocation(location());
            source.setTypeClass("ResourceRoot");
            source.setTest(false);
            source.setGenerated(true);
            Content content = new Content();
            content.setModule("main");
            content.setLocation(location());
            content.setSources(List.of(source));
            content.setExclusions(List.of());
            content.setExcludePatterns(List.of("excluded"));
            Model result = new Model();
            result.setContents(emptyModel ? List.of() : List.of(content));
            result.setDependencySources(List.of());
            result.setDependencyClasses(List.of());
            result.setIgnoredPatterns(ignoredPatterns);
            result.setUnloadedModules(unloaded);
            return result;
        }

        @Override
        public List<Leaf> leaves(Model model, Budget budget) throws IOException {
            return SourceInventoryFiles.scan(root, budget);
        }

        @Override
        public void requireSourceDirectory(Location location, Budget budget) throws IOException {
            SourceInventoryFiles.requireDirectory(
                    SourceInventoryService.localPath(location.getUrl()), budget);
        }

        @Override
        public Entry classify(Leaf leaf, Budget budget) {
            classifyHook.run();
            Entry result = new Entry();
            result.setPath(leaf.path());
            result.setKind(leaf.kind());
            Membership membership = memberships.getOrDefault(leaf.path(), Membership.SOURCE);
            result.setMembership(membership);
            result.setModules(membership == Membership.SOURCE ? List.of("main") : List.of());
            result.setSourceRootUrl(membership == Membership.SOURCE ? sourceUrl : null);
            result.setGenerated(membership == Membership.SOURCE);
            result.setTest(false);
            return result;
        }

        @Override
        public String vfsHash(String path, Budget budget) throws IOException {
            hashHook.run();
            return staleVfs == null
                    ? SourceInventoryFiles.hash(root, path, budget, null).sha256()
                    : staleVfs;
        }
    }
}
