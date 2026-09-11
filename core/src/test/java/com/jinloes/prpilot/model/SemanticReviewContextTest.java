package com.jinloes.prpilot.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SemanticReviewContextTest {
    @Nested
    class Limitations {
        @Test
        void snapshotAndPromptRetainDefensiveBoundedLimitations() throws Exception {
            var input = new java.util.ArrayList<>(List.of("AMBIGUOUS_IDENTITY: 1"));
            var snapshot = new SemanticReviewContext.Snapshot();
            snapshot.setDeclarationLimitations(input);
            var context = new SemanticReviewContext();
            context.setLimitations(input);
            input.clear();
            assertThat(snapshot.getDeclarationLimitations())
                    .containsExactly("AMBIGUOUS_IDENTITY: 1");
            assertThat(context.getLimitations()).containsExactly("AMBIGUOUS_IDENTITY: 1");
            assertThatThrownBy(() -> context.getLimitations().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
            for (var invalid :
                    List.of(
                            List.of(" "),
                            List.of("x".repeat(8193)),
                            java.util.Collections.nCopies(65, "omitted"))) {
                assertThatThrownBy(() -> snapshot.setDeclarationLimitations(invalid))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> context.setLimitations(invalid))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    @Nested
    class Wire {
        @Test
        void unknownMissingAndCoercedFieldsAreRejected() {
            var status =
                    Map.of(
                            "schemaVersion",
                            2,
                            "operation",
                            "STATUS",
                            "projectPath",
                            "/fixture",
                            "nonce",
                            UUID.randomUUID().toString());
            SemanticReviewContext.validateWire(status);
            var bad = new java.util.HashMap<String, Object>(status);
            bad.put("snapshotId", UUID.randomUUID().toString());
            assertThatThrownBy(() -> SemanticReviewContext.validateWire(bad))
                    .isInstanceOf(IllegalArgumentException.class);
            bad.remove("snapshotId");
            bad.put("schemaVersion", "2");
            assertThatThrownBy(() -> SemanticReviewContext.validateWire(bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void mutableInventoryAndRangesCannotAlterRequestAfterCopy() {
            var hash = new SourceInventory.Hash();
            hash.setPath("A.java");
            hash.setSha256("a".repeat(64));
            var request = new SemanticReviewContext.Request();
            request.setFiles(List.of(hash));
            hash.setPath("B.java");
            request.getFiles().get(0).setPath("C.java");
            assertThat(request.getFiles().get(0).getPath()).isEqualTo("A.java");
            var range = new SemanticReviewContext.Range();
            range.setPath("A.java");
            range.setStartLine(1);
            range.setEndLine(2);
            request.setChangedRanges(List.of(range));
            range.setEndLine(999);
            assertThat(request.getChangedRanges().get(0).getEndLine()).isEqualTo(2);
        }
    }
}
