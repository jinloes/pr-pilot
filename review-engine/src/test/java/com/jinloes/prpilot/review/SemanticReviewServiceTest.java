package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.model.SemanticReviewContext;
import com.jinloes.prpilot.model.SourceInventory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SemanticReviewServiceTest {
    @Test
    void doesNotInventADeclarationColumnForPositionBasedQueries() throws Exception {
        Backend backend = new Backend(temporary.toRealPath());
        try (var execution =
                new SemanticReviewService.Execution(backend.root, backend, () -> {}, DIFF)) {
            execution.collect();
            assertThat(backend.calls).doesNotContain("get_symbol_info").contains("search_symbol");
            assertThat(execution.context().getLimitations()).anyMatch(s -> s.contains("column"));
        }
    }

    @TempDir Path temporary;
    static final String DIFF =
            "diff --git a/Main.java b/Main.java\n--- a/Main.java\n+++ b/Main.java\n@@ -1 +1 @@\n-old\n+new\n";

    static class Backend implements SemanticReviewService.Backend {
        final Path root;
        final List<String> calls = new ArrayList<>();
        SourceInventoryClient.SourceCoverage coverage;
        String snapshotId;
        String epochs = "stable";
        String physical = "a".repeat(64);
        String instance = UUID.randomUUID().toString();
        String evidence = "untrusted text-tree: caller -> Main#work";
        boolean invalidateQuery;
        boolean eligible = true;
        int collections;
        int expectedRanges = 1;
        List<String> sourcePaths = List.of("Main.java");

        Backend(Path root) {
            this.root = root;
        }

        @Override
        public SourceInventoryClient.SourceCoverage collect() {
            calls.add("PHYSICAL");
            collections++;
            var discovery = new SourceInventory.Discovery();
            discovery.setDiscoveryId(UUID.randomUUID().toString());
            discovery.setProjectPath(root.toString());
            discovery.setProjectInstanceId("inventory-instance-distinct-from-snapshot");
            discovery.setIdeBuild("262");
            discovery.setEpochs(new SourceInventory.Epochs());
            discovery.setModel(new SourceInventory.Model());
            discovery.setEntries(List.of());
            var hashes =
                    sourcePaths.stream()
                            .map(
                                    path -> {
                                        var hash = new SourceInventory.Hash();
                                        hash.setPath(path);
                                        hash.setSha256("b".repeat(64));
                                        return hash;
                                    })
                            .toList();
            var receipt = new SourceInventory.Coverage();
            receipt.setDiscoveryId(discovery.getDiscoveryId());
            receipt.setSourceManifestSha256(SourceInventory.manifest(hashes));
            coverage =
                    new SourceInventoryClient.SourceCoverage(
                            "c".repeat(40), discovery, hashes, receipt, physical);
            return coverage;
        }

        @Override
        public SemanticReviewContext.Snapshot snapshot(SemanticReviewContext.Request request)
                throws IOException {
            calls.add(request.getOperation().toString());
            SemanticReviewContext.validate(request);
            var snapshot = new SemanticReviewContext.Snapshot();
            snapshot.setNonce(request.getNonce());
            snapshot.setProjectPath(root.toString());
            snapshot.setIdeBuild("262");
            snapshot.setProjectInstanceId(instance);
            snapshot.setStatus(
                    eligible
                            ? SemanticReviewContext.Status.ELIGIBLE
                            : SemanticReviewContext.Status.ARMING);
            if (request.getOperation() == SemanticReviewContext.Operation.STATUS) return snapshot;
            assertThat(request.getDiscoveryId()).isEqualTo(coverage.discovery().getDiscoveryId());
            assertThat(request.getFiles()).hasSize(sourcePaths.size());
            assertThat(request.getChangedRanges()).hasSize(expectedRanges);
            if (request.getOperation() == SemanticReviewContext.Operation.CAPTURE)
                snapshotId = UUID.randomUUID().toString();
            else assertThat(request.getSnapshotId()).isEqualTo(snapshotId);
            snapshot.setSnapshotId(snapshotId);
            snapshot.setStatus(SemanticReviewContext.Status.READY);
            snapshot.setCoverageIdentity(
                    SemanticSkillBundle.sha256(
                            new ObjectMapper().writeValueAsBytes(coverage.coverage())));
            snapshot.setSourceDigest(coverage.coverage().getSourceManifestSha256());
            snapshot.setModelDigest("d".repeat(64));
            snapshot.setSettingsDigest("e".repeat(64));
            snapshot.setEpochs(epochs);
            var declaration = new SemanticReviewContext.Declaration();
            declaration.setPath(sourcePaths.get(0));
            declaration.setQualifiedName("Main#work");
            declaration.setLine(1);
            snapshot.setDeclarations(List.of(declaration));
            snapshot.setDeclarationLimitations(List.of("One native ambiguity omitted."));
            return snapshot;
        }

        @Override
        public String query(String tool, Map<String, Object> args) throws IOException {
            calls.add(tool);
            IjctlClient.validateArguments(tool, args);
            if (tool.equals("analyze_calls"))
                assertThat(args.get("symbolFqn")).isEqualTo("Main#work");
            if (invalidateQuery) epochs = "changed";
            return evidence;
        }
    }

    @Test
    void bracketsActualCollectorQueriesAndReacquiresFreshDiscoveryWithoutEquatingInstances()
            throws Exception {
        Backend backend = new Backend(temporary.toRealPath());
        AtomicBoolean released = new AtomicBoolean();
        try (var execution =
                new SemanticReviewService.Execution(
                        backend.root, backend, () -> released.set(true), DIFF)) {
            execution.collect();
            assertThat(backend.collections).isEqualTo(2);
            assertThat(backend.calls.subList(0, 3))
                    .containsExactly("STATUS", "PHYSICAL", "CAPTURE");
            for (int i = 0; i < backend.calls.size(); i++) {
                if (backend.calls.get(i).startsWith("get_")
                        || backend.calls.get(i).equals("analyze_calls")) {
                    assertThat(backend.calls.get(i - 1)).isEqualTo("VERIFY");
                    assertThat(backend.calls.get(i + 1)).isEqualTo("VERIFY");
                }
            }
            execution.validate();
            assertThat(backend.collections).isEqualTo(3);
            assertThat(execution.context().getEvidence()).contains("untrusted text-tree");
            assertThat(execution.context().getLimitations())
                    .contains("One native ambiguity omitted.");
            execution.context().setEvidence("replacement");
            assertThat(execution.context().getEvidence()).doesNotContain("replacement");
            assertThat(released).isFalse();
        }
        assertThat(released).isTrue();
    }

    @Test
    void readinessPauseStartsNeitherPhysicalCollectionNorQueries() throws Exception {
        Backend backend = new Backend(temporary.toRealPath());
        backend.eligible = false;
        try (var execution =
                new SemanticReviewService.Execution(backend.root, backend, () -> {}, DIFF)) {
            assertThatThrownBy(execution::collect)
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("manually sync");
            assertThat(backend.calls).containsExactly("STATUS");
            assertThatThrownBy(execution::validate).isInstanceOf(IOException.class);
        }
    }

    @Test
    void queryInvalidationFailsAndCannotBeResurrectedByRevertingState() throws Exception {
        Backend backend = new Backend(temporary.toRealPath());
        backend.invalidateQuery = true;
        try (var execution =
                new SemanticReviewService.Execution(backend.root, backend, () -> {}, DIFF)) {
            assertThatThrownBy(execution::collect)
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("invalidated");
            backend.epochs = "stable";
            assertThatThrownBy(execution::validate).isInstanceOf(IOException.class);
        }
    }

    @Test
    void physicalEditRevertAndProjectReopenInvalidateFutureStages() throws Exception {
        for (boolean physicalChange : List.of(true, false)) {
            Backend backend = new Backend(temporary.toRealPath());
            try (var execution =
                    new SemanticReviewService.Execution(backend.root, backend, () -> {}, DIFF)) {
                execution.collect();
                if (physicalChange) backend.physical = "f".repeat(64);
                else backend.instance = UUID.randomUUID().toString();
                assertThatThrownBy(execution::validate)
                        .isInstanceOf(IOException.class)
                        .hasMessageContaining("invalidated");
            }
        }
    }

    @Test
    void boundedEvidenceRetainsTruncationAndNeverTruncatesAuthorityChecks() throws Exception {
        Backend backend = new Backend(temporary.toRealPath());
        backend.evidence = "🧪".repeat(20000);
        try (var execution =
                new SemanticReviewService.Execution(backend.root, backend, () -> {}, DIFF)) {
            execution.collect();
            assertThat(
                            execution
                                    .context()
                                    .getEvidence()
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                                    .length)
                    .isLessThanOrEqualTo(65536);
            assertThat(execution.context().getLimitations()).anyMatch(s -> s.contains("64 KiB"));
            assertThat(backend.collections).isEqualTo(2);
            execution.validate();
            assertThat(backend.collections).isEqualTo(3);
        }
    }
}
