package com.jinloes.prpilot.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.model.SourceInventory;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SourceInventoryMcpProviderTest {
    @Nested
    class Decode {
        @Test
        void rejectsDuplicatesTrailingJsonCoercionsUnknownFieldsAndPartialNestedPayloads()
                throws Exception {
            ObjectMapper json = new ObjectMapper();
            String request =
                    json.writeValueAsString(
                            Map.of(
                                    "schemaVersion",
                                    2,
                                    "operation",
                                    "DISCOVER",
                                    "projectPath",
                                    "/worktree",
                                    "nonce",
                                    UUID.randomUUID().toString()));
            assertThat(SourceInventoryMcpProvider.decode(request))
                    .isInstanceOf(SourceInventory.DiscoverRequest.class);
            for (String malformed :
                    java.util.List.of(
                            request + " {}",
                            request.replace("\"schemaVersion\":2", "\"schemaVersion\":\"2\""),
                            request.replace(
                                    "\"schemaVersion\":2",
                                    "\"schemaVersion\":2,\"schemaVersion\":2"),
                            request.replace("\"DISCOVER\"", "\"READY\""),
                            request.replace("\"schemaVersion\":2", "\"extra\":1"),
                            request.replace("\"schemaVersion\":2", "\"schemaVersion\":1"))) {
                assertThatThrownBy(() -> SourceInventoryMcpProvider.decode(malformed))
                        .isInstanceOf(Exception.class);
            }
        }
    }

    @Nested
    class Abi {
        @Test
        void ordinary261AndUnknownAbiAdvertiseNothing() {
            assertThat(SourceInventoryMcpProvider.tools("IU-261.1")).isEmpty();
            assertThat(SourceInventoryMcpProvider.tools("unknown")).isEmpty();
            // These tests compile/run on the pinned 261 SDK: a 262 build string alone is
            // insufficient.
            assertThat(SourceInventoryMcpProvider.tools("IU-262.1")).isEmpty();
        }

        @Test
        void exactObserved262ShapeUsesCorrectFlagsAndNullAnnotations() throws Exception {
            Descriptor result =
                    (Descriptor)
                            SourceInventoryMcpProvider.Adapter262.construct(
                                    Descriptor.class,
                                    Category.class,
                                    Schema.class,
                                    Annotations.class,
                                    new Schema(),
                                    new Schema());
            assertThat(result.name).isEqualTo(SourceInventory.TOOL);
            assertThat(result.category.experimental).isFalse();
            assertThat(result.category.alwaysIncluded).isTrue();
            assertThat(result.annotations).isNull();
            assertThatThrownBy(
                            () ->
                                    SourceInventoryMcpProvider.Adapter262.construct(
                                            Object.class,
                                            Category.class,
                                            Schema.class,
                                            Annotations.class,
                                            new Schema(),
                                            new Schema()))
                    .isInstanceOf(ReflectiveOperationException.class);
        }

        @Test
        void optionalDescriptorIsSeparateAndSourceOnlySchemaIsExplicit() throws Exception {
            var schema =
                    SourceInventoryMcpProvider.schema(
                            SourceInventory.VerifyRequest.class,
                            java.util.Set.of("schemaVersion", "operation", "projectPath", "nonce"));
            assertThat(schema.getPropertiesSchema().keySet())
                    .containsExactlyInAnyOrder(
                            "schemaVersion",
                            "operation",
                            "projectPath",
                            "nonce",
                            "discoveryId",
                            "files");
            try (var stream = getClass().getResourceAsStream("/META-INF/prpilot-mcp.xml")) {
                assertThat(stream).isNotNull();
                assertThat(
                                new String(
                                        stream.readAllBytes(),
                                        java.nio.charset.StandardCharsets.UTF_8))
                        .contains("com.intellij.mcpServer", "SourceInventoryMcpProvider");
            }
        }
    }

    public static class Category {
        final boolean experimental, alwaysIncluded;

        public Category(
                String name, String qualified, boolean experimental, boolean alwaysIncluded) {
            this.experimental = experimental;
            this.alwaysIncluded = alwaysIncluded;
        }
    }

    public static class Schema {}

    public static class Annotations {}

    public static class Descriptor {
        final String name;
        final Category category;
        final Annotations annotations;

        public Descriptor(
                String name,
                String title,
                String description,
                Category category,
                String qualified,
                Schema input,
                Schema output,
                Annotations annotations) {
            this.name = name;
            this.category = category;
            this.annotations = annotations;
        }
    }
}
