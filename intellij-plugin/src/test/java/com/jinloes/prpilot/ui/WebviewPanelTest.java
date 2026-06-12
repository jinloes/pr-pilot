package com.jinloes.prpilot.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.PullRequest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class WebviewPanelTest {

    @Nested
    class WorktreeKey {

        @Test
        void normalizesOwnerAndRepoCase() {
            assertThat(WebviewPanel.worktreeKey(42, "JinLoes", "PR-Pilot"))
                    .isEqualTo("jinloes/pr-pilot#42");
        }

        @Test
        void keyIncludesPrNumber() {
            assertThat(WebviewPanel.worktreeKey(1, "a", "b"))
                    .isNotEqualTo(WebviewPanel.worktreeKey(2, "a", "b"));
        }
    }

    @Nested
    class IsSamePr {

        @Test
        void matchesByNumberAndRepoIgnoringCase() {
            PullRequest left = new PullRequest("t", "", "OwNeR", "RePo", 7, "", "a", "");
            PullRequest right = new PullRequest("t2", "", "owner", "repo", 7, "", "b", "");

            assertThat(WebviewPanel.isSamePr(left, right)).isTrue();
        }

        @Test
        void rejectsDifferentNumberOrRepo() {
            PullRequest base = new PullRequest("t", "", "owner", "repo", 7, "", "a", "");
            PullRequest differentNumber = new PullRequest("t", "", "owner", "repo", 8, "", "a", "");
            PullRequest differentRepo = new PullRequest("t", "", "owner", "other", 7, "", "a", "");

            assertThat(WebviewPanel.isSamePr(base, differentNumber)).isFalse();
            assertThat(WebviewPanel.isSamePr(base, differentRepo)).isFalse();
        }

        @Test
        void returnsFalseWhenEitherIsNull() {
            PullRequest pr = new PullRequest("t", "", "owner", "repo", 7, "", "a", "");

            assertThat(WebviewPanel.isSamePr(pr, null)).isFalse();
            assertThat(WebviewPanel.isSamePr(null, pr)).isFalse();
        }
    }

    @Nested
    class ResolveResourcePath {

        @Test
        void rootMapsToIndexHtml() {
            assertThat(WebviewPanel.resolveResourcePath("/")).isEqualTo("/webview/index.html");
        }

        @Test
        void normalAssetIsAllowed() {
            assertThat(WebviewPanel.resolveResourcePath("/assets/index.js"))
                    .isEqualTo("/webview/assets/index.js");
        }

        @Test
        void parentSegmentIsRejected() {
            assertThat(WebviewPanel.resolveResourcePath("/../META-INF/plugin.xml")).isNull();
        }

        @Test
        void nestedTraversalIsRejected() {
            assertThat(WebviewPanel.resolveResourcePath("/assets/../../etc/passwd")).isNull();
        }

        @Test
        void pathThatNormalizesBackInsideWebviewIsAllowed() {
            assertThat(WebviewPanel.resolveResourcePath("/assets/../index.html"))
                    .isEqualTo("/webview/index.html");
        }

        @Test
        void pathWithoutLeadingSlashIsRejected() {
            assertThat(WebviewPanel.resolveResourcePath("index.html")).isNull();
        }

        @Test
        void blankPathIsRejected() {
            assertThat(WebviewPanel.resolveResourcePath("")).isNull();
            assertThat(WebviewPanel.resolveResourcePath(null)).isNull();
        }

        @Test
        void multipleParentSegmentsRejected() {
            assertThat(WebviewPanel.resolveResourcePath("/../../foo")).isNull();
        }
    }
}
