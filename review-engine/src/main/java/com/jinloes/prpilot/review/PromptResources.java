package com.jinloes.prpilot.review;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Loads packaged prompt Markdown resources under {@code /review-prompts/} and derives a
 * deterministic content hash over all prompt components for {@link ReviewPrompts#PROMPT_VERSION}.
 */
final class PromptResources {
    static final int MAX_RESOURCE_BYTES = 65_536;
    private static final String ROOT = "/review-prompts/";

    @FunctionalInterface
    interface Opener {
        InputStream open(String path) throws IOException;
    }

    private PromptResources() {}

    static String load(String relativePath) {
        return load(path -> PromptResources.class.getResourceAsStream(ROOT + path), relativePath);
    }

    static String load(Opener opener, String relativePath) {
        try (InputStream stream = opener.open(relativePath)) {
            if (stream == null) {
                throw new IllegalStateException("Missing bundled prompt resource: " + relativePath);
            }
            byte[] bytes = stream.readNBytes(MAX_RESOURCE_BYTES + 1);
            if (bytes.length > MAX_RESOURCE_BYTES) {
                throw new IllegalStateException(
                        "Bundled prompt resource exceeds "
                                + MAX_RESOURCE_BYTES
                                + " bytes: "
                                + relativePath);
            }
            // Normalize CRLF so Windows checkouts produce identical prompts and digests.
            return new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to read bundled prompt resource: " + relativePath, exception);
        }
    }

    /**
     * Length-frames and hashes every prompt component in order so any edit — including moving text
     * across adjacent components — changes the returned version token.
     */
    static String computeVersion(String prefix, List<String> components) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String component : components) {
                byte[] bytes =
                        (component == null ? "" : component).getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) ':');
                digest.update(bytes);
                digest.update((byte) '\n');
            }
            String hex = HexFormat.of().formatHex(digest.digest()).substring(0, 12);
            return prefix + "-" + hex;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
