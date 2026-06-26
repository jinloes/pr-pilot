package com.jinloes.prpilot.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.UserDataHolder;

/** Opens (or reveals) the singleton PR Pilot editor tab for a project. */
final class PRPilotEditorOpener {

    private static final Key<PRPilotVirtualFile> EDITOR_FILE_KEY =
            Key.create("prpilot.editor.virtualFile");

    private PRPilotEditorOpener() {}

    static void openInEditor(Project project) {
        ApplicationManager.getApplication()
                .invokeLater(
                        () -> {
                            if (project.isDisposed()) {
                                return;
                            }
                            FileEditorManager.getInstance(project)
                                    .openFile(getOrCreateVirtualFile(project), true, true);
                        });
    }

    static PRPilotVirtualFile getOrCreateVirtualFile(UserDataHolder holder) {
        PRPilotVirtualFile existing = holder.getUserData(EDITOR_FILE_KEY);
        if (existing != null && existing.isValid()) {
            return existing;
        }
        PRPilotVirtualFile created = new PRPilotVirtualFile();
        holder.putUserData(EDITOR_FILE_KEY, created);
        return created;
    }
}
