package com.jinloes.prpilot.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intellij.mcpserver.ClientInfo;
import com.intellij.mcpserver.McpCallAdditionalDataElement;
import com.intellij.mcpserver.McpCallInfo;
import com.intellij.mcpserver.McpTool;
import com.intellij.mcpserver.McpToolCallResult;
import com.intellij.mcpserver.McpToolCategory;
import com.intellij.mcpserver.McpToolDescriptor;
import com.intellij.mcpserver.McpToolFilter;
import com.intellij.mcpserver.McpToolSchema;
import com.intellij.mcpserver.impl.McpServerService;
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemRefreshStatus;
import com.intellij.openapi.util.registry.Registry;
import com.jinloes.prpilot.model.SemanticReviewContext;
import io.modelcontextprotocol.kotlin.sdk.types.Tool;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import kotlin.ResultKt;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.JobImpl;
import kotlinx.serialization.json.Json;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SemanticMcpToolsProviderTest {
    @Nested
    class ActualDispatch {
        private kotlinx.serialization.json.JsonObject arguments(
                SemanticReviewContext.Request request) throws Exception {
            Map<String, Object> wire = new HashMap<>();
            wire.put("schemaVersion", 2);
            wire.put("operation", request.getOperation().name());
            wire.put("projectPath", request.getProjectPath());
            wire.put("nonce", request.getNonce());
            if (request.getDiscoveryId() != null) {
                wire.put("discoveryId", request.getDiscoveryId());
                wire.put("files", request.getFiles());
                wire.put("changedRanges", request.getChangedRanges());
            }
            if (request.getSnapshotId() != null) wire.put("snapshotId", request.getSnapshotId());
            return SourceInventoryMcpProvider.json(wire);
        }

        private McpToolDescriptor descriptor() throws Exception {
            return new McpToolDescriptor(
                    SemanticReviewContext.TOOL,
                    "fixture",
                    new McpToolCategory("fixture", "fixture"),
                    "fixture",
                    new McpToolSchema(
                            SourceInventoryMcpProvider.json(
                                    SemanticReviewContext.inputProperties()),
                            Set.of("schemaVersion", "operation", "projectPath", "nonce"),
                            Map.of(),
                            "$defs"),
                    null);
        }

        private McpTool tool(McpToolDescriptor descriptor) throws Exception {
            var type = Class.forName(SemanticMcpToolsProvider.class.getName() + "$SnapshotTool");
            var constructor = type.getDeclaredConstructor(McpToolDescriptor.class);
            constructor.setAccessible(true);
            return (McpTool) constructor.newInstance(descriptor);
        }

        private CoroutineContext context(
                SemanticSnapshotServiceTest.NativeFixture fixture, McpToolDescriptor descriptor)
                throws Exception {
            var empty = SourceInventoryMcpProvider.json(Map.of());
            return new McpCallAdditionalDataElement(
                    new McpCallInfo(
                            1,
                            new ClientInfo("fixture", "1"),
                            fixture.project,
                            descriptor,
                            empty,
                            empty,
                            new McpServerService.McpSessionOptions(
                                    McpServerService.AskCommandExecutionMode.ASK,
                                    McpToolFilter.AllowAll.INSTANCE,
                                    null),
                            Map.of()));
        }

        @Test
        void actualPinnedSdkConversionMatchesObserved262InputSchemaProjection() throws Exception {
            // This is the real 261 SDK serializer, not a claim of native 262 dispatch/readiness.
            var conversion =
                    Class.forName("com.intellij.mcpserver.impl.McpSessionHandlerKt")
                            .getDeclaredMethod("access$toSdkTool", McpTool.class);
            conversion.setAccessible(true);
            // Plain unitTest does not load plugin.xml registry contributions. Restore the
            // process-local override/cache; this flag only selects the optional output schema.
            String key = "mcp.server.structured.tool.output";
            String previous = System.getProperty(key);
            Tool sdkTool;
            try {
                System.setProperty(key, "true");
                Registry.get(key).resetCache$intellij_platform_util();
                sdkTool = (Tool) conversion.invoke(null, tool(descriptor()));
            } finally {
                if (previous == null) System.clearProperty(key);
                else System.setProperty(key, previous);
                Registry.get(key).resetCache$intellij_platform_util();
            }
            var mapper = new ObjectMapper();
            var wire =
                    mapper.readTree(
                            Json.Default.encodeToString(Tool.Companion.serializer(), sdkTool));
            assertThat(wire.path("name").asText()).isEqualTo(SemanticReviewContext.TOOL);
            var input = wire.path("inputSchema");
            // Real 262 describe has this projection, including omission (not JSON Schema closure).
            var observed =
                    mapper.valueToTree(
                            Map.of(
                                    "type",
                                    "object",
                                    "properties",
                                    SemanticReviewContext.inputProperties()));
            var projection = ((ObjectNode) input).deepCopy();
            var required = projection.remove("required");
            assertThat(projection).isEqualTo(observed);
            assertThat(required.isArray()).isTrue();
            assertThat(required).hasSize(4);
            assertThat(required)
                    .extracting(node -> node.textValue())
                    .containsExactlyInAnyOrder(
                            "schemaVersion", "operation", "projectPath", "nonce");
            assertThat(input.has("additionalProperties")).isFalse();
        }

        private SemanticReviewContext.Snapshot dispatch(
                SemanticSnapshotServiceTest.NativeFixture fixture,
                McpToolDescriptor descriptor,
                SemanticReviewContext.Request request)
                throws Exception {
            var callback = new Callback(context(fixture, descriptor));
            tool(descriptor).call(arguments(request), callback);
            assertThat(callback.result.get()).isNull();
            fixture.drainOne();
            var result = (McpToolCallResult) callback.result.get();
            assertThat(result.isError()).isFalse();
            return new ObjectMapper()
                    .readValue(
                            result.getStructuredContent().toString(),
                            SemanticReviewContext.Snapshot.class);
        }

        private void rejectsWithoutStateEffects(
                SemanticSnapshotServiceTest.NativeFixture fixture,
                McpToolDescriptor descriptor,
                ObjectNode wire)
                throws Exception {
            assertThat(fixture.background).isEmpty();
            int captures = fixture.physicalCaptures;
            int verifies = fixture.verifies.size();
            var callback = new Callback(context(fixture, descriptor));
            tool(descriptor)
                    .call(
                            SourceInventoryMcpProvider.json(
                                    new ObjectMapper().convertValue(wire, Map.class)),
                            callback);
            assertThat(callback.result.get()).isNull();
            assertThat(fixture.background).hasSize(1);
            fixture.drainOne();
            var result = (McpToolCallResult) callback.result.get();
            assertThat(result.isError()).isTrue();
            assertThat(result.getStructuredContent().toString()).contains("BAD_REQUEST");
            assertThat(fixture.background).isEmpty();
            assertThat(fixture.physicalCaptures).isEqualTo(captures);
            assertThat(fixture.verifies).hasSize(verifies);
        }

        @Test
        void otherwiseValidRequestsRejectUnknownFieldsWithoutArmingCaptureOrBaselineEffects()
                throws Exception {
            try (var fixture = new SemanticSnapshotServiceTest.NativeFixture(true)) {
                var descriptor = descriptor();
                var mapper = new ObjectMapper();
                var status = fixture.requestFor(SemanticReviewContext.Operation.STATUS);
                var badStatus = (ObjectNode) mapper.readTree(arguments(status).toString());
                badStatus.put("unexpected", true);
                rejectsWithoutStateEffects(fixture, descriptor, badStatus);
                assertThat(fixture.physicalCaptures).isZero();
                assertThat(fixture.linked).allSatisfy(build -> assertThat(build.listener).isNull());

                assertThat(dispatch(fixture, descriptor, status).getStatus())
                        .isEqualTo(SemanticReviewContext.Status.ARMING);
                fixture.drainOne();
                fixture.drainOne();
                fixture.reload(ExternalSystemRefreshStatus.SUCCESS);
                fixture.drainOne();
                fixture.drainOne();
                var capture =
                        dispatch(
                                fixture,
                                descriptor,
                                fixture.requestFor(SemanticReviewContext.Operation.CAPTURE));
                assertThat(capture.getStatus()).isEqualTo(SemanticReviewContext.Status.READY);
                var verify = fixture.requestFor(SemanticReviewContext.Operation.VERIFY);
                verify.setSnapshotId(capture.getSnapshotId());
                assertThat(dispatch(fixture, descriptor, verify).getStatus())
                        .isEqualTo(SemanticReviewContext.Status.READY);

                for (var request :
                        List.of(
                                status,
                                fixture.requestFor(SemanticReviewContext.Operation.CAPTURE),
                                verify)) {
                    var wire = (ObjectNode) mapper.readTree(arguments(request).toString());
                    var unknownTop = wire.deepCopy();
                    unknownTop.put("unexpected", true);
                    rejectsWithoutStateEffects(fixture, descriptor, unknownTop);
                    if (request.getOperation() != SemanticReviewContext.Operation.STATUS) {
                        var unknownFile = wire.deepCopy();
                        ((ObjectNode) unknownFile.path("files").get(0)).put("unexpected", true);
                        rejectsWithoutStateEffects(fixture, descriptor, unknownFile);
                        var unknownRange = wire.deepCopy();
                        unknownRange
                                .putArray("changedRanges")
                                .add(
                                        mapper.valueToTree(
                                                Map.of(
                                                        "path",
                                                        "Example.java",
                                                        "startLine",
                                                        1,
                                                        "endLine",
                                                        1)));
                        assertThat(
                                        SemanticMcpToolsProvider.decode(unknownRange.toString())
                                                .getChangedRanges())
                                .hasSize(1);
                        ((ObjectNode) unknownRange.path("changedRanges").get(0))
                                .put("unexpected", true);
                        rejectsWithoutStateEffects(fixture, descriptor, unknownRange);
                    }
                    var stillValid = dispatch(fixture, descriptor, verify);
                    assertThat(stillValid.getStatus())
                            .isEqualTo(SemanticReviewContext.Status.READY);
                    assertThat(stillValid.getSnapshotId()).isEqualTo(capture.getSnapshotId());
                    assertThat(stillValid.getEpochs()).isEqualTo(capture.getEpochs());
                    assertThat(stillValid.getSettingsDigest())
                            .isEqualTo(capture.getSettingsDigest());
                }
            }
        }

        @Test
        void actualToolRequiresNativeCallContextRatherThanRequestSelectedProject()
                throws Exception {
            var descriptor = descriptor();
            var callback = new Callback(EmptyCoroutineContext.INSTANCE);
            Object result =
                    tool(descriptor)
                            .call(
                                    SourceInventoryMcpProvider.json(
                                            SemanticSnapshotServiceTest.request(
                                                    SemanticReviewContext.Operation.STATUS)),
                                    callback);
            assertThat(result).isInstanceOf(McpToolCallResult.class);
            assertThat(((McpToolCallResult) result).isError()).isTrue();
            assertThat(((McpToolCallResult) result).getStructuredContent().toString())
                    .contains("WRONG_PROJECT");
            assertThat(callback.result.get()).isNull();
        }

        @Test
        void actualScheduledToolInvokesServiceWithNativeProjectAndRejectsWrongRequestedPath()
                throws Exception {
            try (var fixture = new SemanticSnapshotServiceTest.NativeFixture()) {
                var descriptor = descriptor();
                var callback = new Callback(context(fixture, descriptor));
                var request =
                        SemanticSnapshotServiceTest.request(SemanticReviewContext.Operation.STATUS);
                request.setProjectPath("/request-cannot-select-this-project");
                tool(descriptor)
                        .call(
                                SourceInventoryMcpProvider.json(
                                        Map.of(
                                                "schemaVersion",
                                                2,
                                                "operation",
                                                "STATUS",
                                                "projectPath",
                                                request.getProjectPath(),
                                                "nonce",
                                                request.getNonce())),
                                callback);
                assertThat(fixture.background).hasSize(1);
                fixture.background.remove(0).run();
                var result = (McpToolCallResult) callback.result.get();
                assertThat(result.getStructuredContent().toString())
                        .contains("WRONG_PROJECT", "/fixture", "NOT_READY");
            }
        }

        @Test
        void scheduledCaptureVerifyAndSourceRejectionUseTheNativeProjectService() throws Exception {
            try (var fixture = new SemanticSnapshotServiceTest.NativeFixture(true)) {
                fixture.establish();
                var descriptor = descriptor();
                var tool = tool(descriptor);
                var mapper = new ObjectMapper();
                var captureCallback = new Callback(context(fixture, descriptor));
                tool.call(
                        arguments(fixture.requestFor(SemanticReviewContext.Operation.CAPTURE)),
                        captureCallback);
                assertThat(captureCallback.result.get()).isNull();
                fixture.drainOne();
                var capture =
                        mapper.readValue(
                                ((McpToolCallResult) captureCallback.result.get())
                                        .getStructuredContent()
                                        .toString(),
                                SemanticReviewContext.Snapshot.class);
                assertThat(capture.getStatus()).isEqualTo(SemanticReviewContext.Status.READY);
                assertThat(capture.getProjectPath()).isEqualTo(fixture.root.toString());
                var verify = fixture.requestFor(SemanticReviewContext.Operation.VERIFY);
                verify.setSnapshotId(capture.getSnapshotId());
                var verifyCallback = new Callback(context(fixture, descriptor));
                tool.call(arguments(verify), verifyCallback);
                fixture.drainOne();
                var verified =
                        mapper.readValue(
                                ((McpToolCallResult) verifyCallback.result.get())
                                        .getStructuredContent()
                                        .toString(),
                                SemanticReviewContext.Snapshot.class);
                assertThat(verified.getStatus()).isEqualTo(SemanticReviewContext.Status.READY);
                assertThat(verified.getNonce()).isEqualTo(verify.getNonce());
                assertThat(verified.getSnapshotId()).isEqualTo(capture.getSnapshotId());
                assertThat(verified.getProjectInstanceId())
                        .isEqualTo(capture.getProjectInstanceId());
                fixture.inventory.dispose();
                var rejected = new Callback(context(fixture, descriptor));
                tool.call(arguments(verify), rejected);
                fixture.drainOne();
                assertThat(
                                ((McpToolCallResult) rejected.result.get())
                                        .getStructuredContent()
                                        .toString())
                        .contains("NOT_READY", "Native source VERIFY rejected current discovery");
                assertThat(fixture.snapshotServiceRequests).isEqualTo(3);
                assertThat(fixture.background).isEmpty();
            }
        }

        @Test
        void actualCoroutineCancellationPreventsQueuedSnapshotExecution() throws Exception {
            try (var fixture = new SemanticSnapshotServiceTest.NativeFixture(true)) {
                fixture.establish();
                var descriptor = descriptor();
                var job = new JobImpl(null);
                var callback = new Callback(context(fixture, descriptor).plus(job));
                tool(descriptor)
                        .call(
                                arguments(
                                        fixture.requestFor(
                                                SemanticReviewContext.Operation.CAPTURE)),
                                callback);
                job.cancel(new CancellationException("fixture cancellation"));
                assertThat(fixture.background).hasSize(1);
                fixture.background.remove(0).run();
                assertThatThrownBy(() -> ResultKt.throwOnFailure(callback.result.get()))
                        .isInstanceOf(CancellationException.class);
                assertThat(fixture.snapshotServiceRequests).isZero();
            }
        }

        @Test
        void actualScheduledDecodeRejectsUnexpectedFieldsBeforeService() throws Exception {
            try (var fixture = new SemanticSnapshotServiceTest.NativeFixture()) {
                var descriptor = descriptor();
                var callback = new Callback(context(fixture, descriptor));
                tool(descriptor)
                        .call(
                                SourceInventoryMcpProvider.json(Map.of("unexpected", true)),
                                callback);
                fixture.background.remove(0).run();
                var result = (McpToolCallResult) callback.result.get();
                assertThat(result.isError()).isTrue();
                assertThat(result.getStructuredContent().toString()).contains("BAD_REQUEST");
            }
        }
    }

    private static final class Callback implements Continuation<McpToolCallResult> {
        private final CoroutineContext context;
        final AtomicReference<Object> result = new AtomicReference<>();

        Callback(CoroutineContext context) {
            this.context = context;
        }

        @Override
        public CoroutineContext getContext() {
            return context;
        }

        @Override
        public void resumeWith(Object value) {
            assertThat(result.compareAndSet(null, value)).as("exactly one completion").isTrue();
        }
    }

    @Nested
    class WireBoundary {
        @Test
        void strictTypedStatusAndSdkFloor() throws Exception {
            ObjectMapper json = new ObjectMapper();
            var node =
                    json.valueToTree(
                            Map.of(
                                    "schemaVersion",
                                    2,
                                    "operation",
                                    "STATUS",
                                    "projectPath",
                                    "/fixture",
                                    "nonce",
                                    UUID.randomUUID().toString()));
            assertThat(
                            SemanticMcpToolsProvider.decode(json.writeValueAsString(node))
                                    .getProjectPath())
                    .isEqualTo("/fixture");
            assertThat(SemanticMcpToolsProvider.tools("IU-261.1")).isEmpty();
            assertThat(SemanticMcpToolsProvider.tools("unknown")).isEmpty();
            for (String invalid :
                    new String[] {
                        json.writeValueAsString(node)
                                .replace("\"schemaVersion\":2", "\"schemaVersion\":\"2\""),
                        json.writeValueAsString(node).replace("\"STATUS\"", "\"CAPTURE\""),
                        json.writeValueAsString(node) + "{}"
                    }) {
                assertThatThrownBy(() -> SemanticMcpToolsProvider.decode(invalid))
                        .isInstanceOf(Exception.class);
            }
        }
    }
}
