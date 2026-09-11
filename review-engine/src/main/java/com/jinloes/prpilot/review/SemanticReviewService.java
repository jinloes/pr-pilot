package com.jinloes.prpilot.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.SemanticReviewContext;
import com.jinloes.prpilot.model.SemanticReviewContext.Operation;
import com.jinloes.prpilot.model.SemanticReviewContext.Range;
import com.jinloes.prpilot.model.SemanticReviewContext.Request;
import com.jinloes.prpilot.model.SemanticReviewContext.Snapshot;
import com.jinloes.prpilot.model.SemanticReviewContext.Status;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Shared host-independent policy. Only this service can issue a deep execution capability. */
public final class SemanticReviewService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final GitWorktreeService git;
    private final SemanticWorktreeStore store;
    private final Map<String, Binding> prepared = new ConcurrentHashMap<>();
    private final java.util.Set<String> operations = ConcurrentHashMap.newKeySet();

    public record Preparation(
            String retainedId, String head, String worktree, List<String> servers) {}

    private record Binding(String prIdentity, String diffDigest) {}

    public SemanticReviewService() {
        this(new GitWorktreeService(), new SemanticWorktreeStore());
    }

    SemanticReviewService(GitWorktreeService git, SemanticWorktreeStore store) {
        this.git = git;
        this.store = store;
    }

    public Preparation prepare(
            File repository,
            int prNumber,
            String branch,
            String head,
            String forkCloneUrl,
            String prIdentity,
            String diffDigest,
            String operationId)
            throws IOException, InterruptedException {
        identity(prIdentity, operationId);
        if (head == null
                || !head.matches("[0-9a-f]{40}|[0-9a-f]{64}")
                || diffDigest == null
                || !diffDigest.matches("[0-9a-f]{64}"))
            throw new IOException("Pinned head and diff digest required");
        store.list(); // A corrupt ownership registry must fail before worktree creation.
        File tree = git.newWorktreePath(prNumber);
        if (forkCloneUrl == null || forkCloneUrl.isBlank())
            git.createWorktree(repository, branch, head, tree);
        else git.createWorktreeFromFork(repository, forkCloneUrl, branch, head, tree);
        var retained =
                store.register(repository.toPath().toRealPath(), tree.toPath().toRealPath(), head);
        prepared.put(retained.id(), new Binding(prIdentity, diffDigest));
        // No runtime, provider, thread or lease is held while the user opens/imports the project.
        try (var runtime = SemanticRuntime.open(tree.toPath().toRealPath())) {
            return new Preparation(retained.id(), head, retained.worktree(), runtime.servers());
        }
    }

    public List<SemanticWorktreeStore.Retained> list() throws IOException {
        return store.list();
    }

    public boolean cleanup(String retainedId, boolean projectClosed) throws IOException {
        boolean removed = store.cleanup(retainedId, projectClosed);
        if (removed) prepared.remove(retainedId);
        return removed;
    }

    public Execution begin(
            String retainedId,
            String server,
            String prIdentity,
            String operationId,
            PRReviewRequest request)
            throws IOException, InterruptedException {
        identity(prIdentity, operationId);
        Binding binding = prepared.get(retainedId);
        if (binding == null
                || !binding.prIdentity().equals(prIdentity)
                || !binding.diffDigest().equals(digest(request.getDiff())))
            throw new IOException("Deep preparation no longer matches PR/diff; prepare again");
        if (!operations.add(operationId)) throw new IOException("Deep operation already consumed");
        var lease = store.acquire(retainedId);
        SemanticRuntime runtime = null;
        try {
            Path root = Path.of(lease.retained().worktree());
            runtime = SemanticRuntime.open(root);
            var launch = runtime.launch(server);
            var inventory = new SourceInventoryClient(launch);
            var cli = new IjctlClient(launch, root);
            Backend backend =
                    new Backend() {
                        @Override
                        public SourceInventoryClient.SourceCoverage collect()
                                throws IOException, InterruptedException {
                            return inventory.collect(root, lease.retained().head());
                        }

                        @Override
                        public Snapshot snapshot(Request input)
                                throws IOException, InterruptedException {
                            return cli.snapshot(input);
                        }

                        @Override
                        public String query(String tool, Map<String, Object> args)
                                throws IOException, InterruptedException {
                            return cli.query(tool, args);
                        }
                    };
            SemanticRuntime ownedRuntime = runtime;
            Execution execution =
                    new Execution(
                            root,
                            backend,
                            () -> {
                                try {
                                    ownedRuntime.close();
                                } finally {
                                    lease.close();
                                }
                            },
                            request.getDiff());
            execution.collect();
            return execution;
        } catch (IOException | InterruptedException | RuntimeException failure) {
            try {
                if (runtime != null) runtime.close();
            } finally {
                lease.close();
            }
            throw failure;
        }
    }

    interface Backend {
        SourceInventoryClient.SourceCoverage collect() throws IOException, InterruptedException;

        Snapshot snapshot(Request input) throws IOException, InterruptedException;

        String query(String tool, Map<String, Object> args)
                throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface Release {
        void close() throws IOException;
    }

    /** Not serializable and not reconstructible from prompt data or a client attestation. */
    public static final class Execution implements AutoCloseable {
        private final Path root;
        private final Backend backend;
        private final Release release;
        private final List<Range> ranges = new ArrayList<>();
        private final List<String> limitations = new ArrayList<>();
        private final SemanticReviewContext context = new SemanticReviewContext();
        private final SemanticSkillBundle skills;
        private String originalStamp;
        private boolean closed;
        private boolean failed;

        Execution(Path root, Backend backend, Release release, String diff) throws IOException {
            this.root = root;
            this.backend = backend;
            this.release = release;
            this.skills = SemanticSkillBundle.load();
            for (var file : InspectionManifest.fromDiff(diff).files()) {
                if (ranges.size() == 20) {
                    limitations.add("Changed-file collection capped at 20 files.");
                    break;
                }
                var lines =
                        file.hunks().stream()
                                .flatMap(h -> h.changedNewLines().stream())
                                .sorted()
                                .toList();
                if (lines.isEmpty()) {
                    limitations.add("No new-side declaration range for " + file.path());
                    continue;
                }
                Range range = new Range();
                range.setPath(file.path());
                range.setStartLine(lines.get(0));
                range.setEndLine(lines.get(lines.size() - 1));
                ranges.add(range);
            }
        }

        void collect() throws IOException, InterruptedException {
            try (Budget ignored = new Budget()) {
                Request status = request(Operation.STATUS, null, null);
                Snapshot readiness = backend.snapshot(status);
                if (readiness.getStatus() != Status.ELIGIBLE)
                    throw new IOException(
                            "Open the exact worktree, wait for arming, manually sync all linked builds, then Retry: "
                                    + readiness.getStatus()
                                    + " "
                                    + readiness.getReasons());
                var coverage = backend.collect();
                // Non-source and deleted files cannot become physical-authority query inputs.
                var covered =
                        coverage.files().stream()
                                .map(f -> f.getPath())
                                .collect(java.util.stream.Collectors.toSet());
                ranges.removeIf(
                        range -> {
                            if (covered.contains(range.getPath())) return false;
                            limitations.add(
                                    "Changed path outside source coverage: " + range.getPath());
                            return true;
                        });
                Snapshot snapshot = capture(coverage);
                originalStamp = stamp(coverage, snapshot);
                limitations.addAll(snapshot.getDeclarationLimitations());
                StringBuilder evidence = new StringBuilder();
                for (Range range : ranges)
                    append(
                            evidence,
                            "get_file_problems",
                            Map.of("filePath", range.getPath()),
                            coverage,
                            snapshot);
                for (var declaration : snapshot.getDeclarations()) {
                    if (!covered.contains(declaration.getPath())
                            || ranges.stream()
                                    .noneMatch(r -> r.getPath().equals(declaration.getPath())))
                        throw new IOException("Declaration escaped changed covered files");
                    limitations.add(
                            "Native declaration column unavailable; position-based symbol info omitted.");
                    append(
                            evidence,
                            "search_symbol",
                            Map.of(
                                    "q",
                                    declaration.getQualifiedName(),
                                    "include_external",
                                    false,
                                    "limit",
                                    20),
                            coverage,
                            snapshot);
                    append(
                            evidence,
                            "analyze_calls",
                            Map.of(
                                    "symbolFqn",
                                    declaration.getQualifiedName(),
                                    "analysisKind",
                                    "INCOMING_CALLS",
                                    "depth",
                                    1,
                                    "maxNodes",
                                    100),
                            coverage,
                            snapshot);
                }
                append(evidence, "get_project_modules", Map.of(), coverage, snapshot);
                append(evidence, "get_project_dependencies", Map.of(), coverage, snapshot);
                compareFresh();
                context.setEvidence(evidence.toString());
                context.setLimitations(boundedLimitations(limitations));
            } catch (IOException | InterruptedException | RuntimeException failure) {
                failed = true;
                throw failure;
            }
        }

        private void append(
                StringBuilder evidence,
                String tool,
                Map<String, Object> args,
                SourceInventoryClient.SourceCoverage coverage,
                Snapshot snapshot)
                throws IOException, InterruptedException {
            if (evidence.toString().getBytes(StandardCharsets.UTF_8).length >= 65536) return;
            verify(coverage, snapshot);
            String text = "\n[" + tool + "]\n" + backend.query(tool, args) + "\n";
            verify(coverage, snapshot);
            int available = 65536 - evidence.toString().getBytes(StandardCharsets.UTF_8).length;
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > available) {
                limitations.add(
                        "Semantic prompt evidence capped at 64 KiB; query output truncated.");
                int end = Math.min(text.length(), available);
                while (text.substring(0, end).getBytes(StandardCharsets.UTF_8).length > available)
                    end--;
                if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
                evidence.append(text, 0, end);
            } else evidence.append(text);
        }

        static List<String> boundedLimitations(List<String> values) {
            List<String> unique = values.stream().distinct().toList();
            List<String> result = new ArrayList<>();
            int bytes = 0, omitted = 0;
            for (String value : unique) {
                int length = value.getBytes(StandardCharsets.UTF_8).length;
                if (result.size() >= 63 || length + bytes > 7900) {
                    omitted++;
                    continue;
                }
                result.add(value);
                bytes += length;
            }
            if (omitted > 0)
                result.add(
                        omitted
                                + " additional limitation details omitted by the 8 KiB / 64 item bound.");
            return List.copyOf(result);
        }

        private Snapshot capture(SourceInventoryClient.SourceCoverage coverage)
                throws IOException, InterruptedException {
            Snapshot snapshot = backend.snapshot(request(Operation.CAPTURE, coverage, null));
            ready(snapshot);
            if (!Objects.equals(snapshot.getCoverageIdentity(), hash(coverage.coverage()))
                    || !Objects.equals(
                            snapshot.getSourceDigest(),
                            coverage.coverage().getSourceManifestSha256()))
                throw new IOException(
                        "Native snapshot does not match independent physical coverage");
            return snapshot;
        }

        private void verify(SourceInventoryClient.SourceCoverage coverage, Snapshot previous)
                throws IOException, InterruptedException {
            Snapshot current =
                    backend.snapshot(request(Operation.VERIFY, coverage, previous.getSnapshotId()));
            ready(current);
            if (!Objects.equals(previous.getSnapshotId(), current.getSnapshotId())
                    || !Objects.equals(
                            previous.getCoverageIdentity(), current.getCoverageIdentity())
                    || !stamp(coverage, current).equals(originalStamp))
                throw new IOException("Deep authority invalidated");
        }

        private void compareFresh() throws IOException, InterruptedException {
            var coverage = backend.collect(); // Fresh discovery, not an expired route lease.
            var snapshot = capture(coverage);
            if (!stamp(coverage, snapshot).equals(originalStamp))
                throw new IOException("Deep authority invalidated");
        }

        public synchronized void validate() throws IOException, InterruptedException {
            if (closed || failed || originalStamp == null)
                throw new IOException("Deep execution is not valid");
            try (Budget ignored = new Budget()) {
                compareFresh();
            } catch (IOException | InterruptedException | RuntimeException failure) {
                failed = true;
                throw failure;
            }
        }

        public SemanticReviewContext context() {
            SemanticReviewContext copy = new SemanticReviewContext();
            copy.setEvidence(context.getEvidence());
            copy.setLimitations(context.getLimitations());
            return copy;
        }

        public String instructions() {
            return skills.instructions();
        }

        public String worktree() {
            return root.toString();
        }

        private Request request(
                Operation operation,
                SourceInventoryClient.SourceCoverage coverage,
                String snapshotId) {
            Request request = new Request();
            request.setSchemaVersion(2);
            request.setProjectPath(root.toString());
            request.setNonce(UUID.randomUUID().toString());
            request.setOperation(operation);
            if (coverage != null) {
                request.setDiscoveryId(coverage.discovery().getDiscoveryId());
                request.setFiles(coverage.files());
                request.setChangedRanges(ranges);
                request.setSnapshotId(snapshotId);
            }
            return request;
        }

        private void ready(Snapshot snapshot) throws IOException {
            if (snapshot.getStatus() != Status.READY
                    || !root.toString().equals(snapshot.getProjectPath()))
                throw new IOException(
                        "Native snapshot not READY for exact worktree: "
                                + snapshot.getStatus()
                                + " "
                                + snapshot.getReasons());
        }

        private String stamp(SourceInventoryClient.SourceCoverage coverage, Snapshot snapshot)
                throws IOException {
            return hash(
                    List.of(
                            coverage.head(),
                            coverage.files(),
                            coverage.physicalFingerprint(),
                            coverage.discovery().getProjectInstanceId(),
                            coverage.discovery().getIdeBuild(),
                            coverage.discovery().getEpochs(),
                            coverage.discovery().getModel(),
                            coverage.discovery().getEntries(),
                            snapshot.getProjectInstanceId(),
                            snapshot.getIdeBuild(),
                            snapshot.getEpochs(),
                            snapshot.getSourceDigest(),
                            snapshot.getModelDigest(),
                            snapshot.getSettingsDigest()));
        }

        @Override
        public synchronized void close() throws IOException {
            if (!closed) {
                closed = true;
                release.close();
            }
        }
    }

    /**
     * Interrupts owned blocking calls at the total budget; closing never clears caller
     * cancellation.
     */
    private static final class Budget implements AutoCloseable {
        private final java.util.concurrent.ScheduledExecutorService scheduler =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread thread = new Thread(r, "semantic-collection-budget");
                            thread.setDaemon(true);
                            return thread;
                        });

        Budget() {
            Thread owner = Thread.currentThread();
            scheduler.schedule(owner::interrupt, 180, TimeUnit.SECONDS);
        }

        @Override
        public void close() {
            scheduler.shutdownNow();
        }
    }

    public static String digest(String text) throws IOException {
        if (text == null) throw new IOException("Diff required");
        return SemanticSkillBundle.sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String hash(Object value) throws IOException {
        return SemanticSkillBundle.sha256(JSON.writeValueAsBytes(value));
    }

    private static void identity(String pr, String operation) throws IOException {
        if (pr == null
                || pr.isBlank()
                || pr.length() > 512
                || operation == null
                || operation.isBlank()
                || operation.length() > 200)
            throw new IOException("PR and operation identity required");
    }
}
