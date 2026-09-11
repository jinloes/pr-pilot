package com.jinloes.prpilot.review;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.model.SourceInventory;
import com.jinloes.prpilot.model.SourceInventory.Content;
import com.jinloes.prpilot.model.SourceInventory.Coverage;
import com.jinloes.prpilot.model.SourceInventory.DiscoverRequest;
import com.jinloes.prpilot.model.SourceInventory.Discovery;
import com.jinloes.prpilot.model.SourceInventory.Entry;
import com.jinloes.prpilot.model.SourceInventory.Hash;
import com.jinloes.prpilot.model.SourceInventory.Kind;
import com.jinloes.prpilot.model.SourceInventory.Membership;
import com.jinloes.prpilot.model.SourceInventory.Operation;
import com.jinloes.prpilot.model.SourceInventory.Response;
import com.jinloes.prpilot.model.SourceInventory.Scope;
import com.jinloes.prpilot.model.SourceInventory.Source;
import com.jinloes.prpilot.model.SourceInventory.Status;
import com.jinloes.prpilot.model.SourceInventory.VerifyRequest;
import com.jinloes.prpilot.review.SourceInventoryFiles.Budget;
import com.jinloes.prpilot.review.SourceInventoryFiles.Leaf;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Internal prerequisite only: no engine RPC, host capability, provider, or readiness claim. */
public final class SourceInventoryClient {
    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Launch launch;
    private final Path ijctl, config, gitExecutable, directory;
    private final String server;
    private final BoundedProcessRunner runner = new BoundedProcessRunner();

    public static final class Failure extends IOException {
        private final String code;

