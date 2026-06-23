package com.jinloes.prpilot.ui;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.UserDataHolder;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import java.awt.BorderLayout;
import java.beans.PropertyChangeListener;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Hosts PR Pilot's webview in a center editor tab when opened via PRPilotVirtualFile. */
public class PRPilotFileEditorProvider implements FileEditorProvider, DumbAware {

    private static final Key<Boolean> PRIMARY_EDITOR_OPEN_KEY =
            Key.create("prpilot.editor.primaryOpen");

    @Override
    public boolean accept(@NotNull Project project, @NotNull VirtualFile file) {
        return file instanceof PRPilotVirtualFile;
    }

    @Override
    public @NotNull FileEditor createEditor(@NotNull Project project, @NotNull VirtualFile file) {
        if (!tryMarkPrimaryEditorOpen(project)) {
            return new AlreadyOpenFileEditor(project, (PRPilotVirtualFile) file);
        }

        WebviewPanel webviewPanel = new WebviewPanel(project);
        try {
            PRToolWindowFactory.wireWebviewLoading(project, webviewPanel);
            return new PRPilotFileEditor(
                    project,
                    webviewPanel,
                    (PRPilotVirtualFile) file,
                    () -> clearPrimaryEditorOpen(project));
        } catch (RuntimeException e) {
            clearPrimaryEditorOpen(project);
            throw e;
        }
    }

    @Override
    public @NotNull String getEditorTypeId() {
        return "pr-pilot.editor";
    }

    @Override
    public @NotNull FileEditorPolicy getPolicy() {
        return FileEditorPolicy.HIDE_DEFAULT_EDITOR;
    }

    static boolean isPrimaryEditorOpen(UserDataHolder holder) {
        return Boolean.TRUE.equals(holder.getUserData(PRIMARY_EDITOR_OPEN_KEY));
    }

    static boolean tryMarkPrimaryEditorOpen(UserDataHolder holder) {
        if (isPrimaryEditorOpen(holder)) {
            return false;
        }
        holder.putUserData(PRIMARY_EDITOR_OPEN_KEY, Boolean.TRUE);
        return true;
    }

    static void clearPrimaryEditorOpen(UserDataHolder holder) {
        holder.putUserData(PRIMARY_EDITOR_OPEN_KEY, null);
    }

    private static final class AlreadyOpenFileEditor extends UserDataHolderBase
            implements FileEditor {
        private final PRPilotVirtualFile virtualFile;
        private final JComponent component;

        AlreadyOpenFileEditor(Project project, PRPilotVirtualFile virtualFile) {
            this.virtualFile = virtualFile;
            this.component = buildComponent(project);
        }

        private static JComponent buildComponent(Project project) {
            JPanel panel = new JPanel(new BorderLayout());
            panel.add(
                    new JLabel(
                            "PR Pilot is already open in another editor group.",
                            JLabel.CENTER),
                    BorderLayout.CENTER);
            JButton focusButton = new JButton("Back to Editor");
            focusButton.addActionListener(event -> PRPilotEditorOpener.openInEditor(project));
            JPanel actions = new JPanel();
            actions.add(focusButton);
            panel.add(actions, BorderLayout.SOUTH);
            return panel;
        }

        @Override
        public @NotNull JComponent getComponent() {
            return component;
        }

        @Override
        public @Nullable JComponent getPreferredFocusedComponent() {
            return component;
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
        public void dispose() {}
    }
}

