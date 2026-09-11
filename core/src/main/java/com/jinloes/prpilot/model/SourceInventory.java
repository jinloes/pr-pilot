package com.jinloes.prpilot.model;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Versioned source-byte evidence. This protocol deliberately has no readiness state. */
public final class SourceInventory {
    public static final int MAX_NODES = 100_000;
    public static final int MAX_FILES = 10_000;
    public static final long MAX_BYTES = 100L * 1024 * 1024;
    public static final int MAX_JSON_BYTES = 1024 * 1024;
    public static final String TOOL = "pr_pilot_source_inventory";
    public static final Comparator<String> UTF8 =
            (a, b) -> Arrays.compareUnsigned(utf8(a), utf8(b));

    private SourceInventory() {}

    @Retention(RetentionPolicy.RUNTIME)
    private @interface Nullable {}

    public enum Operation {
        DISCOVER,
        VERIFY
    }

    public enum Status {
        DISCOVERED,
        VFS_VERIFIED,
        BLOCKED,
        UNSUPPORTED
    }

    public enum Scope {
        WORKTREE,
        EXTERNAL,
        UNRESOLVED,
        NON_LOCAL
    }

    public enum Kind {
        FILE,
        SYMLINK,
        SPECIAL
    }

    public enum Membership {
        SOURCE,
        IDE_IGNORED,
        EXCLUDED,
        LIBRARY_SOURCE,
        OUTSIDE_SOURCE,
        UNKNOWN
    }

    public enum ReasonCode {
        BAD_REQUEST,
        WRONG_PROJECT,
        UNSUPPORTED_IDE,
        UNSUPPORTED_API,
        LIMIT,
        IO_ERROR,
        UNSAFE_PATH,
        UNKNOWN_MEMBERSHIP,
        UNMAPPED_SOURCE_ROOT,
        UNLOADED_MODULE,
        EMPTY_SOURCE_MODEL,
        EXTERNAL_SOURCE_ROOT,
        UNRESOLVED_SOURCE_ROOT,
        STALE_DISCOVERY,
        MODEL_CHANGED,
        INVENTORY_CHANGED,
        MANIFEST_MISMATCH,
        CONTENT_MISMATCH
    }

    public static class DiscoverRequest {
        private Integer schemaVersion;
        private Operation operation;
        private String projectPath;
        private String nonce;

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
    }

    public static class VerifyRequest extends DiscoverRequest {
        private String discoveryId;
        private List<Hash> files;

        public String getDiscoveryId() {
            return discoveryId;
        }

        public void setDiscoveryId(String value) {
            discoveryId = value;
        }

        public List<Hash> getFiles() {
            return files;
        }

        public void setFiles(List<Hash> value) {
            files = value;
        }
    }

    public static class Hash {
        private String path;
        private String sha256;

        public Hash() {}

        public Hash(String path, String sha256) {
            this.path = path;
            this.sha256 = sha256;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String value) {
            path = value;
        }

        public String getSha256() {
            return sha256;
        }

        public void setSha256(String value) {
            sha256 = value;
        }
    }

    public static class Response {
        private Integer schemaVersion;
        private Operation operation;
        private String nonce;
        private Status status;
        private List<Reason> reasons;
        @Nullable private Discovery discovery;
        @Nullable private Coverage coverage;

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

        public String getNonce() {
            return nonce;
        }

        public void setNonce(String value) {
            nonce = value;
        }

        public Status getStatus() {
            return status;
        }

        public void setStatus(Status value) {
            status = value;
        }

        public List<Reason> getReasons() {
            return reasons;
        }

        public void setReasons(List<Reason> value) {
            reasons = value;
        }

        public Discovery getDiscovery() {
            return discovery;
        }

        public void setDiscovery(Discovery value) {
            discovery = value;
        }

        public Coverage getCoverage() {
            return coverage;
        }

        public void setCoverage(Coverage value) {
            coverage = value;
        }
    }

    public static class Reason {
        private ReasonCode code;
        @Nullable private String path;

        public Reason() {}

        public Reason(ReasonCode code, String path) {
            this.code = code;
            this.path = path;
        }

        public ReasonCode getCode() {
            return code;
        }

        public void setCode(ReasonCode value) {
            code = value;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String value) {
            path = value;
        }
    }

    public static class Discovery {
        private String discoveryId;
        private String projectPath;
        private String projectInstanceId;
        private String ideBuild;
        private Epochs epochs;
        private Model model;
        private List<Entry> entries;

