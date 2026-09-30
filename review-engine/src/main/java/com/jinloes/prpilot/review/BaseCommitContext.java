package com.jinloes.prpilot.review;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads trusted review context from a pull request's <b>base</b> commit: repository guidance
 * documents, the recent history of each changed file, and textual call sites of the declarations
 * the diff changes.
 *
 * <p>Guidance at the PR head is author-controlled, so it is never read from the worktree or the
 * head tree. Every read goes through read-only git plumbing against the base commit's objects, with
 * per-command and overall time bounds. Any failure degrades to empty context; only cancellation
 * propagates.
 */
final class BaseCommitContext {
    private static final Logger log = LoggerFactory.getLogger(BaseCommitContext.class);

    static final int MAX_GUIDELINES_BYTES = 24_000;
    static final int MAX_HISTORY_BYTES = 4_000;
    static final int MAX_HISTORY_FILES = 20;
    static final int MAX_BLOB_BYTES = 64 * 1024;
    static final int MAX_CALL_SITES_BYTES = 6_000;
    static final int MAX_CALL_SITE_FILES = 10;
    static final int MAX_CALL_SITES_PER_SYMBOL = 8;
    static final int MAX_LISTED_REFERENCES = 40;
    static final int MAX_CALL_SITE_LINE_CHARS = 160;
    private static final long CALL_SITES_TIMEOUT_SECONDS = 30;
    private static final int HISTORY_COMMITS_PER_FILE = 3;
    private static final long COMMAND_TIMEOUT_SECONDS = 15;
    private static final long FETCH_TIMEOUT_SECONDS = 60;
    private static final long OVERALL_TIMEOUT_SECONDS = 90;
    private static final String REVIEW_GUIDELINES_PATH = ".linkedin/ai-agent/review_guidelines.md";
    private static final Pattern COMMIT_SHA = Pattern.compile("(?i)[0-9a-f]{40}|[0-9a-f]{64}");
    private static final Pattern BLOB_SHA = Pattern.compile("(?i)[0-9a-f]{40}|[0-9a-f]{64}");
    private static final Set<String> SCOPED_NAMES = Set.of("AGENTS.md", "CLAUDE.md");
    private static final Set<String> TRAILING_PATHS =
            Set.of(
                    "CONTRIBUTING.md",
                    ".github/CONTRIBUTING.md",
                    "docs/CONTRIBUTING.md",
                    ".github/pull_request_template.md");

    /** Resolved context; any part is empty when unavailable. */
    record Result(String guidelines, String fileHistory, String callSites) {
        static final Result EMPTY = new Result("", "", "");
    }

    /** Checked before every git command so a cancelled review stops promptly. */
    @FunctionalInterface
    interface CancellationCheck {
        void check() throws InterruptedException;
    }

    record Blob(String path, String sha, long size) {}

    private record GitOutput(int exitCode, String output) {}

    private final BoundedProcessRunner processRunner;
    private final long overallTimeoutNanos;

    BaseCommitContext() {
        this(new BoundedProcessRunner());
    }

    BaseCommitContext(BoundedProcessRunner processRunner) {
        this(processRunner, TimeUnit.SECONDS.toNanos(OVERALL_TIMEOUT_SECONDS));
    }

    BaseCommitContext(BoundedProcessRunner processRunner, long overallTimeoutNanos) {
        this.processRunner = processRunner;
        this.overallTimeoutNanos = overallTimeoutNanos;
    }

