package com.jinloes.prpilot.review;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.model.SourceInventory;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Machine-local launch policy and digest-verified packaged worker lifetime. */
public final class SemanticRuntime implements AutoCloseable {
    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String RESOURCE = "semantic-worker/";
    private static final String WORKER = "pr-pilot-source-inventory-worker.jar";
    private static final int MAX_WORKER_BYTES = 32 * 1024 * 1024;
    private final Path storage;
    private final Path java;
    private final Path git;
    private final Path ijctl;
    private final Path config;
    private final Path home;
    private final String executablePath;
    private final List<String> servers;
    private boolean closed;

    private SemanticRuntime(
            Path storage,
            Path java,
            Path git,
            Path ijctl,
            Path config,
            Path home,
            String executablePath,
            List<String> servers) {
        this.storage = storage;
        this.java = java;
        this.git = git;
        this.ijctl = ijctl;
        this.config = config;
        this.home = home;
        this.executablePath = executablePath;
        this.servers = List.copyOf(servers);
    }

    public static SemanticRuntime open(Path worktree) throws IOException, InterruptedException {
        return open(
                worktree,
                Path.of(System.getProperty("user.home"), ".pr-pilot", "semantic-review.json"),
                SemanticRuntime.class.getClassLoader());
    }

    static SemanticRuntime open(Path worktree, Path launchConfig, ClassLoader loader)
            throws IOException, InterruptedException {
        try {
            trusted(launchConfig, worktree, false, true);
            if (Files.size(launchConfig) > 64 * 1024) throw setup("Launch configuration too large");
            JsonNode node = JSON.readTree(Files.readAllBytes(launchConfig));
            fields(
                    node,
                    Set.of(
                            "schemaVersion",
                            "java",
                            "git",
                            "ijctl",
                            "ijctlConfig",
                            "home",
                            "path",
                            "servers"));
            if (!node.get("schemaVersion").isInt() || node.get("schemaVersion").intValue() != 1) {
                throw setup("Launch configuration schema must be 1");
            }
            Path java = asset(node, "java", worktree, false, false);
            Path git = asset(node, "git", worktree, false, false);
            Path ijctl = asset(node, "ijctl", worktree, false, false);
            Path config = asset(node, "ijctlConfig", worktree, false, true);
            Path home = asset(node, "home", worktree, true, true);
            for (Path executable : List.of(java, git, ijctl)) {
                if (!Files.isExecutable(executable)) throw setup("Non-executable launch asset");
            }
            List<String> paths = strings(node.get("path"));
            List<String> servers = strings(node.get("servers"));
            for (String path : paths) trusted(Path.of(path), worktree, true, false);
            Path nodeExecutable = null;
            for (String path : paths) {
                Path candidate = Path.of(path, "node");
                if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    nodeExecutable = trusted(candidate, worktree, false, false);
                    break;
                }
            }
            if (nodeExecutable == null || !Files.isExecutable(nodeExecutable)) {
                throw setup("Explicit trusted PATH must contain Node >=20");
            }
            Path temp = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
            outside(temp, worktree);
            Path storage =
                    Files.createTempDirectory(
                            temp,
                            "pr-pilot-semantic-",
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rwx------")));
            SemanticRuntime runtime =
                    new SemanticRuntime(
                            storage,
                            java,
                            git,
                            ijctl,
                            config,
                            home,
                            String.join(File.pathSeparator, paths),
                            servers);
            try {
                extract(loader, storage);
                String nodeVersion = runtime.version(nodeExecutable);
                if (!nodeVersion.matches("v[0-9]+\\.[0-9]+\\.[0-9]+")
                        || Integer.parseInt(nodeVersion.substring(1).split("\\.")[0]) < 20) {
                    throw setup("Node >=20 required");
                }
                if (!runtime.version(ijctl).matches("v?0\\.3\\.0")) {
                    throw setup("ijctl exactly 0.3.0 required");
                }
                return runtime;
            } catch (IOException | InterruptedException | RuntimeException e) {
                runtime.close();
                throw e;
            }
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            throw setup("Unsupported or invalid trusted launch configuration");
        }
    }

    public List<String> servers() {
        return servers;
    }

    public synchronized SourceInventoryClient.Launch launch(String server) throws IOException {
        if (closed || !servers.contains(server)) throw setup("Runtime closed or unknown server");
        return new SourceInventoryClient.Launch(
                java, storage.resolve(WORKER), git, ijctl, config, server, home, executablePath);
    }

