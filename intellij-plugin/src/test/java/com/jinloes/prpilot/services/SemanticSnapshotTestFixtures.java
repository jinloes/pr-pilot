package com.jinloes.prpilot.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectAware;
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectId;
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectListener;
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectTracker;
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemRefreshStatus;
import com.intellij.openapi.externalSystem.model.ProjectSystemId;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.BuildNumber;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.openapi.vfs.VFileProperty;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileSystem;
import com.intellij.util.messages.MessageBus;
import com.intellij.util.messages.MessageBusConnection;
import com.jinloes.prpilot.model.SemanticReviewContext;
import com.jinloes.prpilot.model.SourceInventory;
import com.jinloes.prpilot.review.SourceInventoryClient;
import com.jinloes.prpilot.review.SourceInventoryFiles;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Shared native-IDE fixtures for semantic snapshot and MCP tool tests. */
final class SemanticSnapshotTestFixtures {

    private SemanticSnapshotTestFixtures() {}

    static SemanticReviewContext.Request request(SemanticReviewContext.Operation operation) {
        var request = new SemanticReviewContext.Request();
        request.setSchemaVersion(2);
        request.setOperation(operation);
        request.setProjectPath("/fixture");
        request.setNonce(UUID.randomUUID().toString());
        if (operation != SemanticReviewContext.Operation.STATUS) {
            request.setDiscoveryId(UUID.randomUUID().toString());
            request.setFiles(List.of());
            request.setChangedRanges(List.of());
        }
        if (operation == SemanticReviewContext.Operation.VERIFY)
            request.setSnapshotId(UUID.randomUUID().toString());
        return request;
    }

    static DocumentEvent event(String oldText, String newText) {
        Document document = proxy(Document.class, (p, method, args) -> null);
        return new DocumentEvent(document) {
            @Override
            public int getOffset() {
                return 0;
            }

            @Override
            public int getOldLength() {
                return oldText.length();
            }

            @Override
            public int getNewLength() {
                return newText.length();
            }

            @Override
            public CharSequence getOldFragment() {
                return oldText;
            }

            @Override
            public CharSequence getNewFragment() {
                return newText;
            }

            @Override
            public long getOldTimeStamp() {
                return 1;
            }
        };
    }

