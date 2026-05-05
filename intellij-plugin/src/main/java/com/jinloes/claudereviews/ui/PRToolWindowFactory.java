package com.jinloes.claudereviews.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.jcef.JBCefApp;
import com.jinloes.claudereviews.model.PullRequest;
import com.jinloes.claudereviews.services.IntellijGitHubService;
import com.jinloes.claudereviews.settings.PluginSettings;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

@Slf4j
public class PRToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ContentFactory factory = ContentFactory.getInstance();

        PRToolWindow classic = new PRToolWindow(project);
        Content classicContent = factory.createContent(classic.getContent(), "Classic", false);
        toolWindow.getContentManager().addContent(classicContent);

        if (JBCefApp.isSupported()) {
            WebviewPanel webviewPanel = new WebviewPanel();
            wireWebviewLoading(webviewPanel);
            Content webContent = factory.createContent(webviewPanel.getComponent(), "WebUI", false);
            toolWindow.getContentManager().addContent(webContent);
        }
    }

    private void wireWebviewLoading(WebviewPanel webviewPanel) {
        webviewPanel.setOnPageReady(
                () ->
                        ApplicationManager.getApplication()
                                .executeOnPooledThread(
                                        () -> {
                                            try {
                                                loadAndPushPRs(webviewPanel);
                                            } catch (Exception e) {
                                                log.warn(
                                                        "Webview PR load failed: {}",
                                                        e.getMessage());
                                            }
                                        }));
    }

    private void loadAndPushPRs(WebviewPanel webviewPanel) throws Exception {
        String token = PluginSettings.getInstance().getGithubToken();
        if (token == null) {
            log.info("No GitHub token available — skipping webview PR load");
            return;
        }
        List<PullRequest> prs =
                IntellijGitHubService.getInstance()
                        .searchPRs(token, "is:pr is:open draft:false review-requested:@me");
        prs.sort(Comparator.comparing(PullRequest::getCreatedAt).reversed());
        ApplicationManager.getApplication().invokeLater(() -> webviewPanel.loadPRs(prs));
    }
}
