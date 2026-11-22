package com.jinloes.prpilot.ui;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Opens or focuses PR Pilot in a main-window editor tab from the Tools menu or Search Everywhere.
 */
public final class OpenPrPilotAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        performForProject(e.getProject());
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(isProjectAvailable(e.getProject()));
    }

    static boolean isProjectAvailable(Project project) {
        return project != null && !project.isDisposed();
    }

    static void performForProject(Project project) {
        if (isProjectAvailable(project)) {
            PRToolWindowFactory.openEditorForProject(project);
        }
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
