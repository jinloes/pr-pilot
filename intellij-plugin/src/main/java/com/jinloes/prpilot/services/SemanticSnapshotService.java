package com.jinloes.prpilot.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.ide.actions.QualifiedNameProvider;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.externalSystem.ExternalSystemManager;
import com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager;
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectAware;
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectId;
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectListener;
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectTracker;
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemRefreshStatus;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType;
import com.intellij.openapi.externalSystem.service.internal.ExternalSystemProcessingManager;
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderEntry;
import com.intellij.openapi.roots.ProjectRootModificationTracker;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VFileProperty;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiNameIdentifierOwner;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.util.PsiTreeUtil;
import com.jinloes.prpilot.model.SemanticReviewContext;
import com.jinloes.prpilot.model.SemanticReviewContext.Declaration;
import com.jinloes.prpilot.model.SemanticReviewContext.Request;
import com.jinloes.prpilot.model.SemanticReviewContext.Snapshot;
import com.jinloes.prpilot.model.SemanticReviewContext.Status;
import com.jinloes.prpilot.model.SourceInventory;
import com.jinloes.prpilot.review.SemanticRuntime;
import com.jinloes.prpilot.review.SourceInventoryClient;
import com.jinloes.prpilot.review.SourceInventoryFiles;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Native readiness only. All physical settings reads are delegated to the packaged fork. */
public final class SemanticSnapshotService implements Disposable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Project project;
    private final NativeAccess nativeAccess;
    private final String instance = UUID.randomUUID().toString();
    private final Map<ExternalSystemProjectId, ObservedBuild> builds = new HashMap<>();
    private final AtomicLong importEpoch = new AtomicLong();
    private final AtomicLong indexEpoch = new AtomicLong();
    private volatile boolean disposed;
    private Snapshot captured;

    public SemanticSnapshotService(Project project) {
        this(
                project,
                (listener, owner) ->
                        EditorFactory.getInstance()
                                .getEventMulticaster()
                                .addDocumentListener(listener, owner),
                document -> {
                    VirtualFile file = FileDocumentManager.getInstance().getFile(document);
                    return file == null ? null : file.getPath();
                });
    }

    SemanticSnapshotService(
            Project project,
            BiConsumer<DocumentListener, Disposable> documentSubscription,
            Function<Document, String> documentPath) {
        this(project, documentSubscription, documentPath, new PlatformNativeAccess(project));
    }

    SemanticSnapshotService(
            Project project,
            BiConsumer<DocumentListener, Disposable> documentSubscription,
            Function<Document, String> documentPath,
            NativeAccess nativeAccess) {
        this.project = project;
        this.nativeAccess = nativeAccess;
        documentSubscription.accept(
                new DocumentListener() {
                    @Override
                    public void beforeDocumentChange(DocumentEvent event) {
                        String path = documentPath.apply(event.getDocument());
                        if (path == null) return;
                        synchronized (builds) {
                            for (ObservedBuild build : builds.values()) {
                                if (build.paths.contains(path)) {
                                    build.baseline.invalidate();
                                    importEpoch.incrementAndGet();
                                }
                            }
                        }
                    }
                },
                this);
        project.getMessageBus()
                .connect(this)
                .subscribe(
                        DumbService.DUMB_MODE,
                        new DumbService.DumbModeListener() {
                            @Override
                            public void enteredDumbMode() {
                                indexEpoch.incrementAndGet();
                            }

                            @Override
                            public void exitDumbMode() {
                                indexEpoch.incrementAndGet();
                            }
                        });
        project.getMessageBus()
                .connect(this)
                .subscribe(
                        VirtualFileManager.VFS_CHANGES,
                        new BulkFileListener() {
                            @Override
                            public void before(List<? extends VFileEvent> events) {
                                synchronized (builds) {
                                    for (ObservedBuild build : builds.values()) {
                                        if (events.stream()
                                                .anyMatch(
                                                        event ->
                                                                build.paths.stream()
                                                                        .anyMatch(
                                                                                path ->
                                                                                        path.equals(
                                                                                                        event
                                                                                                                .getPath())
                                                                                                || path
                                                                                                        .startsWith(
                                                                                                                event
                                                                                                                                .getPath()
                                                                                                                        + "/")))) {
                                            build.baseline.invalidate();
                                            importEpoch.incrementAndGet();
                                        }
                                    }
                                }
                            }
                        });
    }

    enum Phase {
        UNARMED,
        ARMING,
        ARMED,
        STARTED,
        VERIFYING,
        BASELINED
    }

    record NativeSetting(String path, boolean present, String hash, long stamp) {}

    record Receipt(
            List<NativeSetting> nativeFiles, List<SourceInventoryFiles.Setting> physicalFiles) {
        Receipt {
            nativeFiles = List.copyOf(nativeFiles);
            physicalFiles = List.copyOf(physicalFiles);
        }
    }

    /** Callback state is memory-only; a late asynchronous completion cannot restore authority. */
    static final class ImportBaseline {
        private long generation;
        private Phase phase = Phase.UNARMED;
        private Receipt receipt;

        synchronized Phase phase() {
            return phase;
        }

        synchronized long arm() {
            if (phase != Phase.UNARMED) return -1;
            phase = Phase.ARMING;
            return generation;
        }

        synchronized void armed(long token, Receipt value) {
            if (generation == token && phase == Phase.ARMING) {
                receipt = value;
                phase = Phase.ARMED;
            }
        }

        synchronized void start(List<Long> stamps) {
            if (phase == Phase.ARMED
                    && receipt != null
                    && receipt.nativeFiles().stream()
                            .map(NativeSetting::stamp)
                            .toList()
                            .equals(stamps)) {
                phase = Phase.STARTED;
            } else invalidate();
        }

        synchronized long success() {
            if (phase != Phase.STARTED) {
                invalidate();
                return -1;
            }
            phase = Phase.VERIFYING;
            return generation;
        }

        synchronized void finish(long token, Receipt current) {
            if (generation != token || phase != Phase.VERIFYING) return;
            if (Objects.equals(receipt, current)) phase = Phase.BASELINED;
            else invalidate();
        }

        synchronized boolean current(Receipt current) {
            if (phase != Phase.BASELINED || !Objects.equals(receipt, current)) {
                invalidate();
                return false;
            }
            return true;
        }

        synchronized void invalidate() {
            generation++;
            phase = Phase.UNARMED;
            receipt = null;
        }
    }

    private final class ObservedBuild {
        final ExternalSystemProjectAware aware;
        final Object data;
        final Object settingsTracker;
        final ImportBaseline baseline = new ImportBaseline();
        volatile List<String> paths;
        volatile String failure;

        ObservedBuild(ExternalSystemProjectAware aware, Object data, Object settingsTracker)
                throws IOException {
            this.aware = aware;
            this.data = data;
            this.settingsTracker = settingsTracker;
            paths = settingPaths(aware);
            aware.subscribe(
                    new ExternalSystemProjectListener() {
                        @Override
                        public void onProjectReloadStart() {
                            importEpoch.incrementAndGet();
                            baseline.start(nativeStamps(paths));
                        }

                        @Override
                        public void onProjectReloadFinish(ExternalSystemRefreshStatus status) {
                            importEpoch.incrementAndGet();
                            if (status != ExternalSystemRefreshStatus.SUCCESS) {
                                baseline.invalidate();
                                return;
                            }
                            long token = baseline.success();
                            if (token >= 0)
                                background(
                                        () -> baseline.finish(token, receipt(ObservedBuild.this)));
                        }

                        @Override
                        public void onSettingsFilesListChange() {
                            baseline.invalidate();
                            importEpoch.incrementAndGet();
                        }
                    },
                    SemanticSnapshotService.this);
        }

        void background(IoOperation action) {
            ApplicationManager.getApplication()
                    .executeOnPooledThread(
                            () -> {
                                try {
                                    if (disposed || project.isDisposed()) return;
                                    action.run();
                                    if (disposed || project.isDisposed()) baseline.invalidate();
                                } catch (Exception | LinkageError e) {
                                    failure =
                                            e instanceof IOException
                                                    ? e.getMessage()
                                                            + "; native-declared settings: "
                                                            + (paths.size() <= 20
                                                                    ? paths
                                                                    : paths.size() + " paths")
                                                    : "Unsupported native import API";
                                    baseline.invalidate();
                                }
                            });
        }

        void arm() {
            long token = baseline.arm();
            if (token >= 0)
                background(
                        () -> {
                            Receipt value = receipt(this);
                            baseline.armed(token, value);
                            failure = null;
                        });
        }
    }

    @FunctionalInterface
    private interface IoOperation {
        void run() throws Exception;
    }

    public Snapshot execute(Request request) {
        SemanticReviewContext.validate(request);
        Snapshot response = new Snapshot();
        response.setNonce(request.getNonce());
        response.setProjectPath(project.getBasePath());
        response.setProjectInstanceId(instance);
        response.setIdeBuild(ApplicationInfo.getInstance().getBuild().asString());
        try {
            if (disposed || project.isDisposed())
                throw new IOException("Project closed; manual sync required");
            if (!Objects.equals(project.getBasePath(), request.getProjectPath())) {
                throw new IOException("WRONG_PROJECT");
            }
            if (!SourceInventoryService.supportsBuild(response.getIdeBuild())) {
                throw new UnsupportedOperationException("IntelliJ 262 required");
            }
            reconcileBuilds();
            String before = nativeEpochs();
            List<ObservedBuild> linked;
            synchronized (builds) {
                linked = List.copyOf(builds.values());
            }
            if (request.getOperation() == SemanticReviewContext.Operation.STATUS) {
                linked.forEach(ObservedBuild::arm);
            }
            List<Receipt> receipts = new ArrayList<>();
            for (ObservedBuild build : linked) {
                if (build.baseline.phase() != Phase.BASELINED) {
                    response.setStatus(
                            build.baseline.phase() == Phase.ARMING
                                    ? Status.ARMING
                                    : Status.MANUAL_SYNC_REQUIRED);
                    response.setReasons(
                            List.of(
                                    build.failure == null
                                            ? "Wait for arming, then manually sync every linked build"
                                            : build.failure));
                    return response;
                }
                Receipt current = receipt(build);
                if (!build.baseline.current(current)) {
                    response.setStatus(Status.MANUAL_SYNC_REQUIRED);
                    response.setReasons(List.of("Settings changed; rearm then manually sync"));
                    return response;
                }
                if (!nativeAccess.upToDate(build.data)
                        || !nativeAccess.upToDate(build.settingsTracker)) {
                    build.baseline.invalidate();
                    throw new IOException("Linked build is not up to date; manually sync");
                }
                var id = build.aware.getProjectId();
                var imported = nativeAccess.imported(id);
                if (imported == null
                        || !imported.structurePresent()
                        || imported.successTimestamp() <= 0
                        || imported.importTimestamp() > imported.successTimestamp()) {
                    throw new IOException("Missing or failed imported build model");
                }
                receipts.add(current);
            }
            requireReady();
            response.setSettingsDigest(digest(receipts));
            response.setModelDigest(ReadAction.compute(() -> modelDigest()));
            response.setEpochs(nativeEpochs());
            if (!before.equals(response.getEpochs())) throw new IOException("Native state changed");
            if (request.getOperation() == SemanticReviewContext.Operation.STATUS) {
                response.setStatus(Status.ELIGIBLE);
                return response;
            }
            SourceInventory.VerifyRequest verify = new SourceInventory.VerifyRequest();
            verify.setSchemaVersion(2);
            verify.setOperation(SourceInventory.Operation.VERIFY);
            verify.setNonce(request.getNonce());
            verify.setProjectPath(request.getProjectPath());
            verify.setDiscoveryId(request.getDiscoveryId());
            verify.setFiles(request.getFiles());
            SourceInventory.Response inventory = nativeAccess.verify(verify);
            if (inventory.getStatus() != SourceInventory.Status.VFS_VERIFIED) {
                throw new IOException("Native source VERIFY rejected current discovery");
            }
            response.setCoverageIdentity(digest(inventory.getCoverage()));
            response.setSourceDigest(inventory.getCoverage().getSourceManifestSha256());
            DeclarationEvidence declarations = ReadAction.compute(() -> declarations(request));
            response.setDeclarations(declarations.values());
            response.setDeclarationLimitations(declarations.limitations());
            requireReady();
            if (!before.equals(nativeEpochs()))
                throw new IOException("Native state changed during capture");
            response.setStatus(Status.READY);
            synchronized (this) {
                if (request.getOperation() == SemanticReviewContext.Operation.VERIFY) {
                    if (captured == null
                            || !Objects.equals(captured.getSnapshotId(), request.getSnapshotId())
                            || !same(captured, response)) throw new IOException("STALE_SNAPSHOT");
                    response.setSnapshotId(captured.getSnapshotId());
                } else {
                    response.setSnapshotId(UUID.randomUUID().toString());
                    captured = JSON.readValue(JSON.writeValueAsBytes(response), Snapshot.class);
                }
            }
            if (JSON.writeValueAsBytes(response).length > SourceInventory.MAX_JSON_BYTES) {
                throw new IOException("Snapshot limit");
            }
        } catch (UnsupportedOperationException | ReflectiveOperationException | LinkageError e) {
            response.setStatus(Status.UNSUPPORTED);
            response.setReasons(List.of("Unsupported linked-build observer or native API"));
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            response.setStatus(Status.NOT_READY);
            response.setReasons(
                    List.of(e instanceof IOException ? e.getMessage() : "Native readiness failed"));
        }
        return response;
    }

    private static boolean same(Snapshot left, Snapshot right) {
        return Objects.equals(left.getCoverageIdentity(), right.getCoverageIdentity())
                && Objects.equals(left.getSourceDigest(), right.getSourceDigest())
                && Objects.equals(left.getModelDigest(), right.getModelDigest())
                && Objects.equals(left.getSettingsDigest(), right.getSettingsDigest())
                && Objects.equals(left.getEpochs(), right.getEpochs())
                && Objects.equals(left.getProjectInstanceId(), right.getProjectInstanceId());
    }

    private void reconcileBuilds() throws ReflectiveOperationException, IOException {
        Map<ExternalSystemProjectId, TrackerRow> current = nativeAccess.trackerRows();
        Set<ExternalSystemProjectId> expected = nativeAccess.expectedBuilds();
        if (!expected.equals(current.keySet()))
            throw new UnsupportedOperationException("Unrepresented linked build");
        for (ModuleFacts module : nativeAccess.modules()) {
            if (module.mavenized() || module.system() != null) {
                if (expected.stream()
                        .noneMatch(
                                id ->
                                        id.getSystemId().getId().equals(module.system())
                                                && id.getExternalProjectPath()
                                                        .equals(module.externalRoot()))) {
                    throw new UnsupportedOperationException("Unrepresented imported module");
                }
            }
        }
        synchronized (builds) {
            if (!builds.keySet().equals(current.keySet())) {
                for (ObservedBuild build : builds.values()) build.baseline.invalidate();
                importEpoch.incrementAndGet();
            }
            for (var entry : current.entrySet()) {
                ExternalSystemProjectId id = entry.getKey();
                TrackerRow row = entry.getValue();
                ExternalSystemProjectAware aware = row.aware();
                ObservedBuild existing = builds.get(id);
                if (existing != null && existing.aware != aware) {
                    existing.baseline.invalidate();
                    throw new UnsupportedOperationException("Observer replaced; reopen and sync");
                }
                if (existing == null)
                    builds.put(id, new ObservedBuild(aware, row.data(), row.settingsTracker()));
                else if (!existing.paths.equals(settingPaths(aware))) {
                    existing.baseline.invalidate();
                    existing.paths = settingPaths(aware);
                    importEpoch.incrementAndGet();
                }
            }
            if (!builds.keySet().equals(current.keySet())) {
                throw new UnsupportedOperationException("Linked build removed; reopen and sync");
            }
        }
    }

    record TrackerRow(ExternalSystemProjectAware aware, Object data, Object settingsTracker) {}

    record OrderFacts(String name, boolean valid) {}

    record ModuleFacts(
            String name,
            String system,
            String externalRoot,
            boolean mavenized,
            boolean sdkPresent,
            String sdkHome,
            List<OrderFacts> entries,
            List<String> contentUrls,
            List<String> sourceUrls) {}

    record ImportedFacts(boolean structurePresent, long importTimestamp, long successTimestamp) {}

    record Counters(long roots, long psi, long vfs, boolean dumb, boolean uncommitted) {}

    record SettingFile(VirtualFile file, boolean unsaved, boolean committed) {}

    /** Raw dependency observations only; readiness and receipt authority stay in this service. */
    interface NativeAccess {
        Map<ExternalSystemProjectId, TrackerRow> trackerRows() throws ReflectiveOperationException;

        Set<ExternalSystemProjectId> expectedBuilds();

        List<ModuleFacts> modules();

        ImportedFacts imported(ExternalSystemProjectId id);

        boolean upToDate(Object target) throws ReflectiveOperationException;

        Counters counters();

        List<String> unsavedPaths();

        boolean activeTask();

        SettingFile setting(String path);

        default VirtualFile settingFile(String path) {
            return setting(path).file();
        }

        SourceInventoryClient.SettingsCapture captureSettings(List<String> paths) throws Exception;

        SourceInventory.Response verify(SourceInventory.VerifyRequest request);
    }

    /** The public service constructor always uses this strict, version-gated native adapter. */
    private static final class PlatformNativeAccess implements NativeAccess {
        private final Project project;

        PlatformNativeAccess(Project project) {
            this.project = project;
        }

        @Override
        public Map<ExternalSystemProjectId, TrackerRow> trackerRows()
                throws ReflectiveOperationException {
            ExternalSystemProjectTracker tracker =
                    ExternalSystemProjectTracker.getInstance(project);
            if (!tracker.getClass()
                    .getName()
                    .equals(
                            "com.intellij.openapi.externalSystem.autoimport.AutoImportProjectTracker")) {
                throw new UnsupportedOperationException("Unknown tracker");
            }
            Field field = tracker.getClass().getDeclaredField("projectDataMap");
            field.setAccessible(true);
            if (!(field.get(tracker) instanceof Map<?, ?> current) || current.size() > 256) {
                throw new UnsupportedOperationException("Tracker map schema");
            }
            Map<ExternalSystemProjectId, TrackerRow> rows = new HashMap<>();
            for (var entry : current.entrySet()) {
                ExternalSystemProjectId id = (ExternalSystemProjectId) entry.getKey();
                Object data = entry.getValue();
                if (!data.getClass()
                        .getName()
                        .equals(
                                "com.intellij.openapi.externalSystem.autoimport.AutoImportProjectTracker$ProjectData")) {
                    throw new UnsupportedOperationException("ProjectData schema");
                }
                Field awareField = data.getClass().getDeclaredField("projectAware");
                Field settingsField = data.getClass().getDeclaredField("settingsTracker");
                awareField.setAccessible(true);
                settingsField.setAccessible(true);
                if (!(awareField.get(data) instanceof ExternalSystemProjectAware aware)
                        || !aware.getProjectId().equals(id))
                    throw new UnsupportedOperationException("Build identity");
                Object settings = settingsField.get(data);
                if (settings == null
                        || !settings.getClass()
                                .getName()
                                .equals(
                                        "com.intellij.openapi.externalSystem.autoimport.AutoImportProjectSettingsFilesTracker")) {
                    throw new UnsupportedOperationException("Settings tracker schema");
                }
                rows.put(id, new TrackerRow(aware, data, settings));
            }
            return rows;
        }

        @Override
        public boolean upToDate(Object target) throws ReflectiveOperationException {
            Method method = target.getClass().getDeclaredMethod("isUpToDate");
            if (method.getReturnType() != boolean.class)
                throw new UnsupportedOperationException("Status schema");
            method.setAccessible(true);
            return (boolean) method.invoke(target);
        }

        @Override
        public Set<ExternalSystemProjectId> expectedBuilds() {
            Set<ExternalSystemProjectId> expected = new HashSet<>();
            for (ExternalSystemManager<?, ?, ?, ?, ?> manager :
                    ExternalSystemManager.EP_NAME.getExtensionList()) {
                for (var setting :
                        manager.getSettingsProvider().fun(project).getLinkedProjectsSettings()) {
                    expected.add(
                            new ExternalSystemProjectId(
                                    manager.getSystemId(), setting.getExternalProjectPath()));
                }
            }
            return expected;
        }

        @Override
        public List<ModuleFacts> modules() {
            return ReadAction.compute(
                    () -> {
                        List<ModuleFacts> result = new ArrayList<>();
                        for (Module module : ModuleManager.getInstance(project).getModules()) {
                            var properties =
                                    ExternalSystemModulePropertyManager.getInstance(module);
                            var roots = ModuleRootManager.getInstance(module);
                            List<OrderFacts> entries = new ArrayList<>();
                            for (OrderEntry entry : roots.getOrderEntries()) {
                                entries.add(
                                        new OrderFacts(
                                                entry.getPresentableName(), entry.isValid()));
                            }
                            result.add(
                                    new ModuleFacts(
                                            module.getName(),
                                            properties.getExternalSystemId(),
                                            properties.getRootProjectPath(),
                                            properties.isMavenized(),
                                            roots.getSdk() != null,
                                            roots.getSdk() == null
                                                    ? ""
                                                    : roots.getSdk().getHomePath(),
                                            entries,
                                            List.of(roots.getContentRootUrls()),
                                            List.of(roots.getSourceRootUrls())));
                        }
                        return result;
                    });
        }

        @Override
        public ImportedFacts imported(ExternalSystemProjectId id) {
            var value =
                    ProjectDataManager.getInstance()
                            .getExternalProjectData(
                                    project, id.getSystemId(), id.getExternalProjectPath());
            return value == null
                    ? null
                    : new ImportedFacts(
                            value.getExternalProjectStructure() != null,
                            value.getLastImportTimestamp(),
                            value.getLastSuccessfulImportTimestamp());
        }

        @Override
        public Counters counters() {
            return new Counters(
                    ProjectRootModificationTracker.getInstance(project).getModificationCount(),
                    PsiModificationTracker.getInstance(project).getModificationCount(),
                    VirtualFileManager.getInstance().getModificationCount(),
                    DumbService.getInstance(project).isDumb(),
                    PsiDocumentManager.getInstance(project).hasUncommitedDocuments());
        }

        @Override
        public List<String> unsavedPaths() {
            List<String> paths = new ArrayList<>();
            for (Document document : FileDocumentManager.getInstance().getUnsavedDocuments()) {
                VirtualFile file = FileDocumentManager.getInstance().getFile(document);
                if (file != null) paths.add(file.getPath());
            }
            return paths;
        }

        @Override
        public boolean activeTask() {
            for (ExternalSystemTaskType type : ExternalSystemTaskType.values()) {
                if (ExternalSystemProcessingManager.getInstance()
                        .hasTaskOfTypeInProgress(type, project)) return true;
            }
            return false;
        }

        @Override
        public VirtualFile settingFile(String path) {
            return LocalFileSystem.getInstance().findFileByPath(path);
        }

        @Override
        public SettingFile setting(String path) {
            VirtualFile file = settingFile(path);
            Document document =
                    file == null ? null : FileDocumentManager.getInstance().getCachedDocument(file);
            return new SettingFile(
                    file,
                    document != null
                            && FileDocumentManager.getInstance().isDocumentUnsaved(document),
                    document == null
                            || PsiDocumentManager.getInstance(project).isCommitted(document));
        }

        @Override
        public SourceInventoryClient.SettingsCapture captureSettings(List<String> paths)
                throws Exception {
            try (SemanticRuntime runtime = SemanticRuntime.open(Path.of(project.getBasePath()))) {
                return new SourceInventoryClient(runtime.launch(runtime.servers().get(0)))
                        .captureSettings(Path.of(project.getBasePath()), paths);
            }
        }

        @Override
        public SourceInventory.Response verify(SourceInventory.VerifyRequest request) {
            return project.getService(SourceInventoryService.class).execute(request);
        }
    }

    private static List<String> settingPaths(ExternalSystemProjectAware aware) throws IOException {
        Set<String> paths = aware.getSettingsFiles();
        if (paths == null || paths.size() > SourceInventory.MAX_FILES)
            throw new IOException("Settings limit");
        for (String path : paths) SourceInventory.absolute(path);
        return paths.stream().sorted(SourceInventory.UTF8).toList();
    }

    private Receipt receipt(ObservedBuild build) throws Exception {
        List<String> paths = settingPaths(build.aware);
        if (!paths.equals(build.paths)) throw new IOException("Settings list changed");
        List<NativeSetting> before = nativeSettings(paths);
        SourceInventoryClient.SettingsCapture physical = nativeAccess.captureSettings(paths);
        List<NativeSetting> after = nativeSettings(paths);
        if (!before.equals(after) || !paths.equals(settingPaths(build.aware))) {
            throw new IOException("Native settings changed");
        }
        for (int i = 0; i < paths.size(); i++) {
            var nativeFile = before.get(i);
            var disk = physical.files().get(i);
            if (nativeFile.present() != disk.present()
                    || !Objects.equals(nativeFile.hash(), disk.sha256())) {
                throw new IOException("Native/physical settings mismatch");
            }
        }
        return new Receipt(before, physical.files());
    }

    private List<NativeSetting> nativeSettings(List<String> paths) throws IOException {
        List<NativeSetting> result = new ArrayList<>();
        SourceInventoryFiles.Budget budget = new SourceInventoryFiles.Budget();
        for (String path : paths) {
            result.add(
                    ReadAction.compute(
                            () -> {
                                SettingFile setting = nativeAccess.setting(path);
                                VirtualFile file = setting.file();
                                if (file == null) return new NativeSetting(path, false, null, -1);
                                if (!file.isValid()
                                        || file.isDirectory()
                                        || file.is(VFileProperty.SYMLINK)) {
                                    throw new IOException("Unsafe native setting");
                                }
                                if (setting.unsaved() || !setting.committed()) {
                                    throw new IOException("Dirty settings document");
                                }
                                long stamp = file.getModificationStamp();
                                String hash;
                                try (InputStream input = file.getInputStream()) {
                                    hash = SourceInventoryFiles.hashStream(input, budget);
                                }
                                if (stamp != file.getModificationStamp())
                                    throw new IOException("Settings changed");
                                return new NativeSetting(path, true, hash, stamp);
                            }));
        }
        return List.copyOf(result);
    }

    private List<Long> nativeStamps(List<String> paths) {
        return paths.stream()
                .map(
                        path -> {
                            VirtualFile file = nativeAccess.settingFile(path);
                            return file == null ? -1L : file.getModificationStamp();
                        })
                .toList();
    }

    private String nativeEpochs() {
        return ReadAction.compute(
                () -> {
                    Counters counters = nativeAccess.counters();
                    return counters.roots()
                            + ":"
                            + counters.psi()
                            + ":"
                            + importEpoch.get()
                            + ":"
                            + indexEpoch.get()
                            + ":"
                            + counters.dumb()
                            + ":"
                            + counters.vfs()
                            + ":"
                            + counters.uncommitted();
                });
    }

    private void requireReady() throws IOException {
        ReadAction.run(
                () -> {
                    Counters counters = nativeAccess.counters();
                    if (disposed
                            || project.isDisposed()
                            || counters.dumb()
                            || counters.uncommitted()) {
                        throw new IOException("Indexing or uncommitted PSI");
                    }
                    for (String path : nativeAccess.unsavedPaths()) {
                        if (path.startsWith(project.getBasePath() + "/")) {
                            throw new IOException("Unsaved project document");
                        }
                    }
                    List<ModuleFacts> modules = nativeAccess.modules();
                    if (modules.isEmpty()) throw new IOException("No imported modules");
                    for (ModuleFacts module : modules) {
                        if (!module.sdkPresent()) throw new IOException("Missing module SDK");
                        for (OrderFacts entry : module.entries()) {
                            if (!entry.valid())
                                throw new IOException("Unresolved module dependency");
                        }
                    }
                });
        if (nativeAccess.activeTask()) {
            throw new IOException("External-system task active");
        }
    }

    private String modelDigest() throws IOException {
        Map<String, Object> model = new TreeMap<>();
        for (ModuleFacts module : nativeAccess.modules()) {
            List<String> entries = new ArrayList<>();
            for (OrderFacts entry : module.entries()) {
                entries.add(entry.name() + ":" + entry.valid());
            }
            entries.add("sdk:" + (module.sdkPresent() ? module.sdkHome() : ""));
            entries.addAll(module.contentUrls());
            entries.addAll(module.sourceUrls());
            model.put(module.name(), entries);
        }
        return digest(model);
    }

    private DeclarationEvidence declarations(Request request) {
        DeclarationEvidence result = new DeclarationEvidence();
        for (var range : request.getChangedRanges()) {
            VirtualFile file =
                    LocalFileSystem.getInstance()
                            .findFileByPath(request.getProjectPath() + "/" + range.getPath());
            if (file == null) {
                result.omit("MISSING_NATIVE_FILE");
                continue;
            }
            PsiFile psi = PsiManager.getInstance(project).findFile(file);
            if (psi == null) {
                result.omit("UNSUPPORTED_PSI_FILE");
                continue;
            }
            Document document = PsiDocumentManager.getInstance(project).getDocument(psi);
            if (document == null) {
                result.omit("MISSING_PSI_DOCUMENT");
                continue;
            }
            for (PsiNameIdentifierOwner declaration :
                    PsiTreeUtil.findChildrenOfType(psi, PsiNameIdentifierOwner.class)) {
                PsiElement name = declaration.getNameIdentifier();
                if (name == null) {
                    result.omit("UNLOCATABLE_DECLARATION");
                    continue;
                }
                int line = document.getLineNumber(name.getTextOffset()) + 1;
                if (line < range.getStartLine() || line > range.getEndLine()) continue;
                Set<String> qualified = new TreeSet<>();
                for (QualifiedNameProvider provider :
                        QualifiedNameProvider.EP_NAME.getExtensionList()) {
                    String value = provider.getQualifiedName(declaration);
                    if (value != null && !value.isBlank()) qualified.add(value);
                }
                result.add(range.getPath(), line, qualified);
            }
        }
        return result;
    }

    /** Bounded diagnostic summaries are evidence limitations, not failures of source authority. */
    static final class DeclarationEvidence {
        private final List<Declaration> values = new ArrayList<>();
        private final Set<String> seen = new HashSet<>();
        private final Map<String, Long> omissions = new TreeMap<>();

        void omit(String reason) {
            omissions.merge(reason, 1L, Long::sum);
        }

        void add(String path, int line, Set<String> qualified) {
            if (qualified.size() != 1) {
                omit(qualified.isEmpty() ? "UNSUPPORTED_IDENTITY" : "AMBIGUOUS_IDENTITY");
                return;
            }
            String name = qualified.iterator().next();
            String identity = path + ":" + line + ":" + name;
            if (seen.contains(identity)) return;
            if (values.size() >= 20) {
                omit("DECLARATION_LIMIT");
                return;
            }
            if (SourceInventory.utf8(name).length > 4096) {
                omit("IDENTITY_SIZE_LIMIT");
                return;
            }
            seen.add(identity);
            Declaration item = new Declaration();
            item.setPath(path);
            item.setLine(line);
            item.setQualifiedName(name);
            values.add(item);
        }

        List<Declaration> values() {
            return List.copyOf(values);
        }

        List<String> limitations() {
            return omissions.entrySet().stream()
                    .map(
                            entry ->
                                    entry.getKey()
                                            + ": "
                                            + entry.getValue()
                                            + " omitted observations")
                    .toList();
        }
    }

    private static String digest(Object value) throws IOException {
        return HexFormat.of()
                .formatHex(SourceInventory.digest("SHA-256").digest(JSON.writeValueAsBytes(value)));
    }

    @Override
    public void dispose() {
        disposed = true;
        synchronized (builds) {
            builds.values().forEach(build -> build.baseline.invalidate());
        }
        synchronized (this) {
            captured = null;
        }
    }
}
