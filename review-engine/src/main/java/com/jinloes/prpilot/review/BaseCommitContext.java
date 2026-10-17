package com.jinloes.prpilot.review;

import com.jinloes.prpilot.model.DiffCoverage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads trusted review context from a pull request's <b>base</b> commit: repository guidance
 * documents, the recent history of each changed file, and textual call sites of the declarations
 * the diff changes. It also compares each changed {@code .proto} schema with its version on the
 * diff's old side and finds committed code generated from it that the diff leaves as it was.
 *
 * <p>Guidance at the PR head is author-controlled, so it is never read from the worktree or the
 * head tree. Every read goes through read-only git plumbing against commit objects, with
 * per-command and overall time bounds. A schema's old side can be an earlier PR head, for an
 * incremental review; only a structural summary of the diff is derived from it, never guidance. Any
 * failure degrades to empty context; only cancellation propagates.
 */
final class BaseCommitContext {
    private static final Logger log = LoggerFactory.getLogger(BaseCommitContext.class);

    static final int MAX_GUIDELINES_BYTES = 48_000;
    static final int MAX_HISTORY_BYTES = 4_000;
    static final int MAX_HISTORY_FILES = 20;
    static final int MAX_BLOB_BYTES = 64 * 1024;
    static final int MAX_CALL_SITES_BYTES = 6_000;
    static final int MAX_CALL_SITE_FILES = 10;
    static final int MAX_CALL_SITES_PER_SYMBOL = 8;
    static final int MAX_LISTED_REFERENCES = 40;
    static final int MAX_CALL_SITE_LINE_CHARS = 160;
    private static final long CALL_SITES_TIMEOUT_SECONDS = 30;
    private static final long PROTO_SCHEMA_TIMEOUT_SECONDS = 30;
    private static final int HISTORY_COMMITS_PER_FILE = 3;
    private static final long COMMAND_TIMEOUT_SECONDS = 15;
    private static final long FETCH_TIMEOUT_SECONDS = 60;
    private static final long OVERALL_TIMEOUT_SECONDS = 90;

    /**
     * Diagnostic switch for measuring what call-site context contributes to recall. Only the review
     * benchmark sets it; hosts leave it unset so call sites are always gathered.
     */
    static final String CALL_SITES_PROPERTY = "prpilot.review.callSites";

    private static final Pattern COMMIT_SHA = Pattern.compile("(?i)[0-9a-f]{40}|[0-9a-f]{64}");
    private static final Pattern BLOB_SHA = Pattern.compile("(?i)[0-9a-f]{40}|[0-9a-f]{64}");

    /** A schema file name safe to splice into a search pattern once its dots are escaped. */
    private static final Pattern PROTO_FILE_NAME = Pattern.compile("[A-Za-z0-9_.-]+\\.proto");

    private static final Set<String> SCOPED_NAMES = Set.of("AGENTS.md", "CLAUDE.md");
    private static final Set<String> TRAILING_PATHS =
            Set.of(
                    "CONTRIBUTING.md",
                    ".github/CONTRIBUTING.md",
                    "docs/CONTRIBUTING.md",
                    ".github/pull_request_template.md");

    /** Resolved context; any part is empty when unavailable. */
    record Result(
            String guidelines, String fileHistory, String callSites, String protoSchemaChanges) {
        static final Result EMPTY = new Result("", "", "", "");

        Result(String guidelines, String fileHistory, String callSites) {
            this(guidelines, fileHistory, callSites, "");
        }
    }

