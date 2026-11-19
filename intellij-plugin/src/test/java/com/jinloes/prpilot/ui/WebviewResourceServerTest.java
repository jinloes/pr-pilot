package com.jinloes.prpilot.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class WebviewResourceServerTest {

    @Nested
    class ResolveResourcePath {

        @Test
        void rootMapsToIndexHtml() {
            assertThat(WebviewResourceServer.resolveResourcePath("/"))
                    .isEqualTo("/webview/index.html");
        }

        @Test
        void normalAssetIsAllowed() {
            assertThat(WebviewResourceServer.resolveResourcePath("/assets/index.js"))
                    .isEqualTo("/webview/assets/index.js");
        }

        @Test
        void parentSegmentIsRejected() {
            assertThat(WebviewResourceServer.resolveResourcePath("/../META-INF/plugin.xml"))
                    .isNull();
        }

        @Test
        void nestedTraversalIsRejected() {
            assertThat(WebviewResourceServer.resolveResourcePath("/assets/../../etc/passwd"))
                    .isNull();
        }

        @Test
        void pathThatNormalizesBackInsideWebviewIsAllowed() {
            assertThat(WebviewResourceServer.resolveResourcePath("/assets/../index.html"))
                    .isEqualTo("/webview/index.html");
        }

        @Test
        void pathWithoutLeadingSlashIsRejected() {
            assertThat(WebviewResourceServer.resolveResourcePath("index.html")).isNull();
        }

        @Test
        void blankPathIsRejected() {
            assertThat(WebviewResourceServer.resolveResourcePath("")).isNull();
            assertThat(WebviewResourceServer.resolveResourcePath(null)).isNull();
        }

        @Test
        void multipleParentSegmentsRejected() {
            assertThat(WebviewResourceServer.resolveResourcePath("/../../foo")).isNull();
        }

        @Test
        void encodedQueryFragmentBackslashSpaceAndSemicolonPathsAreRejected() {
            assertThat(WebviewResourceServer.resolveResourcePath("/index.html?foo")).isNull();
            assertThat(WebviewResourceServer.resolveResourcePath("/index.html#bar")).isNull();
            assertThat(WebviewResourceServer.resolveResourcePath("/a b.js")).isNull();
            assertThat(WebviewResourceServer.resolveResourcePath("/assets\\index.js")).isNull();
            assertThat(WebviewResourceServer.resolveResourcePath("/..;/META-INF/plugin.xml"))
                    .isNull();
            assertThat(WebviewResourceServer.resolveResourcePath("/index.html\u0000")).isNull();
        }
    }

    @Nested
    class HttpServing {

        @Test
        void servesHtmlWithCspAndNosniffAndReturns404ForMalformedAndEncodedUris() throws Exception {
            WebviewResourceServer resourceServer = new WebviewResourceServer();
            HttpServer started = resourceServer.tryStart();
            assertThat(started).isNotNull();
            resourceServer.adopt(started);
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + started.getAddress().getPort();
            try {
                HttpResponse<String> rootResponse =
                        client.send(
                                HttpRequest.newBuilder(URI.create(base + "/")).GET().build(),
                                HttpResponse.BodyHandlers.ofString());
                assertThat(rootResponse.statusCode()).isEqualTo(200);
                assertThat(rootResponse.headers().firstValue("Content-Security-Policy"))
                        .hasValue(WebviewResourceServer.HTML_CONTENT_SECURITY_POLICY);
                assertThat(rootResponse.headers().firstValue("X-Content-Type-Options"))
                        .hasValue("nosniff");
                assertThat(rootResponse.headers().firstValue("Cache-Control")).hasValue("no-store");

                for (String badPath :
                        new String[] {
                            "/does-not-exist.js",
                            "/%2e%2e/META-INF/plugin.xml",
                            "/a%20b",
                            "/index.html%3ffoo",
                            "/index.html%23bar",
                            "/assets%5cindex.js",
                            "/..;/index.html"
                        }) {
                    HttpResponse<String> rejected =
                            client.send(
                                    HttpRequest.newBuilder(URI.create(base + badPath))
                                            .GET()
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString());
                    assertThat(rejected.statusCode())
                            .as("expected 404 for %s", badPath)
                            .isEqualTo(404);
                }
            } finally {
                resourceServer.stop();
            }
        }
    }
}
