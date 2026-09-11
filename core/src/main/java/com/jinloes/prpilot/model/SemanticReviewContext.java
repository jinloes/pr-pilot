package com.jinloes.prpilot.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Defensive prompt data, never a serialized capability to run an authoritative review. */
public final class SemanticReviewContext {
    public static final String TOOL = "pr_pilot_review_snapshot";
    private String evidence = "";
    private List<String> limitations = List.of();

    public String getEvidence() {
        return evidence;
    }

    public void setEvidence(String evidence) {
        require(
                evidence != null && SourceInventory.utf8(evidence).length <= 65536,
                "Evidence limit");
        this.evidence = evidence;
    }

    public List<String> getLimitations() {
        return limitations;
    }

    public void setLimitations(List<String> limitations) {
        this.limitations = copyLimitations(limitations);
    }

    private static List<String> copyLimitations(List<String> values) {
        require(values != null && values.size() <= 64, "Limitation count");
        int bytes = 0;
        for (String value : values) {
            require(value != null && !value.isBlank(), "Empty limitation");
            bytes += SourceInventory.utf8(value).length;
            require(bytes <= 8192, "Limitation bytes");
        }
        return List.copyOf(values);
    }

    public enum Operation {
        STATUS,
        CAPTURE,
        VERIFY
    }

    public enum Status {
        ARMING,
        MANUAL_SYNC_REQUIRED,
        NOT_READY,
        ELIGIBLE,
        READY,
        UNSUPPORTED
    }

    public static final class Range {
        private String path;
        private Integer startLine;
        private Integer endLine;

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public Integer getStartLine() {
            return startLine;
        }

        public void setStartLine(Integer startLine) {
            this.startLine = startLine;
        }

        public Integer getEndLine() {
            return endLine;
        }

        public void setEndLine(Integer endLine) {
            this.endLine = endLine;
        }
    }

    public static final class Request {
        private Integer schemaVersion;
        private Operation operation;
        private String projectPath;
        private String nonce;
        private String discoveryId;
        private List<SourceInventory.Hash> files;
        private List<Range> changedRanges;
        private String snapshotId;

        public Integer getSchemaVersion() {
            return schemaVersion;
        }

        public void setSchemaVersion(Integer value) {
            schemaVersion = value;
        }

        public Operation getOperation() {
            return operation;
        }

        public void setOperation(Operation value) {
            operation = value;
        }

        public String getProjectPath() {
            return projectPath;
        }

        public void setProjectPath(String value) {
            projectPath = value;
        }

        public String getNonce() {
            return nonce;
        }

        public void setNonce(String value) {
            nonce = value;
        }

        public String getDiscoveryId() {
            return discoveryId;
        }

        public void setDiscoveryId(String value) {
            discoveryId = value;
        }

        public List<SourceInventory.Hash> getFiles() {
            return copyHashes(files);
        }

        public void setFiles(List<SourceInventory.Hash> value) {
            files = copyHashes(value);
        }

        public List<Range> getChangedRanges() {
            return copyRanges(changedRanges);
        }

        public void setChangedRanges(List<Range> value) {
            changedRanges = copyRanges(value);
        }

        public String getSnapshotId() {
            return snapshotId;
        }

        public void setSnapshotId(String value) {
            snapshotId = value;
        }
    }

    public static final class Declaration {
        private String path;
        private String qualifiedName;
        private Integer line;

        public String getPath() {
            return path;
        }

        public void setPath(String value) {
            path = value;
        }

        public String getQualifiedName() {
            return qualifiedName;
        }

        public void setQualifiedName(String value) {
            qualifiedName = value;
        }

        public Integer getLine() {
            return line;
        }

        public void setLine(Integer value) {
            line = value;
        }
    }

