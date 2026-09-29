package com.jinloes.prpilot.review;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/**
 * Resolves and reads a repository's review-guidance docs from a working directory (the PR-branch
 * worktree or the open project) so the model can weight findings against the project's own
 * conventions.
 *
 * <p>The set of files is <b>configurable</b> rather than hardcoded: each entry is either a literal
 * relative path (e.g. {@code AGENTS.md}, {@code .review/ai-agent/coding-pattern.md}) or a glob
 * (e.g. {@code **}{@code /style.md}, {@code .review/**}{@code /*.md}). This lets teams surface
 * repo-specific guidance without a code change. Shared by both hosts: IntelliJ calls it directly,
 * while VS Code reaches it through the sidecar.
 */
public final class RepoGuidelinesReader {

    /** Standard guidance files scanned for every review. */
    public static final List<String> DEFAULT_GUIDANCE_GLOBS =
            List.of(
                    "**/AGENTS.md",
                    "**/CLAUDE.md",
                    ".claude/rules/**/*.md",
                    ".github/copilot-instructions.md",
                    ".github/instructions/**/*.instructions.md",
                    "CONTRIBUTING.md",
                    ".github/CONTRIBUTING.md",
                    "docs/CONTRIBUTING.md",
                    ".github/pull_request_template.md");

    /** Cap on total guidance bytes fed to the prompt so a large doc can't blow up the context. */
    static final int MAX_GUIDELINES_BYTES = 6000;

    private static final int MAX_FILES_SCANNED = 5000;
    private static final int MAX_DEPTH = 8;
    private static final String TRUNCATION_MARKER = "\n...(truncated)";
    private static final Set<String> SKIP_DIRS =
            Set.of(
                    ".git",
                    "node_modules",
                    "build",
                    "dist",
                    "target",
                    "out",
                    ".gradle",
                    ".idea",
                    ".venv",
                    "venv");

    private RepoGuidelinesReader() {}

    /**
     * Reads the guidance files matching {@code globs} under {@code dir}, concatenated and capped at
     * {@link #MAX_GUIDELINES_BYTES}. Returns an empty string when {@code dir} is null/missing or
     * nothing matches. Configured {@code globs} are evaluated first, then augmented by {@link
     * #DEFAULT_GUIDANCE_GLOBS}; matching files are de-duplicated.
     */
    public static String read(File dir, List<String> globs) {
        if (dir == null || !dir.isDirectory()) {
            return "";
        }
        Path root = realDirectory(dir);
        if (root == null) {
            return "";
        }
        LinkedHashSet<String> patterns = new LinkedHashSet<>();
        if (globs != null) {
            patterns.addAll(globs);
        }
        patterns.addAll(DEFAULT_GUIDANCE_GLOBS);
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (String rel : resolvePaths(root.toFile(), new ArrayList<>(patterns))) {
            if (total >= MAX_GUIDELINES_BYTES) {
                break;
            }
            Path file = root.resolve(rel).normalize();
            if (!isContainedRegularFile(root, file)) {
                continue;
            }
            try {
                String content = Files.readString(file).trim();
                if (content.isEmpty()) {
                    continue;
                }
                String separator = sb.length() == 0 ? "" : "\n\n";
                String header = "## " + rel + "\n";
                int remaining = MAX_GUIDELINES_BYTES - total;
                int framingBytes = utf8Length(separator) + utf8Length(header);
                if (framingBytes >= remaining) {
                    break;
                }
                int contentLimit = remaining - framingBytes;
                int contentBytes = utf8Length(content);
                if (contentBytes > contentLimit) {
                    content = truncateUtf8(content, contentLimit);
                    contentBytes = utf8Length(content);
                }
                if (content.isEmpty()) {
                    break;
                }
                sb.append(separator).append(header).append(content);
                total += framingBytes + contentBytes;
            } catch (IOException e) {
                // unreadable — skip
            }
        }
        return sb.toString();
    }

