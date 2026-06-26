package com.jinloes.prpilot.ui;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import java.awt.BorderLayout;
import java.beans.PropertyChangeListener;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class PRPilotFileEditor extends UserDataHolderBase implements FileEditor {

    private final WebviewPanel webviewPanel;
    private final JComponent component;
    private final PRPilotVirtualFile virtualFile;
    private final Runnable onDispose;
    private boolean disposed;

    PRPilotFileEditor(
            Project project,
            WebviewPanel webviewPanel,
            PRPilotVirtualFile virtualFile,
            Runnable onDispose) {
        this.webviewPanel = webviewPanel;
        this.virtualFile = virtualFile;
        this.onDispose = onDispose;
        this.component = buildComponent(project, webviewPanel);
    }

    private static JComponent buildComponent(Project project, WebviewPanel webviewPanel) {
        JPanel root = new JPanel(new BorderLayout());
        JPanel toolbar = new JPanel(new BorderLayout());

        JButton showLauncher = new JButton("Show Launcher");
        showLauncher.addActionListener(
                event -> {
                    ToolWindow window =
                            ToolWindowManager.getInstance(project)
                                    .getToolWindow(PRToolWindowFactory.TOOL_WINDOW_ID);
                    if (window != null) {
                        window.activate(null, true);
                    }
                });

        JButton reload = new JButton("Reload");
        reload.addActionListener(event -> webviewPanel.reload());

        toolbar.add(showLauncher, BorderLayout.WEST);
        toolbar.add(reload, BorderLayout.EAST);
        root.add(toolbar, BorderLayout.NORTH);
        root.add(webviewPanel.getComponent(), BorderLayout.CENTER);
        return root;
    }

    @Override
    public @NotNull JComponent getComponent() {
        return component;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return webviewPanel.getComponent();
    }

    @Override
    public @NotNull String getName() {
        return PRPilotVirtualFile.TAB_TITLE;
    }

    @Override
    public void setState(@NotNull FileEditorState state) {}

    @Override
    public boolean isModified() {
        return false;
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public @NotNull VirtualFile getFile() {
        return virtualFile;
    }

    @Override
    public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) {}

    @Override
    public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) {}

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        try {
            Disposer.dispose(webviewPanel);
        } finally {
            onDispose.run();
        }
    }
}
