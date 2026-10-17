package com.jinloes.prpilot.review;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Computes the structural compatibility changes a diff makes to its {@code .proto} files, as the
 * entries of the review prompt's {@code proto_schema_changes} section.
 *
 * <p>Each changed schema's base version comes from a {@link BaseReader}; the head version is
 * rebuilt by applying the file's diff hunks to it, and the two parsed schemas are compared with
 * Buf-style rule IDs. Every line names the file, the rule, its impact tags and a detail built only
 * from identifiers and numbers, never from comments or string values, since both are PR-controlled.
 * A file that cannot be read, patched or parsed is reported as {@code unparsed} instead of guessed.
 */
final class ProtoSchemaChanges {
    private static final Logger log = LoggerFactory.getLogger(ProtoSchemaChanges.class);
    static final int MAX_FILES = 20;
    static final int MAX_ENTRIES = 100;
    static final int MAX_FILE_BYTES = 64 * 1024;

    /**
     * Generated files listed per {@code GENERATED_CODE_NOT_UPDATED} entry; the rest are counted.
     */
    static final int MAX_LISTED_GENERATED = 5;

    private static final Pattern VENDORED =
            Pattern.compile(
                    "(?i)(^|/)(third_party|third-party|vendor|vendored|node_modules|external)/");
    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@");

    private ProtoSchemaChanges() {}

    @FunctionalInterface
    interface BaseReader {
        Optional<String> read(String path) throws InterruptedException;
    }

    /**
     * Finds committed generated code that names a schema as its source but is not in the diff.
     * Returns the generated paths keyed by schema path; a schema with none is absent.
     */
    @FunctionalInterface
    interface GeneratedCodeFinder {
        Map<String, List<String>> find(List<String> schemaPaths) throws InterruptedException;
    }

    static boolean isSchemaPath(String path) {
        return normalizePath(path).toLowerCase(Locale.ROOT).endsWith(".proto") && !isVendored(path);
    }

    static boolean isVendored(String path) {
        return VENDORED.matcher(normalizePath(path)).find();
    }

    static String compute(InspectionManifest manifest, BaseReader reader)
            throws InterruptedException {
        return compute(manifest, reader, schemaPaths -> Map.of());
    }

    /**
     * Like {@link #compute(InspectionManifest, BaseReader)}, also reporting each schema changed in
     * place whose committed generated code {@code finder} finds outside the diff.
     */
    static String compute(
            InspectionManifest manifest, BaseReader reader, GeneratedCodeFinder finder)
            throws InterruptedException {
        List<InspectionManifest.FileTarget> schemaFiles =
                manifest.files().stream().filter(file -> isSchemaPath(file.path())).toList();
        if (schemaFiles.isEmpty()) {
            return "";
        }
        List<FileAnalysis> analyses = new ArrayList<>();
        int analyzed = Math.min(MAX_FILES, schemaFiles.size());
        for (int index = 0; index < analyzed; index++) {
            analyses.add(analyze(schemaFiles.get(index), reader));
        }
        applyMoveDetection(analyses);
        List<String> modified =
                analyses.stream()
                        .filter(FileAnalysis::modifiedInPlace)
                        .map(analysis -> analysis.file.path())
                        .toList();
        Map<String, List<String>> staleGenerated =
                modified.isEmpty() ? Map.of() : finder.find(modified);
        List<String> lines = new ArrayList<>();
        int omittedEntries = 0;
        for (FileAnalysis analysis : analyses) {
            List<String> rendered = new ArrayList<>(render(analysis));
            generatedCodeLine(analysis, staleGenerated.get(analysis.file.path()))
                    .ifPresent(rendered::add);
            for (String line : rendered) {
                if (lines.size() >= MAX_ENTRIES) {
                    omittedEntries++;
                } else {
                    lines.add(line);
                }
            }
        }
        if (schemaFiles.size() > MAX_FILES) {
            lines.add(
                    "- "
                            + (schemaFiles.size() - MAX_FILES)
                            + " more changed .proto files not analyzed");
        }
        if (omittedEntries > 0) {
            lines.add("- " + omittedEntries + " more schema changes not listed");
        }
        return String.join("\n", lines);
    }

