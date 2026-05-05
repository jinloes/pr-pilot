package com.jinloes.claudereviews.ui;

import static com.intellij.openapi.application.ApplicationManager.getApplication;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import com.jinloes.claudereviews.model.PullRequest;
import com.jinloes.claudereviews.services.PendingReviewIndex;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JComponent;
import lombok.extern.slf4j.Slf4j;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;

/**
 * JCEF browser panel that loads the React webview and wires the Java↔JS bridge.
 *
 * <p>Bridge protocol (matches webview/src/bridge/types.ts):
 *
 * <ul>
 *   <li>Java→JS: {@code window.__handleMessage(json)} — pushed on page ready and on refresh
 *   <li>JS→Java: {@code window.cefQuery({request: json})} — injected via CefMessageRouter
 * </ul>
 */
@Slf4j
public class WebviewPanel {

    private record WebviewPr(
            int number,
            String title,
            String owner,
            String repo,
            String author,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("htmlUrl") String htmlUrl,
            @JsonProperty("hasDraft") boolean hasDraft) {}

    private record PrListMessage(String type, List<WebviewPr> prs) {}

    private final JBCefBrowser browser;
    private final JBCefJSQuery bridgeQuery;
    private final ObjectMapper mapper = new ObjectMapper();
    private final PendingReviewIndex pendingIndex = new PendingReviewIndex();

    private volatile List<PullRequest> cachedPRs = List.of();
    private Consumer<PullRequest> onPRSelected = pr -> {};
    private Runnable onPageReady = () -> {};

    private static volatile Path cachedWebviewDir;

    public WebviewPanel() {
        browser = new JBCefBrowser();
        bridgeQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);

        bridgeQuery.addHandler(
                request -> {
                    handleIncoming(request);
                    return new JBCefJSQuery.Response(null);
                });

        browser.getJBCefClient()
                .addLoadHandler(
                        new CefLoadHandlerAdapter() {
                            @Override
                            public void onLoadEnd(
                                    CefBrowser cefBrowser, CefFrame frame, int httpStatusCode) {
                                if (!frame.isMain()) {
                                    return;
                                }
                                injectBridge(cefBrowser);
                                getApplication().invokeLater(onPageReady);
                            }
                        },
                        browser.getCefBrowser());

        loadWebview();
    }

    private void injectBridge(CefBrowser cefBrowser) {
        String js =
                "window.cefQuery = function(opts) { " + bridgeQuery.inject("opts.request") + " };";
        cefBrowser.executeJavaScript(js, cefBrowser.getURL(), 0);
    }

    private void handleIncoming(String json) {
        try {
            var node = mapper.readTree(json);
            String type = node.path("type").asText();
            if ("selectPR".equals(type)) {
                int number = node.path("number").asInt();
                String owner = node.path("owner").asText();
                String repo = node.path("repo").asText();
                cachedPRs.stream()
                        .filter(
                                pr ->
                                        pr.getNumber() == number
                                                && pr.getOwner().equals(owner)
                                                && pr.getRepo().equals(repo))
                        .findFirst()
                        .ifPresent(onPRSelected);
            } else if ("refreshPRs".equals(type)) {
                getApplication().invokeLater(onPageReady);
            }
        } catch (Exception e) {
            log.warn("Bridge message error: {}", e.getMessage());
        }
    }

    private void loadWebview() {
        try {
            Path dir = extractWebviewResources();
            browser.loadURL(dir.resolve("index.html").toUri().toString());
        } catch (IOException e) {
            log.error("Webview resource extraction failed", e);
            browser.loadHTML(
                    "<html><body style='color:#e8a030;background:#0a0805;"
                            + "font-family:monospace'>"
                            + "<p>Webview not built. Run:</p>"
                            + "<pre>./gradlew :intellij-plugin:buildPlugin</pre>"
                            + "</body></html>");
        }
    }

    private static synchronized Path extractWebviewResources() throws IOException {
        if (cachedWebviewDir != null && Files.exists(cachedWebviewDir.resolve("index.html"))) {
            return cachedWebviewDir;
        }
        InputStream manifest = WebviewPanel.class.getResourceAsStream("/webview-manifest.txt");
        if (manifest == null) {
            throw new IOException(
                    "webview-manifest.txt not found in classpath — run"
                            + " ./gradlew :intellij-plugin:buildPlugin first");
        }
        Path dir = Files.createTempDirectory("claude-reviews-webview-");
        try (var reader =
                new BufferedReader(new InputStreamReader(manifest, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String entry = line.strip();
                if (entry.isEmpty()) {
                    continue;
                }
                Path target = dir.resolve(entry);
                Files.createDirectories(target.getParent());
                try (InputStream resource =
                        WebviewPanel.class.getResourceAsStream("/webview/" + entry)) {
                    if (resource != null) {
                        Files.copy(resource, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
        cachedWebviewDir = dir;
        return dir;
    }

    /** Pushes the PR list into the webview via the bridge. Safe to call from the EDT. */
    public void loadPRs(List<PullRequest> prs) {
        cachedPRs = prs;
        List<WebviewPr> dtos =
                prs.stream()
                        .map(
                                pr ->
                                        new WebviewPr(
                                                pr.getNumber(),
                                                pr.getTitle(),
                                                pr.getOwner(),
                                                pr.getRepo(),
                                                pr.getAuthor(),
                                                pr.getCreatedAt(),
                                                pr.getHtmlUrl(),
                                                pendingIndex.hasDraft(
                                                        pr.getOwner(),
                                                        pr.getRepo(),
                                                        pr.getNumber())))
                        .toList();
        try {
            String json = mapper.writeValueAsString(new PrListMessage("prListLoaded", dtos));
            String escaped = escapeForJsString(json);
            browser.getCefBrowser()
                    .executeJavaScript(
                            "if(window.__handleMessage)"
                                    + "{window.__handleMessage('"
                                    + escaped
                                    + "');}",
                            browser.getCefBrowser().getURL(),
                            0);
        } catch (JsonProcessingException e) {
            log.warn("PR list serialization failed: {}", e.getMessage());
        }
    }

    private static String escapeForJsString(String s) {
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\u2028", "\\u2028")
                .replace("\u2029", "\\u2029");
    }

    public void setOnPRSelected(Consumer<PullRequest> callback) {
        this.onPRSelected = callback;
    }

    public void setOnPageReady(Runnable callback) {
        this.onPageReady = callback;
    }

    public JComponent getComponent() {
        return (JComponent) browser.getComponent();
    }
}
