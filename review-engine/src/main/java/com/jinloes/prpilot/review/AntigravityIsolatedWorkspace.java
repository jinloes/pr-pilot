package com.jinloes.prpilot.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ephemeral, zero-trust workspace wrapper for one {@code agy} invocation.
 *
 * <p>Antigravity CLI walks upward from its working directory to {@code .git} and automatically
 * loads repository customizations ({@code .agents/hooks.json}, {@code .agents/agents}, {@code
 * AGENTS.md}, {@code GEMINI.md}). Pointing {@code agy} directly at an untrusted PR checkout would
 * let a PR execute arbitrary shell hooks on tool calls.
 *
 * <p>Instead, each invocation runs inside an owner-only temporary directory that:
 *
 * <ol>
 *   <li>Creates an empty {@code .git/} marker so {@code agy} stops its upward customization walk
 *       immediately at the isolated root.
 *   <li>Installs PR Pilot's own restricted {@code .agents/agents/pr-pilot-reviewer.md} agent and an
 *       ephemeral {@code --settings} JSON file with {@code permissions.deny} rules blocking all
 *       writes, shell commands, URL fetches, and MCP tools (plus {@code read_file(*)} on tool-free
 *       passes).
 *   <li>Links only safe top-level entries from {@code projectDir} when {@code allowReadTools} is
 *       enabled, excluding all agent/instruction customization roots and any symlink escaping
 *       {@code projectDir}.
 * </ol>
 */