    private static FileAnalysis analyze(InspectionManifest.FileTarget file, BaseReader reader)
            throws InterruptedException {
        Header header = Header.parse(file);
        try {
            return analyzeSchema(file, header, reader);
        } catch (RuntimeException failure) {
            // Both versions come from the PR, so an analyzer bug must cost only this file's
            // entries,
            // not the review.
            log.warn("Could not analyze schema changes in {}", file.path(), failure);
            FileAnalysis analysis = new FileAnalysis(file, header);
            analysis.unparsed = "unparsed (schema analysis failed)";
            return analysis;
        }
    }

    private static FileAnalysis analyzeSchema(
            InspectionManifest.FileTarget file, Header header, BaseReader reader)
            throws InterruptedException {
        FileAnalysis analysis = new FileAnalysis(file, header);
        if (header.unparsed()) {
            analysis.unparsed = "unparsed (diff header path is quoted or unsupported)";
            return analysis;
        }
        if (header.binary()) {
            analysis.unparsed = "unparsed (diff does not apply to the base version)";
            return analysis;
        }
        String baseText = "";
        if (!header.added()) {
            Optional<String> base = reader.read(header.basePath());
            if (base.isEmpty() || base.get().length() > MAX_FILE_BYTES) {
                analysis.unparsed = "unparsed (base version unavailable or larger than 64 KB)";
                return analysis;
            }
            baseText = base.get();
        }
        if (baseText.length() > MAX_FILE_BYTES) {
            analysis.unparsed = "unparsed (base version unavailable or larger than 64 KB)";
            return analysis;
        }
        Optional<ProtoSchema> baseSchema = ProtoSchema.parse(header.copied() ? "" : baseText);
        if (baseSchema.isEmpty()) {
            analysis.unparsed = "unparsed (could not parse the base version)";
            return analysis;
        }
        analysis.baseSchema = baseSchema.get();
        if (header.deleted()) {
            analysis.deleted = true;
            collectDeletedTypes(analysis);
            return analysis;
        }
        Optional<String> headText = UnifiedPatch.apply(baseText, file.diff());
        if (headText.isEmpty()) {
            analysis.unparsed = "unparsed (diff does not apply to the base version)";
            return analysis;
        }
        if (headText.get().length() > MAX_FILE_BYTES) {
            analysis.unparsed = "unparsed (larger than 64 KB)";
            return analysis;
        }
        Optional<ProtoSchema> headSchema = ProtoSchema.parse(headText.get());
        if (headSchema.isEmpty()) {
            analysis.unparsed = "unparsed (could not parse the head version)";
            return analysis;
        }
        analysis.headSchema = headSchema.get();
        if (header.renamed(file.path())) {
            analysis.changes.add(
                    new ProtoSchemaDelta.Change(
                            "FILE_NO_DELETE",
                            List.of("source"),
                            "renamed from "
                                    + header.basePath()
                                    + "; files importing the old path, and code generated from"
                                    + " it, must change",
                            0,
                            0));
        }
        List<ProtoSchemaDelta.Change> changes =
                ProtoSchemaDelta.compare(baseSchema.get(), headSchema.get());
        // A new file has no previous package, syntax or options to keep the same.
        analysis.changes.addAll(
                header.added() || header.copied()
                        ? changes.stream()
                                .filter(change -> !change.rule().startsWith("FILE_"))
                                .toList()
                        : changes);
        ProtoSchemaDelta.TypeChanges typeChanges =
                ProtoSchemaDelta.typeChanges(baseSchema.get(), headSchema.get());
        analysis.removedMessages.addAll(typeChanges.removedMessages());
        analysis.removedEnums.addAll(typeChanges.removedEnums());
        analysis.removedServices.addAll(typeChanges.removedServices());
        analysis.addedMessages.addAll(typeChanges.addedMessages());
        analysis.addedEnums.addAll(typeChanges.addedEnums());
        analysis.addedServices.addAll(typeChanges.addedServices());
        return analysis;
    }

    private static void collectDeletedTypes(FileAnalysis analysis) {
        analysis.removedMessages.addAll(analysis.baseSchema.messages().keySet());
        analysis.removedEnums.addAll(analysis.baseSchema.enums().keySet());
        analysis.removedServices.addAll(analysis.baseSchema.services().keySet());
    }

