package com.jinloes.prpilot.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Engine-generated identifiers for changed files and hunks. Models may reference these identifiers
 * in an inspection ledger, but never define their own paths or target identity.
 */
final class InspectionManifest {
    private static final ObjectMapper JSON = new ObjectMapper();
    // Git separates diff records with LF (CRLF on some transports). Broader separators such as
    // U+2028 can appear inside source text, so neither splitting nor ^ may treat them as breaks.
    private static final Pattern FILE_START = Pattern.compile("(?md)^diff --git ");
    private static final Pattern DIFF_RECORD = Pattern.compile("\r?\n");
    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@");
    private static final Pattern CONTRACT_SIGNAL =
            Pattern.compile(
                    "(?i)(\\bpublic\\b|\\binterface\\b|\\brecord\\b|\\bimplements\\b|"
                            + "\\bauth(?:entication|orization)?\\b|\\bpermission\\b|\\btransaction\\b|"
                            + "\\bserialize\\b|\\bdeserialize\\b|@(?:Get|Post|Put|Patch|Delete)Mapping|"
                            + "\\bCREATE\\s+TABLE\\b|\\bALTER\\s+TABLE\\b)");
    private static final Set<String> HIGH_RISK_SUFFIXES =
            Set.of(".proto", ".graphql", ".graphqls", ".sql", ".json", ".yaml", ".yml", ".toml");

    private final List<FileTarget> files;
    private final Map<String, Target> targetsById;

    private InspectionManifest(List<FileTarget> files) {
        this.files = List.copyOf(files);
        Map<String, Target> targets = new LinkedHashMap<>();
        for (FileTarget file : files) {
            targets.put(file.id(), file);
            for (HunkTarget hunk : file.hunks()) {
                targets.put(hunk.id(), hunk);
            }
        }
        this.targetsById = Map.copyOf(targets);
    }

    static InspectionManifest fromDiff(String diff) {
        String source = diff == null ? "" : diff;
        Matcher matcher = FILE_START.matcher(source);
        List<Integer> starts = new ArrayList<>();
        while (matcher.find()) {
            starts.add(matcher.start());
        }
        List<FileTarget> files = new ArrayList<>();
        for (int index = 0; index < starts.size(); index++) {
            String section =
                    source.substring(
                            starts.get(index),
                            index + 1 < starts.size() ? starts.get(index + 1) : source.length());
            parseFile(section).ifPresent(files::add);
        }
        return new InspectionManifest(files);
    }

    List<FileTarget> files() {
        return files;
    }

    Optional<Target> target(String id) {
        return Optional.ofNullable(targetsById.get(id));
    }

    boolean containsTarget(String id) {
        return targetsById.containsKey(id);
    }

    Optional<HunkTarget> hunkFor(String path, int newLine) {
        if (path == null || newLine <= 0) {
            return Optional.empty();
        }
        return files.stream()
                .filter(file -> file.path().equals(normalizePath(path)))
                .flatMap(file -> file.hunks().stream())
                .filter(hunk -> hunk.changedNewLines().contains(newLine))
                .findFirst();
    }

    /**
     * Returns the new-side text (diff marker removed) of an added or context line, or empty when
     * the line is absent from the file's diff or the diff only removes it.
     */
    Optional<String> newSideLineText(String path, int newLine) {
        if (path == null || newLine <= 0) {
            return Optional.empty();
        }
        return files.stream()
                .filter(file -> file.path().equals(normalizePath(path)))
                .map(file -> file.newSideLines().get(newLine))
                .filter(text -> text != null)
                .findFirst();
    }

    String toPromptJson() {
        List<Map<String, Object>> encodedFiles = new ArrayList<>();
        for (FileTarget file : files) {
            List<Map<String, Object>> encodedHunks = new ArrayList<>();
            for (HunkTarget hunk : file.hunks()) {
                encodedHunks.add(
                        Map.of(
                                "id", hunk.id(),
                                "newStart", hunk.newStart(),
                                "changedNewLineRanges", compactRanges(hunk.changedNewLines()),
                                "highRisk", hunk.highRisk()));
            }
            encodedFiles.add(
                    Map.of(
                            "id", file.id(),
                            "path", file.path(),
                            "highRisk", file.highRisk(),
                            "hunks", encodedHunks));
        }
        try {
            return JSON.writeValueAsString(Map.of("files", encodedFiles));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize inspection manifest", exception);
        }
    }

    String diffForTargets(Set<String> targetIds) {
        Set<String> paths = new LinkedHashSet<>();
        for (String targetId : targetIds) {
            Target target = targetsById.get(targetId);
            if (target != null) {
                paths.add(target.path());
            }
        }
        return files.stream()
                .filter(file -> paths.contains(file.path()))
                .map(FileTarget::diff)
                .reduce((first, second) -> first + "\n" + second)
                .orElse("");
    }

