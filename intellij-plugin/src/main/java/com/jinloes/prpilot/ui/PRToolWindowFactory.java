package com.jinloes.prpilot.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
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
import com.jinloes.prpilot.util.LogRedactor;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tool-window stripe launcher for PR Pilot.
 *
 * <p>The full PR Pilot UI runs in a main-window editor tab ({@link PrReviewFileEditor}) rather than
 * a docked side tool window so reviews, diffs, and chat have full horizontal width. Showing the
 * {@code PR Pilot} tool window redirects immediately to the main editor tab and hides the side
 * pane; this factory provides lightweight fallback controls without creating a second JCEF browser.
 */
public class PRToolWindowFactory implements ToolWindowFactory, DumbAware {

    private static final Logger log = LoggerFactory.getLogger(PRToolWindowFactory.class);

    static final String TOOL_WINDOW_ID = "PR Pilot";

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ContentFactory factory = ContentFactory.getInstance();

        if (!JBCefApp.isSupported()) {
            JLabel label =
                    new JLabel(
                            "<html><center>PR Pilot requires JCEF.<br>"
                                    + "This IDE variant does not support embedded browsers.</center></html>",
                            SwingConstants.CENTER);
            toolWindow.getContentManager().addContent(factory.createContent(label, null, false));
            return;
        }

        Content content =
                factory.createContent(createLauncherPanel(project, toolWindow), null, false);
        toolWindow.getContentManager().addContent(content);
        toolWindow.setTitleActions(titleActions(toolWindow, project));
        toolWindow.setAdditionalGearActions(gearActions(project));

