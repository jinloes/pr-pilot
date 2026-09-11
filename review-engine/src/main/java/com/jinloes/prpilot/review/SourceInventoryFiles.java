package com.jinloes.prpilot.review;

import com.jinloes.prpilot.model.SourceInventory;
import com.jinloes.prpilot.model.SourceInventory.Kind;
import com.jinloes.prpilot.model.SourceInventory.ReasonCode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Worker-only no-follow physical I/O. Native callers may reuse budgets and stream hashing only. */
public final class SourceInventoryFiles {
    private SourceInventoryFiles() {}

    public static final class Failure extends IOException {
        private final ReasonCode code;
        private final String path;

        public Failure(ReasonCode code, String path) {
            super(code + (path == null ? "" : ": " + path));
            this.code = code;
            this.path = path;
        }

        public ReasonCode code() {
            return code;
        }

        public String path() {
            return path;
        }
    }

    public static final class Budget {
        private final long deadline;
        private final int nodeLimit;
        private final long byteLimit;
        private int nodes;
        private int files;
        private long bytes;

        public Budget() {
            this(Duration.ofSeconds(30), SourceInventory.MAX_NODES, SourceInventory.MAX_BYTES);
        }

        Budget(Duration duration, int nodeLimit, long byteLimit) {
            this(System.nanoTime() + duration.toNanos(), nodeLimit, byteLimit);
        }

        private Budget(long deadline, int nodeLimit, long byteLimit) {
            this.deadline = deadline;
            this.nodeLimit = nodeLimit;
            this.byteLimit = byteLimit;
        }

        public Budget phase() throws Failure {
            check();
            return new Budget(
                    Math.min(deadline, System.nanoTime() + Duration.ofSeconds(30).toNanos()),
                    nodeLimit,
                    byteLimit);
        }

        public void check() throws Failure {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() - deadline >= 0) {
                throw new Failure(ReasonCode.LIMIT, null);
            }
        }

        public void node() throws Failure {
            check();
            if (++nodes > nodeLimit) {
                throw new Failure(ReasonCode.LIMIT, null);
            }
        }

        void file() throws Failure {
            check();
            if (++files > SourceInventory.MAX_FILES) {
                throw new Failure(ReasonCode.LIMIT, null);
            }
        }

        public void bytes(long count) throws Failure {
            check();
            bytes += count;
            if (bytes > byteLimit) {
                throw new Failure(ReasonCode.LIMIT, null);
            }
        }