    private static Optional<FileTarget> parseFile(String section) {
        String path = "";
        for (String line : DIFF_RECORD.split(section)) {
            if (line.startsWith("+++ b/")) {
                path = normalizePath(line.substring(6).trim());
                break;
            }
        }
        if (path.isBlank()) {
            String header = DIFF_RECORD.split(section, 2)[0];
            int destination = header.indexOf(" b/");
            if (destination >= 0) {
                path = normalizePath(header.substring(destination + 3).trim());
            }
        }
        if (!isSafeRelativePath(path)) {
            return Optional.empty();
        }

        List<HunkTarget> hunks = new ArrayList<>();
        Map<Integer, String> newSideLines = new LinkedHashMap<>();
        String[] lines = DIFF_RECORD.split(section);
        for (int index = 0; index < lines.length; ) {
            Matcher header = HUNK_HEADER.matcher(lines[index]);
            if (!header.find()) {
                index++;
                continue;
            }
            int oldStart = Integer.parseInt(header.group(1));
            int newStart = Integer.parseInt(header.group(3));
            int newLine = newStart;
            Set<Integer> changedNewLines = new LinkedHashSet<>();
            boolean highRisk = isHighRiskPath(path);
            int next = index + 1;
            while (next < lines.length && !lines[next].startsWith("@@")) {
                // File headers precede the first hunk, so inside a hunk "+++x" and "---x" are an
                // added "++x" and a removed "--x", classified by their first character alone.
                String line = lines[next];
                if (line.startsWith("+")) {
                    changedNewLines.add(newLine);
                    newSideLines.put(newLine, line.substring(1));
                    highRisk |= CONTRACT_SIGNAL.matcher(line.substring(1)).find();
                    newLine++;
                } else if (line.startsWith("-")) {
                    highRisk |= CONTRACT_SIGNAL.matcher(line.substring(1)).find();
                } else if (!line.startsWith("\\")) {
                    newSideLines.put(newLine, line.isEmpty() ? "" : line.substring(1));
                    newLine++;
                }
                next++;
            }
            String hunkId = stableId("H", path + ":" + oldStart + ":" + newStart);
            hunks.add(
                    new HunkTarget(
                            hunkId,
                            stableId("F", path),
                            path,
                            oldStart,
                            newStart,
                            Collections.unmodifiableSet(new LinkedHashSet<>(changedNewLines)),
                            highRisk));
            index = next;
        }
        boolean highRisk = isHighRiskPath(path) || hunks.stream().anyMatch(HunkTarget::highRisk);
        return Optional.of(
                new FileTarget(
                        stableId("F", path),
                        path,
                        highRisk,
                        List.copyOf(hunks),
                        section,
                        Collections.unmodifiableMap(newSideLines)));
    }

    static boolean isSafeRelativePath(String path) {
        if (path == null || path.isBlank() || path.indexOf('\0') >= 0) {
            return false;
        }
        String normalized = normalizePath(path);
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:/.*")) {
            return false;
        }
        int depth = 0;
        for (String part : normalized.split("/")) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                depth--;
                if (depth < 0) {
                    return false;
                }
            } else {
                depth++;
            }
        }
        return true;
    }

    static String normalizePath(String path) {
        String normalized = path == null ? "" : path.trim().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private static boolean isHighRiskPath(String path) {
        String lower = path.toLowerCase();
        if (lower.contains("/migration") || lower.contains("/schema") || lower.contains("/auth")) {
            return true;
        }
        return HIGH_RISK_SUFFIXES.stream().anyMatch(lower::endsWith);
    }

    private static List<String> compactRanges(Set<Integer> lines) {
        if (lines.isEmpty()) {
            return List.of();
        }
        List<String> ranges = new ArrayList<>();
        int start = -1;
        int previous = -1;
        for (int line : lines) {
            if (start < 0) {
                start = line;
                previous = line;
            } else if (line == previous + 1) {
                previous = line;
            } else {
                ranges.add(start == previous ? Integer.toString(start) : start + "-" + previous);
                start = line;
                previous = line;
            }
        }
        ranges.add(start == previous ? Integer.toString(start) : start + "-" + previous);
        return ranges;
    }

    private static String stableId(String prefix, String value) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            return prefix + "-" + HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    sealed interface Target permits FileTarget, HunkTarget {
        String id();

        String path();

        boolean highRisk();
    }

    /** {@code newSideLines} maps each added or context new-file line number to its text. */
    record FileTarget(
            String id,
            String path,
            boolean highRisk,
            List<HunkTarget> hunks,
            String diff,
            Map<Integer, String> newSideLines)
            implements Target {}

    record HunkTarget(
            String id,
            String fileId,
            String path,
            int oldStart,
            int newStart,
            Set<Integer> changedNewLines,
            boolean highRisk)
            implements Target {}
}
