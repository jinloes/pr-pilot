package com.jinloes.prpilot.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowType;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.jcef.JBCefApp;
import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.services.IntellijGitHubService;
import com.jinloes.prpilot.services.UserFacingErrors;
import com.jinloes.prpilot.settings.PluginSettings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

@Slf4j
public class PRToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ContentFactory factory = ContentFactory.getInstance();

        if (!JBCefApp.isSupported()) {
            JLabel label =
                    new JLabel(
                            "<html><center>Claude PR Reviews requires JCEF.<br>"
                                    + "This IDE variant does not support embedded browsers.</center></html>",
                            SwingConstants.CENTER);
            toolWindow
                    .getContentManager()
                    .addContent(factory.createContent(label, "Claude PR Reviews", false));
            return;
        }

        WebviewPanel webviewPanel = new WebviewPanel(project);
        Disposer.register(toolWindow.getDisposable(), webviewPanel);
        wireWebviewLoading(project, webviewPanel);
        Content webContent = factory.createContent(webviewPanel.getComponent(), "Review", false);
        toolWindow.getContentManager().addContent(webContent);

        List<AnAction> titleActions = new ArrayList<>();
        titleActions.add(new PopOutAction(toolWindow));
        titleActions.add(new ReloadWebviewAction(webviewPanel));
        titleActions.add(new SettingsAction(project));
        toolWindow.setTitleActions(titleActions);
    }

    private static final class ReloadWebviewAction extends AnAction {
        private final WebviewPanel webviewPanel;

        ReloadWebviewAction(WebviewPanel webviewPanel) {
            super("Reload WebView", "Reload the webview", AllIcons.Actions.Refresh);
            this.webviewPanel = webviewPanel;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            webviewPanel.reload();
        }
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
                                                ApplicationManager.getApplication()
                                                        .invokeLater(
                                                                () ->
                                                                        webviewPanel
                                                                                .pushSetupRequired(
                                                                                        "load_failed",
                                                                                        "Couldn't load pull"
                                                                                                + " requests."
                                                                                                + " Check"
                                                                                                + " connectivity"
                                                                                                + " and retry."
                                                                                                + " If auth"
                                                                                                + " is stale,"
                                                                                                + " run 'gh auth"
                                                                                                + " login'."));
                                            }
                                        }));
    }

    private void loadAndPushPRs(Project project, WebviewPanel webviewPanel) throws Exception {
        String token = PluginSettings.getInstance().getGithubToken();
        if (token == null) {
            PluginSettings.AuthDiagnosis diagnosis = PluginSettings.getInstance().diagnoseAuth();
            String detail =
                    diagnosis == PluginSettings.AuthDiagnosis.NOT_INSTALLED
                            ? "The 'gh' CLI was not found. Install it from https://cli.github.com,"
                                    + " then run 'gh auth login' in a terminal and click Refresh."
                            : "Run 'gh auth login' in a terminal to authenticate, then click"
                                    + " Refresh.";
            ApplicationManager.getApplication()
                    .invokeLater(
                            () -> webviewPanel.pushSetupRequired(setupReason(diagnosis), detail));
            return;
        }
        try {
            String currentRepo = RepoDetector.detectCurrentRepo(project.getBasePath());

            List<String> starred;
            try {
                starred = IntellijGitHubService.getInstance().getStarredRepos(token);
            } catch (Exception e) {
                log.warn("Could not fetch starred repos: {}", e.getMessage());
                starred = List.of();
            }

            String query =
                    buildQuery(
                            currentRepo,
                            starred,
                            webviewPanel.getPrStateFilter(),
                            webviewPanel.getSearchScope());
            log.info("Webview PR query: {}", query);
            // Over-fetch by one so we can distinguish "exactly the limit" from "more exist".
            List<PullRequest> fetched =
                    IntellijGitHubService.getInstance()
                            .searchPRs(token, query, WebviewPanel.PR_SEARCH_LIMIT + 1);
            boolean limited = fetched.size() > WebviewPanel.PR_SEARCH_LIMIT;
            List<PullRequest> prs =
                    limited
                            ? new ArrayList<>(fetched.subList(0, WebviewPanel.PR_SEARCH_LIMIT))
                            : fetched;
            prs.sort(Comparator.comparing(PullRequest::getCreatedAt).reversed());

            String defaultRepo =
                    StringUtils.isNotBlank(currentRepo)
                            ? currentRepo
                            : starred.isEmpty() ? null : starred.get(0);

            boolean finalLimited = limited;
            ApplicationManager.getApplication()
                    .invokeLater(
                            () ->
                                    webviewPanel.loadPRs(
                                            prs,
                                            defaultRepo,
                                            webviewPanel.getSearchScope(),
                                            currentRepo,
                                            finalLimited));
        } catch (Exception e) {
            log.warn("Failed to load PR list for webview: {}", e.getMessage());
            String detail = UserFacingErrors.forGitHub(e, "load pull requests");
            ApplicationManager.getApplication()
                    .invokeLater(() -> webviewPanel.pushSetupRequired("load_failed", detail));
        }
    }

    static String setupReason(PluginSettings.AuthDiagnosis diagnosis) {
        return diagnosis == PluginSettings.AuthDiagnosis.NOT_INSTALLED
                ? "gh_not_installed"
                : "gh_not_authenticated";
    }

    static String buildQuery(
            String currentRepo, List<String> starredRepos, String state, String searchScope) {
        StringBuilder q = new StringBuilder("is:pr");
        if ("closed".equals(state)) {
            q.append(" is:closed");
        } else if (!"all".equals(state)) {
            q.append(" is:open");
        }
        q.append(" draft:false");

        switch (WebviewPanel.normalizeSearchScope(searchScope)) {
            case "assigned" -> q.append(" assignee:@me");
            case "reviewRequested" -> q.append(" review-requested:@me");
            case "authored" -> q.append(" author:@me");
            default -> {
                if (StringUtils.isNotBlank(currentRepo)) {
                    q.append(" repo:").append(currentRepo);
                } else {
                    q.append(" author:@me");
                }
            }
        }
        return q.toString();
    }
}