        public String getDiscoveryId() {
            return discoveryId;
        }

        public void setDiscoveryId(String value) {
            discoveryId = value;
        }

        public String getProjectPath() {
            return projectPath;
        }

        public void setProjectPath(String value) {
            projectPath = value;
        }

        public String getProjectInstanceId() {
            return projectInstanceId;
        }

        public void setProjectInstanceId(String value) {
            projectInstanceId = value;
        }

        public String getIdeBuild() {
            return ideBuild;
        }

        public void setIdeBuild(String value) {
            ideBuild = value;
        }

        public Epochs getEpochs() {
            return epochs;
        }

        public void setEpochs(Epochs value) {
            epochs = value;
        }

        public Model getModel() {
            return model;
        }

        public void setModel(Model value) {
            model = value;
        }

        public List<Entry> getEntries() {
            return entries;
        }

        public void setEntries(List<Entry> value) {
            entries = value;
        }
    }

    public static class Epochs {
        private String roots;
        private String modules;
        private String vfs;
        private String fileTypes;

        public String getRoots() {
            return roots;
        }

        public void setRoots(String value) {
            roots = value;
        }

        public String getModules() {
            return modules;
        }

        public void setModules(String value) {
            modules = value;
        }

        public String getVfs() {
            return vfs;
        }

        public void setVfs(String value) {
            vfs = value;
        }

        public String getFileTypes() {
            return fileTypes;
        }

        public void setFileTypes(String value) {
            fileTypes = value;
        }
    }

    public static class Model {
        private List<Content> contents;
        private List<Location> dependencySources;
        private List<Location> dependencyClasses;
        private String ignoredPatterns;
        private List<String> unloadedModules;

        public List<Content> getContents() {
            return contents;
        }

        public void setContents(List<Content> value) {
            contents = value;
        }

        public List<Location> getDependencySources() {
            return dependencySources;
        }

        public void setDependencySources(List<Location> value) {
            dependencySources = value;
        }

        public List<Location> getDependencyClasses() {
            return dependencyClasses;
        }

        public void setDependencyClasses(List<Location> value) {
            dependencyClasses = value;
        }

        public String getIgnoredPatterns() {
            return ignoredPatterns;
        }

        public void setIgnoredPatterns(String value) {
            ignoredPatterns = value;
        }

        public List<String> getUnloadedModules() {
            return unloadedModules;
        }

        public void setUnloadedModules(List<String> value) {
            unloadedModules = value;
        }
    }

    public static class Content {
        private String module;
        private Location location;
        private List<Source> sources;
        private List<Location> exclusions;
        private List<String> excludePatterns;

        public String getModule() {
            return module;
        }

        public void setModule(String value) {
            module = value;
        }

        public Location getLocation() {
            return location;
        }

        public void setLocation(Location value) {
            location = value;
        }

        public List<Source> getSources() {
            return sources;
        }

        public void setSources(List<Source> value) {
            sources = value;
        }

        public List<Location> getExclusions() {
            return exclusions;
        }

        public void setExclusions(List<Location> value) {
            exclusions = value;
        }

        public List<String> getExcludePatterns() {
            return excludePatterns;
        }

        public void setExcludePatterns(List<String> value) {
            excludePatterns = value;
        }
    }

    public static class Location {
        private String url;
        @Nullable private String nativePath;
        private Scope scope;

        public String getUrl() {
            return url;
        }

        public void setUrl(String value) {
            url = value;
        }

        public String getNativePath() {
            return nativePath;
        }

        public void setNativePath(String value) {
            nativePath = value;
        }

        public Scope getScope() {
            return scope;
        }

        public void setScope(Scope value) {
            scope = value;
        }
    }

    public static class Source {
        private Location location;
        private String typeClass;
        private Boolean test;
        @Nullable private Boolean generated;

        public Location getLocation() {
            return location;
        }

        public void setLocation(Location value) {
            location = value;
        }

        public String getTypeClass() {
            return typeClass;
        }

        public void setTypeClass(String value) {
            typeClass = value;
        }

        public Boolean getTest() {
            return test;
        }

        public void setTest(Boolean value) {
            test = value;
        }

        public Boolean getGenerated() {
            return generated;
        }

        public void setGenerated(Boolean value) {
            generated = value;
        }
    }

    public static class Entry {
        private String path;
        private Kind kind;
        private Membership membership;
        @Nullable private String sourceRootUrl;
        private List<String> modules;
        private Boolean generated;
        private Boolean test;

