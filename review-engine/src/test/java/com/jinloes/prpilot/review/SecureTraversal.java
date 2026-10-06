package com.jinloes.prpilot.review;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;

/** Whether this JVM supplies the no-follow traversal the source-inventory worker requires. */
public final class SecureTraversal {
    private static final boolean SUPPORTED = probe();

    private SecureTraversal() {}

    /** Condition for {@link RequiresSecureTraversal}; the worker runs on this JVM's java.home. */
    public static boolean supported() {
        return SUPPORTED;
    }

    private static boolean probe() {
        Path root = Path.of(System.getProperty("java.io.tmpdir")).getRoot();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            return stream instanceof SecureDirectoryStream<Path>;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
