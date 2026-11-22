package com.jinloes.prpilot.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.jinloes.prpilot.model.PullRequest;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JButton;
import javax.swing.JComponent;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PRToolWindowFactoryTest {

    private static ToolWindow toolWindow(String id, AtomicBoolean visible, List<String> callOrder) {
        return (ToolWindow)
                Proxy.newProxyInstance(
                        ToolWindow.class.getClassLoader(),
                        new Class<?>[] {ToolWindow.class},
                        (proxy, method, args) -> {
                            return switch (method.getName()) {
                                case "getId" -> id;
                                case "isVisible" -> visible.get();
                                case "hide" -> {
                                    visible.set(false);
                                    if (callOrder != null) {
                                        callOrder.add("hide");
                                    }
                                    yield null;
                                }
                                default -> null;
                            };
                        });
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

    private static PrPilotUiCoordinator recordingCoordinator(List<String> callOrder) {
        return new PrPilotUiCoordinator(
                new PrPilotUiCoordinator.EditorHost() {
                    @Override
                    public void openReviewEditor(boolean focusEditor) {
                        callOrder.add("openEditor:" + focusEditor);
                    }

                    @Override
                    public void refreshTabPresentation() {}

                    @Override
                    public void runOnEdt(Runnable action) {
                        action.run();
                    }
                });
    }

    private static WebviewPanel headlessPanel() {
        List<Runnable> background = new ArrayList<>();
        return new WebviewPanel(
                new PullRequest("Fixture", "", "acme", "widget", 42, "", "", ""),
                null,
                background::add,
                () -> "claude",
                message -> {},
                () -> false);
    }

    @Nested
    class TitleActions {

        @Test
        void offersOpenPrPilotAndSettingsWithoutLookAlikeRefresh() {
            List<AnAction> actions =
                    PRToolWindowFactory.titleActions(
                            toolWindow("PR Pilot", new AtomicBoolean(false), null), null);

            assertThat(actions)
                    .extracting(action -> action.getTemplatePresentation().getText())
                    .containsExactly("Open PR Pilot", "Settings");
            assertThat(actions)
                    .extracting(action -> action.getTemplatePresentation().getIcon())
                    .doesNotContain(AllIcons.Actions.Refresh);
            assertThat(actions)
                    .extracting(AnAction::getActionUpdateThread)
                    .containsOnly(ActionUpdateThread.BGT);
        }
    }

    @Nested
    class GearActions {

        @Test
        void offersTheViewReloadWithItsConsequence() {
            AnAction[] actions =
                    PRToolWindowFactory.gearActions(headlessPanel()).getChildActionsOrStubs();

            assertThat(actions).hasSize(1);
            var presentation = actions[0].getTemplatePresentation();
            assertThat(presentation.getText()).isEqualTo("Reload PR Pilot View");
            assertThat(presentation.getDescription())
                    .isEqualTo(
                            "Reloads the embedded view. Edits not yet saved to GitHub may be"
                                    + " lost.");
            assertThat(presentation.getIcon()).isSameAs(AllIcons.Actions.ForceRefresh);
            assertThat(actions[0].getActionUpdateThread()).isEqualTo(ActionUpdateThread.BGT);
        }
    }

    @Nested
    class RedirectToolWindowToEditor {

        @Test
        void hidesVisibleToolWindowBeforeOpeningMainEditorTab() {
            List<String> callOrder = new ArrayList<>();
            AtomicBoolean visible = new AtomicBoolean(true);
            ToolWindow tw = toolWindow("PR Pilot", visible, callOrder);
            Project project = projectStub(false, recordingCoordinator(callOrder));

            PRToolWindowFactory.redirectToolWindowToEditor(project, tw);

            assertThat(visible.get()).isFalse();
            assertThat(callOrder).containsExactly("hide", "openEditor:true");
        }

        @Test
        void opensMainEditorTabWithoutHideWhenToolWindowAlreadyHiddenAndSkipsDisposedProject() {
            List<String> callOrder = new ArrayList<>();
            AtomicBoolean visible = new AtomicBoolean(false);
            ToolWindow tw = toolWindow("PR Pilot", visible, callOrder);
            PrPilotUiCoordinator coordinator = recordingCoordinator(callOrder);

            PRToolWindowFactory.redirectToolWindowToEditor(projectStub(false, coordinator), tw);
            assertThat(callOrder).containsExactly("openEditor:true");

            callOrder.clear();
            visible.set(true);
            PRToolWindowFactory.redirectToolWindowToEditor(projectStub(true, coordinator), tw);
            assertThat(callOrder).isEmpty();
            assertThat(visible.get()).isTrue();
        }
    }

    @Nested
    class ToolWindowListenerTests {

        @Test
        void redirectsOnlyPrPilotToolWindowShownEvents() {
            List<String> callOrder = new ArrayList<>();
            Project project = projectStub(false, recordingCoordinator(callOrder));
            PrPilotToolWindowListener listener = new PrPilotToolWindowListener(project);

            listener.toolWindowShown(toolWindow("Commit", new AtomicBoolean(true), callOrder));
            assertThat(callOrder).isEmpty();

            AtomicBoolean prPilotVisible = new AtomicBoolean(true);
            listener.toolWindowShown(toolWindow("PR Pilot", prPilotVisible, callOrder));
            assertThat(prPilotVisible.get()).isFalse();
            assertThat(callOrder).containsExactly("hide", "openEditor:true");
        }
    }

    private static JButton findButton(Container container, String text) {
        for (Component child : container.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText())) {
                return button;
            }
            if (child instanceof Container nested) {
                JButton found = findButton(nested, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Nested
    class LauncherAndOpenActionTests {

        @Test
        void launcherOpenButtonHidesVisibleToolWindowAndOpensMainEditorTab() {
            List<String> callOrder = new ArrayList<>();
            AtomicBoolean visible = new AtomicBoolean(true);
            ToolWindow tw = toolWindow("PR Pilot", visible, callOrder);
            Project project = projectStub(false, recordingCoordinator(callOrder));

            JComponent panel = PRToolWindowFactory.createLauncherPanel(project, tw);
            JButton openButton = findButton(panel, "Open PR Pilot");

            assertThat(openButton).isNotNull();
            assertThat(findButton(panel, "Settings")).isNotNull();

            openButton.doClick();
            assertThat(visible.get()).isFalse();
            assertThat(callOrder).containsExactly("hide", "openEditor:true");
        }

        @Test
        void openPrPilotActionEnablesOnlyForActiveProjectAndOpensMainEditorTab() {
            List<String> callOrder = new ArrayList<>();
            PrPilotUiCoordinator coordinator = recordingCoordinator(callOrder);
            Project activeProject = projectStub(false, coordinator);
            Project disposedProject = projectStub(true, coordinator);
            OpenPrPilotAction action = new OpenPrPilotAction();

            assertThat(action.getActionUpdateThread()).isEqualTo(ActionUpdateThread.BGT);
            assertThat(OpenPrPilotAction.isProjectAvailable(null)).isFalse();
            assertThat(OpenPrPilotAction.isProjectAvailable(disposedProject)).isFalse();
            assertThat(OpenPrPilotAction.isProjectAvailable(activeProject)).isTrue();

            OpenPrPilotAction.performForProject(null);
            OpenPrPilotAction.performForProject(disposedProject);
            assertThat(callOrder).isEmpty();

            OpenPrPilotAction.performForProject(activeProject);
            assertThat(callOrder).containsExactly("openEditor:true");
        }
    }
}
