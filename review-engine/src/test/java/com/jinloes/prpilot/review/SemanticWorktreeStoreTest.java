package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SemanticWorktreeStoreTest {
    @TempDir Path temporary;
    private static final String HEAD = "a".repeat(40);

    static class Git extends GitWorktreeService {
        boolean registered = true;
        boolean removable;
        int removals;

        @Override
        boolean isRegisteredHead(File repo, File tree, String head) {
            return registered;
        }

        @Override
        boolean removeManagedWorktree(File repo, File tree) {
            removals++;
            return removable;
        }
    }

    private SemanticWorktreeStore store(Git git) throws IOException {
        return new SemanticWorktreeStore(temporary.toRealPath().resolve("state"), git);
    }

    private SemanticWorktreeStore.Retained register(SemanticWorktreeStore store)
            throws IOException {
        return store.register(
                Files.createDirectory(temporary.resolve("repo")).toRealPath(),
                Files.createDirectory(temporary.resolve("tree")).toRealPath(),
                HEAD);
    }

    @Test
    void survivesRestartAndProtectsOrdinaryRemoval() throws Exception {
        Git git = new Git();
        var original = store(git);
        var retained = register(original);
        var restarted = store(git);
        assertThat(restarted.list()).containsExactly(retained);
        AtomicBoolean removed = new AtomicBoolean();
        assertThat(
                        restarted.ordinaryRemoval(
                                Path.of(retained.worktree()),
                                () -> {
                                    removed.set(true);
                                    return true;
                                }))
                .isFalse();
        assertThat(removed).isFalse();
        Path alias =
                Files.createSymbolicLink(
                        temporary.resolve("ordinary-alias"), Path.of(retained.worktree()));
        assertThat(restarted.ordinaryRemoval(alias, () -> true)).isFalse();
        assertThat(restarted.ordinaryRemoval(temporary.resolve("ordinary"), () -> true)).isTrue();
        assertThat(
                        Files.getPosixFilePermissions(
                                temporary.resolve("state/semantic-worktrees.json")))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void cleanupRequiresConfirmationLeaseIdentityAndSuccessfulNonForceRemoval() throws Exception {
        Git git = new Git();
        var store = store(git);
        var retained = register(store);
        assertThatThrownBy(() -> store.cleanup(retained.id(), false))
                .isInstanceOf(IOException.class);
        try (var lease = store.acquire(retained.id())) {
            assertThat(lease.retained()).isEqualTo(retained);
            assertThatThrownBy(() -> store.cleanup(retained.id(), true))
                    .isInstanceOf(IOException.class);
            assertThatThrownBy(() -> store(git).acquire(retained.id()))
                    .isInstanceOf(IOException.class);
        }
        git.registered = false;
        assertThatThrownBy(() -> store.cleanup(retained.id(), true))
                .isInstanceOf(IOException.class);
        assertThat(git.removals).isZero();
        git.registered = true;
        assertThat(store.cleanup(retained.id(), true)).isFalse();
        assertThat(store.list()).containsExactly(retained);
        git.removable = true;
        assertThat(store.cleanup(retained.id(), true)).isTrue();
        assertThat(store.list()).isEmpty();
    }

    @Test
    void corruptRegistryAndSymlinkNeverPermitRemoval() throws Exception {
        var store = store(new Git());
        var retained = register(store);
        Path registry = temporary.resolve("state/semantic-worktrees.json");
        Files.writeString(registry, "{corrupt");
        assertThatThrownBy(
                        () ->
                                store.ordinaryRemoval(
                                        Path.of(retained.worktree()),
                                        () -> {
                                            throw new AssertionError("Removal must not run");
                                        }))
                .isInstanceOf(IOException.class);
        Files.delete(registry);
        Files.createSymbolicLink(registry, temporary.resolve("outside"));
        assertThatThrownBy(store::list).isInstanceOf(IOException.class);
    }

    @Test
    void rejectsSymlinkRegistrationAndDuplicateOwnership() throws Exception {
        var store = store(new Git());
        var retained = register(store);
        assertThatThrownBy(
                        () ->
                                store.register(
                                        Path.of(retained.repository()),
                                        Path.of(retained.worktree()),
                                        HEAD))
                .isInstanceOf(IOException.class);
        Path link =
                Files.createSymbolicLink(temporary.resolve("alias"), Path.of(retained.worktree()));
        assertThatThrownBy(() -> store.register(Path.of(retained.repository()), link, HEAD))
                .isInstanceOf(IOException.class);
    }

    @Test
    void leasesAreOperatingSystemLocksAndCrashDoesNotDiscardOwnership() throws Exception {
        var store = store(new Git());
        var retained = register(store);
        Path lock = temporary.resolve("state/semantic-leases/" + retained.id() + ".lock");
        try (var lease = store.acquire(retained.id())) {
            Process child = helper(lock, "probe");
            assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(new String(child.getInputStream().readAllBytes())).isEqualTo("BUSY\n");
            assertThat(child.exitValue()).isZero();
        }
        Process child = helper(lock, "hold");
        try {
            // Bounded wait, without blocking indefinitely on a child stdout read.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (child.getInputStream().available() == 0
                    && child.isAlive()
                    && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(child.getInputStream().available()).isPositive();
            assertThatThrownBy(() -> store.acquire(retained.id())).isInstanceOf(IOException.class);
        } finally {
            child.destroyForcibly();
            assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
        }
        try (var lease = store(new Git()).acquire(retained.id())) {
            assertThat(lease.retained()).isEqualTo(retained);
            assertThat(store.list()).containsExactly(retained);
        }
    }

    private Process helper(Path lock, String mode) throws Exception {
        return new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        Path.of(
                                        LockChild.class
                                                .getProtectionDomain()
                                                .getCodeSource()
                                                .getLocation()
                                                .toURI())
                                .toString(),
                        LockChild.class.getName(),
                        lock.toString(),
                        mode)
                .start();
    }

    public static class LockChild {
        public static void main(String[] args) throws Exception {
            try (var channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.WRITE);
                    var lock = channel.tryLock()) {
                System.out.println(lock == null ? "BUSY" : "HELD");
                System.out.flush();
                if (lock != null && args[1].equals("hold")) Thread.sleep(30000);
            }
        }
    }
}
