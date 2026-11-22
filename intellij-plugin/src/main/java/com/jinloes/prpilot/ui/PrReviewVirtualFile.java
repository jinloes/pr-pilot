package com.jinloes.prpilot.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.fileTypes.ex.FakeFileType;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWithoutContent;
import com.intellij.testFramework.LightVirtualFile;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;

/** In-memory virtual file backing PR Pilot's main-window editor tab. */
public final class PrReviewVirtualFile extends LightVirtualFile
        implements VirtualFileWithoutContent {

    static final String DEFAULT_TAB_TITLE = "PR Pilot";

    static final FakeFileType FILE_TYPE =
            new FakeFileType() {
                @Override
                public boolean isMyFileType(@NotNull VirtualFile file) {
                    return file instanceof PrReviewVirtualFile;
                }

                @Override
                public @NotNull String getName() {
                    return "PR_PILOT";
                }

                @Override
                public @NotNull String getDescription() {
                    return DEFAULT_TAB_TITLE;
                }

                @Override
                public Icon getIcon() {
                    return AllIcons.Vcs.Branch;
                }
            };

    public PrReviewVirtualFile() {
        super(DEFAULT_TAB_TITLE, FILE_TYPE, "");
        setWritable(false);
    }
}
