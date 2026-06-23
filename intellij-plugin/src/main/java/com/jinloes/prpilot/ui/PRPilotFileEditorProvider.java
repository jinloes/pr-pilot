package com.jinloes.prpilot.ui;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/** Hosts PR Pilot's webview in a center editor tab when opened via PRPilotVirtualFile. */
public class PRPilotFileEditorProvider implements FileEditorProvider, DumbAware {

    @Override
    public boolean accept(@NotNull Project project, @NotNull VirtualFile file) {
        return file instanceof PRPilotVirtualFile;
    }

    @Override
    public @NotNull FileEditor createEditor(@NotNull Project project, @NotNull VirtualFile file) {
        WebviewPanel webviewPanel = new WebviewPanel(project);
        PRToolWindowFactory.wireWebviewLoading(project, webviewPanel);
        return new PRPilotFileEditor(project, webviewPanel, (PRPilotVirtualFile) file);
    }

    @Override
    public @NotNull String getEditorTypeId() {
        return "pr-pilot.editor";
    }

    @Override
    public @NotNull FileEditorPolicy getPolicy() {
        return FileEditorPolicy.HIDE_DEFAULT_EDITOR;
    }
}