    /**
     * Resolves guidance and file history for {@code manifest}'s changed files from {@code baseSha}.
     * Returns {@link Result#EMPTY} when the directory is not a git work tree, the SHA is malformed,
     * or the commit cannot be found even after fetching it.
     *
     * @throws InterruptedException when {@code cancellation} reports the review was cancelled
     */
    Result resolve(
            File repoDir,
            String baseSha,
            InspectionManifest manifest,
            CancellationCheck cancellation)
            throws InterruptedException {
        if (repoDir == null || baseSha == null || !COMMIT_SHA.matcher(baseSha).matches()) {
            log.warn("Invalid base commit or directory; reviewing without base-commit guidance");
            return Result.EMPTY;
        }
        Deadline deadline = new Deadline(System.nanoTime() + overallTimeoutNanos, cancellation);
        List<String> changedPaths =
                manifest == null
                        ? List.of()
                        : manifest.files().stream()
                                .map(InspectionManifest.FileTarget::path)
                                .filter(path -> path != null && !path.isBlank())
                                .distinct()
                                .toList();
        try {
            GitOutput workTree = git(repoDir, deadline, "rev-parse", "--is-inside-work-tree");
            if (workTree.exitCode() != 0 || !"true".equals(workTree.output().trim())) {
                log.warn("{} is not a git work tree; reviewing without guidance", repoDir);
                return Result.EMPTY;
            }
            if (!commitExists(repoDir, baseSha, deadline)) {
                GitOutput fetch =
                        git(
                                repoDir,
                                deadline,
                                FETCH_TIMEOUT_SECONDS,
                                "fetch",
                                "--no-tags",
                                "--",
                                "origin",
                                baseSha);
                if (fetch.exitCode() != 0 || !commitExists(repoDir, baseSha, deadline)) {
                    log.warn("Base commit {} is unavailable; reviewing without guidance", baseSha);
                    return Result.EMPTY;
                }
            }
        } catch (IOException | TimeoutException e) {
            log.warn("Could not resolve base commit {}; reviewing without guidance", baseSha, e);
            return Result.EMPTY;
        }
        String guidelines = guidelines(repoDir, baseSha, changedPaths, deadline);
        String fileHistory = fileHistory(repoDir, baseSha, changedPaths, deadline);
        return new Result(
                guidelines,
                fileHistory,
                callSites(repoDir, baseSha, changedPaths, manifest, deadline));
    }

    private boolean commitExists(File repoDir, String sha, Deadline deadline)
            throws IOException, TimeoutException, InterruptedException {
        return git(repoDir, deadline, "cat-file", "-e", sha + "^{commit}").exitCode() == 0;
    }

    private String guidelines(
            File repoDir, String baseSha, List<String> changedPaths, Deadline deadline)
            throws InterruptedException {
        try {
            List<String> args =
                    new ArrayList<>(
                            List.of("ls-tree", "-r", "-z", "-l", "--full-tree", baseSha, "--"));
            args.addAll(candidateLocations(changedPaths));
            GitOutput tree = git(repoDir, deadline, args.toArray(String[]::new));
            if (tree.exitCode() != 0) {
                log.warn("Could not list base commit tree (exit {})", tree.exitCode());
                return "";
            }
            List<Blob> selected = selectGuidance(parseTree(tree.output()), changedPaths);
            StringBuilder sb = new StringBuilder();
            int total = 0;
            for (Blob blob : selected) {
                if (total >= MAX_GUIDELINES_BYTES) break;
                if (blob.size() > MAX_BLOB_BYTES) continue;
                String content = readBlob(repoDir, blob, deadline);
                if (content == null || content.isEmpty()) continue;
                String separator = sb.length() == 0 ? "" : "\n\n";
                String header = "## " + blob.path() + "\n";
                int remaining = MAX_GUIDELINES_BYTES - total;
                int framing = utf8Length(separator) + utf8Length(header);
                if (framing >= remaining) break;
                if (utf8Length(content) > remaining - framing) {
                    content = RepoGuidelinesReader.truncateUtf8(content, remaining - framing);
                }
                if (content.isEmpty()) break;
                sb.append(separator).append(header).append(content);
                total += framing + utf8Length(content);
            }
            return sb.toString();
        } catch (IOException | TimeoutException e) {
            log.warn("Could not read base-commit guidance; continuing without it", e);
            return "";
        }
    }