        if (toolWindow.isVisible()) {
            openEditorAndHideToolWindow(project, toolWindow);
        }
    }

    static JComponent createLauncherPanel(
            @NotNull Project project, @Nullable ToolWindow toolWindow) {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.insets = new Insets(8, 16, 8, 16);
        panel.add(new JLabel("PR Pilot opens in a main editor tab.", SwingConstants.CENTER), gbc);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        JButton openButton = new JButton("Open PR Pilot");
        openButton.addActionListener(e -> openEditorAndHideToolWindow(project, toolWindow));
        buttons.add(openButton);

        JButton settingsButton = new JButton("Settings");
        settingsButton.addActionListener(
                e ->
                        ShowSettingsUtil.getInstance()
                                .showSettingsDialog(project, PluginSettingsConfigurable.class));
        buttons.add(settingsButton);

        gbc.gridy = 1;
        panel.add(buttons, gbc);
        return panel;
    }

    /** Opens PR Pilot in the main editor and collapses the side tool window if visible. */
    static void openEditorForProject(@NotNull Project project) {
        if (project.isDisposed()) {
            return;
        }
        ToolWindowManager manager = project.getServiceIfCreated(ToolWindowManager.class);
        ToolWindow toolWindow = manager != null ? manager.getToolWindow(TOOL_WINDOW_ID) : null;
        openEditorAndHideToolWindow(project, toolWindow);
    }

    static void openEditorAndHideToolWindow(
            @NotNull Project project, @Nullable ToolWindow toolWindow) {
        Application application = ApplicationManager.getApplication();
        if (application == null) {
            redirectToolWindowToEditor(project, toolWindow);
            return;
        }
        application.invokeLater(() -> redirectToolWindowToEditor(project, toolWindow));
    }

    static void redirectToolWindowToEditor(
            @NotNull Project project, @Nullable ToolWindow toolWindow) {
        if (project.isDisposed()) {
            return;
        }
        if (toolWindow != null && toolWindow.isVisible()) {
            toolWindow.hide(null);
        }
        PrPilotUiCoordinator.getInstance(project).openOrFocusEditor();
    }

    static List<AnAction> titleActions(ToolWindow toolWindow, Project project) {
        List<AnAction> titleActions = new ArrayList<>();
        titleActions.add(new OpenInEditorAction(project, toolWindow));
        titleActions.add(new SettingsAction(project));
        return titleActions;
    }

    static DefaultActionGroup gearActions(Project project) {
        return new DefaultActionGroup(new ReloadAction(project, null));
    }

    static DefaultActionGroup gearActions(WebviewPanel webviewPanel) {
        return new DefaultActionGroup(new ReloadAction(null, webviewPanel));
    }

    /** Opens PR Pilot's main editor tab in the first open project and activates {@code pr}. */
    public static void activatePrFromNotification(PullRequest pr) {
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (project.isDisposed()) continue;
            hideToolWindowIfVisible(project);
            PrPilotUiCoordinator.getInstance(project).requestSelectPr(pr, "notification");
            return;
        }
    }

    /** Opens or focuses PR Pilot's main editor tab in the first open project. */
    public static void activateToolWindow() {
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (project.isDisposed()) continue;
            openEditorForProject(project);
            return;
        }
    }

    private static void hideToolWindowIfVisible(@NotNull Project project) {
        ToolWindowManager manager = ToolWindowManager.getInstance(project);
        if (manager == null) {
            return;
        }
        ToolWindow toolWindow = manager.getToolWindow(TOOL_WINDOW_ID);
        if (toolWindow != null && toolWindow.isVisible()) {
            toolWindow.hide(null);
        }
    }

    private static final class OpenInEditorAction extends AnAction implements DumbAware {
        private final Project project;
        private final ToolWindow toolWindow;

        OpenInEditorAction(Project project, ToolWindow toolWindow) {
            super(
                    "Open PR Pilot",
                    "Open or focus PR Pilot in a main editor tab",
                    AllIcons.Actions.OpenNewTab);
            this.project = project;
            this.toolWindow = toolWindow;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            if (project != null && !project.isDisposed()) {
                openEditorAndHideToolWindow(project, toolWindow);
            }
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }
    }

    private static final class ReloadAction extends AnAction implements DumbAware {
        private final Project project;
        private final WebviewPanel webviewPanel;

        ReloadAction(Project project, WebviewPanel webviewPanel) {
            super(
                    "Reload PR Pilot View",
                    "Reloads the embedded view. Edits not yet saved to GitHub may be lost.",
                    AllIcons.Actions.ForceRefresh);
            this.project = project;
            this.webviewPanel = webviewPanel;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            if (webviewPanel != null) {
                webviewPanel.reload();
            }
            if (project != null && !project.isDisposed()) {
                PrPilotUiCoordinator.getInstance(project).reloadEditorSurfaces();
            }
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }
    }

    private static final class SettingsAction extends AnAction implements DumbAware {
        private final Project project;

        SettingsAction(Project project) {
            super("Settings", "Open PR Pilot settings", AllIcons.General.Settings);
            this.project = project;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, PluginSettingsConfigurable.class);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }
    }

    /** Resolved setup screen reason, user-facing detail, and GitHub CLI login command. */
    public record SetupFailure(String reason, String detail, String ghAuthCommand) {}

    /**
     * Maps a pull-request discovery failure to its {@code setupRequired} reason, detail copy, and
     * host-aware {@code gh auth login} command so GitHub CLI install/auth recovery works on both
     * {@code github.com} and GitHub Enterprise.
     */
    public static SetupFailure classifySetupFailure(Exception e, String rawBaseUrl) {
        String ghAuthCommand = WebviewPanel.buildGhAuthLoginCommand(rawBaseUrl);
        String msg =
                e != null && e.getMessage() != null ? e.getMessage().toLowerCase(Locale.ROOT) : "";
        if (msg.contains("not installed")
                || msg.contains("no such file")
                || msg.contains("error=2")
                || msg.contains("command not found")
                || msg.contains("enoent")) {
            return new SetupFailure(
                    "gh_not_installed",
                    "The 'gh' CLI was not found. Install it from https://cli.github.com, then run '"
                            + ghAuthCommand
                            + "' in a terminal and click Refresh.",
                    ghAuthCommand);
        }
        if (msg.contains("gh auth")
                || msg.contains("not signed in")
                || msg.contains("not authenticated")
                || msg.contains("auth token")
                || msg.contains("bad credentials")
                || msg.contains("unauthorized")
                || msg.contains("forbidden")
                || msg.contains(" 401")
                || msg.contains(" 403")) {
            return new SetupFailure(
                    "gh_not_authenticated",
                    "Run '"
                            + ghAuthCommand
                            + "' in a terminal to authenticate, then click Refresh.",
                    ghAuthCommand);
        }
        return new SetupFailure(
                "load_failed", UserFacingErrors.forGitHub(e, "load pull requests"), ghAuthCommand);
    }

    static void wireWebviewLoading(Project project, WebviewPanel webviewPanel) {
        wireWebviewLoading(project, webviewPanel, () -> {});
    }

    static void wireWebviewLoading(
            Project project, WebviewPanel webviewPanel, @NotNull Runnable afterReadyLoad) {
        webviewPanel.setOnPageReady(
                () ->
                        ApplicationManager.getApplication()
                                .executeOnPooledThread(
                                        () -> {
                                            try {
                                                loadAndPushPRs(
                                                        project, webviewPanel, afterReadyLoad);
                                            } catch (Exception e) {
                                                log.warn(
                                                        "Webview PR load failed",
                                                        LogRedactor.sanitize(e));
                                                String baseUrl =
                                                        ApplicationManager.getApplication() != null
                                                                ? PluginSettings.getInstance()
                                                                        .getGithubBaseUrl()
                                                                : "https://github.com";
                                                SetupFailure failure =
                                                        classifySetupFailure(e, baseUrl);
                                                ApplicationManager.getApplication()
                                                        .invokeLater(
                                                                () ->
                                                                        webviewPanel
                                                                                .pushSetupRequired(
                                                                                        failure
                                                                                                .reason(),
                                                                                        failure
                                                                                                .detail()));
                                            }
                                        }));
    }

    private static void loadAndPushPRs(
            Project project, WebviewPanel webviewPanel, @NotNull Runnable afterReadyLoad) {
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
                log.warn("Could not fetch starred repos", LogRedactor.sanitize(e));
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
                                if (webviewPanel.isDisposedForHost() || project.isDisposed()) {
                                    return;
                                }
                                boolean loaded =
                                        webviewPanel.loadPRs(
                                                prs,
                                                defaultRepo,
                                                webviewPanel.getSearchScope(),
                                                currentRepo,
                                                finalLimited,
                                                result.reviewStatusAvailable(),
                                                providerSetup);
                                if (loaded) {
                                    afterReadyLoad.run();
                                }
                            });
        } catch (Exception e) {
            log.warn("Failed to load PR list for webview", LogRedactor.sanitize(e));
            String baseUrl =
                    ApplicationManager.getApplication() != null
                            ? PluginSettings.getInstance().getGithubBaseUrl()
                            : "https://github.com";
            SetupFailure failure = classifySetupFailure(e, baseUrl);
            ApplicationManager.getApplication()
                    .invokeLater(
                            () ->
                                    webviewPanel.pushSetupRequired(
                                            failure.reason(), failure.detail()));
        }
    }
}