        public long remainingMillis() throws Failure {
            check();
            return Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
        }
    }

    public record Identity(Object key, long size, String modified, String created, Kind kind) {}

    public record Leaf(String path, Kind kind, Identity identity) {}

    public record Hashed(String sha256, String gitObjectId, long size) {}

    /** Fingerprints only; setting contents never cross the worker boundary. */
    public record Setting(String path, boolean present, String sha256, String physicalIdentity) {}

    public static List<Setting> captureSettings(List<String> paths, Budget budget)
            throws IOException {
        return captureSettings(paths, budget, () -> {});
    }

    static List<Setting> captureSettings(
            List<String> paths, Budget budget, Runnable absenceObserved) throws IOException {
        validateSettingsPaths(paths);
        List<Setting> before = observeSettings(paths, budget, absenceObserved);
        List<Setting> after = observeSettings(paths, budget, absenceObserved);
        if (!before.equals(after)) {
            throw new Failure(ReasonCode.INVENTORY_CHANGED, null);
        }
        return before;
    }

    static void validateSettingsPaths(List<String> paths) {
        if (paths == null || paths.size() > SourceInventory.MAX_FILES) {
            throw new IllegalArgumentException("Bounded explicit settings paths required");
        }
        String previous = null;
        for (String path : paths) {
            SourceInventory.absolute(path);
            if (Path.of(path).getFileName() == null
                    || previous != null && SourceInventory.UTF8.compare(previous, path) >= 0) {
                throw new IllegalArgumentException("Sorted unique settings files required");
            }
            previous = path;
        }
    }

    private static List<Setting> observeSettings(
            List<String> paths, Budget budget, Runnable absenceObserved) throws IOException {
        List<Setting> result = new ArrayList<>();
        for (String value : paths) {
            budget.node();
            Path path = Path.of(value);
            try (Anchor anchor = new Anchor(path.getParent(), budget, true)) {
                List<String> parents =
                        anchor.identities.stream().map(i -> i.key().toString()).toList();
                if (anchor.absentName != null) {
                    result.add(
                            absentSetting(
                                    path,
                                    anchor,
                                    anchor.absentName,
                                    anchor.absentParentIdentity,
                                    parents,
                                    budget,
                                    absenceObserved));
                    continue;
                }
                Identity parentBefore = identity(self(anchor.last()));
                BasicFileAttributes before;
                try {
                    before = attributes(anchor.last(), path.getFileName());
                } catch (NoSuchFileException e) {
                    result.add(
                            absentSetting(
                                    path,
                                    anchor,
                                    path.getFileName(),
                                    parentBefore,
                                    parents,
                                    budget,
                                    absenceObserved));
                    continue;
                }
                if (!before.isRegularFile() || before.isSymbolicLink()) {
                    throw new Failure(ReasonCode.UNSAFE_PATH, value);
                }
                Hashed hashed = hash(path.getParent(), path.getFileName().toString(), budget, null);
                unchanged(identity(before), attributes(anchor.last(), path.getFileName()));
                anchor.verify();
                List<String> parts = new ArrayList<>(parents);
                addIdentity(parts, identity(before));
                result.add(new Setting(value, true, hashed.sha256(), fingerprint(parts)));
            }
        }
        return List.copyOf(result);
    }

    private static Setting absentSetting(
            Path path,
            Anchor anchor,
            Path missing,
            Identity parentBefore,
            List<String> parents,
            Budget budget,
            Runnable absenceObserved)
            throws IOException {
        absenceObserved.run();
        budget.check();
        anchor.verify();
        try {
            attributes(anchor.last(), missing);
        } catch (NoSuchFileException e) {
            unchanged(parentBefore, self(anchor.last()));
            anchor.verify();
            List<String> parts = new ArrayList<>(parents);
            parts.add(path.toString());
            parts.add(Integer.toString(anchor.names.size()));
            parts.add(path.subpath(anchor.names.size(), path.getNameCount()).toString());
            addIdentity(parts, parentBefore);
            return new Setting(path.toString(), false, null, fingerprint(parts));
        }
        throw new Failure(ReasonCode.INVENTORY_CHANGED, path.toString());
    }

    /** Includes the selected directory's identity, not unrelated ancestor timestamps. */
    static String directoryIdentity(Path path, Budget budget) throws IOException {
        try (Anchor anchor = new Anchor(path, budget)) {
            List<String> parts = new ArrayList<>();
            for (Identity id : anchor.identities) parts.add(id.key().toString());
            addIdentity(parts, identity(self(anchor.last())));
            anchor.verify();
            return fingerprint(parts);
        }
    }

    static String leafIdentity(Leaf leaf) {
        List<String> parts = new ArrayList<>();
        parts.add(leaf.path());
        addIdentity(parts, leaf.identity());
        return fingerprint(parts);
    }

    private static void addIdentity(List<String> parts, Identity identity) {
        parts.add(identity.key().toString());
        parts.add(Long.toString(identity.size()));
        parts.add(identity.modified());
        parts.add(identity.created());
        parts.add(identity.kind().name());
    }

    static String fingerprint(List<String> parts) {
        MessageDigest digest = SourceInventory.digest("SHA-256");
        for (String part : parts) {
            byte[] bytes = SourceInventory.utf8(part);
            digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Identity identity(BasicFileAttributes attributes) throws Failure {
        if (attributes.fileKey() == null) {
            throw new Failure(ReasonCode.UNSUPPORTED_API, null);
        }
        return new Identity(
                attributes.fileKey(),
                attributes.size(),
                attributes.lastModifiedTime().toString(),
                attributes.creationTime().toString(),
                attributes.isSymbolicLink()
                        ? Kind.SYMLINK
                        : attributes.isRegularFile() ? Kind.FILE : Kind.SPECIAL);
    }

    private static BasicFileAttributes attributes(SecureDirectoryStream<Path> directory, Path name)
            throws IOException {
        return directory
                .getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                .readAttributes();
    }

    private static BasicFileAttributes self(SecureDirectoryStream<Path> directory)
            throws IOException {
        return directory.getFileAttributeView(BasicFileAttributeView.class).readAttributes();
    }

    private static void unchanged(Identity before, BasicFileAttributes after) throws IOException {
        if (!before.equals(identity(after))) {
            throw new Failure(ReasonCode.INVENTORY_CHANGED, null);
        }
    }

    /** An anchor holds every directory component open, and verifies links back to each parent. */
    private static final class Anchor implements AutoCloseable {
        private final List<SecureDirectoryStream<Path>> handles = new ArrayList<>();
        private final List<Identity> identities = new ArrayList<>();
        private final List<Path> names = new ArrayList<>();
        private Path absentName;
        private Identity absentParentIdentity;

        Anchor(Path directory, Budget budget) throws IOException {
            this(directory, budget, false);
        }

        private Anchor(Path directory, Budget budget, boolean settingsOnly) throws IOException {
            SourceInventory.absolute(directory.toString());
            try {
                DirectoryStream<Path> stream = Files.newDirectoryStream(directory.getRoot());
                if (!(stream instanceof SecureDirectoryStream<Path> secure)) {
                    stream.close();
                    throw new Failure(ReasonCode.UNSUPPORTED_API, null);
                }
                handles.add(secure);
                identities.add(identity(self(secure)));
                for (Path name : directory) {
                    budget.check();
                    Identity parentBefore = settingsOnly ? identity(self(last())) : null;
                    BasicFileAttributes before;
                    try {
                        before = attributes(last(), name);
                    } catch (NoSuchFileException e) {
                        if (!settingsOnly) {
                            throw e;
                        }
                        absentName = name;
                        absentParentIdentity = parentBefore;
                        break;
                    }
                    if (!before.isDirectory() || before.isSymbolicLink()) {
                        throw new Failure(ReasonCode.UNSAFE_PATH, null);
                    }
                    SecureDirectoryStream<Path> child =
                            last().newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS);
                    handles.add(child);
                    names.add(name);
                    identities.add(identity(before));
                    unchanged(identity(before), self(child));
                }
                verify();
            } catch (IOException | RuntimeException e) {
                close();
                throw e;
            }
        }

        SecureDirectoryStream<Path> last() {
            return handles.get(handles.size() - 1);
        }

        void verify() throws IOException {
            for (int i = 0; i < handles.size(); i++) {
                // Ancestor directory timestamps can legitimately change due to unrelated siblings.
                // Only the selected directory itself must retain its full inventory identity.
                BasicFileAttributes now = self(handles.get(i));
                if (!Objects.equals(identities.get(i).key(), now.fileKey())) {
                    throw new Failure(ReasonCode.UNSAFE_PATH, null);
                }
                if (i > 0) {
                    BasicFileAttributes link = attributes(handles.get(i - 1), names.get(i - 1));
                    if (!link.isDirectory() || !Objects.equals(link.fileKey(), now.fileKey())) {
                        throw new Failure(ReasonCode.UNSAFE_PATH, null);
                    }
                }
            }
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            for (int i = handles.size() - 1; i >= 0; i--) {
                try {
                    handles.get(i).close();
                } catch (IOException e) {
                    failure = e;
                }
            }
            handles.clear();
            if (failure != null) {
                throw failure;
            }
        }
    }

    public static void requireDirectory(Path directory, Budget budget) throws IOException {
        try (Anchor anchor = new Anchor(directory, budget)) {
            anchor.verify();
        }
    }

    public static List<Leaf> scan(Path root, Budget budget) throws IOException {
        try (Anchor anchor = new Anchor(root, budget)) {
            List<Leaf> leaves = new ArrayList<>();
            walk(anchor.last(), "", leaves, budget);
            anchor.verify();
            leaves.sort((a, b) -> SourceInventory.UTF8.compare(a.path(), b.path()));
            for (int i = 1; i < leaves.size(); i++) {
                if (leaves.get(i - 1).path().equals(leaves.get(i).path())) {
                    throw new Failure(ReasonCode.INVENTORY_CHANGED, null);
                }
            }
            return List.copyOf(leaves);
        }
    }

    private static void walk(
            SecureDirectoryStream<Path> directory, String prefix, List<Leaf> leaves, Budget budget)
            throws IOException {
        Identity before = identity(self(directory));
        for (Path entry : directory) {
            budget.node();
            Path name = entry.getFileName();
            String relative = prefix + name;
            try {
                SourceInventory.path(relative);
            } catch (IllegalArgumentException e) {
                throw new Failure(ReasonCode.UNSAFE_PATH, null);
            }
            BasicFileAttributes attrs = attributes(directory, name);
            Identity initial = identity(attrs);
            if (prefix.isEmpty() && name.toString().equals(".git")) {
                if (attrs.isSymbolicLink() || !(attrs.isDirectory() || attrs.isRegularFile())) {
                    throw new Failure(ReasonCode.UNSAFE_PATH, ".git");
                }
                if (attrs.isRegularFile()) {
                    if (attrs.size() < 9 || attrs.size() > 8192) {
                        throw new Failure(ReasonCode.UNSAFE_PATH, ".git");
                    }
                    ByteBuffer marker = ByteBuffer.allocate((int) attrs.size() + 1);
                    try (SeekableByteChannel channel =
                            directory.newByteChannel(
                                    name,
                                    Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                        while (marker.hasRemaining() && channel.read(marker) >= 0) {
                            budget.check();
                        }
                    }
                    String text =
                            new String(
                                    marker.array(),
                                    0,
                                    marker.position(),
                                    java.nio.charset.StandardCharsets.UTF_8);
                    if (marker.position() != attrs.size()
                            || !text.startsWith("gitdir: ")
                            || text.substring(8).isBlank()
                            || text.indexOf('\0') >= 0
                            || text.indexOf('\ufffd') >= 0) {
                        throw new Failure(ReasonCode.UNSAFE_PATH, ".git");
                    }
                }
                unchanged(initial, attributes(directory, name));
                continue;
            }
            if (attrs.isDirectory()) {
                // Bound recursion independently of node count to avoid a stack overflow.
                if (relative.split("/").length > 128) {
                    throw new Failure(ReasonCode.LIMIT, relative);
                }
                try (SecureDirectoryStream<Path> child =
                        directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
                    unchanged(initial, self(child));
                    walk(child, relative + "/", leaves, budget);
                    unchanged(initial, self(child));
                }
            } else {
                leaves.add(new Leaf(relative, initial.kind(), initial));
            }
            unchanged(initial, attributes(directory, name));
        }
        unchanged(before, self(directory));
    }

    public static Hashed hash(Path root, String relative, Budget budget, String gitAlgorithm)
            throws IOException {
        return hash(root, relative, budget, gitAlgorithm, () -> {});
    }

    static Hashed hash(
            Path root, String relative, Budget budget, String gitAlgorithm, Runnable opened)
            throws IOException {
        SourceInventory.path(relative);
        budget.file();
        Path path = root.getFileSystem().getPath(relative);
        Path parent = path.getParent() == null ? root : root.resolve(path.getParent());
        try (Anchor anchor = new Anchor(parent, budget)) {
            Path name = path.getFileName();
            BasicFileAttributes attrs = attributes(anchor.last(), name);
            if (!attrs.isRegularFile() || attrs.isSymbolicLink()) {
                throw new Failure(ReasonCode.UNSAFE_PATH, relative);
            }
            Identity initial = identity(attrs);
            if (attrs.size() > SourceInventory.MAX_BYTES) {
                throw new Failure(ReasonCode.LIMIT, relative);
            }
            MessageDigest sha = SourceInventory.digest("SHA-256");
            MessageDigest git = gitAlgorithm == null ? null : SourceInventory.digest(gitAlgorithm);
            if (git != null) {
                git.update(SourceInventory.utf8("blob " + attrs.size() + "\0"));
            }
            long count = 0;
            try (SeekableByteChannel channel =
                    anchor.last()
                            .newByteChannel(
                                    name,
                                    Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                opened.run();
                ByteBuffer buffer = ByteBuffer.allocate(8192);
                while (true) {
                    budget.check();
                    int read = channel.read(buffer);
                    if (read < 0) {
                        break;
                    }
                    budget.bytes(read);
                    count += read;
                    sha.update(buffer.array(), 0, read);
                    if (git != null) {
                        git.update(buffer.array(), 0, read);
                    }
                    buffer.clear();
                }
                if (channel.size() != attrs.size() || count != attrs.size()) {
                    throw new Failure(ReasonCode.CONTENT_MISMATCH, relative);
                }
            }
            unchanged(initial, attributes(anchor.last(), name));
            anchor.verify();
            return new Hashed(
                    HexFormat.of().formatHex(sha.digest()),
                    git == null ? null : HexFormat.of().formatHex(git.digest()),
                    count);
        }
    }

    public static String hashStream(InputStream input, Budget budget) throws IOException {
        MessageDigest digest = SourceInventory.digest("SHA-256");
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            budget.bytes(read);
            digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