final class AntigravityIsolatedWorkspace implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(AntigravityService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    static final String REVIEWER_AGENT_NAME = "pr-pilot-reviewer";
    private static final long MAX_FALLBACK_COPY_BYTES = 8L * 1024 * 1024;
    private static final long JUNCTION_TIMEOUT_SECONDS = 5;

    static final Set<String> EXCLUDED_TOP_LEVEL_NAMES =
            Set.of(
                    ".git",
                    ".agents",
                    ".agent",
                    "_agents",
                    "_agent",
                    ".gemini",
                    "agents.md",
                    "gemini.md");

    static final List<String> BASE_DENY_RULES =
            List.of(
                    "write_file(*)",
                    "command(*)",
                    "unsandboxed(*)",
                    "read_url(*)",
                    "execute_url(*)",
                    "download_url(*)",
                    "mcp(*)");

    static final List<String> NO_TOOL_DENY_RULES =
            List.of(
                    "read_file(*)",
                    "write_file(*)",
                    "command(*)",
                    "unsandboxed(*)",
                    "read_url(*)",
                    "execute_url(*)",
                    "download_url(*)",
                    "mcp(*)");

    private final Path isolatedDir;
    private final Path settingsFile;
    private final List<Path> linkedEntries;

    private AntigravityIsolatedWorkspace(
            Path isolatedDir, Path settingsFile, List<Path> linkedEntries) {
        this.isolatedDir = isolatedDir;
        this.settingsFile = settingsFile;
        this.linkedEntries = List.copyOf(linkedEntries);
    }

    static AntigravityIsolatedWorkspace create(File projectDir, boolean allowReadTools)
            throws IOException {
        Path isolatedDir = createTempRoot();
        List<Path> linkedEntries = new ArrayList<>();
        boolean exposeProject =
                allowReadTools
                        && projectDir != null
                        && projectDir.exists()
                        && projectDir.isDirectory();
        try {
            Files.createDirectories(isolatedDir.resolve(".git"));
            Path agentsRoot = isolatedDir.resolve(".agents");
            Path agentsDir = agentsRoot.resolve("agents");
            Files.createDirectories(agentsDir);
            Files.writeString(
                    agentsDir.resolve(REVIEWER_AGENT_NAME + ".md"),
                    buildReviewerAgentMarkdown(exposeProject),
                    StandardCharsets.UTF_8);
            Path settingsFile = agentsRoot.resolve("pr-pilot-settings.json");
            Files.writeString(
                    settingsFile, buildSettingsJson(exposeProject), StandardCharsets.UTF_8);
            if (exposeProject) {
                exposeSafeTopLevelEntries(projectDir.toPath(), isolatedDir, linkedEntries);
            }
            return new AntigravityIsolatedWorkspace(isolatedDir, settingsFile, linkedEntries);
        } catch (IOException | RuntimeException failure) {
            closeQuietly(isolatedDir, linkedEntries);
            throw failure;
        }
    }

    Path isolatedDir() {
        return isolatedDir;
    }

    Path settingsFile() {
        return settingsFile;
    }

    List<Path> linkedEntries() {
        return linkedEntries;
    }

    static String buildReviewerAgentMarkdown(boolean allowReadTools) {
        String toolsLine =
                allowReadTools
                        ? "tools: [view_file, list_dir, find_by_name, grep_search]"
                        : "tools: []";
        return "---\n"
                + "name: "
                + REVIEWER_AGENT_NAME
                + "\n"
                + "description: Read-only PR Pilot code reviewer\n"
                + "mainAgent: true\n"
                + "subagent: false\n"
                + "commandExecutionPolicy: off\n"
                + "mcpServers: []\n"
                + "skills: []\n"
                + "plugins: []\n"
                + toolsLine
                + "\n"
                + "---\n"
                + "Follow the caller's review instructions strictly. Never modify files or execute commands.\n";
    }

    static String buildSettingsJson(boolean allowReadTools) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("toolPermission", "request-review");
        root.put("disableSlashCommands", true);
        root.put("allowNonWorkspaceAccess", false);
        ObjectNode permissions = root.putObject("permissions");
        permissions.putArray("allow");
        ArrayNode deny = permissions.putArray("deny");
        for (String rule : allowReadTools ? BASE_DENY_RULES : NO_TOOL_DENY_RULES) {
            deny.add(rule);
        }
        return JSON.writeValueAsString(root);
    }

    private static void exposeSafeTopLevelEntries(
            Path projectRoot, Path isolatedDir, List<Path> linkedEntries) throws IOException {
        Path realProjectRoot = projectRoot.toRealPath();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(projectRoot)) {
            for (Path entry : stream) {
                Path fileName = entry.getFileName();
                if (fileName == null) continue;
                String lowerName = fileName.toString().toLowerCase(Locale.ROOT);
                if (EXCLUDED_TOP_LEVEL_NAMES.contains(lowerName)) {
                    continue;
                }
                Path resolvedTarget;
                try {
                    resolvedTarget = entry.toRealPath();
                } catch (IOException brokenLink) {
                    continue;
                }
                if (!resolvedTarget.startsWith(realProjectRoot)) {
                    log.warn("Skipping top-level entry escaping project root: {}", fileName);
                    continue;
                }
                Path destination = isolatedDir.resolve(fileName);
                if (Files.isDirectory(resolvedTarget)) {
                    if (linkDirectory(destination, resolvedTarget)) {
                        linkedEntries.add(destination);
                    }
                } else if (Files.isRegularFile(resolvedTarget)) {
                    if (linkOrCopyFile(destination, resolvedTarget)) {
                        linkedEntries.add(destination);
                    }
                }
            }
        }
    }

    private static boolean linkDirectory(Path linkPath, Path targetDir) throws IOException {
        try {
            Files.createSymbolicLink(linkPath, targetDir);
            return true;
        } catch (UnsupportedOperationException | FileSystemException symlinkUnavailable) {
            if (!BinaryLocator.isWindows()) {
                throw symlinkUnavailable;
            }
            return createWindowsJunction(linkPath, targetDir);
        }
    }

    private static boolean createWindowsJunction(Path linkPath, Path targetDir) throws IOException {
        String comSpec = BinaryLocator.resolveComSpec(System.getenv());
        ProcessBuilder pb =
                new ProcessBuilder(
                        comSpec,
                        "/d",
                        "/c",
                        "mklink",
                        "/J",
                        linkPath.toAbsolutePath().toString(),
                        targetDir.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try {
            boolean finished = process.waitFor(JUNCTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Timed out creating directory junction for {}", linkPath.getFileName());
                return false;
            }
            return process.exitValue() == 0 && Files.exists(linkPath, LinkOption.NOFOLLOW_LINKS);
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted creating isolated workspace junction", interrupted);
        }
    }

    private static boolean linkOrCopyFile(Path linkPath, Path targetFile) throws IOException {
        try {
            Files.createLink(linkPath, targetFile);
            return true;
        } catch (UnsupportedOperationException | IOException linkFailed) {
            try {
                Files.createSymbolicLink(linkPath, targetFile);
                return true;
            } catch (UnsupportedOperationException | IOException symlinkFailed) {
                long size = Files.size(targetFile);
                if (size > MAX_FALLBACK_COPY_BYTES) {
                    return false;
                }
                Files.copy(targetFile, linkPath, StandardCopyOption.REPLACE_EXISTING);
                return true;
            }
        }
    }

    private static Path createTempRoot() throws IOException {
        try {
            return Files.createTempDirectory(
                    "pr-pilot-agy-",
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException windowsFs) {
            return Files.createTempDirectory("pr-pilot-agy-");
        }
    }

    @Override
    public void close() {
        closeQuietly(isolatedDir, linkedEntries);
    }

    private static void closeQuietly(Path isolatedDir, List<Path> linkedEntries) {
        List<Path> toUnlink = new ArrayList<>(linkedEntries);
        Collections.reverse(toUnlink);
        for (Path link : toUnlink) {
            try {
                Files.deleteIfExists(link);
            } catch (IOException e) {
                log.warn(
                        "Failed to unlink isolated workspace entry {}: {}",
                        link.getFileName(),
                        e.getClass().getSimpleName());
            }
        }
        deleteOwnedTreeNoFollow(isolatedDir);
    }

    /**
     * Deletes owned files/directories inside {@code path} without ever recursing into symlinks or
     * Windows NTFS junctions ({@code BasicFileAttributes#isOther()}).
     */
    private static void deleteOwnedTreeNoFollow(Path path) {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            BasicFileAttributes attrs =
                    Files.readAttributes(
                            path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isDirectory() && !attrs.isSymbolicLink() && !attrs.isOther()) {
                try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                    for (Path child : children) {
                        deleteOwnedTreeNoFollow(child);
                    }
                }
            }
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn(
                    "Failed to clean isolated Antigravity workspace path {}: {}",
                    path.getFileName(),
                    e.getClass().getSimpleName());
        }
    }
}
