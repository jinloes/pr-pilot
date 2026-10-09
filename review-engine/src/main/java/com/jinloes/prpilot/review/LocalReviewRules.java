package com.jinloes.prpilot.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads a user-configured local folder of review rules, such as a team's copy of central review
 * rules that the reviewer cannot reach over the network.
 *
 * <p>The folder is chosen by the reviewer in their own settings, not by the pull request author, so
 * it is trusted like the other preference data. Reads are still bounded: only regular {@code .md},
 * {@code .yaml} and {@code .yml} files, no symbolic links, a limited depth, file count and file
 * size, sorted by path so selection is deterministic. Any failure degrades to no rules.
 *
 * <p>A YAML mapping with a valid {@code name} and non-blank {@code description}, {@code trigger}
 * and {@code prompt} is a structured rule, applied only when its trigger matches the pull request.
 * Any other readable file is an unstructured rule named by its path, applied to every review.
 */
final class LocalReviewRules {
    private static final Logger log = LoggerFactory.getLogger(LocalReviewRules.class);

    static final int MAX_DEPTH = 4;
    static final int MAX_FILES = 50;
    static final int MAX_FILE_BYTES = 32 * 1024;

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /**
     * One review rule. {@code trigger} is null for an unstructured rule, which is always applied.
     * {@code source} is the file's path relative to the rules folder.
     */
    record Rule(String name, String source, String description, String trigger, String prompt) {
        boolean structured() {
            return trigger != null;
        }
    }

    private LocalReviewRules() {}

    /**
     * The rules under {@code directory} in path order, or an empty list when the directory is
     * unset, missing, or holds no readable rule file.
     */
    static List<Rule> load(String directory) {
        try {
            return loadRules(directory);
        } catch (RuntimeException failure) {
            log.warn("Could not load review rules from {}", directory, failure);
            return List.of();
        }
    }

    private static List<Rule> loadRules(String directory) {
        if (directory == null || directory.isBlank()) return List.of();
        Path root;
        try {
            root = Path.of(directory.strip());
        } catch (RuntimeException invalid) {
            log.warn("Review rules directory {} is not a valid path", directory);
            return List.of();
        }
        if (!root.isAbsolute() || !Files.isDirectory(root)) {
            log.warn("Review rules directory {} is not an absolute directory; skipping", root);
            return List.of();
        }
        try {
            // The configured folder itself may be a link; nothing inside it may be.
            root = root.toRealPath();
        } catch (IOException unresolved) {
            log.warn("Could not resolve review rules directory {}", root, unresolved);
            return List.of();
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root, MAX_DEPTH)) {
            files = walk.filter(LocalReviewRules::isRuleFile).sorted().limit(MAX_FILES).toList();
        } catch (IOException | RuntimeException failure) {
            log.warn("Could not list review rules directory {}", root, failure);
            return List.of();
        }
        List<Rule> rules = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Path file : files) {
            String content = readUtf8(file);
            if (content == null || content.isBlank()) continue;
            String source = relative(root, file);
            JsonNode yaml = isYaml(file) ? parseYaml(source, content) : null;
            if (yaml != null && isDisabled(yaml)) {
                log.info("Skipping disabled review rule {}", source);
                continue;
            }
            Rule rule = yaml == null ? null : structured(source, yaml);
            if (rule == null) {
                rules.add(new Rule(source, source, null, null, content.strip()));
            } else if (!names.add(rule.name())) {
                log.warn("Skipping review rule {}: duplicate name {}", source, rule.name());
            } else {
                rules.add(rule);
            }
        }
        return List.copyOf(rules);
    }

    private static JsonNode parseYaml(String source, String content) {
        try {
            JsonNode node = YAML.readTree(content);
            return node != null && node.isObject() ? node : null;
        } catch (IOException | RuntimeException unparseable) {
            log.info("Review rule {} is not a YAML mapping; using it as plain text", source);
            return null;
        }
    }

    private static boolean isDisabled(JsonNode yaml) {
        JsonNode enabled = yaml.get("enabled");
        if (enabled == null) return false;
        return (enabled.isBoolean() && !enabled.booleanValue())
                || (enabled.isTextual() && "false".equalsIgnoreCase(enabled.textValue().strip()));
    }

    private static Rule structured(String source, JsonNode yaml) {
        String name = text(yaml, "name");
        String description = text(yaml, "description");
        String trigger = text(yaml, "trigger");
        String prompt = text(yaml, "prompt");
        if (name == null || !NAME.matcher(name).matches()) return null;
        if (description == null || trigger == null || prompt == null) return null;
        return new Rule(name, source, description, trigger, prompt);
    }

    /** The stripped scalar value of {@code field}, or null when it is absent or blank. */
    private static String text(JsonNode yaml, String field) {
        JsonNode value = yaml.get(field);
        if (value == null || !value.isValueNode() || value.isNull()) return null;
        String text = value.asText().strip();
        return text.isEmpty() ? null : text;
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
        return name.endsWith(".md") || isYaml(path);
    }

    private static boolean isYaml(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yaml") || name.endsWith(".yml");
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
