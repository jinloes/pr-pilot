package com.jinloes.prpilot.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectAware;
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectId;
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectListener;
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectTracker;
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemRefreshStatus;
import com.intellij.openapi.externalSystem.model.ProjectSystemId;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.BuildNumber;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.openapi.vfs.VFileProperty;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileSystem;
import com.intellij.util.messages.MessageBus;
import com.intellij.util.messages.MessageBusConnection;
import com.jinloes.prpilot.model.SemanticReviewContext;
import com.jinloes.prpilot.model.SourceInventory;
import com.jinloes.prpilot.review.SourceInventoryClient;
import com.jinloes.prpilot.review.SourceInventoryFiles;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
                    observed.baseline.armed(observed.baseline.arm(), original);
                    if (completed) {
                        observed.baseline.start(List.of(1L));
                        observed.baseline.finish(observed.baseline.success(), original);
                    }
                    fixture.documentPath.set("/fixture/build.gradle");
                    fixture.documents.get().beforeDocumentChange(event("old", "edited"));
                    fixture.documents.get().beforeDocumentChange(event("edited", "old"));
                    assertThat(observed.baseline.phase())
                            .isEqualTo(SemanticSnapshotService.Phase.UNARMED);
                    assertThat(observed.baseline.current(original)).isFalse();
                }
                assertThat(fixture.documentOwner.get()).isSameAs(fixture.service);
            }
        }

        @Test
        void unrelatedDocumentDoesNotInvalidateButSettingsEditDefeatsLateFinish() throws Exception {
            try (var fixture = new NativeFixture()) {
                var observed = fixture.observe(Set.of("/fixture/build.gradle"));
                var original = receipt(1, "b".repeat(64));
                observed.baseline.armed(observed.baseline.arm(), original);
                fixture.documentPath.set("/fixture/Other.java");
                fixture.documents.get().beforeDocumentChange(event("old", "new"));
                assertThat(observed.baseline.phase())
                        .isEqualTo(SemanticSnapshotService.Phase.ARMED);
                observed.baseline.start(List.of(1L));
                long token = observed.baseline.success();
                fixture.documentPath.set("/fixture/build.gradle");
                fixture.documents.get().beforeDocumentChange(event("old", "new"));
                observed.baseline.finish(token, original);
                assertThat(observed.baseline.phase())
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
                    observed.baseline.armed(observed.baseline.arm(), original);
                    observed.listener.onProjectReloadStart();
                    assertThat(observed.baseline.phase())
                            .isEqualTo(SemanticSnapshotService.Phase.STARTED);
                    observed.listener.onProjectReloadFinish(status);
                    assertThat(observed.baseline.phase())
                            .isEqualTo(SemanticSnapshotService.Phase.UNARMED);
                }
                long token = observed.baseline.arm();
                observed.listener.onProjectReloadStart();
                observed.baseline.armed(token, original);
                observed.listener.onProjectReloadFinish(ExternalSystemRefreshStatus.SUCCESS);
                assertThat(observed.baseline.phase())
                        .isEqualTo(SemanticSnapshotService.Phase.UNARMED);
                assertThat(fixture.background).isEmpty();
                assertThat(observed.owner).isSameAs(fixture.service);
            }
        }

        @Test
        void subscribedSettingsListChangeAndDisposalInvalidateEveryBuild() throws Exception {
            try (var fixture = new NativeFixture()) {
                var first = fixture.observe(Set.of("/fixture/build.gradle"));
                var second = fixture.observe(Set.of("/fixture/included/build.gradle"));
                first.baseline.armed(first.baseline.arm(), receipt(1, "b".repeat(64)));
                second.baseline.armed(second.baseline.arm(), receipt(1, "c".repeat(64)));
                first.listener.onSettingsFilesListChange();
                assertThat(first.baseline.phase()).isEqualTo(SemanticSnapshotService.Phase.UNARMED);
                assertThat(second.baseline.phase()).isEqualTo(SemanticSnapshotService.Phase.ARMED);
                fixture.service.dispose();
                assertThat(second.baseline.phase())
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

    static SemanticReviewContext.Request request(SemanticReviewContext.Operation operation) {
        var request = new SemanticReviewContext.Request();
        request.setSchemaVersion(2);
        request.setOperation(operation);
        request.setProjectPath("/fixture");
        request.setNonce(UUID.randomUUID().toString());
        if (operation != SemanticReviewContext.Operation.STATUS) {
            request.setDiscoveryId(UUID.randomUUID().toString());
            request.setFiles(List.of());
            request.setChangedRanges(List.of());
        }
        if (operation == SemanticReviewContext.Operation.VERIFY)
            request.setSnapshotId(UUID.randomUUID().toString());
        return request;
    }

    private static DocumentEvent event(String oldText, String newText) {
        Document document = proxy(Document.class, (p, method, args) -> null);
        return new DocumentEvent(document) {
            @Override
            public int getOffset() {
                return 0;
            }

            @Override
            public int getOldLength() {
                return oldText.length();
            }

            @Override
            public int getNewLength() {
                return newText.length();
            }

            @Override
            public CharSequence getOldFragment() {
                return oldText;
            }

            @Override
            public CharSequence getNewFragment() {
                return newText;
            }

            @Override
            public long getOldTimeStamp() {
                return 1;
            }
        };
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(
                Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class<?>[] {type},
                        (instance, method, arguments) -> {
                            if (method.getDeclaringClass() == Object.class) {
                                return switch (method.getName()) {
                                    case "toString" -> "fixture:" + type.getSimpleName();
                                    case "hashCode" -> System.identityHashCode(instance);
                                    case "equals" -> instance == arguments[0];
                                    default -> throw new AssertionError(method);
                                };
                            }
                            return handler.invoke(instance, method, arguments);
                        }));
    }

    /**
     * Runs real service/callback bodies; no IDE, import, physical worker or provider is launched.
     */
    static final class NativeFixture
            implements AutoCloseable, SemanticSnapshotService.NativeAccess {
        final Application previous = ApplicationManager.getApplication();
        final FakeInfo info = new FakeInfo();
        final List<Runnable> background = new ArrayList<>();
        final AtomicReference<DocumentListener> documents = new AtomicReference<>();
        final AtomicReference<Disposable> documentOwner = new AtomicReference<>();
        final AtomicReference<String> documentPath = new AtomicReference<>();
        final Map<Object, Object> subscriptions = new HashMap<>();
        int snapshotServiceRequests;
        final Project project;
        final SemanticSnapshotService service;
        final Path root;
        final List<BuildInput> linked = new ArrayList<>();
        final Map<String, SettingNode> settings = new LinkedHashMap<>();
        final List<SourceInventory.VerifyRequest> verifies = new ArrayList<>();
        final List<SourceInventory.Hash> files = new ArrayList<>();
        SourceInventoryService inventory;
        String discoveryId;
        boolean dumb, uncommitted, activeTask, sdk = true, dependencyValid = true, noModules;
        long rootsEpoch, psiEpoch, vfsEpoch;
        List<String> unsaved = List.of();
        Runnable physicalHook = () -> {};
        int physicalCaptures;
        Runnable verifyHook = () -> {};
        Set<ExternalSystemProjectId> expectedOverride;

        NativeFixture() {
            this(false);
        }

        NativeFixture(boolean nativeInputs) {
            try {
                root =
                        nativeInputs
                                ? Files.createTempDirectory("semantic-native-").toRealPath()
                                : Path.of("/fixture");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            var connection =
                    proxy(
                            MessageBusConnection.class,
                            (p, method, args) -> {
                                if (method.getName().equals("subscribe"))
                                    subscriptions.put(args[0], args[1]);
                                return null;
                            });
            var bus =
                    proxy(
                            MessageBus.class,
                            (p, method, args) -> {
                                if (method.getName().equals("connect")) return connection;
                                throw new AssertionError("Unexpected bus call " + method);
                            });
            project =
                    proxy(
                            Project.class,
                            (p, method, args) ->
                                    switch (method.getName()) {
                                        case "getBasePath" -> root.toString();
                                        case "getMessageBus" -> bus;
                                        case "isDisposed" -> false;
                                        case "getService" -> {
                                            if (args[0] == ExternalSystemProjectTracker.class)
                                                yield proxy(
                                                        ExternalSystemProjectTracker.class,
                                                        (o, m, a) -> {
                                                            throw new AssertionError(
                                                                    "No refresh/import allowed");
                                                        });
                                            if (args[0] == SemanticSnapshotService.class) {
                                                snapshotServiceRequests++;
                                                yield serviceValue();
                                            }
                                            throw new AssertionError(
                                                    "Unexpected service " + args[0]);
                                        }
                                        case "toString" -> "isolated-native-fixture";
                                        case "hashCode" -> System.identityHashCode(p);
                                        case "equals" -> p == args[0];
                                        default ->
                                                throw new AssertionError(
                                                        "Unexpected project call " + method);
                                    });
            ApplicationManager.setApplication(
                    proxy(
                            Application.class,
                            (p, method, args) -> {
                                if (method.getName().equals("isUnitTestMode")) return true;
                                if (method.getName().equals("getService")
                                        && args[0] == ApplicationInfo.class) return info;
                                if (method.getName().equals("executeOnPooledThread")) {
                                    background.add((Runnable) args[0]);
                                    return java.util.concurrent.CompletableFuture.completedFuture(
                                            null);
                                }
                                if (method.getName().equals("runReadAction")) {
                                    if (args[0] instanceof ThrowableComputable<?, ?> computation)
                                        return computation.compute();
                                    if (args[0] instanceof Computable<?> computation)
                                        return computation.compute();
                                    ((Runnable) args[0]).run();
                                    return null;
                                }
                                throw new AssertionError("Unexpected application call " + method);
                            }));
            java.util.function.BiConsumer<DocumentListener, Disposable> subscribe =
                    (listener, owner) -> {
                        documents.set(listener);
                        documentOwner.set(owner);
                    };
            service =
                    nativeInputs
                            ? new SemanticSnapshotService(
                                    project, subscribe, document -> documentPath.get(), this)
                            : new SemanticSnapshotService(
                                    project, subscribe, document -> documentPath.get());
            if (nativeInputs) {
                linked.add(new BuildInput(root.toString()));
                linked.add(new BuildInput(root.resolve("included").toString()));
                inventory = new SourceInventoryService(new InventoryInput(), System::nanoTime);
                var discover = new SourceInventory.DiscoverRequest();
                discover.setSchemaVersion(2);
                discover.setOperation(SourceInventory.Operation.DISCOVER);
                discover.setNonce(UUID.randomUUID().toString());
                discover.setProjectPath(root.toString());
                var result = inventory.execute(discover);
                assertThat(result.getStatus())
                        .as(result.getReasons().toString())
                        .isEqualTo(SourceInventory.Status.DISCOVERED);
                discoveryId = result.getDiscovery().getDiscoveryId();
                SourceInventory.Hash hash = new SourceInventory.Hash();
                hash.setPath("Example.java");
                hash.setSha256(hash(SourceInventory.utf8("class Example {}")));
                files.add(hash);
            }
        }

        SemanticReviewContext.Request requestFor(SemanticReviewContext.Operation operation) {
            var input = request(operation);
            input.setProjectPath(root.toString());
            if (operation != SemanticReviewContext.Operation.STATUS) {
                input.setDiscoveryId(discoveryId);
                input.setFiles(files);
            }
            return input;
        }

        SemanticReviewContext.Snapshot execute(SemanticReviewContext.Operation operation) {
            return service.execute(requestFor(operation));
        }

        void drainOne() {
            background.remove(0).run();
        }

        void reload(ExternalSystemRefreshStatus status) {
            linked.forEach(input -> input.listener.onProjectReloadStart());
            linked.forEach(input -> input.listener.onProjectReloadFinish(status));
        }

        void establish() {
            assertThat(execute(SemanticReviewContext.Operation.STATUS).getStatus())
                    .isEqualTo(SemanticReviewContext.Status.ARMING);
            drainOne();
            drainOne();
            reload(ExternalSystemRefreshStatus.SUCCESS);
            drainOne();
            drainOne();
            var result = execute(SemanticReviewContext.Operation.CAPTURE);
            assertThat(result.getStatus())
                    .as(result.getReasons().toString())
                    .isEqualTo(SemanticReviewContext.Status.READY);
        }

        final class BuildInput {
            final ExternalSystemProjectId id;
            final Set<String> paths = new java.util.HashSet<>();
            ExternalSystemProjectAware aware;
            ExternalSystemProjectListener listener;
            boolean dataCurrent = true, settingsCurrent = true;
            SemanticSnapshotService.ImportedFacts imported =
                    new SemanticSnapshotService.ImportedFacts(true, 1, 1);

            BuildInput(String path) {
                id = new ExternalSystemProjectId(new ProjectSystemId("GRADLE"), path);
                String setting = path + "/build.gradle";
                paths.add(setting);
                settings.put(setting, new SettingNode(setting));
                aware =
                        proxy(
                                ExternalSystemProjectAware.class,
                                (p, method, args) ->
                                        switch (method.getName()) {
                                            case "getProjectId" -> id;
                                            case "getSettingsFiles" -> Set.copyOf(paths);
                                            case "subscribe" -> {
                                                listener = (ExternalSystemProjectListener) args[0];
                                                assertThat(args[1])
                                                        .isInstanceOf(
                                                                SemanticSnapshotService.class);
                                                yield null;
                                            }
                                            default ->
                                                    throw new AssertionError(
                                                            "Unexpected native action " + method);
                                        });
            }
        }

        @Override
        public Map<ExternalSystemProjectId, SemanticSnapshotService.TrackerRow> trackerRows() {
            Map<ExternalSystemProjectId, SemanticSnapshotService.TrackerRow> result =
                    new LinkedHashMap<>();
            for (var input : linked) {
                result.put(
                        input.id,
                        new SemanticSnapshotService.TrackerRow(
                                input.aware,
                                (java.util.function.BooleanSupplier) () -> input.dataCurrent,
                                (java.util.function.BooleanSupplier) () -> input.settingsCurrent));
            }
            return result;
        }

        @Override
        public Set<ExternalSystemProjectId> expectedBuilds() {
            return expectedOverride == null
                    ? linked.stream()
                            .map(input -> input.id)
                            .collect(java.util.stream.Collectors.toSet())
                    : expectedOverride;
        }

        @Override
        public List<SemanticSnapshotService.ModuleFacts> modules() {
            return noModules
                    ? List.of()
                    : List.of(
                            new SemanticSnapshotService.ModuleFacts(
                                    "main",
                                    "GRADLE",
                                    root.toString(),
                                    false,
                                    sdk,
                                    "/fixture-jdk",
                                    List.of(
                                            new SemanticSnapshotService.OrderFacts(
                                                    "dependency", dependencyValid)),
                                    List.of("file://" + root),
                                    List.of("file://" + root)));
        }

        @Override
        public SemanticSnapshotService.ImportedFacts imported(ExternalSystemProjectId id) {
            return linked.stream()
                    .filter(input -> input.id.equals(id))
                    .findFirst()
                    .orElseThrow()
                    .imported;
        }

        @Override
        public boolean upToDate(Object target) {
            return ((java.util.function.BooleanSupplier) target).getAsBoolean();
        }

        @Override
        public SemanticSnapshotService.Counters counters() {
            return new SemanticSnapshotService.Counters(
                    rootsEpoch, psiEpoch, vfsEpoch, dumb, uncommitted);
        }

        @Override
        public List<String> unsavedPaths() {
            return unsaved;
        }

        @Override
        public boolean activeTask() {
            return activeTask;
        }

        @Override
        public SemanticSnapshotService.SettingFile setting(String path) {
            SettingNode node = settings.get(path);
            return new SemanticSnapshotService.SettingFile(
                    node == null || !node.present ? null : node,
                    node != null && node.unsaved,
                    node == null || node.committed);
        }

        @Override
        public SourceInventoryClient.SettingsCapture captureSettings(List<String> paths) {
            physicalCaptures++;
            physicalHook.run();
            return new SourceInventoryClient.SettingsCapture(
                    root.toString(),
                    paths.stream()
                            .map(
                                    path -> {
                                        SettingNode node = settings.get(path);
                                        return new SourceInventoryFiles.Setting(
                                                path,
                                                node != null && node.diskPresent,
                                                node != null && node.diskPresent
                                                        ? hash(node.diskBytes)
                                                        : null,
                                                node == null
                                                        ? "a".repeat(64)
                                                        : node.physicalIdentity);
                                    })
                            .toList());
        }

        @Override
        public SourceInventory.Response verify(SourceInventory.VerifyRequest input) {
            verifies.add(input);
            verifyHook.run();
            return inventory.execute(input);
        }

        private final class InventoryInput implements SourceInventoryService.Authority {
            @Override
            public Path root() {
                return root;
            }

            @Override
            public String build() {
                return info.build;
            }

            @Override
            public boolean closed() {
                return false;
            }

            @Override
            public SourceInventory.Epochs epochs() {
                var epochs = new SourceInventory.Epochs();
                epochs.setRoots("0");
                epochs.setModules("0");
                epochs.setVfs("0");
                epochs.setFileTypes("0");
                return epochs;
            }

            @Override
            public SourceInventory.Model model(SourceInventoryFiles.Budget budget) {
                var location = new SourceInventory.Location();
                location.setUrl("file://" + root);
                location.setNativePath(root.toString());
                location.setScope(SourceInventory.Scope.WORKTREE);
                var source = new SourceInventory.Source();
                source.setLocation(location);
                source.setTypeClass("JavaSourceRoot");
                source.setTest(false);
                source.setGenerated(false);
                var content = new SourceInventory.Content();
                content.setModule("main");
                content.setLocation(location);
                content.setSources(List.of(source));
                content.setExclusions(List.of());
                content.setExcludePatterns(List.of());
                var model = new SourceInventory.Model();
                model.setContents(List.of(content));
                model.setDependencySources(List.of());
                model.setDependencyClasses(List.of());
                model.setIgnoredPatterns("");
                model.setUnloadedModules(List.of());
                return model;
            }

            @Override
            public List<SourceInventoryFiles.Leaf> leaves(
                    SourceInventory.Model model, SourceInventoryFiles.Budget budget) {
                return List.of(
                        new SourceInventoryFiles.Leaf(
                                "Example.java", SourceInventory.Kind.FILE, null));
            }

            @Override
            public void requireSourceDirectory(
                    SourceInventory.Location location, SourceInventoryFiles.Budget budget) {
                assertThat(location.getNativePath()).isEqualTo(root.toString());
            }

            @Override
            public SourceInventory.Entry classify(
                    SourceInventoryFiles.Leaf leaf, SourceInventoryFiles.Budget budget) {
                var entry = new SourceInventory.Entry();
                entry.setPath(leaf.path());
                entry.setKind(leaf.kind());
                entry.setMembership(SourceInventory.Membership.SOURCE);
                entry.setModules(List.of("main"));
                entry.setSourceRootUrl("file://" + root);
                entry.setTest(false);
                entry.setGenerated(false);
                return entry;
            }

            @Override
            public String vfsHash(String path, SourceInventoryFiles.Budget budget) {
                assertThat(path).isEqualTo("Example.java");
                return hash(SourceInventory.utf8("class Example {}"));
            }
        }

        private SemanticSnapshotService serviceValue() {
            return service;
        }

        Observed observe(Set<String> paths) throws Exception {
            var listener = new AtomicReference<ExternalSystemProjectListener>();
            var owner = new AtomicReference<Disposable>();
            var id =
                    new ExternalSystemProjectId(
                            new ProjectSystemId("GRADLE"), "/fixture/" + UUID.randomUUID());
            var aware =
                    proxy(
                            ExternalSystemProjectAware.class,
                            (p, method, args) ->
                                    switch (method.getName()) {
                                        case "getSettingsFiles" -> paths;
                                        case "getProjectId" -> id;
                                        case "subscribe" -> {
                                            listener.set((ExternalSystemProjectListener) args[0]);
                                            owner.set((Disposable) args[1]);
                                            yield null;
                                        }
                                        default ->
                                                throw new AssertionError(
                                                        "Unexpected external action " + method);
                                    });
            Class<?> type =
                    Class.forName(SemanticSnapshotService.class.getName() + "$ObservedBuild");
            var constructor = type.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            Object build = constructor.newInstance(service, aware, new Object(), new Object());
            var field = SemanticSnapshotService.class.getDeclaredField("builds");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var builds = (Map<ExternalSystemProjectId, Object>) field.get(service);
            builds.put(id, build);
            var baseline = type.getDeclaredField("baseline");
            baseline.setAccessible(true);
            return new Observed(
                    (SemanticSnapshotService.ImportBaseline) baseline.get(build),
                    listener.get(),
                    owner.get());
        }

        @Override
        public void close() {
            service.dispose();
            if (inventory != null) inventory.dispose();
            ApplicationManager.setApplication(previous);
            if (!root.toString().equals("/fixture")) {
                try {
                    Files.delete(root);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    private static String hash(byte[] bytes) {
        return HexFormat.of().formatHex(SourceInventory.digest("SHA-256").digest(bytes));
    }

    static final class SettingNode extends VirtualFile {
        final String path;
        byte[] bytes = SourceInventory.utf8("plugins {}");
        byte[] diskBytes = bytes;
        String physicalIdentity = "a".repeat(64);
        long stamp = 1;
        boolean valid = true,
                present = true,
                diskPresent = true,
                unsaved,
                committed = true,
                symlink;
        Runnable readHook = () -> {};
        int contentReads;

        SettingNode(String path) {
            this.path = path;
        }

        @Override
        public String getName() {
            return Path.of(path).getFileName().toString();
        }

        @Override
        public VirtualFileSystem getFileSystem() {
            throw new AssertionError("No filesystem access");
        }

        @Override
        public String getPath() {
            return path;
        }

        @Override
        public boolean isWritable() {
            return false;
        }

        @Override
        public boolean isDirectory() {
            return false;
        }

        @Override
        public boolean isValid() {
            return valid;
        }

        @Override
        public boolean is(VFileProperty property) {
            return property == VFileProperty.SYMLINK && symlink;
        }

        @Override
        public VirtualFile getParent() {
            throw new AssertionError("No parent access");
        }

        @Override
        public VirtualFile[] getChildren() {
            throw new AssertionError("No child access");
        }

        @Override
        public OutputStream getOutputStream(Object requestor, long stamp, long timestamp) {
            throw new AssertionError("No write");
        }

        @Override
        public byte[] contentsToByteArray() {
            contentReads++;
            return bytes.clone();
        }

        @Override
        public long getTimeStamp() {
            return stamp;
        }

        @Override
        public long getModificationStamp() {
            return stamp;
        }

        @Override
        public long getLength() {
            return bytes.length;
        }

        @Override
        public void refresh(boolean asynchronous, boolean recursive, Runnable postRunnable) {
            throw new AssertionError("No refresh");
        }

        @Override
        public InputStream getInputStream() {
            contentReads++;
            readHook.run();
            return new ByteArrayInputStream(bytes);
        }
    }

    private record Observed(
            SemanticSnapshotService.ImportBaseline baseline,
            ExternalSystemProjectListener listener,
            Disposable owner) {}

    static final class FakeInfo extends ApplicationInfo {
        String build = "IU-262.1";

        @Override
        public BuildNumber getBuild() {
            return BuildNumber.fromString(build);
        }

        @Override
        public Calendar getBuildDate() {
            return Calendar.getInstance();
        }

        @Override
        public ZonedDateTime getBuildTime() {
            return ZonedDateTime.now();
        }

        @Override
        public String getApiVersion() {
            return "262";
        }

        @Override
        public String getMajorVersion() {
            return "2026";
        }

        @Override
        public String getMinorVersion() {
            return "2";
        }

        @Override
        public String getMicroVersion() {
            return "0";
        }

        @Override
        public String getPatchVersion() {
            return "0";
        }

        @Override
        public String getVersionName() {
            return "fixture";
        }

        @Override
        public String getCompanyName() {
            return "fixture";
        }

        @Override
        public String getShortCompanyName() {
            return "fixture";
        }

        @Override
        public String getCompanyURL() {
            return "https://example.invalid";
        }

        @Override
        public String getFullVersion() {
            return "2026.2";
        }

        @Override
        public String getStrictVersion() {
            return "2026.2";
        }

        @Override
        public String getFullApplicationName() {
            return "fixture";
        }

        @Override
        public boolean isEssentialPlugin(String id) {
            return false;
        }

        @Override
        public boolean isEssentialPlugin(PluginId id) {
            return false;
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
