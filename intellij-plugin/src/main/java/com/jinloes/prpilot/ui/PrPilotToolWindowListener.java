package com.jinloes.prpilot.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ex.ToolWindowManagerListener;
import org.jetbrains.annotations.NotNull;

/**
 * Redirects clicks on the {@code PR Pilot} tool-window stripe button to the main-window editor tab
 * and collapses the side tool window so PR Pilot never steals horizontal editor width.
 */
public final class PrPilotToolWindowListener implements ToolWindowManagerListener {

    private final Project project;

    public PrPilotToolWindowListener(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public void toolWindowShown(@NotNull ToolWindow toolWindow) {
        if (PRToolWindowFactory.TOOL_WINDOW_ID.equals(toolWindow.getId())) {
            PRToolWindowFactory.openEditorAndHideToolWindow(project, toolWindow);
        }
    }
}
