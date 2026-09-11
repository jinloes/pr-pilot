package com.jinloes.prpilot.services;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.mcpserver.McpCallInfoKt;
import com.intellij.mcpserver.McpTool;
import com.intellij.mcpserver.McpToolCallResult;
import com.intellij.mcpserver.McpToolCallResultContent;
import com.intellij.mcpserver.McpToolCategory;
import com.intellij.mcpserver.McpToolDescriptor;
import com.intellij.mcpserver.McpToolSchema;
import com.intellij.mcpserver.McpToolsProvider;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.jinloes.prpilot.model.SourceInventory;
import com.jinloes.prpilot.model.SourceInventory.DiscoverRequest;
import com.jinloes.prpilot.model.SourceInventory.Response;
import com.jinloes.prpilot.model.SourceInventory.VerifyRequest;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import kotlin.ResultKt;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.intrinsics.IntrinsicsKt;
import kotlinx.coroutines.DisposableHandle;
import kotlinx.coroutines.Job;
import kotlinx.serialization.json.Json;
import kotlinx.serialization.json.JsonObject;

/** Loaded only through the optional MCP descriptor. Unknown SDK ABIs advertise no tool. */
public final class SourceInventoryMcpProvider implements McpToolsProvider {
    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    @Override
    public List<McpTool> getTools() {
        return tools(ApplicationInfo.getInstance().getBuild().asString());
    }

    static List<McpTool> tools(String build) {
        if (!SourceInventoryService.supportsBuild(build)) {
            return List.of();
        }
        try {
            McpToolDescriptor descriptor = Adapter262.descriptor();
            return List.of(new InventoryTool(descriptor));
        } catch (ReflectiveOperationException | IOException | LinkageError | RuntimeException e) {
            return List.of();
        }
    }

    static DiscoverRequest decode(String text) throws IOException {
        if (SourceInventory.utf8(text).length > SourceInventory.MAX_JSON_BYTES) {
            throw new IOException("Request limit");
        }
        Object wire = JSON.readValue(text, Object.class);
        if (!(wire instanceof Map<?, ?> object)) {
            throw new IOException("Expected request object");
        }
        Class<? extends DiscoverRequest> type =
                "VERIFY".equals(object.get("operation"))
                        ? VerifyRequest.class
                        : DiscoverRequest.class;
        SourceInventory.validateWire(wire, type);
        DiscoverRequest request = JSON.convertValue(wire, type);
        SourceInventory.validate(request);
        return request;
    }

    static JsonObject json(Object value) throws IOException {
        return (JsonObject) Json.Default.parseToJsonElement(JSON.writeValueAsString(value));
    }

    static McpToolSchema schema(Class<?> type, Set<String> required) throws IOException {
        return new McpToolSchema(
                json(SourceInventory.schema(type).get("properties")), required, Map.of(), "$defs");
    }

    static final class Adapter262 {
        private Adapter262() {}

        static Object construct(
                Class<?> descriptor,
                Class<?> category,
                Class<?> schema,
                Class<?> annotations,
                Object input,
                Object output)
                throws ReflectiveOperationException {
            return construct(
                    descriptor,
                    category,
                    schema,
                    annotations,
                    input,
                    output,
                    SourceInventory.TOOL,
                    "Source inventory",
                    "Protocol v2: discover native VFS membership and verify VFS hashes only; "
                            + "physical/Git coverage requires the external worker. Not readiness.");
        }

        static Object construct(
                Class<?> descriptor,
                Class<?> category,
                Class<?> schema,
                Class<?> annotations,
                Object input,
                Object output,
                String name,
                String title,
                String description)
                throws ReflectiveOperationException {
            Constructor<?> categoryConstructor =
                    category.getConstructor(
                            String.class, String.class, boolean.class, boolean.class);
            Constructor<?> descriptorConstructor =
                    descriptor.getConstructor(
                            String.class,
                            String.class,
                            String.class,
                            category,
                            String.class,
                            schema,
                            schema,
                            annotations);
            Object group =
                    categoryConstructor.newInstance(
                            "pr_pilot", "com.jinloes.prpilot.sourceInventory", false, true);
            return descriptorConstructor.newInstance(
                    name, title, description, group, name, input, output, null);
        }

