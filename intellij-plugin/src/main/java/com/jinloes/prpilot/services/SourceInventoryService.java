package com.jinloes.prpilot.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileTypes.FileTypeEvent;
import com.intellij.openapi.fileTypes.FileTypeListener;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootModificationTracker;
import com.intellij.openapi.roots.SourceFolder;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VFileProperty;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.jinloes.prpilot.model.SourceInventory;
import com.jinloes.prpilot.model.SourceInventory.Content;
import com.jinloes.prpilot.model.SourceInventory.Coverage;
import com.jinloes.prpilot.model.SourceInventory.DiscoverRequest;
import com.jinloes.prpilot.model.SourceInventory.Discovery;
import com.jinloes.prpilot.model.SourceInventory.Entry;
import com.jinloes.prpilot.model.SourceInventory.Epochs;
import com.jinloes.prpilot.model.SourceInventory.Hash;
import com.jinloes.prpilot.model.SourceInventory.Kind;
import com.jinloes.prpilot.model.SourceInventory.Location;
import com.jinloes.prpilot.model.SourceInventory.Membership;
import com.jinloes.prpilot.model.SourceInventory.Model;
import com.jinloes.prpilot.model.SourceInventory.Operation;
import com.jinloes.prpilot.model.SourceInventory.Reason;
import com.jinloes.prpilot.model.SourceInventory.ReasonCode;
import com.jinloes.prpilot.model.SourceInventory.Response;
import com.jinloes.prpilot.model.SourceInventory.Scope;
import com.jinloes.prpilot.model.SourceInventory.Source;
import com.jinloes.prpilot.model.SourceInventory.Status;
import com.jinloes.prpilot.model.SourceInventory.VerifyRequest;
import com.jinloes.prpilot.review.SourceInventoryFiles;
import com.jinloes.prpilot.review.SourceInventoryFiles.Budget;
import com.jinloes.prpilot.review.SourceInventoryFiles.Failure;
import com.jinloes.prpilot.review.SourceInventoryFiles.Leaf;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.jetbrains.jps.model.java.JavaResourceRootProperties;
import org.jetbrains.jps.model.java.JavaSourceRootProperties;

