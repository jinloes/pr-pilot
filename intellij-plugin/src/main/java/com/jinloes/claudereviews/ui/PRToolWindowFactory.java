package com.jinloes.claudereviews.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowType;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.jcef.JBCefApp;
import com.jinloes.claudereviews.model.PullRequest;
import com.jinloes.claudereviews.services.IntellijGitHubService;
import com.jinloes.claudereviews.settings.PluginSettings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

@Slf4j
public class PRToolWindowFactory implements ToolWindowFactory {

    private static final int MAX_REPO_QUALIFIERS = 20;

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ContentFactory factory = ContentFactory.getInstance();

        if (JBCefApp.isSupported()) {
            WebviewPanel webviewPanel = new WebviewPanel();
            wireWebviewLoading(project, webviewPanel);
            Content webContent =
                    factory.createContent(webviewPanel.getComponent(), "Review", false);
            toolWindow.getContentManager().addContent(webContent);
        }

        PRToolWindow classic = new PRToolWindow(project);
        Content classicContent =
                factory.createContent(classic.getContent(), "Classic (legacy)", false);
        toolWindow.getContentManager().addContent(classicContent);

        toolWindow.setTitleActions(
                List.of(new PopOutAction(toolWindow), new SettingsAction(project)));
    }

    private static final class PopOutAction extends AnAction {
        private final ToolWindow toolWindow;

        PopOutAction(ToolWindow toolWindow) {
            super("Pop Out", "Float as a separate window", AllIcons.Actions.MoveToWindow);
            this.toolWindow = toolWindow;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            boolean floating = toolWindow.getType() == ToolWindowType.FLOATING;
            toolWindow.setType(floating ? ToolWindowType.DOCKED : ToolWindowType.FLOATING, null);
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            boolean floating = toolWindow.getType() == ToolWindowType.FLOATING;
            e.getPresentation().setText(floating ? "Dock" : "Pop Out");
            e.getPresentation()
                    .setDescription(
                            floating ? "Dock back into the IDE" : "Float as a separate window");
        }
    }

    private static final class SettingsAction extends AnAction {
        private final Project project;

        SettingsAction(Project project) {
            super("Settings", "Open Claude PR Reviews settings", AllIcons.General.Settings);
            this.project = project;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, "Claude PR Reviews");
        }
    }

    private void wireWebviewLoading(Project project, WebviewPanel webviewPanel) {
        webviewPanel.setOnPageReady(
                () ->
                        ApplicationManager.getApplication()
                                .executeOnPooledThread(
                                        () -> {
                                            try {
                                                loadAndPushPRs(project, webviewPanel);
                                            } catch (Exception e) {
                                                log.warn(
                                                        "Webview PR load failed: {}",
                                                        e.getMessage());
                                            }
                                        }));
    }

    private void loadAndPushPRs(Project project, WebviewPanel webviewPanel) throws Exception {
        String token = PluginSettings.getInstance().getGithubToken();
        if (token == null) {
            log.info("No GitHub token available — skipping webview PR load");
            return;
        }
        String currentRepo = PRToolWindow.detectCurrentRepo(project.getBasePath());

        List<String> starred;
        try {
            starred = IntellijGitHubService.getInstance().getStarredRepos(token);
        } catch (Exception e) {
            log.warn("Could not fetch starred repos: {}", e.getMessage());
            starred = List.of();
        }

        String query = buildQuery(currentRepo, starred);
        log.info("Webview PR query: {}", query);
        List<PullRequest> prs = IntellijGitHubService.getInstance().searchPRs(token, query);
        prs.sort(Comparator.comparing(PullRequest::getCreatedAt).reversed());

        // Default to current project repo; fall back to first starred repo
        String defaultRepo =
                StringUtils.isNotBlank(currentRepo)
                        ? currentRepo
                        : starred.isEmpty() ? null : starred.get(0);

        ApplicationManager.getApplication()
                .invokeLater(() -> webviewPanel.loadPRs(prs, defaultRepo));
    }

    static String buildQuery(String currentRepo, List<String> starredRepos) {
        List<String> repos = new ArrayList<>();
        if (StringUtils.isNotBlank(currentRepo)) {
            repos.add(currentRepo);
        }
        for (String r : starredRepos) {
            if (repos.size() >= MAX_REPO_QUALIFIERS) break;
            if (!r.equals(currentRepo)) {
                repos.add(r);
            }
        }
        if (repos.isEmpty()) {
            return "is:pr is:open draft:false author:@me";
        }
        StringBuilder q = new StringBuilder("is:pr is:open draft:false");
        for (String r : repos) {
            q.append(" repo:").append(r);
        }
        return q.toString();
    }
}