    public static final class Snapshot {
        private Integer schemaVersion = 2;
        private String nonce;
        private String projectPath;
        private String ideBuild;
        private String projectInstanceId;
        private String snapshotId;
        private Status status;
        private List<String> reasons = List.of();
        private String coverageIdentity;
        private String sourceDigest;
        private String modelDigest;
        private String settingsDigest;
        private String epochs;
        private List<Declaration> declarations = List.of();
        private List<String> declarationLimitations = List.of();

        public Integer getSchemaVersion() {
            return schemaVersion;
        }

        public void setSchemaVersion(Integer value) {
            schemaVersion = value;
        }

        public String getNonce() {
            return nonce;
        }

        public void setNonce(String value) {
            nonce = value;
        }

        public String getProjectPath() {
            return projectPath;
        }

        public void setProjectPath(String value) {
            projectPath = value;
        }

        public String getIdeBuild() {
            return ideBuild;
        }

        public void setIdeBuild(String value) {
            ideBuild = value;
        }

        public String getProjectInstanceId() {
            return projectInstanceId;
        }

        public void setProjectInstanceId(String value) {
            projectInstanceId = value;
        }

        public String getSnapshotId() {
            return snapshotId;
        }

        public void setSnapshotId(String value) {
            snapshotId = value;
        }

        public Status getStatus() {
            return status;
        }

        public void setStatus(Status value) {
            status = value;
        }

        public List<String> getReasons() {
            return reasons;
        }

        public void setReasons(List<String> value) {
            reasons = List.copyOf(value);
        }

        public String getCoverageIdentity() {
            return coverageIdentity;
        }

        public void setCoverageIdentity(String value) {
            coverageIdentity = value;
        }

        public String getSourceDigest() {
            return sourceDigest;
        }

        public void setSourceDigest(String value) {
            sourceDigest = value;
        }

        public String getModelDigest() {
            return modelDigest;
        }

        public void setModelDigest(String value) {
            modelDigest = value;
        }

        public String getSettingsDigest() {
            return settingsDigest;
        }

        public void setSettingsDigest(String value) {
            settingsDigest = value;
        }

        public String getEpochs() {
            return epochs;
        }

        public void setEpochs(String value) {
            epochs = value;
        }

        public List<Declaration> getDeclarations() {
            return copyDeclarations(declarations);
        }

        public void setDeclarations(List<Declaration> value) {
            declarations = copyDeclarations(value);
        }

        public List<String> getDeclarationLimitations() {
            return declarationLimitations;
        }

        public void setDeclarationLimitations(List<String> value) {
            declarationLimitations = copyLimitations(value);
        }
    }

    private static List<Declaration> copyDeclarations(List<Declaration> values) {
        List<Declaration> result = new ArrayList<>();
        for (Declaration value : values) {
            Declaration copy = new Declaration();
            copy.setPath(value.getPath());
            copy.setQualifiedName(value.getQualifiedName());
            copy.setLine(value.getLine());
            result.add(copy);
        }
        return List.copyOf(result);
    }

    private static List<SourceInventory.Hash> copyHashes(List<SourceInventory.Hash> values) {
        if (values == null) return null;
        List<SourceInventory.Hash> result = new ArrayList<>();
        for (SourceInventory.Hash value : values) {
            SourceInventory.Hash copy = new SourceInventory.Hash();
            copy.setPath(value.getPath());
            copy.setSha256(value.getSha256());
            result.add(copy);
        }
        return List.copyOf(result);
    }

    private static List<Range> copyRanges(List<Range> values) {
        if (values == null) return null;
        List<Range> result = new ArrayList<>();
        for (Range value : values) {
            Range copy = new Range();
            copy.setPath(value.getPath());
            copy.setStartLine(value.getStartLine());
            copy.setEndLine(value.getEndLine());
            result.add(copy);
        }
        return List.copyOf(result);
    }

