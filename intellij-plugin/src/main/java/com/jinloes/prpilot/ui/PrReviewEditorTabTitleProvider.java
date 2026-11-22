package com.jinloes.prpilot.ui;

import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.jinloes.prpilot.model.PullRequest;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Formats the main-window review editor tab title and tooltip from the active pull request. */
public final class PrReviewEditorTabTitleProvider implements EditorTabTitleProvider {

    @Override
    public @Nullable String getEditorTabTitle(@NotNull Project project, @NotNull VirtualFile file) {
        if (!(file instanceof PrReviewVirtualFile)) {
            return null;
        }
        if (project.isDisposed()) {
            return PrReviewVirtualFile.DEFAULT_TAB_TITLE;
        }
        return formatTabTitle(PrPilotUiCoordinator.getInstance(project).tabTitlePr());
    }

    @Override
    public @Nullable String getEditorTabTooltipText(
            @NotNull Project project, @NotNull VirtualFile virtualFile) {
        if (!(virtualFile instanceof PrReviewVirtualFile)) {
            return null;
        }
        if (project.isDisposed()) {
            return PrReviewVirtualFile.DEFAULT_TAB_TITLE;
        }
        return formatTabTooltip(PrPilotUiCoordinator.getInstance(project).tabTitlePr());
    }

    static String formatTabTitle(@Nullable PullRequest pr) {
        if (pr == null) {
            return PrReviewVirtualFile.DEFAULT_TAB_TITLE;
        }
        return PrReviewVirtualFile.DEFAULT_TAB_TITLE + " · #" + pr.getNumber();
    }

    static String formatTabTooltip(@Nullable PullRequest pr) {
        if (pr == null) {
            return PrReviewVirtualFile.DEFAULT_TAB_TITLE;
        }
        String coordinates =
                StringUtils.isNotBlank(pr.getOwner()) && StringUtils.isNotBlank(pr.getRepo())
                        ? pr.getOwner() + "/" + pr.getRepo() + "#" + pr.getNumber()
                        : "#" + pr.getNumber();
        if (StringUtils.isNotBlank(pr.getTitle())) {
            return coordinates + " — " + pr.getTitle();
        }
        return coordinates;
    }
}