    private static void applyMoveDetection(List<FileAnalysis> analyses) {
        Map<String, AddedType> added = new LinkedHashMap<>();
        for (FileAnalysis analysis : analyses) {
            putAdded(added, analysis, analysis.addedMessages, "MESSAGE");
            putAdded(added, analysis, analysis.addedEnums, "ENUM");
            putAdded(added, analysis, analysis.addedServices, "SERVICE");
        }
        for (FileAnalysis analysis : analyses) {
            detectMoves(analysis, analysis.removedMessages, added, "MESSAGE");
            detectMoves(analysis, analysis.removedEnums, added, "ENUM");
            detectMoves(analysis, analysis.removedServices, added, "SERVICE");
        }
    }

    private static void putAdded(
            Map<String, AddedType> added, FileAnalysis analysis, Set<String> names, String kind) {
        for (String fqn : names) {
            added.put(
                    kind + ":" + ProtoSchema.lastComponent(fqn),
                    new AddedType(fqn, analysis, kind));
        }
    }

    private static void detectMoves(
            FileAnalysis analysis, Set<String> removed, Map<String, AddedType> added, String kind) {
        for (String fqn : removed) {
            AddedType target = added.get(kind + ":" + ProtoSchema.lastComponent(fqn));
            if (target == null || target.analysis() == analysis) {
                continue;
            }
            analysis.movedTypes.add(fqn);
            String basePackage = packagePart(fqn);
            String headPackage = packagePart(target.fqn());
            boolean samePackage = basePackage.equals(headPackage);
            String rule = kind + (samePackage ? "_MOVED" : "_MOVED_PACKAGE");
            List<String> impacts =
                    samePackage
                            ? List.of("source")
                            : "SERVICE".equals(kind)
                                    ? List.of("wire", "json", "source")
                                    : List.of("json", "source");
            String detail =
                    ProtoSchema.lastComponent(fqn)
                            + " moved to "
                            + target.analysis().file.path()
                            + (samePackage ? "" : "; Any type URLs and JSON @type change");
            analysis.changes.add(
                    new ProtoSchemaDelta.Change(
                            rule, impacts, detail, 0, lineOf(analysis, fqn, kind)));
        }
    }

    private static List<String> render(FileAnalysis analysis) {
        List<String> lines = new ArrayList<>();
        if (analysis.unparsed != null) {
            lines.add("- " + analysis.file.path() + " " + analysis.unparsed);
            return lines;
        }
        if (analysis.deleted) {
            lines.addAll(deletedFileLine(analysis));
        }
        List<ProtoSchemaDelta.Change> changes =
                analysis.changes.stream()
                        .filter(change -> !isSuppressedMoveDelete(analysis, change))
                        .sorted(
                                Comparator.comparingInt(
                                        change ->
                                                anchor(analysis, change).orElse(Integer.MAX_VALUE)))
                        .toList();
        for (ProtoSchemaDelta.Change change : changes) {
            lines.add(entry(analysis, change));
        }
        if (!analysis.header.added()
                && !analysis.header.copied()
                && !analysis.deleted
                && analysis.unparsed == null
                && changes.isEmpty()) {
            lines.add("- " + analysis.file.path() + " no structural compatibility changes");
        }
        return lines;
    }

    private static String entry(FileAnalysis analysis, ProtoSchemaDelta.Change change) {
        Optional<Integer> line = anchor(analysis, change);
        return "- "
                + analysis.file.path()
                + line.map(value -> ":" + value).orElse("")
                + " "
                + change.rule()
                + " ["
                + String.join(", ", change.impacts())
                + "] "
                + change.detail()
                + (line.isPresent() ? "" : " (no added line in this file)");
    }

    /**
     * The entry for a schema whose committed generated code the diff leaves as it was, anchored on
     * the schema's first added line.
     */
    private static Optional<String> generatedCodeLine(FileAnalysis analysis, List<String> stale) {
        if (stale == null || stale.isEmpty()) {
            return Optional.empty();
        }
        String detail =
                "committed generated files not in this diff: "
                        + String.join(", ", stale.stream().limit(MAX_LISTED_GENERATED).toList())
                        + (stale.size() > MAX_LISTED_GENERATED
                                ? " and " + (stale.size() - MAX_LISTED_GENERATED) + " more"
                                : "");
        return Optional.of(
                entry(
                        analysis,
                        new ProtoSchemaDelta.Change(
                                "GENERATED_CODE_NOT_UPDATED", List.of("generated"), detail, 0, 0)));
    }