        public String getPath() {
            return path;
        }

        public void setPath(String value) {
            path = value;
        }

        public Kind getKind() {
            return kind;
        }

        public void setKind(Kind value) {
            kind = value;
        }

        public Membership getMembership() {
            return membership;
        }

        public void setMembership(Membership value) {
            membership = value;
        }

        public String getSourceRootUrl() {
            return sourceRootUrl;
        }

        public void setSourceRootUrl(String value) {
            sourceRootUrl = value;
        }

        public List<String> getModules() {
            return modules;
        }

        public void setModules(List<String> value) {
            modules = value;
        }

        public Boolean getGenerated() {
            return generated;
        }

        public void setGenerated(Boolean value) {
            generated = value;
        }

        public Boolean getTest() {
            return test;
        }

        public void setTest(Boolean value) {
            test = value;
        }
    }

    public static class Coverage {
        private String discoveryId;
        private String projectPath;
        private String projectInstanceId;
        private String ideBuild;
        private Epochs epochs;
        private Integer fileCount;
        private String sourceManifestSha256;

        public String getDiscoveryId() {
            return discoveryId;
        }

        public void setDiscoveryId(String value) {
            discoveryId = value;
        }

        public String getProjectPath() {
            return projectPath;
        }

        public void setProjectPath(String value) {
            projectPath = value;
        }

        public String getProjectInstanceId() {
            return projectInstanceId;
        }

        public void setProjectInstanceId(String value) {
            projectInstanceId = value;
        }

        public String getIdeBuild() {
            return ideBuild;
        }

        public void setIdeBuild(String value) {
            ideBuild = value;
        }

        public Epochs getEpochs() {
            return epochs;
        }

        public void setEpochs(Epochs value) {
            epochs = value;
        }

        public Integer getFileCount() {
            return fileCount;
        }

        public void setFileCount(Integer value) {
            fileCount = value;
        }

        public String getSourceManifestSha256() {
            return sourceManifestSha256;
        }

        public void setSourceManifestSha256(String value) {
            sourceManifestSha256 = value;
        }
    }

    public static byte[] utf8(String value) {
        require(
                value != null && StandardCharsets.UTF_8.newEncoder().canEncode(value),
                "Invalid UTF-8");
        return value.getBytes(StandardCharsets.UTF_8);
    }

    public static void path(String value) {
        utf8(value);
        require(!value.isEmpty() && !value.contains("\\") && !value.contains("\0"), "Unsafe path");
        for (String component : value.split("/", -1)) {
            require(
                    !component.isEmpty() && !component.equals(".") && !component.equals(".."),
                    "Unsafe path");
        }
    }

    public static void absolute(String value) {
        utf8(value);
        require(
                !value.contains("\0")
                        && Path.of(value).isAbsolute()
                        && Path.of(value).normalize().toString().equals(value),
                "Noncanonical absolute path");
    }