    private String readBlob(File repoDir, Blob blob, Deadline deadline)
            throws IOException, TimeoutException, InterruptedException {
        GitOutput content = git(repoDir, deadline, "cat-file", "blob", blob.sha());
        if (content.exitCode() != 0 || content.output().indexOf('\uFFFD') >= 0) {
            return null;
        }
        return content.output().trim();
    }

    /**
     * Literal pathspecs covering every location a guidance glob can match, plus the scoped
     * instruction files in each changed path's ancestor directories. Listing the whole tree would
     * overflow the process output bound on large repositories.
     */
    static List<String> candidateLocations(List<String> changedPaths) {
        LinkedHashSet<String> locations =
                new LinkedHashSet<>(
                        List.of(
                                "AGENTS.md",
                                "CLAUDE.md",
                                "CONTRIBUTING.md",
                                ".claude/rules",
                                ".github",
                                "docs/CONTRIBUTING.md",
                                REVIEW_GUIDELINES_PATH));
        for (String path : changedPaths) {
            for (String dir = parentDirectory(path); !dir.isEmpty(); dir = parentDirectory(dir)) {
                for (String name : SCOPED_NAMES.stream().sorted().toList()) {
                    locations.add(dir + "/" + name);
                }
            }
        }
        return new ArrayList<>(locations);
    }

    /**
     * Parses {@code ls-tree -r -z -l} output, keeping only regular file blobs so symlinks and
     * submodules can never redirect a read outside the base tree.
     */
    static List<Blob> parseTree(String output) {
        List<Blob> blobs = new ArrayList<>();
        for (String entry : output.split("\0")) {
            int tab = entry.indexOf('\t');
            if (tab < 0) continue;
            String[] meta = entry.substring(0, tab).trim().split("\\s+");
            if (meta.length != 4) continue;
            if (!"100644".equals(meta[0]) && !"100755".equals(meta[0])) continue;
            if (!"blob".equals(meta[1]) || !BLOB_SHA.matcher(meta[2]).matches()) continue;
            long size;
            try {
                size = Long.parseLong(meta[3]);
            } catch (NumberFormatException e) {
                continue;
            }
            blobs.add(new Blob(entry.substring(tab + 1), meta[2], size));
        }
        return blobs;
    }

