package com.jinloes.prpilot.review;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinloes.prpilot.model.SemanticReviewContext;
import com.jinloes.prpilot.model.SourceInventory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Closed transport: no generic commands, ambient configuration, daemon or schema fallback. */
public final class IjctlClient {
    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Map<String, Map<String, String>> ARGUMENTS =
            Map.of(
                    "search_symbol",
                            Map.of(
                                    "q",
                                    "string",
                                    "include_external",
                                    "boolean",
                                    "limit",
                                    "integer"),
                    "get_symbol_info",
                            Map.of("filePath", "string", "line", "integer", "column", "integer"),
                    "analyze_calls",
                            Map.of(
                                    "symbolFqn",
                                    "string",
                                    "analysisKind",
                                    "string",
                                    "depth",
                                    "integer",
                                    "maxNodes",
                                    "integer"),
                    "get_file_problems", Map.of("filePath", "string"),
                    "get_project_modules", Map.of(),
                    "get_project_dependencies", Map.of());
    private static final Map<String, Set<String>> REQUIRED =
            Map.of(
                    "search_symbol", Set.of("q"),
                    "get_symbol_info", Set.of("filePath", "line", "column"),
                    "analyze_calls", Set.of("symbolFqn", "analysisKind"),
                    "get_file_problems", Set.of("filePath"),
                    "get_project_modules", Set.of(),
                    "get_project_dependencies", Set.of());
    private final SourceInventoryClient.Launch launch;
    private final Path project;
    private final Transport transport;

    @FunctionalInterface
    interface Transport {
        BoundedProcessRunner.ProcessResult run(ProcessBuilder builder, Duration timeout)
                throws IOException, InterruptedException;
    }

    public IjctlClient(SourceInventoryClient.Launch launch, Path project) throws IOException {
        this(
                launch,
                project,
                (builder, timeout) -> {
                    try {
                        return new BoundedProcessRunner()
                                .runOwnedTree(
                                        builder,
                                        timeout.toMillis(),
                                        java.util.concurrent.TimeUnit.MILLISECONDS);
                    } catch (java.util.concurrent.TimeoutException failure) {
                        throw new IOException("CLI call timed out", failure);
                    }
                });
    }

    IjctlClient(SourceInventoryClient.Launch launch, Path project, Transport transport)
            throws IOException {
        // The same asset/path policy as the public physical worker, never DTO-supplied launch data.
        new SourceInventoryClient(launch);
        if (!project.isAbsolute() || !project.toRealPath().equals(project))
            throw new IOException("Exact canonical review worktree required");
        this.launch = launch;
        this.project = project;
        this.transport = transport;
    }

    public SemanticReviewContext.Snapshot snapshot(SemanticReviewContext.Request request)
            throws IOException, InterruptedException {
        if (!project.toString().equals(request.getProjectPath()))
            throw new IOException("Snapshot project mismatch");
        SemanticReviewContext.validate(request);
        JsonNode schema = invoke("describe", SemanticReviewContext.TOOL, null).path("inputSchema");
        if (!schema.isObject()
                || !"object".equals(schema.path("type").asText())
                || !schema.path("properties")
                        .equals(JSON.valueToTree(SemanticReviewContext.inputProperties()))
                || !schema.path("required").isArray()
                || schema.path("required").size() != 4
                || (schema.has("additionalProperties")
                        && (!schema.get("additionalProperties").isBoolean()
                                || schema.get("additionalProperties").booleanValue())))
            throw new IOException("Native TOOL_SCHEMA_CHANGED");
        for (var names = schema.fieldNames(); names.hasNext(); )
            if (!Set.of("type", "properties", "required", "additionalProperties")
                    .contains(names.next())) throw new IOException("Native TOOL_SCHEMA_CHANGED");
        Set<String> required = new HashSet<>();
        for (JsonNode name : schema.get("required"))
            if (!name.isTextual() || !required.add(name.textValue()))
                throw new IOException("Native TOOL_SCHEMA_CHANGED");
        if (!required.equals(Set.of("schemaVersion", "operation", "projectPath", "nonce")))
            throw new IOException("Native TOOL_SCHEMA_CHANGED");
        // The SDK omits additionalProperties. Strict native decoding, not omission, closes
        // requests.
        JsonNode result = invoke("call", SemanticReviewContext.TOOL, JSON.valueToTree(request));
        JsonNode payload = structured(result);
        try {
            validateSnapshot(payload);
            var snapshot = JSON.treeToValue(payload, SemanticReviewContext.Snapshot.class);
            if (!request.getNonce().equals(snapshot.getNonce())
                    || !project.toString().equals(snapshot.getProjectPath()))
                throw new IOException("Snapshot identity mismatch");
            return snapshot;
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid native snapshot", failure);
        }
    }