    public static void validate(Request request) {
        require(
                request != null && Integer.valueOf(2).equals(request.schemaVersion),
                "Schema 2 required");
        require(request.operation != null, "Operation required");
        SourceInventory.absolute(request.projectPath);
        uuid(request.nonce);
        if (request.operation == Operation.STATUS) {
            require(
                    request.discoveryId == null
                            && request.files == null
                            && request.changedRanges == null
                            && request.snapshotId == null,
                    "STATUS fields");
        } else {
            uuid(request.discoveryId);
            require(
                    request.files != null && request.files.size() <= SourceInventory.MAX_FILES,
                    "Complete files required");
            for (SourceInventory.Hash hash : request.files) SourceInventory.validate(hash);
            SourceInventory.ordered(request.files);
            require(
                    request.changedRanges != null && request.changedRanges.size() <= 20,
                    "Range limit");
            for (Range range : request.changedRanges) {
                SourceInventory.path(range.path);
                require(
                        range.startLine != null
                                && range.endLine != null
                                && range.startLine >= 1
                                && range.endLine >= range.startLine,
                        "Range bounds");
            }
            if (request.operation == Operation.VERIFY) uuid(request.snapshotId);
            else require(request.snapshotId == null, "CAPTURE snapshotId");
        }
    }

    public static void validateWire(Object value) {
        require(value instanceof Map<?, ?>, "Object required");
        Map<?, ?> object = (Map<?, ?>) value;
        Set<String> expected =
                new java.util.HashSet<>(
                        Set.of("schemaVersion", "operation", "projectPath", "nonce"));
        require(object.get("operation") instanceof String, "Operation required");
        Operation operation = Operation.valueOf((String) object.get("operation"));
        if (operation != Operation.STATUS)
            expected.addAll(Set.of("discoveryId", "files", "changedRanges"));
        if (operation == Operation.VERIFY) expected.add("snapshotId");
        require(object.keySet().equals(expected), "Unknown or missing request fields");
        require(object.get("schemaVersion") instanceof Integer, "Integer schema required");
        for (String key : Set.of("nonce", "projectPath"))
            require(object.get(key) instanceof String, key);
        if (operation != Operation.STATUS) {
            require(
                    object.get("discoveryId") instanceof String
                            && object.get("files") instanceof List<?>
                            && object.get("changedRanges") instanceof List<?>,
                    "Capture types");
            for (Object hash : (List<?>) object.get("files"))
                SourceInventory.validateWire(hash, SourceInventory.Hash.class);
            for (Object item : (List<?>) object.get("changedRanges")) {
                require(item instanceof Map<?, ?>, "Range object");
                Map<?, ?> range = (Map<?, ?>) item;
                require(
                        range.keySet().equals(Set.of("path", "startLine", "endLine"))
                                && range.get("path") instanceof String
                                && range.get("startLine") instanceof Integer
                                && range.get("endLine") instanceof Integer,
                        "Range types");
            }
        }
        if (operation == Operation.VERIFY)
            require(object.get("snapshotId") instanceof String, "Snapshot ID");
    }

    public static Map<String, Object> inputProperties() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", Map.of("type", "integer", "const", 2));
        result.put(
                "operation",
                Map.of("type", "string", "enum", List.of("STATUS", "CAPTURE", "VERIFY")));
        for (String name : List.of("projectPath", "nonce", "discoveryId", "snapshotId")) {
            result.put(name, Map.of("type", "string"));
        }
        result.put(
                "files",
                Map.of(
                        "type",
                        "array",
                        "maxItems",
                        SourceInventory.MAX_FILES,
                        "items",
                        SourceInventory.schema(SourceInventory.Hash.class)));
        result.put(
                "changedRanges",
                Map.of(
                        "type",
                        "array",
                        "maxItems",
                        20,
                        "items",
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of(
                                        "path",
                                        Map.of("type", "string"),
                                        "startLine",
                                        Map.of("type", "integer", "minimum", 1),
                                        "endLine",
                                        Map.of("type", "integer", "minimum", 1)),
                                "required",
                                List.of("path", "startLine", "endLine"),
                                "additionalProperties",
                                false)));
        return result;
    }

    private static void uuid(String text) {
        require(text != null && UUID.fromString(text).toString().equals(text), "UUID required");
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
}
