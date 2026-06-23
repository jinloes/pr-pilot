package com.jinloes.prpilot.ui;

import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.testFramework.LightVirtualFile;

/** Marker virtual file used to host the PR Pilot webview in the editor area. */
final class PRPilotVirtualFile extends LightVirtualFile {

    static final String TAB_TITLE = "PR Pilot";

    PRPilotVirtualFile() {
        super(TAB_TITLE, PlainTextFileType.INSTANCE, "");
        setWritable(false);
    }
}


