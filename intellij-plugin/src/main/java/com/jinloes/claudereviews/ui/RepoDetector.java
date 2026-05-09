package com.jinloes.claudereviews.ui;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.commons.io.FileUtils;

/** Detects the owner/repo for the current project by reading the local git config. */
class RepoDetector {

    private RepoDetector() {}

    /**
     * Walks up from {@code basePath} to find a {@code .git/config} file, then extracts the {@code
     * owner/repo} for the {@code origin} remote. Returns {@code null} if not found. No process is
     * spawned — reads the file directly. Walking up matches git's own behaviour for repos where the
     * IntelliJ project root is a subdirectory of the git root.
     */
    static String detectCurrentRepo(String basePath) {
        if (basePath == null) return null;
        File dir = new File(basePath);
        while (dir != null) {
            File gitConfig = new File(dir, ".git/config");
            if (gitConfig.isFile()) {
                try {
                    List<String> lines = FileUtils.readLines(gitConfig, StandardCharsets.UTF_8);
                    boolean inOriginSection = false;
                    for (String line : lines) {
                        String trimmed = line.trim();
                        if (trimmed.equals("[remote \"origin\"]")) {
                            inOriginSection = true;
                        } else if (inOriginSection && trimmed.startsWith("[")) {
                            break;
                        } else if (inOriginSection && trimmed.startsWith("url")) {
                            int eq = trimmed.indexOf('=');
                            if (eq >= 0) return parseOwnerRepo(trimmed.substring(eq + 1).trim());
                        }
                    }
                } catch (IOException ignored) {
                }
                return null;
            }
            dir = dir.getParentFile();
        }
        return null;
    }

    /**
     * Parses an owner/repo pair from a git remote URL. Handles scp-style SSH ({@code
     * git@github.com:owner/repo.git}), SSH URIs ({@code ssh://git@host:port/owner/repo.git}), and
     * HTTPS ({@code https://github.com/owner/repo.git}) formats.
     */
    static String parseOwnerRepo(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) return null;
        String url = remoteUrl.strip();
        if (url.endsWith(".git")) url = url.substring(0, url.length() - 4);
        // scp-style SSH: git@github.com:owner/repo — NOT ssh:// URIs (those have a port after ':')
        if (url.contains("@")
                && url.contains(":")
                && !url.startsWith("http")
                && !url.startsWith("ssh://")) {
            return ownerRepoFromPath(url.substring(url.lastIndexOf(':') + 1));
        }
        // HTTPS/HTTP/SSH-URI: https://github.com/owner/repo or ssh://git@host:port/owner/repo
        try {
            String path = new URI(url).getPath();
            if (path != null && path.startsWith("/")) path = path.substring(1);
            return ownerRepoFromPath(path);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String ownerRepoFromPath(String path) {
        if (path == null || path.isBlank()) return null;
        String[] parts = path.split("/");
        return parts.length >= 2 && !parts[0].isBlank() && !parts[1].isBlank()
                ? parts[0] + "/" + parts[1]
                : null;
    }
}