    public static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String manifest(List<Hash> files) {
        require(
                files != null && !files.isEmpty() && files.size() <= MAX_FILES,
                "Invalid manifest size");
        MessageDigest digest = digest("SHA-256");
        List<Hash> sorted = new ArrayList<>(files);
        sorted.sort(Comparator.comparing(Hash::getPath, UTF8));
        String previous = null;
        for (Hash file : sorted) {
            validate(file);
            require(!file.path.equals(previous), "Duplicate hash path");
            byte[] name = utf8(file.path);
            digest.update(ByteBuffer.allocate(4).putInt(name.length).array());
            digest.update(name);
            digest.update(HexFormat.of().parseHex(file.sha256));
            previous = file.path;
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Validate JSON types/presence before Jackson is allowed to bind (and potentially coerce)
     * beans.
     */
    public static void validateWire(Object value, Class<?> type) {
        wire(value, type);
    }

    private static void wire(Object value, Type type) {
        require(value != null, "Missing required value");
        if (type instanceof ParameterizedType listType) {
            require(value instanceof List<?>, "Expected array");
            List<?> list = (List<?>) value;
            require(list.size() <= MAX_NODES, "Array limit");
            for (Object item : list) {
                wire(item, listType.getActualTypeArguments()[0]);
            }
            return;
        }
        Class<?> clazz = (Class<?>) type;
        if (clazz == String.class) {
            require(value instanceof String, "Expected string");
            utf8((String) value);
        } else if (clazz == Boolean.class) {
            require(value instanceof Boolean, "Expected boolean");
        } else if (clazz == Integer.class) {
            require(value instanceof Integer, "Expected integer");
        } else if (clazz.isEnum()) {
            require(
                    value instanceof String
                            && Arrays.stream(clazz.getEnumConstants())
                                    .anyMatch(v -> v.toString().equals(value)),
                    "Unknown enum");
        } else {
            require(value instanceof Map<?, ?>, "Expected object");
            Map<?, ?> map = (Map<?, ?>) value;
            List<Field> fields = fields(clazz);
            require(map.size() == fields.size(), "Unknown or missing fields");
            for (Field field : fields) {
                require(map.containsKey(field.getName()), "Missing " + field.getName());
                Object item = map.get(field.getName());
                if (item == null) {
                    require(field.isAnnotationPresent(Nullable.class), "Unexpected null");
                } else {
                    wire(item, field.getGenericType());
                }
            }
        }
    }

    private static List<Field> fields(Class<?> type) {
        require(type.getEnclosingClass() == SourceInventory.class, "Not a protocol bean");
        List<Field> result = new ArrayList<>();
        if (type.getSuperclass() != Object.class) {
            result.addAll(fields(type.getSuperclass()));
        }
        result.addAll(Arrays.asList(type.getDeclaredFields()));
        return result;
    }

    /** Schema generation shares the exact required/nullable field definitions with validation. */
    public static Map<String, Object> schema(Class<?> type) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Field field : fields(type)) {
            Object definition = schemaType(field.getGenericType());
            if (field.isAnnotationPresent(Nullable.class)) {
                definition = Map.of("anyOf", List.of(definition, Map.of("type", "null")));
            }
            properties.put(field.getName(), definition);
        }
        return Map.of(
                "type",
                "object",
                "properties",
                properties,
                "required",
                new ArrayList<>(properties.keySet()),
                "additionalProperties",
                false);
    }

    private static Object schemaType(Type type) {
        if (type instanceof ParameterizedType list) {
            return Map.of(
                    "type",
                    "array",
                    "items",
                    schemaType(list.getActualTypeArguments()[0]),
                    "maxItems",
                    MAX_NODES);
        }
        Class<?> clazz = (Class<?>) type;
        if (clazz == String.class) {
            return Map.of("type", "string");
        }
        if (clazz == Boolean.class) {
            return Map.of("type", "boolean");
        }
        if (clazz == Integer.class) {
            return Map.of("type", "integer");
        }
        if (clazz.isEnum()) {
            return Map.of(
                    "type",
                    "string",
                    "enum",
                    Arrays.stream(clazz.getEnumConstants()).map(Object::toString).toList());
        }
        return schema(clazz);
    }

