package com.jinloes.prpilot.services;

import static com.jinloes.prpilot.services.SemanticSnapshotTestFixtures.event;
import static com.jinloes.prpilot.services.SemanticSnapshotTestFixtures.proxy;
import static com.jinloes.prpilot.services.SemanticSnapshotTestFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;

import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectAware;
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemRefreshStatus;
import com.jinloes.prpilot.model.SemanticReviewContext;
import com.jinloes.prpilot.model.SourceInventory;
import com.jinloes.prpilot.review.SourceInventoryFiles;
import com.jinloes.prpilot.services.SemanticSnapshotTestFixtures.NativeFixture;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SemanticSnapshotServiceTest {
    @Test
    void serviceEntryArmsBeforePairedCaptureAndVerify() throws Exception {
        try (var fixture = new NativeFixture(true)) {
            assertThat(fixture.execute(SemanticReviewContext.Operation.STATUS).getStatus())
                    .isEqualTo(SemanticReviewContext.Status.ARMING);
            assertThat(fixture.execute(SemanticReviewContext.Operation.CAPTURE).getStatus())
                    .isNotEqualTo(SemanticReviewContext.Status.READY);
            assertThat(fixture.background).hasSize(2);
            fixture.drainOne();
            assertThat(fixture.execute(SemanticReviewContext.Operation.CAPTURE).getStatus())
                    .isNotEqualTo(SemanticReviewContext.Status.READY);
            fixture.drainOne();
            fixture.reload(ExternalSystemRefreshStatus.SUCCESS);
            assertThat(fixture.background).hasSize(2);
            fixture.drainOne();
            assertThat(fixture.execute(SemanticReviewContext.Operation.CAPTURE).getStatus())
                    .isNotEqualTo(SemanticReviewContext.Status.READY);
            fixture.drainOne();
            var capture = fixture.execute(SemanticReviewContext.Operation.CAPTURE);
            assertThat(capture.getStatus())
                    .as(capture.getReasons().toString())
                    .isEqualTo(SemanticReviewContext.Status.READY);
            var verify = fixture.requestFor(SemanticReviewContext.Operation.VERIFY);
            verify.setSnapshotId(capture.getSnapshotId());
            var verified = fixture.service.execute(verify);
            assertThat(verified.getStatus()).isEqualTo(SemanticReviewContext.Status.READY);
            assertThat(verified.getSnapshotId()).isEqualTo(capture.getSnapshotId()).isNotBlank();
            assertThat(verified.getProjectInstanceId())
                    .isEqualTo(capture.getProjectInstanceId())
                    .isNotBlank();
            assertThat(verified.getNonce()).isEqualTo(verify.getNonce());
            assertThat(verified.getProjectPath()).isEqualTo(fixture.root.toString());
            assertThat(verified.getSourceDigest())
                    .isEqualTo(SourceInventory.manifest(fixture.files));
            for (String digest :
                    List.of(
                            verified.getSettingsDigest(),
                            verified.getModelDigest(),
                            verified.getCoverageIdentity())) {
                assertThat(digest).matches("[0-9a-f]{64}");
            }
            assertThat(fixture.verifies)
                    .hasSize(2)
                    .allSatisfy(
                            input -> {
                                assertThat(input.getProjectPath())
                                        .isEqualTo(fixture.root.toString());
                                assertThat(input.getDiscoveryId()).isEqualTo(fixture.discoveryId);
                                assertThat(input.getFiles())
                                        .extracting(SourceInventory.Hash::getPath)
                                        .containsExactly("Example.java");
                            });
            assertThat(fixture.verifies.get(1).getNonce()).isEqualTo(verify.getNonce());
            assertThat(fixture.execute(SemanticReviewContext.Operation.STATUS).getStatus())
                    .isEqualTo(SemanticReviewContext.Status.ELIGIBLE);
        }
    }

    @Nested
    class ServiceOrchestration {
        private void absentSettings(NativeFixture fixture) {
            fixture.settings
                    .values()
                    .forEach(
                            node -> {
                                node.present = false;
                                node.diskPresent = false;
                            });
        }

        @Test
        void absentNativeAndPhysicalSettingsPairToReadyWithoutStartIo() throws Exception {
            try (var fixture = new NativeFixture(true)) {
                absentSettings(fixture);
                assertThat(fixture.execute(SemanticReviewContext.Operation.STATUS).getStatus())
                        .isEqualTo(SemanticReviewContext.Status.ARMING);
                fixture.drainOne();
                fixture.drainOne();
                assertThat(fixture.physicalCaptures).isEqualTo(2);
                int reads =
                        fixture.settings.values().stream()
                                .mapToInt(node -> node.contentReads)
                                .sum();
                fixture.linked.forEach(input -> input.listener.onProjectReloadStart());
                assertThat(fixture.physicalCaptures).isEqualTo(2);
                assertThat(
                                fixture.settings.values().stream()
                                        .mapToInt(node -> node.contentReads)
                                        .sum())
                        .isEqualTo(reads)
                        .isZero();
                fixture.linked.forEach(
                        input ->
                                input.listener.onProjectReloadFinish(
                                        ExternalSystemRefreshStatus.SUCCESS));
                assertThat(fixture.physicalCaptures).isEqualTo(2);
                fixture.drainOne();
                fixture.drainOne();
                assertThat(fixture.physicalCaptures).isEqualTo(4);
                var capture = fixture.execute(SemanticReviewContext.Operation.CAPTURE);
                assertThat(capture.getStatus())
                        .as(capture.getReasons().toString())
                        .isEqualTo(SemanticReviewContext.Status.READY);
                var verify = fixture.requestFor(SemanticReviewContext.Operation.VERIFY);
                verify.setSnapshotId(capture.getSnapshotId());
                assertThat(fixture.service.execute(verify).getStatus())
                        .isEqualTo(SemanticReviewContext.Status.READY);
                assertThat(fixture.physicalCaptures).isEqualTo(8);
            }
        }

        @Test
        void absentProofChangeErrorAndStalePresenceRejectOldSnapshotAndLateFinish()
                throws Exception {
            Map<String, Consumer<NativeFixture>> changes = new LinkedHashMap<>();
            changes.put(
                    "changed physical absence proof",
                    f -> f.settings.values().iterator().next().physicalIdentity = "b".repeat(64));
            changes.put(
                    "physical capture error",
                    f ->
                            f.physicalHook =
                                    () -> {
                                        throw new IllegalStateException("physical capture failed");
                                    });
            changes.put(
                    "stale native absence",
                    f -> f.settings.values().iterator().next().diskPresent = true);
            changes.put(
                    "stale native presence",
                    f -> f.settings.values().iterator().next().present = true);
            for (var change : changes.entrySet()) {
                for (boolean beforeFinish : List.of(false, true)) {
                    try (var fixture = new NativeFixture(true)) {
                        absentSettings(fixture);
                        fixture.establish();
                        var old = fixture.execute(SemanticReviewContext.Operation.CAPTURE);
                        assertThat(old.getStatus()).isEqualTo(SemanticReviewContext.Status.READY);
                        if (beforeFinish) {
                            // Re-arm through STATUS, then leave the successful finish queued.
                            fixture.linked.forEach(
                                    input -> input.listener.onSettingsFilesListChange());
                            fixture.execute(SemanticReviewContext.Operation.STATUS);
                            fixture.drainOne();
                            fixture.drainOne();
                            int captures = fixture.physicalCaptures;
                            int reads =
                                    fixture.settings.values().stream()
                                            .mapToInt(node -> node.contentReads)
                                            .sum();
                            fixture.reload(ExternalSystemRefreshStatus.SUCCESS);
                            assertThat(fixture.physicalCaptures).isEqualTo(captures);
                            assertThat(
                                            fixture.settings.values().stream()
                                                    .mapToInt(node -> node.contentReads)
                                                    .sum())
                                    .isEqualTo(reads);
                        }
                        change.getValue().accept(fixture);
                        var verify = fixture.requestFor(SemanticReviewContext.Operation.VERIFY);
                        verify.setSnapshotId(old.getSnapshotId());
                        assertThat(fixture.service.execute(verify).getStatus())
                                .as(change.getKey() + " beforeFinish=" + beforeFinish)
                                .isNotEqualTo(SemanticReviewContext.Status.READY);
                        while (!fixture.background.isEmpty()) fixture.drainOne();
                        assertThat(
                                        fixture.execute(SemanticReviewContext.Operation.CAPTURE)
                                                .getStatus())
                                .as(change.getKey())
                                .isNotEqualTo(SemanticReviewContext.Status.READY);
                        fixture.physicalHook = () -> {};
                        absentSettings(fixture);
                        fixture.settings
                                .values()
                                .forEach(node -> node.physicalIdentity = "a".repeat(64));
                        if (beforeFinish
                                || change.getKey().equals("changed physical absence proof")) {
                            assertThat(fixture.service.execute(verify).getStatus())
                                    .as("Restoration must not resurrect an invalidated baseline")
                                    .isNotEqualTo(SemanticReviewContext.Status.READY);
                        }
                    }
                }
            }
        }

        @Test
        void physicalCaptureErrorCannotArmAbsentSettings() throws Exception {
            try (var fixture = new NativeFixture(true)) {
                absentSettings(fixture);
                fixture.physicalHook =
                        () -> {
                            throw new IllegalStateException("physical capture failed");
                        };
                fixture.execute(SemanticReviewContext.Operation.STATUS);
                fixture.drainOne();
                fixture.drainOne();
                fixture.physicalHook = () -> {};
                fixture.reload(ExternalSystemRefreshStatus.SUCCESS);
                while (!fixture.background.isEmpty()) fixture.drainOne();
                assertThat(fixture.execute(SemanticReviewContext.Operation.CAPTURE).getStatus())
                        .isEqualTo(SemanticReviewContext.Status.MANUAL_SYNC_REQUIRED);
            }
        }

        @Test
        void queuedFinishCannotResurrectAfterSettingsEditAndRevert() throws Exception {
            try (var fixture = new NativeFixture(true)) {
                fixture.execute(SemanticReviewContext.Operation.STATUS);
                fixture.drainOne();
                fixture.drainOne();
                fixture.reload(ExternalSystemRefreshStatus.SUCCESS);
                assertThat(fixture.background).hasSize(2);
                assertThat(fixture.execute(SemanticReviewContext.Operation.CAPTURE).getStatus())
                        .isEqualTo(SemanticReviewContext.Status.MANUAL_SYNC_REQUIRED);
                fixture.documentPath.set(fixture.linked.get(0).paths.iterator().next());
                fixture.documents.get().beforeDocumentChange(event("old", "changed"));
                fixture.documents.get().beforeDocumentChange(event("changed", "old"));
                fixture.drainOne();
                fixture.drainOne();
                assertThat(fixture.execute(SemanticReviewContext.Operation.CAPTURE).getStatus())
                        .isEqualTo(SemanticReviewContext.Status.MANUAL_SYNC_REQUIRED);
            }
        }

        @Test
        void unrelatedDocumentLeavesSettingsBaselineIntact() throws Exception {
            try (var fixture = new NativeFixture(true)) {
                fixture.establish();
                fixture.documentPath.set("/another-project/Other.java");
                fixture.documents.get().beforeDocumentChange(event("old", "new"));
                assertThat(fixture.execute(SemanticReviewContext.Operation.CAPTURE).getStatus())
                        .isEqualTo(SemanticReviewContext.Status.READY);
            }
        }

        @Test
        void preArmUnpairedFailedCancelledAndConcurrentReloadsCannotEstablishReadiness()
                throws Exception {
            for (String scenario :
                    List.of("prearm", "unpaired", "failure", "cancel", "concurrent")) {
                try (var fixture = new NativeFixture(true)) {
                    fixture.execute(SemanticReviewContext.Operation.STATUS);
                    if (!scenario.equals("prearm")) {
                        fixture.drainOne();
                        fixture.drainOne();
                    }
                    if (!scenario.equals("unpaired"))
                        fixture.linked.forEach(input -> input.listener.onProjectReloadStart());
                    if (scenario.equals("concurrent"))
                        fixture.linked.forEach(input -> input.listener.onProjectReloadStart());
                    var finish =
                            switch (scenario) {
                                case "failure" -> ExternalSystemRefreshStatus.FAILURE;
                                case "cancel" -> ExternalSystemRefreshStatus.CANCEL;
                                default -> ExternalSystemRefreshStatus.SUCCESS;
                            };
                    fixture.linked.forEach(input -> input.listener.onProjectReloadFinish(finish));
                    while (!fixture.background.isEmpty()) fixture.drainOne();
                    assertThat(fixture.execute(SemanticReviewContext.Operation.CAPTURE).getStatus())
                            .as(scenario)
                            .isEqualTo(SemanticReviewContext.Status.MANUAL_SYNC_REQUIRED);
                }
            }
        }

        @Test
        void nativeInputFailuresNeverProduceReadyOrAcceptThePriorSnapshot() throws Exception {
            Map<String, Consumer<NativeFixture>> controls = new LinkedHashMap<>();
            controls.put("one stale linked build", f -> f.linked.get(1).dataCurrent = false);
            controls.put(
                    "one stale settings tracker", f -> f.linked.get(1).settingsCurrent = false);
            controls.put("missing imported model", f -> f.linked.get(1).imported = null);
            controls.put(
                    "missing imported structure",
                    f ->
                            f.linked.get(1).imported =
                                    new SemanticSnapshotService.ImportedFacts(false, 1, 1));
            controls.put(
                    "failed import",
                    f ->
                            f.linked.get(1).imported =
                                    new SemanticSnapshotService.ImportedFacts(true, 2, 1));
            controls.put(
                    "no successful import",
                    f ->
                            f.linked.get(1).imported =
                                    new SemanticSnapshotService.ImportedFacts(true, 0, 0));
            controls.put("missing SDK", f -> f.sdk = false);
            controls.put("invalid dependency", f -> f.dependencyValid = false);
            controls.put("no modules", f -> f.noModules = true);
            controls.put("indexing", f -> f.dumb = true);
            controls.put("uncommitted PSI", f -> f.uncommitted = true);
            controls.put("unsaved source", f -> f.unsaved = List.of(f.root + "/Example.java"));
            controls.put("external task", f -> f.activeTask = true);
            controls.put(
                    "settings unsaved", f -> f.settings.values().iterator().next().unsaved = true);
            controls.put(
                    "settings uncommitted",
                    f -> f.settings.values().iterator().next().committed = false);
            controls.put(
                    "settings stamp edit revert",
                    f -> f.settings.values().iterator().next().stamp++);
            controls.put(
                    "settings physical edit revert",
                    f -> f.settings.values().iterator().next().physicalIdentity = "b".repeat(64));
            controls.put(
                    "stale VFS disk bytes",
                    f ->
                            f.settings.values().iterator().next().diskBytes =
                                    SourceInventory.utf8("changed"));
            controls.put(
                    "native hash mismatch",
                    f ->
                            f.settings.values().iterator().next().bytes =
                                    SourceInventory.utf8("changed"));
            controls.put(
                    "settings invalid VFS",
                    f -> f.settings.values().iterator().next().valid = false);
            controls.put(
                    "settings symlink", f -> f.settings.values().iterator().next().symlink = true);
            controls.put(
                    "settings unlinked on disk",
                    f -> f.settings.values().iterator().next().diskPresent = false);
            controls.put(
                    "settings missing VFS",
                    f -> f.settings.values().iterator().next().present = false);
            controls.put(
                    "settings set changed",
                    f -> f.linked.get(0).paths.add(f.root + "/settings.gradle"));
            controls.put(
                    "settings list callback",
                    f -> f.linked.get(0).listener.onSettingsFilesListChange());
            controls.put(
                    "settings before after fork",
                    f -> f.physicalHook = () -> f.settings.values().iterator().next().stamp++);
            controls.put(
                    "hash stream stamp change",
                    f -> {
                        var node = f.settings.values().iterator().next();
                        node.readHook = () -> node.stamp++;
                    });
            controls.put("source discovery rejected", f -> f.inventory.dispose());
            controls.put(
                    "epoch changes during source VERIFY", f -> f.verifyHook = () -> f.rootsEpoch++);
            controls.put("unrepresented build", f -> f.expectedOverride = Set.of());
            controls.put("removed observed build", f -> f.linked.remove(1));
            controls.put(
                    "replaced observer",
                    f ->
                            f.linked.get(1).aware =
                                    proxy(
                                            ExternalSystemProjectAware.class,
                                            (p, method, args) -> {
                                                throw new AssertionError(
                                                        "Replacement must not be trusted");
                                            }));
            controls.put("disposed service", f -> f.service.dispose());
            for (var control : controls.entrySet()) {
                try (var fixture = new NativeFixture(true)) {
                    fixture.establish();
                    var previous = fixture.execute(SemanticReviewContext.Operation.CAPTURE);
                    control.getValue().accept(fixture);
                    var input = fixture.requestFor(SemanticReviewContext.Operation.VERIFY);
                    input.setSnapshotId(previous.getSnapshotId());
                    var result = fixture.service.execute(input);
                    assertThat(result.getStatus())
                            .as(control.getKey())
                            .isIn(
                                    SemanticReviewContext.Status.NOT_READY,
                                    SemanticReviewContext.Status.MANUAL_SYNC_REQUIRED,
                                    SemanticReviewContext.Status.UNSUPPORTED);
                    assertThat(result.getReasons()).as(control.getKey()).isNotEmpty();
                }
            }
        }

        @Test
        void wrongSnapshotAndChangedEpochRejectWhileReopenNeedsNewPairedImport() throws Exception {
            for (Consumer<NativeFixture> mutate :
                    List.<Consumer<NativeFixture>>of(
                            f -> f.rootsEpoch++, f -> f.psiEpoch++, f -> f.vfsEpoch++)) {
                try (var fixture = new NativeFixture(true)) {
                    fixture.establish();
                    assertThat(fixture.execute(SemanticReviewContext.Operation.VERIFY).getReasons())
                            .containsExactly("STALE_SNAPSHOT");
                    var capture = fixture.execute(SemanticReviewContext.Operation.CAPTURE);
                    mutate.accept(fixture);
                    var verify = fixture.requestFor(SemanticReviewContext.Operation.VERIFY);
                    verify.setSnapshotId(capture.getSnapshotId());
                    assertThat(fixture.service.execute(verify).getReasons())
                            .containsExactly("STALE_SNAPSHOT");
                    fixture.service.dispose();
                    var reopened =
                            new SemanticSnapshotService(
                                    fixture.project, (listener, owner) -> {}, d -> null, fixture);
                    try {
                        assertThat(
                                        reopened.execute(
                                                        fixture.requestFor(
                                                                SemanticReviewContext.Operation
                                                                        .CAPTURE))
                                                .getStatus())
                                .isEqualTo(SemanticReviewContext.Status.MANUAL_SYNC_REQUIRED);
                        assertThat(
                                        reopened.execute(
                                                        fixture.requestFor(
                                                                SemanticReviewContext.Operation
                                                                        .STATUS))
                                                .getStatus())
                                .isEqualTo(SemanticReviewContext.Status.ARMING);
                        while (!fixture.background.isEmpty()) fixture.drainOne();
                        assertThat(
                                        reopened.execute(
                                                        fixture.requestFor(
                                                                SemanticReviewContext.Operation
                                                                        .CAPTURE))
                                                .getStatus())
                                .isEqualTo(SemanticReviewContext.Status.MANUAL_SYNC_REQUIRED);
                    } finally {
                        reopened.dispose();
                    }
                }
            }
        }
    }

    @Nested
    class NativeCallbacks {
        @Test
        void actualDocumentSubscriptionInvalidatesArmedAndCompletedImportOnEditRevert()
                throws Exception {
            try (var fixture = new NativeFixture()) {
                var observed = fixture.observe(Set.of("/fixture/build.gradle"));
                var original = receipt(1, "b".repeat(64));
                for (boolean completed : List.of(false, true)) {
                    observed.baseline().armed(observed.baseline().arm(), original);
                    if (completed) {
                        observed.baseline().start(List.of(1L));
                        observed.baseline().finish(observed.baseline().success(), original);
                    }
                    fixture.documentPath.set("/fixture/build.gradle");
                    fixture.documents.get().beforeDocumentChange(event("old", "edited"));
                    fixture.documents.get().beforeDocumentChange(event("edited", "old"));
                    assertThat(observed.baseline().phase())
                            .isEqualTo(SemanticSnapshotService.Phase.UNARMED);
                    assertThat(observed.baseline().current(original)).isFalse();
                }
                assertThat(fixture.documentOwner.get()).isSameAs(fixture.service);
            }
        }

        @Test
        void unrelatedDocumentDoesNotInvalidateButSettingsEditDefeatsLateFinish() throws Exception {
            try (var fixture = new NativeFixture()) {
                var observed = fixture.observe(Set.of("/fixture/build.gradle"));
                var original = receipt(1, "b".repeat(64));
                observed.baseline().armed(observed.baseline().arm(), original);
                fixture.documentPath.set("/fixture/Other.java");
                fixture.documents.get().beforeDocumentChange(event("old", "new"));
                assertThat(observed.baseline().phase())
                        .isEqualTo(SemanticSnapshotService.Phase.ARMED);
                observed.baseline().start(List.of(1L));
                long token = observed.baseline().success();
                fixture.documentPath.set("/fixture/build.gradle");
                fixture.documents.get().beforeDocumentChange(event("old", "new"));
                observed.baseline().finish(token, original);
                assertThat(observed.baseline().phase())
                        .isEqualTo(SemanticSnapshotService.Phase.UNARMED);
            }
        }

        @Test
        void subscribedReloadCallbacksRejectFailureCancellationUnpairedAndPrearmStart()
                throws Exception {
            try (var fixture = new NativeFixture()) {
                var observed = fixture.observe(Set.of());
                var original = new SemanticSnapshotService.Receipt(List.of(), List.of());
                for (var status :
                        List.of(
                                ExternalSystemRefreshStatus.FAILURE,
                                ExternalSystemRefreshStatus.CANCEL)) {
                    observed.baseline().armed(observed.baseline().arm(), original);
                    observed.listener().onProjectReloadStart();
                    assertThat(observed.baseline().phase())
                            .isEqualTo(SemanticSnapshotService.Phase.STARTED);
                    observed.listener().onProjectReloadFinish(status);
                    assertThat(observed.baseline().phase())
                            .isEqualTo(SemanticSnapshotService.Phase.UNARMED);
                }
                long token = observed.baseline().arm();
                observed.listener().onProjectReloadStart();
                observed.baseline().armed(token, original);
                observed.listener().onProjectReloadFinish(ExternalSystemRefreshStatus.SUCCESS);
                assertThat(observed.baseline().phase())
                        .isEqualTo(SemanticSnapshotService.Phase.UNARMED);
                assertThat(fixture.background).isEmpty();
                assertThat(observed.owner()).isSameAs(fixture.service);
            }
        }

        @Test
        void subscribedSettingsListChangeAndDisposalInvalidateEveryBuild() throws Exception {
            try (var fixture = new NativeFixture()) {
                var first = fixture.observe(Set.of("/fixture/build.gradle"));
                var second = fixture.observe(Set.of("/fixture/included/build.gradle"));
                first.baseline().armed(first.baseline().arm(), receipt(1, "b".repeat(64)));
                second.baseline().armed(second.baseline().arm(), receipt(1, "c".repeat(64)));
                first.listener().onSettingsFilesListChange();
                assertThat(first.baseline().phase())
                        .isEqualTo(SemanticSnapshotService.Phase.UNARMED);
                assertThat(second.baseline().phase())
                        .isEqualTo(SemanticSnapshotService.Phase.ARMED);
                fixture.service.dispose();
                assertThat(second.baseline().phase())
                        .isEqualTo(SemanticSnapshotService.Phase.UNARMED);
                for (var operation : SemanticReviewContext.Operation.values()) {
                    var response = fixture.service.execute(request(operation));
                    assertThat(response.getStatus())
                            .isEqualTo(SemanticReviewContext.Status.NOT_READY);
                    assertThat(response.getReasons())
                            .containsExactly("Project closed; manual sync required");
                }
            }
        }

        @Test
        void actualServiceEntryRejectsWrongProjectSdkAndUnknownTrackerBeforeAuthority()
                throws Exception {
            try (var fixture = new NativeFixture()) {
                for (var operation : SemanticReviewContext.Operation.values()) {
                    var request = request(operation);
                    request.setProjectPath("/wrong");
                    assertThat(fixture.service.execute(request).getReasons())
                            .containsExactly("WRONG_PROJECT");
                    fixture.info.build = "IU-261.1";
                    request.setProjectPath("/fixture");
                    assertThat(fixture.service.execute(request).getStatus())
                            .isEqualTo(SemanticReviewContext.Status.UNSUPPORTED);
                    fixture.info.build = "IU-262.1";
                    assertThat(fixture.service.execute(request).getStatus())
                            .isEqualTo(SemanticReviewContext.Status.UNSUPPORTED);
                }
                assertThat(fixture.background).isEmpty();
            }
        }
    }

    @Nested
    class DeclarationLimitations {
        @Test
        void unsupportedAmbiguousAndCappedDeclarationsRemainExplicit() throws Exception {
            var evidence = new SemanticSnapshotService.DeclarationEvidence();
            evidence.add("A.java", 1, java.util.Set.of());
            evidence.add("A.java", 2, java.util.Set.of("example.A#a", "other.A#a"));
            for (int i = 0; i < 23; i++) {
                evidence.add("A.java", i + 3, java.util.Set.of("example.A#m" + i));
            }
            assertThat(evidence.values()).hasSize(20);
            assertThat(evidence.limitations())
                    .containsExactly(
                            "AMBIGUOUS_IDENTITY: 1 omitted observations",
                            "DECLARATION_LIMIT: 3 omitted observations",
                            "UNSUPPORTED_IDENTITY: 1 omitted observations");
            var snapshot = new com.jinloes.prpilot.model.SemanticReviewContext.Snapshot();
            snapshot.setDeclarations(evidence.values());
            snapshot.setDeclarationLimitations(evidence.limitations());
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            assertThat(
                            json.readValue(
                                            json.writeValueAsBytes(snapshot),
                                            com.jinloes.prpilot.model.SemanticReviewContext.Snapshot
                                                    .class)
                                    .getDeclarationLimitations())
                    .isEqualTo(evidence.limitations());
        }

        @Test
        void overlappingRangesDoNotConsumeCapacityAndDiagnosticsStayBounded() {
            var evidence = new SemanticSnapshotService.DeclarationEvidence();
            for (int i = 0; i < 1000; i++) {
                evidence.add("A.java", 1, java.util.Set.of("example.A"));
                evidence.omit("UNSUPPORTED_PSI_FILE");
            }
            evidence.add("A.java", 2, java.util.Set.of("x".repeat(4097)));
            assertThat(evidence.values()).hasSize(1);
            assertThat(evidence.limitations())
                    .containsExactly(
                            "IDENTITY_SIZE_LIMIT: 1 omitted observations",
                            "UNSUPPORTED_PSI_FILE: 1000 omitted observations");
        }
    }

    private static SemanticSnapshotService.Receipt receipt(long stamp, String identity) {
        return new SemanticSnapshotService.Receipt(
                List.of(
                        new SemanticSnapshotService.NativeSetting(
                                "/fixture/build.gradle", true, "a".repeat(64), stamp)),
                List.of(
                        new SourceInventoryFiles.Setting(
                                "/fixture/build.gradle", true, "a".repeat(64), identity)));
    }

    @Nested
    class PairedImport {
        @Test
        void onlyCompletedArmingThenPairedUnchangedSuccessEstablishesBaseline() {
            var baseline = new SemanticSnapshotService.ImportBaseline();
            var receipt = receipt(1, "b".repeat(64));
            long arm = baseline.arm();
            baseline.armed(arm, receipt);
            assertThat(baseline.phase()).isEqualTo(SemanticSnapshotService.Phase.ARMED);
            baseline.start(List.of(1L));
            long finish = baseline.success();
            assertThat(baseline.phase()).isEqualTo(SemanticSnapshotService.Phase.VERIFYING);
            baseline.finish(finish, receipt);
            assertThat(baseline.phase()).isEqualTo(SemanticSnapshotService.Phase.BASELINED);
            assertThat(baseline.current(receipt)).isTrue();
            assertThat(baseline.current(receipt(1, "c".repeat(64)))).isFalse();
        }

        @Test
        void startBeforeArmCompletionAndUnpairedSuccessCannotResurrectAuthority() {
            var baseline = new SemanticSnapshotService.ImportBaseline();
            long arm = baseline.arm();
            baseline.start(List.of(1L));
            baseline.armed(arm, receipt(1, "b".repeat(64)));
            assertThat(baseline.success()).isEqualTo(-1);
            assertThat(baseline.phase()).isEqualTo(SemanticSnapshotService.Phase.UNARMED);
        }

        @Test
        void invalidationDefeatsLateFinishAndChangedOrConcurrentStartsDiscardReceipts() {
            var baseline = new SemanticSnapshotService.ImportBaseline();
            var original = receipt(1, "b".repeat(64));
            baseline.armed(baseline.arm(), original);
            baseline.start(List.of(1L));
            long token = baseline.success();
            baseline.invalidate();
            baseline.finish(token, original);
            assertThat(baseline.phase()).isEqualTo(SemanticSnapshotService.Phase.UNARMED);
            assertThat(baseline.current(original)).isFalse();
            baseline.armed(baseline.arm(), receipt(1, "b".repeat(64)));
            baseline.start(List.of(2L));
            assertThat(baseline.phase()).isEqualTo(SemanticSnapshotService.Phase.UNARMED);
            baseline.armed(baseline.arm(), receipt(1, "b".repeat(64)));
            baseline.start(List.of(1L));
            baseline.start(List.of(1L));
            assertThat(baseline.success()).isEqualTo(-1);
        }

        @Test
        void unchangedContentsWithReplacedFileOrNativeStampNeverPassFinish() {
            for (var changed : List.of(receipt(2, "b".repeat(64)), receipt(1, "c".repeat(64)))) {
                var baseline = new SemanticSnapshotService.ImportBaseline();
                baseline.armed(baseline.arm(), receipt(1, "b".repeat(64)));
                baseline.start(List.of(1L));
                baseline.finish(baseline.success(), changed);
                assertThat(baseline.phase()).isEqualTo(SemanticSnapshotService.Phase.UNARMED);
            }
        }
    }
}
