package com.jinloes.prpilot.review;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads a user-configured local folder of extra review rules, such as a team's copy of central
 * review rules that the reviewer cannot reach over the network.
 *
 * <p>The folder is chosen by the reviewer in their own settings, not by the pull request author, so
 * it is trusted like the other preference data. Reads are still bounded: only regular {@code .md},
 * {@code .yaml} and {@code .yml} files, no symbolic links, a limited depth, file count and total
 * size, sorted by path so the prompt is deterministic. Any failure degrades to no rules.
 */
final class LocalReviewRules {
    private static final Logger log = LoggerFactory.getLogger(LocalReviewRules.class);

    static final int MAX_DEPTH = 4;
    static final int MAX_FILES = 50;
    static final int MAX_FILE_BYTES = 16 * 1024;
    static final int MAX_TOTAL_BYTES = 32 * 1024;

    /** Heading prefix for each rule file, so a finding can name its source. */
    static final String SOURCE_PREFIX = "local-rules/";

    private LocalReviewRules() {}

    /**
     * The rule files under {@code directory} as {@code ## local-rules/<relative path>} sections, or
     * an empty string when the directory is unset, missing, or holds no readable rule file.
     */
    static String read(String directory) {
        if (directory == null || directory.isBlank()) return "";
        Path root;
        try {
            root = Path.of(directory.strip());
        } catch (RuntimeException invalid) {
            log.warn("Review rules directory {} is not a valid path", directory);
            return "";
        }
        if (!root.isAbsolute() || !Files.isDirectory(root)) {
            log.warn("Review rules directory {} is not an absolute directory; skipping", root);
            return "";
        }
        try {
            // The configured folder itself may be a link; nothing inside it may be.
            root = root.toRealPath();
        } catch (IOException unresolved) {
            log.warn("Could not resolve review rules directory {}", root, unresolved);
            return "";
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root, MAX_DEPTH)) {
            files = walk.filter(LocalReviewRules::isRuleFile).sorted().limit(MAX_FILES).toList();
        } catch (IOException | RuntimeException failure) {
            log.warn("Could not list review rules directory {}", root, failure);
            return "";
        }
        StringBuilder rules = new StringBuilder();
        int total = 0;
        for (Path file : files) {
            String content = readUtf8(file);
            if (content == null || content.isBlank()) continue;
            String section = "## " + SOURCE_PREFIX + relative(root, file) + "\n" + content.strip();
            int bytes = section.getBytes(StandardCharsets.UTF_8).length;
            if (total + bytes > MAX_TOTAL_BYTES) {
                log.info("Review rules exceed {} bytes; skipping {}", MAX_TOTAL_BYTES, file);
                continue;
            }
            if (rules.length() > 0) rules.append("\n\n");
            rules.append(section);
            total += bytes;
        }
        return rules.toString();
    }

    /** {@code rules} appended to {@code guidelines} as further guidance sections. */
    static String appendTo(String guidelines, String rules) {
        if (rules == null || rules.isBlank()) return guidelines;
        if (guidelines == null || guidelines.isBlank()) return rules;
        return guidelines + "\n\n" + rules;
    }

    private static boolean isRuleFile(Path path) {
        BasicFileAttributes attributes;
        try {
            attributes =
                    Files.readAttributes(
                            path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException unreadable) {
            return false;
        }
        if (!attributes.isRegularFile() || attributes.size() > MAX_FILE_BYTES) return false;
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".md") || name.endsWith(".yaml") || name.endsWith(".yml");
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static String readUtf8(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException binary) {
            log.info("Skipping non-UTF-8 review rule file {}", file);
            return null;
        } catch (IOException failure) {
            log.warn("Could not read review rule file {}", file, failure);
            return null;
        }
    }
}
