package com.jinloes.prpilot.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SourceInventoryTest {
    @Nested
    class WireContract {
        private Map<String, Object> request() {
            return new LinkedHashMap<>(
                    Map.of(
                            "schemaVersion",
                            2,
                            "operation",
                            "DISCOVER",
                            "projectPath",
                            "/worktree",
                            "nonce",
                            "00000000-0000-0000-0000-000000000001"));
        }

        @Test
        void requiresExactFieldsAndTypes() {
            Map<String, Object> request = request();
            SourceInventory.validateWire(request, SourceInventory.DiscoverRequest.class);
            request.put("schemaVersion", "1");
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    SourceInventory.validateWire(
                                            request, SourceInventory.DiscoverRequest.class));
            request.put("schemaVersion", 2);
            request.put("extra", true);
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    SourceInventory.validateWire(
                                            request, SourceInventory.DiscoverRequest.class));
            request.remove("extra");
            request.remove("nonce");
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    SourceInventory.validateWire(
                                            request, SourceInventory.DiscoverRequest.class));
        }

        @Test
        void rejectsUnknownEnumsAndNullArrays() {
            Map<String, Object> request = request();
            request.put("operation", "READY");
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    SourceInventory.validateWire(
                                            request, SourceInventory.DiscoverRequest.class));
            request.put("operation", "VERIFY");
            request.put("discoveryId", "00000000-0000-0000-0000-000000000002");
            request.put("files", null);
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    SourceInventory.validateWire(
                                            request, SourceInventory.VerifyRequest.class));
        }

        @Test
        void sourceRequiresAuthorityNotOnlyAPath() {
            SourceInventory.Entry entry = new SourceInventory.Entry();
            entry.setPath("extensionless");
            entry.setKind(SourceInventory.Kind.FILE);
            entry.setMembership(SourceInventory.Membership.SOURCE);
            entry.setModules(List.of());
            entry.setGenerated(false);
            entry.setTest(false);
            assertThatIllegalArgumentException().isThrownBy(() -> SourceInventory.validate(entry));
            entry.setModules(List.of("main"));
            entry.setSourceRootUrl("file:///worktree/src");
            SourceInventory.validate(entry);
            entry.setMembership(SourceInventory.Membership.OUTSIDE_SOURCE);
            assertThatIllegalArgumentException().isThrownBy(() -> SourceInventory.validate(entry));
        }

        @Test
        void requiresNullableFieldsEvenWhenNull() {
            Map<String, Object> location = new LinkedHashMap<>();
            location.put("url", "jar://sdk");
            location.put("scope", "NON_LOCAL");
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    SourceInventory.validateWire(
                                            location, SourceInventory.Location.class));
            location.put("nativePath", null);
            SourceInventory.validateWire(location, SourceInventory.Location.class);
        }

        @Test
        void v1CannotMasqueradeAsVfsEvidence() {
            var request = new SourceInventory.DiscoverRequest();
            request.setSchemaVersion(1);
            request.setOperation(SourceInventory.Operation.DISCOVER);
            request.setProjectPath("/worktree");
            request.setNonce("00000000-0000-0000-0000-000000000001");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> SourceInventory.validate(request));
            request.setSchemaVersion(2);
            SourceInventory.validate(request);
            assertThat(SourceInventory.Status.values())
                    .extracting(Enum::name)
                    .contains("VFS_VERIFIED")
                    .doesNotContain("COVERED", "READY");
        }
    }

    @Nested
    class Manifest {
        @Test
        void usesLengthPrefixedUtf8AndRawHashBytes() {
            String zero = "0".repeat(64);
            byte[] path = "é".getBytes(StandardCharsets.UTF_8);
            ByteBuffer expected = ByteBuffer.allocate(4 + path.length + 32);
            expected.putInt(path.length).put(path).put(new byte[32]);
            assertThat(SourceInventory.manifest(List.of(new SourceInventory.Hash("é", zero))))
                    .isEqualTo(
                            HexFormat.of()
                                    .formatHex(
                                            SourceInventory.digest("SHA-256")
                                                    .digest(expected.array())));
        }

        @Test
        void rejectsDuplicatesEmptyUnsafeAndMalformedHash() {
            var hash = new SourceInventory.Hash("a", "0".repeat(64));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> SourceInventory.manifest(List.of()));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> SourceInventory.manifest(List.of(hash, hash)));
            for (String path : List.of("../a", "/a", "a//b", "a\\b", "a/./b", "a\0b", "\ud800")) {
                assertThatIllegalArgumentException().isThrownBy(() -> SourceInventory.path(path));
            }
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    SourceInventory.manifest(
                                            List.of(
                                                    new SourceInventory.Hash(
                                                            "a", "A".repeat(64)))));
        }

        @Test
        void canonicalOrderingUsesUnsignedUtf8() {
            assertThat(SourceInventory.sorted(List.of("😀", "\ue000", "a")))
                    .containsExactly("a", "\ue000", "😀");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> SourceInventory.ordered(List.of("b", "a")));
        }
    }
}