        public Failure(String code, String detail) {
            super(code + ": " + detail);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public record SourceCoverage(
            String head,
            Discovery discovery,
            List<Hash> files,
            Coverage coverage,
            String physicalFingerprint) {}

    public record SettingsCapture(String projectPath, List<SourceInventoryFiles.Setting> files) {
        public SettingsCapture {
            files = List.copyOf(files);
        }
    }

    private record WorkerOutput(BoundedProcessRunner.ProcessResult output, String nonce) {}

    private record GitEntry(String mode, String objectId) {}

    private record GitSnapshot(String head, String algorithm, Map<String, GitEntry> entries) {}

    /**
     * All assets and environment values must be selected by the trusted caller, never project data.
     */
    public record Launch(
            Path java,
            Path workerJar,
            Path git,
            Path ijctl,
            Path config,
            String server,
            Path home,
            String executablePath) {}

    public SourceInventoryClient(Launch launch) throws IOException {
        this.launch =
                new Launch(
                        trustedFile(launch.java()),
                        trustedFile(launch.workerJar()),
                        trustedFile(launch.git()),
                        trustedFile(launch.ijctl()),
                        trustedFile(launch.config()),
                        launch.server(),
                        trustedDirectory(launch.home()),
                        launch.executablePath());
        for (Path executable :
                List.of(this.launch.java(), this.launch.git(), this.launch.ijctl())) {
            if (!Files.isExecutable(executable)) {
                throw new IllegalArgumentException("Trusted executable required");
            }
        }
        if (launch.server() == null
                || launch.server().isBlank()
                || launch.server().indexOf('\0') >= 0
                || launch.executablePath() == null
                || launch.executablePath().isEmpty()) {
            throw new IllegalArgumentException("Explicit server and executable PATH required");
        }
        for (String part : launch.executablePath().split(File.pathSeparator, -1)) {
            trustedDirectory(Path.of(part));
        }
        this.ijctl = this.launch.ijctl();
        this.config = this.launch.config();
        this.gitExecutable = this.launch.git();
        this.server = this.launch.server();
        directory = null;
    }

    private SourceInventoryClient(Path git, Path ijctl, Path config, String server, Path directory)
            throws IOException {
        launch = null;
        gitExecutable = trustedFile(git);
        this.ijctl = trustedFile(ijctl);
        this.config = trustedFile(config);
        this.server = server;
        this.directory = directory;
    }

    private static Path trustedFile(Path path) throws IOException {
        if (!path.isAbsolute() || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Trusted absolute regular file required");
        }
        return path.toRealPath();
    }

    private static Path trustedDirectory(Path path) throws IOException {
        if (!path.isAbsolute() || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Trusted absolute directory required");
        }
        return path.toRealPath();
    }

    public SourceCoverage collect(Path root, String expectedHead)
            throws IOException, InterruptedException {
        if (expectedHead == null || !expectedHead.matches("[0-9a-f]{40}|[0-9a-f]{64}")) {
            throw new Failure("GIT_CHANGED", "Expected full HEAD required");
        }
        WorkerOutput result = fork(root, expectedHead, null);
        return decodeWorker(result.output(), root, expectedHead, result.nonce());
    }

    public SettingsCapture captureSettings(Path root, List<String> paths)
            throws IOException, InterruptedException {
        SourceInventoryFiles.validateSettingsPaths(paths);
        WorkerOutput result = fork(root, "-", List.copyOf(paths));
        return decodeSettings(result.output(), root, paths, result.nonce());
    }

    private WorkerOutput fork(Path root, String expectedHead, List<String> settings)
            throws IOException, InterruptedException {
        SourceInventory.absolute(root.toString());
        for (Path asset :
                List.of(
                        launch.java(),
                        launch.workerJar(),
                        launch.git(),
                        launch.ijctl(),
                        launch.config(),
                        launch.home())) {
            outside(root, asset);
        }
        for (String part : launch.executablePath().split(File.pathSeparator, -1)) {
            outside(root, trustedDirectory(Path.of(part)));
        }
        Path parent = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        outside(root, parent);
        Path privateDirectory =
                Files.createTempDirectory(
                        parent,
                        "pr-pilot-source-",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
        String nonce = UUID.randomUUID().toString();
        try {
            if (settings != null) {
                byte[] bytes = JSON.writeValueAsBytes(settings);
                if (bytes.length > SourceInventory.MAX_JSON_BYTES) {
                    throw new Failure("LIMIT", "Settings request");
                }
                Path request = privateDirectory.resolve("request.json");
                Files.createFile(
                        request,
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
                Files.write(request, bytes, StandardOpenOption.WRITE);
            }
            List<String> command =
                    List.of(
                            launch.java().toString(),
                            "-Xmx256m",
                            "-jar",
                            launch.workerJar().toString(),
                            settings == null ? "--worker" : "--capture-settings",
                            root.toString(),
                            expectedHead,
                            launch.git().toString(),
                            launch.ijctl().toString(),
                            launch.config().toString(),
                            launch.server(),
                            nonce,
                            privateDirectory.toString());
            if (command.stream().mapToInt(s -> SourceInventory.utf8(s).length + 1).sum()
                    > 16 * 1024) {
                throw new Failure("LIMIT", "Worker arguments");
            }
            ProcessBuilder builder =
                    new ProcessBuilder(command).directory(privateDirectory.toFile());
            builder.environment().clear();
            builder.environment()
                    .putAll(
                            environment(
                                    launch.home().toString(),
                                    launch.executablePath(),
                                    privateDirectory));
            try {
                return new WorkerOutput(
                        runner.runOwnedTree(builder, settings == null ? 180 : 30, TimeUnit.SECONDS),
                        nonce);
            } catch (TimeoutException e) {
                throw new Failure("TRANSPORT_ERROR", "Worker timeout");
            }
        } finally {
            // The owned tree is gone before removing the only protocol file it may create.
            Files.deleteIfExists(privateDirectory.resolve("request.json"));
            Files.delete(privateDirectory);
        }
    }

    private static void outside(Path root, Path asset) throws Failure {
        // Do not perform parent-side worktree I/O through an IDE filesystem provider.
        if (Path.of(asset.toString()).startsWith(Path.of(root.toString()))) {
            throw new Failure("UNSAFE_PATH", "Launch assets/private storage inside worktree");
        }
    }

    private static Map<String, String> environment(String home, String path, Path directory) {
        Map<String, String> result = new HashMap<>();
        result.put("HOME", home);
        result.put("PATH", path);
        for (String key : List.of("TMPDIR", "TMP", "TEMP")) result.put(key, directory.toString());
        if (File.separatorChar == '\\' && System.getenv("SystemRoot") != null) {
            result.put("SystemRoot", System.getenv("SystemRoot"));
        }
        return result;
    }

    private record WorkerEnvelope(
            int schemaVersion, String nonce, String status, String code, Object result) {}

    /** Minimal jar entrypoint. It is never invoked in the IDE process. */
    public static void main(String[] args) throws IOException {
        String nonce = args.length == 9 ? args[7] : "";
        WorkerEnvelope envelope;
        try {
            if (args.length != 9
                    || !Set.of("--worker", "--capture-settings").contains(args[0])
                    || !UUID.fromString(nonce).toString().equals(nonce)
                    || List.of(args).stream()
                                    .mapToInt(s -> SourceInventory.utf8(s).length + 1)
                                    .sum()
                            > 16 * 1024) {
                throw new Failure("BAD_REQUEST", "Worker invocation");
            }
            Path root = Path.of(args[1]), privateDirectory = Path.of(args[8]);
            SourceInventory.absolute(root.toString());
            outside(root, privateDirectory);
            if (!privateDirectory.equals(trustedDirectory(privateDirectory))
                    || !Files.getPosixFilePermissions(privateDirectory)
                            .equals(PosixFilePermissions.fromString("rwx------"))
                    || !Files.getOwner(privateDirectory)
                            .getName()
                            .equals(System.getProperty("user.name"))) {
                throw new Failure("UNSAFE_PATH", "Worker private directory");
            }
            for (String asset : List.of(args[3], args[4], args[5]))
                outside(root, trustedFile(Path.of(asset)));
            if (!root.toRealPath().equals(root)) {
                throw new Failure("UNSAFE_PATH", "Physical root alias");
            }
            // Fail explicitly on runtimes without the secure-handle contract; no fallback.
            SourceInventoryFiles.requireDirectory(root, new Budget());
            SourceInventoryClient worker =
                    new SourceInventoryClient(
                            Path.of(args[3]),
                            Path.of(args[4]),
                            Path.of(args[5]),
                            args[6],
                            privateDirectory);
            if (args[0].equals("--capture-settings")) {
                Path request = privateDirectory.resolve("request.json");
                if (!Files.isRegularFile(request, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(request) > SourceInventory.MAX_JSON_BYTES) {
                    throw new Failure("BAD_REQUEST", "Settings request");
                }
                JsonNode node = JSON.readTree(Files.readAllBytes(request));
                if (!node.isArray()) throw new Failure("BAD_REQUEST", "Settings path list");
                List<String> paths = new ArrayList<>();
                for (JsonNode path : node) {
                    if (!path.isTextual()) throw new Failure("BAD_REQUEST", "Settings path");
                    paths.add(path.textValue());
                }
                envelope =
                        new WorkerEnvelope(
                                2,
                                nonce,
                                "COVERED",
                                null,
                                new SettingsCapture(
                                        root.toString(),
                                        SourceInventoryFiles.captureSettings(paths, new Budget())));
            } else {
                envelope =
                        new WorkerEnvelope(
                                2, nonce, "COVERED", null, worker.collectWorker(root, args[2]));
            }
        } catch (Exception | LinkageError e) {
            String code =
                    e instanceof Failure failure
                            ? failure.code()
                            : e instanceof SourceInventoryFiles.Failure failure
                                    ? failure.code().name()
                                    : e instanceof UnsupportedOperationException
                                            ? "UNSUPPORTED_RUNTIME"
                                            : e instanceof InterruptedException
                                                    ? "TRANSPORT_ERROR"
                                                    : "IO_ERROR";
            envelope =
                    new WorkerEnvelope(
                            2,
                            nonce,
                            code.startsWith("UNSUPPORTED_") ? "UNSUPPORTED" : "BLOCKED",
                            code,
                            null);
        }
        byte[] bytes = JSON.writeValueAsBytes(envelope);
        if (bytes.length > SourceInventory.MAX_JSON_BYTES) {
            bytes = JSON.writeValueAsBytes(new WorkerEnvelope(2, nonce, "BLOCKED", "LIMIT", null));
        }
        System.out.write(bytes);
        System.out.flush();
    }

    static SourceCoverage decodeWorker(
            BoundedProcessRunner.ProcessResult output, Path root, String head, String nonce)
            throws IOException {
        try {
            JsonNode result = workerResult(output, nonce);
            exactFields(
                    result,
                    Set.of("head", "discovery", "files", "coverage", "physicalFingerprint"));
            if (!result.get("head").isTextual()
                    || !result.get("head").textValue().equals(head)
                    || !result.get("files").isArray()
                    || result.get("files").size() > SourceInventory.MAX_FILES
                    || !isDigest(result.get("physicalFingerprint"))) {
                throw new Failure("TRANSPORT_ERROR", "Worker HEAD/files/physical identity");
            }
            Discovery discovery = bean(result.get("discovery"), Discovery.class);
            Coverage coverage = bean(result.get("coverage"), Coverage.class);
            List<Hash> files = new ArrayList<>();
            for (JsonNode file : result.get("files")) files.add(bean(file, Hash.class));
            SourceInventory.ordered(files);
            if (!root.toString().equals(discovery.getProjectPath())
                    || !buildSupported(discovery.getIdeBuild())) {
                throw new Failure("WRONG_PROJECT", "Worker discovery identity");
            }
            validateModel(root, discovery);
            if (!discovery.getEntries().stream()
                    .filter(e -> e.getMembership() == Membership.SOURCE)
                    .map(Entry::getPath)
                    .toList()
                    .equals(files.stream().map(Hash::getPath).toList())) {
                throw new Failure("MANIFEST_MISMATCH", "Worker source paths");
            }
            Response response = new Response();
            response.setStatus(Status.VFS_VERIFIED);
            response.setCoverage(coverage);
            coverage(discovery, files, response);
            return new SourceCoverage(
                    head,
                    discovery,
                    List.copyOf(files),
                    coverage,
                    result.get("physicalFingerprint").textValue());
        } catch (IllegalArgumentException e) {
            throw new Failure("TRANSPORT_ERROR", "Invalid worker payload");
        }
    }

    private static boolean isDigest(JsonNode node) {
        return node != null && node.isTextual() && node.textValue().matches("[0-9a-f]{64}");
    }

    static SettingsCapture decodeSettings(
            BoundedProcessRunner.ProcessResult output, Path root, List<String> paths, String nonce)
            throws IOException {
        SourceInventoryFiles.validateSettingsPaths(paths);
        JsonNode result = workerResult(output, nonce);
        exactFields(result, Set.of("projectPath", "files"));
        if (!result.get("projectPath").isTextual()
                || !result.get("projectPath").textValue().equals(root.toString())
                || !result.get("files").isArray()
                || result.get("files").size() != paths.size()) {
            throw new Failure("TRANSPORT_ERROR", "Settings identity/set");
        }
        List<SourceInventoryFiles.Setting> files = new ArrayList<>();
        for (int i = 0; i < paths.size(); i++) {
            JsonNode file = result.get("files").get(i);
            exactFields(file, Set.of("path", "present", "sha256", "physicalIdentity"));
            if (!file.get("path").isTextual()
                    || !file.get("path").textValue().equals(paths.get(i))
                    || !file.get("present").isBoolean()
                    || !isDigest(file.get("physicalIdentity"))
                    || (file.get("present").booleanValue()
                            ? !isDigest(file.get("sha256"))
                            : !file.get("sha256").isNull())) {
                throw new Failure("TRANSPORT_ERROR", "Settings fingerprint");
            }
            files.add(JSON.treeToValue(file, SourceInventoryFiles.Setting.class));
        }
        return new SettingsCapture(root.toString(), files);
    }

    private static JsonNode workerResult(BoundedProcessRunner.ProcessResult output, String nonce)
            throws IOException {
        if (output.exitCode() != 0
                || output.outputTruncated()
                || output.output().indexOf('\ufffd') >= 0
                || SourceInventory.utf8(output.output()).length > SourceInventory.MAX_JSON_BYTES) {
            throw new Failure("TRANSPORT_ERROR", "Worker exit/framing");
        }
        JsonNode node = JSON.readTree(output.output());
        exactFields(node, Set.of("schemaVersion", "nonce", "status", "code", "result"));
        if (!node.get("schemaVersion").isInt()
                || node.get("schemaVersion").intValue() != 2
                || !node.get("nonce").isTextual()
                || !node.get("nonce").textValue().equals(nonce)
                || !node.get("status").isTextual()) {
            throw new Failure("TRANSPORT_ERROR", "Worker version/nonce/status");
        }
        String status = node.get("status").textValue();
        if (!status.equals("COVERED")) {
            if (!Set.of("BLOCKED", "UNSUPPORTED").contains(status)
                    || !node.get("result").isNull()
                    || !node.get("code").isTextual()
                    || node.get("code").textValue().isBlank()) {
                throw new Failure("TRANSPORT_ERROR", "Worker failure shape");
            }
            throw new Failure(node.get("code").textValue(), "External worker rejected coverage");
        }
        if (!node.get("code").isNull()) throw new Failure("TRANSPORT_ERROR", "Worker success code");
        return node.get("result");
    }

    private static void exactFields(JsonNode node, Set<String> expected) throws Failure {
        Set<String> actual = new HashSet<>();
        if (node == null || !node.isObject())
            throw new Failure("TRANSPORT_ERROR", "Expected object");
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected))
            throw new Failure("TRANSPORT_ERROR", "Unknown/missing fields");
    }

    private static <T> T bean(JsonNode node, Class<T> type) throws IOException {
        SourceInventory.validateWire(JSON.convertValue(node, Object.class), type);
        T value = JSON.treeToValue(node, type);
        SourceInventory.validate(value);
        return value;
    }

    private SourceCoverage collectWorker(Path root, String expectedHead)
            throws IOException, InterruptedException {
        Budget total =
                new Budget(
                        Duration.ofSeconds(180),
                        SourceInventory.MAX_NODES,
                        SourceInventory.MAX_BYTES);
        GitSnapshot git = gitSnapshot(root, expectedHead, total);
        DiscoverRequest discover = request(new DiscoverRequest(), root, Operation.DISCOVER);
        Response first = call(root, discover, total);
        if (first.getStatus() != Status.DISCOVERED) {
            throw nativeFailure(first);
        }
        Discovery discovery = first.getDiscovery();
        if (!discovery.getProjectPath().equals(root.toString())
                || !buildSupported(discovery.getIdeBuild())) {
            throw new Failure("WRONG_PROJECT", "Discovery project/build mismatch");
        }
        validateModel(root, discovery);
        List<Leaf> leaves = reconcile(root, discovery, git, total.phase());
        String physicalFingerprint =
                physicalFingerprint(root, discovery, git, leaves, total.phase());
        List<Hash> hashes = hashes(root, discovery, git, total.phase());
        VerifyRequest verify = verification(root, discovery, hashes);
        Response verified = call(root, verify, total);
        Coverage coverage = coverage(discovery, hashes, verified);
        if (!git.equals(gitSnapshot(root, expectedHead, total))) {
            throw new Failure("GIT_CHANGED", "Git index or HEAD changed");
        }
        if (!leaves.equals(reconcile(root, discovery, git, total.phase()))) {
            throw new Failure("INVENTORY_CHANGED", "Physical inventory changed");
        }
        List<Hash> repeated = hashes(root, discovery, git, total.phase());
        if (!equal(hashes, repeated)) {
            throw new Failure("CONTENT_MISMATCH", "Source changed");
        }
        Response finalResponse = call(root, verification(root, discovery, repeated), total);
        Coverage finalCoverage = coverage(discovery, repeated, finalResponse);
        if (!equal(coverage, finalCoverage)) {
            throw new Failure("MODEL_CHANGED", "Coverage stamps changed");
        }
        if (!git.equals(gitSnapshot(root, expectedHead, total))) {
            throw new Failure("GIT_CHANGED", "Git changed during final verification");
        }
        if (!physicalFingerprint.equals(
                physicalFingerprint(
                        root,
                        discovery,
                        git,
                        reconcile(root, discovery, git, total.phase()),
                        total.phase()))) {
            throw new Failure("INVENTORY_CHANGED", "Source physical identity changed");
        }
        total.check();
        return new SourceCoverage(
                git.head(), discovery, List.copyOf(repeated), finalCoverage, physicalFingerprint);
    }

    private static String physicalFingerprint(
            Path root, Discovery discovery, GitSnapshot git, List<Leaf> leaves, Budget budget)
            throws IOException {
        Set<String> selected = new HashSet<>();
        discovery.getEntries().stream()
                .filter(e -> e.getMembership() == Membership.SOURCE)
                .forEach(e -> selected.add(e.getPath()));
        List<String> parts = new ArrayList<>();
        parts.add(git.head());
        parts.add(git.algorithm());
        for (String path : new TreeSet<>(git.entries().keySet())) {
            parts.add(path);
            parts.add(git.entries().get(path).mode());
            parts.add(git.entries().get(path).objectId());
        }
        for (Leaf leaf : leaves) {
            if (selected.contains(leaf.path())) parts.add(SourceInventoryFiles.leafIdentity(leaf));
        }
        Set<String> roots = new TreeSet<>();
        for (Content content : discovery.getModel().getContents()) {
            for (Source source : content.getSources()) {
                roots.add(source.getLocation().getNativePath());
            }
        }
        for (String path : roots) {
            parts.add(path);
            parts.add(SourceInventoryFiles.directoryIdentity(Path.of(path), budget));
        }
        return SourceInventoryFiles.fingerprint(parts);
    }

    private static boolean buildSupported(String build) {
        try {
            String numeric = build.contains("-") ? build.substring(build.indexOf('-') + 1) : build;
            return Integer.parseInt(numeric.split("\\.")[0]) >= 262;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static <T extends DiscoverRequest> T request(
            T request, Path root, Operation operation) {
        request.setSchemaVersion(2);
        request.setOperation(operation);
        request.setProjectPath(root.toString());
        request.setNonce(UUID.randomUUID().toString());
        return request;
    }

    private static VerifyRequest verification(Path root, Discovery discovery, List<Hash> files) {
        VerifyRequest request = request(new VerifyRequest(), root, Operation.VERIFY);
        request.setDiscoveryId(discovery.getDiscoveryId());
        request.setFiles(files);
        return request;
    }

    private static Failure nativeFailure(Response response) {
        return new Failure(
                response.getReasons().isEmpty()
                        ? "BAD_REQUEST"
                        : response.getReasons().get(0).getCode().name(),
                "Native inventory rejected request");
    }

    private Response call(Path root, DiscoverRequest request, Budget total)
            throws IOException, InterruptedException {
        SourceInventory.validate(request);
        byte[] bytes = JSON.writeValueAsBytes(request);
        if (bytes.length > SourceInventory.MAX_JSON_BYTES) {
            throw new Failure("LIMIT", "Request");
        }
        Path arguments = directory.resolve("request.json");
        try {
            Files.createFile(
                    arguments,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-------")));
            Files.write(arguments, bytes);
            ProcessBuilder builder =
                    new ProcessBuilder(
                            ijctl.toString(),
                            "--config",
                            config.toString(),
                            "--project",
                            root.toString(),
                            "--server",
                            server,
                            "--no-daemon",
                            "--timeout",
                            "30000",
                            "call",
                            SourceInventory.TOOL,
                            "--args-file",
                            arguments.toString());
            builder.directory(directory.toFile());
            sanitize(builder, false);
            BoundedProcessRunner.ProcessResult output = run(builder, total);
            return decode(output, request, server);
        } finally {
            Files.deleteIfExists(arguments);
        }
    }

    static Response decode(
            BoundedProcessRunner.ProcessResult output, DiscoverRequest request, String server)
            throws IOException {
        try {
            if (output.exitCode() != 0
                    || output.outputTruncated()
                    || output.output().indexOf('\ufffd') >= 0
                    || SourceInventory.utf8(output.output()).length
                            > SourceInventory.MAX_JSON_BYTES) {
                throw new Failure("TRANSPORT_ERROR", "Exit, truncation, or encoding");
            }
            JsonNode envelope = JSON.readTree(output.output());
            if (envelope == null || !envelope.isObject()) {
                throw new Failure("TRANSPORT_ERROR", "No object envelope");
            }
            Set<String> allowed =
                    Set.of(
                            "ok",
                            "command",
                            "durationMs",
                            "server",
                            "connectionMode",
                            "tool",
                            "result",
                            "target",
                            "safety",
                            "warning");
            envelope.fieldNames()
                    .forEachRemaining(
                            name -> {
                                if (!allowed.contains(name)) {
                                    throw new IllegalArgumentException("Unknown envelope field");
                                }
                            });
            if (!envelope.path("ok").isBoolean()
                    || !envelope.path("ok").booleanValue()
                    || !envelope.path("command").isTextual()
                    || !envelope.path("command").textValue().equals("call")
                    || !envelope.path("server").isTextual()
                    || !envelope.path("server").textValue().equals(server)
                    || !envelope.path("tool").isTextual()
                    || !envelope.path("tool").textValue().equals(SourceInventory.TOOL)
                    || !envelope.path("connectionMode").isTextual()
                    || !envelope.path("connectionMode").textValue().equals("direct")) {
                throw new Failure("TRANSPORT_ERROR", "Mismatched envelope");
            }
            JsonNode result = envelope.path("result");
            if (!result.isObject()) {
                throw new Failure("TRANSPORT_ERROR", "No result");
            }
            result.fieldNames()
                    .forEachRemaining(
                            name -> {
                                if (!Set.of("content", "structuredContent", "isError", "_meta")
                                        .contains(name)) {
                                    throw new IllegalArgumentException("Unknown result field");
                                }
                            });
            if (result.has("isError")
                    && (!result.get("isError").isBoolean()
                            || result.get("isError").booleanValue())) {
                throw new Failure("TRANSPORT_ERROR", "MCP error");
            }
            JsonNode structured = result.get("structuredContent");
            if (structured != null && !structured.isObject()) {
                throw new Failure("TRANSPORT_ERROR", "Structured payload");
            }
            JsonNode content = result.get("content");
            JsonNode text = null;
            if (content != null) {
                if (!content.isArray() || content.size() > 1) {
                    throw new Failure("TRANSPORT_ERROR", "Content count");
                }
                if (!content.isEmpty()) {
                    JsonNode item = content.get(0);
                    if (!item.isObject()
                            || item.size() != 2
                            || !item.path("type").isTextual()
                            || !item.path("type").textValue().equals("text")
                            || !item.path("text").isTextual()) {
                        throw new Failure("TRANSPORT_ERROR", "Non-JSON text content");
                    }
                    text = JSON.readTree(item.path("text").textValue());
                }
            }
            if (structured == null && text == null
                    || structured != null && text != null && !structured.equals(text)) {
                throw new Failure("TRANSPORT_ERROR", "Absent or disagreeing payload");
            }
            JsonNode payload = structured != null ? structured : text;
            SourceInventory.validateWire(JSON.convertValue(payload, Object.class), Response.class);
            Response response = JSON.treeToValue(payload, Response.class);
            SourceInventory.validate(response);
            if (!response.getNonce().equals(request.getNonce())
                    || response.getOperation() != request.getOperation()) {
                throw new Failure("BAD_REQUEST", "Response nonce or operation mismatch");
            }
            return response;
        } catch (IllegalArgumentException e) {
            throw new Failure("TRANSPORT_ERROR", "Invalid typed payload: " + e.getMessage());
        }
    }

    private static void validateModel(Path root, Discovery discovery) throws Failure {
        Map<String, Set<String>> roots = new HashMap<>();
        if (!discovery.getModel().getUnloadedModules().isEmpty()) {
            throw new Failure("UNLOADED_MODULE", "Native model");
        }
        for (Content content : discovery.getModel().getContents()) {
            for (Source source : content.getSources()) {
                if (source.getLocation().getScope() != Scope.WORKTREE
                        || !Path.of(source.getLocation().getNativePath())
                                .startsWith(Path.of(root.toString()))) {
                    throw new Failure("EXTERNAL_SOURCE_ROOT", "Source model is not confined");
                }
                roots.computeIfAbsent(source.getLocation().getUrl(), ignored -> new HashSet<>())
                        .add(content.getModule());
            }
        }
        if (roots.isEmpty()) {
            throw new Failure("EMPTY_SOURCE_MODEL", "No sources");
        }
        for (Entry entry : discovery.getEntries()) {
            if (entry.getMembership() == Membership.UNKNOWN) {
                throw new Failure("UNKNOWN_MEMBERSHIP", entry.getPath());
            }
            if (entry.getMembership() == Membership.SOURCE
                    && (!roots.containsKey(entry.getSourceRootUrl())
                            || !roots.get(entry.getSourceRootUrl())
                                    .containsAll(entry.getModules()))) {
                throw new Failure("UNMAPPED_SOURCE_ROOT", entry.getPath());
            }
        }
    }

    private List<Leaf> reconcile(Path root, Discovery discovery, GitSnapshot git, Budget budget)
            throws IOException {
        for (Content content : discovery.getModel().getContents()) {
            for (Source source : content.getSources()) {
                Path path = Path.of(source.getLocation().getNativePath());
                if (!source.getLocation().getUrl().equals("file://" + path)
                        || path.startsWith(root.resolve(".git"))) {
                    throw new Failure("UNSAFE_PATH", "Source-root URL/path");
                }
                SourceInventoryFiles.requireDirectory(path, budget);
            }
        }
        List<Leaf> leaves = SourceInventoryFiles.scan(root, budget);
        if (leaves.size() != discovery.getEntries().size()) {
            throw new Failure("INVENTORY_CHANGED", "Incomplete native inventory");
        }
        Map<String, Leaf> physical = new HashMap<>();
        for (int i = 0; i < leaves.size(); i++) {
            Leaf leaf = leaves.get(i);
            Entry entry = discovery.getEntries().get(i);
            if (!leaf.path().equals(entry.getPath()) || leaf.kind() != entry.getKind()) {
                throw new Failure("INVENTORY_CHANGED", "Native path/kind mismatch");
            }
            physical.put(leaf.path(), leaf);
            if (entry.getMembership() == Membership.SOURCE
                    && !git.entries().containsKey(entry.getPath())) {
                throw new Failure("SOURCE_CONTAMINATION", entry.getPath());
            }
        }
        for (Map.Entry<String, GitEntry> entry : git.entries().entrySet()) {
            Leaf leaf = physical.get(entry.getKey());
            Kind expected = entry.getValue().mode().equals("120000") ? Kind.SYMLINK : Kind.FILE;
            if (leaf == null || leaf.kind() != expected) {
                throw new Failure("UNSUPPORTED_GIT_ENTRY", entry.getKey());
            }
        }
        return leaves;
    }

    private List<Hash> hashes(Path root, Discovery discovery, GitSnapshot git, Budget budget)
            throws IOException {
        List<Hash> hashes = new ArrayList<>();
        for (Entry entry : discovery.getEntries()) {
            if (entry.getMembership() != Membership.SOURCE) {
                continue;
            }
            GitEntry tracked = git.entries().get(entry.getPath());
            if (entry.getKind() != Kind.FILE
                    || tracked == null
                    || tracked.mode().equals("120000")) {
                throw new Failure("UNSUPPORTED_GIT_ENTRY", entry.getPath());
            }
            var hash = SourceInventoryFiles.hash(root, entry.getPath(), budget, git.algorithm());
            if (!hash.gitObjectId().equals(tracked.objectId())) {
                throw new Failure("CONTENT_MISMATCH", entry.getPath());
            }
            hashes.add(new Hash(entry.getPath(), hash.sha256()));
        }
        if (hashes.isEmpty()) {
            throw new Failure("EMPTY_SOURCE_MODEL", "No source files");
        }
        return hashes;
    }

    private static Coverage coverage(Discovery discovery, List<Hash> hashes, Response response)
            throws Failure {
        if (response.getStatus() != Status.VFS_VERIFIED) {
            throw nativeFailure(response);
        }
        Coverage coverage = response.getCoverage();
        if (!coverage.getDiscoveryId().equals(discovery.getDiscoveryId())
                || !coverage.getProjectPath().equals(discovery.getProjectPath())
                || !coverage.getProjectInstanceId().equals(discovery.getProjectInstanceId())
                || !coverage.getIdeBuild().equals(discovery.getIdeBuild())
                || !equal(coverage.getEpochs(), discovery.getEpochs())
                || coverage.getFileCount() != hashes.size()
                || !coverage.getSourceManifestSha256().equals(SourceInventory.manifest(hashes))) {
            throw new Failure("MANIFEST_MISMATCH", "Coverage identity/count/digest");
        }
        return coverage;
    }

    private GitSnapshot gitSnapshot(Path root, String expected, Budget budget)
            throws IOException, InterruptedException {
        String actualRoot = git(root, budget, "rev-parse", "--show-toplevel").stripTrailing();
        if (!root.toString().equals(actualRoot)) {
            throw new Failure("WRONG_PROJECT", "Not the Git root");
        }
        String algorithm = git(root, budget, "rev-parse", "--show-object-format").strip();
        int length;
        if (algorithm.equals("sha1")) {
            length = 40;
        } else if (algorithm.equals("sha256")) {
            length = 64;
        } else {
            throw new Failure("UNSUPPORTED_GIT_ENTRY", "Object format");
        }
        String head = git(root, budget, "rev-parse", "--verify", "HEAD").strip();
        if (expected == null
                || !expected.matches("[0-9a-f]{" + length + "}")
                || !head.equals(expected)) {
            throw new Failure("GIT_CHANGED", "HEAD mismatch");
        }
        Map<String, GitEntry> index =
                parseGit(git(root, budget, "ls-files", "--stage", "-z"), true, length);
        Map<String, GitEntry> tree =
                parseGit(
                        git(root, budget, "ls-tree", "-r", "-z", "--full-tree", "HEAD"),
                        false,
                        length);
        if (!index.equals(tree)) {
            throw new Failure("GIT_CHANGED", "Index differs from HEAD");
        }
        if (!git(root, budget, "rev-parse", "--verify", "HEAD").strip().equals(head)) {
            throw new Failure("GIT_CHANGED", "HEAD changed during read");
        }
        return new GitSnapshot(
                head, algorithm.equals("sha1") ? "SHA-1" : "SHA-256", Map.copyOf(index));
    }

    private static Map<String, GitEntry> parseGit(String raw, boolean index, int hashLength)
            throws Failure {
        Map<String, GitEntry> entries = new HashMap<>();
        if (raw.isEmpty()) {
            return entries;
        }
        if (!raw.endsWith("\0") || raw.indexOf('\ufffd') >= 0) {
            throw new Failure("UNSUPPORTED_GIT_ENTRY", "Encoding/framing");
        }
        String[] records = raw.substring(0, raw.length() - 1).split("\0", -1);
        for (String record : records) {
            int tab = record.indexOf('\t');
            if (tab < 0) {
                throw new Failure("UNSUPPORTED_GIT_ENTRY", "Record");
            }
            String[] header = record.substring(0, tab).split(" ", -1);
            String path = record.substring(tab + 1);
            try {
                SourceInventory.path(path);
            } catch (IllegalArgumentException e) {
                throw new Failure("UNSUPPORTED_GIT_ENTRY", "Path");
            }
            if (header.length != 3 || !Set.of("100644", "100755", "120000").contains(header[0])) {
                throw new Failure("UNSUPPORTED_GIT_ENTRY", path);
            }
            if (index ? !header[2].equals("0") : !header[1].equals("blob")) {
                throw new Failure("UNSUPPORTED_GIT_ENTRY", path);
            }
            String object = header[index ? 1 : 2];
            if (!object.matches("[0-9a-f]{" + hashLength + "}")
                    || entries.putIfAbsent(path, new GitEntry(header[0], object)) != null) {
                throw new Failure("UNSUPPORTED_GIT_ENTRY", path);
            }
            if (entries.size() > SourceInventory.MAX_NODES) {
                throw new Failure("LIMIT", "Git entries");
            }
        }
        return entries;
    }

    private String git(Path root, Budget budget, String... args)
            throws IOException, InterruptedException {
        List<String> command =
                new ArrayList<>(
                        List.of(
                                gitExecutable.toString(),
                                "--no-optional-locks",
                                "-c",
                                "core.fsmonitor=false",
                                "-c",
                                "core.untrackedCache=false",
                                "-C",
                                root.toString()));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        sanitize(builder, true);
        BoundedProcessRunner.ProcessResult result = run(builder, budget);
        if (result.exitCode() != 0
                || result.outputTruncated()
                || result.output().indexOf('\ufffd') >= 0) {
            throw new Failure("GIT_CHANGED", "Git exit/truncation/encoding");
        }
        return result.output();
    }

    private void sanitize(ProcessBuilder builder, boolean git) {
        builder.environment().clear();
        builder.environment()
                .putAll(environment(System.getenv("HOME"), System.getenv("PATH"), directory));
        if (git) {
            builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
            builder.environment()
                    .put("GIT_CONFIG_GLOBAL", File.separatorChar == '\\' ? "NUL" : "/dev/null");
        }
    }

    private BoundedProcessRunner.ProcessResult run(ProcessBuilder builder, Budget total)
            throws IOException, InterruptedException {
        try {
            return runner.runOwnedTree(
                    builder, Math.min(30_000, total.remainingMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new Failure("TRANSPORT_ERROR", "Process timeout");
        }
    }

    private static boolean equal(Object left, Object right) {
        return JSON.valueToTree(left).equals(JSON.valueToTree(right));
    }
}
