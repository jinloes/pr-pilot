package com.jinloes.prpilot.services;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.mcpserver.McpTool;
import com.intellij.mcpserver.McpToolCallResult;
import com.intellij.mcpserver.McpToolCallResultContent;
import com.intellij.mcpserver.McpToolDescriptor;
import com.intellij.mcpserver.McpToolSchema;
import com.intellij.mcpserver.McpToolsProvider;
import com.intellij.openapi.application.ApplicationInfo;
import com.jinloes.prpilot.model.SemanticReviewContext;
import com.jinloes.prpilot.model.SourceInventory;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kotlin.coroutines.Continuation;
import kotlinx.serialization.json.JsonObject;

/**
 * Optional MCP entrypoint; no project supplied in tool arguments can select another IDE project.
 */
public final class SemanticMcpToolsProvider implements McpToolsProvider {
    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    @Override
    public List<McpTool> getTools() {
        return tools(ApplicationInfo.getInstance().getBuild().asString());
    }

    static List<McpTool> tools(String build) {
        if (!SourceInventoryService.supportsBuild(build)) return List.of();
        try {
            McpToolSchema input =
                    new McpToolSchema(
                            SourceInventoryMcpProvider.json(
                                    SemanticReviewContext.inputProperties()),
                            Set.of("schemaVersion", "operation", "projectPath", "nonce"),
                            Map.of(),
                            "$defs");
            McpToolDescriptor descriptor =
                    SourceInventoryMcpProvider.Adapter262.descriptor(
                            SemanticReviewContext.TOOL,
                            "Review snapshot",
                            "Schema 2 STATUS arms manual-import observers; CAPTURE and VERIFY prove native"
                                    + " readiness only. Independent forked physical coverage is mandatory.",
                            input,
                            null);
            return List.of(new SnapshotTool(descriptor));
        } catch (ReflectiveOperationException | IOException | LinkageError | RuntimeException e) {
            return List.of();
        }
    }

    static SemanticReviewContext.Request decode(String text) throws IOException {
        if (SourceInventory.utf8(text).length > SourceInventory.MAX_JSON_BYTES) {
            throw new IOException("Snapshot request limit");
        }
        Object wire = JSON.readValue(text, Object.class);
        SemanticReviewContext.validateWire(wire);
        SemanticReviewContext.Request request =
                JSON.convertValue(wire, SemanticReviewContext.Request.class);
        SemanticReviewContext.validate(request);
        return request;
    }

    private record SnapshotTool(McpToolDescriptor descriptor) implements McpTool {
        @Override
        public McpToolDescriptor getDescriptor() {
            return descriptor;
        }

        @Override
        public Object call(
                JsonObject arguments, Continuation<? super McpToolCallResult> continuation) {
            return SourceInventoryMcpProvider.dispatch(
                    continuation,
                    project ->
                            new McpToolCallResult(
                                    new McpToolCallResultContent[0],
                                    SourceInventoryMcpProvider.json(
                                            project.getService(SemanticSnapshotService.class)
                                                    .execute(decode(arguments.toString()))),
                                    false));
        }
    }
}
