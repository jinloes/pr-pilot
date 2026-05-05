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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
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
 * <p>Resources are served from an embedded localhost HTTP server so that Chromium treats every
 * request as same-origin — avoiding the null-origin CORS failures that occur when loading ES
 * modules from {@code file://} URLs.
 *
 * <p>Bridge protocol (matches webview/src/bridge/types.ts):
 *
 * <ul>
 *   <li>Java→JS: {@code window.__handleMessage(json)} — pushed after page ready
 *   <li>JS→Java: {@code window.cefQuery({request: json})} — injected via JBCefJSQuery
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

    private final HttpServer httpServer;
    private final JBCefBrowser browser;
    private final JBCefJSQuery bridgeQuery;
    private final ObjectMapper mapper = new ObjectMapper();
    private final PendingReviewIndex pendingIndex = new PendingReviewIndex();

    private volatile List<PullRequest> cachedPRs = List.of();
    private Consumer<PullRequest> onPRSelected = pr -> {};
    private Runnable onPageReady = () -> {};

    public WebviewPanel() {
        httpServer = tryStartServer();
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

        if (httpServer != null) {
            String url = "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/";
            log.debug("Loading webview from {}", url);
            browser.loadURL(url);
        } else {
            browser.loadHTML(
                    "<html><body style='color:#e8a030;background:#0a0805;"
                            + "font-family:monospace'>"
                            + "<p>Could not start webview server</p>"
                            + "</body></html>");
        }
    }

    private HttpServer tryStartServer() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/", this::serveResource);
            s.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> s.stop(0)));
            log.debug("Webview HTTP server listening on port {}", s.getAddress().getPort());
            return s;
        } catch (IOException e) {
            log.error("Failed to start webview HTTP server", e);
            return null;
        }
    }

    private void serveResource(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("/".equals(path)) {
            path = "/index.html";
        }
        try (InputStream in = WebviewPanel.class.getResourceAsStream("/webview" + path)) {
            if (in == null) {
                exchange.sendResponseHeaders(404, 0);
                exchange.getResponseBody().close();
                return;
            }
            byte[] bytes = in.readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", mimeFor(path));
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.getResponseBody().close();
        }
    }

    private static String mimeFor(String path) {
        if (path.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (path.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (path.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        return "application/octet-stream";
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

    /** Pushes the PR list into the webview via the bridge. Call from the EDT. */
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
                .replace("\n", "\\n");
    }

    public void setOnPRSelected(Consumer<PullRequest> callback) {
        this.onPRSelected = callback;
    }

    public void setOnPageReady(Runnable callback) {
        this.onPageReady = callback;
    }

    public JComponent getComponent() {
        return browser.getComponent();
    }
}