    /** Arguments originate only in the collector from validated native declarations/files. */
    String query(String tool, Map<String, Object> arguments)
            throws IOException, InterruptedException {
        validateArguments(tool, arguments);
        JsonNode descriptor = invoke("describe", tool, null);
        validateSchema(tool, descriptor.path("inputSchema"));
        // CLI target metadata does not route the native tool; only the engine supplies this path.
        ObjectNode payload = JSON.valueToTree(arguments);
        payload.put("projectPath", project.toString());
        return evidence(invoke("call", tool, payload));
    }

    static void validateArguments(String tool, Map<String, Object> args) throws IOException {
        Map<String, String> allowed = ARGUMENTS.get(tool);
        if (allowed == null || !args.keySet().equals(allowed.keySet()))
            throw new IOException("Closed query policy");
        for (var entry : args.entrySet()) {
            Object value = entry.getValue();
            boolean valid =
                    switch (allowed.get(entry.getKey())) {
                        case "string" ->
                                value instanceof String s
                                        && !s.isBlank()
                                        && s.length() <= 2048
                                        && s.chars().noneMatch(Character::isISOControl);
                        case "integer" -> value instanceof Integer i && i > 0;
                        case "boolean" -> value instanceof Boolean;
                        default -> false;
                    };
            if (!valid) throw new IOException("Invalid query argument");
        }
        if (args.containsKey("filePath")) {
            try {
                SourceInventory.path((String) args.get("filePath"));
            } catch (IllegalArgumentException failure) {
                throw new IOException("Invalid query path", failure);
            }
        }
        if (tool.equals("analyze_calls")
                && (!"INCOMING_CALLS".equals(args.get("analysisKind"))
                        || !Integer.valueOf(1).equals(args.get("depth"))
                        || !Integer.valueOf(100).equals(args.get("maxNodes"))))
            throw new IOException("Unbounded call query");
        if (tool.equals("search_symbol")
                && (!Boolean.FALSE.equals(args.get("include_external"))
                        || !Integer.valueOf(20).equals(args.get("limit"))))
            throw new IOException("Unbounded/external search");
    }

    static void validateSchema(String tool, JsonNode schema) throws IOException {
        if (!ARGUMENTS.containsKey(tool)
                || !schema.isObject()
                || !"object".equals(schema.path("type").asText())
                || !schema.path("properties").isObject())
            throw new IOException("TOOL_SCHEMA_CHANGED");
        Set<String> required = new java.util.HashSet<>();
        if (schema.has("required")) {
            if (!schema.get("required").isArray()) throw new IOException("TOOL_SCHEMA_CHANGED");
            for (JsonNode name : schema.get("required"))
                if (!name.isTextual() || !required.add(name.textValue()))
                    throw new IOException("TOOL_SCHEMA_CHANGED");
        }
        if (!required.equals(REQUIRED.get(tool))) throw new IOException("TOOL_SCHEMA_CHANGED");
        for (var argument : ARGUMENTS.get(tool).entrySet()) {
            JsonNode property = schema.path("properties").path(argument.getKey());
            if (!matchesType(property, argument.getValue()))
                throw new IOException("TOOL_SCHEMA_CHANGED: " + argument.getKey());
        }
        JsonNode binding = schema.path("properties").path("projectPath");
        if (!binding.isObject()
                || !binding.path("type").isTextual()
                || !"string".equals(binding.get("type").textValue())
                || (binding.has("description") && !binding.get("description").isTextual()))
            throw new IOException("TOOL_SCHEMA_CHANGED: projectPath");
        for (var names = binding.fieldNames(); names.hasNext(); )
            if (!Set.of("type", "description").contains(names.next()))
                throw new IOException("TOOL_SCHEMA_CHANGED: projectPath");
    }

    private static boolean matchesType(JsonNode property, String type) {
        if (property.path("type").isTextual()) return type.equals(property.get("type").textValue());
        if (property.path("anyOf").isArray() && property.get("anyOf").size() == 2) {
            boolean matched = false, nullable = false;
            for (JsonNode alternative : property.get("anyOf")) {
                matched |= type.equals(alternative.path("type").asText());
                nullable |= "null".equals(alternative.path("type").asText());
            }
            return matched && nullable;
        }
        return false;
    }

