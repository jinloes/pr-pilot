package com.jinloes.prpilot.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.ToolWindowType;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.jcef.JBCefApp;
import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.model.ReviewProvider;
import com.jinloes.prpilot.review.ProviderSetupProbe;
import com.jinloes.prpilot.services.IntellijGitHubService;
import com.jinloes.prpilot.services.UserFacingErrors;
import com.jinloes.prpilot.settings.PluginSettings;
import com.jinloes.prpilot.settings.PluginSettingsConfigurable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PRToolWindowFactory implements ToolWindowFactory {

    private static final Logger log = LoggerFactory.getLogger(PRToolWindowFactory.class);

    static final String TOOL_WINDOW_ID = "PR Pilot";
    private static final Map<Project, WebviewPanel> WEBVIEW_PANELS = new ConcurrentHashMap<>();

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ContentFactory factory = ContentFactory.getInstance();

        if (!JBCefApp.isSupported()) {
            JLabel label =
                    new JLabel(
                            "<html><center>PR Pilot requires JCEF.<br>"
                                    + "This IDE variant does not support embedded browsers.</center></html>",
                            SwingConstants.CENTER);
            toolWindow
                    .getContentManager()
                    .addContent(factory.createContent(label, "PR Pilot", false));
            return;
        }

        WebviewPanel webviewPanel = new WebviewPanel(project);
        WEBVIEW_PANELS.put(project, webviewPanel);
        Disposer.register(webviewPanel, () -> WEBVIEW_PANELS.remove(project, webviewPanel));
        wireWebviewLoading(project, webviewPanel);
        Content content = factory.createContent(webviewPanel.getComponent(), "PR Pilot", false);
        content.setDisposer(webviewPanel);
        toolWindow.getContentManager().addContent(content);

        toolWindow.setTitleActions(titleActions(toolWindow, project));
        toolWindow.setAdditionalGearActions(gearActions(webviewPanel));
    }

    /** Harmless, frequent actions stay in the title bar beside the PR list's own Refresh. */
    static List<AnAction> titleActions(ToolWindow toolWindow, Project project) {
        List<AnAction> titleActions = new ArrayList<>();
        titleActions.add(new PopOutAction(toolWindow));
        titleActions.add(new SettingsAction(project));
        return titleActions;
    }

    /**
     * A full webview reload can drop unsaved edits, so it lives in the gear menu, away from the
     * look-alike PR list Refresh.
     */
    static DefaultActionGroup gearActions(WebviewPanel webviewPanel) {
        return new DefaultActionGroup(new ReloadAction(webviewPanel));
    }

    /**
     * Activates the first open PR Pilot tool window and selects a PR opened from a notification.
     */
    public static void activatePrFromNotification(PullRequest pr) {
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (project.isDisposed()) continue;
            ToolWindow toolWindow =
                    ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
            if (toolWindow == null) continue;
            toolWindow.activate(
                    () -> {
                        WebviewPanel webviewPanel = WEBVIEW_PANELS.get(project);
                        if (webviewPanel != null) webviewPanel.activatePr(pr, "notification");
                    },
                    true);
            return;
        }
    }

    private static final class ReloadAction extends AnAction {
        private final WebviewPanel webviewPanel;

        ReloadAction(WebviewPanel webviewPanel) {
            super(
                    "Reload PR Pilot View",
                    "Reloads the embedded view. Edits not yet saved to GitHub may be lost.",
                    AllIcons.Actions.ForceRefresh);
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
            super("Settings", "Open PR Pilot settings", AllIcons.General.Settings);
            this.project = project;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, PluginSettingsConfigurable.class);
        }
    }

    static void wireWebviewLoading(Project project, WebviewPanel webviewPanel) {
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

    private static void loadAndPushPRs(Project project, WebviewPanel webviewPanel)
            throws Exception {
        try {
            IntellijGitHubService github = IntellijGitHubService.getInstance();
            IntellijGitHubService.PullRequestList result =
                    github.listPullRequests(
                            project.getBasePath(),
                            webviewPanel.getPrStateFilter(),
                            webviewPanel.getSearchScope());
            String currentRepo = result.currentRepo();

            List<String> starred;
            try {
                starred = github.getStarredRepos();
            } catch (Exception e) {
                log.warn("Could not fetch starred repos: {}", e.getMessage());
                starred = List.of();
            }

            log.info("Webview PR query: {}", result.query());
            boolean limited = result.limited();
            List<PullRequest> prs = new ArrayList<>(result.pullRequests());
            prs.sort(Comparator.comparing(PullRequest::getCreatedAt).reversed());

            String defaultRepo =
                    StringUtils.isNotBlank(currentRepo)
                            ? currentRepo
                            : starred.isEmpty() ? null : starred.get(0);

            boolean finalLimited = limited;
            ReviewProvider provider = PluginSettings.getInstance().getReviewProvider();
            ProviderSetupProbe.Result providerSetup = new ProviderSetupProbe().probe(provider);
            ApplicationManager.getApplication()
                    .invokeLater(
                            () -> {
                                webviewPanel.loadPRs(
                                        prs,
                                        defaultRepo,
                                        webviewPanel.getSearchScope(),
                                        currentRepo,
                                        finalLimited,
                                        result.reviewStatusAvailable(),
                                        providerSetup);
                            });
        } catch (Exception e) {
            log.warn("Failed to load PR list for webview: {}", e.getMessage());
            String detail = UserFacingErrors.forGitHub(e, "load pull requests");
            ApplicationManager.getApplication()
                    .invokeLater(() -> webviewPanel.pushSetupRequired("load_failed", detail));
        }
    }
}
