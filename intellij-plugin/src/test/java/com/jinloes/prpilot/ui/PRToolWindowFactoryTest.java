package com.jinloes.prpilot.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.wm.ToolWindow;
import com.jinloes.prpilot.model.PullRequest;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PRToolWindowFactoryTest {

    private static ToolWindow toolWindow() {
        return (ToolWindow)
                Proxy.newProxyInstance(
                        ToolWindow.class.getClassLoader(),
                        new Class<?>[] {ToolWindow.class},
                        (proxy, method, args) -> null);
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
        void keepsOnlyPopOutAndSettingsBesideThePrListRefresh() {
            List<AnAction> actions = PRToolWindowFactory.titleActions(toolWindow(), null);

            assertThat(actions)
                    .extracting(action -> action.getTemplatePresentation().getText())
                    .containsExactly("Pop Out", "Settings");
            assertThat(actions)
                    .extracting(action -> action.getTemplatePresentation().getIcon())
                    .doesNotContain(AllIcons.Actions.Refresh);
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
        }
    }
}
