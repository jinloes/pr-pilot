package com.jinloes.claudereviews.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.claudereviews.model.LineComment;
import com.jinloes.claudereviews.model.ReviewResult;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class GitHubServiceRoundTripTest {

    private static ReviewResult review(String summary, String verdict, List<LineComment> comments) {
        return new ReviewResult(summary, verdict, comments);
    }

    @Nested
    class EncodeBody {

        @Test
        void summaryStoredInHiddenTag() {
            String body = GitHubService.encodeBody(review("My summary", "APPROVE", List.of()));
            // Summary must be in the hidden tag, NOT in visible text at the start
            assertThat(body).contains("<!-- claude-summary: My summary -->");
            assertThat(body).doesNotStartWith("My summary");
        }

        @Test
        void generalComment_noTypePrefix() {
            LineComment general = new LineComment("", 0, "issue", "look at this");
            String body = GitHubService.encodeBody(review("s", "COMMENT", List.of(general)));
            // Type prefix must not appear in the human-readable general notes section
            assertThat(body).doesNotContain("[ISSUE]");
            assertThat(body).contains("look at this");
        }

        @Test
        void containsVerdictTag() {
            String body = GitHubService.encodeBody(review("s", "REQUEST_CHANGES", List.of()));
            assertThat(body).contains("<!-- claude-verdict: REQUEST_CHANGES -->");
        }

        @Test
        void containsCommentsJson() {
            LineComment c = new LineComment("src/Foo.java", 42, "issue", "null check missing");
            String body = GitHubService.encodeBody(review("s", "COMMENT", List.of(c)));
            assertThat(body).contains("<!-- claude-comments:");
            assertThat(body).contains("src/Foo.java");
            assertThat(body).contains("42");
            assertThat(body).contains("issue");
            assertThat(body).contains("null check missing");
        }

        @Test
        void generalComment_includedInCommentsJson() {
            LineComment general = new LineComment("", 0, "note", "overall looks good");
            String body = GitHubService.encodeBody(review("s", "APPROVE", List.of(general)));
            assertThat(body).contains("overall looks good");
        }

        @Test
        void summaryContainingCommentTerminator_escapedSoTagDoesNotBreak() {
            ReviewResult r = review("See: <!-- end --> tag", "APPROVE", List.of());
            String body = GitHubService.encodeBody(r);
            // The raw "-->" in the summary must be escaped so the HTML comment is well-formed
            assertThat(body).doesNotContain("<!-- claude-summary: See: <!-- end --> tag -->");
            // Round-trip still recovers a readable summary (with "-- >" substitution)
            ReviewResult decoded = GitHubService.decodeReview(body, List.of());
            assertThat(decoded.getSummary()).contains("See:");
        }

        @Test
        void commentBodyContainingCommentTerminator_roundTrips() {
            LineComment c = new LineComment("src/Foo.java", 5, "issue", "closes --> stream");
            String body = GitHubService.encodeBody(review("s", "COMMENT", List.of(c)));
            // The embedded JSON must not contain a raw "-->" that would break the HTML comment
            int commentsStart = body.indexOf("<!-- claude-comments:");
            int commentsEnd =
                    body.indexOf(" -->", commentsStart + "<!-- claude-comments:".length());
            String jsonBlob = body.substring(commentsStart, commentsEnd);
            assertThat(jsonBlob).doesNotContain("-->");
        }

        @Test
        void blankBodyComment_preservedInEmbeddedJson() {
            // Manually added comments start with blank body. encodeBody must include them so they
            // round-trip through the JSON blob even though saveDraftReview skips them as inline
            // GitHub comments (to avoid a 422 from the GitHub API).
            LineComment blank = new LineComment("src/Foo.java", 7, "note", "");
            String body = GitHubService.encodeBody(review("s", "COMMENT", List.of(blank)));
            ReviewResult decoded = GitHubService.decodeReview(body, List.of());
            assertThat(decoded.getLineComments()).hasSize(1);
            assertThat(decoded.getLineComments().get(0).getFile()).isEqualTo("src/Foo.java");
            assertThat(decoded.getLineComments().get(0).getLine()).isEqualTo(7);
            assertThat(decoded.getLineComments().get(0).getBody()).isEqualTo("");
        }
    }

    @Nested
    class DecodeReview {

        @Test
        void parsesSummary() {
            String body =
                    GitHubService.encodeBody(review("The summary text", "COMMENT", List.of()));
            ReviewResult decoded = GitHubService.decodeReview(body, List.of());
            assertThat(decoded.getSummary()).isEqualTo("The summary text");
        }

        @Test
        void parsesVerdict() {
            String body = GitHubService.encodeBody(review("s", "REQUEST_CHANGES", List.of()));
            ReviewResult decoded = GitHubService.decodeReview(body, List.of());
            assertThat(decoded.getVerdict()).isEqualTo("REQUEST_CHANGES");
        }

        @Test
        void parsesInlineCommentFromEmbeddedJson() {
            LineComment c = new LineComment("src/Bar.java", 10, "suggestion", "extract method");
            String body = GitHubService.encodeBody(review("s", "COMMENT", List.of(c)));
            ReviewResult decoded = GitHubService.decodeReview(body, List.of());
            assertThat(decoded.getLineComments()).hasSize(1);
            LineComment got = decoded.getLineComments().get(0);
            assertThat(got.getFile()).isEqualTo("src/Bar.java");
            assertThat(got.getLine()).isEqualTo(10);
            assertThat(got.getType()).isEqualTo("suggestion");
            assertThat(got.getBody()).isEqualTo("extract method");
        }

        @Test
        void embeddedJsonTakesPrecedenceOverApiComments() {
            LineComment embedded = new LineComment("src/A.java", 5, "issue", "from embedded");
            String body = GitHubService.encodeBody(review("s", "COMMENT", List.of(embedded)));

            // API comment that would conflict — embedded JSON wins so this is ignored
            List<GitHubService.GhReviewComment> apiComments =
                    List.of(
                            new GitHubService.GhReviewComment(
                                    "src/B.java", 99, null, "[NOTE] from api"));

            ReviewResult decoded = GitHubService.decodeReview(body, apiComments);
            assertThat(decoded.getLineComments()).hasSize(1);
            assertThat(decoded.getLineComments().get(0).getFile()).isEqualTo("src/A.java");
        }

        @Test
        void fallsBackToApiComments_whenNoEmbeddedJson() {
            // Body without embedded JSON block (legacy format)
            String body = "Summary text\n\n<!-- claude-verdict: APPROVE -->";
            List<GitHubService.GhReviewComment> apiComments =
                    List.of(
                            new GitHubService.GhReviewComment(
                                    "src/Foo.java", 7, null, "[ISSUE] legacy comment"));

            ReviewResult decoded = GitHubService.decodeReview(body, apiComments);
            assertThat(decoded.getLineComments()).hasSize(1);
            assertThat(decoded.getLineComments().get(0).getFile()).isEqualTo("src/Foo.java");
            assertThat(decoded.getLineComments().get(0).getLine()).isEqualTo(7);
            assertThat(decoded.getLineComments().get(0).getType()).isEqualTo("issue");
        }

        @Test
        void nullLineInApiComment_usesOriginalLine() {
            String body = "Summary\n\n<!-- claude-verdict: COMMENT -->";
            List<GitHubService.GhReviewComment> apiComments =
                    List.of(
                            new GitHubService.GhReviewComment(
                                    "src/Foo.java", null, 15, "[NOTE] note text"));

            ReviewResult decoded = GitHubService.decodeReview(body, apiComments);
            assertThat(decoded.getLineComments().get(0).getLine()).isEqualTo(15);
        }
    }

    @Nested
    class BuildCommentArray {

        @Test
        void validComment_included() {
            ReviewResult r =
                    review(
                            "s",
                            "APPROVE",
                            List.of(new LineComment("src/Foo.java", 5, "issue", "body")));
            assertThat(GitHubService.buildCommentArray(r).size()).isEqualTo(1);
        }

        @Test
        void blankFile_skipped() {
            ReviewResult r =
                    review("s", "APPROVE", List.of(new LineComment("", 5, "issue", "body")));
            assertThat(GitHubService.buildCommentArray(r).size()).isEqualTo(0);
        }

        @Test
        void zeroLine_skipped() {
            ReviewResult r =
                    review(
                            "s",
                            "APPROVE",
                            List.of(new LineComment("src/Foo.java", 0, "issue", "body")));
            assertThat(GitHubService.buildCommentArray(r).size()).isEqualTo(0);
        }

        @Test
        void negativeLine_skipped() {
            ReviewResult r =
                    review(
                            "s",
                            "APPROVE",
                            List.of(new LineComment("src/Foo.java", -1, "issue", "body")));
            assertThat(GitHubService.buildCommentArray(r).size()).isEqualTo(0);
        }

        @Test
        void blankBody_skipped() {
            ReviewResult r =
                    review(
                            "s",
                            "APPROVE",
                            List.of(new LineComment("src/Foo.java", 5, "issue", "")));
            assertThat(GitHubService.buildCommentArray(r).size()).isEqualTo(0);
        }

        @Test
        void aSlashPrefix_stripped() {
            ReviewResult r =
                    review(
                            "s",
                            "APPROVE",
                            List.of(new LineComment("a/src/Foo.java", 5, "issue", "body")));
            assertThat(GitHubService.buildCommentArray(r).get(0).get("path").asText())
                    .isEqualTo("src/Foo.java");
        }

        @Test
        void bSlashPrefix_stripped() {
            ReviewResult r =
                    review(
                            "s",
                            "APPROVE",
                            List.of(new LineComment("b/src/Foo.java", 5, "issue", "body")));
            assertThat(GitHubService.buildCommentArray(r).get(0).get("path").asText())
                    .isEqualTo("src/Foo.java");
        }

        @Test
        void exactDuplicate_deduplicated() {
            List<LineComment> comments =
                    List.of(
                            new LineComment("src/Foo.java", 5, "issue", "body"),
                            new LineComment("src/Foo.java", 5, "issue", "body"));
            ReviewResult r = review("s", "APPROVE", comments);
            assertThat(GitHubService.buildCommentArray(r).size()).isEqualTo(1);
        }

        @Test
        void distinctCommentsOnSameLine_bothIncluded() {
            List<LineComment> comments =
                    List.of(
                            new LineComment("src/Foo.java", 5, "issue", "first"),
                            new LineComment("src/Foo.java", 5, "note", "second"));
            ReviewResult r = review("s", "APPROVE", comments);
            assertThat(GitHubService.buildCommentArray(r).size()).isEqualTo(2);
        }

        @Test
        void commentNode_hasSideRight() {
            ReviewResult r =
                    review(
                            "s",
                            "APPROVE",
                            List.of(new LineComment("src/Foo.java", 5, "issue", "body")));
            assertThat(GitHubService.buildCommentArray(r).get(0).get("side").asText())
                    .isEqualTo("RIGHT");
        }
    }

    @Nested
    class RoundTrip {

        @Test
        void multipleComments_allPreserved() {
            List<LineComment> comments =
                    List.of(
                            new LineComment("src/Foo.java", 10, "issue", "null check"),
                            new LineComment("src/Bar.java", 20, "suggestion", "extract method"),
                            new LineComment("", 0, "note", "general note"));
            ReviewResult original = review("Full summary", "REQUEST_CHANGES", comments);
            String body = GitHubService.encodeBody(original);
            ReviewResult decoded = GitHubService.decodeReview(body, List.of());

            assertThat(decoded.getSummary()).isEqualTo("Full summary");
            assertThat(decoded.getVerdict()).isEqualTo("REQUEST_CHANGES");
            assertThat(decoded.getLineComments()).hasSize(3);

            assertThat(decoded.getLineComments())
                    .anySatisfy(
                            c -> {
                                assertThat(c.getFile()).isEqualTo("src/Foo.java");
                                assertThat(c.getLine()).isEqualTo(10);
                                assertThat(c.getType()).isEqualTo("issue");
                                assertThat(c.getBody()).isEqualTo("null check");
                            });
            assertThat(decoded.getLineComments())
                    .anySatisfy(
                            c -> {
                                assertThat(c.getFile()).isEqualTo("src/Bar.java");
                                assertThat(c.getLine()).isEqualTo(20);
                            });
            assertThat(decoded.getLineComments())
                    .anySatisfy(
                            c -> {
                                assertThat(c.getFile()).isEmpty();
                                assertThat(c.getLine()).isEqualTo(0);
                                assertThat(c.getBody()).isEqualTo("general note");
                            });
        }

        @Test
        void emptyComments_preservedAsEmpty() {
            ReviewResult original = review("Summary only", "APPROVE", List.of());
            String body = GitHubService.encodeBody(original);
            ReviewResult decoded = GitHubService.decodeReview(body, List.of());
            assertThat(decoded.getLineComments()).isEmpty();
            assertThat(decoded.getSummary()).isEqualTo("Summary only");
            assertThat(decoded.getVerdict()).isEqualTo("APPROVE");
        }
    }
}