    /** Checks nested bean invariants; wire readers must additionally call validateWire. */
    public static void validate(Object bean) {
        require(bean != null, "Null bean");
        try {
            for (Field field : fields(bean.getClass())) {
                Object value = field.get(bean);
                if (value == null) {
                    require(
                            field.isAnnotationPresent(Nullable.class),
                            "Missing " + field.getName());
                } else if (value instanceof List<?> list) {
                    require(list.size() <= MAX_NODES, "Array limit");
                    for (Object item : list) {
                        require(item != null, "Null array item");
                        if (item instanceof String text) {
                            utf8(text);
                        } else {
                            validate(item);
                        }
                    }
                    ordered(list);
                } else if (value instanceof String text) {
                    utf8(text);
                } else if (value.getClass().getEnclosingClass() == SourceInventory.class
                        && !value.getClass().isEnum()) {
                    validate(value);
                }
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        if (bean instanceof DiscoverRequest request) {
            require(request.schemaVersion == 2, "Version");
            uuid(request.nonce);
            absolute(request.projectPath);
            require(
                    request.operation
                            == (bean instanceof VerifyRequest
                                    ? Operation.VERIFY
                                    : Operation.DISCOVER),
                    "Operation");
        }
        if (bean instanceof VerifyRequest request) {
            uuid(request.discoveryId);
            manifest(request.files);
        }
        if (bean instanceof Hash hash) {
            path(hash.path);
            sha256(hash.sha256);
        }
        if (bean instanceof Reason reason && reason.path != null) {
            path(reason.path);
        }
        if (bean instanceof Epochs epochs) {
            for (String epoch :
                    List.of(epochs.roots, epochs.modules, epochs.vfs, epochs.fileTypes)) {
                require(epoch.matches("0|[1-9][0-9]*"), "Epoch");
            }
        }
        if (bean instanceof Location location) {
            require(!location.url.isEmpty(), "URL");
            boolean resolved = location.scope == Scope.WORKTREE || location.scope == Scope.EXTERNAL;
            require(resolved == (location.nativePath != null), "Location scope");
            if (resolved) {
                absolute(location.nativePath);
            }
        }
        if (bean instanceof Content content) {
            require(!content.module.isEmpty(), "Module identity");
        }
        if (bean instanceof Source source) {
            require(!source.typeClass.isEmpty(), "Source type identity");
        }
        if (bean instanceof Entry entry) {
            path(entry.path);
            if (entry.membership == Membership.SOURCE) {
                require(
                        entry.sourceRootUrl != null
                                && !entry.sourceRootUrl.isEmpty()
                                && !entry.modules.isEmpty()
                                && entry.modules.stream().noneMatch(String::isEmpty),
                        "Missing SOURCE identity");
            } else {
                require(
                        entry.sourceRootUrl == null && !entry.generated && !entry.test,
                        "Non-source flags");
            }
        }
        if (bean instanceof Discovery discovery) {
            uuid(discovery.discoveryId);
            uuid(discovery.projectInstanceId);
            absolute(discovery.projectPath);
            require(!discovery.ideBuild.isEmpty(), "Build");
        }
        if (bean instanceof Coverage coverage) {
            uuid(coverage.discoveryId);
            uuid(coverage.projectInstanceId);
            absolute(coverage.projectPath);
            require(
                    !coverage.ideBuild.isEmpty()
                            && coverage.fileCount > 0
                            && coverage.fileCount <= MAX_FILES,
                    "Coverage");
            sha256(coverage.sourceManifestSha256);
        }
        if (bean instanceof Response response) {
            require(response.schemaVersion == 2, "Version");
            uuid(response.nonce);
            if (response.status == Status.DISCOVERED) {
                require(
                        response.operation == Operation.DISCOVER
                                && response.discovery != null
                                && response.coverage == null
                                && response.reasons.isEmpty(),
                        "Discovery response");
            } else if (response.status == Status.VFS_VERIFIED) {
                require(
                        response.operation == Operation.VERIFY
                                && response.discovery == null
                                && response.coverage != null
                                && response.reasons.isEmpty(),
                        "Coverage response");
            } else {
                require(
                        response.discovery == null
                                && response.coverage == null
                                && !response.reasons.isEmpty(),
                        "Failure response");
            }
        }
    }

    public static void ordered(List<?> list) {
        List<String> previous = null;
        for (Object item : list) {
            List<String> current = identity(item);
            if (previous != null) {
                int comparison = 0;
                for (int i = 0; i < current.size() && comparison == 0; i++) {
                    comparison = UTF8.compare(previous.get(i), current.get(i));
                }
                require(comparison < 0, "Duplicate or noncanonical identity");
            }
            previous = current;
        }
    }

    public static List<String> identity(Object bean) {
        if (bean instanceof String text) {
            return List.of(text);
        }
        if (bean instanceof Hash hash) {
            return List.of(hash.path);
        }
        if (bean instanceof Entry entry) {
            return List.of(entry.path);
        }
        if (bean instanceof Location location) {
            return List.of(location.url);
        }
        if (bean instanceof Content content) {
            return List.of(content.module, content.location.url);
        }
        if (bean instanceof Source source) {
            return List.of(source.location.url, source.typeClass);
        }
        if (bean instanceof Reason reason) {
            return List.of(reason.code.name(), reason.path == null ? "" : reason.path);
        }
        throw new IllegalArgumentException("Unknown identity");
    }

    public static <T> List<T> sorted(List<T> items) {
        List<T> result = new ArrayList<>(items);
        result.sort(
                (a, b) -> {
                    List<String> left = identity(a), right = identity(b);
                    for (int i = 0; i < left.size(); i++) {
                        int comparison = UTF8.compare(left.get(i), right.get(i));
                        if (comparison != 0) {
                            return comparison;
                        }
                    }
                    return 0;
                });
        ordered(result);
        return result;
    }

    private static void uuid(String value) {
        require(
                value != null
                        && value.matches(
                                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                        && UUID.fromString(value).toString().equals(value),
                "UUID");
    }

    private static void sha256(String value) {
        require(value != null && value.matches("[0-9a-f]{64}"), "SHA-256");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