    /**
     * What to resolve context for. {@code diffBaseSha} is the commit an incremental diff was taken
     * against, or null for a full pull request diff. {@code omittedPaths} are files the diff
     * changes but the review leaves out, such as regenerated code. {@code guidanceGlobs} are the
     * user-configured guidance globs, matched before the built-in defaults; null or empty means
     * defaults only.
     */
    record Scope(
            String baseSha,
            String diffBaseSha,
            InspectionManifest manifest,
            Set<String> omittedPaths,
            List<String> guidanceGlobs) {
        Scope {
            omittedPaths = omittedPaths == null ? Set.of() : Set.copyOf(omittedPaths);
        }
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
     * {@code guidanceGlobs} are the user-configured guidance globs, matched before the built-in
     * defaults; null or empty means defaults only. Returns {@link Result#EMPTY} when the directory
     * is not a git work tree, the SHA is malformed, or the commit cannot be found even after
     * fetching it.
     *
     * @throws InterruptedException when {@code cancellation} reports the review was cancelled
     */
    Result resolve(
            File repoDir,
            String baseSha,
            InspectionManifest manifest,
            List<String> guidanceGlobs,
            CancellationCheck cancellation)
            throws InterruptedException {
        return resolve(
                repoDir, new Scope(baseSha, null, manifest, Set.of(), guidanceGlobs), cancellation);
    }

    /**
     * Resolves context for {@code scope}, as {@link #resolve(File, String, InspectionManifest,
     * List, CancellationCheck)} does, adding the changed schemas' compatibility delta.
     *
     * @throws InterruptedException when {@code cancellation} reports the review was cancelled
     */
    Result resolve(File repoDir, Scope scope, CancellationCheck cancellation)
            throws InterruptedException {
        String baseSha = scope.baseSha();
        InspectionManifest manifest = scope.manifest();
        List<String> configuredGlobs =
                scope.guidanceGlobs() == null
                        ? List.of()
                        : scope.guidanceGlobs().stream()
                                .filter(glob -> glob != null && !glob.isBlank())
                                .map(String::strip)
                                .distinct()
                                .toList();
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
            if (!commitExists(repoDir, baseSha, deadline)
                    && !fetchCommit(repoDir, baseSha, deadline)) {
                log.warn("Base commit {} is unavailable; reviewing without guidance", baseSha);
                return Result.EMPTY;
            }
        } catch (IOException | TimeoutException e) {
            log.warn("Could not resolve base commit {}; reviewing without guidance", baseSha, e);
            return Result.EMPTY;
        }
        String guidelines = guidelines(repoDir, baseSha, changedPaths, configuredGlobs, deadline);
        String fileHistory = fileHistory(repoDir, baseSha, changedPaths, deadline);
        String protoSchemaChanges = protoSchemaChanges(repoDir, scope, changedPaths, deadline);
        String callSites =
                callSitesEnabled()
                        ? callSites(repoDir, baseSha, changedPaths, manifest, deadline)
                        : "";
        return new Result(guidelines, fileHistory, callSites, protoSchemaChanges);
    }

    static boolean callSitesEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty(CALL_SITES_PROPERTY, "").strip());
    }

    private boolean commitExists(File repoDir, String sha, Deadline deadline)
            throws IOException, TimeoutException, InterruptedException {
        return git(repoDir, deadline, "cat-file", "-e", sha + "^{commit}").exitCode() == 0;
    }

    /** Fetches {@code sha} from origin; true when the commit is present afterwards. */
    private boolean fetchCommit(File repoDir, String sha, Deadline deadline)
            throws IOException, TimeoutException, InterruptedException {
        GitOutput fetch =
                git(
                        repoDir,
                        deadline,
                        FETCH_TIMEOUT_SECONDS,
                        "fetch",
                        "--no-tags",
                        "--",
                        "origin",
                        sha);
        return fetch.exitCode() == 0 && commitExists(repoDir, sha, deadline);
    }

    /**
     * The compatibility delta of each changed {@code .proto} schema against its version on the
     * diff's old side, plus committed code generated from a schema changed in place that the diff
     * leaves as it was. Runs with its own sub-budget; a review that changes no schema skips it
     * without running git.
     */
    private String protoSchemaChanges(
            File repoDir, Scope scope, List<String> changedPaths, Deadline overall)
            throws InterruptedException {
        InspectionManifest manifest = scope.manifest();
        if (manifest == null
                || manifest.files().stream()
                        .noneMatch(file -> ProtoSchemaChanges.isSchemaPath(file.path()))) {
            return "";
        }
        Deadline deadline = overall.within(PROTO_SCHEMA_TIMEOUT_SECONDS);
        String oldSide = diffOldSide(repoDir, scope, deadline);
        Set<String> excluded = new HashSet<>(changedPaths);
        excluded.addAll(scope.omittedPaths());
        return ProtoSchemaChanges.compute(
                manifest,
                path -> fileAt(repoDir, oldSide, path, deadline),
                schemaPaths ->
                        staleGeneratedCode(repoDir, oldSide, schemaPaths, excluded, deadline));
    }

    /**
     * The commit the diff's old side shows. An incremental diff compares against its baseline,
     * which is in the head's history. A pull request diff compares against the merge base of the
     * base commit and the head, which is the checked-out {@code HEAD} in a review worktree. Falls
     * back to the base commit, already known to exist; a diff that does not apply to the version
     * read there is reported as unparsed rather than compared.
     */
    private String diffOldSide(File repoDir, Scope scope, Deadline deadline)
            throws InterruptedException {
        String baseSha = scope.baseSha();
        String baseline = scope.diffBaseSha();
        try {
            if (baseline != null
                    && COMMIT_SHA.matcher(baseline).matches()
                    && !baseline.equalsIgnoreCase(baseSha)) {
                if (commitExists(repoDir, baseline, deadline)
                        || fetchCommit(repoDir, baseline, deadline)) {
                    return baseline;
                }
                log.debug("Incremental baseline {} is unavailable; using the merge base", baseline);
            }
            GitOutput mergeBase = git(repoDir, deadline, "merge-base", baseSha, "HEAD");
            String sha = mergeBase.output().trim();
            if (mergeBase.exitCode() == 0 && COMMIT_SHA.matcher(sha).matches()) {
                return sha;
            }
        } catch (IOException | TimeoutException e) {
            log.debug("Could not resolve the diff's old side: {}", e.getMessage());
        }
        return baseSha;
    }

    /**
     * A regular file's exact text at {@code commit}; empty when it is missing, larger than a schema
     * may be, not UTF-8, or unreadable in time.
     */
    private Optional<String> fileAt(File repoDir, String commit, String path, Deadline deadline)
            throws InterruptedException {
        if (!InspectionManifest.isSafeRelativePath(path)) {
            return Optional.empty();
        }
        try {
            GitOutput tree =
                    git(
                            repoDir,
                            deadline,
                            "ls-tree",
                            "-r",
                            "-z",
                            "-l",
                            "--full-tree",
                            commit,
                            "--",
                            path);
            if (tree.exitCode() != 0) return Optional.empty();
            List<Blob> blobs =
                    parseTree(tree.output()).stream()
                            .filter(blob -> blob.path().equals(path))
                            .toList();
            if (blobs.size() != 1 || blobs.get(0).size() > ProtoSchemaChanges.MAX_FILE_BYTES) {
                return Optional.empty();
            }
            GitOutput content = git(repoDir, deadline, "cat-file", "blob", blobs.get(0).sha());
            if (content.exitCode() != 0 || content.output().indexOf('\uFFFD') >= 0) {
                return Optional.empty();
            }
            return Optional.of(content.output());
        } catch (IOException | TimeoutException e) {
            log.debug("Could not read {} at {}: {}", path, commit, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Files at {@code commit} whose generator comment names one of {@code schemaPaths} as their
     * source, keyed by schema. Files in {@code excluded}, schemas, vendored files and paths the
     * prompt cannot name are skipped. A failed search finds nothing.
     */
    private Map<String, List<String>> staleGeneratedCode(
            File repoDir,
            String commit,
            List<String> schemaPaths,
            Set<String> excluded,
            Deadline deadline)
            throws InterruptedException {
        List<String> args = new ArrayList<>(List.of("grep", "--full-name", "-z", "-I", "-E"));
        int fixedArgs = args.size();
        schemaPaths.stream()
                .map(BaseCommitContext::fileName)
                .distinct()
                .filter(name -> PROTO_FILE_NAME.matcher(name).matches())
                .forEach(
                        name -> {
                            args.add("-e");
                            args.add(
                                    "(source:|@generated from file)[[:space:]]*([^[:space:]]*/)?"
                                            + name.replace(".", "[.]")
                                            + "([[:space:]]|$)");
                        });
        if (args.size() == fixedArgs) return Map.of();
        args.add(commit);
        GitOutput matches;
        try {
            matches = git(repoDir, deadline, args.toArray(String[]::new));
        } catch (IOException | TimeoutException e) {
            log.debug("Could not search for generated code: {}", e.getMessage());
            return Map.of();
        }
        if (matches.exitCode() != 0) return Map.of();
        String prefix = commit + ":";
        Map<String, Set<String>> found = new LinkedHashMap<>();
        for (String line : matches.output().split("\n")) {
            String[] parts = line.split("\0", 2);
            if (parts.length < 2 || !parts[0].startsWith(prefix)) continue;
            String path = parts[0].substring(prefix.length());
            if (excluded.contains(path)
                    || path.toLowerCase(Locale.ROOT).endsWith(".proto")
                    || ProtoSchemaChanges.isVendored(path)
                    || !DiffCoverage.listablePath(path)
                    || !InspectionManifest.isSafeRelativePath(path)) {
                continue;
            }
            Optional<String> source = GeneratedProtoCode.sourceProto(parts[1]);
            if (source.isEmpty()) continue;
            for (String schema : schemaPaths) {
                if (GeneratedProtoCode.sameProto(schema, source.get())) {
                    found.computeIfAbsent(schema, key -> new LinkedHashSet<>()).add(path);
                }
            }
        }
        Map<String, List<String>> stale = new LinkedHashMap<>();
        found.forEach((schema, paths) -> stale.put(schema, List.copyOf(paths)));
        return stale;
    }

    private String guidelines(
            File repoDir,
            String baseSha,
            List<String> changedPaths,
            List<String> configuredGlobs,
            Deadline deadline)
            throws InterruptedException {
        try {
            List<String> args =
                    new ArrayList<>(
                            List.of("ls-tree", "-r", "-z", "-l", "--full-tree", baseSha, "--"));
            args.addAll(candidateLocations(changedPaths, configuredGlobs));
            GitOutput tree = git(repoDir, deadline, args.toArray(String[]::new));
            if (tree.exitCode() != 0) {
                log.warn("Could not list base commit tree (exit {})", tree.exitCode());
                return "";
            }
            List<Blob> selected =
                    selectGuidance(parseTree(tree.output()), changedPaths, configuredGlobs);
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
     * overflow the process output bound on large repositories. Each configured glob contributes its
     * {@link #literalPathspec}; one with no usable prefix is still matched in {@link
     * #selectGuidance} against whatever the defaults list.
     */
    static List<String> candidateLocations(List<String> changedPaths, List<String> globs) {
        LinkedHashSet<String> locations =
                new LinkedHashSet<>(
                        List.of(
                                "AGENTS.md",
                                "CLAUDE.md",
                                "CONTRIBUTING.md",
                                ".claude/rules",
                                ".github",
                                "docs/CONTRIBUTING.md"));
        if (globs != null) {
            for (String glob : globs) {
                String pathspec = literalPathspec(glob);
                if (pathspec != null) locations.add(pathspec);
            }
        }
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
     * The literal path {@code ls-tree} must list for {@code glob} to be matchable: the whole glob
     * when it has no wildcard, otherwise the directory prefix before the first wildcard segment.
     * Null for blank, absolute, or {@code ..}-containing globs and for globs whose first segment is
     * already a wildcard, since those cannot be bounded to a subtree.
     */
    static String literalPathspec(String glob) {
        if (glob == null || glob.isBlank()) return null;
        String trimmed = glob.strip();
        if (trimmed.startsWith("/")
                || trimmed.startsWith("\\")
                || trimmed.matches("^[A-Za-z]:.*")) {
            return null;
        }
        String[] segments = trimmed.split("/");
        if (List.of(segments).contains("..")) return null;
        List<String> prefix = new ArrayList<>();
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (segment.indexOf('*') >= 0 || segment.indexOf('?') >= 0) break;
            prefix.add(segment);
        }
        return prefix.isEmpty() ? null : String.join("/", prefix);
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
     * dropped. Configured globs are evaluated before the defaults, matching {@link
     * RepoGuidelinesReader#read}; a file matched by both is kept once.
     */
    static List<Blob> selectGuidance(
            List<Blob> blobs, List<String> changedPaths, List<String> configuredGlobs) {
        LinkedHashSet<String> globs = new LinkedHashSet<>();
        if (configuredGlobs != null) {
            configuredGlobs.stream()
                    .filter(glob -> glob != null && !glob.isBlank())
                    .map(String::strip)
                    .forEach(globs::add);
        }
        globs.addAll(RepoGuidelinesReader.DEFAULT_GUIDANCE_GLOBS);
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
     * whole word, or for a protobuf element, any name generated code gives it. The matches are
     * textual, so they can include unrelated symbols that share a name; the prompt says so. Runs
     * last, with its own sub-budget, so a large repository cannot starve guidance or history.
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
        Deadline deadline = overall.within(CALL_SITES_TIMEOUT_SECONDS);
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
        List<String> countArgs = new ArrayList<>(List.of("grep", "-z", "-c", "-I", "-w", "-F"));
        addPatterns(countArgs, symbol);
        countArgs.add(baseSha);
        GitOutput counts = git(repoDir, deadline, countArgs.toArray(String[]::new));
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
                        + (symbol.aliases().isEmpty()
                                ? ""
                                : "; matches include the names generated code gives it")
                        + ")";
        if (references > MAX_LISTED_REFERENCES) {
            return header
                    + "\n"
                    + references
                    + " references in "
                    + files.size()
                    + " files outside the changed files; too many to list";
        }
        List<String> args = new ArrayList<>(List.of("grep", "-z", "-n", "-I", "-w", "-F"));
        addPatterns(args, symbol);
        args.add(baseSha);
        args.add("--");
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

    /** One {@code -e} per search term; git grep matches a line containing any of them. */
    private static void addPatterns(List<String> args, ChangedSymbols.Symbol symbol) {
        for (String term : symbol.searchTerms()) {
            args.add("-e");
            args.add(term);
        }
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

        /** A sub-budget of at most {@code seconds} from now, inside this one. */
        Deadline within(long seconds) {
            return new Deadline(
                    Math.min(deadlineNanos, System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)),
                    cancellation);
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