        static McpToolDescriptor descriptor() throws ReflectiveOperationException, IOException {
            Class<?> annotations =
                    Class.forName(
                            "io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations",
                            false,
                            McpToolDescriptor.class.getClassLoader());
            McpToolSchema input =
                    schema(
                            VerifyRequest.class,
                            Set.of("schemaVersion", "operation", "projectPath", "nonce"));
            McpToolSchema output =
                    schema(
                            Response.class,
                            Set.of(
                                    "schemaVersion",
                                    "operation",
                                    "nonce",
                                    "status",
                                    "reasons",
                                    "discovery",
                                    "coverage"));
            return (McpToolDescriptor)
                    construct(
                            McpToolDescriptor.class,
                            McpToolCategory.class,
                            McpToolSchema.class,
                            annotations,
                            input,
                            output);
        }

        static McpToolDescriptor descriptor(
                String name,
                String title,
                String description,
                McpToolSchema input,
                McpToolSchema output)
                throws ReflectiveOperationException {
            return (McpToolDescriptor)
                    construct(
                            McpToolDescriptor.class,
                            McpToolCategory.class,
                            McpToolSchema.class,
                            Class.forName(
                                    "io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations",
                                    false,
                                    McpToolDescriptor.class.getClassLoader()),
                            input,
                            output,
                            name,
                            title,
                            description);
        }
    }

    private record InventoryTool(McpToolDescriptor descriptor) implements McpTool {
        @Override
        public McpToolDescriptor getDescriptor() {
            return descriptor;
        }

        @Override
        public Object call(
                JsonObject arguments, Continuation<? super McpToolCallResult> continuation) {
            return dispatch(
                    continuation,
                    project -> {
                        DiscoverRequest request = decode(arguments.toString());
                        Response response =
                                project.getService(SourceInventoryService.class).execute(request);
                        return new McpToolCallResult(
                                new McpToolCallResultContent[0], json(response), false);
                    });
        }
    }

    @FunctionalInterface
    interface ProjectOperation {
        McpToolCallResult execute(Project project) throws IOException;
    }

    static Object dispatch(
            Continuation<? super McpToolCallResult> continuation, ProjectOperation operation) {
        final Project project;
        try {
            project = McpCallInfoKt.getProject(continuation.getContext());
        } catch (RuntimeException e) {
            return error("WRONG_PROJECT");
        }
        AtomicReference<DisposableHandle> cancellation = new AtomicReference<>();
        FutureTask<McpToolCallResult> task =
                new FutureTask<>(
                        () -> {
                            try {
                                return operation.execute(project);
                            } catch (IOException | IllegalArgumentException e) {
                                return error("BAD_REQUEST");
                            }
                        }) {
                    @Override
                    protected void done() {
                        DisposableHandle handle = cancellation.get();
                        if (handle != null) {
                            handle.dispose();
                        }
                        Object result;
                        try {
                            result = get();
                        } catch (Exception e) {
                            result = ResultKt.createFailure(e);
                        }
                        continuation.resumeWith(result);
                    }
                };
        Job job = continuation.getContext().get(Job.Key);
        if (job != null) {
            cancellation.set(
                    job.invokeOnCompletion(
                            true,
                            true,
                            cause -> {
                                if (cause != null) {
                                    task.cancel(true);
                                }
                                return Unit.INSTANCE;
                            }));
            if (task.isDone()) {
                cancellation.get().dispose();
            }
        }
        try {
            ApplicationManager.getApplication().executeOnPooledThread(task);
        } catch (RuntimeException e) {
            task.cancel(true);
        }
        return IntrinsicsKt.getCOROUTINE_SUSPENDED();
    }

    private static McpToolCallResult error(String code) {
        try {
            return new McpToolCallResult(
                    new McpToolCallResultContent[0], json(Map.of("error", code)), true);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