    static void validateSnapshot(JsonNode value) throws IOException {
        Set<String> fields =
                Set.of(
                        "schemaVersion",
                        "nonce",
                        "projectPath",
                        "ideBuild",
                        "projectInstanceId",
                        "snapshotId",
                        "status",
                        "reasons",
                        "coverageIdentity",
                        "sourceDigest",
                        "modelDigest",
                        "settingsDigest",
                        "epochs",
                        "declarations",
                        "declarationLimitations");
        if (value == null || !value.isObject() || value.size() != fields.size())
            throw new IOException("Snapshot schema");
        for (String field : fields)
            if (!value.has(field)) throw new IOException("Snapshot missing " + field);
        if (!value.get("schemaVersion").isInt() || value.get("schemaVersion").intValue() != 2)
            throw new IOException("Snapshot schema version");
        for (String field :
                List.of("nonce", "projectPath", "ideBuild", "projectInstanceId", "status"))
            if (!value.get(field).isTextual() || value.get(field).textValue().isBlank())
                throw new IOException("Snapshot " + field);
        try {
            java.util.UUID.fromString(value.get("nonce").textValue());
            java.util.UUID.fromString(value.get("projectInstanceId").textValue());
            SemanticReviewContext.Status.valueOf(value.get("status").textValue());
            SourceInventory.absolute(value.get("projectPath").textValue());
        } catch (IllegalArgumentException failure) {
            throw new IOException("Snapshot identity", failure);
        }
        boolean ready = "READY".equals(value.get("status").textValue());
        for (String field :
                List.of(
                        "snapshotId",
                        "coverageIdentity",
                        "sourceDigest",
                        "modelDigest",
                        "settingsDigest",
                        "epochs")) {
            JsonNode item = value.get(field);
            if ((!item.isNull() && !item.isTextual())
                    || (ready && (!item.isTextual() || item.textValue().isBlank())))
                throw new IOException("Snapshot authority field " + field);
        }
        if (ready) {
            try {
                java.util.UUID.fromString(value.get("snapshotId").textValue());
            } catch (IllegalArgumentException failure) {
                throw new IOException("Snapshot ID", failure);
            }
            for (String field :
                    List.of("coverageIdentity", "sourceDigest", "modelDigest", "settingsDigest"))
                if (!value.get(field).textValue().matches("[0-9a-f]{64}"))
                    throw new IOException("Snapshot digest");
        }
        for (String field : List.of("reasons", "declarationLimitations")) {
            JsonNode items = value.get(field);
            if (!items.isArray() || items.size() > 64)
                throw new IOException("Snapshot limitations");
            for (JsonNode item : items)
                if (!item.isTextual() || item.textValue().isBlank())
                    throw new IOException("Snapshot limitation");
        }
        if (ready && !value.get("reasons").isEmpty())
            throw new IOException("READY with blocking reasons");
        if (!value.get("declarations").isArray() || value.get("declarations").size() > 20)
            throw new IOException("Declaration limit");
        for (JsonNode declaration : value.get("declarations")) {
            if (!declaration.isObject()
                    || declaration.size() != 3
                    || !declaration.path("path").isTextual()
                    || !declaration.path("qualifiedName").isTextual()
                    || declaration.get("qualifiedName").textValue().isBlank()
                    || !declaration.path("line").isInt()
                    || declaration.get("line").intValue() < 1)
                throw new IOException("Native declaration schema");
            try {
                SourceInventory.path(declaration.get("path").textValue());
            } catch (IllegalArgumentException failure) {
                throw new IOException("Native declaration path", failure);
            }
        }
    }

