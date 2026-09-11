package com.jinloes.prpilot.review;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Durable ownership is independent of process lifetime; OS locks, not timestamps, mean active. */
public final class SemanticWorktreeStore {
    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Path directory;
    private final GitWorktreeService git;

    public record Retained(
            String id, String repository, String worktree, String head, long createdAt) {}

    public SemanticWorktreeStore() {
        this(Path.of(System.getProperty("user.home"), ".pr-pilot"), new GitWorktreeService());
    }

    SemanticWorktreeStore(Path directory, GitWorktreeService git) {
        this.directory = directory.toAbsolutePath();
        this.git = git;
    }

    public Retained register(Path repository, Path worktree, String head) throws IOException {
        canonicalDirectory(repository);
        canonicalDirectory(worktree);
        if (!head.matches("[0-9a-f]{40}|[0-9a-f]{64}")
                || repository.equals(worktree)
                || !git.isRegisteredHead(repository.toFile(), worktree.toFile(), head)) {
            throw new IOException("Managed worktree identity mismatch");
        }
        return locked(
                records -> {
                    if (records.stream().anyMatch(r -> r.worktree().equals(worktree.toString())))
                        throw new IOException("Worktree already retained");
                    Retained retained =
                            new Retained(
                                    UUID.randomUUID().toString(),
                                    repository.toString(),
                                    worktree.toString(),
                                    head,
                                    System.currentTimeMillis());
                    records.add(retained);
                    write(records);
                    return retained;
                });
    }

