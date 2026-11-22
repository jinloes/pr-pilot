package com.jinloes.prpilot.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFileWithoutContent;
import com.intellij.testFramework.LightVirtualFile;
import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.model.ReviewResult;
import com.jinloes.prpilot.review.ProviderSetupProbe;
import com.jinloes.prpilot.services.PendingReviewIndex;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.ActivatePrMsg;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.PrListMessage;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.SetupRequiredMsg;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.io.file.PathUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PrReviewEditorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("pr-pilot-review-editor-test");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            PathUtils.deleteDirectory(tempDir);
        }
    }

    private WebviewPanel headlessPanel(PendingReviewIndex index, List<Object> messages) {
        return new WebviewPanel(
                null, index, null, Runnable::run, () -> "claude", messages::add, () -> true);
    }

    private static Project projectStub(boolean disposed, PrPilotUiCoordinator coordinator) {
        return (Project)
                Proxy.newProxyInstance(
                        Project.class.getClassLoader(),
                        new Class<?>[] {Project.class},
                        (proxy, method, args) -> {
                            if ("isDisposed".equals(method.getName())) {
                                return disposed;
                            }
                            if ("getService".equals(method.getName())
                                    && args != null
                                    && args.length == 1
                                    && PrPilotUiCoordinator.class.equals(args[0])) {
                                return coordinator;
                            }
                            return null;
                        });
    }

    @Nested
    class VirtualFileAndProvider {

        @Test
        void createsReadOnlyContentlessVirtualFileAcceptedOnlyByReviewEditorProvider() {
            PrReviewVirtualFile reviewFile = new PrReviewVirtualFile();
            LightVirtualFile otherFile = new LightVirtualFile("Other.txt", "");
            PrReviewFileEditorProvider provider = new PrReviewFileEditorProvider();
            Project project = projectStub(false, null);

            assertThat(reviewFile).isInstanceOf(VirtualFileWithoutContent.class);
            assertThat(reviewFile.isWritable()).isFalse();
            assertThat(reviewFile.getName()).isEqualTo("PR Pilot");
            assertThat(PrReviewVirtualFile.FILE_TYPE.isMyFileType(reviewFile)).isTrue();
            assertThat(PrReviewVirtualFile.FILE_TYPE.isMyFileType(otherFile)).isFalse();
            assertThat(PrReviewVirtualFile.FILE_TYPE.getName()).isEqualTo("PR_PILOT");
            assertThat(PrReviewVirtualFile.FILE_TYPE.getIcon()).isSameAs(AllIcons.Vcs.Branch);

            assertThat(provider.accept(project, reviewFile)).isTrue();
            assertThat(provider.accept(project, otherFile)).isFalse();
            assertThat(provider.acceptRequiresReadAction()).isFalse();
            assertThat(provider.getPolicy()).isEqualTo(FileEditorPolicy.HIDE_DEFAULT_EDITOR);
            assertThat(provider.getEditorTypeId()).isEqualTo("pr-pilot-review-editor");
        }
    }

    @Nested
    class TabTitleFormatting {

        @Test
        void formatsDefaultAndActivePrTitlesAndTooltips() {
            PullRequest pr =
                    new PullRequest(
                            "Fix OAuth callback race",
                            "https://github.com/acme/widgets/pull/42",
                            "acme",
                            "widgets",
                            42,
                            "",
                            "alice",
                            "2026-01-01T00:00:00Z");

            assertThat(PrReviewEditorTabTitleProvider.formatTabTitle(null)).isEqualTo("PR Pilot");
            assertThat(PrReviewEditorTabTitleProvider.formatTabTitle(pr))
                    .isEqualTo("PR Pilot · #42");
            assertThat(PrReviewEditorTabTitleProvider.formatTabTooltip(null)).isEqualTo("PR Pilot");
            assertThat(PrReviewEditorTabTitleProvider.formatTabTooltip(pr))
                    .isEqualTo("acme/widgets#42 — Fix OAuth callback race");
        }

        @Test
        void resolvesTabTitleOnlyForReviewVirtualFileAndHandlesDisposedProject() {
            PrPilotUiCoordinator coordinator =
                    new PrPilotUiCoordinator(
                            new PrPilotUiCoordinator.EditorHost() {
                                @Override
                                public void openReviewEditor(boolean focusEditor) {}

                                @Override
                                public void refreshTabPresentation() {}

                                @Override
                                public void runOnEdt(Runnable action) {
                                    action.run();
                                }
                            });
            PullRequest pr =
                    new PullRequest("Add retry backoff", "", "acme", "widgets", 19, "", "bob", "");
            coordinator.requestSelectPr(pr, null);

            PrReviewEditorTabTitleProvider titleProvider = new PrReviewEditorTabTitleProvider();
            PrReviewVirtualFile reviewFile = new PrReviewVirtualFile();
            LightVirtualFile unrelatedFile = new LightVirtualFile("Main.java", "");

            Project activeProject = projectStub(false, coordinator);
            Project disposedProject = projectStub(true, coordinator);

            assertThat(titleProvider.getEditorTabTitle(activeProject, unrelatedFile)).isNull();
            assertThat(titleProvider.getEditorTabTooltipText(activeProject, unrelatedFile))
                    .isNull();
            assertThat(titleProvider.getEditorTabTitle(activeProject, reviewFile))
                    .isEqualTo("PR Pilot · #19");
            assertThat(titleProvider.getEditorTabTooltipText(activeProject, reviewFile))
                    .isEqualTo("acme/widgets#19 — Add retry backoff");
            assertThat(titleProvider.getEditorTabTitle(disposedProject, reviewFile))
                    .isEqualTo("PR Pilot");
        }
    }

    @Nested
    class BridgeMessageSerialization {

        @Test
        void omitsNullSourceAndPreservesExplicitNotificationSourceInActivatePr() {
            PullRequest pr = new PullRequest("Title", "", "acme", "widgets", 7, "", "alice", "");
            WebviewBridgeMessages.WebviewPr dto = WebviewPrSupport.toWebviewPr(pr, false);

            JsonNode activateWithoutSource =
                    MAPPER.valueToTree(new ActivatePrMsg("activatePR", dto, null));
            assertThat(activateWithoutSource.has("source")).isFalse();

            JsonNode activateWithNotification =
                    MAPPER.valueToTree(new ActivatePrMsg("activatePR", dto, "notification"));
            assertThat(activateWithNotification.path("source").asText()).isEqualTo("notification");
            assertThat(activateWithNotification.path("pr").path("number").asInt()).isEqualTo(7);
        }
    }

    @Nested
    class LoadPrsReadiness {

        @Test
        void returnsTrueAndPublishesPrListWhenProviderAndDraftIndexAreHealthy() {
            List<Object> messages = new ArrayList<>();
            PendingReviewIndex index = new PendingReviewIndex(tempDir.resolve("pending-prs.json"));
            WebviewPanel panel = headlessPanel(index, messages);
            PullRequest pr =
                    new PullRequest("Add tab host", "", "acme", "widgets", 42, "", "alice", "");

            boolean loaded =
                    panel.loadPRs(
                            List.of(pr),
                            "acme/widgets",
                            "currentRepo",
                            "acme/widgets",
                            false,
                            true,
                            new ProviderSetupProbe.Result(
                                    true, "ready", "ready", "claude auth login"));

            assertThat(loaded).isTrue();
            assertThat(messages).singleElement().isInstanceOf(PrListMessage.class);
            PrListMessage prList = (PrListMessage) messages.get(0);
            assertThat(prList.prs()).hasSize(1);
            assertThat(prList.intellijAssistedEnabled()).isTrue();
            assertThat(prList.providerReadiness().available()).isTrue();
        }

        @Test
        void returnsFalseAndPublishesSetupRequiredWhenProviderMissingUnauthenticatedOrIndexCorrupt()
                throws IOException {
            List<Object> messages = new ArrayList<>();
            Path indexPath = tempDir.resolve("pending-prs.json");
            PendingReviewIndex index = new PendingReviewIndex(indexPath);
            WebviewPanel panel = headlessPanel(index, messages);

            assertThat(
                            panel.loadPRs(
                                    List.of(),
                                    null,
                                    "currentRepo",
                                    null,
                                    false,
                                    true,
                                    new ProviderSetupProbe.Result(
                                            false, "missing", "unavailable", "claude auth login")))
                    .isFalse();
            assertThat(
                            panel.loadPRs(
                                    List.of(),
                                    null,
                                    "currentRepo",
                                    null,
                                    false,
                                    true,
                                    new ProviderSetupProbe.Result(
                                            true, "ready", "unavailable", "copilot login")))
                    .isFalse();

            Files.writeString(indexPath, "{not-valid-json", StandardCharsets.UTF_8);
            assertThat(
                            panel.loadPRs(
                                    List.of(),
                                    null,
                                    "currentRepo",
                                    null,
                                    false,
                                    true,
                                    new ProviderSetupProbe.Result(
                                            true, "ready", "ready", "claude auth login")))
                    .isFalse();

            assertThat(messages)
                    .extracting(msg -> ((SetupRequiredMsg) msg).reason())
                    .containsExactly(
                            "provider_not_installed",
                            "provider_not_authenticated",
                            "draft_index_unavailable");
        }
    }

    @Nested
    class LifecycleTransitionOnClose {

        @Test
        void transitionToNullSelectionClearsActivePrStateWithoutNullPointerException() {
            List<Object> messages = new ArrayList<>();
            PendingReviewIndex index = new PendingReviewIndex(tempDir.resolve("pending-prs.json"));
            WebviewPanel panel = headlessPanel(index, messages);
            panel.activePR = new PullRequest("PR", "", "acme", "widgets", 42, "", "alice", "");
            panel.lastResult = new ReviewResult("COMMENT", "Summary", List.of());
            panel.pendingReviewId = "PRR_123";
            panel.pendingReviewKey = "acme/widgets#42";

            WebviewPanel.LifecycleTransition transition = panel.transitionToSelection(null);

            assertThat(transition).isNotNull();
            assertThat(panel.activePR).isNull();
            assertThat(panel.lastResult).isNull();
            assertThat(panel.pendingReviewId).isNull();
            assertThat(panel.pendingReviewKey).isNull();
        }
    }
}