    private JsonNode invoke(String command, String tool, JsonNode arguments)
            throws IOException, InterruptedException {
        if (!ARGUMENTS.containsKey(tool) && !SemanticReviewContext.TOOL.equals(tool))
            throw new IOException("Tool not allowed");
        Path privateDirectory =
                Files.createTempDirectory(
                        "pr-pilot-semantic-query-",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
        Path request = privateDirectory.resolve("request.json");
        try {
            List<String> argv =
                    new ArrayList<>(
                            List.of(
                                    launch.ijctl().toString(),
                                    "--config",
                                    launch.config().toString(),
                                    "--project",
                                    project.toString(),
                                    "--server",
                                    launch.server(),
                                    "--no-daemon",
                                    "--timeout",
                                    "30000",
                                    command,
                                    tool));
            if (arguments != null) {
                byte[] bytes = JSON.writeValueAsBytes(arguments);
                if (bytes.length > SourceInventory.MAX_JSON_BYTES)
                    throw new IOException("Request limit");
                Files.createFile(
                        request,
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
                Files.write(request, bytes);
                argv.addAll(List.of("--args-file", request.toString()));
            }
            ProcessBuilder builder = new ProcessBuilder(argv).directory(privateDirectory.toFile());
            builder.environment().clear();
            builder.environment().put("HOME", launch.home().toString());
            builder.environment().put("PATH", launch.executablePath());
            builder.environment().put("LC_ALL", "C");
            return decode(
                    transport.run(builder, Duration.ofSeconds(30)), launch.server(), command, tool);
        } finally {
            Files.deleteIfExists(request);
            Files.deleteIfExists(privateDirectory);
        }
    }

    static JsonNode decode(
            BoundedProcessRunner.ProcessResult output, String server, String command, String tool)
            throws IOException {
        if (output.exitCode() != 0
                || output.outputTruncated()
                || output.output().contains("\ufffd")
                || SourceInventory.utf8(output.output()).length > SourceInventory.MAX_JSON_BYTES)
            throw new IOException("CLI transport failure");
        JsonNode envelope = JSON.readTree(output.output());
        if (envelope == null || !envelope.isObject()) throw new IOException("Missing CLI envelope");
        Set<String> allowed =
                Set.of(
                        "ok",
                        "command",
                        "durationMs",
                        "server",
                        "connectionMode",
                        "tool",
                        "result",
                        "target",
                        "safety",
                        "warning");
        for (var names = envelope.fieldNames(); names.hasNext(); )
            if (!allowed.contains(names.next()))
                throw new IOException("Unknown CLI envelope field");
        if (!envelope.path("ok").isBoolean()
                || !envelope.get("ok").booleanValue()
                || !server.equals(envelope.path("server").asText())
                || !command.equals(envelope.path("command").asText())
                || !"direct".equals(envelope.path("connectionMode").asText()))
            throw new IOException("CLI envelope identity mismatch");
        if (command.equals("describe")) {
            JsonNode descriptor = envelope.path("tool");
            if (!descriptor.isObject() || !tool.equals(descriptor.path("name").asText()))
                throw new IOException("Wrong tool descriptor");
            return descriptor;
        }
        if (!tool.equals(envelope.path("tool").asText()))
            throw new IOException("Wrong called tool");
        JsonNode result = envelope.path("result");
        if (!result.isObject()) throw new IOException("Missing MCP result");
        for (var names = result.fieldNames(); names.hasNext(); )
            if (!Set.of("content", "structuredContent", "isError", "_meta").contains(names.next()))
                throw new IOException("Unknown MCP result field");
        if (result.has("isError")
                && (!result.get("isError").isBoolean() || result.get("isError").booleanValue()))
            throw new IOException("MCP_TOOL_ERROR");
        return result;
    }

    private static JsonNode structured(JsonNode result) throws IOException {
        JsonNode structured = result.get("structuredContent");
        JsonNode text = null;
        if (result.has("content")) {
            JsonNode content = result.get("content");
            if (!content.isArray() || content.size() > 1)
                throw new IOException("Native content count");
            if (content.size() == 1) text = JSON.readTree(text(content.get(0)));
        }
        if ((structured == null && text == null)
                || (structured != null && text != null && !structured.equals(text)))
            throw new IOException("Absent or conflicting native payload");
        return structured == null ? text : structured;
    }

    private static String text(JsonNode item) throws IOException {
        if (!item.isObject()
                || item.size() != 2
                || !"text".equals(item.path("type").asText())
                || !item.path("text").isTextual()) throw new IOException("Unsupported MCP content");
        return item.get("text").textValue();
    }

    static String evidence(JsonNode result) throws IOException {
        StringBuilder value = new StringBuilder();
        if (result.has("structuredContent")) {
            if (!result.get("structuredContent").isObject())
                throw new IOException("Invalid structured evidence");
            value.append(JSON.writeValueAsString(result.get("structuredContent")));
        }
        if (result.has("content")) {
            if (!result.get("content").isArray()) throw new IOException("Invalid evidence content");
            for (JsonNode item : result.get("content")) value.append('\n').append(text(item));
        }
        if (value.isEmpty()) throw new IOException("Absent evidence");
        return value.toString(); // Text trees remain data; never parsed into arguments.
    }
}