    static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(
                Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class<?>[] {type},
                        (instance, method, arguments) -> {
                            if (method.getDeclaringClass() == Object.class) {
                                return switch (method.getName()) {
                                    case "toString" -> "fixture:" + type.getSimpleName();
                                    case "hashCode" -> System.identityHashCode(instance);
                                    case "equals" -> instance == arguments[0];
                                    default -> throw new AssertionError(method);
                                };
                            }
                            return handler.invoke(instance, method, arguments);
                        }));
    }

    /**
     * Runs real service/callback bodies; no IDE, import, physical worker or provider is launched.
     */
    static final class NativeFixture
            implements AutoCloseable, SemanticSnapshotService.NativeAccess {
        final Application previous = ApplicationManager.getApplication();
        final FakeInfo info = new FakeInfo();
        final List<Runnable> background = new ArrayList<>();
        final AtomicReference<DocumentListener> documents = new AtomicReference<>();
        final AtomicReference<Disposable> documentOwner = new AtomicReference<>();
        final AtomicReference<String> documentPath = new AtomicReference<>();
        final Map<Object, Object> subscriptions = new HashMap<>();
        int snapshotServiceRequests;
        final Project project;
        final SemanticSnapshotService service;
        final Path root;
        final List<BuildInput> linked = new ArrayList<>();
        final Map<String, SettingNode> settings = new LinkedHashMap<>();
        final List<SourceInventory.VerifyRequest> verifies = new ArrayList<>();
        final List<SourceInventory.Hash> files = new ArrayList<>();
        SourceInventoryService inventory;
        String discoveryId;
        boolean dumb, uncommitted, activeTask, sdk = true, dependencyValid = true, noModules;
        long rootsEpoch, psiEpoch, vfsEpoch;
        List<String> unsaved = List.of();
        Runnable physicalHook = () -> {};
        int physicalCaptures;
        Runnable verifyHook = () -> {};
        Set<ExternalSystemProjectId> expectedOverride;

        NativeFixture() {
            this(false);
        }

        NativeFixture(boolean nativeInputs) {
            try {
                root =
                        nativeInputs
                                ? Files.createTempDirectory("semantic-native-").toRealPath()
                                : Path.of("/fixture");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            var connection =
                    proxy(
                            MessageBusConnection.class,
                            (p, method, args) -> {
                                if (method.getName().equals("subscribe"))
                                    subscriptions.put(args[0], args[1]);
                                return null;
                            });
            var bus =
                    proxy(
                            MessageBus.class,
                            (p, method, args) -> {
                                if (method.getName().equals("connect")) return connection;
                                throw new AssertionError("Unexpected bus call " + method);
                            });
            project =
                    proxy(
                            Project.class,
                            (p, method, args) ->
                                    switch (method.getName()) {
                                        case "getBasePath" -> root.toString();
                                        case "getMessageBus" -> bus;
                                        case "isDisposed" -> false;
                                        case "getService" -> {
                                            if (args[0] == ExternalSystemProjectTracker.class)
                                                yield proxy(
                                                        ExternalSystemProjectTracker.class,
                                                        (o, m, a) -> {
                                                            throw new AssertionError(
                                                                    "No refresh/import allowed");
                                                        });
                                            if (args[0] == SemanticSnapshotService.class) {
                                                snapshotServiceRequests++;
                                                yield serviceValue();
                                            }
                                            throw new AssertionError(
                                                    "Unexpected service " + args[0]);
                                        }
                                        case "toString" -> "isolated-native-fixture";
                                        case "hashCode" -> System.identityHashCode(p);
                                        case "equals" -> p == args[0];
                                        default ->
                                                throw new AssertionError(
                                                        "Unexpected project call " + method);
                                    });
            ApplicationManager.setApplication(
                    proxy(
                            Application.class,
                            (p, method, args) -> {
                                if (method.getName().equals("isUnitTestMode")) return true;
                                if (method.getName().equals("getService")
                                        && args[0] == ApplicationInfo.class) return info;
                                if (method.getName().equals("executeOnPooledThread")) {
                                    background.add((Runnable) args[0]);
                                    return java.util.concurrent.CompletableFuture.completedFuture(
                                            null);
                                }
                                if (method.getName().equals("runReadAction")) {
                                    if (args[0] instanceof ThrowableComputable<?, ?> computation)
                                        return computation.compute();
                                    if (args[0] instanceof Computable<?> computation)
                                        return computation.compute();
                                    ((Runnable) args[0]).run();
                                    return null;
                                }
                                throw new AssertionError("Unexpected application call " + method);
                            }));
            java.util.function.BiConsumer<DocumentListener, Disposable> subscribe =
                    (listener, owner) -> {
                        documents.set(listener);
                        documentOwner.set(owner);
                    };
            service =
                    nativeInputs
                            ? new SemanticSnapshotService(
                                    project, subscribe, document -> documentPath.get(), this)
                            : new SemanticSnapshotService(
                                    project, subscribe, document -> documentPath.get());
            if (nativeInputs) {
                linked.add(new BuildInput(root.toString()));
                linked.add(new BuildInput(root.resolve("included").toString()));
                inventory = new SourceInventoryService(new InventoryInput(), System::nanoTime);
                var discover = new SourceInventory.DiscoverRequest();
                discover.setSchemaVersion(2);
                discover.setOperation(SourceInventory.Operation.DISCOVER);
                discover.setNonce(UUID.randomUUID().toString());
                discover.setProjectPath(root.toString());
                var result = inventory.execute(discover);
                assertThat(result.getStatus())
                        .as(result.getReasons().toString())
                        .isEqualTo(SourceInventory.Status.DISCOVERED);
                discoveryId = result.getDiscovery().getDiscoveryId();
                SourceInventory.Hash hash = new SourceInventory.Hash();
                hash.setPath("Example.java");
                hash.setSha256(hash(SourceInventory.utf8("class Example {}")));
                files.add(hash);
            }
        }

        SemanticReviewContext.Request requestFor(SemanticReviewContext.Operation operation) {
            var input = request(operation);
            input.setProjectPath(root.toString());
            if (operation != SemanticReviewContext.Operation.STATUS) {
                input.setDiscoveryId(discoveryId);
                input.setFiles(files);
            }
            return input;
        }

        SemanticReviewContext.Snapshot execute(SemanticReviewContext.Operation operation) {
            return service.execute(requestFor(operation));
        }

        void drainOne() {
            background.remove(0).run();
        }

        void reload(ExternalSystemRefreshStatus status) {
            linked.forEach(input -> input.listener.onProjectReloadStart());
            linked.forEach(input -> input.listener.onProjectReloadFinish(status));
        }

        void establish() {
            assertThat(execute(SemanticReviewContext.Operation.STATUS).getStatus())
                    .isEqualTo(SemanticReviewContext.Status.ARMING);
            drainOne();
            drainOne();
            reload(ExternalSystemRefreshStatus.SUCCESS);
            drainOne();
            drainOne();
            var result = execute(SemanticReviewContext.Operation.CAPTURE);
            assertThat(result.getStatus())
                    .as(result.getReasons().toString())
                    .isEqualTo(SemanticReviewContext.Status.READY);
        }

        final class BuildInput {
            final ExternalSystemProjectId id;
            final Set<String> paths = new java.util.HashSet<>();
            ExternalSystemProjectAware aware;
            ExternalSystemProjectListener listener;
            boolean dataCurrent = true, settingsCurrent = true;
            SemanticSnapshotService.ImportedFacts imported =
                    new SemanticSnapshotService.ImportedFacts(true, 1, 1);

            BuildInput(String path) {
                id = new ExternalSystemProjectId(new ProjectSystemId("GRADLE"), path);
                String setting = path + "/build.gradle";
                paths.add(setting);
                settings.put(setting, new SettingNode(setting));
                aware =
                        proxy(
                                ExternalSystemProjectAware.class,
                                (p, method, args) ->
                                        switch (method.getName()) {
                                            case "getProjectId" -> id;
                                            case "getSettingsFiles" -> Set.copyOf(paths);
                                            case "subscribe" -> {
                                                listener = (ExternalSystemProjectListener) args[0];
                                                assertThat(args[1])
                                                        .isInstanceOf(
                                                                SemanticSnapshotService.class);
                                                yield null;
                                            }
                                            default ->
                                                    throw new AssertionError(
                                                            "Unexpected native action " + method);
                                        });
            }
        }

        @Override
        public Map<ExternalSystemProjectId, SemanticSnapshotService.TrackerRow> trackerRows() {
            Map<ExternalSystemProjectId, SemanticSnapshotService.TrackerRow> result =
                    new LinkedHashMap<>();
            for (var input : linked) {
                result.put(
                        input.id,
                        new SemanticSnapshotService.TrackerRow(
                                input.aware,
                                (java.util.function.BooleanSupplier) () -> input.dataCurrent,
                                (java.util.function.BooleanSupplier) () -> input.settingsCurrent));
            }
            return result;
        }

        @Override
        public Set<ExternalSystemProjectId> expectedBuilds() {
            return expectedOverride == null
                    ? linked.stream()
                            .map(input -> input.id)
                            .collect(java.util.stream.Collectors.toSet())
                    : expectedOverride;
        }

        @Override
        public List<SemanticSnapshotService.ModuleFacts> modules() {
            return noModules
                    ? List.of()
                    : List.of(
                            new SemanticSnapshotService.ModuleFacts(
                                    "main",
                                    "GRADLE",
                                    root.toString(),
                                    false,
                                    sdk,
                                    "/fixture-jdk",
                                    List.of(
                                            new SemanticSnapshotService.OrderFacts(
                                                    "dependency", dependencyValid)),
                                    List.of("file://" + root),
                                    List.of("file://" + root)));
        }

        @Override
        public SemanticSnapshotService.ImportedFacts imported(ExternalSystemProjectId id) {
            return linked.stream()
                    .filter(input -> input.id.equals(id))
                    .findFirst()
                    .orElseThrow()
                    .imported;
        }

        @Override
        public boolean upToDate(Object target) {
            return ((java.util.function.BooleanSupplier) target).getAsBoolean();
        }

        @Override
        public SemanticSnapshotService.Counters counters() {
            return new SemanticSnapshotService.Counters(
                    rootsEpoch, psiEpoch, vfsEpoch, dumb, uncommitted);
        }

        @Override
        public List<String> unsavedPaths() {
            return unsaved;
        }

        @Override
        public boolean activeTask() {
            return activeTask;
        }

        @Override
        public SemanticSnapshotService.SettingFile setting(String path) {
            SettingNode node = settings.get(path);
            return new SemanticSnapshotService.SettingFile(
                    node == null || !node.present ? null : node,
                    node != null && node.unsaved,
                    node == null || node.committed);
        }

        @Override
        public SourceInventoryClient.SettingsCapture captureSettings(List<String> paths) {
            physicalCaptures++;
            physicalHook.run();
            return new SourceInventoryClient.SettingsCapture(
                    root.toString(),
                    paths.stream()
                            .map(
                                    path -> {
                                        SettingNode node = settings.get(path);
                                        return new SourceInventoryFiles.Setting(
                                                path,
                                                node != null && node.diskPresent,
                                                node != null && node.diskPresent
                                                        ? hash(node.diskBytes)
                                                        : null,
                                                node == null
                                                        ? "a".repeat(64)
                                                        : node.physicalIdentity);
                                    })
                            .toList());
        }

        @Override
        public SourceInventory.Response verify(SourceInventory.VerifyRequest input) {
            verifies.add(input);
            verifyHook.run();
            return inventory.execute(input);
        }

        private final class InventoryInput implements SourceInventoryService.Authority {
            @Override
            public Path root() {
                return root;
            }

            @Override
            public String build() {
                return info.build;
            }

            @Override
            public boolean closed() {
                return false;
            }

            @Override
            public SourceInventory.Epochs epochs() {
                var epochs = new SourceInventory.Epochs();
                epochs.setRoots("0");
                epochs.setModules("0");
                epochs.setVfs("0");
                epochs.setFileTypes("0");
                return epochs;
            }

            @Override
            public SourceInventory.Model model(SourceInventoryFiles.Budget budget) {
                var location = new SourceInventory.Location();
                location.setUrl("file://" + root);
                location.setNativePath(root.toString());
                location.setScope(SourceInventory.Scope.WORKTREE);
                var source = new SourceInventory.Source();
                source.setLocation(location);
                source.setTypeClass("JavaSourceRoot");
                source.setTest(false);
                source.setGenerated(false);
                var content = new SourceInventory.Content();
                content.setModule("main");
                content.setLocation(location);
                content.setSources(List.of(source));
                content.setExclusions(List.of());
                content.setExcludePatterns(List.of());
                var model = new SourceInventory.Model();
                model.setContents(List.of(content));
                model.setDependencySources(List.of());
                model.setDependencyClasses(List.of());
                model.setIgnoredPatterns("");
                model.setUnloadedModules(List.of());
                return model;
            }

            @Override
            public List<SourceInventoryFiles.Leaf> leaves(
                    SourceInventory.Model model, SourceInventoryFiles.Budget budget) {
                return List.of(
                        new SourceInventoryFiles.Leaf(
                                "Example.java", SourceInventory.Kind.FILE, null));
            }

            @Override
            public void requireSourceDirectory(
                    SourceInventory.Location location, SourceInventoryFiles.Budget budget) {
                assertThat(location.getNativePath()).isEqualTo(root.toString());
            }

            @Override
            public SourceInventory.Entry classify(
                    SourceInventoryFiles.Leaf leaf, SourceInventoryFiles.Budget budget) {
                var entry = new SourceInventory.Entry();
                entry.setPath(leaf.path());
                entry.setKind(leaf.kind());
                entry.setMembership(SourceInventory.Membership.SOURCE);
                entry.setModules(List.of("main"));
                entry.setSourceRootUrl("file://" + root);
                entry.setTest(false);
                entry.setGenerated(false);
                return entry;
            }

            @Override
            public String vfsHash(String path, SourceInventoryFiles.Budget budget) {
                assertThat(path).isEqualTo("Example.java");
                return hash(SourceInventory.utf8("class Example {}"));
            }
        }

        private SemanticSnapshotService serviceValue() {
            return service;
        }

        Observed observe(Set<String> paths) throws Exception {
            var listener = new AtomicReference<ExternalSystemProjectListener>();
            var owner = new AtomicReference<Disposable>();
            var id =
                    new ExternalSystemProjectId(
                            new ProjectSystemId("GRADLE"), "/fixture/" + UUID.randomUUID());
            var aware =
                    proxy(
                            ExternalSystemProjectAware.class,
                            (p, method, args) ->
                                    switch (method.getName()) {
                                        case "getSettingsFiles" -> paths;
                                        case "getProjectId" -> id;
                                        case "subscribe" -> {
                                            listener.set((ExternalSystemProjectListener) args[0]);
                                            owner.set((Disposable) args[1]);
                                            yield null;
                                        }
                                        default ->
                                                throw new AssertionError(
                                                        "Unexpected external action " + method);
                                    });
            Class<?> type =
                    Class.forName(SemanticSnapshotService.class.getName() + "$ObservedBuild");
            var constructor = type.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            Object build = constructor.newInstance(service, aware, new Object(), new Object());
            var field = SemanticSnapshotService.class.getDeclaredField("builds");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var builds = (Map<ExternalSystemProjectId, Object>) field.get(service);
            builds.put(id, build);
            var baseline = type.getDeclaredField("baseline");
            baseline.setAccessible(true);
            return new Observed(
                    (SemanticSnapshotService.ImportBaseline) baseline.get(build),
                    listener.get(),
                    owner.get());
        }

        @Override
        public void close() {
            service.dispose();
            if (inventory != null) inventory.dispose();
            ApplicationManager.setApplication(previous);
            if (!root.toString().equals("/fixture")) {
                try {
                    Files.delete(root);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    static String hash(byte[] bytes) {
        return HexFormat.of().formatHex(SourceInventory.digest("SHA-256").digest(bytes));
    }

    static final class SettingNode extends VirtualFile {
        final String path;
        byte[] bytes = SourceInventory.utf8("plugins {}");
        byte[] diskBytes = bytes;
        String physicalIdentity = "a".repeat(64);
        long stamp = 1;
        boolean valid = true,
                present = true,
                diskPresent = true,
                unsaved,
                committed = true,
                symlink;
        Runnable readHook = () -> {};
        int contentReads;

        SettingNode(String path) {
            this.path = path;
        }

        @Override
        public String getName() {
            return Path.of(path).getFileName().toString();
        }

        @Override
        public VirtualFileSystem getFileSystem() {
            throw new AssertionError("No filesystem access");
        }

        @Override
        public String getPath() {
            return path;
        }

        @Override
        public boolean isWritable() {
            return false;
        }

        @Override
        public boolean isDirectory() {
            return false;
        }

        @Override
        public boolean isValid() {
            return valid;
        }

        @Override
        public boolean is(VFileProperty property) {
            return property == VFileProperty.SYMLINK && symlink;
        }

        @Override
        public VirtualFile getParent() {
            throw new AssertionError("No parent access");
        }

        @Override
        public VirtualFile[] getChildren() {
            throw new AssertionError("No child access");
        }

        @Override
        public OutputStream getOutputStream(Object requestor, long stamp, long timestamp) {
            throw new AssertionError("No write");
        }

        @Override
        public byte[] contentsToByteArray() {
            contentReads++;
            return bytes.clone();
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
        public void refresh(boolean asynchronous, boolean recursive, Runnable postRunnable) {
            throw new AssertionError("No refresh");
        }

        @Override
        public InputStream getInputStream() {
            contentReads++;
            readHook.run();
            return new ByteArrayInputStream(bytes);
        }
    }

    record Observed(
            SemanticSnapshotService.ImportBaseline baseline,
            ExternalSystemProjectListener listener,
            Disposable owner) {}

    static final class FakeInfo extends ApplicationInfo {
        String build = "IU-262.1";

        @Override
        public BuildNumber getBuild() {
            return BuildNumber.fromString(build);
        }

        @Override
        public Calendar getBuildDate() {
            return Calendar.getInstance();
        }

        @Override
        public ZonedDateTime getBuildTime() {
            return ZonedDateTime.now();
        }

        @Override
        public String getApiVersion() {
            return "262";
        }

        @Override
        public String getMajorVersion() {
            return "2026";
        }

        @Override
        public String getMinorVersion() {
            return "2";
        }

        @Override
        public String getMicroVersion() {
            return "0";
        }

        @Override
        public String getPatchVersion() {
            return "0";
        }

        @Override
        public String getVersionName() {
            return "fixture";
        }

        @Override
        public String getCompanyName() {
            return "fixture";
        }

        @Override
        public String getShortCompanyName() {
            return "fixture";
        }

        @Override
        public String getCompanyURL() {
            return "https://example.invalid";
        }

        @Override
        public String getFullVersion() {
            return "2026.2";
        }

        @Override
        public String getStrictVersion() {
            return "2026.2";
        }

        @Override
        public String getFullApplicationName() {
            return "fixture";
        }

        @Override
        public boolean isEssentialPlugin(String id) {
            return false;
        }

        @Override
        public boolean isEssentialPlugin(PluginId id) {
            return false;
        }
    }
}