    public List<Retained> list() throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        return locked(records -> List.copyOf(records));
    }

    public Lease acquire(String id) throws IOException {
        uuid(id);
        return locked(
                records -> {
                    Retained record = find(records, id);
                    Lease lease = lease(record);
                    try {
                        validateRegistration(record);
                        return lease;
                    } catch (IOException | RuntimeException failure) {
                        lease.close();
                        throw failure;
                    }
                });
    }

    public boolean cleanup(String id, boolean projectClosed) throws IOException {
        uuid(id);
        if (!projectClosed) throw new IOException("Explicit project-closed confirmation required");
        return locked(
                records -> {
                    Retained record = find(records, id);
                    try (Lease ignored = lease(record)) {
                        validateRegistration(record);
                        if (!git.removeManagedWorktree(
                                Path.of(record.repository()).toFile(),
                                Path.of(record.worktree()).toFile())) return false;
                        records.remove(record);
                        write(records);
                        return true;
                    }
                });
    }

    /**
     * Ordinary cleanup cannot bypass retained ownership, including when the registry is corrupt.
     */
    boolean ordinaryRemoval(Path worktree, Removal removal) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return removal.remove();
        return locked(
                records -> {
                    Path target =
                            Files.exists(worktree, LinkOption.NOFOLLOW_LINKS)
                                    ? worktree.toRealPath()
                                    : worktree.toAbsolutePath().normalize();
                    if (records.stream().anyMatch(r -> Path.of(r.worktree()).equals(target)))
                        return false;
                    return removal.remove();
                });
    }

    @FunctionalInterface
    interface Removal {
        boolean remove() throws IOException;
    }

    private void validateRegistration(Retained r) throws IOException {
        canonicalDirectory(Path.of(r.repository()));
        canonicalDirectory(Path.of(r.worktree()));
        if (!git.isRegisteredHead(
                Path.of(r.repository()).toFile(), Path.of(r.worktree()).toFile(), r.head()))
            throw new IOException("Retained Git registration or HEAD changed");
    }

    private static Retained find(List<Retained> records, String id) throws IOException {
        return records.stream()
                .filter(r -> r.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IOException("Unknown retained review"));
    }

    private Lease lease(Retained record) throws IOException {
        Path leases = directory.resolve("semantic-leases");
        ensureDirectory(leases);
        FileChannel channel = openLock(leases.resolve(record.id() + ".lock"));
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("Retained worktree is active");
            return new Lease(record, channel, lock);
        } catch (IOException | OverlappingFileLockException failure) {
            channel.close();
            throw new IOException("Retained worktree is active or cannot be locked", failure);
        }
    }

    public static final class Lease implements AutoCloseable {
        private final Retained retained;
        private final FileChannel channel;
        private final FileLock lock;

        private Lease(Retained retained, FileChannel channel, FileLock lock) {
            this.retained = retained;
            this.channel = channel;
            this.lock = lock;
        }

        public Retained retained() {
            return retained;
        }

        @Override
        public void close() throws IOException {
            try {
                if (lock.isValid()) lock.release();
            } finally {
                channel.close();
            }
        }
    }

    @FunctionalInterface
    private interface Transaction<T> {
        T apply(List<Retained> records) throws IOException;
    }

    private <T> T locked(Transaction<T> transaction) throws IOException {
        ensureDirectory(directory);
        try (FileChannel channel = openLock(directory.resolve("semantic-worktrees.lock"));
                FileLock ignored = channel.lock()) {
            return transaction.apply(read());
        } catch (OverlappingFileLockException e) {
            throw new IOException("Retained registry busy; retry", e);
        }
    }

    private List<Retained> read() throws IOException {
        Path path = directory.resolve("semantic-worktrees.json");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return new ArrayList<>();
        ownedFile(path);
        if (Files.size(path) > 1024 * 1024) throw new IOException("Retained registry limit");
        List<Retained> values = JSON.readValue(Files.readAllBytes(path), new TypeReference<>() {});
        if (values == null || values.size() > 1000)
            throw new IOException("Invalid retained registry");
        var ids = new java.util.HashSet<String>();
        var paths = new java.util.HashSet<String>();
        try {
            for (Retained r : values) {
                uuid(r.id());
                if (!ids.add(r.id())
                        || !paths.add(r.worktree())
                        || r.createdAt() <= 0
                        || !r.head().matches("[0-9a-f]{40}|[0-9a-f]{64}"))
                    throw new IOException("Invalid retained registry identity");
                for (String p : List.of(r.repository(), r.worktree())) {
                    Path candidate = Path.of(p);
                    if (!candidate.isAbsolute()
                            || !candidate.normalize().equals(candidate)
                            || p.chars().anyMatch(Character::isISOControl))
                        throw new IOException("Invalid retained path");
                }
            }
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IOException("Corrupt retained registry", e);
        }
        return new ArrayList<>(values);
    }

    private void write(List<Retained> records) throws IOException {
        if (records.size() > 1000) throw new IOException("Retained review limit");
        byte[] bytes = JSON.writeValueAsBytes(records);
        if (bytes.length > 1024 * 1024) throw new IOException("Retained registry limit");
        Path temporary =
                Files.createTempFile(
                        directory,
                        "semantic-worktrees-",
                        ".tmp",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
        try {
            try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) file.write(buffer);
                file.force(true);
            }
            Files.move(
                    temporary,
                    directory.resolve("semantic-worktrees.json"),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static FileChannel openLock(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createFile(
                        path,
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
            }
        }
        ownedFile(path);
        return FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }

    private static void ownedFile(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !Files.getOwner(path, LinkOption.NOFOLLOW_LINKS)
                        .getName()
                        .equals(System.getProperty("user.name"))
                || Files.getPosixFilePermissions(path).stream()
                        .anyMatch(
                                p ->
                                        p.name().startsWith("GROUP_")
                                                || p.name().startsWith("OTHERS_")))
            throw new IOException("Unsafe retained registry/lock");
    }

    private static void ensureDirectory(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            canonicalDirectory(path.getParent());
            try {
                Files.createDirectory(
                        path,
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
            }
        }
        canonicalDirectory(path);
        if (!Files.getOwner(path).getName().equals(System.getProperty("user.name"))
                || Files.getPosixFilePermissions(path)
                        .contains(java.nio.file.attribute.PosixFilePermission.GROUP_WRITE)
                || Files.getPosixFilePermissions(path)
                        .contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE))
            throw new IOException("Unsafe retained directory");
    }

    private static void canonicalDirectory(Path path) throws IOException {
        if (!path.isAbsolute()
                || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                || !path.toRealPath().equals(path))
            throw new IOException("Canonical directory required");
    }

    private static void uuid(String id) throws IOException {
        try {
            if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IOException("Retained ID must be UUID", e);
        }
    }
}
