package com.jinloes.prpilot.ui;

import static org.assertj.core.api.Assertions.assertThat;

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
    }
}
