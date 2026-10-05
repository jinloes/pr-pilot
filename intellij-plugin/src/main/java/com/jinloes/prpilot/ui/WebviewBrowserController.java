package com.jinloes.prpilot.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.sun.net.httpserver.HttpServer;
import org.cef.browser.CefBrowser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns webview server startup and browser repaint/bridge plumbing. */
final class WebviewBrowserController {
    private static final Logger log = LoggerFactory.getLogger(WebviewBrowserController.class);
    private final WebviewPanel panel;

    WebviewBrowserController(WebviewPanel panel) {
        this.panel = panel;
    }

    void startServerAndLoad() {
        HttpServer server = panel.resourceServer.tryStart();
        if (panel.isDisposedForHost()) {
            if (server != null) {
                server.stop(0);
            }
            return;
        }
        panel.resourceServer.adopt(server);
        if (server == null) {
            ApplicationManager.getApplication()
                    .invokeLater(
                            () -> {
                                if (!panel.isDisposedForHost()) {
                                    panel.browser.loadHTML(
                                            "<html><body style='color:#e8a030;"
                                                    + "background:#0a0805;"
                                                    + "font-family:monospace'>"
                                                    + "<p>Could not start webview server</p>"
                                                    + "</body></html>");
                                }
                            });
            return;
        }
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        panel.webviewUrl = url;
        log.info("Loading webview from {}", url);
        ApplicationManager.getApplication()
                .invokeLater(
                        () -> {
                            if (!panel.isDisposedForHost()) {
                                panel.browser.loadURL(url);
                            }
                        });
    }

    void injectBridge(CefBrowser cefBrowser) {
        String js =
                "window.cefQuery = function(opts) { "
                        + panel.bridgeQuery.inject("opts.request")
                        + " };";
        cefBrowser.executeJavaScript(js, cefBrowser.getURL(), 0);
    }

    void scheduleLayoutRepaint() {
        ApplicationManager.getApplication()
                .invokeLater(
                        () -> {
                            if (panel.isDisposedForHost()) {
                                return;
                            }
                            panel.layoutRepaintAlarm.cancelAllRequests();
                            panel.layoutRepaintAlarm.addRequest(
                                    () -> {
                                        if (panel.isDisposedForHost()) {
                                            return;
                                        }
                                        CefBrowser cefBrowser = panel.browser.getCefBrowser();
                                        if (cefBrowser != null) {
                                            cefBrowser.invalidate();
                                        }
                                        panel.browser.getComponent().revalidate();
                                        panel.browser.getComponent().repaint();
                                        panel.browserPanel.revalidate();
                                        panel.browserPanel.repaint();
                                    },
                                    WebviewPanel.LAYOUT_REPAINT_DELAY_MS);
                        });
    }
}
