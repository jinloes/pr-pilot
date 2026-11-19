package com.jinloes.prpilot.ui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Embedded localhost HTTP server for the webview bundle, so Chromium treats every request as
 * same-origin.
 */
final class WebviewResourceServer {

    private static final Logger log = LoggerFactory.getLogger(WebviewResourceServer.class);
    static final String HTML_CONTENT_SECURITY_POLICY =
            "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline';"
                    + " img-src 'self' data:; font-src 'self' data:; connect-src 'none';"
                    + " frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    private volatile HttpServer server;

    /** Starts a new server, or returns null when it cannot bind. The caller decides to adopt it. */
    HttpServer tryStart() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/", this::serveResource);
            s.start();
            log.debug("Webview HTTP server listening on port {}", s.getAddress().getPort());
            return s;
        } catch (IOException e) {
            log.error("Failed to start webview HTTP server", e);
            return null;
        }
    }

    void adopt(HttpServer started) {
        server = started;
    }

    void stop() {
        HttpServer current = server;
        if (current != null) {
            try {
                current.stop(0);
            } catch (Exception e) {
                log.warn("HttpServer.stop failed: {}", e.getMessage());
            }
            server = null;
        }
    }

    private void serveResource(HttpExchange exchange) throws IOException {
        String resource = resolveResourcePath(exchange.getRequestURI().getPath());
        if (resource == null) {
            exchange.sendResponseHeaders(404, 0);
            exchange.close();
            return;
        }
        try (InputStream in = WebviewResourceServer.class.getResourceAsStream(resource)) {
            if (in == null) {
                exchange.sendResponseHeaders(404, 0);
                exchange.close();
                return;
            }
            byte[] bytes = in.readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", mimeFor(resource));
            exchange.getResponseHeaders().add("Cache-Control", "no-store");
            exchange.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
            if (resource.endsWith(".html")) {
                exchange.getResponseHeaders()
                        .add("Content-Security-Policy", HTML_CONTENT_SECURITY_POLICY);
            }
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    /**
     * Maps a request path to a classpath resource path under {@code /webview/}, or returns null if
     * the request is malformed or would escape that root.
     */
    static String resolveResourcePath(String requestPath) {
        if (StringUtils.isBlank(requestPath)
                || !requestPath.startsWith("/")
                || requestPath.indexOf('\\') >= 0
                || requestPath.indexOf('?') >= 0
                || requestPath.indexOf('#') >= 0
                || requestPath.indexOf(' ') >= 0
                || requestPath.indexOf(';') >= 0
                || requestPath.chars().anyMatch(Character::isISOControl)) {
            return null;
        }
        String path = "/".equals(requestPath) ? "/index.html" : requestPath;
        String candidate = "/webview" + path;
        String normalized;
        try {
            normalized = new URI(null, null, candidate, null).normalize().getPath();
        } catch (URISyntaxException | IllegalArgumentException malformed) {
            return null;
        }
        if (normalized == null
                || !normalized.startsWith("/webview/")
                || normalized.contains("/..")) {
            return null;
        }
        return normalized;
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
}
