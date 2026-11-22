package com.jinloes.prpilot.ui;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.jcef.JBCefApp;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Main-window editor tab hosting the unified PR Pilot workspace. */
final class PrReviewFileEditor extends UserDataHolderBase implements FileEditor {

    private final VirtualFile file;
    private final WebviewPanel panel;
    private final JComponent component;
    private final JComponent preferredFocusComponent;
    private final PropertyChangeSupport propertyChangeSupport = new PropertyChangeSupport(this);
    private final PrPilotUiCoordinator.ReviewRegistration registration;
    private volatile boolean disposed;

    PrReviewFileEditor(@NotNull Project project, @NotNull VirtualFile file) {
        this.file = file;
        if (!JBCefApp.isSupported()) {
            JLabel unsupportedLabel =
                    new JLabel(
                            "<html><center>PR Pilot requires JCEF.<br>"
                                    + "This IDE variant does not support embedded browsers.</center></html>",
                            SwingConstants.CENTER);
            this.panel = null;
            this.component = unsupportedLabel;
            this.preferredFocusComponent = unsupportedLabel;
            this.registration = null;
            return;
        }

        WebviewPanel webviewPanel = new WebviewPanel(project);
        Disposer.register(this, webviewPanel);
        this.panel = webviewPanel;
        this.component = webviewPanel.getComponent();
        this.preferredFocusComponent = webviewPanel.preferredFocusedComponent();
        this.registration =
                PrPilotUiCoordinator.getInstance(project)
                        .registerEditorPanel(project, webviewPanel);
        Disposer.register(this, registration::close);
    }

    @Override
    public @NotNull JComponent getComponent() {
        return component;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return preferredFocusComponent;
    }

    @Override
    public @NotNull String getName() {
        return PrReviewVirtualFile.DEFAULT_TAB_TITLE;
    }

    @Override
    public void setState(@NotNull FileEditorState state) {}

    @Override
    public boolean isModified() {
        return false;
    }

    @Override
    public boolean isValid() {
        return !disposed && file.isValid();
    }

    @Override
    public void selectNotify() {
        if (!disposed) {
            if (registration != null) {
                registration.markFocused();
            }
            if (panel != null) {
                panel.scheduleWebviewLayoutRepaint();
            }
        }
    }

    @Override
    public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) {
        propertyChangeSupport.addPropertyChangeListener(listener);
    }

    @Override
    public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) {
        propertyChangeSupport.removePropertyChangeListener(listener);
    }

    @Override
    public @NotNull VirtualFile getFile() {
        return file;
    }

    @Override
    public void dispose() {
        disposed = true;
    }
}