    private static boolean isSuppressedMoveDelete(
            FileAnalysis analysis, ProtoSchemaDelta.Change change) {
        if (!Set.of("MESSAGE_NO_DELETE", "ENUM_NO_DELETE", "SERVICE_NO_DELETE")
                .contains(change.rule())) {
            return false;
        }
        return analysis.movedTypes.stream().anyMatch(change.detail()::startsWith);
    }

    private static List<String> deletedFileLine(FileAnalysis analysis) {
        List<String> names =
                Stream.of(analysis.removedMessages, analysis.removedEnums, analysis.removedServices)
                        .flatMap(Set::stream)
                        .filter(name -> !analysis.movedTypes.contains(name))
                        .map(analysis.baseSchema::relativeName)
                        .toList();
        boolean removesService =
                analysis.removedServices.stream()
                        .anyMatch(name -> !analysis.movedTypes.contains(name));
        String impacts =
                removesService ? "wire, json, source" : names.isEmpty() ? "source" : "json, source";
        String detail;
        if (names.isEmpty()) {
            detail = "files that import it must change";
        } else {
            int omitted = Math.max(0, names.size() - 10);
            detail = "removes " + String.join(", ", names.stream().limit(10).toList());
            if (omitted > 0) {
                detail += " and " + omitted + " more";
            }
        }
        return List.of(
                "- "
                        + analysis.file.path()
                        + " (deleted) FILE_NO_DELETE ["
                        + impacts
                        + "] "
                        + detail);
    }

    private static Optional<Integer> anchor(FileAnalysis analysis, ProtoSchemaDelta.Change change) {
        List<HunkMap> hunks = hunkMaps(analysis.file.diff());
        List<Integer> added =
                hunks.stream().flatMap(hunk -> hunk.addedLines().stream()).sorted().toList();
        if (added.isEmpty()) {
            return Optional.empty();
        }
        if (change.headLine() > 0) {
            if (added.contains(change.headLine())) {
                return Optional.of(change.headLine());
            }
            Optional<HunkMap> containing =
                    hunks.stream().filter(hunk -> hunk.newContains(change.headLine())).findFirst();
            if (containing.isPresent()) {
                return nearest(containing.get().addedLines(), change.headLine());
            }
            return nearest(added, change.headLine());
        }
        if (change.baseLine() > 0) {
            Optional<HunkMap> containing =
                    hunks.stream().filter(hunk -> hunk.oldContains(change.baseLine())).findFirst();
            if (containing.isPresent()) {
                int projected = containing.get().projectOldToNew(change.baseLine());
                return nearest(containing.get().addedLines(), projected)
                        .or(() -> nearest(added, projected));
            }
        }
        return nearest(added, 1);
    }

    private static Optional<Integer> nearest(List<Integer> lines, int target) {
        return lines.stream()
                .min(
                        Comparator.comparingInt((Integer line) -> Math.abs(line - target))
                                .thenComparingInt(Integer::intValue));
    }

    private static List<HunkMap> hunkMaps(String diff) {
        Optional<List<UnifiedPatch.Hunk>> parsed = UnifiedPatch.parseHunks(diff);
        if (parsed.isEmpty()) {
            return List.of();
        }
        List<HunkMap> maps = new ArrayList<>();
        for (UnifiedPatch.Hunk hunk : parsed.get()) {
            List<Integer> added = new ArrayList<>();
            int oldLine = hunk.oldStart();
            int newLine = hunk.newStart();
            for (String line : hunk.lines()) {
                char prefix = line.charAt(0);
                if (prefix == '+') {
                    added.add(newLine++);
                } else if (prefix == '-') {
                    oldLine++;
                } else {
                    oldLine++;
                    newLine++;
                }
            }
            maps.add(
                    new HunkMap(
                            hunk.oldStart(),
                            hunk.oldCount(),
                            hunk.newStart(),
                            hunk.newCount(),
                            added));
        }
        return maps;
    }