    /**
     * Resolves {@code patterns} to a de-duplicated, priority-ordered list of relative paths under
     * {@code dir}. Literal paths are checked directly; glob patterns are matched against a bounded
     * walk of the directory tree (matches sorted for determinism).
     */
    static List<String> resolvePaths(File dir, List<String> patterns) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        Path root = realDirectory(dir);
        if (root == null) {
            return List.of();
        }
        List<String> allFiles = null;
        for (String raw : patterns) {
            String pattern = StringUtils.strip(raw);
            if (StringUtils.isBlank(pattern)) {
                continue;
            }
            pattern = pattern.replace('\\', '/');
            if (isGlob(pattern)) {
                if (allFiles == null) {
                    allFiles = collectRelativeFiles(root);
                }
                Pattern re = Pattern.compile(globToRegex(pattern));
                allFiles.stream()
                        .filter(f -> re.matcher(f).matches())
                        .sorted()
                        .forEach(ordered::add);
            } else {
                Path requested = Path.of(pattern);
                if (requested.isAbsolute()) {
                    continue;
                }
                Path candidate = root.resolve(requested).normalize();
                if (isContainedRegularFile(root, candidate)) {
                    ordered.add(
                            root.relativize(candidate).toString().replace(File.separatorChar, '/'));
                }
            }
        }
        return new ArrayList<>(ordered);
    }

    static boolean isGlob(String pattern) {
        return StringUtils.containsAny(pattern, '*', '?', '[', '{');
    }

    /**
     * Bounded breadth-first walk returning '/'-joined relative paths, skipping heavy directories.
     */
    private static List<String> collectRelativeFiles(Path root) {
        List<String> files = new ArrayList<>();
        Deque<Entry> queue = new ArrayDeque<>();
        queue.add(new Entry(root, "", 0));
        while (!queue.isEmpty() && files.size() < MAX_FILES_SCANNED) {
            Entry entry = queue.poll();
            List<Path> children;
            try (var paths = Files.list(entry.dir)) {
                children = paths.toList();
            } catch (IOException e) {
                continue;
            }
            for (Path child : children) {
                String rel =
                        entry.prefix.isEmpty()
                                ? child.getFileName().toString()
                                : entry.prefix + "/" + child.getFileName();
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    if (entry.depth < MAX_DEPTH
                            && !SKIP_DIRS.contains(child.getFileName().toString())) {
                        queue.add(new Entry(child, rel, entry.depth + 1));
                    }
                } else if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                    files.add(rel);
                    if (files.size() >= MAX_FILES_SCANNED) {
                        break;
                    }
                }
            }
        }
        return files;
    }

    private static Path realDirectory(File dir) {
        if (dir == null) {
            return null;
        }
        try {
            Path root = dir.toPath().toRealPath();
            return Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) ? root : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isContainedRegularFile(Path root, Path candidate) {
        if (!candidate.startsWith(root)) {
            return false;
        }
        Path current = root;
        for (Path segment : root.relativize(candidate)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                return false;
            }
        }
        if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            return candidate.toRealPath().startsWith(root);
        } catch (IOException e) {
            return false;
        }
    }

    static String truncateUtf8(String content, int maxBytes) {
        int markerBytes = utf8Length(TRUNCATION_MARKER);
        if (maxBytes <= markerBytes) {
            return "";
        }
        int contentLimit = maxBytes - markerBytes;
        StringBuilder truncated = new StringBuilder();
        int bytes = 0;
        for (int offset = 0; offset < content.length(); ) {
            int codePoint = content.codePointAt(offset);
            String value = new String(Character.toChars(codePoint));
            int valueBytes = utf8Length(value);
            if (bytes + valueBytes > contentLimit) {
                break;
            }
            truncated.append(value);
            bytes += valueBytes;
            offset += Character.charCount(codePoint);
        }
        return truncated.append(TRUNCATION_MARKER).toString();
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Translates a minimal glob ({@code **}, {@code *}, {@code ?}) into a regex matched against a
     * '/'-joined relative path. {@code **}{@code /} matches zero or more leading segments so {@code
     * **}{@code /style.md} also matches {@code style.md} at the root.
     */
    static String globToRegex(String glob) {
        StringBuilder re = new StringBuilder("^");
        int i = 0;
        int n = glob.length();
        while (i < n) {
            char c = glob.charAt(i);
            if (c == '*') {
                boolean doubleStar = i + 1 < n && glob.charAt(i + 1) == '*';
                if (doubleStar) {
                    i += 2;
                    if (i < n && glob.charAt(i) == '/') {
                        i++;
                        re.append("(?:.*/)?");
                    } else {
                        re.append(".*");
                    }
                    continue;
                }
                re.append("[^/]*");
            } else if (c == '?') {
                re.append("[^/]");
            } else if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                re.append('\\').append(c);
            } else {
                re.append(c);
            }
            i++;
        }
        return re.append('$').toString();
    }

    private record Entry(Path dir, String prefix, int depth) {}
}