    private String version(Path executable) throws IOException, InterruptedException {
        ProcessBuilder builder =
                new ProcessBuilder(executable.toString(), "--version").directory(storage.toFile());
        builder.environment().clear();
        builder.environment().put("HOME", home.toString());
        builder.environment().put("PATH", executablePath);
        for (String key : List.of("TMPDIR", "TMP", "TEMP")) {
            builder.environment().put(key, storage.toString());
        }
        try {
            BoundedProcessRunner.ProcessResult result =
                    new BoundedProcessRunner().runOwnedTree(builder, 30, TimeUnit.SECONDS);
            if (result.exitCode() != 0
                    || result.outputTruncated()
                    || result.output().length() > 1024) throw setup("Version probe failed");
            return result.output().strip();
        } catch (TimeoutException e) {
            throw setup("Version probe timed out");
        }
    }

    static void extract(ClassLoader loader, Path storage) throws IOException {
        JsonNode manifest = JSON.readTree(resource(loader, "manifest.json", 4096));
        fields(manifest, Set.of("schemaVersion", "sha256"));
        if (!manifest.get("schemaVersion").isInt()
                || manifest.get("schemaVersion").intValue() != 2
                || !manifest.get("sha256").isTextual()
                || !manifest.get("sha256").textValue().matches("[0-9a-f]{64}")) {
            throw setup("Packaged worker version mismatch");
        }
        byte[] worker = resource(loader, WORKER, MAX_WORKER_BYTES);
        String digest = HexFormat.of().formatHex(SourceInventory.digest("SHA-256").digest(worker));
        if (!digest.equals(manifest.get("sha256").textValue())) {
            throw setup("Packaged worker digest mismatch");
        }
        Path target = storage.resolve(WORKER);
        Files.createFile(
                target,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.write(target, worker);
    }

    private static byte[] resource(ClassLoader loader, String name, int limit) throws IOException {
        try (InputStream stream = loader.getResourceAsStream(RESOURCE + name)) {
            if (stream == null) throw setup("Missing packaged worker resource");
            byte[] bytes = stream.readNBytes(limit + 1);
            if (bytes.length == 0 || bytes.length > limit) throw setup("Worker resource size");
            return bytes;
        }
    }

    private static List<String> strings(JsonNode node) throws IOException {
        if (node == null || !node.isArray() || node.isEmpty() || node.size() > 64) {
            throw setup("Nonempty bounded string array required");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()
                    || item.textValue().isBlank()
                    || item.textValue().indexOf('\0') >= 0
                    || item.textValue().length() > 4096
                    || result.contains(item.textValue()))
                throw setup("Invalid or duplicate string");
            result.add(item.textValue());
        }
        return List.copyOf(result);
    }

    private static Path asset(
            JsonNode node, String name, Path root, boolean directory, boolean owned)
            throws IOException {
        if (!node.get(name).isTextual()) throw setup("Absolute launch path required");
        return trusted(Path.of(node.get(name).textValue()), root, directory, owned);
    }

    static Path trusted(Path path, Path root, boolean directory, boolean owned) throws IOException {
        if (!path.isAbsolute() || !path.normalize().equals(path)) {
            throw setup("Canonical absolute launch path required");
        }
        outside(path, root);
        for (Path current = path; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw setup("Symlink launch path");
            Set<PosixFilePermission> permissions =
                    Files.getPosixFilePermissions(current, LinkOption.NOFOLLOW_LINKS);
            if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                throw setup("Writable launch path");
            }
            String owner = Files.getOwner(current, LinkOption.NOFOLLOW_LINKS).getName();
            if (!owner.equals(System.getProperty("user.name")) && !owner.equals("root")) {
                throw setup("Untrusted launch owner");
            }
        }
        if (directory
                ? !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                : !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw setup("Wrong launch asset type");
        }
        if (owned && !Files.getOwner(path).getName().equals(System.getProperty("user.name"))) {
            throw setup("Current-user-owned configuration required");
        }
        return path;
    }

    private static void outside(Path path, Path root) throws IOException {
        if (path.startsWith(root)) throw setup("Worktree-local runtime asset forbidden");
    }

    private static void fields(JsonNode node, Set<String> expected) throws IOException {
        if (node == null || !node.isObject()) throw setup("Expected object");
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw setup("Unknown or missing fields");
    }

    private static IOException setup(String detail) {
        return new IOException(
                detail
                        + ". Configure owner-only ~/.pr-pilot/semantic-review.json"
                        + " (schema 1); install a secure-filesystem JDK, Node >=20 and ijctl 0.3.0 manually.");
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            Files.deleteIfExists(storage.resolve(WORKER));
            Files.delete(storage);
            closed = true;
        }
    }
}