    private static int lineOf(FileAnalysis analysis, String fqn, String kind) {
        if ("MESSAGE".equals(kind)) {
            return analysis.baseSchema.messages().get(fqn).line();
        }
        if ("ENUM".equals(kind)) {
            return analysis.baseSchema.enums().get(fqn).line();
        }
        return analysis.baseSchema.services().get(fqn).line();
    }

    private static String packagePart(String fqn) {
        int index = fqn.lastIndexOf('.');
        return index >= 0 ? fqn.substring(0, index) : "";
    }

    private static String normalizePath(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }

    private record Header(
            String basePath,
            boolean added,
            boolean deleted,
            boolean copied,
            boolean binary,
            boolean unparsed) {
        /** Reads only the lines before the first hunk, so file content cannot pose as metadata. */
        static Header parse(InspectionManifest.FileTarget file) {
            String base = null;
            boolean added = false;
            boolean deleted = false;
            boolean copied = false;
            for (String line : file.diff().split("\\r?\\n")) {
                if (line.startsWith("@@")) {
                    break;
                }
                if (line.startsWith("GIT binary patch") || line.startsWith("Binary files ")) {
                    return new Header(file.path(), false, false, false, true, false);
                }
                if (line.startsWith("--- \"")
                        || line.startsWith("+++ \"")
                        || line.startsWith("rename from \"")
                        || line.startsWith("copy from \"")) {
                    return new Header(file.path(), false, false, false, false, true);
                }
                if (line.startsWith("new file mode") || line.startsWith("--- /dev/null")) {
                    added = true;
                } else if (line.startsWith("deleted file mode")
                        || line.startsWith("+++ /dev/null")) {
                    deleted = true;
                } else if (line.startsWith("rename from ")) {
                    base = line.substring("rename from ".length()).trim();
                } else if (line.startsWith("copy from ")) {
                    base = line.substring("copy from ".length()).trim();
                    copied = true;
                } else if (line.startsWith("--- a/") && base == null) {
                    base = line.substring("--- a/".length()).trim();
                }
            }
            String basePath = base == null ? file.path() : InspectionManifest.normalizePath(base);
            if (!InspectionManifest.isSafeRelativePath(basePath)) {
                return new Header(file.path(), false, false, false, false, true);
            }
            return new Header(basePath, added, deleted, copied, false, false);
        }

        boolean renamed(String path) {
            return !copied && !added && !deleted && !basePath.equals(path);
        }
    }

    private record AddedType(String fqn, FileAnalysis analysis, String kind) {}

    private record HunkMap(
            int oldStart, int oldCount, int newStart, int newCount, List<Integer> addedLines) {
        boolean oldContains(int line) {
            return oldCount > 0 && line >= oldStart && line < oldStart + oldCount;
        }

        boolean newContains(int line) {
            return newCount > 0 && line >= newStart && line < newStart + newCount;
        }

        int projectOldToNew(int oldLine) {
            return newStart + Math.max(0, oldLine - oldStart);
        }
    }

    private static final class FileAnalysis {
        private final InspectionManifest.FileTarget file;
        private final Header header;
        private final List<ProtoSchemaDelta.Change> changes = new ArrayList<>();
        private final Set<String> removedMessages = new LinkedHashSet<>();
        private final Set<String> removedEnums = new LinkedHashSet<>();
        private final Set<String> removedServices = new LinkedHashSet<>();
        private final Set<String> addedMessages = new LinkedHashSet<>();
        private final Set<String> addedEnums = new LinkedHashSet<>();
        private final Set<String> addedServices = new LinkedHashSet<>();
        private final Set<String> movedTypes = new LinkedHashSet<>();
        private String unparsed;
        private boolean deleted;
        private ProtoSchema baseSchema;
        private ProtoSchema headSchema;

        private FileAnalysis(InspectionManifest.FileTarget file, Header header) {
            this.file = file;
            this.header = header;
        }

        /** A schema the diff edits where it stands, so committed generated code should follow. */
        private boolean modifiedInPlace() {
            return !header.unparsed()
                    && !header.binary()
                    && !header.added()
                    && !header.deleted()
                    && !header.copied()
                    && !header.renamed(file.path());
        }
    }
}
