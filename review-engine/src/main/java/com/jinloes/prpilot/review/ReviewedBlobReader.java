package com.jinloes.prpilot.review;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/** Reads committed text without consulting or executing anything in the working tree. */
public final class ReviewedBlobReader {
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern ENTRY = Pattern.compile("100(?:644|755) blob ([0-9a-f]{40})");
    private final BoundedProcessRunner runner = new BoundedProcessRunner();

    public String read(String gitRoot, String commit, String file, int line)
            throws IOException, InterruptedException, TimeoutException {
        if (gitRoot == null
                || gitRoot.isBlank()
                || commit == null
                || !SHA.matcher(commit).matches()
                || !validPath(file)
                || line < 1) {
            throw new IOException("Invalid reviewed-file location.");
        }
        Path root = Path.of(gitRoot).toRealPath();
        if (!root.equals(Path.of(git(root, "rev-parse", "--show-toplevel").strip()).toRealPath())) {
            throw new IOException(
                    "Open the matching pull request repository before viewing the reviewed file.");
        }
        String entry = git(root, "ls-tree", "-z", "--full-tree", commit + "^{commit}", "--", file);
        int tab = entry.indexOf('\t');
        if (tab < 0 || !entry.substring(tab + 1).equals(file + '\0')) {
            throw new IOException("The file is unavailable at the reviewed commit.");
        }
        var metadata = ENTRY.matcher(entry.substring(0, tab));
        if (!metadata.matches()) throw new IOException("The reviewed entry is not a regular file.");
        String oid = metadata.group(1);
        String content = git(root, "cat-file", "blob", oid);
        // Verify the decoded text against the object ID: the subprocess runner decodes malformed
        // UTF-8 with substitution, which must not become a silently altered preview.
        if (content.indexOf('\0') >= 0 || !oid.equals(blobId(content))) {
            throw new IOException("The reviewed file is not supported UTF-8 text.");
        }
        if (line > content.split("\\r\\n|\\n|\\r", -1).length || content.isEmpty()) {
            throw new IOException("The finding line is unavailable at the reviewed commit.");
        }
        return content;
    }

    private static boolean validPath(String file) {
        if (file == null
                || file.length() > 4096
                || file.indexOf('\\') >= 0
                || file.indexOf(':') >= 0
                || file.chars().anyMatch(Character::isISOControl)) {
            return false;
        }
        for (String segment : file.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return false;
        }
        return true;
    }

    private String git(Path root, String... args)
            throws IOException, InterruptedException, TimeoutException {
        var command =
                new ArrayList<>(List.of("git", "--no-replace-objects", "--literal-pathspecs"));
        command.addAll(List.of(args));
        var builder = new ProcessBuilder(command).directory(root.toFile());
        builder.environment().keySet().removeIf(key -> key.startsWith("GIT_"));
        builder.environment().put("GIT_NO_LAZY_FETCH", "1");
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        var result = runner.runOwnedTree(builder, 5, TimeUnit.SECONDS);
        if (result.outputTruncated())
            throw new IOException("The reviewed file exceeds the 1 MiB preview limit.");
        if (result.exitCode() != 0)
            throw new IOException("The reviewed Git object is unavailable.");
        return result.output();
    }

    private static String blobId(String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try {
            var digest = MessageDigest.getInstance("SHA-1");
            digest.update(("blob " + bytes.length + '\0').getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