    /**
     * Orders matched guidance: root and rule files first, then nested {@code AGENTS.md}/{@code
     * CLAUDE.md} scoped to a changed path (deepest last, so the most specific rules sit nearest the
     * diff), then contribution docs. Nested files outside every changed path's ancestry are
     * dropped.
     */
    static List<Blob> selectGuidance(List<Blob> blobs, List<String> changedPaths) {
        List<String> globs = new ArrayList<>(RepoGuidelinesReader.DEFAULT_GUIDANCE_GLOBS);
        globs.add(REVIEW_GUIDELINES_PATH);
        List<Pattern> patterns =
                globs.stream()
                        .map(g -> Pattern.compile(RepoGuidelinesReader.globToRegex(g)))
                        .toList();
        List<Blob> leading = new ArrayList<>();
        List<Blob> scoped = new ArrayList<>();
        List<Blob> trailing = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Pattern pattern : patterns) {
            blobs.stream()
                    .filter(blob -> pattern.matcher(blob.path()).matches())
                    .sorted(Comparator.comparing(Blob::path))
                    .filter(blob -> seen.add(blob.path()))
                    .forEach(
                            blob -> {
                                String directory = parentDirectory(blob.path());
                                if (TRAILING_PATHS.contains(blob.path())) {
                                    trailing.add(blob);
                                } else if (!directory.isEmpty()
                                        && SCOPED_NAMES.contains(fileName(blob.path()))) {
                                    if (isAncestorOfAny(directory, changedPaths)) {
                                        scoped.add(blob);
                                    }
                                } else {
                                    leading.add(blob);
                                }
                            });
        }
        scoped.sort(
                Comparator.comparingInt((Blob blob) -> depth(blob.path()))
                        .thenComparing(Blob::path));
        List<Blob> ordered = new ArrayList<>(leading);
        ordered.addAll(scoped);
        ordered.addAll(trailing);
        return ordered;
    }

    private String fileHistory(
            File repoDir, String baseSha, List<String> changedPaths, Deadline deadline)
            throws InterruptedException {
        StringBuilder sb = new StringBuilder();
        int total = 0;
        try {
            for (String path : changedPaths.stream().limit(MAX_HISTORY_FILES).toList()) {
                GitOutput history =
                        git(
                                repoDir,
                                deadline,
                                "log",
                                "-n",
                                String.valueOf(HISTORY_COMMITS_PER_FILE),
                                "--no-merges",
                                "--date=short",
                                "--format=%h %ad %s",
                                baseSha,
                                "--",
                                path);
                if (history.exitCode() != 0 || history.output().isBlank()) continue;
                String section =
                        (sb.length() == 0 ? "" : "\n\n")
                                + "## "
                                + path
                                + "\n"
                                + history.output().trim();
                int bytes = utf8Length(section);
                if (total + bytes > MAX_HISTORY_BYTES) break;
                sb.append(section);
                total += bytes;
            }
        } catch (IOException | TimeoutException e) {
            log.warn("Could not read base-commit file history; continuing without it", e);
            return "";
        }
        return sb.toString();
    }

    /**
     * Lists base-commit lines outside the changed files that mention each changed declaration by
     * whole word. The matches are textual, so they can include unrelated symbols that share a name;
     * the prompt says so. Runs last, with its own sub-budget, so a large repository cannot starve
     * guidance or history.
     */
    private String callSites(
            File repoDir,
            String baseSha,
            List<String> changedPaths,
            InspectionManifest manifest,
            Deadline overall)
            throws InterruptedException {
        List<ChangedSymbols.Symbol> symbols = ChangedSymbols.extract(manifest);
        if (symbols.isEmpty()) return "";
        Deadline deadline =
                new Deadline(
                        Math.min(
                                overall.deadlineNanos(),
                                System.nanoTime()
                                        + TimeUnit.SECONDS.toNanos(CALL_SITES_TIMEOUT_SECONDS)),
                        overall.cancellation());
        Set<String> changed = Set.copyOf(changedPaths);
        String prefix = baseSha + ":";
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (ChangedSymbols.Symbol symbol : symbols) {
            String section;
            try {
                section = callSiteSection(repoDir, baseSha, prefix, changed, symbol, deadline);
            } catch (IOException e) {
                log.debug("Skipping call sites for {}: {}", symbol.name(), e.getMessage());
                continue;
            } catch (TimeoutException e) {
                log.warn("Call-site search ran out of time; using the sites found so far");
                break;
            }
            if (section.isEmpty()) continue;
            String block = (sb.length() == 0 ? "" : "\n\n") + section;
            int bytes = utf8Length(block);
            if (total + bytes > MAX_CALL_SITES_BYTES) break;
            sb.append(block);
            total += bytes;
        }
        return sb.toString();
    }

    private String callSiteSection(
            File repoDir,
            String baseSha,
            String prefix,
            Set<String> changed,
            ChangedSymbols.Symbol symbol,
            Deadline deadline)
            throws IOException, TimeoutException, InterruptedException {
        GitOutput counts =
                git(
                        repoDir,
                        deadline,
                        "grep",
                        "-z",
                        "-c",
                        "-I",
                        "-w",
                        "-F",
                        "-e",
                        symbol.name(),
                        baseSha);
        if (counts.exitCode() != 0) return "";
        List<String> files = new ArrayList<>();
        int references = 0;
        for (String line : counts.output().split("\n")) {
            int separator = line.indexOf('\0');
            if (separator < 0 || !line.startsWith(prefix)) continue;
            String path = line.substring(prefix.length(), separator);
            if (changed.contains(path)) continue;
            try {
                references += Integer.parseInt(line.substring(separator + 1).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            files.add(path);
        }
        if (files.isEmpty()) return "";
        String header =
                "## "
                        + symbol.name()
                        + " ("
                        + (symbol.declarationChanged() ? "declaration" : "body")
                        + " changed in "
                        + symbol.path()
                        + ")";
        if (references > MAX_LISTED_REFERENCES) {
            return header
                    + "\n"
                    + references
                    + " references in "
                    + files.size()
                    + " files outside the changed files; too many to list";
        }
        List<String> args =
                new ArrayList<>(
                        List.of(
                                "grep",
                                "-z",
                                "-n",
                                "-I",
                                "-w",
                                "-F",
                                "-e",
                                symbol.name(),
                                baseSha,
                                "--"));
        args.addAll(files.stream().limit(MAX_CALL_SITE_FILES).toList());
        GitOutput matches = git(repoDir, deadline, args.toArray(String[]::new));
        if (matches.exitCode() != 0) return "";
        StringBuilder sb = new StringBuilder(header);
        int listed = 0;
        for (String line : matches.output().split("\n")) {
            if (listed == MAX_CALL_SITES_PER_SYMBOL) break;
            String[] parts = line.split("\0", 3);
            if (parts.length < 3 || !parts[0].startsWith(prefix)) continue;
            String content = parts[2].strip();
            if (content.length() > MAX_CALL_SITE_LINE_CHARS) {
                content = content.substring(0, MAX_CALL_SITE_LINE_CHARS) + "...";
            }
            sb.append('\n')
                    .append(parts[0].substring(prefix.length()))
                    .append(':')
                    .append(parts[1])
                    .append(": ")
                    .append(content);
            listed++;
        }
        return listed == 0 ? "" : sb.toString();
    }

    private GitOutput git(File dir, Deadline deadline, String... args)
            throws IOException, TimeoutException, InterruptedException {
        return git(dir, deadline, COMMAND_TIMEOUT_SECONDS, args);
    }

    private GitOutput git(File dir, Deadline deadline, long timeoutSeconds, String... args)
            throws IOException, TimeoutException, InterruptedException {
        long remainingMillis = deadline.remainingMillis();
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir);
        pb.environment().put("HOME", System.getProperty("user.home", "/"));
        String existingPath = pb.environment().getOrDefault("PATH", "");
        pb.environment().put("PATH", "/opt/homebrew/bin:/usr/local/bin:" + existingPath);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GIT_LITERAL_PATHSPECS", "1");
        long timeoutMillis = Math.min(TimeUnit.SECONDS.toMillis(timeoutSeconds), remainingMillis);
        BoundedProcessRunner.ProcessResult result =
                processRunner.run(pb, timeoutMillis, TimeUnit.MILLISECONDS);
        if (result.outputTruncated()) {
            throw new IOException("git " + args[0] + " output exceeded the process output bound");
        }
        return new GitOutput(result.exitCode(), result.output());
    }

    /** Overall time budget shared by every git command, plus the review's cancellation check. */
    private record Deadline(long deadlineNanos, CancellationCheck cancellation) {
        long remainingMillis() throws TimeoutException, InterruptedException {
            if (cancellation != null) cancellation.check();
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            if (remaining < 1) {
                throw new TimeoutException("Base-commit context exceeded its overall time budget");
            }
            return remaining;
        }
    }

    private static boolean isAncestorOfAny(String directory, List<String> changedPaths) {
        String prefix = directory + "/";
        return changedPaths.stream().anyMatch(path -> path.startsWith(prefix));
    }

    private static String parentDirectory(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static String fileName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static int depth(String path) {
        return (int) path.chars().filter(c -> c == '/').count();
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