/** Project-local native authority. Calls are made on pooled threads, never on the EDT. */
public final class SourceInventoryService implements Disposable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Authority authority;
    private final LongSupplier clock;
    private final String instanceId = UUID.randomUUID().toString();
    private final AtomicReference<Slot> active = new AtomicReference<>();
    private volatile boolean disposed;

    public SourceInventoryService(Project project) {
        authority = new NativeAuthority(project, this);
        clock = System::nanoTime;
    }

    SourceInventoryService(Authority authority, LongSupplier clock) {
        this.authority = authority;
        this.clock = clock;
    }

    interface Authority {
        Path root();

        String build();

        boolean closed();

        Epochs epochs();

        Model model(Budget budget) throws IOException;

        List<Leaf> leaves(Model model, Budget budget) throws IOException;

        void requireSourceDirectory(Location location, Budget budget) throws IOException;

        Entry classify(Leaf leaf, Budget budget) throws IOException;

        String vfsHash(String path, Budget budget) throws IOException;
    }

    private static final class Slot {
        final String id = UUID.randomUUID().toString();
        final long deadline;
        volatile Discovery discovery;

        Slot(long deadline) {
            this.deadline = deadline;
        }
    }

    @Override
    public void dispose() {
        disposed = true;
        active.set(null);
    }

    public Response execute(DiscoverRequest request) {
        SourceInventory.validate(request);
        Response result = new Response();
        result.setSchemaVersion(2);
        result.setOperation(request.getOperation());
        result.setNonce(request.getNonce());
        result.setReasons(List.of());
        try {
            Budget budget = new Budget();
            if (disposed || authority.closed()) {
                throw new Failure(ReasonCode.STALE_DISCOVERY, null);
            }
            if (!authority.root().toString().equals(request.getProjectPath())) {
                throw new Failure(ReasonCode.WRONG_PROJECT, null);
            }
            if (!supportsBuild(authority.build())) {
                throw new Failure(ReasonCode.UNSUPPORTED_IDE, null);
            }
            if (request.getOperation() == Operation.DISCOVER) {
                Slot slot = new Slot(clock.getAsLong() + Duration.ofSeconds(180).toNanos());
                active.set(slot);
                Discovery discovery = capture(slot.id, budget);
                slot.discovery = copy(discovery);
                current(slot);
                result.setStatus(Status.DISCOVERED);
                result.setDiscovery(discovery);
            } else {
                VerifyRequest verify = (VerifyRequest) request;
                Slot slot = active.get();
                if (slot == null
                        || !slot.id.equals(verify.getDiscoveryId())
                        || slot.discovery == null) {
                    throw new Failure(ReasonCode.STALE_DISCOVERY, null);
                }
                current(slot);
                Discovery saved = copy(slot.discovery);
                Discovery before = capture(slot.id, budget);
                sameCapture(saved, before);
                verifySourceModel(before.getModel(), budget);
                List<Entry> sources =
                        before.getEntries().stream()
                                .filter(e -> e.getMembership() == Membership.SOURCE)
                                .toList();
                if (!sources.stream()
                        .map(Entry::getPath)
                        .toList()
                        .equals(verify.getFiles().stream().map(Hash::getPath).toList())) {
                    throw new Failure(ReasonCode.MANIFEST_MISMATCH, null);
                }
                Budget vfsBudget = budget.phase();
                for (int i = 0; i < sources.size(); i++) {
                    current(slot);
                    budget.check();
                    Entry entry = sources.get(i);
                    if (entry.getKind() != Kind.FILE) {
                        throw new Failure(ReasonCode.UNSAFE_PATH, entry.getPath());
                    }
                    String expected = verify.getFiles().get(i).getSha256();
                    String vfs = authority.vfsHash(entry.getPath(), vfsBudget);
                    budget.check();
                    if (!expected.equals(vfs)) {
                        throw new Failure(ReasonCode.CONTENT_MISMATCH, entry.getPath());
                    }
                }
                sameCapture(before, capture(slot.id, budget));
                current(slot);
                Coverage coverage = new Coverage();
                coverage.setDiscoveryId(slot.id);
                coverage.setProjectPath(before.getProjectPath());
                coverage.setProjectInstanceId(instanceId);
                coverage.setIdeBuild(before.getIdeBuild());
                coverage.setEpochs(before.getEpochs());
                coverage.setFileCount(sources.size());
                coverage.setSourceManifestSha256(SourceInventory.manifest(verify.getFiles()));
                result.setStatus(Status.VFS_VERIFIED);
                result.setCoverage(coverage);
            }
            budget.check();
            SourceInventory.validate(result);
            if (JSON.writeValueAsBytes(result).length > SourceInventory.MAX_JSON_BYTES) {
                throw new Failure(ReasonCode.LIMIT, null);
            }
            Slot slot = active.get();
            String completedId =
                    result.getDiscovery() != null
                            ? result.getDiscovery().getDiscoveryId()
                            : result.getCoverage().getDiscoveryId();
            if (slot == null || !slot.id.equals(completedId)) {
                throw new Failure(ReasonCode.STALE_DISCOVERY, null);
            }
            current(slot);
        } catch (Failure e) {
            fail(result, e.code(), e.path());
        } catch (IOException e) {
            fail(result, ReasonCode.IO_ERROR, null);
        } catch (LinkageError e) {
            fail(result, ReasonCode.UNSUPPORTED_API, null);
        } catch (RuntimeException e) {
            fail(result, ReasonCode.UNKNOWN_MEMBERSHIP, null);
        }
        return result;
    }

    static boolean supportsBuild(String build) {
        try {
            String numeric = build.contains("-") ? build.substring(build.indexOf('-') + 1) : build;
            return Integer.parseInt(numeric.split("\\.")[0]) >= 262;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void fail(Response result, ReasonCode code, String path) {
        result.setStatus(
                code == ReasonCode.UNSUPPORTED_API || code == ReasonCode.UNSUPPORTED_IDE
                        ? Status.UNSUPPORTED
                        : Status.BLOCKED);
        result.setReasons(List.of(new Reason(code, path)));
        result.setDiscovery(null);
        result.setCoverage(null);
    }

    private void current(Slot slot) throws Failure {
        if (disposed
                || authority.closed()
                || active.get() != slot
                || clock.getAsLong() - slot.deadline >= 0) {
            throw new Failure(ReasonCode.STALE_DISCOVERY, null);
        }
    }

    private Discovery capture(String id, Budget budget) throws IOException {
        Epochs start = authority.epochs();
        Model model = authority.model(budget);
        List<Leaf> leaves = authority.leaves(model, budget);
        List<Entry> entries = new ArrayList<>();
        Map<String, List<String>> roots = sourceRoots(model);
        int sourceCount = 0;
        for (Leaf leaf : leaves) {
            budget.check();
            Entry entry = authority.classify(leaf, budget);
            SourceInventory.validate(entry);
            if (!entry.getPath().equals(leaf.path()) || entry.getKind() != leaf.kind()) {
                throw new Failure(ReasonCode.INVENTORY_CHANGED, leaf.path());
            }
            if (entry.getMembership() == Membership.UNKNOWN) {
                throw new Failure(ReasonCode.UNKNOWN_MEMBERSHIP, leaf.path());
            }
            if (entry.getMembership() == Membership.SOURCE) {
                if (++sourceCount > SourceInventory.MAX_FILES) {
                    throw new Failure(ReasonCode.LIMIT, null);
                }
                List<String> modules = roots.get(entry.getSourceRootUrl());
                if (modules == null || !modules.containsAll(entry.getModules())) {
                    throw new Failure(ReasonCode.UNMAPPED_SOURCE_ROOT, entry.getPath());
                }
                if (entry.getKind() != Kind.FILE) {
                    throw new Failure(ReasonCode.UNSAFE_PATH, entry.getPath());
                }
            }
            entries.add(entry);
        }
        if (!leaves.equals(authority.leaves(model, budget))) {
            throw new Failure(ReasonCode.INVENTORY_CHANGED, null);
        }
        if (!same(model, authority.model(budget)) || !same(start, authority.epochs())) {
            throw new Failure(ReasonCode.MODEL_CHANGED, null);
        }
        Discovery discovery = new Discovery();
        discovery.setDiscoveryId(id);
        discovery.setProjectPath(authority.root().toString());
        discovery.setProjectInstanceId(instanceId);
        discovery.setIdeBuild(authority.build());
        discovery.setEpochs(start);
        discovery.setModel(model);
        discovery.setEntries(SourceInventory.sorted(entries));
        SourceInventory.validate(discovery);
        return discovery;
    }

    private static Map<String, List<String>> sourceRoots(Model model) {
        Map<String, List<String>> roots = new HashMap<>();
        for (Content content : model.getContents()) {
            for (Source source : content.getSources()) {
                roots.computeIfAbsent(source.getLocation().getUrl(), ignored -> new ArrayList<>())
                        .add(content.getModule());
            }
        }
        return roots;
    }

    private void verifySourceModel(Model model, Budget budget) throws IOException {
        if (!model.getUnloadedModules().isEmpty()) {
            throw new Failure(ReasonCode.UNLOADED_MODULE, null);
        }
        if (sourceRoots(model).isEmpty()) {
            throw new Failure(ReasonCode.EMPTY_SOURCE_MODEL, null);
        }
        for (Content content : model.getContents()) {
            for (Source source : content.getSources()) {
                Location location = source.getLocation();
                if (location.getScope() == Scope.EXTERNAL
                        || location.getScope() == Scope.NON_LOCAL) {
                    throw new Failure(ReasonCode.EXTERNAL_SOURCE_ROOT, null);
                }
                if (location.getScope() != Scope.WORKTREE) {
                    throw new Failure(ReasonCode.UNRESOLVED_SOURCE_ROOT, null);
                }
                Path lexical = localPath(location.getUrl());
                if (lexical == null || !lexical.startsWith(authority.root())) {
                    throw new Failure(ReasonCode.UNSAFE_PATH, null);
                }
                authority.requireSourceDirectory(location, budget);
            }
        }
    }

    private static Discovery copy(Discovery discovery) {
        return JSON.convertValue(discovery, Discovery.class);
    }

    private static boolean same(Object left, Object right) {
        return JSON.valueToTree(left).equals(JSON.valueToTree(right));
    }

    private static void sameCapture(Discovery left, Discovery right) throws Failure {
        if (!same(left.getEpochs(), right.getEpochs())
                || !same(left.getModel(), right.getModel())) {
            throw new Failure(ReasonCode.MODEL_CHANGED, null);
        }
        if (!same(left.getEntries(), right.getEntries())) {
            throw new Failure(ReasonCode.INVENTORY_CHANGED, null);
        }
    }

    static Path localPath(String url) {
        try {
            if (!url.startsWith("file://")) {
                return null;
            }
            // IntelliJ URLs are VFS paths, not percent-encoded URIs.
            Path path = Path.of(url.substring("file://".length()));
            SourceInventory.absolute(path.toString());
            return path;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static final class NativeAuthority implements Authority {
        private final Project project;
        private final AtomicLong fileTypes = new AtomicLong();
        private final Path testRoot;
        private final java.util.function.Function<String, VirtualFile> testLookup;

        NativeAuthority(Project project, Disposable lifetime) {
            this.project = project;
            testRoot = null;
            testLookup = null;
            ApplicationManager.getApplication()
                    .getMessageBus()
                    .connect(lifetime)
                    .subscribe(
                            FileTypeManager.TOPIC,
                            new FileTypeListener() {
                                @Override
                                public void beforeFileTypesChanged(FileTypeEvent event) {
                                    fileTypes.incrementAndGet();
                                }

                                @Override
                                public void fileTypesChanged(FileTypeEvent event) {
                                    fileTypes.incrementAndGet();
                                }
                            });
        }

        NativeAuthority(Path root, java.util.function.Function<String, VirtualFile> lookup) {
            project = null;
            testRoot = root;
            testLookup = lookup;
        }

        private <T> T read(
                com.intellij.openapi.util.ThrowableComputable<T, IOException> computation)
                throws IOException {
            return testLookup == null ? ReadAction.compute(computation) : computation.compute();
        }

        private VirtualFile resolveUrl(String url) {
            return testLookup == null
                    ? VirtualFileManager.getInstance().findFileByUrl(url)
                    : testLookup.apply(localPath(url).toString());
        }

        @Override
        public Path root() {
            if (testRoot != null) return testRoot;
            if (project.getBasePath() == null) {
                throw new IllegalStateException("No project root");
            }
            return Path.of(project.getBasePath());
        }

        @Override
        public String build() {
            return ApplicationInfo.getInstance().getBuild().asString();
        }

        @Override
        public boolean closed() {
            return project.isDisposed();
        }

        @Override
        public Epochs epochs() {
            return ReadAction.compute(
                    () -> {
                        Epochs epochs = new Epochs();
                        epochs.setRoots(
                                Long.toString(
                                        ProjectRootModificationTracker.getInstance(project)
                                                .getModificationCount()));
                        epochs.setModules(
                                Long.toString(
                                        ModuleManager.getInstance(project).getModificationCount()));
                        epochs.setVfs(
                                Long.toString(
                                        VirtualFileManager.getInstance().getModificationCount()));
                        epochs.setFileTypes(Long.toString(fileTypes.get()));
                        return epochs;
                    });
        }

        @Override
        public Model model(Budget budget) throws IOException {
            Model model = new Model();
            model.setIgnoredPatterns(
                    ReadAction.compute(() -> FileTypeManager.getInstance().getIgnoredFilesList()));
            model.setUnloadedModules(
                    ReadAction.compute(
                            () ->
                                    SourceInventory.sorted(
                                            ModuleManager.getInstance(project)
                                                    .getUnloadedModuleDescriptions()
                                                    .stream()
                                                    .map(description -> description.getName())
                                                    .toList())));
            List<Content> contents = new ArrayList<>();
            Map<String, Location> dependencySources = new HashMap<>(),
                    dependencyClasses = new HashMap<>();
            Module[] modules =
                    ReadAction.compute(() -> ModuleManager.getInstance(project).getModules());
            for (Module module : modules) {
                budget.check();
                ReadAction.run(
                        () -> {
                            ModuleRootManager manager = ModuleRootManager.getInstance(module);
                            for (ContentEntry entry : manager.getContentEntries()) {
                                Content content = new Content();
                                content.setModule(module.getName());
                                content.setLocation(unresolved(entry.getUrl()));
                                List<Source> sources = new ArrayList<>();
                                for (SourceFolder folder : entry.getSourceFolders()) {
                                    Source source = new Source();
                                    source.setLocation(unresolved(folder.getUrl()));
                                    source.setTypeClass(folder.getRootType().getClass().getName());
                                    source.setTest(folder.isTestSource());
                                    Object properties = folder.getJpsElement().getProperties();
                                    if (properties instanceof JavaSourceRootProperties javaSource) {
                                        source.setGenerated(javaSource.isForGeneratedSources());
                                    } else if (properties
                                            instanceof JavaResourceRootProperties resource) {
                                        source.setGenerated(resource.isForGeneratedSources());
                                    } else {
                                        VirtualFile file = folder.getFile();
                                        ProjectFileIndex index =
                                                ProjectFileIndex.getInstance(project);
                                        source.setGenerated(
                                                file != null && index.isInSourceContent(file)
                                                        ? index.isInGeneratedSources(file)
                                                        : null);
                                    }
                                    sources.add(source);
                                }
                                content.setSources(SourceInventory.sorted(sources));
                                content.setExclusions(
                                        SourceInventory.sorted(
                                                entry.getExcludeFolderUrls().stream()
                                                        .map(NativeAuthority::unresolved)
                                                        .toList()));
                                content.setExcludePatterns(
                                        SourceInventory.sorted(entry.getExcludePatterns()));
                                contents.add(content);
                            }
                            for (String url :
                                    manager.orderEntries()
                                            .withoutModuleSourceEntries()
                                            .sources()
                                            .getUrls()) {
                                dependencySources.put(url, unresolved(url));
                            }
                            for (String url :
                                    manager.orderEntries()
                                            .withoutModuleSourceEntries()
                                            .classes()
                                            .getUrls()) {
                                dependencyClasses.put(url, unresolved(url));
                            }
                        });
                if (contents.size() > SourceInventory.MAX_NODES) {
                    throw new Failure(ReasonCode.LIMIT, null);
                }
            }
            model.setContents(SourceInventory.sorted(contents));
            model.setDependencySources(
                    SourceInventory.sorted(new ArrayList<>(dependencySources.values())));
            model.setDependencyClasses(
                    SourceInventory.sorted(new ArrayList<>(dependencyClasses.values())));
            List<Location> locations = new ArrayList<>();
            locations.addAll(model.getDependencySources());
            locations.addAll(model.getDependencyClasses());
            for (Content content : contents) {
                locations.add(content.getLocation());
                locations.addAll(content.getExclusions());
                content.getSources().forEach(source -> locations.add(source.getLocation()));
            }
            for (Location location : locations) {
                budget.check();
                Path path = localPath(location.getUrl());
                if (path == null) {
                    location.setScope(Scope.NON_LOCAL);
                } else {
                    VirtualFile file =
                            ReadAction.compute(
                                    () ->
                                            VirtualFileManager.getInstance()
                                                    .findFileByUrl(location.getUrl()));
                    if (file != null && file.isValid() && file.getPath().equals(path.toString())) {
                        location.setNativePath(path.toString());
                        location.setScope(
                                path.startsWith(root()) ? Scope.WORKTREE : Scope.EXTERNAL);
                    } else {
                        location.setScope(Scope.UNRESOLVED);
                    }
                }
            }
            SourceInventory.validate(model);
            return model;
        }

        private static Location unresolved(String url) {
            Location location = new Location();
            location.setUrl(url);
            location.setScope(Scope.UNRESOLVED);
            return location;
        }

        private VirtualFile find(String relative) {
            if (testLookup != null) {
                return testLookup.apply(
                        relative.isEmpty()
                                ? root().toString()
                                : root().resolve(relative).toString());
            }
            return LocalFileSystem.getInstance()
                    .findFileByPath(
                            relative.isEmpty()
                                    ? root().toString()
                                    : root().resolve(relative).toString());
        }

        private static Kind kind(VirtualFile file) {
            return file.is(VFileProperty.SYMLINK)
                    ? Kind.SYMLINK
                    : file.is(VFileProperty.SPECIAL) ? Kind.SPECIAL : Kind.FILE;
        }

        private static SourceInventoryFiles.Identity identity(VirtualFile file) {
            return new SourceInventoryFiles.Identity(
                    file,
                    file.getLength(),
                    Long.toString(file.getModificationStamp()),
                    file.getUrl(),
                    kind(file));
        }

        private static boolean sameFile(VirtualFile expected, VirtualFile actual) {
            // Native VFS may return fresh handles for one persistent file ID. Equality is the
            // identity contract; neither Java reference identity nor a matching path is sufficient.
            return actual != null
                    && expected.isValid()
                    && actual.isValid()
                    && expected.equals(actual)
                    && expected.getFileSystem().equals(actual.getFileSystem())
                    && expected.getPath().equals(actual.getPath())
                    && expected.isDirectory() == actual.isDirectory()
                    && identity(expected).equals(identity(actual));
        }

        @Override
        public void requireSourceDirectory(Location location, Budget budget) throws IOException {
            budget.check();
            read(
                    () -> {
                        Path path = localPath(location.getUrl());
                        if (path == null
                                || !path.startsWith(root())
                                || !path.toString().equals(location.getNativePath())) {
                            throw new Failure(ReasonCode.UNSAFE_PATH, null);
                        }
                        String relative = root().relativize(path).toString().replace('\\', '/');
                        if (relative.equals(".git") || relative.startsWith(".git/")) {
                            throw new Failure(ReasonCode.UNSAFE_PATH, null);
                        }
                        VirtualFile current = find("");
                        if (current == null
                                || !current.isValid()
                                || !current.isDirectory()
                                || kind(current) != Kind.FILE) {
                            throw new Failure(ReasonCode.UNSAFE_PATH, null);
                        }
                        if (!relative.isEmpty()) {
                            SourceInventory.path(relative);
                            for (String part : relative.split("/")) {
                                budget.check();
                                current = current.findChild(part);
                                if (current == null
                                        || !current.isValid()
                                        || !current.isDirectory()
                                        || kind(current) != Kind.FILE) {
                                    throw new Failure(ReasonCode.UNSAFE_PATH, relative);
                                }
                            }
                        }
                        if (!current.getUrl().equals(location.getUrl())
                                || !sameFile(current, resolveUrl(location.getUrl()))) {
                            throw new Failure(ReasonCode.INVENTORY_CHANGED, null);
                        }
                        return null;
                    });
        }

        @Override
        public List<Leaf> leaves(Model model, Budget budget) throws IOException {
            Map<String, VirtualFile> seen = new HashMap<>();
            Map<VirtualFile, String> identities = new HashMap<>();
            List<Leaf> leaves = new ArrayList<>();
            walk(find(""), "", seen, identities, leaves, budget);
            // Source roots may be resolved in VFS even when absent from a parent's child list.
            for (Content content : model.getContents()) {
                for (Source source : content.getSources()) {
                    Location location = source.getLocation();
                    if (location.getScope() != Scope.WORKTREE) {
                        continue;
                    }
                    requireSourceDirectory(location, budget);
                    String path = root().relativize(localPath(location.getUrl())).toString();
                    walk(resolveUrl(location.getUrl()), path, seen, identities, leaves, budget);
                }
            }
            leaves.sort((a, b) -> SourceInventory.UTF8.compare(a.path(), b.path()));
            return List.copyOf(leaves);
        }

        private void walk(
                VirtualFile file,
                String path,
                Map<String, VirtualFile> seen,
                Map<VirtualFile, String> identities,
                List<Leaf> leaves,
                Budget budget)
                throws IOException {
            budget.check();
            if (path.split("/").length > 128) {
                throw new Failure(ReasonCode.LIMIT, path);
            }
            if (!path.isEmpty()) {
                SourceInventory.path(path);
            }
            if (file == null) {
                throw new Failure(ReasonCode.UNKNOWN_MEMBERSHIP, path.isEmpty() ? null : path);
            }
            var before =
                    read(
                            () -> {
                                if (!file.isValid()
                                        || !file.getFileSystem().getProtocol().equals("file")
                                        || !file.getPath()
                                                .equals(
                                                        path.isEmpty()
                                                                ? root().toString()
                                                                : root().resolve(path).toString())
                                        || !sameFile(file, find(path))) {
                                    throw new Failure(
                                            ReasonCode.INVENTORY_CHANGED,
                                            path.isEmpty() ? null : path);
                                }
                                return identity(file);
                            });
            VirtualFile previous = seen.putIfAbsent(path, file);
            String priorPath = identities.putIfAbsent(file, path);
            if (previous != null && !sameFile(previous, file)
                    || priorPath != null && !priorPath.equals(path)) {
                throw new Failure(ReasonCode.INVENTORY_CHANGED, null);
            }
            if (previous != null) {
                return;
            }
            budget.node();
            if (path.equals(".git")) {
                if (before.kind() != Kind.FILE) {
                    throw new Failure(ReasonCode.UNSAFE_PATH, path);
                }
                return;
            }
            if (file.isDirectory() && before.kind() == Kind.FILE) {
                VirtualFile[] children = read(file::getChildren);
                for (VirtualFile child : children) {
                    walk(
                            child,
                            path.isEmpty() ? child.getName() : path + "/" + child.getName(),
                            seen,
                            identities,
                            leaves,
                            budget);
                }
                if (!java.util.Arrays.equals(children, read(file::getChildren))) {
                    throw new Failure(ReasonCode.INVENTORY_CHANGED, null);
                }
            } else {
                if (path.isEmpty()) {
                    throw new Failure(ReasonCode.UNSAFE_PATH, null);
                }
                leaves.add(new Leaf(path, before.kind(), before));
            }
            if (!read(
                    () ->
                            file.isValid()
                                    && identity(file).equals(before)
                                    && sameFile(file, find(path)))) {
                throw new Failure(ReasonCode.INVENTORY_CHANGED, null);
            }
        }

        @Override
        public Entry classify(Leaf leaf, Budget budget) throws IOException {
            budget.check();
            return ReadAction.compute(
                    () -> {
                        Entry entry = new Entry();
                        entry.setPath(leaf.path());
                        entry.setKind(leaf.kind());
                        entry.setModules(List.of());
                        entry.setGenerated(false);
                        entry.setTest(false);
                        VirtualFile file =
                                LocalFileSystem.getInstance()
                                        .findFileByNioFile(root().resolve(leaf.path()));
                        if (file == null || !file.isValid()) {
                            entry.setMembership(Membership.UNKNOWN);
                            return entry;
                        }
                        ProjectFileIndex index = ProjectFileIndex.getInstance(project);
                        if (index.isInSourceContent(file)) {
                            entry.setMembership(Membership.SOURCE);
                            VirtualFile sourceRoot = index.getSourceRootForFile(file);
                            entry.setSourceRootUrl(sourceRoot == null ? null : sourceRoot.getUrl());
                            entry.setModules(
                                    SourceInventory.sorted(
                                            index.getModulesForFile(file, true).stream()
                                                    .map(Module::getName)
                                                    .toList()));
                            entry.setGenerated(index.isInGeneratedSources(file));
                            entry.setTest(index.isInTestSourceContent(file));
                        } else if (index.isUnderIgnored(file)) {
                            entry.setMembership(Membership.IDE_IGNORED);
                        } else if (index.isExcluded(file)) {
                            entry.setMembership(Membership.EXCLUDED);
                        } else if (index.isInLibrarySource(file)) {
                            entry.setMembership(Membership.LIBRARY_SOURCE);
                        } else {
                            entry.setMembership(Membership.OUTSIDE_SOURCE);
                        }
                        return entry;
                    });
        }

        private record VfsRead(
                VirtualFile file, SourceInventoryFiles.Identity identity, InputStream stream) {}

        @Override
        public String vfsHash(String path, Budget budget) throws IOException {
            VfsRead read =
                    read(
                            () -> {
                                VirtualFile file = find(path);
                                if (file == null
                                        || !file.isValid()
                                        || file.isDirectory()
                                        || kind(file) != Kind.FILE
                                        || file.getLength() > SourceInventory.MAX_BYTES) {
                                    throw new Failure(ReasonCode.UNKNOWN_MEMBERSHIP, path);
                                }
                                return new VfsRead(file, identity(file), file.getInputStream());
                            });
            String hash;
            long count = 0;
            try (InputStream stream = read.stream()) {
                var digest = SourceInventory.digest("SHA-256");
                byte[] bytes = new byte[8192];
                while (true) {
                    budget.check();
                    int length = stream.read(bytes);
                    if (length < 0) break;
                    budget.bytes(length);
                    count += length;
                    digest.update(bytes, 0, length);
                }
                hash = java.util.HexFormat.of().formatHex(digest.digest());
            }
            boolean stable =
                    read(
                            () ->
                                    read.file().isValid()
                                            && !read.file().isDirectory()
                                            && identity(read.file()).equals(read.identity())
                                            && read.file()
                                                    .getPath()
                                                    .equals(root().resolve(path).toString())
                                            && sameFile(read.file(), find(path)));
            if (!stable || count != read.identity().size()) {
                throw new Failure(ReasonCode.CONTENT_MISMATCH, path);
            }
            return hash;
        }
    }
}
